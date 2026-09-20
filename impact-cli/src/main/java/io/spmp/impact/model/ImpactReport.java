package io.spmp.impact.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import io.spmp.impact.model.ImpactReport.AffectedApi;
import io.spmp.impact.model.ImpactReport.EntryPointRef;

/**
 * Top-level impact analysis result, suitable for JSON serialization and FreeMarker rendering.
 */
public record ImpactReport(
    String generated,                       // ISO-8601 timestamp
    String diffSource,                      // "patch:/abs/path.patch" or "git:<base>..<head>"
    String repoPath,
    int totalChangedSymbols,
    int totalEntryPointsApi,
    int totalEntryPointsSchedule,
    //int totalForwardReach,
    Map<String, Long> kindCounts,           // e.g. {"METHOD/ADDED": 323, "CLASS/ADDED": 41}
    Map<String, Long> riskCounts,           // {"HIGH": 12, "MEDIUM": 30, "LOW": 387}
    String overallRisk,                     // HIGH|MEDIUM|LOW = max symbol risk
    List<SymbolImpact> symbols,
    LayerImpact layerImpact,                // Layer1 — cross-component impact aggregation
    List<PolyglotChange> polyglotChanges,   // P-Polyglot-Diff — JS/HBS/C#/XML/properties hunks in the diff
    // ── AFF: pre-aggregated affected-X lists for the top of the report ──
    List<AffectedApi> apisAffected,
    List<AffectedApi> schedulesAffected,
    //List<AffectedSchedule> schedulesAffected,
    List<AffectedDbTable> dbTablesAffected
) {

    /**
     * Cross-component aggregation of everything the patch can affect. One instance
     * per analysis run. Rendered as the "Impact by Layer" section of the HTML report
     * and the equivalent table in the Markdown export.
     */
    public record LayerImpact(
     // capped sample
        int dbTablesTouched,
        int dbTablesWritten,
        int dbTablesRead,
        List<String> dbTableNames,             // capped sample
        int dbColumnsOnAffectedTables
    ) {}

    /**
     * Polyglot enrichment for a single entry-point owner class, used by the test-case
     * generator to name concrete UI components / C# counterparts / .ps1 scripts in
     * the Steps / Expected columns.
     */
    public record OwnerEnrichment(
        List<String> jsCallers,        // Ember components / routes that call this owner's REST URLs
        List<String> csCounterparts,   // C# class simple names with a similar name to this owner
        List<String> psScripts,        // .ps1 filenames invoked from this owner's methods (Java side)
        List<String> restUrls,         // canonical REST URLs this owner exposes (best-known)
        List<String> hbsTemplates      // L8 — HBS templates that {{use-component}} a JS caller of this owner
    ) {}

    public record CoverageSummary(
        int testCasesAvailable,             // total :TestCase nodes in graph
        int coveringTestCases,              // distinct test cases recommended for this diff
        int coverageGaps,                   // distinct entry points reached with no covering test case
        List<CoveringTestCase> recommended,
        List<CoverageGap> gaps
    ) {}

    public record CoveringTestCase(
        String id,
        String title,
        String area,
        double confidence,
        int reachedEntryPoints              // how many reached entry points this TC covers
    ) {}

    public record CoverageGap(
        String entryPointFqn,
        List<String> labels,
        String owner,
        List<String> reachingChanges        // FQNs of changed methods that reach this gap
    ) {}

    public record SymbolImpact(
        String fqn,
        String kind,                        // METHOD | CONSTRUCTOR | CLASS | INTERFACE
        String nature,                      // ADDED | SIGNATURE | BODY
        String filePath,
        int startLine,
        int endLine,
        String risk,                        // HIGH | MEDIUM | LOW
        boolean sensitivePackage,
        int entryPointCount,
        List<EntryPointRef> entryPointsApi,
        List<EntryPointRef> entryPointsSchedule,
        //List<String> forwardReach,
        //List<String> readsTables,
        //List<String> writesTables,
        // Actual diff-hunk line range INSIDE the enclosing symbol. e.g. a one-line
        // change at L2490 inside a method spanning L2299-L2719 surfaces as
        // (startLine=2299, endLine=2719, hunkStartLine=2490, hunkEndLine=2490). This is
        // what QA / reviewers actually want to look at; the symbol range is just
        // context. Defaults to (startLine, endLine) when the upstream caller didn't
        // track a finer hunk.
        int hunkStartLine,
        int hunkEndLine

    ) {
        /** Back-compat constructor: defaults the hunk range to the symbol range. */
        public SymbolImpact(
            String fqn, String kind, String nature, String filePath,
            int startLine, int endLine, String risk, boolean sensitivePackage,
            int entryPointCount, List<EntryPointRef> entryPointsApi, List<EntryPointRef> entryPointsSchedule
        ) {
            this(fqn, kind, nature, filePath, startLine, endLine, risk, sensitivePackage,
                 entryPointCount, entryPointsApi, entryPointsSchedule,
                 startLine, endLine);
        }

    }

    public record EntryPointRef(
        String fqn,
        List<String> labels,                // ["EntryPoint", "Method"] or ["ScheduledTask", "Method"]
        String owner,
        List<String> restUrls
    ) {}

    /**
     * A non-Java file touched by the patch. Captures both the file-level change and
     * graph-resolved downstream effects (REST URLs called by a JS file, DB tables
     * named in a data-dictionary.xml hunk, etc.) so the polyglot impact is visible
     * even though the file isn't part of the call-graph.
     */
    public record PolyglotChange(
        String filePath,                       // "source/ember/app/components/ads-select-input.js"
        String language,                       // "JS" | "HBS" | "CS" | "XML" | "PROPERTIES" | "JSON" | "OTHER"
        String role,                           // for ember: "Component"/"Route"/"Controller"; for XML: "DataDictionary"/"RestApi"/"ServletActions"/"Other"; else ""
        String changeType,                     // "ADD" | "MODIFY" | "DELETE" | "RENAME" | "COPY"
        int hunkCount,
        List<String> restUrlsCalled,           // JS/C#: URLs the file calls (via :CALLS_API)
        List<String> javaClassesExposing,      // Java classes whose REST URLs the JS/C# calls (via :EXPOSES)
        List<String> dbTablesAffected,         // XML/data-dictionary: tables named in hunks
        List<String> dbColumnsAffected,        // XML/data-dictionary: "table.column" pairs in hunks
        List<String> restEndpointsAffected,    // XML/ADSProductAPIS: URLs declared in hunks
        List<String> javaCallersOfThisFile,    // JS: Java classes that this JS file talks to (capped sample)
        String risk,                           // HIGH/MEDIUM/LOW — escalated to HIGH if anything downstream is HIGH-risk Java
        // ── PD-2: graph-resolved consequences of the named symbols ──
        List<String> writersOfAffectedTables,  // XML/DB: Java methods that WRITE the tablesAffected (sampled)
        List<String> readersOfAffectedTables,  // XML/DB: Java methods that READ the tablesAffected (sampled)
        // ── PD-3: per-hunk symbol granularity for JS / C# ──
        List<HunkSymbol> changedSymbolsInFile   // resolved via tree-sitter for JS/C#; empty for other langs
    ) {}

    /**
     * A single node in a discovery graph-path visualization.
     */
    public record PathNode(
        String nodeType,    // "Method", "Class", "RestEndpoint", "Notification", "Macro", "CsFile", "XmlConfig"
        String name,        // short display name (e.g. "approveRequest()", "WorkFlowAction", "/RestAPI/WC/...")
        boolean highlighted // true if this is the affected/changed symbol
    ) {}

    /**
     * Describes how a particular affected finding was discovered: a chain of graph nodes
     * and edges from the changed method to the affected entity.
     * Rendered as a Cypher-like path: (Type:name)-[:EDGE]->(Type:name)-[:EDGE]->...
     * Hidden by default in the report; shown when the user expands the details.
     */
    public record DiscoveryPath(
        String pattern,         // "forward-slice" | "owner-class-exposes" | "backward-reach" | "xml-declared" | "c#-calls" | "forward-reach"
        List<PathNode> nodes,   // ordered nodes in the path (first = source, last = target)
        List<String> edges      // edge labels between consecutive nodes (size = nodes.size() - 1)
    ) {}

    /**
     * One REST endpoint affected by the patch — either declared/changed in an XML hunk,
     * or exposed by a Java class that the call-graph slice reaches from a changed method.
     */
    public record AffectedApi(
        String url,                          // "/RestAPI/WC/Clustering/updateSchedulerNode"
        String source,                       // "patched-method" | "xml-declared" | "patched+xml" | "c#-touches" etc.
        String ownerClassFqn,                // class that exposes this URL (or "" if unknown)
        String ownerSimpleName,              // simple name of the owner class
        int changedSymbolsReaching,          // # changed Java methods that reach this URL (via slice)
        String risk,                         // HIGH/MEDIUM/LOW
        List<DiscoveryPath> discoveryPaths   // how this URL was discovered (hidden by default; expand to see)
    ) {
        /** Back-compat 12-arg constructor: defaults discoveryPaths to empty list. */
        public AffectedApi(
            String url, String source, String ownerClassFqn, String ownerSimpleName,
            int changedSymbolsReaching, String risk
        ) {
            this(url, source, ownerClassFqn, ownerSimpleName, changedSymbolsReaching, risk, List.of());
        }

    }



    /**
     * Lightweight reference to a user-visible feature derived from a Java class.
     * The goal: give non-technical testers a name they recognise (e.g. "Grant Permission",
     * "List Items Report", "Add Site Collection Administrator") instead of a Java FQN.
     */
    public record FeatureRef(
        String kind,                         // "TaskType" | "Report" | "Action" | "Scheduler" | "Job" | "EntryPoint"
        String id,                           // raw id: "GrantPermission" / "ListItemReportGenerator" / "AddSiteCollectionAdmin"
        String displayName                   // English-translated name: "Grant Permission" / "List Item Report"
    ) {}

    /**
     * One DB table touched by the patch — promoted out of LayerImpact summary to a
     * dedicated section. Lists the columns the patch declares (data-dictionary.xml hunks)
     * AND the Java methods that already read/write the table.
     */
    public record AffectedDbTable(
        String name,                         // "DistributedTaskBatches"
        String source,                       // "schema-changed" | "java-reached" | "both"
        List<String> changedColumns,         // columns declared in a data-dictionary.xml hunk (empty for java-reached only)
        int writerCount,
        int readerCount,
        List<String> sampleWriters,          // capped sample of "owner.method()" strings
        List<String> sampleReaders,
        List<FeatureRef> features,           // features attribution derived from writers/readers
        String risk,
        List<DiscoveryPath> discoveryPaths
    ) {
        /** Back-compat ctor without discoveryPaths. */
        public AffectedDbTable(String name, String source, List<String> changedColumns,
                               int writerCount, int readerCount, List<String> sampleWriters,
                               List<String> sampleReaders, List<FeatureRef> features, String risk) {
            this(name, source, changedColumns, writerCount, readerCount, sampleWriters,
                 sampleReaders, features, risk, List.of());
        }
    }

    /**
     * One scheduler / scheduled job / task handler reached by the patch. These represent
     * "background things that will run differently after the patch".
     */
    public record AffectedSchedule(
        String ownerClassFqn,
        String ownerSimpleName,
        String kind,                         // "Scheduler" | "Job" | "TaskHandler"
        String source,                       // "patch-added" | "patch-modified" | "reached"
        List<String> dbTablesWritten,        // tables this schedule's methods write
        List<String> dbTablesRead,
        int changedSymbolsReaching,
        String risk,
        List<DiscoveryPath> discoveryPaths
    ) {
        /** Back-compat ctor without discoveryPaths. */
        public AffectedSchedule(String ownerClassFqn, String ownerSimpleName, String kind, String source,
                                List<String> dbTablesWritten, List<String> dbTablesRead,
                                int changedSymbolsReaching, String risk) {
            this(ownerClassFqn, ownerSimpleName, kind, source,
                 dbTablesWritten, dbTablesRead, changedSymbolsReaching, risk, List.of());
        }
    }

    // ─── §4.1 affected-X records (one per category) ─────────────────────────
    // All eight follow the same shape: an identifier, a sample of the methods the
    // slice reaches that emit this edge, a count for sorting, and a risk pill.
    // QA reads each section the same way: "what's affected, who reaches it, how bad".

    /** N1: notification kind reached by the slice (constant id, e.g. WF_REQUEST_REJECTED). */
    public record AffectedNotification(
        String id,
        int changedSymbolsReaching,
        List<String> sampleEmittingMethods,
        String risk,
        List<DiscoveryPath> discoveryPaths
    ) {
        /** Back-compat ctor without discoveryPaths. */
        public AffectedNotification(String id, int changedSymbolsReaching,
                                    List<String> sampleEmittingMethods, String risk) {
            this(id, changedSymbolsReaching, sampleEmittingMethods, risk, List.of());
        }
    }

    /** N3: audit category reached by the slice. */
    public record AffectedAudit(
        String id,
        int changedSymbolsReaching,
        List<String> sampleEmittingMethods,
        String risk
    ) {}

    /** C1: configuration property reached by the slice. */
    public record AffectedProperty(
        String key,
        int changedSymbolsReaching,
        List<String> sampleReadingMethods,
        String risk
    ) {}

    /** C2: feature flag reached by the slice (the patched code is gated by this flag). */
    public record AffectedFeatureFlag(
        String id,
        int changedSymbolsReaching,
        List<String> sampleGatedMethods,
        String risk
    ) {}

    /** S1: access-control permission required by any method the slice reaches. */
    public record AffectedPermission(
        String id,
        int changedSymbolsReaching,
        List<String> sampleRequiringMethods,
        String risk
    ) {}

    /** O3: event type the slice publishes (kind="PUBLISH") or listens for (kind="LISTEN"). */
    public record AffectedEvent(
        String fqn,
        String kind,                         // "PUBLISH" | "LISTEN"
        int changedSymbolsReaching,
        List<String> sampleMethods,
        String risk
    ) {}

    /** E1: external system the slice calls. */
    public record AffectedExternalSystem(
        String id,
        String baseUrl,
        int changedSymbolsReaching,
        List<String> sampleCallingMethods,
        String risk
    ) {}

    /** M1: state-machine end-state the slice transitions to. */
    public record AffectedStateTransition(
        String entity,
        String toState,
        int changedSymbolsReaching,
        List<String> sampleMethods,
        String risk
    ) {}

    /** D5: orchestration profile the slice triggers (via OrchestrationTrigger.start). */
    public record AffectedOrchestration(
        String profileId,                    // actionId or "<unspecified>"
        int changedSymbolsReaching,
        List<String> sampleTriggeringMethods,
        String risk
    ) {}

    /** D6: user-created schedule the slice configures (via SchedulerInputsUtil.addSchedulerDetails). */
    public record AffectedUserSchedule(
        String scheduleId,                   // schedule id or "<unspecified>"
        int changedSymbolsReaching,
        List<String> sampleConfiguringMethods,
        String risk
    ) {}

    /** D7: notification macro (per §8) reached when the slice walks into a :MacroInit method. */
    public record AffectedMacro(
        String macroClassFqn,                // e.g. "...WFNotificationMacro"
        String macroSimpleName,              // e.g. "WFNotificationMacro"
        int changedSymbolsReaching,
        List<String> sampleEmittingMethods,
        String risk,
        List<String> affectedMacroKeys,      // §8c2: specific placeholder keys affected (may be empty)
        List<DiscoveryPath> discoveryPaths
    ) {
        /** Backward-compat ctor without discoveryPaths. */
        public AffectedMacro(String macroClassFqn, String macroSimpleName,
                             int changedSymbolsReaching, List<String> sampleEmittingMethods,
                             String risk, List<String> affectedMacroKeys) {
            this(macroClassFqn, macroSimpleName, changedSymbolsReaching,
                 sampleEmittingMethods, risk, affectedMacroKeys, List.of());
        }
        /** Backward-compat ctor without affectedMacroKeys or discoveryPaths. */
        public AffectedMacro(String macroClassFqn, String macroSimpleName,
                             int changedSymbolsReaching, List<String> sampleEmittingMethods,
                             String risk) {
            this(macroClassFqn, macroSimpleName, changedSymbolsReaching,
                 sampleEmittingMethods, risk, List.of(), List.of());
        }
    }

    /**
     * A function/method/class enclosing one or more diff hunks inside a non-Java file.
     * For JS/C# this comes from a tree-sitter walk of the post-image AST; for other
     * languages it remains empty in v1.
     */
    public record HunkSymbol(
        String name,            // "submitTask" / "DistributedTaskBatch" / "executeSite" / "actions.foo"
        String kind,            // "function" | "method" | "constructor" | "class" | "property" | "<anon>"
        String parent,          // for methods: the enclosing class/object name; "" otherwise
        int startLine,          // 1-based inclusive
        int endLine,            // 1-based inclusive
        int hunkStartLine,      // the hunk start that pulled this symbol in
        int hunkEndLine
    ) {}
}
