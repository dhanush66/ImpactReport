package io.spmp.impact.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.nio.file.Path;
import java.util.List;

/**
 * Resolver hook invoked by {@link CoreExtractor} once per compilation unit.
 *
 * Implementations add synthetic nodes/edges (extra labels, REST endpoints, DB tables,
 * SPI services, task-registry dispatch) that a pure call graph cannot infer.
 */
public interface BoundaryResolver {
    /** Called once per compilation unit during ingest. */
    void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch);

    /**
     * Called once per method during Pass 1 from {@link CoreExtractor#walkMethodForPass1},
     * after {@link BodyCollector} has already walked the method body. Writes to the
     * thread-local {@code local} batch — no lock required.
     */
    default void visitMethod(MethodDeclaration md, String methodFqn, String ownerFqn,
                             List<BodyCollector.CallSite> calls, ExtractionBatch local) {}

    /**
     * Called once after all CUs have been visited. Used by resolvers that need
     * to emit cross-file edges (e.g., dispatcher method → all known handlers).
     */
    default void afterAll(ExtractionBatch batch) {}
}
