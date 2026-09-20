package io.spmp.impact.extract;

import java.util.List;
import java.util.Map;

/**
 * Flattened import record for a single Java compilation unit.
 *
 * <p><b>Why a flat record:</b> retaining JavaParser's {@code ImportDeclaration} AST nodes
 * keeps the entire {@code CompilationUnit} alive through any reference chain. With 4,351
 * files in scope, that pins ~200 MB of AST in heap throughout the resolution pass.
 * This record copies only the strings we need at construction time, then the CU is
 * eligible for GC as soon as Pass 1b drops its reference.
 *
 * <p>Resolution semantics (used by {@link CallResolver}):
 * <ul>
 *   <li>{@code simpleToFqn} — explicit imports: {@code import com.foo.Bar;} → {@code "Bar" -> "com.foo.Bar"}</li>
 *   <li>{@code wildcardPkgs} — wildcard imports: {@code import com.foo.*;} → {@code "com.foo"}</li>
 *   <li>{@code staticImports} — static imports: {@code import static com.foo.Bar.baz;} → {@code "baz" -> "com.foo.Bar"}</li>
 *   <li>{@code pkg} — the file's own package; classes in the same package resolve without explicit import</li>
 * </ul>
 *
 * <p>Java implicit imports ({@code java.lang.*}) are handled by {@link CallResolver}'s
 * JDK-reflection fallback, not stored here.
 */
