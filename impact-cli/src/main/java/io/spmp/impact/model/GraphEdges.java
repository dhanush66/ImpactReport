package io.spmp.impact.model;

import java.util.List;

public final class GraphEdges {
    private GraphEdges() {}

    public record CallEdge(String fromMethodFqn, String toMethodFqn, String kind) {}
    public record ExtendsEdge(String fromClassFqn, String toClassFqn) {}
    public record ImplementsEdge(String fromClassFqn, String toInterfaceFqn) {}
    public record OverridesEdge(String fromMethodFqn, String toMethodFqn) {}
    public record FieldAccessEdge(String fromMethodFqn, String toFieldFqn, boolean write) {}
    public record ClassFileEdge(String classFqn, String filePath) {}

    // ── Boundary edges (P4) ────────────────────────────────────────────
    /** TaskHandler class HANDLES a TaskType. */
    public record HandlesEdge(String handlerClassFqn, String taskTypeId) {}

    /** Method DISPATCHES_TO a TaskHandler's executeSite method (synthetic registry-driven edge). */
    public record DispatchesToEdge(String fromMethodFqn, String toMethodFqn) {}

    /**
     * Servlet/Controller class EXPOSES a RestEndpoint URL.
     *
     * <p>{@code targetMethodSimpleName} carries the per-method routing target for
     * <em>dispatcher</em>-style classes (e.g. ADMP's Struts {@code WorkFlowAction}
     * that fronts ~48 URLs through {@code ADMPRestAPIServlet}'s URL-last-segment
     * convention). When non-empty, the analyze layer's "APIs Affected" aggregator
     * only surfaces this URL if the patched method's simple name matches.
     *
     * <p>Empty string = legacy class-granularity (HttpServlet subclasses where
     * one class = one URL, and REST-XML mappings). The analyze filter falls back
     * to unconditional inclusion in that case, preserving pre-fix behaviour.
     */
    public record ExposesEdge(String classFqn, String url, String targetMethodSimpleName) {
        /** Back-compat 2-arg constructor — class-granularity (empty target). */
        public ExposesEdge(String classFqn, String url) { this(classFqn, url, ""); }
    }

    /** Method READS_TABLE / WRITES_TABLE — one record, write flag distinguishes direction. */
    public record DbTableEdge(String fromMethodFqn, String tableName, boolean write) {}

    /** :DbTable HAS_COLUMN :DbColumn — emitted by DbSchemaXmlResolver. */
    public record HasColumnEdge(String tableName, String columnName) {}

    /** :HtmlPage REFERENCES :RestEndpoint — link from a page to a URL it embeds. */
    public record HtmlPageReferencesEdge(String pageFilename, String restUrl) {}

    /** :JsFile CALLS_API :RestEndpoint — JS file references a URL in a string literal. */
    public record JsCallsApiEdge(String jsFilePath, String restUrl) {}

    /** :CsFile CALLS_API :RestEndpoint — C# file references a URL in a string literal. */
    public record CsCallsApiEdge(String csFilePath, String restUrl) {}

    /** :CsFile INVOKES_SCRIPT :PsScript — C# file references a .ps1 filename. */
    public record CsInvokesScriptEdge(String csFilePath, String scriptName) {}

    /** Method INVOKES_SCRIPT — calls a .ps1 file via ProcessBuilder/Process.Start. */
    public record InvokesScriptEdge(String fromMethodFqn, String scriptName) {}

    /** Method SENDS_MESSAGE / RECEIVES_MESSAGE for a JGroups MessageConstant. */
    public record MessageConstantEdge(String fromMethodFqn, String constantValue, boolean sending) {}

    // ── L9: Ember UI graph edges ────────────────────────────────────────
    /** :HbsTemplate USES_COMPONENT :JsFile — template renders a JS component (or component-helper). */
    public record HbsUsesComponentEdge(String hbsPath, String jsPath) {}

    /** :JsFile IMPORTS :JsFile — module import (resolved within source/ember/app). */
    public record JsImportsEdge(String fromJsPath, String toJsPath) {}

    /** :JsFile RENDERS_TEMPLATE :HbsTemplate — JS component file paired with its template (path convention). */
    public record JsRendersTemplateEdge(String jsPath, String hbsPath) {}

    // ─── §4.1 expansion (boundary-edge promotion rules N1–M1, N4–N6) ───────────

    /** N1: Method SENDS_NOTIFICATION → NotificationType. */
    public record SendsNotificationEdge(String fromMethodFqn, String notificationTypeId) {}

    /** N2: Method SENDS_EMAIL → EmailTemplate. */
    public record SendsEmailEdge(String fromMethodFqn, String templateId) {}

    /** N3: Method WRITES_AUDIT → AuditCategory. */
    public record WritesAuditEdge(String fromMethodFqn, String categoryId) {}

    /**
     * O1: Method SCHEDULES → ScheduledTask. {@code delayMillis} is -1 when not statically
     * extractable; otherwise carries the literal numeric delay argument.
     */
    public record SchedulesEdge(String fromMethodFqn, String taskClassFqn, long delayMillis) {}

