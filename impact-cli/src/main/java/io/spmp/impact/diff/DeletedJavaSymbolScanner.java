package io.spmp.impact.diff;

import io.spmp.impact.model.DiffModels.ChangedSymbol;
import io.spmp.impact.model.DiffModels.ChangedSymbol.ChangeNature;
import io.spmp.impact.model.DiffModels.ChangedSymbol.Kind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort scanner for Java symbols deleted by a unified-diff {@code .patch} file.
 *
 * <p>The graph reflects the post-patch state — anything the patch removed is no longer a
 * {@code :Method} or {@code :Class} node. So a pure graph-driven slice can't surface
 * deleted code paths. This scanner reads the raw {@code -} lines from the patch, extracts
 * any line that matches a Java method, constructor, class, interface, or enum signature,
 * and emits a {@link ChangedSymbol} with {@link ChangeNature#DELETED}. Downstream code can
 * then surface them in the report as a warning panel ("methods you removed — check callers").
 *
 * <p><b>Limitations:</b> regex-only — we can't fully type-resolve parameters, so the FQN
 * may be slightly fuzzy (no generic erasure handling). False positives are filtered by
 * requiring a recognisable Java declaration syntax. Inner-class FQNs use the containing
 * file's package + outermost class name.
 */
public final class DeletedJavaSymbolScanner {

    // ─── Java-declaration regexes (deliberately strict to keep noise low) ──

    /** Method declaration heuristic: modifiers? return-type (qualified or array) name (params) ... */
    private static final Pattern METHOD_RE = Pattern.compile(
        "^\\s*" +
        "(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)\\s+)*" +
        "(?:<[^>]+>\\s+)?" +                                        // optional generic
        "(?:[A-Za-z_$][A-Za-z0-9_$<>\\[\\],?\\s\\.]*?)\\s+" +       // return type
        "([A-Za-z_$][A-Za-z0-9_$]*)\\s*" +                          // method name (group 1)
        "\\(([^)]*)\\)\\s*" +                                        // parameter list (group 2)
        "(?:throws\\s+[A-Za-z_$][\\w$\\.,\\s]*)?\\s*" +
        "[{;]"
    );

    /** Constructor: ClassName(...) at the top of a class body. */
    private static final Pattern CTOR_RE = Pattern.compile(
        "^\\s*" +
        "(?:(?:public|private|protected)\\s+)?" +
        "([A-Za-z_$][A-Za-z0-9_$]*)\\s*" +                          // name (group 1)
        "\\(([^)]*)\\)\\s*" +                                        // params (group 2)
        "(?:throws\\s+[A-Za-z_$][\\w$\\.,\\s]*)?\\s*" +
        "[{;]"
    );

    /** Class / interface / enum / record. */
    private static final Pattern CLASS_RE = Pattern.compile(
        "^\\s*" +
        "(?:(?:public|private|protected|static|final|abstract)\\s+)*" +
        "(class|interface|enum|record)\\s+" +
        "([A-Za-z_$][A-Za-z0-9_$]*)"
    );

    /** Package declaration so we can build a proper FQN. */
    private static final Pattern PACKAGE_RE = Pattern.compile("^\\s*package\\s+([A-Za-z_$][\\w$\\.]*)\\s*;");

    private DeletedJavaSymbolScanner() {}

    /**
     * Scan the patch for deleted Java symbols. Returns one {@link ChangedSymbol} per
     * detected declaration with {@link ChangeNature#DELETED}.
     */
    public static List<ChangedSymbol> scan(Path patchFile) throws IOException {
        List<ChangedSymbol> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(patchFile, StandardCharsets.UTF_8);

        String currentOldPath = null;
        String currentNewPath = null;
        boolean isDeleteFile = false;
        boolean inFileBlock = false;
        String currentPackage = null;
        int oldLine = 0;     // 1-based line number in pre-image (track via @@ headers)

        for (int idx = 0; idx < lines.size(); idx++) {
            String line = lines.get(idx);

            // Per-file header
            if (line.startsWith("diff --git ")) {
                currentOldPath = null;
                currentNewPath = null;
                isDeleteFile = false;
                currentPackage = null;
                oldLine = 0;
                inFileBlock = false;
                continue;
            }
            if (line.startsWith("--- a/") || line.startsWith("--- \"a/")) {
                currentOldPath = stripPathPrefix(line.substring(4));
                inFileBlock = true;
                continue;
            }
            if (line.startsWith("+++ b/") || line.startsWith("+++ \"b/")) {
                currentNewPath = stripPathPrefix(line.substring(4));
                continue;
            }
            if (line.startsWith("--- /dev/null")) {
                currentOldPath = null;   // pure ADD — no deletions to detect
                continue;
            }
            if (line.startsWith("+++ /dev/null")) {
                isDeleteFile = true;
                continue;
            }
            if (line.startsWith("deleted file mode")) {
                isDeleteFile = true;
                continue;
            }
            if (!inFileBlock) continue;

            // Hunk header: @@ -a,b +c,d @@
            if (line.startsWith("@@ ")) {
                int dashIdx = line.indexOf('-');
                int spaceIdx = line.indexOf(' ', dashIdx);
                if (dashIdx >= 0 && spaceIdx > dashIdx) {
                    String aRange = line.substring(dashIdx + 1, spaceIdx);
                    int comma = aRange.indexOf(',');
                    String startStr = comma > 0 ? aRange.substring(0, comma) : aRange;
                    try { oldLine = Integer.parseInt(startStr.trim()); } catch (NumberFormatException ignored) {}
                }
                continue;
            }

            // We only care about Java files for the v1 deleted-symbol surfacing.
            String filterPath = isDeleteFile ? currentOldPath : (currentOldPath != null ? currentOldPath : currentNewPath);
            if (filterPath == null || !filterPath.endsWith(".java")) {
                // still tick old-line counters even for non-Java to stay synced
                if (!line.isEmpty()) {
                    char c = line.charAt(0);
                    if (c == ' ' || c == '-') oldLine++;
                }
                continue;
            }

            if (line.isEmpty()) continue;
            char first = line.charAt(0);

            // Context lines stay on both sides; track old-line counter.
            if (first == ' ') {
                String content = line.length() > 1 ? line.substring(1) : "";
                Matcher pm = PACKAGE_RE.matcher(content);
                if (pm.find()) currentPackage = pm.group(1);
                oldLine++;
                continue;
            }
            if (first == '+') {
                // Post-image only — does not contribute to deletion analysis
                continue;
            }
            if (first == '-') {
                String content = line.length() > 1 ? line.substring(1) : "";
                // Update package if we see one in deleted lines
                Matcher pm = PACKAGE_RE.matcher(content);
                if (pm.find()) currentPackage = pm.group(1);

                // Try class/method/ctor matches. Order matters — class first.
                ChangedSymbol cs = matchDeletedDeclaration(content, currentOldPath, currentPackage, oldLine);
                if (cs != null) out.add(cs);
                oldLine++;
            }
        }
        return dedupe(out);
    }

    /** Detect a Java declaration in the deleted line; returns null if not a declaration. */
    private static ChangedSymbol matchDeletedDeclaration(String content, String filePath, String pkg, int line) {
        if (filePath == null) return null;
        String topClass = topClassFromPath(filePath);

        Matcher mc = CLASS_RE.matcher(content);
        if (mc.find()) {
            String kw = mc.group(1);
            String name = mc.group(2);
            Kind kind = "interface".equals(kw) ? Kind.INTERFACE : Kind.CLASS;
            String fqn = (pkg == null ? "" : pkg + ".") + name;
            return new ChangedSymbol(filePath, fqn, kind, line, line, ChangeNature.DELETED);
        }
        // Try constructor FIRST so "public Demo(...)" isn't swallowed by the
        // method-regex which treats "public" as the return type.
        Matcher mctor = CTOR_RE.matcher(content);
        if (mctor.find()) {
            String name = mctor.group(1);
            if (name.equals(topClass)) {
                String paramTypes = simplifyParams(mctor.group(2));
                String owner = (pkg == null ? "" : pkg + ".") + topClass;
                String fqn = owner + ".<init>(" + paramTypes + ")";
                return new ChangedSymbol(filePath, fqn, Kind.CONSTRUCTOR, line, line, ChangeNature.DELETED);
            }
        }
        Matcher mm = METHOD_RE.matcher(content);
        if (mm.find()) {
            String name = mm.group(1);
            if (isJavaKeyword(name) || isControlKeyword(name)) return null;
            if (name.equals(topClass)) return null;   // constructor (already handled above)
            String paramTypes = simplifyParams(mm.group(2));
            String owner = (pkg == null ? "" : pkg + ".") + topClass;
            String fqn = owner + "." + name + "(" + paramTypes + ")";
            return new ChangedSymbol(filePath, fqn, Kind.METHOD, line, line, ChangeNature.DELETED);
        }
        return null;
    }

    /** Reduce a parameter list to simplified type names so the FQN is reasonably stable. */
    private static String simplifyParams(String params) {
        if (params == null) return "";
        String s = params.trim();
        if (s.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String raw : s.split(",")) {
            String p = raw.trim();
            int sp = p.lastIndexOf(' ');
            if (sp > 0) p = p.substring(0, sp).trim();   // drop the param name; keep the type
            // Drop annotations like "@Nullable"
            if (p.startsWith("@")) {
                int firstSpace = p.indexOf(' ');
                if (firstSpace > 0) p = p.substring(firstSpace + 1).trim();
            }
            if (out.length() > 0) out.append(',');
            out.append(p);
        }
        return out.toString();
    }

    private static String topClassFromPath(String filePath) {
        int slash = Math.max(filePath.lastIndexOf('/'), filePath.lastIndexOf('\\'));
        String fn = slash < 0 ? filePath : filePath.substring(slash + 1);
        int dot = fn.lastIndexOf('.');
        return dot < 0 ? fn : fn.substring(0, dot);
    }

    private static String stripPathPrefix(String s) {
        // Strip quotes around paths with special chars
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length() - 1);
        if (t.startsWith("a/")) t = t.substring(2);
        if (t.startsWith("b/")) t = t.substring(2);
        // Tab-separated trailing modification timestamp etc. — strip everything after the first tab
        int tab = t.indexOf('\t');
        return tab >= 0 ? t.substring(0, tab) : t;
    }

    private static boolean isJavaKeyword(String s) {
        return switch (s) {
            case "if", "else", "for", "while", "do", "switch", "return", "throw", "try", "catch",
                 "finally", "synchronized", "new", "this", "super", "case", "break", "continue",
                 "default", "instanceof", "yield", "void" -> true;
            default -> false;
        };
    }
    private static boolean isControlKeyword(String s) {
        return switch (s) {
            case "if", "for", "while", "switch", "catch", "return" -> true;
            default -> false;
        };
    }

    /** De-duplicate (same fqn + same kind) keeping the first occurrence. */
    private static List<ChangedSymbol> dedupe(List<ChangedSymbol> in) {
        java.util.LinkedHashMap<String, ChangedSymbol> seen = new java.util.LinkedHashMap<>();
        for (ChangedSymbol cs : in) {
            String key = cs.fqn() + "|" + cs.kind();
            seen.putIfAbsent(key, cs);
        }
        return new ArrayList<>(seen.values());
    }
}
