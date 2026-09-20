package io.spmp.impact.extract;

import io.spmp.impact.extract.ExtractionBatch.PendingSchedulerSite;
import io.spmp.impact.model.GraphEdges.*;
import io.spmp.impact.model.GraphNodes.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.github.javaparser.ast.body.MethodDeclaration;

/** Accumulated nodes/edges produced by a single ingestion pass. */
public class ExtractionBatch {
    public final String repoId;
    public final String commitSha;
    /**
     * IDs of dependency repositories whose files are also present in this batch
     * (populated by {@link CoreExtractor} when multi-repo ingest is enabled).
     * The writer creates one extra {@code :Repo} + {@code :Commit} pair per id.
     */
    public final java.util.LinkedHashSet<String> additionalRepoIds = new java.util.LinkedHashSet<>();

    public final List<FileNode> files = new ArrayList<>();
    public final List<ClassNode> classes = new ArrayList<>();
    public final List<MethodNode> methods = new ArrayList<>();
    public final List<FieldNode> fields = new ArrayList<>();

    public final List<ClassFileEdge> classToFile = new ArrayList<>();
    public final List<CallEdge> calls = new ArrayList<>();
    public final List<ExtendsEdge> extendsEdges = new ArrayList<>();
    public final List<ImplementsEdge> implementsEdges = new ArrayList<>();
    public final List<OverridesEdge> overrides = new ArrayList<>();
    public final List<FieldAccessEdge> fieldAccess = new ArrayList<>();

    /**
     * Three-pass extractor only: collected in Pass 1b by {@link BodyCollector},
     * drained in Pass 2 by {@link CallResolver}. Empty when the extractor is run
     * in single-pass mode (legacy path).
     */
    public final List<BodyCollector.CallSite> pendingCalls = new ArrayList<>();
    public final List<BodyCollector.FieldAccessSite> pendingFieldAccesses = new ArrayList<>();

    // P4 boundary nodes/edges
    public final List<TaskTypeNode> taskTypes = new ArrayList<>();
    public final List<RestEndpointNode> restEndpoints = new ArrayList<>();
    public final List<HandlesEdge> handles = new ArrayList<>();
    public final List<DispatchesToEdge> dispatchesTo = new ArrayList<>();
    public final List<ExposesEdge> exposes = new ArrayList<>();

    // P5 boundary nodes/edges
    public final List<DbTableNode> dbTables = new ArrayList<>();
    public final List<PsScriptNode> psScripts = new ArrayList<>();
    public final List<MessageConstantNode> messageConstants = new ArrayList<>();
    public final List<DbTableEdge> dbTableEdges = new ArrayList<>();
    public final List<InvokesScriptEdge> invokesScript = new ArrayList<>();
    public final List<MessageConstantEdge> messageConstantEdges = new ArrayList<>();

    // L1b: DB-schema nodes/edges from data-dictionary.xml
    public final List<DbColumnNode> dbColumns = new ArrayList<>();
    public final List<HasColumnEdge> hasColumn = new ArrayList<>();

    // L2: HTML pages
    public final List<HtmlPageNode> htmlPages = new ArrayList<>();
    public final List<HtmlPageReferencesEdge> htmlReferences = new ArrayList<>();

    // L4: JS/Ember files
    public final List<JsFileNode> jsFiles = new ArrayList<>();
    public final List<JsCallsApiEdge> jsCallsApi = new ArrayList<>();

    // L6: C# files
    public final List<CsFileNode> csFiles = new ArrayList<>();
    public final List<CsCallsApiEdge> csCallsApi = new ArrayList<>();
    public final List<CsInvokesScriptEdge> csInvokesScript = new ArrayList<>();

    // L9 (PD-4): Handlebars templates + JS-import edges
    public final List<HbsTemplateNode> hbsTemplates = new ArrayList<>();
    public final List<HbsUsesComponentEdge> hbsUsesComponent = new ArrayList<>();
    public final List<JsImportsEdge> jsImports = new ArrayList<>();
    public final List<JsRendersTemplateEdge> jsRendersTemplate = new ArrayList<>();

