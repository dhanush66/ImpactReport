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
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedTypeDeclaration;
import com.github.javaparser.resolution.types.ResolvedReferenceType;
import com.github.javaparser.resolution.types.ResolvedType;

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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves interface-dispatch for ANY interface (a generalization of
 * {@link IMgmtListenerResolver} / {@link NotificationMacroResolver}, which each target one
 * hardcoded interface), for the shape:
 * <pre>
 *   SomeInterface var = someFactoryOrGetter();   // BOTH the declared type and the initializer's
 *   var.method(args);                            // resolved type must be interfaces
 * </pre>
 * The initializer can be a plain method call, a chained method call ({@code a.b().c()} — still
 * a single {@code MethodCallExpr} at the top, resolved recursively by JavaParser), or a
 * constructor call ({@code new Foo()}) — {@link #resolveInterfaceFqn(Expression)} uses
 * {@code calculateResolvedType()}, which works uniformly on any expression kind, so no
 * AST-node-type gating is needed; JavaParser's SymbolSolver alone decides whether the result is
 * an interface.
 *
 * <p><b>{@link #visit}, structured like {@link IMgmtListenerResolver#visit}:</b> for every
 * concrete class, walk {@code getAllAncestors()} and check whether ANY ancestor is currently in
 * {@code candidateInterfaces} — the interfaces discovered by {@link #visitMethod} (playing the
 * role of {@code IMgmtListenerResolver}'s single hardcoded {@code LISTENER_INTERFACE_FQN}, but
 * as a dynamically-growing set instead of one constant). A match records the class as an
 * implementer.
 *
 * <p><b>Ordering caveat:</b> {@code CoreExtractor} runs Pass 1 on a thread pool; {@code visit()}
 * runs under a shared lock while {@code visitMethod()} (which adds to {@code candidateInterfaces})
 * runs unlocked. A class whose file's {@code visit()} already ran before a candidate interface
 * was discovered elsewhere won't be captured as an implementer of that interface — a best-effort
 * trade-off matching {@code IMgmtListenerResolver}'s per-file style.
 */
public class InterfaceResolver implements BoundaryResolver {

    // Written (unlocked, concurrently) by visitMethod() as candidate interfaces are discovered;
    // read by visit() (locked) to decide which ancestor interfaces to record implementers for.
    private final Set<String> candidateInterfaces = ConcurrentHashMap.newKeySet();

    // interfaceFqn -> Set<implementer classFqn>. Written only from visit(), which CoreExtractor
    // calls under a shared lock — safe as a plain HashMap (mirrors IMgmtListenerResolver's
    // listenerClasses / NotificationMacroResolver's macroClasses).
    private final Map<String, Set<String>> interfaceToImplementers = new HashMap<>();

    /** (callerMethodFqn, declared-interface FQN, "methodName(paramTypeFqns)"). */
    private record InterfaceCall(String callerFqn, String interfaceFqn, String signature) {}
    private final ConcurrentLinkedQueue<InterfaceCall> interfaceCalls = new ConcurrentLinkedQueue<>();

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        if (candidateInterfaces.isEmpty()) return;   // nothing to check against yet
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            if (classDecl.isInterface()) return;
            try {
                ResolvedReferenceTypeDeclaration resolvedClass = classDecl.resolve();
                for (ResolvedReferenceType ancestor : resolvedClass.getAllAncestors()) {
                    String ancestorFqn = ancestor.getQualifiedName();
                    if (!candidateInterfaces.contains(ancestorFqn)) continue;
                    String classFqn = classDecl.getFullyQualifiedName().orElse(null);
                    if (classFqn == null) continue;   // anonymous/local class — no stable FQN to link against
                    interfaceToImplementers
                        .computeIfAbsent(ancestorFqn, k -> new HashSet<>())
                        .add(classFqn);
                }
            } catch (Exception e) {
                // Symbol resolution might fail if missing external library dependencies.
                System.err.println("Could not resolve symbol for: " + classDecl.getNameAsString());
            }
        });
    }

    @Override
    public void visitMethod(MethodDeclaration md, String methodFqn, String ownerFqn,
                            List<BodyCollector.CallSite> calls, ExtractionBatch local) {
        // No caller-class restriction — this pattern can occur in any class.

        // Local vars whose DECLARED type is an interface AND whose initializer's resolved type
        // is ALSO an interface. The initializer may be a plain call, a chained call, or a
        // constructor call — calculateResolvedType() handles all of them uniformly, so there is
        // no instanceof gate on the initializer's AST node kind.
        Map<String, String> interfaceVars = null;   // varName -> declared interface FQN
        for (VariableDeclarationExpr vde : md.findAll(VariableDeclarationExpr.class)) {
            for (VariableDeclarator vd : vde.getVariables()) {
                if (vd.getInitializer().isEmpty()) continue;

                String declaredIfaceFqn = resolveInterfaceFqn(vd.getType());
                if (declaredIfaceFqn == null) continue;                              // declared type isn't an interface
                if (isExcludedInterface(declaredIfaceFqn)) continue;                 // JDK/platform or AdvPersistence — skip
                if (resolveInterfaceFqn(vd.getInitializer().get()) == null) continue; // initializer's resolved type isn't an interface

                if (interfaceVars == null) interfaceVars = new HashMap<>();
                interfaceVars.put(vd.getNameAsString(), declaredIfaceFqn);
                candidateInterfaces.add(declaredIfaceFqn);   // make it visible to visit() on other files
            }
        }
        if (interfaceVars == null) return;

        for (MethodCallExpr mc : md.findAll(MethodCallExpr.class)) {
            if (mc.getScope().isEmpty()) continue;
            if (!(unwrap(mc.getScope().get()) instanceof NameExpr ne)) continue;
            String ifaceFqn = interfaceVars.get(ne.getNameAsString());
            if (ifaceFqn == null) continue;
            String params = resolvedParamTypes(mc);
            if (params == null) continue;   // unresolvable — skip rather than emit a wrong edge
            interfaceCalls.add(new InterfaceCall(methodFqn, ifaceFqn, mc.getNameAsString() + "(" + params + ")"));
        }
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (interfaceCalls.isEmpty()) {
            System.out.println("[InterfaceResolver] no interface-typed local-var calls found; nothing to link.");
            return;
        }
        if (interfaceToImplementers.isEmpty()) {
            System.out.println("[InterfaceResolver] no classes implement any candidate interface (" +
                candidateInterfaces.size() + " candidates); nothing to link.");
            return;
        }

        // Index the implementers' declared methods (from the master batch).
        Map<String, List<MethodNode>> byOwner = new HashMap<>();
        for (MethodNode mn : batch.methods) {
            for (Set<String> implementers : interfaceToImplementers.values()) {
                if (implementers.contains(mn.ownerFqn())) {
                    byOwner.computeIfAbsent(mn.ownerFqn(), k -> new ArrayList<>()).add(mn);
                    break;
                }
            }
        }

        Pattern sigPattern = Pattern.compile("([a-zA-Z0-9_$]+\\(.*\\))$");
        Set<String> emitted = new HashSet<>();   // dedup by "from#to"
        int edges = 0;
        for (InterfaceCall call : interfaceCalls) {
            Set<String> implementers = interfaceToImplementers.get(call.interfaceFqn());
            if (implementers == null) continue;
            for (String cls : implementers) {
                List<MethodNode> methods = byOwner.get(cls);
                if (methods == null) continue;
                for (MethodNode mn : methods) {
                    Matcher m = sigPattern.matcher(mn.fqn());
                    if (!m.find()) continue;
                    if (!m.group(1).equals(call.signature())) continue;
                    if (!emitted.add(call.callerFqn() + "#" + mn.fqn())) continue;
                    batch.calls.add(new CallEdge(call.callerFqn(), mn.fqn(), "interface-dispatch"));
                    edges++;
                }
            }
        }
        System.out.printf("[InterfaceResolver] candidateInterfaces=%d  implementers=%d  interfaceCalls=%d  edges=%d%n",
            candidateInterfaces.size(), interfaceToImplementers.values().stream().mapToInt(Set::size).sum(),
            interfaceCalls.size(), edges);
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

    /** FQN if {@code type} resolves (via SymbolSolver) to an interface, else {@code null}. */
    private static String resolveInterfaceFqn(Type type) {
        try {
            ResolvedType rt = type.resolve();
            return interfaceFqnOf(rt);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * FQN if {@code expr}'s resolved type (via SymbolSolver) is an interface, else {@code null}.
     * Works uniformly for a plain call, a chained call, or a constructor call — no need to
     * distinguish the expression's AST kind; {@code calculateResolvedType()} resolves any of them.
     */
    private static String resolveInterfaceFqn(Expression expr) {
        try {
            ResolvedType rt = expr.calculateResolvedType();
            return interfaceFqnOf(rt);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Ancestor interfaces we never want to treat as candidates: JDK/platform interfaces (huge,
     * unrelated implementer fan-out) and {@code com.adventnet.persistence} (the AdvPersistence
     * framework's own structural interfaces, e.g. {@code DataObject} — same fan-out risk, just
     * from a third-party jar instead of the JDK).
     */
    private static boolean isExcludedInterface(String qualifiedName) {
        return isJavaStandardLibrary(qualifiedName) || qualifiedName.startsWith("com.adventnet.persistence");
    }

    private static boolean isJavaStandardLibrary(String qualifiedName) {
        return qualifiedName.startsWith("java.")
            || qualifiedName.startsWith("javax.")
            || qualifiedName.startsWith("jakarta.")
            || qualifiedName.startsWith("org.w3c.dom")
            || qualifiedName.startsWith("org.xml.sax");
    }

    private static String interfaceFqnOf(ResolvedType rt) {
        if (!rt.isReferenceType()) return null;
        ResolvedReferenceType ref = rt.asReferenceType();
        Optional<ResolvedReferenceTypeDeclaration> decl = ref.getTypeDeclaration();
        if (decl.isEmpty()) return null;
        ResolvedTypeDeclaration td = decl.get();
        if (!td.isInterface()) return null;
        return ref.getQualifiedName();
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