public record FileImports(
    Map<String, String> simpleToFqn,
    List<String> wildcardPkgs,
    Map<String, String> staticImports,
    String pkg
) {
    public static final FileImports EMPTY =
        new FileImports(Map.of(), List.of(), Map.of(), "");

    /**
     * Resolve a simple class name (e.g. {@code "List"}) to its FQN using imports + same-package.
     * Returns {@code null} if no rule matches — caller falls back to JDK reflection or treats as unresolved.
     *
     * <p>Order: explicit import → single-wildcard import (only if exactly one wildcard) → same-package.
     * We deliberately don't auto-prepend {@code java.lang.} here — that's the caller's concern; this
     * keeps the resolver explicit about which strategy fired (for the debug log).
     */
    public String resolveSimpleName(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) return null;
        // 1. Explicit import wins.
        String exact = simpleToFqn.get(simpleName);
        if (exact != null) return exact;
        // 2. Wildcard import + JDK-registry match — pick the wildcard whose package contains this
        //    common JDK type. Resolves the long-standing "multiple wildcards" gap that previously
        //    fell through to same-package and produced phantom FQNs like
        //    {@code com.adventnet.sym.adsm.common.server.admin.HashMap} instead of
        //    {@code java.util.HashMap}.
        if (!wildcardPkgs.isEmpty()
            && !simpleName.isEmpty()
            && Character.isUpperCase(simpleName.charAt(0))) {
            String knownPkg = JDK_COMMON_PACKAGES.get(simpleName);
            if (knownPkg != null) {
                for (String w : wildcardPkgs) {
                    if (w.equals(knownPkg)) return knownPkg + "." + simpleName;
                }
            }
            // Single wildcard remains a safe heuristic — pick it.
            if (wildcardPkgs.size() == 1) return wildcardPkgs.get(0) + "." + simpleName;
        }
        // 3. JDK common-types fallback — covers the case where a JDK type is used WITHOUT
        //    an explicit import (uncommon outside lambda/method-reference call sites; happens
        //    when files rely on auto-import IDE features or have wildcards we can't unify).
        //    Must come BEFORE same-package or we mis-resolve {@code HashMap} → {@code <pkg>.HashMap}.
        if (!simpleName.isEmpty() && Character.isUpperCase(simpleName.charAt(0))) {
            String jdkPkg = JDK_COMMON_PACKAGES.get(simpleName);
            if (jdkPkg != null) return jdkPkg + "." + simpleName;
        }
        // 4. Same-package class.
        if (pkg != null && !pkg.isEmpty()
            && !simpleName.isEmpty()
            && Character.isUpperCase(simpleName.charAt(0))) {
            return pkg + "." + simpleName;
        }
        return null;
    }

    /**
     * Pre-computed registry of common JDK + javax + popular library simple-name → package.
     * Used as a fallback when a type name appears in source without an explicit import
     * (e.g. wildcard imports we can't unify, or transitively-imported types).
     * Order: java.lang (highest priority — always implicitly imported) → java.util → java.io
     * → java.util.concurrent → javax.servlet → org.json (heavily used by ADMP).
     */
    private static final java.util.Map<String, String> JDK_COMMON_PACKAGES = java.util.Map.ofEntries(
        // java.lang (implicit) — included for completeness; isJavaLangSimpleName covers most
        java.util.Map.entry("String",          "java.lang"),
        java.util.Map.entry("Object",          "java.lang"),
        java.util.Map.entry("Integer",         "java.lang"),
        java.util.Map.entry("Long",            "java.lang"),
        java.util.Map.entry("Boolean",         "java.lang"),
        java.util.Map.entry("Double",          "java.lang"),
        java.util.Map.entry("Float",           "java.lang"),
        java.util.Map.entry("Byte",            "java.lang"),
        java.util.Map.entry("Short",           "java.lang"),
        java.util.Map.entry("Character",       "java.lang"),
        java.util.Map.entry("Number",          "java.lang"),
        java.util.Map.entry("Math",            "java.lang"),
        java.util.Map.entry("System",          "java.lang"),
        java.util.Map.entry("Thread",          "java.lang"),
        java.util.Map.entry("Throwable",       "java.lang"),
        java.util.Map.entry("Exception",       "java.lang"),
        java.util.Map.entry("RuntimeException","java.lang"),
        java.util.Map.entry("Error",           "java.lang"),
        java.util.Map.entry("Class",           "java.lang"),
        java.util.Map.entry("Void",            "java.lang"),
        java.util.Map.entry("Iterable",        "java.lang"),
        java.util.Map.entry("Comparable",      "java.lang"),
        java.util.Map.entry("CharSequence",    "java.lang"),
        java.util.Map.entry("StringBuilder",   "java.lang"),
        java.util.Map.entry("StringBuffer",    "java.lang"),
        // java.util — the most common source of the bug
        java.util.Map.entry("HashMap",         "java.util"),
        java.util.Map.entry("LinkedHashMap",   "java.util"),
        java.util.Map.entry("TreeMap",         "java.util"),
        java.util.Map.entry("Map",             "java.util"),
        java.util.Map.entry("AbstractMap",     "java.util"),
        java.util.Map.entry("ArrayList",       "java.util"),
        java.util.Map.entry("LinkedList",      "java.util"),
        java.util.Map.entry("List",            "java.util"),
        java.util.Map.entry("AbstractList",    "java.util"),
        java.util.Map.entry("Vector",          "java.util"),
        java.util.Map.entry("Stack",           "java.util"),
        java.util.Map.entry("HashSet",         "java.util"),
        java.util.Map.entry("LinkedHashSet",   "java.util"),
        java.util.Map.entry("TreeSet",         "java.util"),
        java.util.Map.entry("Set",             "java.util"),
        java.util.Map.entry("AbstractSet",     "java.util"),
        java.util.Map.entry("Collection",      "java.util"),
        java.util.Map.entry("AbstractCollection","java.util"),
        java.util.Map.entry("Iterator",        "java.util"),
        java.util.Map.entry("Enumeration",     "java.util"),
        java.util.Map.entry("Hashtable",       "java.util"),
        java.util.Map.entry("Properties",      "java.util"),
        java.util.Map.entry("Date",            "java.util"),
        java.util.Map.entry("Calendar",        "java.util"),
        java.util.Map.entry("Optional",        "java.util"),
        java.util.Map.entry("Arrays",          "java.util"),
        java.util.Map.entry("Collections",     "java.util"),
        java.util.Map.entry("Objects",         "java.util"),
        java.util.Map.entry("Comparator",      "java.util"),
        java.util.Map.entry("UUID",            "java.util"),
        // java.io
        java.util.Map.entry("File",            "java.io"),
        java.util.Map.entry("InputStream",     "java.io"),
        java.util.Map.entry("OutputStream",    "java.io"),
        java.util.Map.entry("Reader",          "java.io"),
        java.util.Map.entry("Writer",          "java.io"),
        java.util.Map.entry("BufferedReader",  "java.io"),
        java.util.Map.entry("BufferedWriter",  "java.io"),
        java.util.Map.entry("FileReader",      "java.io"),
        java.util.Map.entry("FileWriter",      "java.io"),
        java.util.Map.entry("FileInputStream", "java.io"),
        java.util.Map.entry("FileOutputStream","java.io"),
        java.util.Map.entry("PrintStream",     "java.io"),
        java.util.Map.entry("PrintWriter",     "java.io"),
        java.util.Map.entry("IOException",     "java.io"),
        java.util.Map.entry("Serializable",    "java.io"),
        // java.util.concurrent
        java.util.Map.entry("ConcurrentHashMap","java.util.concurrent"),
        java.util.Map.entry("ConcurrentMap",   "java.util.concurrent"),
        java.util.Map.entry("ExecutorService", "java.util.concurrent"),
        java.util.Map.entry("Executors",       "java.util.concurrent"),
        java.util.Map.entry("Future",          "java.util.concurrent"),
        java.util.Map.entry("Callable",        "java.util.concurrent"),
        java.util.Map.entry("TimeUnit",        "java.util.concurrent"),
        // javax.servlet.http (heavily used by ADSM)
        java.util.Map.entry("HttpServlet",         "javax.servlet.http"),
        java.util.Map.entry("HttpServletRequest",  "javax.servlet.http"),
        java.util.Map.entry("HttpServletResponse", "javax.servlet.http"),
        java.util.Map.entry("HttpSession",         "javax.servlet.http"),
        java.util.Map.entry("Cookie",              "javax.servlet.http"),
        // javax.servlet
        java.util.Map.entry("ServletException", "javax.servlet"),
        java.util.Map.entry("ServletContext",   "javax.servlet"),
        java.util.Map.entry("ServletConfig",    "javax.servlet"),
        // org.json (heavily used by ADMP / ADSM)
        java.util.Map.entry("JSONObject",      "org.json"),
        java.util.Map.entry("JSONArray",       "org.json"),
        java.util.Map.entry("JSONException",   "org.json"),
        // java.text
        java.util.Map.entry("MessageFormat",   "java.text"),
        java.util.Map.entry("SimpleDateFormat","java.text"),
        java.util.Map.entry("DateFormat",      "java.text"),
        // java.util.logging
        java.util.Map.entry("Logger",          "java.util.logging"),
        java.util.Map.entry("Level",           "java.util.logging")
    );
}