    // ── §4.1 expansion (rules N1–M1, N4–N6) ───────────────────────────────
    public final List<NotificationTypeNode> notificationTypes = new ArrayList<>();
    public final List<EmailTemplateNode>    emailTemplates    = new ArrayList<>();
    public final List<AuditCategoryNode>    auditCategories   = new ArrayList<>();
    public final List<ScheduledTaskNode>    scheduledTasks    = new ArrayList<>();
    public final List<ThreadStartEdge>      threadStartEdges  = new ArrayList<>();
    public final List<EventTypeNode>        eventTypes        = new ArrayList<>();
    public final List<PropertyNode>         properties        = new ArrayList<>();
    public final List<FeatureFlagNode>      featureFlags      = new ArrayList<>();
    public final List<PermissionNode>       permissions       = new ArrayList<>();
    public final List<ValidatorNode>        validators        = new ArrayList<>();
    public final List<ExternalSystemNode>   externalSystems   = new ArrayList<>();
    public final List<LogChannelNode>       logChannels       = new ArrayList<>();
    public final List<StateNode>            states            = new ArrayList<>();

    public final List<SendsNotificationEdge>   sendsNotification   = new ArrayList<>();
    public final List<SendsEmailEdge>          sendsEmail          = new ArrayList<>();
    public final List<WritesAuditEdge>         writesAudit         = new ArrayList<>();
    public final List<SchedulesEdge>           schedules           = new ArrayList<>();
    public final List<SchedulesToMethodEdge>   schedulesToMethod   = new ArrayList<>();
    public final List<CancelsScheduleEdge>     cancelsSchedule     = new ArrayList<>();
    public final List<PublishesEventEdge>      publishesEvent      = new ArrayList<>();
    public final List<ListensForEdge>          listensFor          = new ArrayList<>();
    public final List<InstantiatesHandlerEdge> instantiatesHandler = new ArrayList<>();
    public final List<ReadsPropertyEdge>       readsProperty       = new ArrayList<>();
    public final List<GatedByEdge>             gatedBy             = new ArrayList<>();
    public final List<RequiresPermissionEdge>  requiresPermission  = new ArrayList<>();
    public final List<ValidatesInputEdge>      validatesInput      = new ArrayList<>();
    public final List<CallsExternalEdge>       callsExternal       = new ArrayList<>();
    public final List<WritesLogEdge>           writesLog           = new ArrayList<>();
    public final List<TransitionsStateEdge>    transitionsState    = new ArrayList<>();
    public final List<InstantiatesEdge>        instantiates        = new ArrayList<>();
    public final List<SingletonOfEdge>         singletonOf         = new ArrayList<>();
    public final List<InjectsEdge>             injects             = new ArrayList<>();
    public final List<RequestParamNode>        requestParams       = new ArrayList<>();
    public final List<ReadsParamEdge>          readsParam          = new ArrayList<>();
    public final List<OrchestrationProfileNode>   orchestrationProfiles = new ArrayList<>();
    public final List<TriggersOrchestrationEdge>  triggersOrchestration = new ArrayList<>();
    public final List<UserSchedulesEdge>          userSchedules         = new ArrayList<>();
    @Deprecated(forRemoval = true) // migrate callers to use instantiates with block annotation
    public final List<GatesDispatchEdge>          gatesDispatch         = new ArrayList<>();
    public final List<HandlesAttributeEdge>       handlesAttribute      = new ArrayList<>();
    public final List<MethodDeclaration> AdsAPIMethodDeclarion = new ArrayList<>();

    public ExtractionBatch(String repoId, String commitSha) {
        this.repoId = repoId;
        this.commitSha = commitSha;
    }

    public int fileCount()   { return files.size(); }
    public int classCount()  { return classes.size(); }
    public int methodCount() { return methods.size(); }
    public int callCount()   { return calls.size(); }

    /**
     * Populated during Pass 1 by CoreExtractor.walkMethodForPass1 — outside the
     * resolversLock — so ThreadStartResolver.afterAll() can cross-join without
     * needing to iterate AST or pendingCalls under the lock.
     * Each entry records a class that declares run() and extends Thread or implements Runnable.
     */
    public record PendingRunMethod(String classFqn, String methodFqn) {}
    public record PendingStartMethod(String scopeTypeFqn, String methodFqn) {}

