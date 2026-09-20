package io.spmp.impact.model;

import java.util.List;

public final class GraphNodes {
    private GraphNodes() {}

    public record FileNode(String path, String pkg, String repoId, String commitSha, String contentHash) {}

    public record ClassNode(
        String fqn,
        String simpleName,
        String pkg,
        String filePath,
        boolean isInterface,
        boolean isAbstract,
        int startLine,
        int endLine,
        List<String> extraLabels   // e.g. ["Servlet"], ["Scheduler"], ["TaskHandler"], ["Job"]
    ) {}

    public record MethodNode(
        String fqn,           // owner.method(paramTypes)
        String signature,     // method(paramTypes)
        String simpleName,
        String ownerFqn,
        String returnType,
        boolean isStatic,
        boolean isConstructor,
        int startLine,
        int endLine,
        List<String> extraLabels   // e.g. ["EntryPoint"]
    ) {}

    /**
     * A field declaration. {@code constantValue} carries the literal value for fields
     * declared {@code public static final} with a Long / Integer / String literal
     * initializer (e.g. {@code public static final Long WORKFLOW_REJECT = 1914L;} →
     * {@code "1914"}). Empty string for non-constants or runtime-initialized fields.
     * Used by the post-ingest ConstantIndex cleanup to reverse-map numeric ids like
     * {@code 1914} to symbolic names like {@code WORKFLOW_REJECT}.
     */
    public record FieldNode(
        String fqn,
        String simpleName,
        String ownerFqn,
        String type,
        String constantValue
    ) {
        /** Back-compat 4-arg constructor — non-constant field. */
        public FieldNode(String fqn, String simpleName, String ownerFqn, String type) {
            this(fqn, simpleName, ownerFqn, type, "");
        }
    }

    /** Boundary node: a task-type string keyed in ManagementTaskRegistry. */
    public record TaskTypeNode(String id) {}

    /** Boundary node: a REST endpoint URL. */
    public record RestEndpointNode(String url, String classFqn) {}

    /** Boundary node: a SQL table name. */
    public record DbTableNode(String name) {}

    /** Boundary node: a SQL column on a specific table. Key = "<table>.<column>". */
    public record DbColumnNode(
        String table,
        String name,
        String dataType,      // "BIGINT", "NCHAR", "INTEGER", etc.
        Integer maxSize,      // null when not specified
        boolean nullable,
        boolean pkLike        // true when a uniquevalue-generation block names this column
    ) {}

    /** Boundary node: an HTML page file. Key = relative filename (e.g. "Admin-MailServer.html"). */
    public record HtmlPageNode(
        String filename,      // "Admin-MailServer.html"
        String title,         // <title> content or "" if absent
        String filePath       // absolute path on disk
    ) {}

    /** Boundary node: an Ember/JS source file. */
    public record JsFileNode(
        String path,          // forward-slash relative path under source/ember/app, e.g. "routes/dashboard.js"
        String simpleName,    // "dashboard"
        String role,          // "Route" | "Model" | "Controller" | "Component" | "Service" | "Adapter" | "Helper" | "Other"
        String filePath       // absolute disk path
    ) {}

    /** Boundary node: a C# source file (in SPMP these are the SharePoint client / agent layer). */
    public record CsFileNode(
        String path,          // forward-slash relative path under source/c_sharp
        String simpleName,    // "ManagementTaskHandler"
        String role,          // "Management" | "Reports" | "Audit" | "Client" | "Common" | "Core" | "Other"
        String filePath       // absolute disk path
    ) {}

    /** Boundary node: a PowerShell script filename. */
    public record PsScriptNode(String name) {}

    /** Boundary node: a string-constant value used in JGroups message routing. */
    public record MessageConstantNode(String value, String ownerFqn) {}

    /**
     * L9: Handlebars (.hbs) template file. Key = path relative to source/ember/app.
     * Templates can render JS components via {@code {{component-name}}} invocations —
     * captured as :USES_COMPONENT edges to :JsFile nodes.
     */
    public record HbsTemplateNode(
        String path,              // forward-slash relative path under source/ember/app
        String simpleName,        // "ads-select-input" (no extension)
        String role,              // "ComponentTemplate" | "RouteTemplate" | "Other"
        String filePath           // absolute disk path
    ) {}

    // ─── §4.1 expansion (boundary-edge promotion rules N1–M1, N4–N6) ───────────
    // These node types correspond to the new graph edges documented in
    // ARCHITECTURE.md §4.1. Each carries a single primary key (the field marked
    // as such in §4.1's "node key" callout); secondary properties are illustrative
    // and may be empty when the call site doesn't expose them.

    /** N1: a notification kind keyed by the constant value (e.g. WF_REQUEST_REJECTED). */
    public record NotificationTypeNode(String id) {}

    /** N2: an email template identifier (string constant or templateId). */
    public record EmailTemplateNode(String id) {}

    /** N3: an audit category constant. */
    public record AuditCategoryNode(String id) {}

    /**
     * O1/O2: a class scheduled by code via Timer / ScheduleManager / Quartz / Executor.
     * Key = FQN of the Runnable/Task class that gets scheduled.
     */
    public record ScheduledTaskNode(String taskClassFqn, String taskName) {}

    /** O3: an event type keyed by its full class FQN. */
    public record EventTypeNode(String fqn) {}

    /** C1: a configuration property keyed by its dotted name. */
    public record PropertyNode(String key) {}

    /** C2: a feature flag (boolean gate). */
    public record FeatureFlagNode(String id) {}

    /** S1: an access-control permission constant. */
    public record PermissionNode(String id) {}

    /** S2: a validator instance / class (FQN or constant identifier). */
    public record ValidatorNode(String id) {}

    /** E1: an external system the application calls into (SharePoint, Graph, Slack, …). */
    public record ExternalSystemNode(String id, String baseUrl) {}

    /** E2: a named logger / log channel — only when non-default. */
    public record LogChannelNode(String name) {}

    /**
     * M1: a state-machine end-state. Key = (entity, to) — e.g. WorkflowRequest → REJECTED.
     * "From" is left out because static analysis usually can't pin down the source state.
     */
    public record StateNode(String entity, String to) {}

    /**
     * §4.1 P1 — HTTP / form request parameter the application reads via
     * {@code request.getParameter("name")} / {@code @RequestParam} / {@code @PathVariable}.
     * Key = the parameter name string. Per-method usage (and per-comparison value) is
     * carried on the {@code :READS_PARAM} edge, not here.
     */
    public record RequestParamNode(String name) {}

    /**
     * D5: an orchestration profile / template identifier. Key = the {@code actionId}
     * passed to {@code OrchestrationTrigger.setActionId(...)} when statically
     * extractable, else the string {@code "<unspecified>"} (callers can filter on this
     * to find triggers without identifiable profiles).
     */
    public record OrchestrationProfileNode(String id) {}
}