    public record SchedulesToMethodEdge(String fromTaskclassFqn, String ToMethodFqn, long delayMillis) {}

    /** O2: Method CANCELS_SCHEDULE → ScheduledTask (Timer.cancel / ScheduleManager.cancel). */
    public record CancelsScheduleEdge(String fromMethodFqn, String taskClassFqn) {}

    /** O3a: Method PUBLISHES_EVENT → EventType (Spring eventPublisher.publishEvent / Guava EventBus.post). */
    public record PublishesEventEdge(String fromMethodFqn, String eventTypeFqn) {}

    /** O3b: Method LISTENS_FOR → EventType (Spring @EventListener / Guava @Subscribe). */
    public record ListensForEdge(String fromMethodFqn, String eventTypeFqn) {}

    /**
     * O4: Method INSTANTIATES_HANDLER → Class. Subset of N4 (:INSTANTIATES) reserved for
     * registry/coordinator method bodies that construct task-handler classes — used
     * by the TaskRegistry-style orchestration view.
     */
    public record InstantiatesHandlerEdge(String fromMethodFqn, String handlerClassFqn) {}

    /**
     * D5: Method TRIGGERS_ORCHESTRATION → OrchestrationProfile. Emitted when a method
     * builds {@code new OrchestrationTrigger(...)} and (chained or via local-var)
     * invokes {@code .start()} on it. See BOUNDARY_DESTINATIONS.md §6.
     *
     * <p>{@code actionId} carries the value passed to {@code .setActionId(Long)} when
     * statically extractable (FieldAccessExpr's simple name preferred; numeric literal
     * as fallback). Empty string when the action-id is a runtime expression — the edge
     * still emits so the orchestration trigger is captured as a structural impact, the
     * report just can't name which profile.
     */
    public record TriggersOrchestrationEdge(String fromMethodFqn, String actionId) {}

    /**
     * D6: Method USER_SCHEDULES → ScheduledTask. Emitted when a method calls
     * {@code SchedulerInputsUtil.addSchedulerDetails(...)} — the ADMP-specific API that
     * writes user-input schedule config into {@code ADSMSchedulerInputDetails}. Distinct
     * from {@code :SCHEDULES} (which is the system-level registration via
     * {@code SchedulerHandler.createScheduler}) — see BOUNDARY_DESTINATIONS.md §3.
     *
     * <p>{@code scheduleId} carries the schedule identifier when statically extractable;
     * most call sites pass a runtime {@code Long scheduleId} so the value is typically
     * {@code "<unspecified>"} (set by the resolver as the fallback). The edge still
     * captures the structural fact that this method writes the user-schedule config
     * table — useful for QA even without the specific schedule name.
     */
    public record UserSchedulesEdge(String fromMethodFqn, String scheduleId) {}

    /**
     * D7 Layer C (BOUNDARY_DESTINATIONS.md §8c2): REPLACED by block-annotated
     * {@link InstantiatesEdge}. The dispatch-block metadata (line range, sibling methods)
     * now lives as optional properties on the unified {@code :INSTANTIATES} edge.
     * Kept as a deprecated type alias during migration — remove after full verification.
     *
     * @deprecated Use {@link InstantiatesEdge#InstantiatesEdge(String, String, int, int, List)}
     */
    @Deprecated(forRemoval = true)
    public record GatesDispatchEdge(
        String fromMethodFqn,
        String macroSimpleName,
        int blockStartLine,
        int blockEndLine,
        List<String> siblingMethods      // method calls inside the gating block
    ) {
        /** Backward-compat ctor without siblingMethods. */
        public GatesDispatchEdge(String fromMethodFqn, String macroSimpleName,
                                 int blockStartLine, int blockEndLine) {
            this(fromMethodFqn, macroSimpleName, blockStartLine, blockEndLine, List.of());
        }

        /** Convert to the unified InstantiatesEdge. */
        public InstantiatesEdge toInstantiatesEdge() {
            return new InstantiatesEdge(fromMethodFqn, macroSimpleName,
                blockStartLine, blockEndLine, siblingMethods);
        }
    }

    /** C1: Method READS_PROPERTY → Property (System.getProperty, ConfigManager.get, @Value). */
    public record ReadsPropertyEdge(String fromMethodFqn, String propertyKey) {}

    /** C2: Method GATED_BY → FeatureFlag. */
    public record GatedByEdge(String fromMethodFqn, String flagId) {}

    /** S1: Method REQUIRES_PERMISSION → Permission. */
    public record RequiresPermissionEdge(String fromMethodFqn, String permissionId) {}

    /** S2: Method VALIDATES_INPUT → Validator. */
    public record ValidatesInputEdge(String fromMethodFqn, String validatorId) {}

    /**
     * E1: Method CALLS_EXTERNAL → ExternalSystem. {@code urlSample} is a representative
     * URL literal when one is statically extractable, else empty.
     */
    public record CallsExternalEdge(String fromMethodFqn, String systemId, String urlSample) {}

