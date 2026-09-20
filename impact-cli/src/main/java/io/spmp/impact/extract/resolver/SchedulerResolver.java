package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;

import io.spmp.impact.extract.BodyCollector;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.extract.ExtractionBatch.PendingRunMethod;
import io.spmp.impact.extract.ExtractionBatch.PendingSchedulerSite;
import io.spmp.impact.model.GraphEdges.DispatchesToEdge;
import io.spmp.impact.model.GraphEdges.SchedulesEdge;
import io.spmp.impact.model.GraphEdges.SchedulesToMethodEdge;
import io.spmp.impact.model.GraphNodes.MethodNode;
import io.spmp.impact.model.GraphNodes.ScheduledTaskNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves scheduled-task nodes and programmatic dispatch edges from task-mapping XML
 * files that follow the {@code TaskEngine_Task task_name="..." class_name="..."} schema —
 * e.g. {@code taskflow.xml} and {@code ADSPatchUpdateManager.xml}.
 */
public class SchedulerResolver implements BoundaryResolver {

    private final List<Path> taskXmlPaths;
    private final JavaParserFacade javaParserFacade;
    private record TaskIDdetails(String ownerFqn, String callerFqn, String taskName) {}
    private record SchedulerExecutionCallSite(String ownerFqn, String callerFqn, String variableName) {}
    private record SchedulerExecutionStartCallSite(String ownerFqn, String callerFqn, String consCallFqn) {}

    // task_name → class_name, insertion order preserved for logging
    private final Map<String, String> taskNameToClass = new LinkedHashMap<>();
    private final Map<String, String> classFqnToTaskName = new HashMap<>();

    /** TASK_ID → (TASK_NAME, CLASS_NAME) parsed ONLY from DelayedTaskCategory.xml's
     *  {@code <BackgroundTaskDetails>} tags. Kept separate from {@code taskNameToClass}
     *  because it's a different dispatch mechanism (reflective Class.forName, not TaskEngine). */
    private record BackgroundTaskDetail(String taskId, String taskName, String classFqn) {}
    private final Map<String, BackgroundTaskDetail> backgroundTaskIdToDetail = new LinkedHashMap<>();

    /** Backward-compatible constructor — no source roots, type resolution may not resolve project types. */
    public SchedulerResolver(Path taskflowXmlPath) {
        this(taskflowXmlPath, null, ResolverUtils.javaParserFacade(List.of()));
    }

    /**
     * Backward-compatible single-XML constructor. {@code javaParserFacade} should be built via
     * {@link ResolverUtils#javaParserFacade(List)} at the call site so the SymbolSolver is
     * constructed exactly once and shared across resolvers, rather than rebuilt here.
     */
    public SchedulerResolver(Path taskflowXmlPath, JavaParserFacade javaParserFacade) {
        this(taskflowXmlPath, null, javaParserFacade);
    }

    /**
     * Single-taskflow-XML constructor that also accepts {@code DelayedTaskCategory.xml}
     * (or an equivalent file with {@code <BackgroundTaskDetails>} tags).
     */
    public SchedulerResolver(Path taskflowXmlPath, Path delayedTaskCategoryXmlPath, JavaParserFacade javaParserFacade) {
        this(taskflowXmlPath == null ? List.of() : List.of(taskflowXmlPath), delayedTaskCategoryXmlPath, javaParserFacade);
    }

    /**
     * Full constructor. {@code taskXmlPaths} are every task-mapping XML file to merge
     * (e.g. {@code taskflow.xml}, {@code ADSPatchUpdateManager.xml}) — all must follow the
     * same {@code TaskEngine_Task task_name="..." class_name="..."} schema. {@code javaParserFacade}
     * is built by the caller via {@link ResolverUtils#javaParserFacade(List)} so the SymbolSolver
     * (which covers project source roots + nearby product jars) is constructed exactly once.
     */
    public SchedulerResolver(List<Path> taskXmlPaths, JavaParserFacade javaParserFacade) {
        this(taskXmlPaths, null, javaParserFacade);
    }

