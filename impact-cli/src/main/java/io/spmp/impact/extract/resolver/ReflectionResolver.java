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
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;

import io.spmp.impact.extract.BodyCollector;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.extract.GlobalIndex;
import io.spmp.impact.extract.GlobalIndex.MethodSig;
import io.spmp.impact.model.GraphEdges.CallEdge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Resolves {@code Class.forName(...)}-based reflective dispatch into real :CALLS edges.
 *
 * <p>Reflection is invisible to a call graph built from the AST: the target class is a
 * <em>string</em>, so nothing links the caller to the method that actually runs. This repo
 * has 451 {@code Class.forName} sites, and the framework dispatches whole subsystems through
 * them (workflow commit listeners, report listeners, data providers, transformers).
 *
 * <h2>Shapes handled</h2>
 * All four resolve to {@code <resolvedFqn>.<method>(<arity>)}:
 * <pre>
 *   // 1. getMethod + invoke — the Method object may be stored first
 *   Class c = Class.forName("…TicketHandler");
 *   Method m = c.getMethod("invalidateTickets", Long.class);
 *   m.invoke(loginId);                       → TicketHandler.invalidateTickets(1 param)
 *
 *   // 2. getConstructor + newInstance
 *   classToInvoke = Class.forName("…HDTAuditReportListener");
 *   Constructor&lt;AuditTask&gt; ctor = classToInvoke.getConstructor(Integer.class);
 *   AuditTask l = (AuditTask) ctor.newInstance(7038);   → HDTAuditReportListener.&lt;init&gt;(1 param)
 *
 *   // 3. fully chained, one expression
 *   Class.forName("…WorkFlowClientUtil")
 *        .getMethod("export", HttpServletRequest.class, …)
 *        .invoke(null, …);                   → WorkFlowClientUtil.export(7 params)
 *
 *   // 4. newInstance + interface cast, then a call on the INTERFACE variable
 *   Class dp = Class.forName(this.domainSpecificDataProviderClass);
 *   IDataProvider p = (IDataProvider) dp.newInstance();
 *   p.setDefaultValue(a, b, c, d, e, f);     → &lt;concrete class&gt;.setDefaultValue(6 params)
 * </pre>
 * Shape 4 is the valuable one: without it, {@code p.setDefaultValue(...)} resolves only to
 * the <em>interface</em> declaration. Here the edge lands on the concrete implementation that
 * {@code Class.forName} actually named.
 *
 * <h2>Emitting at getMethod, not at invoke</h2>
 * The edge is emitted where {@code getMethod} / {@code getConstructor} appears, not where
 * {@code invoke} does. Shapes 1 and 2 park the {@code Method} / {@code Constructor} in a local
 * first, so the {@code invoke} call site knows only a reflection handle, not a class name;
 * tying the two together would need dataflow. Looking up a method reflectively is itself
 * strong enough evidence of a call.
 *
 * <h2>Two-phase, like the rest of Pass 1</h2>
 * {@link #visitMethod} runs while the AST is live and records
 * {@code (caller, ownerFqn, methodName, arity)} tuples. {@link #afterAll} turns each into a
 * concrete FQN through the frozen {@link GlobalIndex} — the same owner-then-ancestors lookup
 * {@code CallResolver.resolveOwnerAndEmit} performs, including its {@code owner.name(?)}
 * placeholder fallback so no reflective call vanishes from the graph.
 */
public class ReflectionResolver implements BoundaryResolver {

    /** How many unresolved Class.forName targets to print; there are hundreds. */
    private static final int UNRESOLVED_PRINT_LIMIT = 25;

    /** Max name-to-name hops when chasing a variable back to a string literal. */
    private static final int MAX_NAME_HOPS = 3;

    /** A reflective call found in Pass 1, pending index lookup in afterAll. */
    private record ReflectiveCall(String callerFqn, String ownerFqn,
                                  String simpleName, int arity, String shape) {}

    private final ConcurrentLinkedQueue<ReflectiveCall> pending = new ConcurrentLinkedQueue<>();

    /** {@code Class.forName(expr)} sites whose argument is not a resolvable class-name string. */
    private final ConcurrentLinkedQueue<String> unresolvedTargets = new ConcurrentLinkedQueue<>();

    private volatile GlobalIndex index;

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

        // ── Phase 1: resolve each Class.forName target, and note the Class variable ──
        Map<String, String> classVars = new LinkedHashMap<>();   // varName -> target class FQN
        for (MethodCallExpr fn : forNameCalls) {
            String target = resolveTargetFqn(fn, md, methodFqn);
            if (target == null) continue;                        // already recorded as unresolved

            // Shape 3: the Class object is used inline, e.g. Class.forName(x).getMethod(...)
            chainedCallOn(fn).ifPresent(outer -> onClassObjectCall(outer, target, methodFqn));

            // Shapes 1/2/4: the Class object lands in a variable.
            String var = assignedVariableName(fn);
            if (var != null) classVars.put(var, target);
        }
        if (classVars.isEmpty()) return;

        // ── Phase 2: instance variables produced by <classVar>.newInstance() ──
        // e.g. IDataProvider p = (IDataProvider) dp.newInstance();
        Map<String, String> instanceVars = new HashMap<>();
        for (MethodCallExpr mc : md.findAll(MethodCallExpr.class)) {
            if (!"newInstance".equals(mc.getNameAsString())) continue;
            String owner = classVarOfScope(mc, classVars);
            if (owner == null) continue;
            String var = assignedVariableName(mc);
            if (var != null) instanceVars.put(var, owner);
        }

        // ── Phase 3: sweep every call and emit ──
        for (MethodCallExpr mc : md.findAll(MethodCallExpr.class)) {
            // (a) reflection API called on a Class variable
            String classOwner = classVarOfScope(mc, classVars);
            if (classOwner != null) {
                onClassObjectCall(mc, classOwner, methodFqn);
                continue;
            }
            // (b) a real call on a variable holding the reflectively-created instance
            if (mc.getScope().isEmpty()) continue;
            if (!(unwrap(mc.getScope().get()) instanceof NameExpr ne)) continue;
            String instOwner = instanceVars.get(ne.getNameAsString());
            if (instOwner == null) continue;
            pending.add(new ReflectiveCall(methodFqn, instOwner,
                mc.getNameAsString(), mc.getArguments().size(), "reflect-instance"));
        }
    }

    /**
     * Handles a call made ON a {@code Class} object: {@code getMethod}, {@code getDeclaredMethod},
     * {@code getConstructor}, {@code getDeclaredConstructor} or a bare {@code newInstance()}.
     */
    private void onClassObjectCall(MethodCallExpr call, String ownerFqn, String callerFqn) {
        String name = call.getNameAsString();
        switch (name) {
            case "getMethod", "getDeclaredMethod" -> {
                if (call.getArguments().isEmpty()) return;
                String target = literalOf(call.getArgument(0));
                if (target == null) {
                    unresolvedTargets.add(describe(call, "method name not a literal: " + call));
                    return;
                }
                // arg 0 is the method name; the rest are the parameter Class objects.
                pending.add(new ReflectiveCall(callerFqn, ownerFqn, target,
                    call.getArguments().size() - 1, "reflect-getMethod"));
            }
            case "getConstructor", "getDeclaredConstructor" ->
                pending.add(new ReflectiveCall(callerFqn, ownerFqn, "<init>",
                    call.getArguments().size(), "reflect-getConstructor"));
            case "newInstance" ->
                // Class.newInstance() is always the no-arg constructor. The
                // Constructor.newInstance(args) form is covered by getConstructor above.
                pending.add(new ReflectiveCall(callerFqn, ownerFqn, "<init>", 0,
                    "reflect-newInstance"));
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
        int exact = 0, inherited = 0, placeholder = 0;
        Map<String, Integer> byShape = new LinkedHashMap<>();

        for (ReflectiveCall rc : pending) {
            byShape.merge(rc.shape(), 1, Integer::sum);

            String targetFqn = null;
            String strategy = rc.shape();

            // Same lookup order as CallResolver.resolveOwnerAndEmit: owner, then ancestors.
            MethodSig hit = firstByArity(index.methodsOn(rc.ownerFqn(), rc.simpleName()), rc.arity());
            if (hit != null) {
                targetFqn = hit.fqn();
                exact++;
            } else {
                for (String anc : index.transitiveAncestors(rc.ownerFqn())) {
                    MethodSig ancHit = firstByArity(index.methodsOn(anc, rc.simpleName()), rc.arity());
                    if (ancHit != null) {
                        targetFqn = ancHit.fqn();
                        strategy = rc.shape() + "-inherited";
                        inherited++;
                        break;
                    }
                }
            }
            if (targetFqn == null) {
                // Keep the call in the graph even when the arity does not match anything —
                // Neo4j MERGE creates a placeholder :Method the same way Pass 2 does.
                targetFqn = rc.ownerFqn() + "." + rc.simpleName() + "(?)";
                strategy = rc.shape() + "-no-arity-match";
                placeholder++;
            }
            if (emitted.add(rc.callerFqn() + "#" + targetFqn)) {
                batch.calls.add(new CallEdge(rc.callerFqn(), targetFqn, strategy));
            }
        }

        System.out.printf("[ReflectionResolver] reflective calls=%d  edges=%d "
                + "(exact=%d inherited=%d placeholder=%d)  unresolvedTargets=%d%n",
            pending.size(), emitted.size(), exact, inherited, placeholder, unresolvedTargets.size());
        System.out.println("[ReflectionResolver] by shape: " + byShape);

        if (!unresolvedTargets.isEmpty()) {
            System.out.println("[ReflectionResolver] Class.forName targets that are not literal "
                + "class names (showing up to " + UNRESOLVED_PRINT_LIMIT + "):");
            int n = 0;
            for (String u : unresolvedTargets) {
                if (n++ >= UNRESOLVED_PRINT_LIMIT) break;
                System.out.println("    " + u);
            }
            if (unresolvedTargets.size() > UNRESOLVED_PRINT_LIMIT) {
                System.out.println("    … and " + (unresolvedTargets.size() - UNRESOLVED_PRINT_LIMIT)
                    + " more. These are runtime-valued (DB column, config, request param), so the "
                    + "target cannot be known statically — an XML/config resolver is the way in.");
            }
        }
    }

    // ─── target-FQN resolution ──────────────────────────────────────────────

    /**
     * Resolves the {@code Class.forName(arg)} argument to a class FQN.
     *
     * <p>Handles, in order: a string literal; a literal-concatenation
     * ({@code "a.b." + "C"}); a wrapper call that does not change the value
     * ({@code className.trim()}, {@code .toString()}, {@code .intern()}) — by far the most
     * common form here, 189 of 311 non-literal sites; a local variable or field whose
     * initializer is a literal; and a {@code static final String} constant on the enclosing
     * class. Anything else is runtime-valued and gets recorded for the report instead.
     */
    private String resolveTargetFqn(MethodCallExpr forName, MethodDeclaration md, String callerFqn) {
        if (forName.getArguments().isEmpty()) return null;
        Expression arg = unwrapValuePreserving(forName.getArgument(0));

        String literal = literalOf(arg);
        if (literal != null) return validateFqn(literal, forName, callerFqn);

        if (arg instanceof NameExpr ne) {
            String v = literalFromDeclaration(ne.getNameAsString(), md);
            if (v != null) return validateFqn(v, forName, callerFqn);
            unresolvedTargets.add(describe(forName, "variable '" + ne.getNameAsString()
                + "' has no literal initializer"));
            return null;
        }
        if (arg instanceof FieldAccessExpr fae) {
            String v = literalFromDeclaration(fae.getNameAsString(), md);
            if (v != null) return validateFqn(v, forName, callerFqn);
            unresolvedTargets.add(describe(forName, "field '" + fae + "' is runtime-valued"));
            return null;
        }
        unresolvedTargets.add(describe(forName, "expression: " + arg));
        return null;
    }

    /** Rejects strings that are clearly not class names (a bare word, a path, an SQL fragment). */
    private String validateFqn(String s, MethodCallExpr at, String callerFqn) {
        String t = s.trim();
        if (t.isEmpty() || t.indexOf('.') < 0 || t.indexOf(' ') >= 0 || t.indexOf('/') >= 0) {
            unresolvedTargets.add(describe(at, "not a class name: \"" + t + "\""));
            return null;
        }
        return t;
    }

    /** String value of a literal, or of a concatenation whose every operand is a literal. */
    private static String literalOf(Expression e) {
        Expression x = unwrap(e);
        if (x instanceof StringLiteralExpr sle) return sle.getValue();
        if (x instanceof BinaryExpr be && be.getOperator() == BinaryExpr.Operator.PLUS) {
            String l = literalOf(be.getLeft());
            String r = literalOf(be.getRight());
            if (l != null && r != null) return l + r;
        }
        return null;
    }

    /**
     * Finds a literal initializer for {@code name}: first a local declaration inside this
     * method, then a field on the enclosing class.
     *
     * <p>Follows one name to the next up to {@link #MAX_NAME_HOPS} times, because
     * {@code String className = SOME_CONSTANT; Class.forName(className.trim())} is a common
     * shape here — the local's initializer is itself a name, not a literal. The hop limit
     * both bounds the work and stops a cyclic {@code a = b; b = a;} from looping.
     */
    private static String literalFromDeclaration(String name, MethodDeclaration md) {
        String current = name;
        for (int hop = 0; hop < MAX_NAME_HOPS; hop++) {
            Expression init = initializerOf(current, md);
            if (init == null) return null;
            Expression value = unwrapValuePreserving(init);

            String literal = literalOf(value);
            if (literal != null) return literal;

            // Not a literal — if it is another name, follow it and try again.
            String next = null;
            if (value instanceof NameExpr ne) next = ne.getNameAsString();
            else if (value instanceof FieldAccessExpr fae) next = fae.getNameAsString();
            if (next == null || next.equals(current)) return null;
            current = next;
        }
        return null;
    }

    /** Initializer expression of a local (preferred) or field named {@code name}. */
    private static Expression initializerOf(String name, MethodDeclaration md) {
        for (VariableDeclarationExpr vde : md.findAll(VariableDeclarationExpr.class)) {
            for (VariableDeclarator vd : vde.getVariables()) {
                if (vd.getNameAsString().equals(name) && vd.getInitializer().isPresent()) {
                    return vd.getInitializer().get();
                }
            }
        }
        Optional<ClassOrInterfaceDeclaration> owner = md.findAncestor(ClassOrInterfaceDeclaration.class);
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
     * Peels calls that return the same string value, so {@code className.trim()} is treated as
     * {@code className}. This is the dominant shape in this codebase.
     */
    private static Expression unwrapValuePreserving(Expression e) {
        Expression x = unwrap(e);
        while (x instanceof MethodCallExpr mc && mc.getArguments().isEmpty()
               && mc.getScope().isPresent()
               && switch (mc.getNameAsString()) {
                    case "trim", "toString", "intern", "strip" -> true;
                    default -> false;
                  }) {
            x = unwrap(mc.getScope().get());
        }
        return x;
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

    /** Name of the variable this expression is assigned to, through casts. */
    private static String assignedVariableName(Expression e) {
        Node cur = e;
        Node parent = cur.getParentNode().orElse(null);
        while (parent instanceof CastExpr || parent instanceof EnclosedExpr) {
            cur = parent;
            parent = cur.getParentNode().orElse(null);
        }
        if (parent instanceof VariableDeclarator vd) return vd.getNameAsString();
        if (parent instanceof AssignExpr ae && unwrap(ae.getTarget()) instanceof NameExpr ne) {
            return ne.getNameAsString();
        }
        return null;
    }

    /** If this call's scope is one of the tracked Class variables, its target FQN. */
    private static String classVarOfScope(MethodCallExpr mc, Map<String, String> classVars) {
        if (mc.getScope().isEmpty()) return null;
        Expression scope = unwrap(mc.getScope().get());
        if (scope instanceof NameExpr ne) return classVars.get(ne.getNameAsString());
        if (scope instanceof FieldAccessExpr fae) return classVars.get(fae.getNameAsString());
        return null;
    }

    private static MethodSig firstByArity(List<MethodSig> candidates, int arity) {
        if (candidates == null) return null;
        for (MethodSig m : candidates) {
            if (m.paramCount() == arity) return m;
        }
        return null;
    }

    private static String describe(MethodCallExpr at, String why) {
        int line = at.getBegin().map(p -> p.line).orElse(0);
        return "line " + line + " : " + why;
    }
}
