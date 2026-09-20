package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.model.GraphEdges.ThreadStartEdge;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves Thread.start() -> run() dispatch edges.
 *
 * Collection is fully delegated to CoreExtractor pass1 (outside resolversLock):
 *   walkMethodForPass1  -> fills batch.pendingRunMethods    (run() in Thread/Runnable classes)
 *   walkMethodForPass1  -> fills batch.pendingStartMethods  (distinct receiver classes of .start())
 *   walkConstructorForPass1 -> same for constructors
 *
 * visit() is a no-op. afterAll() joins the two pending collections and emits one
 * ThreadStartEdge per thread class: {class}.start() -> {class}.run(). Because
 * pendingStartMethods is deduped by receiver class at collection time, afterAll()
 * iterates one entry per thread class instead of one per .start() call site.
 */
public class ThreadStartResolver implements BoundaryResolver {

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // No-op: collection happens in CoreExtractor.walkMethodForPass1 / walkConstructorForPass1,
        // which run before the resolversLock is acquired, avoiding lock contention.
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        // Build classFqn -> run() FQN lookup from all collected run() declarations.
        Map<String, String> runMap = new HashMap<>();
        for (ExtractionBatch.PendingRunMethod r : batch.pendingRunMethods) {
            runMap.put(r.classFqn(), r.methodFqn());
        }

        // Join: for each distinct thread class whose .start() was called, find its run()
        // and emit one STARTS_THREAD edge: receiverClass.start() -> receiverClass.run().
        // The from is the start() method of the thread class (not the outer caller) so that
        // the edge models the thread lifecycle dispatch, not the caller context; the caller
        // context is captured by the regular CALLS graph. pendingStartMethods is already
        // deduped by receiver class, so no extra emitted-key set is needed.
        int edgeCount = 0;
        for (ExtractionBatch.PendingStartMethod s : batch.pendingStartMethods) {
            String receiverClassFqn = s.scopeTypeFqn();
            String runFqn = runMap.get(receiverClassFqn);
            if (runFqn == null) continue;
            String startFqn = s.methodFqn() ;
            batch.threadStartEdges.add(new ThreadStartEdge(startFqn, runFqn));
            edgeCount++;
        }
        System.out.printf("[ThreadStartResolver] runDecls=%d  startMethods=%d  edges=%d%n",
                runMap.size(), batch.pendingStartMethods.size(), edgeCount);
    }
}