    // Distinct receiver-class FQNs whose zero-arg .start() was called (Thread.start()).
    // Deduped at collection time so ThreadStartResolver.afterAll() iterates one entry per
    // thread class instead of one per call site. The synthesized start-method FQN is
    // {receiverClassFqn}.start(); the caller context is not needed (STARTS_THREAD models
    // the thread class's lifecycle dispatch, not the caller). See pendingStartMethods below.

    /**
     * Populated during Pass 1 by {@link BoundaryResolver#visitMethod} via
     * {@code SchedulerResolver} — outside the resolversLock.
     * Each entry records a {@code getTaskID()} call site discovered in that method.
     * {@code forSchedules=true} means the method also instantiates {@code SchedulerExecution}
     * and should produce a SCHEDULES edge in addition to DISPATCHES_TO.
     */
    public record PendingSchedulerSite(String callerFqn, String taskName) {}


    public final List<PendingRunMethod>      pendingRunMethods      = new ArrayList<>();
    public final List<PendingStartMethod>    pendingStartMethods    = new ArrayList<>();
    public final List<PendingSchedulerSite>  pendingSchedulerSites  = new ArrayList<>();


    /**
     * Append every list-typed collection from {@code other} into this batch. Used by
     * {@link CoreExtractor#run()} to consolidate per-thread batches produced during the
     * parallel file pass into one master batch (which is then passed to the {@code afterAll}
     * resolvers). The {@code repoId} / {@code commitSha} of this
     * batch are unchanged; per-file repo attribution is already carried on each FileNode.
     */
    public void mergeFrom(ExtractionBatch other) {
        if (other == null || other == this) return;
        additionalRepoIds.addAll(other.additionalRepoIds);
        files.addAll(other.files);
        classes.addAll(other.classes);
        methods.addAll(other.methods);
        fields.addAll(other.fields);
        classToFile.addAll(other.classToFile);
        calls.addAll(other.calls);
        pendingRunMethods.addAll(other.pendingRunMethods);
        pendingStartMethods.addAll(other.pendingStartMethods);
        pendingSchedulerSites.addAll(other.pendingSchedulerSites);
        extendsEdges.addAll(other.extendsEdges);
        implementsEdges.addAll(other.implementsEdges);
        overrides.addAll(other.overrides);
        fieldAccess.addAll(other.fieldAccess);
        pendingCalls.addAll(other.pendingCalls);
        pendingFieldAccesses.addAll(other.pendingFieldAccesses);
        taskTypes.addAll(other.taskTypes);
        restEndpoints.addAll(other.restEndpoints);
        handles.addAll(other.handles);
        dispatchesTo.addAll(other.dispatchesTo);
        exposes.addAll(other.exposes);
        dbTables.addAll(other.dbTables);
        psScripts.addAll(other.psScripts);
        messageConstants.addAll(other.messageConstants);
        dbTableEdges.addAll(other.dbTableEdges);
        invokesScript.addAll(other.invokesScript);
        messageConstantEdges.addAll(other.messageConstantEdges);
        dbColumns.addAll(other.dbColumns);
        hasColumn.addAll(other.hasColumn);
        htmlPages.addAll(other.htmlPages);
        htmlReferences.addAll(other.htmlReferences);
        jsFiles.addAll(other.jsFiles);
        jsCallsApi.addAll(other.jsCallsApi);
        csFiles.addAll(other.csFiles);
        csCallsApi.addAll(other.csCallsApi);
        csInvokesScript.addAll(other.csInvokesScript);
        hbsTemplates.addAll(other.hbsTemplates);
        hbsUsesComponent.addAll(other.hbsUsesComponent);
        jsImports.addAll(other.jsImports);
        jsRendersTemplate.addAll(other.jsRendersTemplate);
        // §4.1
        notificationTypes.addAll(other.notificationTypes);
        emailTemplates.addAll(other.emailTemplates);
        auditCategories.addAll(other.auditCategories);
        scheduledTasks.addAll(other.scheduledTasks);
        threadStartEdges.addAll(other.threadStartEdges);
        eventTypes.addAll(other.eventTypes);
        properties.addAll(other.properties);
        featureFlags.addAll(other.featureFlags);
        permissions.addAll(other.permissions);
        validators.addAll(other.validators);
        externalSystems.addAll(other.externalSystems);
        logChannels.addAll(other.logChannels);
        states.addAll(other.states);
        sendsNotification.addAll(other.sendsNotification);
        sendsEmail.addAll(other.sendsEmail);
        writesAudit.addAll(other.writesAudit);
        schedules.addAll(other.schedules);
        cancelsSchedule.addAll(other.cancelsSchedule);
        publishesEvent.addAll(other.publishesEvent);
        listensFor.addAll(other.listensFor);
        instantiatesHandler.addAll(other.instantiatesHandler);
        readsProperty.addAll(other.readsProperty);
        gatedBy.addAll(other.gatedBy);
        requiresPermission.addAll(other.requiresPermission);
        validatesInput.addAll(other.validatesInput);
        callsExternal.addAll(other.callsExternal);
        writesLog.addAll(other.writesLog);
        transitionsState.addAll(other.transitionsState);
        instantiates.addAll(other.instantiates);
        singletonOf.addAll(other.singletonOf);
        injects.addAll(other.injects);
        requestParams.addAll(other.requestParams);
        readsParam.addAll(other.readsParam);
        orchestrationProfiles.addAll(other.orchestrationProfiles);
        triggersOrchestration.addAll(other.triggersOrchestration);
        userSchedules.addAll(other.userSchedules);
        gatesDispatch.addAll(other.gatesDispatch);
        handlesAttribute.addAll(other.handlesAttribute);
        AdsAPIMethodDeclarion.addAll(other.AdsAPIMethodDeclarion);
    }

