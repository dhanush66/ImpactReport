package io.spmp.impact.testgen.generate;

import java.util.List;
import java.util.Set;

/**
 * Heuristic translation of Java identifiers into plain-English fragments used in
 * generated test-case titles and steps.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code "DistributedTaskCoordinator"} → "Distributed Task Coordinator"</li>
 *   <li>{@code "GrantPermissionTaskHandler"} → "Grant Permission" (stripped {@code TaskHandler})</li>
 *   <li>{@code "submitTask"} → "Submit a task"</li>
 *   <li>{@code "doPost"} → "submit the form"</li>
 *   <li>{@code "runTask"} → "Run a task"</li>
 * </ul>
 */
public final class EnglishTranslator {
    private EnglishTranslator() {}

    private static final Set<String> STRIP_SUFFIXES = Set.of(
        "TaskHandler", "Handler", "Servlet", "Controller", "Impl", "Scheduler",
        "ScheduleHandler", "DataCollector", "Generator", "Util", "Helper", "Manager"
    );

    private static final java.util.Map<String, String> VERB_PHRASES = java.util.Map.ofEntries(
        java.util.Map.entry("submitTask",  "Submit a task"),
        java.util.Map.entry("runTask",     "Run a task"),
        java.util.Map.entry("startTask",   "Start a task"),
        java.util.Map.entry("resumeTask",  "Resume a task"),
        java.util.Map.entry("cancelTask",  "Cancel a task"),
        java.util.Map.entry("executeTask", "Execute the scheduled task"),
        java.util.Map.entry("doGet",       "load the page"),
        java.util.Map.entry("doPost",      "submit the form"),
        java.util.Map.entry("doPut",       "update via the API"),
        java.util.Map.entry("doDelete",    "delete via the API"),
        java.util.Map.entry("execute",     "run"),
        java.util.Map.entry("run",         "run"),
        java.util.Map.entry("onMessage",   "Process an incoming cluster message"),
        java.util.Map.entry("postCreateCluster", "Set up a new cluster"),
        java.util.Map.entry("postAddNewNodes",   "Add new nodes to the cluster"),
        java.util.Map.entry("postRemoveNode",    "Remove a node from the cluster"),
        java.util.Map.entry("collectDistributed", "Collect report data from all nodes"),
        java.util.Map.entry("collectLocal",       "Collect report data on this node"),
        java.util.Map.entry("aggregateResults",   "Aggregate distributed task results"),
        java.util.Map.entry("getExecutor",        "Pick the right task executor"),
        java.util.Map.entry("buildDistributedRequest", "Build the distributed task request"),
        java.util.Map.entry("extractSiteUrls",    "Extract the per-site work list"),
        java.util.Map.entry("checkTimeouts",      "Check for timed-out task assignments"),
        java.util.Map.entry("drainPendingQueue",  "Drain the pending-task queue"),
        java.util.Map.entry("cancelPendingTask",  "Cancel a queued (not-yet-started) task"),
        java.util.Map.entry("startSchedules",     "Start the cluster heartbeat schedules"),
        java.util.Map.entry("stopSchedulesIfIdle", "Stop heartbeat schedules when idle"),
        java.util.Map.entry("onNodeDeath",        "Handle a node-death event"),
        java.util.Map.entry("cleanupOldBatches",  "Clean up stale task batches")
    );

