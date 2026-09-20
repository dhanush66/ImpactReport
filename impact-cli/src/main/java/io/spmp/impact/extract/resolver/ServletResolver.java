package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.model.GraphEdges.ExposesEdge;
import io.spmp.impact.model.GraphNodes.RestEndpointNode;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Emits a synthetic {@code :RestEndpoint} per servlet class with a URL inferred from the
 * class's simple name (best-effort fallback; full XML-driven URL mapping is out of scope
 * for v1). The URL is namespaced so it's distinguishable from real REST URLs that a
 * future web.xml resolver will emit.
 *
 * <p>Example: {@code com.manageengine.migration.copycontents.servlet.AddCopyContentTask}
 *           → URL {@code servlet:AddCopyContentTask}.
 */
public class ServletResolver implements BoundaryResolver {

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            if (cls.isInterface()) continue;
            if (!extendsHttpServlet(cls)) continue;
            String fqn = pkg.isEmpty() ? cls.getNameAsString() : pkg + "." + cls.getNameAsString();
            String url = "servlet:" + cls.getNameAsString();
            batch.restEndpoints.add(new RestEndpointNode(url, fqn));
            batch.exposes.add(new ExposesEdge(fqn, url));
        }
    }

    private static boolean extendsHttpServlet(ClassOrInterfaceDeclaration cls) {
        Optional<com.github.javaparser.ast.type.ClassOrInterfaceType> parent =
            cls.getExtendedTypes().stream().findFirst();
        if (parent.isEmpty()) return false;
        String name = parent.get().getNameAsString();
        // Match HttpServlet directly OR common SPMP intermediate base classes that extend it.
        return name.equals("HttpServlet") || name.endsWith("Servlet");
    }
}
