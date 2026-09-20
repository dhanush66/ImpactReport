package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Helpers shared by the §4.1 resolvers (NotificationAudit, Orchestration,
 * Event, Config, Security, ExternalApi, StateMachine, ClassShape). Centralises
 * the boilerplate for:
 * <ul>
 *   <li>computing the {@code owner.method(paramTypes)} FQN for a method node,</li>
 *   <li>walking up to the enclosing class / class-FQN,</li>
 *   <li>pulling string-literal or fully-qualified-constant arguments out of a
 *       {@link MethodCallExpr} / {@link ObjectCreationExpr},</li>
 *   <li>identifying scope text for static-method calls ({@code Foo.bar()} →
 *       {@code "Foo"}).</li>
 * </ul>
 * Resolvers stay focused on their detection rules; this class owns the AST glue.
 */
public final class ResolverUtils {
    private ResolverUtils() {}

    /**
     * Application-wide {@link JavaParserFacade} singleton. Built exactly ONCE per JVM run
     * — from whichever source roots the FIRST caller supplies — and reused by every
     * resolver afterwards. SymbolSolver construction walks every source root + product jar
     * on disk, so rebuilding it per-resolver would multiply that cost by the resolver count.
     */
    private static volatile JavaParserFacade sharedFacade;

    /** Shared access point for JavaParser's SymbolSolver facade. Same instance every call. */
    public static JavaParserFacade javaParserFacade(List<Path> sourceRoots) {
        JavaParserFacade facade = sharedFacade;
        if (facade == null) {
            synchronized (ResolverUtils.class) {
                facade = sharedFacade;
                if (facade == null) {
                    facade = JavaParserFacade.get(buildTypeSolver(sourceRoots));
                    sharedFacade = facade;
                }
            }
        }
        return facade;
    }

    /** Build a SymbolSolver that covers JDK types, project source roots, and nearby product jars. */
    private static TypeSolver buildTypeSolver(List<Path> sourceRoots) {
        CombinedTypeSolver solver = new CombinedTypeSolver();
        solver.add(new ReflectionTypeSolver());
        for (Path root : expandTypeSolverRoots(sourceRoots)) {
            try { solver.add(new JavaParserTypeSolver(root)); }
            catch (Exception ignored) {}
        }
        for (Path jar : discoverJarDependencies(sourceRoots)) {
            try { solver.add(new JarTypeSolver(jar)); }
            catch (Exception ignored) {}
        }
        return solver;
    }

    private static List<Path> expandTypeSolverRoots(List<Path> sourceRoots) {
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        for (Path root : sourceRoots == null ? List.<Path>of() : sourceRoots) {
            if (root == null) continue;
            addTypeSolverRoot(roots, root);
            addTypeSolverRoot(roots, root.resolve("server"));
            addTypeSolverRoot(roots, root.resolve("source").resolve("java_source"));
            addTypeSolverRoot(roots, root.resolve("source").resolve("java_source").resolve("server"));
            addTypeSolverRoot(roots, root.resolve("source").resolve("java"));
            addTypeSolverRoot(roots, root.resolve("source").resolve("java").resolve("server"));
            addTypeSolverRoot(roots, root.resolve("src").resolve("main").resolve("java"));
            addTypeSolverRoot(roots, root.resolve("java"));

            Path web = root.resolve("web");
            if (Files.isDirectory(web)) {
                try (var stream = Files.list(web)) {
                    stream.filter(Files::isDirectory)
                            .map(appDir -> appDir.resolve("src"))
                            .forEach(src -> addTypeSolverRoot(roots, src));
                } catch (Exception ignored) {}
            }
        }
        return new ArrayList<>(roots);
    }

    private static void addTypeSolverRoot(Set<Path> roots, Path root) {
        if (root != null && Files.isDirectory(root)) {
            roots.add(root.toAbsolutePath().normalize());
        }
    }

    private static List<Path> discoverJarDependencies(List<Path> sourceRoots) {
        LinkedHashSet<Path> jars = new LinkedHashSet<>();
        for (Path sourceRoot : sourceRoots == null ? List.<Path>of() : sourceRoots) {
            if (sourceRoot == null) continue;
            Path probe = sourceRoot.toAbsolutePath().normalize();
            while (probe != null) {
                addJarsUnder(jars, probe.resolve("lib"));
                probe = probe.getParent();
            }
        }
        return new ArrayList<>(jars);
    }