    /** "DistributedTaskCoordinator" → "Distributed Task Coordinator". Strips known suffixes. */
    public static String className(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) return simpleName;
        String stripped = stripSuffix(simpleName);
        // Drop common ADMP/ADSM project-internal prefixes — they're noise for QA readers.
        stripped = stripPrefix(stripped);
        if (stripped.isEmpty()) stripped = simpleName;   // never go negative
        return splitCamel(stripped);
    }

    /**
     * Build a feature name from a full class FQN. When the bare simple name is too generic
     * (e.g., {@code Action}, {@code Listener}, {@code Handler}, {@code Util}) — which happens
     * when distinct classes from different packages share a short common name — fall back to
     * combining the package's last segment with the class name to keep test-case titles
     * distinguishable.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code com.foo.api.ADMPAPIAction} → "ADMPAPI Action" (already specific)</li>
     *   <li>{@code com.foo.bulkmgmt.Action} → "Bulk Mgmt Action" (combine pkg.tail + name)</li>
     *   <li>{@code com.foo.workflow.IMModifyUser} → "Workflow — IM Modify User" (prefix drop)</li>
     * </ul>
     */
    public static String featureFromFqn(String fqn) {
        if (fqn == null || fqn.isEmpty()) return "the feature";
        int dot = fqn.lastIndexOf('.');
        String simple = dot < 0 ? fqn : fqn.substring(dot + 1);
        String pkgTail = "";
        boolean tailIsOuterClass = false;
        if (dot > 0) {
            String pkg = fqn.substring(0, dot);
            int prevDot = pkg.lastIndexOf('.');
            pkgTail = prevDot < 0 ? pkg : pkg.substring(prevDot + 1);
            // Heuristic: if pkgTail starts with uppercase, it's actually an OUTER class
            // (nested class FQN: "com.foo.Outer.Inner"). Java package conventions are
            // lowercase, so an uppercase tail signals a nested-type situation.
            tailIsOuterClass = !pkgTail.isEmpty() && Character.isUpperCase(pkgTail.charAt(0));
        }
        if (tailIsOuterClass) {
            // For a nested class, prefer "Outer.Inner" as one feature name. The "Inner" alone
            // is often too generic (Action, Worker, Builder) — qualifying it with the outer
            // class makes test titles distinct without dragging the whole package in.
            return className(pkgTail) + " — " + className(simple);
        }
        String classFeature = className(simple);
        if (isGenericName(simple)) {
            String pkgPretty = splitCamel(capitalise(pkgTail));
            if (!pkgPretty.isEmpty()) return pkgPretty + " " + classFeature;
        }
        return classFeature;
    }

    private static boolean isGenericName(String simple) {
        if (simple == null) return false;
        String stripped = stripSuffix(stripPrefix(simple));
        // After stripping known prefixes/suffixes, anything ≤ 6 chars is "generic enough" to need pkg context.
        return stripped.length() <= 6
            || "Action".equals(simple) || "Listener".equals(simple) || "Handler".equals(simple)
            || "Util".equals(simple) || "Helper".equals(simple) || "Manager".equals(simple)
            || "Servlet".equals(simple) || "Job".equals(simple) || "Task".equals(simple);
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Drop common ADMP/ADSM/SPMP class-name prefixes. {@code Fc} is project-internal and
     * means "FormCluster"-style component naming — drops cleanly to a more readable name.
     * (e.g., {@code FcUserModificationListener} → {@code UserModificationListener})
     */
    private static String stripPrefix(String simpleName) {
        if (simpleName == null) return simpleName;
        if (simpleName.startsWith("Fc") && simpleName.length() > 2 && Character.isUpperCase(simpleName.charAt(2))) {
            return simpleName.substring(2);
        }
        return simpleName;
    }

    /** "submitTask" → "Submit a task" (looked up first), or fallback "Submit task". */
    public static String methodPhrase(String methodSimpleName) {
        if (methodSimpleName == null || methodSimpleName.isEmpty()) return "";
        String phrase = VERB_PHRASES.get(methodSimpleName);
        if (phrase != null) return phrase;
        String words = splitCamel(methodSimpleName);
        if (words.isEmpty()) return methodSimpleName;
        // Capitalise first letter for use as a sentence start.
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /** Split CamelCase into space-separated words: "FooBarBaz" → "Foo Bar Baz". */
    public static String splitCamel(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (i > 0 && Character.isUpperCase(c)
                && (Character.isLowerCase(s.charAt(i - 1)) || (i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1))))) {
                sb.append(' ');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Strip known suffixes once (e.g., GrantPermissionTaskHandler → GrantPermission). */
    public static String stripSuffix(String simpleName) {
        for (String suf : STRIP_SUFFIXES) {
            if (simpleName.length() > suf.length() && simpleName.endsWith(suf)) {
                return simpleName.substring(0, simpleName.length() - suf.length());
            }
        }
        return simpleName;
    }

    /** Format a comma-list with "and" before the last item. */
    public static String oxfordList(List<String> items) {
        if (items == null || items.isEmpty()) return "";
        if (items.size() == 1) return items.get(0);
        if (items.size() == 2) return items.get(0) + " and " + items.get(1);
        return String.join(", ", items.subList(0, items.size() - 1)) + ", and " + items.get(items.size() - 1);
    }
}