    /**
     * Full constructor with {@code DelayedTaskCategory.xml} support. {@code delayedTaskCategoryXmlPath}
     * is parsed for ONLY its {@code <BackgroundTaskDetails>} tags (TASK_ID/TASK_NAME/CLASS_NAME) —
     * a separate map from {@code taskNameToClass}, since these classes are dispatched reflectively
     * from {@code wengine.DelayedTask.executeTask()}, not registered directly with TaskEngine.
     */
    public SchedulerResolver(List<Path> taskXmlPaths, Path delayedTaskCategoryXmlPath, JavaParserFacade javaParserFacade) {
        this.taskXmlPaths = taskXmlPaths == null ? List.of() : taskXmlPaths;
        this.javaParserFacade = javaParserFacade;
        for (Path xml : this.taskXmlPaths) {
            if (xml != null && Files.isRegularFile(xml)) {
                parseTaskflow(xml);
            } else {
                System.out.println("[SchedulerResolver] task xml not found at " + xml + " — skipped");
            }
        }
        if (delayedTaskCategoryXmlPath != null) {
            if (Files.isRegularFile(delayedTaskCategoryXmlPath)) {
                parseDelayedTaskCategory(delayedTaskCategoryXmlPath);
            } else {
                System.out.println("[SchedulerResolver] DelayedTaskCategory xml not found at " + delayedTaskCategoryXmlPath + " — skipped");
            }
        }
    }

    // ─── XML parsing ──────────────────────────────────────────────────

