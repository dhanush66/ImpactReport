package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.ReturnStmt;

import com.github.javaparser.resolution.declarations.ResolvedValueDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import io.spmp.impact.extract.BodyCollector;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.extract.GlobalIndex;
import io.spmp.impact.extract.GlobalIndex.MethodSig;
import io.spmp.impact.model.GraphEdges.CallEdge;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Resolves {@code Class.forName(...)} reflective dispatch into real :CALLS edges.
 *
 * <p>Reflection is invisible to a call graph built from the AST: the target class is a
 * <em>string</em>, so nothing links the caller to the method that actually runs. This repo has
 * 451 {@code Class.forName} sites, and the framework dispatches whole subsystems through them
 * (workflow commit listeners, report listeners, data providers, transformers).
 *
 * <h2>Call shapes handled</h2>
 * All resolve to {@code <target>.<method>(<params>)}:
 * <pre>
 *   Class c = Class.forName("…TicketHandler");                 // 1. getMethod + invoke,
 *   Method m = c.getMethod("invalidateTickets", Long.class);   //    Method parked in a local
 *   m.invoke(loginId);
 *
 *   Class k = Class.forName("…HDTAuditReportListener");        // 2. getConstructor + newInstance
 *   Constructor&lt;AuditTask&gt; ctor = k.getConstructor(Integer.class);
 *   AuditTask l = (AuditTask) ctor.newInstance(7038);
 *
 *   Class.forName("…WorkFlowClientUtil")                       // 3. fully chained
 *        .getMethod("export", HttpServletRequest.class, …).invoke(null, …);
 *
 *   Class dp = Class.forName(this.domainSpecificDataProviderClass);   // 4. newInstance +
 *   IDataProvider p = (IDataProvider) dp.newInstance();               //    interface cast
 *   p.setDefaultValue(a, b, c, d, e, f);
 * </pre>
 * Shape 4 matters most: without it {@code p.setDefaultValue(...)} resolves only to the
 * <em>interface</em> declaration, never the concrete implementation.
 *
 * <h2>Table-backed targets</h2>
 * Only 140 of the 451 sites pass a literal; 311 pass an expression, and for many the value is
 * a DB column — {@code WFTask} does {@code getRow("ADSMWorkFlowActions", …).get("CLASS_NAME")}
 * and feeds that into {@code Class.forName}. The exact class is unknowable statically, but the
 * <em>set of possible</em> classes is exactly that table's CLASS_NAME column, which ships as
 * seed XML. So when the argument traces back to a table read, every class value in that
 * table's column becomes a candidate and each gets an edge — see {@link TableClassNameIndex}
 * and {@link #tableColumnBehind}.
 *
 * <p>The trace crosses methods and files. In the requester-listener flow the query is three
 * calls away from the reflection, in a different class entirely:
 * <pre>
 *   Hashtable h = RequesterHandler.getTechnicianListenerDetails("Requester");
 *   Properties p = (Properties) h.get(classType);
 *   String className = p.getProperty("className");
 *   Class.forName(className).newInstance();
 * </pre>
 * so the tracer steps {@code className → p.getProperty(…) → p → h.get(…) → h →
 * getTechnicianListenerDetails(…)} and then looks INSIDE that method, where
 * {@code getPersistence().get("TechnicianCatagory", cri)} and
 * {@code r.get("LISTENER_CLASS_NAME")} finally name the table and column.
 *
 * <h2>Emitting at getMethod, not at invoke</h2>
 * The edge is emitted where {@code getMethod} / {@code getConstructor} appears, not where
 * {@code invoke} does. Shapes 1 and 2 park the handle in a local first, so the {@code invoke}
 * site knows only a {@code Method}, not a class; tying them together needs dataflow. Looking a
 * method up reflectively is strong enough evidence of a call.
 */
public class ReflectionResolver implements BoundaryResolver {

    /** How many unresolved Class.forName targets to print; there are hundreds. */
    private static final int UNRESOLVED_PRINT_LIMIT = 25;

    /** Max name-to-name hops when chasing a variable back to a literal or a table read. */
    private static final int MAX_NAME_HOPS = 15;

    /** Max parent-chain steps when looking for the variable an expression is assigned to. */
    private static final int MAX_PARENT_HOPS = 8;

    /** Recursion guard for constant folding through named references. */
    private static final int MAX_CONSTANT_HOPS = 4;

    /**
     * Cap on candidates fanned out from one table. {@code ADSMServletAPIMapping} carries 824
     * CLASS_NAME values; multiplying that by every reflective call site would swamp the graph
     * with edges that are individually near-worthless. Over the cap the site is reported
     * instead.
     */
    private static final int MAX_TABLE_CANDIDATES = 250;

    /** Reflection API methods that name a member on a {@code Class} object. */
    private static final Set<String> VALUE_PRESERVING =
        Set.of("trim", "toString", "intern", "strip");

    /** A reflective call found in Pass 1, pending index lookup in afterAll. */
    private record ReflectiveCall(String callerFqn, String ownerFqn, String simpleName,
                                  int arity, List<String> paramTypes, String shape) {}

    private final ConcurrentLinkedQueue<ReflectiveCall> pending = new ConcurrentLinkedQueue<>();

    /** {@code Class.forName(expr)} sites whose argument is not a resolvable class name. */
    private final ConcurrentLinkedQueue<String> unresolvedTargets = new ConcurrentLinkedQueue<>();

    private final TableClassNameIndex tables;
    private volatile GlobalIndex index;

    /** @param tableXmlDir directory of table-seed XML (config key {@code TableXmlDir}); may be null */
    public ReflectionResolver(Path tableXmlDir) {
        this.tables = new TableClassNameIndex(tableXmlDir);
    }

    public ReflectionResolver() {
        this(null);
    }

    @Override
    public void indexFrozen(GlobalIndex index) {
        this.index = index;
    }

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // Nothing per-CU: reflective dispatch is always inside a method body.
    }

    @Override
    public void visitMethod(MethodDeclaration md, String methodFqn, String ownerFqn,
                            List<BodyCollector.CallSite> calls, ExtractionBatch local) {
        List<MethodCallExpr> forNameCalls = new ArrayList<>();
        for (MethodCallExpr mc : md.findAll(MethodCallExpr.class)) {
            if (isClassForName(mc)) forNameCalls.add(mc);
        }
        if (forNameCalls.isEmpty()) return;

        // ── Phase 1: resolve each Class.forName to one or more candidate target classes ──
        Map<String, List<String>> classVars = new LinkedHashMap<>();     // varName -> candidates
        Map<String, List<String>> instanceVars = new HashMap<>();        // varName -> candidates
        for (MethodCallExpr fn : forNameCalls) {
            List<String> targets = resolveTargets(fn, md);
            if (targets.isEmpty()) continue;                 // already recorded as unresolved

            // Shape 3: the Class object is used inline, e.g. Class.forName(x).getMethod(...)
            chainedCallOn(fn).ifPresent(outer -> {
                for (String t : targets) onClassObjectCall(outer, t, methodFqn);

                // …and when that inline chain is newInstance(), the RESULT is what gets
                // stored, not the Class:
                //   StatusUpdater su = (StatusUpdater) Class.forName(x).newInstance();
                // assignedVariableName(fn) is null here — fn's parent is the newInstance
                // call, not the declarator — so the instance variable has to be registered
                // from the outer call instead, or every later su.foo() is missed.
                if ("newInstance".equals(outer.getNameAsString())) {
                    String instVar = assignedVariableName(outer);
                    if (instVar != null) instanceVars.put(instVar, targets);
                }
            });

            // Shapes 1/2/4: the Class object lands in a variable.
            String var = assignedVariableName(fn);
            if (var != null) classVars.put(var, targets);
        }
        if (classVars.isEmpty() && instanceVars.isEmpty()) return;

        // ── Phase 2: instance variables produced by <classVar>.newInstance() ──
        for (MethodCallExpr mc : md.findAll(MethodCallExpr.class)) {
            if (!"newInstance".equals(mc.getNameAsString())) continue;
            List<String> owners = classVarOfScope(mc, classVars);
            if (owners == null) continue;
            String var = assignedVariableName(mc);
            if (var != null) instanceVars.put(var, owners);
        }

        // ── Phase 3: sweep every call and record ──
        for (MethodCallExpr mc : md.findAll(MethodCallExpr.class)) {
            // (a) reflection API called on a Class variable
            List<String> classOwners = classVarOfScope(mc, classVars);
            if (classOwners != null) {
                for (String owner : classOwners) onClassObjectCall(mc, owner, methodFqn);
                continue;
            }
            // (b) a real call on a variable holding the reflectively-created instance
            if (mc.getScope().isEmpty()) continue;
            if (!(unwrap(mc.getScope().get()) instanceof NameExpr ne)) continue;
            List<String> instOwners = instanceVars.get(ne.getNameAsString());
            if (instOwners == null) continue;
            // Arguments here are VALUE expressions, not X.class literals, so their types
            // come from resolving each one. Computed once and shared across the candidates.
            List<String> argTypes = resolvedArgumentTypes(mc.getArguments());
            for (String owner : instOwners) {
                pending.add(new ReflectiveCall(methodFqn, owner, mc.getNameAsString(),
                    mc.getArguments().size(), argTypes, "reflect-instance"));
            }
        }
    }

    /**
     * Handles a call made ON a {@code Class} object: {@code getMethod},
     * {@code getDeclaredMethod}, {@code getConstructor}, {@code getDeclaredConstructor}, or a
     * bare {@code newInstance()}.
     */
    private void onClassObjectCall(MethodCallExpr call, String ownerFqn, String callerFqn) {
        switch (call.getNameAsString()) {
            case "getMethod", "getDeclaredMethod" -> {
                if (call.getArguments().isEmpty()) return;
                String name = literalOf(call.getArgument(0));
                if (name == null) {
                    unresolvedTargets.add(describe(call, "method name not a literal: " + call));
                    return;
                }
                // arg 0 is the method name; the rest are parameter Class literals, which give
                // us real declared types to match on — not just a count.
                List<Expression> rest = new ArrayList<>(call.getArguments()).subList(
                    1, call.getArguments().size());
                pending.add(new ReflectiveCall(callerFqn, ownerFqn, name,
                    rest.size(), classLiteralTypes(rest), "reflect-getMethod"));
            }
            case "getConstructor", "getDeclaredConstructor" ->
                pending.add(new ReflectiveCall(callerFqn, ownerFqn, "<init>",
                    call.getArguments().size(), classLiteralTypes(call.getArguments()),
                    "reflect-getConstructor"));
            case "newInstance" ->
                // Class.newInstance() is always the no-arg constructor; the
                // Constructor.newInstance(args) form is covered by getConstructor above.
                pending.add(new ReflectiveCall(callerFqn, ownerFqn, "<init>", 0,
                    List.of(), "reflect-newInstance"));
            default -> { /* getName, isAssignableFrom, … — not a dispatch */ }
        }
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (pending.isEmpty() && unresolvedTargets.isEmpty()) {
            System.out.println("[ReflectionResolver] no Class.forName dispatch found.");
            return;
        }
        if (index == null) {
            System.err.println("[ReflectionResolver] index was never handed over; "
                + pending.size() + " reflective call(s) dropped.");
            return;
        }

        Set<String> emitted = new HashSet<>();
        int exact = 0, byTypes = 0, inherited = 0, placeholder = 0;
        Map<String, Integer> byShape = new LinkedHashMap<>();

        for (ReflectiveCall rc : pending) {
            byShape.merge(rc.shape(), 1, Integer::sum);

            String targetFqn = null;
            String strategy = rc.shape();

            // Does the call site tell us the declared parameter types? getMethod /
            // getConstructor do, via their X.class literals; reflect-instance does not —
            // it only has argument expressions, so arity is all there is to go on there.
            boolean typed = rc.paramTypes() != null && !rc.paramTypes().isEmpty();

            // ── Match on the owner: arity first, then the parameter types ──
            for (MethodSig cand : methodsOn(rc.ownerFqn(), rc.simpleName())) {
                if (cand.paramCount() != rc.arity()) continue;
                if (typed && !typesCompatible(rc.paramTypes(), paramTypesOf(cand.fqn()))) {
                    // Right name, right count, wrong types — e.g. getMethod("x", A.class)
                    // against x(B). Skip it rather than assert an edge that does not
                    // typecheck; an unmatched call still lands as a placeholder below.
                    continue;
                }
                targetFqn = cand.fqn();
                if (typed) byTypes++; else exact++;
                break;
            }

            if (targetFqn == null) {
                // Level-by-level: the immediate parents first, then THEIR parents, and so on.
                // A flat transitiveAncestors() set loses depth ordering, so a grandparent's
                // method could win over the parent's override of it — the opposite of what
                // virtual dispatch does.
                String found = searchAncestorsBreadthFirst(rc);
                if (found != null) {
                    targetFqn = found;
                    strategy = rc.shape() + "-inherited";
                    inherited++;
                }
            }
            if (targetFqn == null) {
                // Keep the call in the graph even with no match, as Pass 2 does: Neo4j MERGE
                // creates a placeholder :Method that downstream queries see as an orphan.
                targetFqn = rc.ownerFqn() + "." + rc.simpleName() + "(?)";
                strategy = rc.shape() + "-no-match";
                placeholder++;
            }
            if (emitted.add(rc.callerFqn() + "#" + targetFqn)) {
                batch.calls.add(new CallEdge(rc.callerFqn(), targetFqn, strategy));
            }
        }

        System.out.printf("[ReflectionResolver] reflective calls=%d  edges=%d "
                + "(arity=%d typed=%d inherited=%d placeholder=%d)  unresolvedTargets=%d%n",
            pending.size(), emitted.size(), exact, byTypes, inherited, placeholder,
            unresolvedTargets.size());
        System.out.println("[ReflectionResolver] by shape: " + byShape);
        if (!tables.isEmpty()) {
            System.out.println("[ReflectionResolver] table seed data: " + tables.stats());
        }

        if (!unresolvedTargets.isEmpty()) {
            System.out.println("[ReflectionResolver] Class.forName targets not resolvable to a "
                + "class name or table (showing up to " + UNRESOLVED_PRINT_LIMIT + "):");
            int n = 0;
            for (String u : unresolvedTargets) {
                if (n++ >= UNRESOLVED_PRINT_LIMIT) break;
                System.out.println("    " + u);
            }
            if (unresolvedTargets.size() > UNRESOLVED_PRINT_LIMIT) {
                System.out.println("    … and " + (unresolvedTargets.size() - UNRESOLVED_PRINT_LIMIT)
                    + " more.");
            }
        }
    }

    // ─── index lookup ───────────────────────────────────────────────────────

    /**
     * Picks the matching overload: arity first, then parameter types when the call site gave
     * us {@code X.class} literals to match against.
     *
     * <p>Comparison is on simple names and lenient, because the two sides are produced by
     * different machinery: the call site writes {@code HttpServletRequest.class} (a simple
     * name, as written) while {@link MethodSig#fqn()} carries Pass 1's import-resolved FQN,
     * which is {@code ?} wherever an import could not be resolved.
     */
    /** The class declaration enclosing {@code md}, falling back to {@code md} itself. */
    private static Node enclosingClassOf(MethodDeclaration md) {
        return md.findAncestor(ClassOrInterfaceDeclaration.class)
                 .map(Node.class::cast)
                 .orElse(md);
    }

    /** Candidates for {@code owner.simpleName}, never null. */
    private List<MethodSig> methodsOn(String owner, String simpleName) {
        List<MethodSig> ms = index.methodsOn(owner, simpleName);
        return ms == null ? List.of() : ms;
    }

    /**
     * The match rule used both on the owner (inline in {@link #afterAll}) and on every
     * ancestor generation: same arity, and — when the call site declared parameter types —
     * types that agree.
     */
    private static boolean signatureMatches(MethodSig cand, ReflectiveCall rc) {
        if (cand.paramCount() != rc.arity()) return false;
        if (rc.paramTypes() == null || rc.paramTypes().isEmpty()) return true;
        return typesCompatible(rc.paramTypes(), paramTypesOf(cand.fqn()));
    }

    /**
     * Walks up the hierarchy one generation at a time: the immediate parents, then their
     * immediate parents, and so on. Returns the FQN of the first match found at the shallowest
     * depth.
     */
    private String searchAncestorsBreadthFirst(ReflectiveCall rc) {
        Set<String> seen = new HashSet<>();
        Deque<String> frontier = new ArrayDeque<>(index.directParents(rc.ownerFqn()));
        seen.add(rc.ownerFqn());
        seen.addAll(frontier);

        while (!frontier.isEmpty()) {
            // Drain exactly this generation before descending to the next.
            int generation = frontier.size();
            List<String> next = new ArrayList<>();
            for (int i = 0; i < generation; i++) {
                String owner = frontier.poll();
                if (owner == null) continue;
                // Same arity + parameter-type rule the owner lookup uses.
                for (MethodSig cand : methodsOn(owner, rc.simpleName())) {
                    if (signatureMatches(cand, rc)) return cand.fqn();
                }
                for (String parent : index.directParents(owner)) {
                    if (seen.add(parent)) next.add(parent);
                }
            }
            frontier.addAll(next);
        }
        return null;
    }

    // ─── target resolution ──────────────────────────────────────────────────

    /**
     * Resolves a {@code Class.forName(arg)} argument to candidate class FQNs.
     *
     * <p>Order: a literal (or literal concatenation); a literal that names a TABLE, which fans
     * out to that table's CLASS_NAME column; a variable/field/getter chased back to a literal;
     * and finally a variable chased back to a table read, which fans out the same way.
     * Anything still unresolved is recorded for the report rather than guessed.
     */
    private List<String> resolveTargets(MethodCallExpr forName, MethodDeclaration md) {
        if (forName.getArguments().isEmpty()) return List.of();
        Expression arg = unwrapValuePreserving(forName.getArgument(0));

        // 1. Direct literal — either an FQN, or a bare table name.
        String literal = literalOf(arg);
        if (literal != null) {
            List<String> viaTable = candidatesForTable(literal, forName, "literal");
            if (viaTable != null) return viaTable;
            String fqn = asClassName(literal);
            if (fqn != null) return List.of(fqn);
            unresolvedTargets.add(describe(forName, "literal is neither a class nor a table: \""
                + literal + "\""));
            return List.of();
        }

        // 2. A name: chase it back through initializers/assignments.
        String name = nameOf(arg);
        if (name == null) {
            unresolvedTargets.add(describe(forName, "expression: " + arg));
            return List.of();
        }

        String viaLiteral = literalFromDeclaration(name, md);
        if (viaLiteral != null) {
            List<String> viaTable = candidatesForTable(viaLiteral, forName, "constant");
            if (viaTable != null) return viaTable;
            String fqn = asClassName(viaLiteral);
            if (fqn != null) return List.of(fqn);
        }

        // 3. The value comes from a table column — fan out over that table's class names.
        String table = tableColumnBehind(arg, md);
        if (table != null) {
            List<String> viaTable = candidatesForTable(table, forName, "table-column");
            if (viaTable != null) return viaTable;
            unresolvedTargets.add(describe(forName, "'" + name + "' reads table '" + table
                + "', which has no class-valued seed data"));
            return List.of();
        }

        unresolvedTargets.add(describe(forName, "'" + name + "' is runtime-valued "
            + "(no literal initializer, no table read found)"));
        return List.of();
    }

    /**
     * If {@code candidate} names a table with CLASS_NAME seed data, its class list.
     * Returns {@code null} when it is not a table (so the caller can try other routes), and an
     * EMPTY list when it is a table but over {@link #MAX_TABLE_CANDIDATES}.
     */
    private List<String> candidatesForTable(String table, MethodCallExpr at, String how) {
        if (table == null || table.isEmpty()) return null;
        // A class FQN is never a table name. Without this guard a literal like "com.foo.Bar"
        // that happened to collide with a table would be fanned out instead of used directly.
        if (looksLikeClassFqn(table)) return null;
        if (!tables.isKnownTable(table)) return null;

        // Try each known class-bearing column in turn and take the first that has values.
        String column = null;
        List<String> classes = List.of();
        for (String candidateColumn : CLASS_NAME_COLUMNS) {
            List<String> hit = tables.classNamesForExactColumn(table, candidateColumn);
            if (!hit.isEmpty()) {
                column = candidateColumn;
                classes = hit;
                break;
            }
        }
        if (classes.isEmpty()) return null;

        String label = table + "." + column;
        if (classes.size() > MAX_TABLE_CANDIDATES) {
            unresolvedTargets.add(describe(at, label + " has " + classes.size()
                + " class values (> " + MAX_TABLE_CANDIDATES + "), fan-out skipped"));
            return List.of();
        }
        System.out.println("[ReflectionResolver] " + describe(at, "via " + how + ", " + label
            + " -> " + classes.size() + " candidate class(es)"));
        return classes;
    }


    /**
     * Table identifier from a {@code getRow} / {@code getFirstValue} first argument: the value
     * of a literal, or the trailing name of a constant reference — {@code ADSMREQUESTS.TABLE}
     * and the bare constant {@code ADSMWorkFlowActions} both being common in this codebase.
     */
    private static String tableToken(Expression e) {
        Expression x = unwrap(e);
        if (x instanceof StringLiteralExpr sle) return sle.getValue();
        if (x instanceof FieldAccessExpr fae) {
            // ADSMREQMONITORTASK.TABLE -> the constant's owner is the table name
            String field = fae.getNameAsString();
            if ("TABLE".equalsIgnoreCase(field)) return nameOf(fae.getScope());
            return field;
        }
        if (x instanceof NameExpr ne) return ne.getNameAsString();
        return null;
    }

    /** Any assignment or declaration of {@code name} anywhere inside {@code scope}. */
    private static Expression anyAssignmentTo(String name, MethodDeclaration md) {
        // The declaring method first — a local belongs to exactly one method, and its own
        // method is the only place the answer is certainly right.
        Expression inMethod = assignmentWithin(name, md);
        if (inMethod != null) return inMethod;

        // Then the whole class: fields, and locals in sibling methods. Needed because the
        // value often reaches the reflection site through a member rather than a local, and
        // because a helper that fills it may sit in the same class.
        Node classScope = enclosingClassOf(md);
        return classScope == md ? null : assignmentWithin(name, classScope);
    }

    /** Local declaration, assignment, or field declaration of {@code name} inside {@code scope}. */
    private static Expression assignmentWithin(String name, Node scope) {
        for (VariableDeclarationExpr vde : scope.findAll(VariableDeclarationExpr.class)) {
            for (VariableDeclarator vd : vde.getVariables()) {
                if (vd.getNameAsString().equals(name) && vd.getInitializer().isPresent()) {
                    return vd.getInitializer().get();
                }
            }
        }
        for (AssignExpr ae : scope.findAll(AssignExpr.class)) {
            if (name.equals(nameOf(ae.getTarget()))) return ae.getValue();
        }
        for (FieldDeclaration fd : scope.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator vd : fd.getVariables()) {
                if (vd.getNameAsString().equals(name) && vd.getInitializer().isPresent()) {
                    return vd.getInitializer().get();
                }
            }
        }
        return null;
    }

    /**
     * Producer of a FIELD named {@code name}, searched across the whole class.
     *
     * <p>Separate from {@link #anyAssignmentTo} on purpose. Locals must be looked up in the
     * declaring method only — searching the class for them matches same-named locals in
     * unrelated methods and picks an arbitrary one. A field has no method scope to search, so
     * it genuinely needs the class, and this is the case that carries the value across
     * methods:
     * <pre>
     *   void readRow(DataObject o) { this.listenerClassName = (String) row.get("CLASS_NAME"); }
     *   void execute()             { Class.forName(getListenerClassName()); }
     * </pre>
     * Only consulted once the method-scoped lookup has come up empty, and only when the name
     * really is declared as a field.
     */
    private static Expression fieldAssignmentTo(String name, Node classScope) {
        boolean isField = false;
        for (FieldDeclaration fd : classScope.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator vd : fd.getVariables()) {
                if (vd.getNameAsString().equals(name)) { isField = true; break; }
            }
        }
        if (!isField) return null;

        // Prefer a real assignment over the declaration's initializer: a field is commonly
        // declared `= ""` and only filled in later from the query.
        for (AssignExpr ae : classScope.findAll(AssignExpr.class)) {
            if (name.equals(nameOf(ae.getTarget()))) return ae.getValue();
        }
        for (FieldDeclaration fd : classScope.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator vd : fd.getVariables()) {
                if (vd.getNameAsString().equals(name) && vd.getInitializer().isPresent()) {
                    return vd.getInitializer().get();
                }
            }
        }
        return null;
    }


    /** Rejects strings that are clearly not class names (bare word, path, SQL fragment). */
    private static String asClassName(String s) {
        String t = s.trim();
        if (t.isEmpty() || t.indexOf('.') < 0 || t.indexOf(' ') >= 0 || t.indexOf('/') >= 0) {
            return null;
        }
        return t;
    }

    /** String value of a literal, a literal concatenation, or a resolvable constant. */
    private static String literalOf(Expression e) {
        return literalOf(e, 0);
    }

    /**
     * @param depth guards the recursion through named references — {@code A = B; B = "x";}
     *              would otherwise loop on a cyclic or self-referential constant.
     */
    private static String literalOf(Expression e, int depth) {
        Expression x = unwrap(e);
        if (x instanceof StringLiteralExpr sle) return sle.getValue();

        if (x instanceof BinaryExpr be && be.getOperator() == BinaryExpr.Operator.PLUS) {
            String l = literalOf(be.getLeft(), depth);
            String r = literalOf(be.getRight(), depth);
            if (l != null && r != null) return l + r;
            return null;
        }

        // A named reference: resolve it to its declaration and read the initializer. This is
        // what makes `Class.forName(CONST)` and `getRow(ADSMREQMONITORTASK.TABLE)` work
        // without the caller having to know whether the argument was written inline. The
        // declaration is commonly in ANOTHER class, so the SymbolSolver does the lookup
        // rather than a textual search.
        if (depth < MAX_CONSTANT_HOPS
            && (x instanceof NameExpr || x instanceof FieldAccessExpr)) {
            Expression init = resolvedInitializerOf(x);
            if (init != null) return literalOf(init, depth + 1);
        }
        return null;
    }

    /**
     * Initializer of the field or variable a name refers to, resolved via JavaParser.
     *
     * <p>Returns null on any resolution failure — no solver attached, a value from a jar
     * (no AST to read), or a genuinely runtime-valued symbol.
     */
    private static Expression resolvedInitializerOf(Expression nameLike) {
        try {
            ResolvedValueDeclaration decl;
            if (nameLike instanceof NameExpr ne) {
                decl = ne.resolve();
            } else if (nameLike instanceof FieldAccessExpr fae) {
                decl = fae.resolve();
            } else {
                return null;
            }

            // toAst() does NOT hand back a VariableDeclarator: a field resolves to its
            // FieldDeclaration and a local to its VariableDeclarationExpr, both of which can
            // declare several names at once ("String a = x, b = y;"). Asking for
            // VariableDeclarator directly returns empty, so take the node and pick the
            // declarator whose name matches.
            Node ast = decl.toAst().orElse(null);
            if (ast == null) return null;
            String wanted = decl.getName();

            if (ast instanceof VariableDeclarator vd) {
                return vd.getInitializer().orElse(null);
            }
            if (ast instanceof FieldDeclaration fd) {
                return initializerNamed(fd.getVariables(), wanted);
            }
            if (ast instanceof VariableDeclarationExpr vde) {
                return initializerNamed(vde.getVariables(), wanted);
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Initializer of the declarator called {@code name}, from a multi-declarator statement. */
    private static Expression initializerNamed(
            com.github.javaparser.ast.NodeList<VariableDeclarator> vars, String name) {
        for (VariableDeclarator vd : vars) {
            if (vd.getNameAsString().equals(name)) return vd.getInitializer().orElse(null);
        }
        return vars.size() == 1 ? vars.get(0).getInitializer().orElse(null) : null;
    }

    /**
     * Chases {@code name} back to a string literal, following name→name up to
     * {@link #MAX_NAME_HOPS} times: {@code String cn = SOME_CONSTANT;} is common, so the first
     * initializer found is often another name rather than a literal.
     */
    private static String literalFromDeclaration(String name, MethodDeclaration md) {
        String current = name;
        for (int hop = 0; hop < MAX_NAME_HOPS; hop++) {
            Expression init = initializerOf(current, md);
            if (init == null) return null;
            Expression value = unwrapValuePreserving(init);

            String literal = literalOf(value);
            if (literal != null) return literal;

            String next = nameOf(value);
            if (next == null || next.equals(current)) return null;
            current = next;
        }
        return null;
    }

    /** Initializer of a local (preferred) or field named {@code name}. */
    private static Expression initializerOf(String name, MethodDeclaration md) {
        for (VariableDeclarationExpr vde : md.findAll(VariableDeclarationExpr.class)) {
            for (VariableDeclarator vd : vde.getVariables()) {
                if (vd.getNameAsString().equals(name) && vd.getInitializer().isPresent()) {
                    return vd.getInitializer().get();
                }
            }
        }
        Optional<ClassOrInterfaceDeclaration> owner =
            md.findAncestor(ClassOrInterfaceDeclaration.class);
        if (owner.isPresent()) {
            for (FieldDeclaration fd : owner.get().getFields()) {
                for (VariableDeclarator vd : fd.getVariables()) {
                    if (vd.getNameAsString().equals(name) && vd.getInitializer().isPresent()) {
                        return vd.getInitializer().get();
                    }
                }
            }
        }
        return null;
    }

    // ─── parameter types ────────────────────────────────────────────────────

    /**
     * Simple type names of ARGUMENT expressions, resolved with JavaParser.
     *
     * <p>Used for the {@code reflect-instance} shape, where the call site passes values
     * ({@code p.setDefaultValue(dpvalues, iComponent, …)}) rather than {@code X.class}
     * literals, so there is nothing to read off the source text. A position that will not
     * resolve becomes {@code "?"}, which {@link #typesCompatible} treats as a wildcard — so
     * a partly-resolvable call still matches on the positions that are known instead of
     * falling back to arity for all of them.
     */
    private static List<String> resolvedArgumentTypes(List<Expression> args) {
        List<String> out = new ArrayList<>(args.size());
        for (Expression a : args) {
            String type = "?";
            try {
                ResolvedType rt = a.calculateResolvedType();
                String described = rt.describe();
                if (described != null && !described.isEmpty()) {
                    type = simpleNameOfType(described);
                }
            } catch (Throwable ignored) {
                // Unresolvable argument (no solver, missing jar, inference edge case).
            }
            out.add(type);
        }
        return out;
    }

    /** Simple type names from {@code X.class} literals; {@code "?"} for anything else. */
    private static List<String> classLiteralTypes(List<Expression> args) {
        List<String> out = new ArrayList<>(args.size());
        for (Expression a : args) {
            Expression x = unwrap(a);
            out.add(x instanceof ClassExpr ce ? simpleNameOfType(ce.getType().asString()) : "?");
        }
        return out;
    }

    /** Parameter types parsed back out of a Pass-1 method FQN {@code owner.name(a,b)}. */
    private static List<String> paramTypesOf(String methodFqn) {
        int open = methodFqn.indexOf('(');
        int close = methodFqn.lastIndexOf(')');
        if (open < 0 || close <= open) return List.of();
        String params = methodFqn.substring(open + 1, close).trim();
        if (params.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : params.split(",")) out.add(simpleNameOfType(p.trim()));
        return out;
    }

    /**
     * Lenient positional comparison. A position matches when either side is unknown
     * ({@code ?}) or the simple names are equal — the call site writes the type as imported
     * ({@code HttpServletRequest.class}) while the FQN side carries Pass 1's resolved package.
     */
    private static boolean typesCompatible(List<String> expected, List<String> actual) {
        if (expected.size() != actual.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            String a = expected.get(i);
            String b = actual.get(i);
            if ("?".equals(a) || "?".equals(b)) continue;
            if (!a.equals(b)) return false;
        }
        return true;
    }

    private static String simpleNameOfType(String type) {
        String base = type;
        int lt = base.indexOf('<');
        if (lt >= 0) base = base.substring(0, lt);
        while (base.endsWith("[]")) base = base.substring(0, base.length() - 2);
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1) : base;
    }

    // ─── table / column tracing ─────────────────────────────────────────────

    /** A resolved {@code (table, column)} pair; {@code column} may be null. */


    /**
     * Persistence APIs that name a table. Every one of them takes it as argument 0 — verified
     * against {@code AdvPersistence.jar}, where {@code DataObject}'s entire String-first
     * surface ({@code getRow}, {@code getRows}, {@code getFirstRow}, {@code getLastRow},
     * {@code getFirstValue}, {@code getValue}, {@code getAddedRows}, …) is table-first, as are
     * {@code Column.getColumn}, {@code Table.getTable} and {@code ReadOnlyPersistence.get}.
     *
     * <p>Ranked by real usage here: {@code Column.getColumn} 9089, {@code getRows} 1790,
     * {@code Table.getTable} 1784, {@code getPersistence().get} 1381, {@code getRow} 877,
     * {@code getFirstValue} 23.
     */
    private static final int TABLE_ARG_INDEX = 0;

    /**
     * Table APIs keyed by {@code declaringTypeFqn#methodName}. The DECLARING TYPE is part of
     * the key, not a separate package check, because the method name alone is ambiguous even
     * within these packages:
     * <ul>
     *   <li>{@code ReadOnlyPersistence.get("TechnicianCatagory", criteria)} — a query.</li>
     *   <li>{@code Row.get("CLASS_NAME")} — a COLUMN of the row's own table. Same name, same
     *       package. Keyed by type, it simply is not in this set.</li>
     *   <li>{@code Map.get("…")} / {@code Properties.getProperty("…")} — not persistence
     *       at all.</li>
     * </ul>
     * Names are verified against {@code AdvPersistence.jar}: every entry takes the table at
     * argument 0. {@code Persistence} is listed alongside {@code ReadOnlyPersistence} because
     * {@code get} is declared on the latter and inherited by the former, and JavaParser
     * reports whichever one the receiver's static type resolves through.
     */
    private static final Set<String> TABLE_APIS = Set.of(
        "com.adventnet.persistence.DataObject#getRow",
        "com.adventnet.persistence.DataObject#getRows",
        "com.adventnet.persistence.DataObject#getFirstRow",
        "com.adventnet.persistence.DataObject#getLastRow",
        "com.adventnet.persistence.DataObject#getRowsAsStream",
        "com.adventnet.persistence.DataObject#getAddedRows",
        "com.adventnet.persistence.DataObject#getUpdatedRows",
        "com.adventnet.persistence.DataObject#getDeletedRows",
        "com.adventnet.persistence.DataObject#getFirstValue",
        "com.adventnet.persistence.DataObject#getValue",
        "com.adventnet.persistence.DataObject#containsTable",
        "com.adventnet.persistence.DataObject#getDataObject",
        "com.adventnet.persistence.ReadOnlyPersistence#get",
        "com.adventnet.persistence.Persistence#get",
        "com.adventnet.ds.query.Column#getColumn",
        "com.adventnet.ds.query.Table#getTable");

    /** Method names appearing in {@link #TABLE_APIS}, for a cheap pre-filter. */
    private static final Set<String> TABLE_METHOD_NAMES = TABLE_APIS.stream()
        .map(k -> k.substring(k.indexOf('#') + 1))
        .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /**
     * Table API names that no other common type shares, used only when the receiver's type
     * cannot be resolved at all.
     *
     * <p>Needed because {@code toAst()} hands back a node from the TypeSolver's own internal
     * parse, and that parse has no symbol resolver attached — so every {@code resolve()}
     * inside a cross-file callee body fails. Treating "unresolvable" as "not a table call"
     * silently lost every query that lives in a helper method.
     *
     * <p>Deliberately excludes {@code get} and {@code getValue}: those are exactly the names
     * that collide with {@code Row}, {@code Map} and {@code Properties}, and without a type
     * to check, accepting them would read {@code r.get("LISTENER_CLASS_NAME")} as a query
     * against a table named {@code LISTENER_CLASS_NAME}.
     */
    private static final Set<String> UNAMBIGUOUS_TABLE_METHODS = Set.of(
        "getRow", "getRows", "getFirstRow", "getLastRow", "getRowsAsStream",
        "getAddedRows", "getUpdatedRows", "getDeletedRows",
        "getFirstValue", "containsTable", "getColumn", "getTable");

    /**
     * Columns known to hold a Java class name, most common first.
     *
     * <p>Replaces inferring the column from the source: the column is looked up by trying
     * these in order against the table's seed data until one yields values. Derived by
     * counting which attributes in the seed XML actually carry class FQNs —
     * {@code CLASS_NAME} 1337 (+621 as lowercase {@code class_name}),
     * {@code TRANSFORMER_CLASS} 193, {@code DOMAIN_SPECIFIC_DP_CLASS} 177, down to
     * {@code LISTENER_CLASS_NAME} 5.
     *
     * <p>Columns like {@code ATTRIB_DISP_NAME} (112) and {@code WF_ACTION_NAME} (21) also
     * hold dotted values that pass a naive FQN regex — they are i18n keys, and an explicit
     * list is what keeps them out. Lookup is case-insensitive, so {@code class_name} is
     * covered by {@code CLASS_NAME}.
     */
    private static final List<String> CLASS_NAME_COLUMNS = List.of(
        "CLASS_NAME",
        "TRANSFORMER_CLASS",
        "DOMAIN_SPECIFIC_DP_CLASS",
        "LISTENER_CLASS_NAME",
        "BULK_LISTENER_CLASS_NAME",
        "MGMT_HANDLER_CLASS_NAME",
        "ACTION_CLASS_NAME",
        "ACTION_CLASS",
        "DATA_PROVIDER_CLASS",
        "GENERATOR_CLASS",
        "API_ERROR_HANDLER",
        "API_AUTHORIZATION",
        "CLASS");

    /** Constructors that name a table in argument 0. */
    private static final Set<String> TABLE_CTORS = Set.of("Row", "Table", "Column");


    /**
     * Walks backwards from the {@code Class.forName} argument until it reaches a persistence
     * call that names a table.
     *
     * <p>Driven by expression KIND rather than by name, because the producer chain mixes them:
     * <pre>
     *   Hashtable h = RequesterHandler.getTechnicianListenerDetails("Requester"); // MethodCallExpr
     *   Properties p = (Properties) h.get(classType);                             // CastExpr→MethodCallExpr
     *   String className = p.getProperty("className");                            // MethodCallExpr
     *   Class.forName(className);                                                 // NameExpr
     * </pre>
     * Each hop asks: is this expression itself a table call? If not, and it is a call, does
     * the CALLEE'S BODY contain one (resolved across files via the symbol solver)? If still
     * not, step onto the receiver, or onto the declaration of the name.
     */
    private String tableColumnBehind(Expression start, MethodDeclaration md) {
        Set<String> seenNames = new HashSet<>();
        Set<String> seenMethods = new HashSet<>();
        Expression current = start;
        // The scope TRAVELS WITH the expression. Following a field assignment or a callee's
        // return lands us in a different method — and a local named there belongs to that
        // method, not to the one we started in. Re-deriving both scopes from the current
        // node each hop is what lets locals stay method-scoped while a value still flows
        // across methods:
        //   readRow():  Row actionRow = o.getRow("ADSMWorkFlowActions");
        //               this.listenerClassName = (String) actionRow.get("CLASS_NAME");
        //   execute():  Class.forName(getListenerClassName());
        // Once the field hop moves us into readRow, `actionRow` resolves there.
        MethodDeclaration scopeMethod = md;
        Node classScope = enclosingClassOf(md);

        for (int hop = 0; hop < MAX_NAME_HOPS; hop++) {
            if (current == null) break;
            current = unwrapValuePreserving(current);
            if (current == null) break;

            MethodDeclaration owningMethod =
                current.findAncestor(MethodDeclaration.class).orElse(null);
            if (owningMethod != null) {
                scopeMethod = owningMethod;
                classScope = enclosingClassOf(owningMethod);
            }

            // 1. Is this expression itself a table-naming persistence call / constructor?
            String direct = tableOf(current);
            if (direct != null) return direct;

            if (current instanceof MethodCallExpr mc) {
                // A column accessor — row.get("CLASS_NAME"), prop.getProperty("className").
                // Never follow these into their declaration: Row.get's body is a generic
                // lookup that tells us nothing (and `return null;` would derail the trace).
                // The value means "some column OF whatever the receiver came from", so step
                // straight to the receiver. WHICH column is decided later, by trying
                // CLASS_NAME_COLUMNS against the table's seed data.
                if (isColumnAccessor(mc)) {
                    current = mc.getScope().orElse(null);
                    continue;
                }
                if (seenMethods.add(mc.getNameAsString())) {
                    // 2. Look inside the callee — this is what finds the table when the query
                    //    lives in a helper (getTechnicianListenerDetails) rather than inline.
                    MethodDeclaration callee = declarationOf(mc, classScope);
                    if (callee != null) {
                        String inBody = scanBodyForTable(callee);
                        if (inBody != null) return inBody;
                        // 3. A pass-through like `return listenerClassName;` — follow what it
                        //    returns rather than treating the call as a dead end. This is the
                        //    getter case (getListenerClassName -> the field holding the value).
                        Expression returned = soleReturnedExpression(callee);
                        if (returned != null) {
                            current = returned;
                            continue;
                        }
                    }
                }
                // 4. Otherwise step onto the receiver: p.getProperty(..) -> p
                current = mc.getScope().orElse(null);
                continue;
            }

            if (current instanceof ObjectCreationExpr oce) {
                current = oce.getArguments().isEmpty() ? null : oce.getArgument(0);
                continue;
            }

            // 4. A name: follow it to whatever produced it. Locals are looked up in THIS
            //    method only; widening to the class would match a same-named local in some
            //    unrelated method. Fields legitimately have no method scope, so they get the
            //    class-wide search — separately, and only if the name really is a field.
            String name = nameOf(current);
            if (name == null || name.isEmpty()) break;   // nothing more to follow
            if (!seenNames.add(name)) break;             // cycle

            Expression producer = anyAssignmentTo(name, scopeMethod);
            if (producer == null) producer = fieldAssignmentTo(name, classScope);
            if (producer == null) producer = returnedExpressionOf(name, classScope);
            if (producer == null) break;
            current = producer;
        }
        return null;
    }

    /** The table named by this expression, if it is a persistence call or constructor. */
    private static String tableOf(Expression e) {
        Expression x = unwrap(e);
        if (x instanceof MethodCallExpr mc) {
            if (!isTableCall(mc)) return null;
            String table = literalOf(mc.getArgument(TABLE_ARG_INDEX));
            return table != null ? table : tableToken(mc.getArgument(TABLE_ARG_INDEX));
        }
        if (x instanceof ObjectCreationExpr oce
            && TABLE_CTORS.contains(oce.getType().getNameAsString())
            && !oce.getArguments().isEmpty()) {
            return literalOf(oce.getArgument(0));
        }
        return null;
    }

    /**
     * A single-argument accessor that reads a column off a row/map rather than querying a
     * table: {@code row.get("CLASS_NAME")}, {@code prop.getProperty("className")}. The
     * argument is NOT inspected — which column matters is decided later against the seed
     * data, so the only job here is to keep the trace stepping to the receiver instead of
     * descending into a generic lookup body.
     */
    private static boolean isColumnAccessor(MethodCallExpr mc) {
        if (mc.getArguments().size() != 1) return false;
        if (isTableCall(mc)) return false;
        return switch (mc.getNameAsString()) {
            case "get", "getString", "getValue", "getProperty" -> true;
            default -> false;
        };
    }

    /**
     * Requires BOTH the method name and the receiver's declaring type to match
     * {@link #TABLE_APIS}. The name alone is ambiguous even inside the persistence packages —
     * {@code ReadOnlyPersistence.get(table, criteria)} is a query while
     * {@code Row.get("CLASS_NAME")} reads a column — and outside them {@code Map.get} and
     * {@code Properties.getProperty} would both be false positives.
     */
    private static boolean isTableCall(MethodCallExpr mc) {
        // Cheap name pre-filter before paying for symbol resolution.
        if (!TABLE_METHOD_NAMES.contains(mc.getNameAsString())) return false;
        if (mc.getArguments().size() <= TABLE_ARG_INDEX) return false;

        String arg0 = literalOf(mc.getArgument(TABLE_ARG_INDEX));
        if (arg0 == null) arg0 = tableToken(mc.getArgument(TABLE_ARG_INDEX));
        if (arg0 == null || arg0.isEmpty() || looksLikeClassFqn(arg0)) return false;

        // The scope's type must match too — the name on its own is ambiguous.
        String owner = declaringTypeOf(mc);
        if (owner != null) return TABLE_APIS.contains(owner + "#" + mc.getNameAsString());

        // Type unknown (not mismatched): the node came from the TypeSolver's own parse, which
        // has no resolver attached. Accept only names nothing else shares.
        return UNAMBIGUOUS_TABLE_METHODS.contains(mc.getNameAsString());
    }

    /** Qualified name of the type declaring this call, or null when unresolvable. */
    private static String declaringTypeOf(MethodCallExpr mc) {
        try {
            return mc.resolve().declaringType().getQualifiedName();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolves {@code mc} to its source declaration and scans that body for a table call plus
     * the class-valued column read out of it.
     *
     * <p>Resolution goes through the symbol solver so it crosses files — the query for
     * {@code TechnicianCatagory} lives in {@code RequesterHandler}, not in the class doing the
     * reflection. Falls back to a same-file lookup by name when the solver is unavailable
     * (files over the large-file threshold are parsed without one).
     */
    private MethodDeclaration declarationOf(MethodCallExpr mc, Node classScope) {
        try {
            MethodDeclaration viaSolver = mc.resolve().toAst(MethodDeclaration.class).orElse(null);
            if (viaSolver != null && viaSolver.getBody().isPresent()) return viaSolver;
        } catch (Throwable ignored) {
            // No solver, a JDK method, or unresolvable — fall through to the local search.
        }
        for (MethodDeclaration cand : classScope.findAll(MethodDeclaration.class)) {
            if (cand.getNameAsString().equals(mc.getNameAsString())
                && cand.getBody().isPresent()) {
                return cand;
            }
        }
        return null;
    }

    /** The expression of a method whose body is just {@code return X;}. */
    private static Expression soleReturnedExpression(MethodDeclaration decl) {
        List<ReturnStmt> returns = decl.findAll(ReturnStmt.class);
        if (returns.size() != 1) return null;
        return returns.get(0).getExpression().orElse(null);
    }

    /**
     * The table queried inside {@code decl}, when the query lives in a helper rather than at
     * the reflection site:
     * <pre>
     *   Criteria cri = new Criteria(Column.getColumn("TechnicianCatagory", "…TYPE"), …);
     *   DataObject obj = CommonUtil.getPersistence().get("TechnicianCatagory", cri);
     *   Iterator it = obj.getRows("TechnicianCatagory");
     * </pre>
     * Only the table is taken. Which column holds the class name is not inferred from the
     * source — the criteria column ({@code TECHNICIAN_CATEGORY_TYPE}) and the class column
     * ({@code LISTENER_CLASS_NAME}) are both just {@code get("…")} calls with nothing to tell
     * them apart structurally, so guessing picked the wrong one. The column is resolved
     * against the seed data instead, via {@link #CLASS_NAME_COLUMNS}.
     */
    private String scanBodyForTable(MethodDeclaration decl) {
        for (MethodCallExpr call : decl.findAll(MethodCallExpr.class)) {
            String table = tableOf(call);
            if (table != null) return table;
        }
        for (ObjectCreationExpr oce : decl.findAll(ObjectCreationExpr.class)) {
            String table = tableOf(oce);
            if (table != null) return table;
        }
        return null;
    }



    /**
     * Whether a string is written as a Java class FQN. Used to keep a class name from being
     * looked up as a table, and to keep {@code Map.get("some.Class")} from being read as a
     * query.
     */
    private static boolean looksLikeClassFqn(String s) {
        return s != null && TableClassNameIndex.CLASS_FQN.matcher(s.trim()).matches();
    }

    /** Expression returned by a no-arg method named {@code name} in scope, if any. */
    private static Expression returnedExpressionOf(String name, Node scope) {
        String target = name;
        for (MethodDeclaration m : scope.findAll(MethodDeclaration.class)) {
            if (!m.getNameAsString().equals(target)) continue;
            for (ReturnStmt rs : m.findAll(ReturnStmt.class)) {
                if (rs.getExpression().isPresent()) return rs.getExpression().get();
            }
        }
        // getListenerClassName -> listenerClassName, when no such method body was found.
        // The bean-property name is a field by construction, so the class-wide field search
        // is the right one here — there is no single method that owns it.
        if (target.startsWith("get") && target.length() > 3) {
            String field = Character.toLowerCase(target.charAt(3)) + target.substring(4);
            Expression assigned = fieldAssignmentTo(field, scope);
            if (assigned != null) return assigned;
        }
        return null;
    }

    // ─── AST helpers ────────────────────────────────────────────────────────

    private static boolean isClassForName(MethodCallExpr mc) {
        if (!"forName".equals(mc.getNameAsString())) return false;
        if (mc.getScope().isEmpty()) return false;
        String scope = unwrap(mc.getScope().get()).toString();
        return "Class".equals(scope) || "java.lang.Class".equals(scope);
    }

    /** Peels casts and parentheses. */
    private static Expression unwrap(Expression e) {
        Expression x = e;
        while (true) {
            if (x instanceof EnclosedExpr en) { x = en.getInner(); continue; }
            if (x instanceof CastExpr ce)     { x = ce.getExpression(); continue; }
            return x;
        }
    }

    /**
     * Peels calls that return the same string value, so {@code className.trim()} reads as
     * {@code className}. This is the dominant shape here: 189 of the 311 non-literal
     * {@code Class.forName} arguments are {@code something.trim()}.
     */
    private static Expression unwrapValuePreserving(Expression e) {
        Expression x = unwrap(e);
        while (x instanceof MethodCallExpr mc
               && mc.getArguments().isEmpty()
               && mc.getScope().isPresent()
               && VALUE_PRESERVING.contains(mc.getNameAsString())) {
            x = unwrap(mc.getScope().get());
        }
        return x;
    }

    /** Identifier of a name-like expression: {@code x}, {@code this.x}, {@code a.b.x}. */
    private static String nameOf(Expression e) {
        Expression x = unwrap(e);
        if (x instanceof NameExpr ne) return ne.getNameAsString();
        if (x instanceof FieldAccessExpr fae) return fae.getNameAsString();
        if (x instanceof MethodCallExpr mc && mc.getArguments().isEmpty()) {
            return mc.getNameAsString();   // a getter used as a value
        }
        return null;
    }

    /** The chained call made directly on {@code inner}'s result, if any. */
    private static Optional<MethodCallExpr> chainedCallOn(MethodCallExpr inner) {
        Node p = inner.getParentNode().orElse(null);
        if (p instanceof MethodCallExpr outer
            && outer.getScope().map(s -> unwrap(s) == inner).orElse(false)) {
            return Optional.of(outer);
        }
        return Optional.empty();
    }

    /**
     * Name of the variable this expression ends up in, found by walking up the parent chain.
     *
     * <p>Peeling only casts and parentheses was too narrow — anything else between the call
     * and the declarator ended the search. Walking up instead handles the wrappers that
     * actually occur, e.g. a conditional or a nested cast:
     * <pre>
     *   StatusUpdater su = (StatusUpdater) Class.forName(x).newInstance();
     *   Foo f = (Foo) (cond ? a.newInstance() : b.newInstance());
     * </pre>
     *
     * <p>The walk stops at the enclosing statement, so an expression buried in an argument
     * list or a return does not get attributed to some unrelated declarator further out.
     */
    private static String assignedVariableName(Expression e) {
        Node cur = e;
        for (int hop = 0; hop < MAX_PARENT_HOPS; hop++) {
            Node parent = cur.getParentNode().orElse(null);
            if (parent == null) return null;

            if (parent instanceof VariableDeclarator vd) return vd.getNameAsString();
            if (parent instanceof AssignExpr ae) {
                String target = nameOf(ae.getTarget());
                return target;   // null when the target is not a plain name — still a stop
            }
            // A VariableDeclarationExpr is only reached THROUGH its declarator, which the
            // branch above already caught; seeing one here means the declarator was skipped.
            if (parent instanceof VariableDeclarationExpr vde) {
                return vde.getVariables().isEmpty() ? null
                     : vde.getVariable(0).getNameAsString();
            }
            // Past the statement boundary there is no assignment to attribute this to.
            if (parent instanceof com.github.javaparser.ast.stmt.Statement
                && !(parent instanceof com.github.javaparser.ast.stmt.ExpressionStmt)) {
                return null;
            }
            cur = parent;
        }
        return null;
    }

    /** If this call's scope is a tracked Class variable, its candidate target FQNs. */
    private static List<String> classVarOfScope(MethodCallExpr mc,
                                                Map<String, List<String>> classVars) {
        if (mc.getScope().isEmpty()) return null;
        String n = nameOf(mc.getScope().get());
        return n == null ? null : classVars.get(n);
    }

    private static String describe(MethodCallExpr at, String why) {
        int line = at.getBegin().map(p -> p.line).orElse(0);
        return "line " + line + " : " + why;
    }
}