    private static void addJarsUnder(Set<Path> jars, Path libDir) {
        if (libDir == null || !Files.isDirectory(libDir)) return;
        try (var stream = Files.walk(libDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".jar"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(jars::add);
        } catch (IOException ignored) {}
    }

    /** Enclosing class simple name + pkg → FQN. Returns "" when none found. */
    public static String enclosingClassFqn(Node n, String pkg) {
        ClassOrInterfaceDeclaration cls = n.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (cls != null) {
            String name = cls.getNameAsString();
            return pkg.isEmpty() ? name : pkg + "." + name;
        }
        return pkg;
    }

    /**
     * Canonical FQN for a method declaration, matching the form used in CoreExtractor:
     * {@code "owner.method(paramType1,paramType2)"}. Falls back to source-text param
     * types if SymbolSolver fails (mirrors MessageConstantResolver pattern).
     */
    public static String methodFqn(MethodDeclaration md, String pkg) {
        String owner = enclosingClassFqn(md, pkg);
        return owner + "." + md.getNameAsString() + "(" + canonicalParamTypes(md) + ")";
    }

    /** Canonical FQN for a constructor declaration. */
    public static String constructorFqn(ConstructorDeclaration cd, String pkg) {
        String owner = enclosingClassFqn(cd, pkg);
        return owner + ".<init>(" + canonicalParamTypesC(cd) + ")";
    }

    /**
     * Walk up to whichever method or constructor encloses the given AST node. Returns
     * the FQN, or {@code null} if the node lives outside any callable (e.g. in a
     * static field initialiser).
     */
    public static String enclosingMethodFqn(Node n, String pkg) {
        Optional<MethodDeclaration> md = n.findAncestor(MethodDeclaration.class);
        if (md.isPresent()) return methodFqn(md.get(), pkg);
        Optional<ConstructorDeclaration> cd = n.findAncestor(ConstructorDeclaration.class);
        if (cd.isPresent()) return constructorFqn(cd.get(), pkg);
        return null;
    }

    private static String canonicalParamTypes(MethodDeclaration md) {
        try {
            var r = md.resolve();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.getNumberOfParams(); i++) {
                if (i > 0) sb.append(',');
                try { sb.append(r.getParam(i).getType().describe()); }
                catch (Throwable t) { sb.append('?'); }
            }
            return sb.toString();
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < md.getParameters().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(md.getParameter(i).getType().asString());
            }
            return sb.toString();
        }
    }

    private static String canonicalParamTypesC(ConstructorDeclaration cd) {
        try {
            var r = cd.resolve();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.getNumberOfParams(); i++) {
                if (i > 0) sb.append(',');
                try { sb.append(r.getParam(i).getType().describe()); }
                catch (Throwable t) { sb.append('?'); }
            }
            return sb.toString();
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cd.getParameters().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(cd.getParameter(i).getType().asString());
            }
            return sb.toString();
        }
    }

    /**
     * Pull the string identifier out of an argument expression. Returns the literal
     * value for a {@link StringLiteralExpr}, or the bare name for a
     * {@link FieldAccessExpr} / {@link NameExpr} (i.e. constants like
     * {@code NotifConstants.WF_REQUEST_REJECTED} surface as {@code "WF_REQUEST_REJECTED"}).
     * Returns {@code null} if no usable identifier can be extracted.
     */
    public static String stringArg(Expression expr) {
        if (expr == null) return null;
        if (expr instanceof StringLiteralExpr sle) return sle.getValue();
        if (expr instanceof FieldAccessExpr fae) return fae.getNameAsString();
        if (expr instanceof NameExpr ne)         return ne.getNameAsString();
        return null;
    }

    /** Returns the scope text of a method call, or "" if no explicit scope. */
    public static String scopeText(MethodCallExpr call) {
        return call.getScope().map(Object::toString).orElse("");
    }

    /** Method name (the simple identifier after the last dot). */
    public static String callName(MethodCallExpr call) {
        return call.getNameAsString();
    }

    /** True when {@code call} matches {@code scope.method(...)} loosely (scope can be a fragment). */
    public static boolean callMatches(MethodCallExpr call, String scopeFragment, String methodName) {
        if (!call.getNameAsString().equals(methodName)) return false;
        String scope = scopeText(call);
        return scope.contains(scopeFragment);
    }

    /** Extract `(host, system-id)` from a URL literal — used by E1 ExternalApiResolver. */
    public static String[] hostAndSystemFromUrl(String url) {
        if (url == null || url.isEmpty()) return null;
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) return null;
        int hostEnd = url.indexOf('/', schemeEnd + 3);
        String host = hostEnd < 0 ? url.substring(schemeEnd + 3) : url.substring(schemeEnd + 3, hostEnd);
        if (host.isEmpty()) return null;
        // System id: well-known hosts get friendly names; otherwise use the bare host.
        String id;
        String lower = host.toLowerCase();
        if      (lower.contains("graph.microsoft.com"))     id = "Graph";
        else if (lower.contains("sharepoint.com"))           id = "SharePoint";
        else if (lower.contains("slack.com"))                id = "Slack";
        else if (lower.contains("zoho"))                     id = "Zoho";
        else if (lower.contains("google"))                   id = "Google";
        else if (lower.contains("github"))                   id = "GitHub";
        else                                                 id = host;
        return new String[]{host, id};
    }
}