    /** E2: Method WRITES_LOG → LogChannel (named logger only — skip default channel). */
    public record WritesLogEdge(String fromMethodFqn, String channelName) {}

    /**
     * M1: Method TRANSITIONS_STATE → State. {@code fromState} may be empty when the
     * source state isn't statically extractable.
     */
    public record TransitionsStateEdge(String fromMethodFqn, String entity, String fromState, String toState) {}

    /**
     * N4: Method INSTANTIATES → Class. {@code via} = "new" / "getInstance" / "factory".
     *
     * <p>When emitted by {@code ClassShapeResolver}, this is a generic constructor-call
     * edge with no block metadata (blockStartLine = -1). When emitted by
     * {@code NotificationAuditResolver} for §8c2 Layer C, it carries the dispatch-block
     * line range ({@code blockStartLine..blockEndLine}) and sibling method calls for
     * hunk-overlap gating and macro-key attribution.
     *
     * <p>The writer uses {@code blockStartLine > 0} as the discriminator: basic edges
     * MERGE on {@code (m)-[:INSTANTIATES]->(c)}; block-annotated edges MERGE on
     * {@code (m)-[:INSTANTIATES {block_start_line}]->(c)} so multiple dispatch blocks
     * within the same method produce separate edges.
     *
     * <p>When {@code classFqn} is null, the writer resolves via {@code targetSimpleName}
     * (OPTIONAL MATCH by simple_name) — used by the notification resolver which only
     * knows the macro type's simple name at extraction time.
     */
    public record InstantiatesEdge(
        String fromMethodFqn,
        String classFqn,            // null when only simple name is known
        String targetSimpleName,    // non-null for simple-name-targeted (notification); null for FQN-targeted
        String via,
        int blockStartLine,         // -1 = no block annotation
        int blockEndLine,           // -1 = no block annotation
        List<String> siblingMethods // empty = no siblings
    ) {
        /** Basic constructor for ClassShapeResolver (FQN-targeted, no block metadata). */
        public InstantiatesEdge(String fromMethodFqn, String classFqn, String via) {
            this(fromMethodFqn, classFqn, null, via, -1, -1, List.of());
        }

        /** Block-annotated constructor for NotificationAuditResolver (simple-name-targeted). */
        public InstantiatesEdge(String fromMethodFqn, String macroSimpleName,
                                int blockStartLine, int blockEndLine, List<String> siblingMethods) {
            this(fromMethodFqn, null, macroSimpleName, "new", blockStartLine, blockEndLine,
                 siblingMethods != null ? siblingMethods : List.of());
        }
    }

    /** N5: Class SINGLETON_OF → Class (self-loop). */
    public record SingletonOfEdge(String classFqn) {}

    /** N6: Class INJECTS → Class (Spring/CDI dependency injection). */
    public record InjectsEdge(String fromClassFqn, String dependencyClassFqn) {}

    /**
     * P1: Method READS_PARAM → RequestParam. Captures (parameter name, optional
     * literal value compared against). One edge per (fromMethod, paramName,
     * branchValue, blockStartLine) tuple — when the same method has multiple
     * {@code .equals("...")} / {@code case "..."} comparisons against the same param
     * in different branches, each one becomes a row.
     *
     * <p>{@code branchValue} is the empty string when the resolver only sees the read
     * (e.g. raw {@code request.getParameter("X")} with no value compare).
     *
     * <p>{@code blockStartLine} / {@code blockEndLine} are the source-line range of
     * the enclosing branch — the {@code if} body, {@code case} body, or method body
     * the comparison/read sits inside. The analyze layer uses these to filter to
     * "affected" params: a param is affected iff its block range CONTAINS the patch's
     * hunk lines (i.e. the patched code can only run if the comparison takes that
     * branch). For a bare read at the top of a method, the block range = the method
     * body, which always contains the hunk — those rows stay regardless.
     */
    public record ReadsParamEdge(
        String fromMethodFqn,
        String paramName,
        String branchValue,
        int blockStartLine,
        int blockEndLine
    ) {
        /** Back-compat 3-arg constructor — defaults block range to (0, Integer.MAX_VALUE) → matches everything. */
        public ReadsParamEdge(String fromMethodFqn, String paramName, String branchValue) {
            this(fromMethodFqn, paramName, branchValue, 0, Integer.MAX_VALUE);
        }
    }

    /**
     * §8c2-g: Method in a NotificationMacro class HANDLES_ATTRIBUTE → an LDAP
     * attribute (detected from {@code ldapName.equals("X")} / {@code equalsIgnoreCase}
     * patterns). {@code blockStartLine}/{@code blockEndLine} are the line range of the
     * containing {@code if} block so the analyze layer can overlap-test patch hunks.
     */
    public record HandlesAttributeEdge(
        String fromMethodFqn,
        String macroSimpleName,
        String attributeName,
        int blockStartLine,
        int blockEndLine
    ) {}

    /** Method STARTS_THREAD -> run() — emitted by ThreadStartResolver. */
    public record ThreadStartEdge(String fromMethodFqn, String toRunMethodFqn) {}
}
