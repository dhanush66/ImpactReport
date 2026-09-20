package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;

import io.spmp.impact.extract.BodyCollector;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.model.GraphEdges.CallEdge;
import io.spmp.impact.model.GraphEdges.ImplementsEdge;
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
 * Resolves interface-dispatch through {@code NotificationMacro} in {@code NotificationTrigger}.
 *
 * <p>{@code NotificationTrigger} holds a {@code NotificationMacro} field (an interface) and calls it:
 * <pre>
 *   userSubject = this.notificationMacro.parseMacroForAdmin(subject, rb, loginId, ...);
 * </pre>
 * A static call graph resolves this only to {@code NotificationMacro.parseMacroForAdmin()}
 * (the interface method), so the concrete implementations are never linked. This resolver
 * bridges that gap — like {@link ReportsXmlResolver} does for {@code ReportClientUtil}.
 *
 * <p><b>Targets:</b> classes that implement {@code NotificationMacro} (collected from
 * {@code batch.implementsEdges}, i.e. JavaParser-derived during ingestion).
 *
 * <p><b>Detection (JavaParser, {@link #visitMethod}, restricted to {@code NotificationTrigger}):</b>
 * calls of the form {@code this.<field>.method(...)} / {@code <field>.method(...)} where {@code <field>}
 * is a {@code NotificationMacro}-typed field.
 *
 * <p><b>Emission ({@link #afterAll}):</b> for each such call a {@code :CALLS} edge
 * {@code callerMethod -> implClass.method()} is emitted for every implementing class that declares a
 * method with a matching {@code name(paramTypes)} signature. Multiple implementers match → multiple edges.
 */
public class NotificationMacroResolver implements BoundaryResolver {

    private static final String MACRO_INTERFACE = "NotificationMacro";
    private static final String MACRO_INTERFACE_FQN = "com.adventnet.sym.adsm.common.server.admin.notification.NotificationMacro";
    private static final String CALLER_CLASS    = "NotificationTrigger";
    private Set<String> macroClasses = new HashSet<>();

    /** (callerMethodFqn, "methodName(paramTypeFqns)") collected across files (parallel). */
    private record MacroCall(String callerFqn, String signature) {}
    private final ConcurrentLinkedQueue<MacroCall> macroCalls = new ConcurrentLinkedQueue<>();

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            // Filter out interfaces—we only want concrete/abstract classes
            if (!classDecl.isInterface()) {
                try {
                    // Resolve the class type using SymbolSolver
                    ResolvedReferenceTypeDeclaration resolvedClass = classDecl.resolve();

                    // Check all ancestors (superclasses and implemented interfaces recursively)
                    boolean inheritsInterface = resolvedClass.getAllAncestors().stream()
                        .anyMatch(ancestor -> 
                            ancestor.getQualifiedName().equals(MACRO_INTERFACE_FQN) ||
                            ancestor.describe().equals(MACRO_INTERFACE)
                        );

                    if (inheritsInterface) {
                        macroClasses.add(classDecl.getFullyQualifiedName().get());
                    }
                } catch (Exception e) {
                    // Symbol resolution might fail if missing external library dependencies
                    System.err.println("Could not resolve symbol for: " + classDecl.getNameAsString());
                }
            }
        });
    }

    @Override
    public void visitMethod(MethodDeclaration md, String methodFqn, String ownerFqn,
                            List<BodyCollector.CallSite> calls, ExtractionBatch local) {
        // Only the NotificationTrigger class calls the macro field.
        if (!CALLER_CLASS.equals(simpleTypeName(ownerFqn))) return;

        // NotificationMacro-typed fields of the enclosing class.
        Set<String> macroFields = macroFieldNames(md);
        if (macroFields.isEmpty()) return;

        for (MethodCallExpr mc : md.findAll(MethodCallExpr.class)) {
            if (mc.getScope().isEmpty()) continue;
            if (!isMacroFieldAccess(unwrap(mc.getScope().get()), macroFields)) continue;
            String params = resolvedParamTypes(mc);
            if (params == null) continue;   // unresolvable — skip rather than emit a wrong edge
            macroCalls.add(new MacroCall(methodFqn, mc.getNameAsString() + "(" + params + ")"));
        }
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (macroCalls.isEmpty()) {
            System.out.println("[NotificationMacroResolver] no this.notificationMacro.*(...) calls found; nothing to link.");
            return;
        }

        // Concrete NotificationMacro implementers.
        // Set<String> macroClasses = new HashSet<>();
        // for (ImplementsEdge ie : batch.implementsEdges) {
        //     if (MACRO_INTERFACE.equals(simpleTypeName(ie.toInterfaceFqn()))) {
        //         macroClasses.add(ie.fromClassFqn());
        //     }
        // }
        if (macroClasses.isEmpty()) {
            System.out.println("[NotificationMacroResolver] no classes implement " + MACRO_INTERFACE + "; nothing to link.");
            return;
        }

        // Index the implementers' declared methods (from the master batch).
        Map<String, List<MethodNode>> byOwner = new HashMap<>();
        for (MethodNode mn : batch.methods) {
            if (!macroClasses.contains(mn.ownerFqn())) continue;
            byOwner.computeIfAbsent(mn.ownerFqn(), k -> new ArrayList<>()).add(mn);
        }

        Pattern sigPattern = Pattern.compile("([a-zA-Z0-9_$]+\\(.*\\))$");
        Set<String> emitted = new HashSet<>();   // dedup by "from#to"
        int edges = 0;
        for (MacroCall call : macroCalls) {
            for (String cls : macroClasses) {
                List<MethodNode> methods = byOwner.get(cls);
                if (methods == null) continue;
                for (MethodNode mn : methods) {
                    Matcher m = sigPattern.matcher(mn.fqn());
                    if (!m.find()) continue;
                    if (!m.group(1).equals(call.signature())) continue;
                    if (!emitted.add(call.callerFqn() + "#" + mn.fqn())) continue;
                    batch.calls.add(new CallEdge(call.callerFqn(), mn.fqn(), "notification-macro"));
                    edges++;
                }
            }
        }
        System.out.printf("[NotificationMacroResolver] implementers=%d  macroCalls=%d  edges=%d%n",
            macroClasses.size(), macroCalls.size(), edges);
    }

    /** Field names of the enclosing class whose declared type is {@code NotificationMacro}. */
    private static Set<String> macroFieldNames(MethodDeclaration md) {
        var cls = md.findAncestor(ClassOrInterfaceDeclaration.class);
        if (cls.isEmpty()) return Set.of();
        Set<String> names = new HashSet<>();
        for (FieldDeclaration fd : cls.get().getFields()) {
            for (VariableDeclarator v : fd.getVariables()) {
                if (MACRO_INTERFACE.equals(simpleTypeName(v.getType().asString()))) {
                    names.add(v.getNameAsString());
                }
            }
        }
        return names;
    }

    /** True if {@code scope} is {@code this.<field>} or a bare {@code <field>} in {@code fields}. */
    private static boolean isMacroFieldAccess(Expression scope, Set<String> fields) {
        if (scope instanceof FieldAccessExpr fa) {
            return fa.getScope() instanceof ThisExpr && fields.contains(fa.getNameAsString());
        }
        if (scope instanceof NameExpr ne) {
            return fields.contains(ne.getNameAsString());
        }
        return false;
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