    /**
     * Move every "leaf" collection (data that does NOT feed {@code emitOverrides}'s ancestor
     * walk) into a fresh delta batch and clear it in this one. Used for streaming flush:
     * after every N processed files the caller drains, writes the delta to Neo4j, and lets
     * the caller proceed with a slimmer in-memory state.
     *
     * <p>Retained (NOT drained): {@link #classes}, {@link #methods}, {@link #fields},
     * {@link #extendsEdges}, {@link #implementsEdges}, {@link #classToFile}, {@link #overrides}.
     * These are all needed by {@code CoreExtractor.emitOverrides()} after the file loop.
     */
    public ExtractionBatch drainLeafCollections() {
        ExtractionBatch d = new ExtractionBatch(repoId, commitSha);
        d.additionalRepoIds.addAll(additionalRepoIds);
        d.files.addAll(files);                       files.clear();
        d.calls.addAll(calls);                       calls.clear();
        d.fieldAccess.addAll(fieldAccess);           fieldAccess.clear();
        // P4 boundary
        d.taskTypes.addAll(taskTypes);               taskTypes.clear();
        d.restEndpoints.addAll(restEndpoints);       restEndpoints.clear();
        d.handles.addAll(handles);                   handles.clear();
        d.dispatchesTo.addAll(dispatchesTo);         dispatchesTo.clear();
        d.exposes.addAll(exposes);                   exposes.clear();
        // P5 boundary
        d.dbTables.addAll(dbTables);                 dbTables.clear();
        d.psScripts.addAll(psScripts);               psScripts.clear();
        d.messageConstants.addAll(messageConstants); messageConstants.clear();
        d.dbTableEdges.addAll(dbTableEdges);         dbTableEdges.clear();
        d.invokesScript.addAll(invokesScript);       invokesScript.clear();
        d.messageConstantEdges.addAll(messageConstantEdges); messageConstantEdges.clear();
        // L1b DB schema
        d.dbColumns.addAll(dbColumns);               dbColumns.clear();
        d.hasColumn.addAll(hasColumn);               hasColumn.clear();
        // L2 HTML / L4 JS / L6 CS / L9 HBS
        d.htmlPages.addAll(htmlPages);               htmlPages.clear();
        d.htmlReferences.addAll(htmlReferences);     htmlReferences.clear();
        d.jsFiles.addAll(jsFiles);                   jsFiles.clear();
        d.jsCallsApi.addAll(jsCallsApi);             jsCallsApi.clear();
        d.csFiles.addAll(csFiles);                   csFiles.clear();
        d.csCallsApi.addAll(csCallsApi);             csCallsApi.clear();
        d.csInvokesScript.addAll(csInvokesScript);   csInvokesScript.clear();
        d.hbsTemplates.addAll(hbsTemplates);         hbsTemplates.clear();
        d.hbsUsesComponent.addAll(hbsUsesComponent); hbsUsesComponent.clear();
        d.jsImports.addAll(jsImports);               jsImports.clear();
        d.jsRendersTemplate.addAll(jsRendersTemplate); jsRendersTemplate.clear();
        // §4.1
        d.notificationTypes.addAll(notificationTypes);     notificationTypes.clear();
        d.emailTemplates.addAll(emailTemplates);           emailTemplates.clear();
        d.auditCategories.addAll(auditCategories);         auditCategories.clear();
        d.scheduledTasks.addAll(scheduledTasks);           scheduledTasks.clear();
        d.threadStartEdges.addAll(threadStartEdges);         threadStartEdges.clear();
        d.eventTypes.addAll(eventTypes);                   eventTypes.clear();
        d.properties.addAll(properties);                   properties.clear();
        d.featureFlags.addAll(featureFlags);               featureFlags.clear();
        d.permissions.addAll(permissions);                 permissions.clear();
        d.validators.addAll(validators);                   validators.clear();
        d.externalSystems.addAll(externalSystems);         externalSystems.clear();
        d.logChannels.addAll(logChannels);                 logChannels.clear();
        d.states.addAll(states);                           states.clear();
        d.sendsNotification.addAll(sendsNotification);     sendsNotification.clear();
        d.sendsEmail.addAll(sendsEmail);                   sendsEmail.clear();
        d.writesAudit.addAll(writesAudit);                 writesAudit.clear();
        d.schedules.addAll(schedules);                     schedules.clear();
        d.cancelsSchedule.addAll(cancelsSchedule);         cancelsSchedule.clear();
        d.publishesEvent.addAll(publishesEvent);           publishesEvent.clear();
        d.listensFor.addAll(listensFor);                   listensFor.clear();
        d.instantiatesHandler.addAll(instantiatesHandler); instantiatesHandler.clear();
        d.readsProperty.addAll(readsProperty);             readsProperty.clear();
        d.gatedBy.addAll(gatedBy);                         gatedBy.clear();
        d.requiresPermission.addAll(requiresPermission);   requiresPermission.clear();
        d.validatesInput.addAll(validatesInput);           validatesInput.clear();
        d.callsExternal.addAll(callsExternal);             callsExternal.clear();
        d.writesLog.addAll(writesLog);                     writesLog.clear();
        d.transitionsState.addAll(transitionsState);       transitionsState.clear();
        // Drain only basic INSTANTIATES (FQN-targeted). Keep block-annotated ones
        // (blockStartLine > 0, targeting by simple_name) in master because the writer
        // uses OPTIONAL MATCH by simple_name which requires the class node to exist.
        var basicInstantiates = instantiates.stream()
            .filter(e -> e.blockStartLine() <= 0).toList();
        var blockInstantiates = instantiates.stream()
            .filter(e -> e.blockStartLine() > 0).toList();
        d.instantiates.addAll(basicInstantiates);
        instantiates.clear();
        instantiates.addAll(blockInstantiates);
        d.singletonOf.addAll(singletonOf);                 singletonOf.clear();
        d.injects.addAll(injects);                         injects.clear();
        d.requestParams.addAll(requestParams);             requestParams.clear();
        d.readsParam.addAll(readsParam);                   readsParam.clear();
        d.orchestrationProfiles.addAll(orchestrationProfiles); orchestrationProfiles.clear();
        d.triggersOrchestration.addAll(triggersOrchestration); triggersOrchestration.clear();
        d.userSchedules.addAll(userSchedules);                 userSchedules.clear();
        // D7 Layer C — DO NOT drain gatesDispatch (deprecated) or block-annotated
        // instantiates in partial flushes. The writer's OPTIONAL MATCH by simple_name
        // depends on the class node being already in Neo4j. Block-annotated
        // instantiates are retained above; gatesDispatch is kept here for legacy compat.
        return d;
    }
}
