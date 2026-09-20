package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;

import io.spmp.impact.extract.BodyCollector;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.model.GraphEdges.CallEdge;
import io.spmp.impact.model.GraphNodes.MethodNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves interface-dispatch through {@code IMgmtListener}, e.g. in bulk-management flows:
 * <pre>
 *   IMgmtListener listener = fcBulkExecuteFormBean.getIMgmtListener();
 *   listener.setRequestId(requestId);
 *   listener.doBulkAction(request, fcBulkExecuteFormBean, fcIamApps, loginId);
 * </pre>
 * A static call graph resolves {@code listener.doBulkAction(...)} only to the interface
 * method {@code IMgmtListener.doBulkAction()}, so the concrete implementations (e.g.
 * {@code FcMgmtListener}) are never linked. This resolver bridges that gap — like
 * {@link NotificationMacroResolver} does for {@code NotificationMacro}.
 *
 * <p><b>Targets:</b> classes whose ancestry (direct or transitive — resolved via JavaParser's
 * SymbolSolver, so subclasses of implementers are caught too) includes {@code IMgmtListener}.
 *
 * <p><b>Detection (JavaParser, {@link #visitMethod}, ALL classes/methods):</b> local variables
 * declared with type {@code IMgmtListener}, followed by {@code var.method(...)} calls on them.
 * Unlike {@link NotificationMacroResolver}, detection is not restricted to a specific caller
 * class — {@code IMgmtListener} locals appear across many bulk-management listener classes.
 *
 * <p><b>Emission ({@link #afterAll}):</b> for each such call a {@code :CALLS} edge
 * {@code callerMethod -> implClass.method()} is emitted for every implementing class that
 * declares a method with a matching {@code name(paramTypes)} signature. Multiple implementers
 * match → multiple edges.
 */
public class IMgmtListenerResolver implements BoundaryResolver {

    private static final String LISTENER_INTERFACE = "IMgmtListener";
    private static final String LISTENER_INTERFACE_FQN = "com.adventnet.sym.adsm.common.server.layout.IMgmtListener";

    // Populated only from visit(), which CoreExtractor calls under a shared lock — safe as a
    // plain HashSet (mirrors NotificationMacroResolver.macroClasses).
    private final Set<String> listenerClasses = new HashSet<>();

    /** (callerMethodFqn, "methodName(paramTypeFqns)") collected across files (parallel). */
    private record ListenerCall(String callerFqn, String signature) {}
    private final ConcurrentLinkedQueue<ListenerCall> listenerCalls = new ConcurrentLinkedQueue<>();

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            // Filter out interfaces — we only want concrete/abstract classes.
            if (!classDecl.isInterface()) {
                try {
                    // Resolve the class type using SymbolSolver.
                    ResolvedReferenceTypeDeclaration resolvedClass = classDecl.resolve();

                    // Check all ancestors (superclasses and implemented interfaces recursively).
                    boolean inheritsInterface = resolvedClass.getAllAncestors().stream()
                        .anyMatch(ancestor ->
                            ancestor.getQualifiedName().equals(LISTENER_INTERFACE_FQN) ||
                            ancestor.describe().equals(LISTENER_INTERFACE)
                        );

                    if (inheritsInterface) {
                        listenerClasses.add(classDecl.getFullyQualifiedName().get());
                    }
                } catch (Exception e) {
                    // Symbol resolution might fail if missing external library dependencies.
                    System.err.println("Could not resolve symbol for: " + classDecl.getNameAsString());
                }
            }
        });
    }

    @Override
    public void visitMethod(MethodDeclaration md, String methodFqn, String ownerFqn,
                            List<BodyCollector.CallSite> calls, ExtractionBatch local) {
        // No caller-class restriction — IMgmtListener locals appear across many classes.

        // Local variables of type IMgmtListener declared anywhere in this method.
        Set<String> listenerVars = null;
        for (VariableDeclarationExpr vde : md.findAll(VariableDeclarationExpr.class)) {
            for (VariableDeclarator vd : vde.getVariables()) {
                if (!LISTENER_INTERFACE.equals(simpleTypeName(vd.getType().asString()))) continue;
                if (listenerVars == null) listenerVars = new HashSet<>();
                listenerVars.add(vd.getNameAsString());
            }
        }
        if (listenerVars == null) return;

        for (MethodCallExpr mc : md.findAll(MethodCallExpr.class)) {
            if (mc.getScope().isEmpty()) continue;
            if (!(unwrap(mc.getScope().get()) instanceof NameExpr ne)) continue;
            if (!listenerVars.contains(ne.getNameAsString())) continue;
            String params = resolvedParamTypes(mc);
            if (params == null) continue;   // unresolvable — skip rather than emit a wrong edge
            listenerCalls.add(new ListenerCall(methodFqn, mc.getNameAsString() + "(" + params + ")"));
        }
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (listenerCalls.isEmpty()) {
            System.out.println("[IMgmtListenerResolver] no IMgmtListener local-var calls found; nothing to link.");
            return;
        }
        if (listenerClasses.isEmpty()) {
            System.out.println("[IMgmtListenerResolver] no classes implement " + LISTENER_INTERFACE + "; nothing to link.");
            return;
        }

        // Index the implementers' declared methods (from the master batch).
        Map<String, List<MethodNode>> byOwner = new HashMap<>();
        for (MethodNode mn : batch.methods) {
            if (!listenerClasses.contains(mn.ownerFqn())) continue;
            byOwner.computeIfAbsent(mn.ownerFqn(), k -> new ArrayList<>()).add(mn);
        }

        Pattern sigPattern = Pattern.compile("([a-zA-Z0-9_$]+\\(.*\\))$");
        Set<String> emitted = new HashSet<>();   // dedup by "from#to"
        int edges = 0;
        for (ListenerCall call : listenerCalls) {
            for (String cls : listenerClasses) {
                List<MethodNode> methods = byOwner.get(cls);
                if (methods == null) continue;
                for (MethodNode mn : methods) {
                    Matcher m = sigPattern.matcher(mn.fqn());
                    if (!m.find()) continue;
                    if (!m.group(1).equals(call.signature())) continue;
                    if (!emitted.add(call.callerFqn() + "#" + mn.fqn())) continue;
                    batch.calls.add(new CallEdge(call.callerFqn(), mn.fqn(), "imgmt-listener"));
                    edges++;
                }
            }
        }
        System.out.printf("[IMgmtListenerResolver] listenerClasses=%d  listenerCalls=%d  edges=%d%n",
            listenerClasses.size(), listenerCalls.size(), edges);
    }

    /** Resolved comma-joined parameter type FQNs of the call, or {@code null} if unresolvable. */
    private static String resolvedParamTypes(MethodCallExpr mc) {
        try {
            ResolvedMethodDeclaration r = mc.resolve();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.getNumberOfParams(); i++) {
                if (i > 0) sb.append(',');
                try { sb.append(r.getParam(i).getType().describe()); }
                catch (Throwable t) { sb.append('?'); }
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Strip generics + package from a type string: {@code a.b.Foo<X>} → {@code Foo}. */
    private static String simpleTypeName(String t) {
        int lt = t.indexOf('<');
        if (lt >= 0) t = t.substring(0, lt);
        int dot = t.lastIndexOf('.');
        if (dot >= 0) t = t.substring(dot + 1);
        return t.trim();
    }

    /** Peel {@code (expr)} and {@code (Type) expr} wrappers off an expression. */
    private static Expression unwrap(Expression e) {
        while (true) {
            if (e instanceof EnclosedExpr en) e = en.getInner();
            else if (e instanceof CastExpr c) e = c.getExpression();
            else return e;
        }
    }
}