    private void parseTaskflow(Path xml) {
        try {
            Document doc = parseXmlSecurely(xml);
            NodeList nodes = doc.getElementsByTagName("TaskEngine_Task");
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element el)) continue;
                String taskName = attrCI(el, "task_name");
                String className = attrCI(el, "class_name");
                if (taskName == null || className == null) continue;
                taskNameToClass.put(taskName, className);
                classFqnToTaskName.put(className, taskName);
            }
            System.out.printf("[SchedulerResolver] parsed %s — %d task mappings%n",
                xml.getFileName(), taskNameToClass.size());
        } catch (Exception e) {
            System.err.println("[SchedulerResolver] failed to parse " + xml + ": " + e.getMessage());
        }
    }

    /**
     * Parses ONLY {@code <BackgroundTaskDetails>} tags from {@code DelayedTaskCategory.xml}.
     * Its {@code DelayedTaskType}/{@code DelayedSubTaskType} tags belong to a different,
     * UI-facing dispatch path and are intentionally ignored here. Populates
     * {@code backgroundTaskIdToDetail}, keyed by TASK_ID, so callers of
     * {@code DelayedSchedulerUtil.createNewDelayedTaskSchedule(...)} can be mapped to the
     * concrete background-task class that ultimately runs.
     */
    private void parseDelayedTaskCategory(Path xml) {
        try {
            Document doc = parseXmlSecurely(xml);
            NodeList nodes = doc.getElementsByTagName("BackgroundTaskDetails");
            for (int i = 0; i < nodes.getLength(); i++) {
                if (!(nodes.item(i) instanceof Element el)) continue;
                String taskId = attrCI(el, "task_id");
                String taskName = attrCI(el, "task_name");
                String className = attrCI(el, "class_name");
                if (taskId == null || taskName == null || className == null) continue;
                backgroundTaskIdToDetail.put(taskId, new BackgroundTaskDetail(taskId, taskName, className));
            }
            System.out.printf("[SchedulerResolver] parsed %s — %d BackgroundTaskDetails mappings%n",
                xml.getFileName(), backgroundTaskIdToDetail.size());
        } catch (Exception e) {
            System.err.println("[SchedulerResolver] failed to parse " + xml + ": " + e.getMessage());
        }
    }

    private static Document parseXmlSecurely(Path xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        dbf.setXIncludeAware(false);
        dbf.setExpandEntityReferences(false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        db.setErrorHandler(null);
        Document doc = db.parse(xml.toFile());
        doc.getDocumentElement().normalize();
        return doc;
    }

    private static String attrCI(Element el, String localName) {
        for (String n : new String[]{ localName, localName.toUpperCase() }) {
            String v = el.getAttribute(n);
            if (v != null && !v.isEmpty()) return v.trim();
        }
        return null;
    }

    // ─── BoundaryResolver ─────────────────────────────────────────────

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // All per-method work moved to visitMethod(); nothing to do here.
    }

    @Override
    public void visitMethod(MethodDeclaration md, String methodFqn, String ownerFqn,
                        List<BodyCollector.CallSite> calls, ExtractionBatch local) {

        List<TaskIDdetails> taskIDdetails = new ArrayList<>();
        Set<String> taskIDSeen = new HashSet<>();
        Set<String> backgroundTaskNamesSeen = new HashSet<>();
        // 1. Change to primitive boolean for standard flag management
        boolean hasSchedulerExecutionStartCall = false;
        boolean hasSchedulerOperationHandlerCall = false;
        
        if (taskNameToClass.isEmpty() && backgroundTaskIdToDetail.isEmpty()) return;
        

        Map<String, String> strVars = null;

        for (MethodCallExpr call : md.findAll(MethodCallExpr.class)) {
            // Find any calls to SchedulerExecution.start()
            if (call.getNameAsString().equals("start") && call.getScope().isPresent()) {
                Expression scope = call.getScope().get();
                try {
                        // Use JavaParserFacade with our CombinedTypeSolver — works even when
                        // the CU was parsed without a SymbolSolver (CoreExtractor's no-symbol mode).
                        ResolvedType scopeType = resolveExecuteTaskScopeType(scope, methodFqn, call);
                        String className = scopeType.asReferenceType().getTypeDeclaration().get().getName();
                        if (className.contains("SchedulerExecution")) {
                            hasSchedulerExecutionStartCall = true;
                        }
                    } catch (Throwable e) {
                        // Type not resolvable — source roots may not cover this class, or a
                        // 3rd-party jar on the classpath has an ECJ-compiled class that throws
                        // java.lang.Error on unresolved symbols; skip silently either way.
                        System.err.println("[SchedulerResolver] failed to resolve type of executeTask() scope: " + e.getMessage());
                    }
            }

            if (call.getNameAsString().equals("execute")) {
                if (call.getScope().isPresent()) {
                    Expression executeTaskScope = call.getScope().get();
                    try {
                        // Use JavaParserFacade with our CombinedTypeSolver — works even when
                        // the CU was parsed without a SymbolSolver (CoreExtractor's no-symbol mode).
                        ResolvedType scopeType = resolveExecuteTaskScopeType(executeTaskScope, methodFqn, call);
                        String className = scopeType.asReferenceType().getTypeDeclaration().get().getName();
                        if (className.contains("SchedulerOperationHandler")) {
                            hasSchedulerOperationHandlerCall = true;
                        }
                    } catch (Throwable e) {
                        // Type not resolvable — source roots may not cover this class, or a
                        // 3rd-party jar on the classpath has an ECJ-compiled class that throws
                        // java.lang.Error on unresolved symbols; skip silently either way.
                        System.err.println("[SchedulerResolver] failed to resolve type of executeTask() scope: " + e.getMessage());
                    }
                }
            }

            // DelayedSchedulerUtil.createNewDelayedTaskSchedule(..., taskType) — 9th arg selects
            // which BackgroundTaskDetails-registered class ultimately runs (see DelayedTaskCategory.xml).
            if ("createNewDelayedTaskSchedule".equals(call.getNameAsString()) && call.getScope().isPresent()) {
                try {
                    Expression delayedScheduleScope = call.getScope().get();
                    if (delayedScheduleScope instanceof NameExpr scopeName
                            && "DelayedSchedulerUtil".equals(scopeName.getNameAsString())) {
                        var args = call.getArguments();
                        if (args.size() >= 9) {
                            Long taskTypeValue = resolveDelayedTaskTypeArg(args.get(8));
                            String backgroundTaskId = taskTypeValue == null ? null : mapDelayedTaskTypeToBackgroundTaskId(taskTypeValue);
                            BackgroundTaskDetail detail = backgroundTaskId == null ? null : backgroundTaskIdToDetail.get(backgroundTaskId);
                            if (detail != null && backgroundTaskNamesSeen.add(detail.taskName())) {
                                local.pendingSchedulerSites.add(new PendingSchedulerSite(methodFqn, detail.taskName()));
                            }
                        }
                    }
                } catch (Throwable e) {
                    // Defense-in-depth: resolveDelayedTaskTypeArg/resolveViaSymbolSolver already
                    // catch Throwable internally, but this outer guard ensures a LinkageError/Error
                    // thrown at method-invocation time (before the inner try region even starts)
                    // can never escape visitMethod and abort extraction of the whole source file.
                    System.err.println("[SchedulerResolver] failed to inspect createNewDelayedTaskSchedule call: " + e.getMessage());
                }
            }

            // BGTaskUtil.addToQueue(task) — the queued task object often wraps a DataObject
            // whose rows contain TASK_COMPONENT_ID. Resolve that ID back to BackgroundTaskDetails.
            inspectBgTaskAddToQueue(md, methodFqn, call, backgroundTaskNamesSeen, local);
            
            if (!"getTaskID".equals(call.getNameAsString()) ) continue;
            if (call.getArguments().size() != 1) continue;

            Expression arg = call.getArgument(0);
            String taskName;
            if (arg instanceof StringLiteralExpr sl) {
                taskName = sl.getValue();
            } else if (arg instanceof NameExpr ne) {
                taskName = null;
                try {
                    // Use JavaParserFacade to resolve the symbol declaration without requiring
                    // the CU to have been parsed with a SymbolSolver.
                    var ref = javaParserFacade.solve(ne);
                    if (ref.isSolved()) {
                        ResolvedValueDeclaration resolved = ref.getCorrespondingDeclaration();
                        var astNode = resolved.toAst();
                        if (astNode.isPresent()) {
                            // toAst() returns VariableDeclarator for fields,
                            // but VariableDeclarationExpr for local variables.
                            VariableDeclarator targetVd = null;
                            if (astNode.get() instanceof VariableDeclarator vd) {
                                targetVd = vd;
                            } else if (astNode.get() instanceof VariableDeclarationExpr vde) {
                                for (VariableDeclarator vd : vde.getVariables()) {
                                    if (vd.getNameAsString().equals(ne.getNameAsString())) {
                                        targetVd = vd;
                                        break;
                                    }
                                }
                            }
                            if (targetVd != null
                                    && targetVd.getInitializer().isPresent()
                                    && targetVd.getInitializer().get() instanceof StringLiteralExpr literal) {
                                taskName = literal.getValue();
                            }
                        }
                    }
                } catch (Throwable e) {
                    System.err.println("[SchedulerResolver] failed to resolve variable " + ne.getNameAsString() + ": " + e.getMessage());
                }
            } else {
                continue;
            }
            if (taskName == null || !taskNameToClass.containsKey(taskName)) continue;

            if(taskIDSeen.add(taskName)) {
                taskIDdetails.add(new TaskIDdetails(ownerFqn, methodFqn, taskName));
            }
        }
        
        if (hasSchedulerExecutionStartCall ) {
            taskIDdetails.forEach(taskID -> {
                local.pendingSchedulerSites.add(new PendingSchedulerSite(taskID.callerFqn(), taskID.taskName()));
            });
        } else if (hasSchedulerOperationHandlerCall) {
            taskIDdetails.forEach(taskID -> {
                local.pendingSchedulerSites.add(new PendingSchedulerSite(taskID.callerFqn(), taskID.taskName()));
            });
        }
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (taskNameToClass.isEmpty() && backgroundTaskIdToDetail.isEmpty()) return;

        // Emit ScheduledTaskNode for every class listed in taskflow.xml
        java.util.Set<String> seenClasses = new java.util.HashSet<>();
        for (String classFqn : taskNameToClass.values()) {
            String taskName ="";
            Optional<String> matchedKey = taskNameToClass.entrySet().stream()
            .filter(entry -> classFqn.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .findFirst();

            if(matchedKey.isPresent()) {
                taskName = matchedKey.get();
            }
            if (seenClasses.add(classFqn)) {
                batch.scheduledTasks.add(new ScheduledTaskNode(classFqn, taskName));
            }
        }

        // Create new scheduledTask for every BackgroundTaskDetails class from DelayedTaskCategory.xml
        java.util.Set<String> seenBackgroundClasses = new java.util.HashSet<>();
        for (BackgroundTaskDetail detail : backgroundTaskIdToDetail.values()) {
            if (seenBackgroundClasses.add(detail.classFqn())) {
                batch.scheduledTasks.add(new ScheduledTaskNode(detail.classFqn(), detail.taskName()));
            }
        }

        int  scheduleToMethodEdge = 0, scheduleEdges = 0;
        // Tag executeTask() methods in scheduled-task classes and build classToExecuteFqn
        // (covers both taskflow.xml classes AND DelayedTaskCategory.xml background-task classes).
        Set<String> allTaskClassFqns = new HashSet<>(classFqnToTaskName.keySet());
        for (BackgroundTaskDetail detail : backgroundTaskIdToDetail.values()) {
            allTaskClassFqns.add(detail.classFqn());
        }
        Map<String, String> classToExecuteFqn = new HashMap<>();
        for ( MethodNode mn  : batch.methods){
            for (String classFqn : allTaskClassFqns) {

                if (mn.ownerFqn().equals(classFqn) && mn.simpleName().contains("executeTask")) {
                    classToExecuteFqn.put(classFqn, mn.fqn());
                    batch.schedulesToMethod.add(new SchedulesToMethodEdge(classFqn, mn.fqn(), -1L));
                    scheduleToMethodEdge++;
                }
                // classToExecuteFqn.put(classFqn, classFqn + ".executeTask()");
                // batch.schedulesToMethod.add(new SchedulesToMethodEdge(classFqn, classFqn + ".executeTask()", -1L));
                // scheduleToMethodEdge++;
            }
        }
        

        // Emit edges from pendingSchedulerSites collected during Pass 1
        
        for (ExtractionBatch.PendingSchedulerSite site : batch.pendingSchedulerSites) {
            String classFqn = taskNameToClass.get(site.taskName());
            if (classFqn == null) classFqn = findBackgroundClassFqnByTaskName(site.taskName());
            if (classFqn == null) continue;
        
            batch.schedules.add(new SchedulesEdge(site.callerFqn(), classFqn, -1L));
            scheduleEdges++;

            String methodFqn = classToExecuteFqn.get(classFqn);
            if (methodFqn != null) {
                batch.schedulesToMethod.add(new SchedulesToMethodEdge(classFqn, methodFqn, -1L));
                scheduleToMethodEdge++;
            }
        }

        System.out.printf(
            "[SchedulerResolver] scheduledTasks=%d  backgroundTasks=%d  entryPoints=%d   schedule-edges=%d  schedule-to-method-edges=%d%n",
            seenClasses.size(), seenBackgroundClasses.size(), classToExecuteFqn.size(), scheduleEdges, scheduleToMethodEdge);
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private void inspectBgTaskAddToQueue(MethodDeclaration md, String methodFqn, MethodCallExpr call,
                                         Set<String> backgroundTaskNamesSeen, ExtractionBatch local) {
        if (!"addToQueue".equals(call.getNameAsString()) || call.getScope().isEmpty()) return;
        if (!(call.getScope().get() instanceof NameExpr scopeName)
                || !"BGTaskUtil".equals(scopeName.getNameAsString())) return;
        if (call.getArguments().size() != 1) return;

        String dataObjectName = null;
        String constructorTaskName = null;
        Expression queuedTaskExpr = call.getArgument(0);
        if (queuedTaskExpr instanceof NameExpr taskVar) {
            ObjectCreationExpr initializer = findObjectCreationInitializer(md, taskVar.getNameAsString());
            if (initializer != null) {
                constructorTaskName = initializer.getType().getNameAsString();
                dataObjectName = firstArgName(initializer);
            }
        } else if (queuedTaskExpr instanceof ObjectCreationExpr initializer) {
            constructorTaskName = initializer.getType().getNameAsString();
            dataObjectName = firstArgName(initializer);
        }

        boolean emitted = false;
        if (dataObjectName != null) {
            Set<String> taskIds = findTaskComponentIdsForDataObject(md, dataObjectName);
            if (taskIds.isEmpty()) {
                taskIds = findTaskComponentIdsForDataObjectInClass(md, dataObjectName);
            }
            for (String taskId : taskIds) {
                BackgroundTaskDetail detail = backgroundTaskIdToDetail.get(taskId);
                if (detail != null && backgroundTaskNamesSeen.add(detail.taskName())) {
                    local.pendingSchedulerSites.add(new PendingSchedulerSite(methodFqn, detail.taskName()));
                    emitted = true;
                }
            }
        }

        if (!emitted && constructorTaskName != null) {
            BackgroundTaskDetail detail = findBackgroundTaskBySimpleName(constructorTaskName);
            if (detail != null && backgroundTaskNamesSeen.add(detail.taskName())) {
                local.pendingSchedulerSites.add(new PendingSchedulerSite(methodFqn, detail.taskName()));
            }
        }
    }

    private static ObjectCreationExpr findObjectCreationInitializer(MethodDeclaration md, String variableName) {
        for (VariableDeclarator vd : md.findAll(VariableDeclarator.class)) {
            if (!vd.getNameAsString().equals(variableName)) continue;
            if (vd.getInitializer().isPresent() && vd.getInitializer().get() instanceof ObjectCreationExpr oce) {
                return oce;
            }
        }
        return null;
    }

    private static String firstArgName(ObjectCreationExpr oce) {
        if (oce.getArguments().isEmpty()) return null;
        Expression arg = oce.getArgument(0);
        if (arg instanceof NameExpr ne) return ne.getNameAsString();
        return normalizeVarText(arg.toString());
    }

    private static Set<String> findTaskComponentIdsForDataObject(MethodDeclaration md, String dataObjectName) {
        Set<String> taskIds = new HashSet<>();
        collectTaskComponentIdsForDataObject(md, dataObjectName, taskIds);
        return taskIds;
    }

    private static Set<String> findTaskComponentIdsForDataObjectInClass(MethodDeclaration md, String dataObjectName) {
        Set<String> taskIds = new HashSet<>();
        var owner = md.findAncestor(ClassOrInterfaceDeclaration.class);
        if (owner.isEmpty()) return taskIds;
        for (MethodDeclaration ownerMethod : owner.get().findAll(MethodDeclaration.class)) {
            collectTaskComponentIdsForDataObject(ownerMethod, dataObjectName, taskIds);
        }
        return taskIds;
    }

    private static void collectTaskComponentIdsForDataObject(MethodDeclaration md, String dataObjectName, Set<String> taskIds) {
        String normalizedDataObjectName = normalizeVarText(dataObjectName);
        for (MethodCallExpr addRowCall : md.findAll(MethodCallExpr.class)) {
            if (!"addRow".equals(addRowCall.getNameAsString()) || addRowCall.getScope().isEmpty()) continue;
            if (!normalizedDataObjectName.equals(normalizeVarText(addRowCall.getScope().get().toString()))) continue;
            if (addRowCall.getArguments().isEmpty() || !(addRowCall.getArgument(0) instanceof NameExpr rowVar)) continue;

            String rowVarName = rowVar.getNameAsString();
            for (MethodCallExpr setCall : md.findAll(MethodCallExpr.class)) {
                if (!"set".equals(setCall.getNameAsString()) || setCall.getScope().isEmpty()) continue;
                if (!(setCall.getScope().get() instanceof NameExpr setScope)
                        || !rowVarName.equals(setScope.getNameAsString())) continue;
                if (setCall.getArguments().size() < 2 || !isTaskComponentIdKey(setCall.getArgument(0))) continue;
                String taskId = resolveTaskComponentId(setCall.getArgument(1));
                if (taskId != null) taskIds.add(taskId);
            }
        }
    }

    private static boolean isTaskComponentIdKey(Expression expr) {
        if (expr instanceof StringLiteralExpr sl) {
            return "TASK_ID".equals(sl.getValue()) || "TASK_COMPONENT_ID".equals(sl.getValue());
        }
        if (expr instanceof FieldAccessExpr fae) {
            return "TASK_COMPONENT_ID".equals(fae.getNameAsString()) || "COMPONENT_ID".equals(fae.getNameAsString());
        }
        if (expr instanceof NameExpr ne) {
            return "TASK_COMPONENT_ID".equals(ne.getNameAsString()) || "COMPONENT_ID".equals(ne.getNameAsString());
        }
        return false;
    }

    private static String resolveTaskComponentId(Expression expr) {
        if (expr instanceof IntegerLiteralExpr ile) return normalizeNumericLiteral(ile.getValue());
        if (expr instanceof LongLiteralExpr lle) return normalizeNumericLiteral(lle.getValue());
        String constantName = null;
        if (expr instanceof FieldAccessExpr fae) constantName = fae.getNameAsString();
        else if (expr instanceof NameExpr ne) constantName = ne.getNameAsString();
        if (constantName == null) return null;
        return switch (constantName) {
            case "MEMBER_OF_TASK_ID" -> "1";
            case "CROSS_MEMBER_OF_TASK_ID" -> "2";
            case "EXPORT_MAILBOX_TASK_ID" -> "3";
            case "EXCH_ONLINE_TASK_ID" -> "4";
            case "EXCH_ONLINE_ROOM_MBX_TASK_ID" -> "5";
            case "MSTEAMS_TASK_ID" -> "6";
            case "ENABLE_MFA_TASK_ID" -> "7";
            case "REPLICATE_DC_TASK_ID" -> "8";
            case "TIME_SYNC_TASK_ID" -> "9";
            case "FSM_AUTOMATION_TASK" -> "10";
            case "MIGRATION_TASK" -> "11";
            case "BUM_MEMBER_OF_TASK_ID" -> "12";
            default -> null;
        };
    }

    private static String normalizeNumericLiteral(String value) {
        if (value == null) return null;
        String normalized = value.trim().replace("_", "");
        if (normalized.endsWith("L") || normalized.endsWith("l")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeVarText(String text) {
        if (text == null) return null;
        String normalized = text.trim();
        return normalized.startsWith("this.") ? normalized.substring("this.".length()) : normalized;
    }

    private BackgroundTaskDetail findBackgroundTaskBySimpleName(String simpleName) {
        if (simpleName == null || simpleName.isEmpty()) return null;
        for (BackgroundTaskDetail detail : backgroundTaskIdToDetail.values()) {
            if (detail.classFqn().endsWith("." + simpleName) || detail.taskName().equals(simpleName)) return detail;
        }
        return null;
    }

    private ResolvedType resolveExecuteTaskScopeType(Expression executeTaskScope, String methodFqn, MethodCallExpr call) {
        try {
            return javaParserFacade.getType(executeTaskScope);
        } catch (Throwable e) {
            int line = call.getBegin().map(p -> p.line).orElse(-1);
            String scopeKind = executeTaskScope == null ? "null" : executeTaskScope.getClass().getSimpleName();
            String scopeText = executeTaskScope == null ? "" : executeTaskScope.toString();
            throw new IllegalStateException("method=" + methodFqn
                    + ", line=" + line
                    + ", scopeKind=" + scopeKind
                    + ", scope=`" + scopeText + "`"
                    + ", call=`" + call + "`: " + e.getMessage(), e);
        }
    }

    /**
     * Resolves the delayed-task-type argument (the 9th parameter of
     * {@code DelayedSchedulerUtil.createNewDelayedTaskSchedule(...)}) to a numeric value.
     * Handles three shapes seen in practice:
     * <ol>
     *   <li>a literal, e.g. {@code 3L}</li>
     *   <li>an unqualified reference to a {@code static final Long} field declared in the
     *       SAME enclosing class, e.g. {@code MS_TEAMS_TASK_ID} \u2014 resolved directly from the
     *       AST (no SymbolSolver needed), since these are the majority case and the most
     *       reliable to resolve</li>
     *   <li>a qualified reference to a field on ANOTHER class, e.g.
     *       {@code O365MgmtUtil.modifyExchOnlineMailboxTaskTypeId} \u2014 resolved via SymbolSolver
     *       since the field's declaration lives in a different source file</li>
     * </ol>
     * Returns {@code null} when none of these shapes match or resolution fails.
     */
    private Long resolveDelayedTaskTypeArg(Expression arg) {
        if (arg instanceof LongLiteralExpr lle) {
            return parseLongLiteral(lle.getValue());
        }
        if (arg instanceof NameExpr ne) {
            Long fromSameClass = findFieldLiteralInEnclosingClass(ne, ne.getNameAsString());
            if (fromSameClass != null) return fromSameClass;
            return resolveViaSymbolSolver(ne);
        }
        if (arg instanceof FieldAccessExpr fae) {
            return resolveViaSymbolSolver(fae);
        }
        return null;
    }

    /**
     * AST-only fast path: scans the top-level class enclosing {@code context} for a
     * {@code static final Long NAME = <literal>L;}-shaped field named {@code fieldName}.
     * Covers the common same-class constant-reference case without depending on
     * SymbolSolver, which has proven unreliable for some unqualified field references.
     */
    private static Long findFieldLiteralInEnclosingClass(Expression context, String fieldName) {
        var cls = context.findAncestor(ClassOrInterfaceDeclaration.class);
        if (cls.isEmpty()) return null;
        for (FieldDeclaration fd : cls.get().getFields()) {
            for (VariableDeclarator vd : fd.getVariables()) {
                if (vd.getNameAsString().equals(fieldName)
                        && vd.getInitializer().isPresent()
                        && vd.getInitializer().get() instanceof LongLiteralExpr literal) {
                    return parseLongLiteral(literal.getValue());
                }
            }
        }
        return null;
    }

    /**
     * Resolves a {@link NameExpr} or {@link FieldAccessExpr} to the {@code Long} literal
     * value of the field/variable it refers to, via SymbolSolver. Needed for qualified
     * references like {@code OtherClass.CONSTANT} whose declaration lives in a different
     * source file than the call site.
     */
    private Long resolveViaSymbolSolver(Expression exprNode)  {
        try {
            String simpleName;
            ResolvedValueDeclaration resolved;
            if (exprNode instanceof NameExpr ne) {
                var ref = javaParserFacade.solve(ne);
                if (!ref.isSolved()) return null;
                resolved = ref.getCorrespondingDeclaration();
                simpleName = ne.getNameAsString();
            } else if (exprNode instanceof FieldAccessExpr fae) {
                var ref = javaParserFacade.solve(fae);
                if (!ref.isSolved()) return null;
                resolved = ref.getCorrespondingDeclaration();
                simpleName = fae.getNameAsString();
            } else {
                return null;
            }
            var astNode = resolved.toAst();
            if (astNode.isEmpty()) return null;
            VariableDeclarator targetVd = null;
            if (astNode.get() instanceof VariableDeclarator vd) {
                targetVd = vd;
            } else if (astNode.get() instanceof FieldDeclaration fd) {
                for (VariableDeclarator vd : fd.getVariables()) {
                    if (vd.getNameAsString().equals(simpleName)) {
                        targetVd = vd;
                        break;
                    }
                }
            } else if (astNode.get() instanceof VariableDeclarationExpr vde) {
                for (VariableDeclarator vd : vde.getVariables()) {
                    if (vd.getNameAsString().equals(simpleName)) {
                        targetVd = vd;
                        break;
                    }
                }
            }
            if (targetVd != null
                    && targetVd.getInitializer().isPresent()
                    && targetVd.getInitializer().get() instanceof LongLiteralExpr literal) {
                return parseLongLiteral(literal.getValue());
            }
        } catch (Throwable e) {
            System.err.println("[SchedulerResolver] failed to resolve delayed-task-type expr `"
                + exprNode + "`: " + e.getMessage());
        }
        return null;
    }

    private static Long parseLongLiteral(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.endsWith("L") || v.endsWith("l")) v = v.substring(0, v.length() - 1);
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Maps the numeric task-type value passed to
     * {@code DelayedSchedulerUtil.createNewDelayedTaskSchedule(...)} to the
     * {@code BackgroundTaskDetails TASK_ID} of the concrete class that ultimately runs it.
     */
    private static String mapDelayedTaskTypeToBackgroundTaskId(long value) {
        return switch ((int) value) {
            case 2 -> "5";
            case 3 -> "6";
            case 4 -> "7";
            default -> "4";
        };
    }

    private String findBackgroundClassFqnByTaskName(String taskName) {
        for (BackgroundTaskDetail detail : backgroundTaskIdToDetail.values()) {
            if (detail.taskName().equals(taskName)) return detail.classFqn();
        }
        return null;
    }

    private static Map<String, String> buildStrVars(MethodDeclaration md) {
        Map<String, String> vars = new HashMap<>();
        for (VariableDeclarator vd : md.findAll(VariableDeclarator.class)) {
            if (vd.getInitializer().isEmpty()) continue;
            Expression init = vd.getInitializer().get();
            if (init instanceof StringLiteralExpr sl) {
                vars.put(vd.getNameAsString(), sl.getValue());
            }
        }
        return vars;
    }

    private static List<String> addLabels(MethodNode m, String... labelsToAdd) {
        List<String> existing = m.extraLabels() == null ? List.of() : m.extraLabels();
        boolean anyNew = false;
        for (String l : labelsToAdd) {
            if (!existing.contains(l)) { anyNew = true; break; }
        }
        if (!anyNew) return null;
        List<String> merged = new ArrayList<>(existing);
        for (String l : labelsToAdd) {
            if (!merged.contains(l)) merged.add(l);
        }
        return merged;
    }
}
