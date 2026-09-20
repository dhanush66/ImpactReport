package io.spmp.impact.extract;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;

import io.spmp.impact.extract.JavaProjectParser.ParsedFile;
import io.spmp.impact.model.GraphEdges.CallEdge;
import io.spmp.impact.model.GraphEdges.ClassFileEdge;
import io.spmp.impact.model.GraphEdges.ExtendsEdge;
import io.spmp.impact.model.GraphEdges.FieldAccessEdge;
import io.spmp.impact.model.GraphEdges.ImplementsEdge;
import io.spmp.impact.model.GraphEdges.OverridesEdge;
import io.spmp.impact.model.GraphNodes.ClassNode;
import io.spmp.impact.model.GraphNodes.FieldNode;
import io.spmp.impact.model.GraphNodes.FileNode;
import io.spmp.impact.model.GraphNodes.MethodNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Three-pass call-graph extractor that NEVER invokes SymbolSolver.
 *
 * <p>The historical CoreExtractor delegated to JavaParser's SymbolSolver for every
 * {@code call.resolve()} site. Empirically that's O(n²) on overloaded methods
 * (JavaParser issue #1554), causing 10-15+ minute hangs on pathological files.
 * This rewrite replaces SymbolSolver with import + index lookups, eliminates the
 * hangs entirely, and runs ~5× faster on the same corpus.
 *
 * <h2>Pass structure</h2>
 *
 * <h3>Pass 1a + 1b (combined): per-file parse, declare, and collect body sites</h3>
 * <p>Workers parse each file with the no-symbol parser, walk top-level
 * {@link TypeDeclaration}s once, and:
 * <ul>
 *   <li>Emit {@link ClassNode}, {@link MethodNode}, {@link FieldNode} into the worker's
 *       local {@link ExtractionBatch}</li>
 *   <li>Emit {@link ExtendsEdge}, {@link ImplementsEdge} using import-resolved type names</li>
 *   <li>Record class/method/field/imports into the shared {@link GlobalIndex}</li>
 *   <li>For each method/constructor body, run {@link BodyCollector} to push
 *       {@link BodyCollector.CallSite} / {@link BodyCollector.FieldAccessSite} records
 *       into the local batch's {@code pendingCalls} / {@code pendingFieldAccesses}.
 *       No resolution happens here.</li>
 *   <li>Invoke {@link BoundaryResolver#visit} on the CU (synchronized — resolver state
 *       is shared mutable Maps).</li>
 * </ul>
 *
 * <h3>Between passes: freeze the index</h3>
 * <p>Worker batches are merged into the master batch; CUs are dropped (no references
 * retained beyond Pass 1). The shared {@link GlobalIndex} is {@code freeze()}d to
 * immutable maps for lock-free reads in Pass 2.
 *
 * <h3>Pass 2: resolve every CallSite / FieldAccessSite via the index</h3>
 * <p>A worker pool drains {@code master.pendingCalls} / {@code master.pendingFieldAccesses}
 * and calls {@link CallResolver#resolve}, appending {@link CallEdge}s and
 * {@link FieldAccessEdge}s. Every call site produces 1..N edges (or 1 "unresolved"
 * placeholder) — count is preserved end-to-end.
 *
 * <h3>Pass 3: emitOverrides (unchanged)</h3>
 * <p>Uses {@link GlobalIndex#transitiveAncestors} for the ancestor walk.
 */
public class CoreExtractor {

    private static  JavaProjectParser parser;
    private final String repoId;
    private final String commitSha;
    private final List<BoundaryResolver> resolvers;
    private final GlobalIndex index = new GlobalIndex();

    private java.util.Map<String, String> existingHashes = java.util.Map.of();
    private boolean incremental = false;
    private int skippedUnchanged = 0;

    private java.util.function.Consumer<ExtractionBatch> partialFlushSink;
    private int flushEveryFiles = 500;
    private int totalFlushedFiles = 0;

    /** Audit log writer. {@code null} → audit logging disabled. */
    private java.io.PrintWriter auditLog;

    /** Optional per-call TSV log path; passed to {@link CallResolver}. */
    private Path callTsvPath;

    public CoreExtractor(JavaProjectParser parser, String repoId, String commitSha) {
        this(parser, repoId, commitSha, List.of());
    }

    public CoreExtractor(JavaProjectParser parser, String repoId, String commitSha,
                         List<BoundaryResolver> resolvers) {
        this.parser = parser;
        this.repoId = repoId;
        this.commitSha = commitSha;
        this.resolvers = resolvers;
    }

    public void setPartialFlushSink(java.util.function.Consumer<ExtractionBatch> sink, int everyNFiles) {
        this.partialFlushSink = sink;
        if (everyNFiles > 0) this.flushEveryFiles = everyNFiles;
    }

    public void setIncremental(boolean incremental, java.util.Map<String, String> existingHashes) {
        this.incremental = incremental;
        this.existingHashes = existingHashes == null ? java.util.Map.of() : existingHashes;
    }

    public int skippedUnchanged() { return skippedUnchanged; }

    public void enableAuditLog(Path path) {
        try {
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            this.auditLog = new java.io.PrintWriter(Files.newBufferedWriter(path,
                java.nio.charset.StandardCharsets.UTF_8));
            audit("AUDIT", "Begin ingest audit log at " + java.time.Instant.now());
            // Per-call TSV is a sibling file alongside the audit log.
            this.callTsvPath = path.resolveSibling(path.getFileName().toString() + ".calls.tsv");
        } catch (Throwable t) {
            System.err.println("[audit] failed to open audit log: " + t.getMessage());
            this.auditLog = null;
        }
    }

    private synchronized void audit(String tag, String msg) {
        if (auditLog == null) return;
        auditLog.printf("[%s] %s%n", tag, msg);
        auditLog.flush();
    }

    /** Worker count for the parallel passes. */
    private static final int WORKER_THREAD_COUNT = Math.max(2,
        Math.min(4, Runtime.getRuntime().availableProcessors() / 2));

    // ─── Orchestration ──────────────────────────────────────────────────

    public ExtractionBatch run() throws IOException {
        long runStart = System.nanoTime();
        ExtractionBatch master = new ExtractionBatch(repoId, commitSha);

        List<JavaProjectParser.RepoFile> files = parser.listJavaFilesByRepo();
        Map<String, Integer> perRepoCount = new LinkedHashMap<>();
        // To check for duplicates in the list of files, we can use a Set to track seen files and identify duplicates. Here's how you can do it:
        // Set<JavaProjectParser.RepoFile> seen = new java.util.HashSet<>();
        // Set<JavaProjectParser.RepoFile> duplicates = files.stream().filter(item -> !seen.add(item)).collect(Collectors.toSet());
        
        for (var rf : files) {
            perRepoCount.merge(rf.repoId(), 1, Integer::sum);
            if (!repoId.equals(rf.repoId())) master.additionalRepoIds.add(rf.repoId());
        }
        System.out.printf("[CoreExtractor] discovered %d Java files across %d repos: %s%n",
            files.size(), perRepoCount.size(), perRepoCount);
        audit("DISCOVERY", "files=" + files.size() + " perRepo=" + perRepoCount);

        skippedUnchanged = 0;
        List<JavaProjectParser.RepoFile> toProcess = new ArrayList<>(files.size());
        for (var rf : files) {
            if (incremental) {
                String pathStr = rf.path().toString().replace('\\', '/');
                String prevHash = existingHashes.get(pathStr);
                if (prevHash != null && !prevHash.isEmpty()) {
                    String currentHash = sha1(rf.path());
                    if (prevHash.equals(currentHash)) { skippedUnchanged++; continue; }
                }
            }
            toProcess.add(rf);
        }

        // ── Pass 1: parse + walk decls + collect body sites ────────────
        long p1Start = System.nanoTime();
        runPass1(toProcess, master);
        long p1Sec = (System.nanoTime() - p1Start) / 1_000_000_000L;
        System.out.printf("[CoreExtractor] pass 1 done: %d files in %ds (%d classes, %d methods, %d pending-calls)%n",
            toProcess.size(), p1Sec, master.classCount(), master.methodCount(), master.pendingCalls.size());
        audit("PASS1", "elapsedSec=" + p1Sec + " classes=" + master.classCount()
            + " methods=" + master.methodCount()
            + " fields=" + master.fields.size()
            + " extends=" + master.extendsEdges.size()
            + " implements=" + master.implementsEdges.size()
            + " pendingCalls=" + master.pendingCalls.size()
            + " pendingFieldAccesses=" + master.pendingFieldAccesses.size());

        // ── Freeze the index ───────────────────────────────────────────
        long freezeStart = System.nanoTime();
        index.freeze();
        long freezeMs = (System.nanoTime() - freezeStart) / 1_000_000L;
        System.out.printf("[CoreExtractor] index frozen in %dms (classes=%d methods=%d fields=%d files=%d)%n",
            freezeMs, index.classCount(), index.methodTotalCount(), index.fieldCount(), index.fileCount());
        audit("INDEX", "classes=" + index.classCount() + " methods=" + index.methodTotalCount()
            + " fields=" + index.fieldCount() + " files=" + index.fileCount() + " freezeMs=" + freezeMs);

        // ── Pass 2: resolve call sites ────────────────────────────────
        long p2Start = System.nanoTime();
        runPass2(master);
        long p2Sec = (System.nanoTime() - p2Start) / 1_000_000_000L;
        System.out.printf("[CoreExtractor] pass 2 done: %ds — %d call edges, %d field-access edges%n",
            p2Sec, master.calls.size(), master.fieldAccess.size());
        audit("PASS2", "elapsedSec=" + p2Sec
            + " callEdges=" + master.calls.size()
            + " fieldAccessEdges=" + master.fieldAccess.size());

        // ── Resolver afterAll() — must run AFTER index freeze so cross-file edges land in master.
        for (BoundaryResolver r : resolvers) {
            try { r.afterAll(master); }
            catch (Throwable t) {
                System.err.println("[resolver-afterAll-error] " + r.getClass().getSimpleName() + " : " + t.getMessage());
            }
        }

        // ── Streaming flush before OVERRIDES (boundary edges from afterAll could be big) ──
        if (partialFlushSink != null) {
            partialFlushSink.accept(master.drainLeafCollections());
        }

        // ── Pass 3: emit OVERRIDES via the index ─────────────────────
        long p3Start = System.nanoTime();
        emitOverrides(master, index);
        long p3Sec = (System.nanoTime() - p3Start) / 1_000_000_000L;
        System.out.printf("[CoreExtractor] pass 3 (overrides) done: %ds — %d edges%n",
            p3Sec, master.overrides.size());
        audit("PASS3", "elapsedSec=" + p3Sec + " overridesEdges=" + master.overrides.size());

        long runSec = (System.nanoTime() - runStart) / 1_000_000_000L;
        System.out.printf("[CoreExtractor] total run: %ds (%d classes, %d methods, %d calls, %d overrides)%n",
            runSec, master.classCount(), master.methodCount(), master.callCount(), master.overrides.size());
        audit("RUN_TOTAL", "totalSec=" + runSec
            + " classes=" + master.classCount()
            + " methods=" + master.methodCount()
            + " calls=" + master.callCount()
            + " overrides=" + master.overrides.size());

        // CALL kind distribution (post-Pass-2).
        Map<String, Long> callKindCounts = new HashMap<>();
        for (var ce : master.calls) callKindCounts.merge(ce.kind(), 1L, Long::sum);
        audit("CALL_KINDS", callKindCounts.toString());
        long total = master.calls.size();
        long virtualEdges = callKindCounts.getOrDefault("virtual", 0L);
        long staticEdges  = callKindCounts.getOrDefault("static", 0L);
        long ctorEdges    = callKindCounts.getOrDefault("constructor", 0L);
        long unresolved   = callKindCounts.getOrDefault("unresolved", 0L);
        long ambiguous = total - virtualEdges - staticEdges - ctorEdges - unresolved;
        if (total > 0) {
            System.out.printf("[CoreExtractor] CALL kinds: virtual=%d (%.1f%%) static=%d (%.1f%%) constructor=%d (%.1f%%) ambiguous=%d (%.1f%%) unresolved=%d (%.1f%%)%n",
                virtualEdges, 100.0 * virtualEdges / total,
                staticEdges,  100.0 * staticEdges / total,
                ctorEdges,    100.0 * ctorEdges / total,
                ambiguous,    100.0 * ambiguous / total,
                unresolved,   100.0 * unresolved / total);
        }
        if (auditLog != null) auditLog.flush();
        return master;
    }

    // ─── Pass 1 ─────────────────────────────────────────────────────────

    private void runPass1(List<JavaProjectParser.RepoFile> toProcess, ExtractionBatch master) {
        int nThreads = WORKER_THREAD_COUNT;
        final Object resolversLock = new Object();
        final AtomicInteger processed = new AtomicInteger();
        final int total = toProcess.size();
        final long passStart = System.nanoTime();

        ExecutorService exec = Executors.newFixedThreadPool(nThreads, r -> {
            Thread t = new Thread(r, "core-extractor-p1");
            t.setDaemon(true);
            return t;
        });

        Map<Long, ExtractionBatch> threadBatches = new ConcurrentHashMap<>();

        // Progress watchdog every 10s.
        Thread watchdog = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try { Thread.sleep(10_000L); } catch (InterruptedException ie) { return; }
                int done = processed.get();
                long elapsedSec = (System.nanoTime() - passStart) / 1_000_000_000L;
                Runtime rt = Runtime.getRuntime();
                long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024L * 1024L);
                long maxMb = rt.maxMemory() / (1024L * 1024L);
                double rate = elapsedSec > 0 ? (double) done / elapsedSec : 0;
                int pct = total > 0 ? (int) (100.0 * done / total) : 0;
                int barLen = 30;
                int filled = total > 0 ? (int) ((double) barLen * done / total) : 0;
                StringBuilder bar = new StringBuilder();
                for (int i = 0; i < barLen; i++) bar.append(i < filled ? '#' : '-');
                System.out.printf("[CoreExtractor.pass1] [%s] %d/%d (%d%%) %.1f files/s heap=%dMB/%dMB elapsed=%ds%n",
                    bar, done, total, pct, rate, usedMb, maxMb, elapsedSec);
            }
        }, "core-extractor-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        List<Future<?>> futures = new ArrayList<>(toProcess.size());
        for (JavaProjectParser.RepoFile rf : toProcess) {
            futures.add(exec.submit(() -> {
                long tid = Thread.currentThread().getId();
                ExtractionBatch local = threadBatches.computeIfAbsent(tid,
                    k -> new ExtractionBatch(repoId, commitSha));
                processOneFile(rf, local, resolversLock);
                processed.incrementAndGet();
            }));
        }
        exec.shutdown();
        for (Future<?> f : futures) {
            try { f.get(); } catch (Throwable t) { /* per-task errors logged */ }
        }
        try { exec.awaitTermination(30, TimeUnit.MINUTES); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        watchdog.interrupt();
        try { watchdog.join(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        // Merge worker batches into master.
        for (ExtractionBatch local : threadBatches.values()) {
            master.mergeFrom(local);
        }
        threadBatches.clear();
    }

    /**
     * Pass 1 worker: parse one file, emit declarations + boundary edges, collect body sites.
     * On any unrecoverable error, logs and moves on. The CU goes out of scope on return —
     * eligible for GC immediately (this is why heap stays flat during the parallel pass).
     */
    private void processOneFile(JavaProjectParser.RepoFile rf,
                                ExtractionBatch local,
                                Object resolversLock) {
        Path path = rf.path();
        String fileRepoId = rf.repoId();
        String className = null;
        try {
            Optional<CompilationUnit> maybeCu = parser.parseFileNoSymbols(path);
             if (maybeCu.isEmpty()) return;
            CompilationUnit cu = maybeCu.get();

            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
            String filePath = path.toString().replace('\\', '/');
            String hash = sha1(path);
            local.files.add(new FileNode(filePath, pkg,
                fileRepoId == null ? repoId : fileRepoId, commitSha, hash));

            // Build the FileImports record FIRST — the body collector needs it
            // to resolve parameter and local-variable type FQNs.
            FileImports imports = buildImports(cu, pkg);
            index.recordImports(filePath, imports);

            // Walk top-level types.
            for (TypeDeclaration<?> td : cu.getTypes()) {
                walkTypeForPass1(td, pkg, filePath, local, imports);

                //For ADSproductApis.xml visit method, create link between modified URL to  calls inside execute method based requestURI
                if (td.isClassOrInterfaceDeclaration()) {
                    ClassOrInterfaceDeclaration clazz = td.asClassOrInterfaceDeclaration();

                    className = clazz.getFullyQualifiedName()
                            .orElse(clazz.getNameAsString());
                    
                    if(className.equals("com.adventnet.sym.adsm.common.webclient.api.ADMPAPIAction") || className.contains("ADMPAPIAction")) {
                        for (MethodDeclaration md : clazz.findAll(MethodDeclaration.class)) {
                            
                            if (md.getNameAsString().equals("execute")) {   
                                local.AdsAPIMethodDeclarion.add(md);
                            }
                        }
                    }
                    
                }

            }

            // Boundary resolvers — synchronized because they mutate shared instance state.
            synchronized (resolversLock) {
                for (BoundaryResolver r : resolvers) {
                    try { r.visit(cu, path, local); }
                    catch (Throwable t) {
                        System.err.println("[resolver-error] " + r.getClass().getSimpleName()
                            + " on " + path + " : " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[extract-error] " + path + " : " + t.getMessage());
            audit("EXTRACT_ERROR", path + " : " + t.getMessage());
        }
    }

    /** Recursive type walker for Pass 1. Emits decls + collects body sites. */
    private void walkTypeForPass1(TypeDeclaration<?> td, String pkg, String filePath,
                                  ExtractionBatch local, FileImports imports) {
        String simple = td.getNameAsString();
        String fqn = td.getFullyQualifiedName().isPresent() ? td.getFullyQualifiedName().get() : pkg+"."+simple;

        boolean isInterface = false;
        boolean isAbstract = false;
        List<String> extraLabels = new ArrayList<>();

        if (td instanceof ClassOrInterfaceDeclaration coid) {
            isInterface = coid.isInterface();
            isAbstract = coid.isAbstract();
            // EXTENDS edges (parent FQN via the SymbolSolver, imports as fallback)
            for (ClassOrInterfaceType ext : coid.getExtendedTypes()) {
                String parentFqn = resolveSuperTypeFqn(ext, imports);
                if (parentFqn != null && !parentFqn.isEmpty()) {
                    ExtendsEdge e = new ExtendsEdge(fqn, parentFqn);
                    local.extendsEdges.add(e);
                    index.recordExtends(e);
                }
            }
            // IMPLEMENTS edges
            for (ClassOrInterfaceType iface : coid.getImplementedTypes()) {
                String ifaceFqn = resolveSuperTypeFqn(iface, imports);
                if (ifaceFqn != null && !ifaceFqn.isEmpty()) {
                    ImplementsEdge e = new ImplementsEdge(fqn, ifaceFqn);
                    local.implementsEdges.add(e);
                    index.recordImplements(e);
                }
                String sn = iface.getNameAsString();

            }

        } else if (td instanceof EnumDeclaration ed) {
            // Enums can implement interfaces — capture those for OVERRIDES propagation.
            for (ClassOrInterfaceType iface : ed.getImplementedTypes()) {
                String ifaceFqn = resolveTypeText(iface, imports);
                if (ifaceFqn != null && !ifaceFqn.isEmpty()) {
                    ImplementsEdge e = new ImplementsEdge(fqn, ifaceFqn);
                    local.implementsEdges.add(e);
                    index.recordImplements(e);
                }
            }
        } else if (td instanceof RecordDeclaration rd) {
            // Records can implement interfaces too.
            for (ClassOrInterfaceType iface : rd.getImplementedTypes()) {
                String ifaceFqn = resolveTypeText(iface, imports);
                if (ifaceFqn != null && !ifaceFqn.isEmpty()) {
                    ImplementsEdge e = new ImplementsEdge(fqn, ifaceFqn);
                    local.implementsEdges.add(e);
                    index.recordImplements(e);
                }
            }
        }

        ClassNode classNode = new ClassNode(
            fqn, simple, pkg, filePath, isInterface, isAbstract,
            td.getBegin().map(p -> p.line).orElse(0),
            td.getEnd().map(p -> p.line).orElse(0),
            extraLabels
        );
        local.classes.add(classNode);
        local.classToFile.add(new ClassFileEdge(fqn, filePath));
        index.recordClass(classNode);

        // Fields. For `static final` fields with a literal initializer (Long / Integer /
        // String / Boolean), capture the constant value so the post-ingest
        // ConstantIndex cleanup (task #98) can reverse-map numeric ids like 1914 to
        // symbolic names like WORKFLOW_REJECT. Other fields get an empty constantValue.
        for (FieldDeclaration fd : td.getFields()) {
            boolean isConstantDecl = fd.isStatic() && fd.isFinal();
            String typeText = fd.getElementType().asString();
            for (VariableDeclarator v : fd.getVariables()) {
                String fname = v.getNameAsString();
                String ffqn = fqn + "." + fname;
                String constantValue = "";
                if (isConstantDecl) {
                    constantValue = v.getInitializer().map(CoreExtractor::extractLiteralValue).orElse("");
                }
                FieldNode fn = new FieldNode(ffqn, fname, fqn, typeText, constantValue);
                local.fields.add(fn);
                index.recordField(fn);
            }
        }

        // Methods + constructors + nested types
        for (BodyDeclaration<?> bd : td.getMembers()) {
            if (bd instanceof MethodDeclaration md) {
                walkMethodForPass1(md, fqn, filePath, local, imports);
            } else if (bd instanceof ConstructorDeclaration cd) {
                walkConstructorForPass1(cd, fqn, simple, filePath, local, imports);
            } else if (bd instanceof ClassOrInterfaceDeclaration nested) {
                // Nested type: walk recursively with the OUTER class's FQN as its package prefix.
                String nestedPkg = pkg.isEmpty() ? simple : pkg + "." + simple;
                // Pass through — recursion handles its own FQN building.
                walkTypeForPass1(nested, nestedPkg, filePath, local, imports);
            }
        }
    }

    private void walkMethodForPass1(MethodDeclaration md, String ownerFqn, String filePath,
                                    ExtractionBatch local, FileImports imports) {
        String name = md.getNameAsString();
        int paramCount = md.getParameters().size();
        // Method FQN: owner.name(paramText1,paramText2) — using IMPORT-resolved param FQNs
        // so this matches what BodyCollector emits for callers, ensuring CALL edges unify.
        String paramFqn = buildImportResolvedParamTypes(md, imports);
        String sig = name + "(" + paramFqn + ")";
        String fqn = ownerFqn + "." + sig;
        String ret = md.getType().asString();

        List<String> extras = new ArrayList<>();
        // Servlet entry-point conventions
        // if ("doGet".equals(name) || "doPost".equals(name) || "doPut".equals(name) || "doDelete".equals(name)) {
        //     extras.add("EntryPoint");
        // }
        // if ("executeTask".equals(name) || ("execute".equals(name) && paramCount == 0)) {
        //     extras.add("EntryPoint");
        // }
        // // ADMP/ADSM conventions — these methods are framework-called (CSV-import, lifecycle hooks, etc.)
        // String ownerSimple = simpleNameOf(ownerFqn);
        // if (isAdmpEntryClass(ownerSimple) && isAdmpEntryMethod(name)) {
        //     extras.add("EntryPoint");
        // }

        MethodNode mn = new MethodNode(fqn, sig, name, ownerFqn, ret,
            md.isStatic(), false,
            md.getBegin().map(p -> p.line).orElse(0),
            md.getEnd().map(p -> p.line).orElse(0),
            extras);
        local.methods.add(mn);
        index.recordMethod(mn, paramCount);

        // Thread run() detection — outside resolversLock so it doesn't serialize workers.
        // If this method is named run() with no parameters, and the owning class extends
        // Thread or implements Runnable, record it for ThreadStartResolver.afterAll().
        if ("run".equals(name) && paramCount == 0) {
            boolean found = false;
            for (var e : local.extendsEdges) {
                if (ownerFqn.equals(e.fromClassFqn()) && e.toClassFqn().endsWith("Thread")) {
                    found = true; break;
                }
            }
            if (!found) {
                for (var e : local.implementsEdges) {
                    if (ownerFqn.equals(e.fromClassFqn()) && e.toInterfaceFqn().endsWith("Runnable")) {
                        found = true; break;
                    }
                }
            }
            if (found) local.pendingRunMethods.add(new ExtractionBatch.PendingRunMethod(ownerFqn, fqn));
        }



        // Body site collection (no resolution yet).
        BodyCollector collector = new BodyCollector(filePath, imports, fqn, ownerFqn);
        collector.collectFromMethod(md);
        local.pendingCalls.addAll(collector.calls());
        local.pendingFieldAccesses.addAll(collector.fieldAccesses());
        // .start() call detection — outside resolversLock. Dedup by receiver class so
        // afterAll() iterates one entry per thread class, not one per call site.
        for (BodyCollector.CallSite cs : collector.calls()) {
            if ("start".equals(cs.simpleName()) && cs.arity() == 0 && cs.resolvedScopeFqn() != null) {
                local.pendingStartMethods.add(new ExtractionBatch.PendingStartMethod(cs.resolvedScopeFqn(),cs.fromMethodFqn()));
            }
        }
        // Per-method resolver hook — writes to thread-local local; no lock needed.
        for (BoundaryResolver r : resolvers) {
            r.visitMethod(md, fqn, ownerFqn, collector.calls(), local);
        }
    }

    private void walkConstructorForPass1(ConstructorDeclaration cd, String ownerFqn, String ownerSimple,
                                         String filePath, ExtractionBatch local, FileImports imports) {
        int paramCount = cd.getParameters().size();
        String paramFqn = buildImportResolvedCtorParamTypes(cd, imports);
        String sig = ownerSimple + "(" + paramFqn + ")";
        String fqn = ownerFqn + ".<init>(" + paramFqn + ")";

        MethodNode mn = new MethodNode(fqn, sig, "<init>", ownerFqn, "void",
            false, true,
            cd.getBegin().map(p -> p.line).orElse(0),
            cd.getEnd().map(p -> p.line).orElse(0),
            List.of());
        local.methods.add(mn);
        index.recordMethod(mn, paramCount);

        BodyCollector collector = new BodyCollector(filePath, imports, fqn, ownerFqn);
        collector.collectFromConstructor(cd);
        local.pendingCalls.addAll(collector.calls());
        local.pendingFieldAccesses.addAll(collector.fieldAccesses());
        // .start() call detection — outside resolversLock. Dedup by receiver class so
        // afterAll() iterates one entry per thread class, not one per call site.
        for (BodyCollector.CallSite cs : collector.calls()) {
            if ("start".equals(cs.simpleName()) && cs.arity() == 0 && cs.resolvedScopeFqn() != null) {
                local.pendingStartMethods.add(new ExtractionBatch.PendingStartMethod(cs.resolvedScopeFqn(),cs.fromMethodFqn()+"."+cs.simpleName()+"()"));
            }
        }
    }

    // ─── Pass 2 ─────────────────────────────────────────────────────────

    private void runPass2(ExtractionBatch master) {
        CallResolver resolver = new CallResolver(index);
        if (callTsvPath != null) resolver.setCallTsvPath(callTsvPath);

        int nThreads = WORKER_THREAD_COUNT;
        ExecutorService exec = Executors.newFixedThreadPool(nThreads, r -> {
            Thread t = new Thread(r, "core-extractor-p2");
            t.setDaemon(true);
            return t;
        });

        // Partition pendingCalls across workers; emit edges into per-worker lists,
        // then merge into master at the end (avoids contention on master.calls).
        int total = master.pendingCalls.size();
        int chunkSize = Math.max(1, (total + nThreads - 1) / nThreads);
        List<List<CallEdge>> workerCallEdges = new ArrayList<>();
        List<Future<?>> futures = new ArrayList<>();
        for (int start = 0; start < total; start += chunkSize) {
            int from = start;
            int to = Math.min(total, start + chunkSize);
            List<CallEdge> workerOut = new ArrayList<>();
            workerCallEdges.add(workerOut);
            futures.add(exec.submit(() -> {
                for (int i = from; i < to; i++) {
                    BodyCollector.CallSite cs = master.pendingCalls.get(i);
                    List<CallEdge> edges = resolver.resolve(cs);
                    workerOut.addAll(edges);
                }
            }));
        }

        // Field accesses run on the same pool but in their own chunks.
        int faTotal = master.pendingFieldAccesses.size();
        int faChunk = Math.max(1, (faTotal + nThreads - 1) / nThreads);
        List<List<FieldAccessEdge>> workerFieldEdges = new ArrayList<>();
        for (int start = 0; start < faTotal; start += faChunk) {
            int from = start;
            int to = Math.min(faTotal, start + faChunk);
            List<FieldAccessEdge> workerOut = new ArrayList<>();
            workerFieldEdges.add(workerOut);
            futures.add(exec.submit(() -> {
                for (int i = from; i < to; i++) {
                    BodyCollector.FieldAccessSite fa = master.pendingFieldAccesses.get(i);
                    List<FieldAccessEdge> edges = resolver.resolveFieldAccess(fa);
                    workerOut.addAll(edges);
                }
            }));
        }

        exec.shutdown();
        for (Future<?> f : futures) {
            try { f.get(); } catch (Throwable t) { /* per-task errors logged inside resolver */ }
        }
        try { exec.awaitTermination(30, TimeUnit.MINUTES); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        // Merge into master.
        for (List<CallEdge> wlist : workerCallEdges) master.calls.addAll(wlist);
        for (List<FieldAccessEdge> wlist : workerFieldEdges) master.fieldAccess.addAll(wlist);
        // Drop the pending collections — they're consumed.
        master.pendingCalls.clear();
        master.pendingFieldAccesses.clear();

        // Resolver summary + shutdown.
        for (String line : resolver.summaryLines()) {
            System.out.println("[CallResolver] " + line);
            audit("RESOLVER_STRATEGY", line);
        }
        resolver.shutdown();
    }

    // ─── Pass 3 ─────────────────────────────────────────────────────────

    /**
     * Emit :OVERRIDES edges. Uses the {@link GlobalIndex#transitiveAncestors} memoized walker
     * and same-arity + same-simple-name matching (param-type strings already agree since both
     * sides use the same import-resolution rules in {@link #buildImportResolvedParamTypes}).
     */
    private static void emitOverrides(ExtractionBatch batch, GlobalIndex index) {
        Map<String, List<MethodNode>> byOwner = new HashMap<>();
        for (MethodNode m : batch.methods) {
            byOwner.computeIfAbsent(m.ownerFqn(), k -> new ArrayList<>()).add(m);
        }

        java.util.Set<String> emitted = new java.util.HashSet<>();
        for (MethodNode child : batch.methods) {
            if ("<init>".equals(child.simpleName())) continue;
            if (child.isStatic()) continue;
            Set<String> ancestors = index.transitiveAncestors(child.ownerFqn());
            String[] childSig = sigParts(child);
            for (String ancestor : ancestors) {
                List<MethodNode> parentMethods = byOwner.get(ancestor);
                if (parentMethods == null) continue;
                for (MethodNode pm : parentMethods) {
                    if (pm.isStatic()) continue;
                    if (!signaturesCompatible(childSig, sigParts(pm))) continue;
                    String key = child.fqn() + "->" + pm.fqn();
                    if (emitted.add(key)) {
                        batch.overrides.add(new OverridesEdge(child.fqn(), pm.fqn()));
                    }
                }
            }
        }
        System.out.printf("[CoreExtractor] emitted %d :OVERRIDES edges%n", batch.overrides.size());
    }

    private static String[] sigParts(MethodNode m) {
        String fqn = m.fqn();
        int paren = fqn.indexOf('(');
        if (paren < 0) return new String[]{ m.simpleName() == null ? "" : m.simpleName() };
        int closeParen = fqn.lastIndexOf(')');
        String paramStr = closeParen > paren ? fqn.substring(paren + 1, closeParen) : "";
        if (paramStr.isEmpty()) return new String[]{ m.simpleName() };
        String[] params = paramStr.split(",");
        String[] out = new String[params.length + 1];
        out[0] = m.simpleName();
        for (int i = 0; i < params.length; i++) out[i + 1] = params[i].trim();
        return out;
    }

    /**
     * Signature compatibility for OVERRIDES detection. Two signatures match if:
     * <ol>
     *   <li>Same simple method name (position 0).</li>
     *   <li>Same arity (parameter count).</li>
     *   <li>For each parameter position: either side is {@code ?} (unresolved wildcard),
     *       OR the FQNs are equal, OR their SIMPLE NAMES are equal.</li>
     * </ol>
     *
     * <p>The simple-name match handles the multi-wildcard-import case where the same
     * conceptual class is resolved to different FQNs per file (e.g.,
     * {@code com.foo.workflow.AdventNetResourceBundle} in one file vs
     * {@code com.foo.i18n.AdventNetResourceBundle} in another — both mean the i18n class
     * but our same-package fallback fired for one). Real-world false-positive risk is
     * minimal because Java code conventionally uses distinct simple names for distinct
     * concepts; for impact analysis, an extra edge is safer than a missed one.
     */
    private static boolean signaturesCompatible(String[] a, String[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        if (!a[0].equals(b[0])) return false;
        for (int i = 1; i < a.length; i++) {
            if ("?".equals(a[i]) || "?".equals(b[i])) continue;
            if (a[i].equals(b[i])) continue;
            // Simple-name match — handles cross-file wildcard-import variance.
            if (simpleNameOfType(a[i]).equals(simpleNameOfType(b[i]))) continue;
            return false;
        }
        return true;
    }

    /**
     * Extract the trailing simple name from a parameter-type FQN, ignoring generics.
     * E.g. {@code com.foo.HashMap<java.lang.String,java.lang.Long>} → {@code HashMap}.
     * Falls through to the whole string when no dot is present.
     */
    private static String simpleNameOfType(String fqn) {
        if (fqn == null || fqn.isEmpty()) return "";
        int lt = fqn.indexOf('<');
        String base = lt > 0 ? fqn.substring(0, lt) : fqn;
        int dot = base.lastIndexOf('.');
        return dot < 0 ? base : base.substring(dot + 1);
    }


    /**
     * Task #98 — extract a literal initializer value from a field declaration so the
     * ConstantIndex post-pass can reverse-map numeric edge ids to symbolic names.
     * Supports {@code LongLiteralExpr}, {@code IntegerLiteralExpr}, {@code StringLiteralExpr},
     * {@code BooleanLiteralExpr}. Trailing {@code L}/{@code l} suffix is stripped from longs
     * so {@code 1914L} stores as {@code "1914"} — same shape as resolver-emitted numeric
     * ids. Returns the empty string for non-literal initializers (compile-time constants
     * computed from other constants, runtime expressions, factory calls, etc. — these
     * can't be reverse-looked-up without expression evaluation, which is out of scope).
     */
    static String extractLiteralValue(com.github.javaparser.ast.expr.Expression init) {
        if (init instanceof com.github.javaparser.ast.expr.LongLiteralExpr lle) {
            String v = lle.getValue();
            if (v == null) return "";
            if (v.endsWith("L") || v.endsWith("l")) v = v.substring(0, v.length() - 1);
            return v.replace("_", "");
        }
        if (init instanceof com.github.javaparser.ast.expr.IntegerLiteralExpr ile) {
            String v = ile.getValue();
            return v == null ? "" : v.replace("_", "");
        }
        if (init instanceof com.github.javaparser.ast.expr.StringLiteralExpr sle) {
            return sle.getValue();
        }
        if (init instanceof com.github.javaparser.ast.expr.BooleanLiteralExpr ble) {
            return Boolean.toString(ble.getValue());
        }
        // Unary minus on a numeric literal: -1L, -42.
        if (init instanceof com.github.javaparser.ast.expr.UnaryExpr ue
            && ue.getOperator() == com.github.javaparser.ast.expr.UnaryExpr.Operator.MINUS) {
            String inner = extractLiteralValue(ue.getExpression());
            return inner.isEmpty() ? "" : "-" + inner;
        }
        return "";
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    /** Build the {@link FileImports} record once per file. Used by BodyCollector + Pass 1 owner-resolution. */
    private static FileImports buildImports(CompilationUnit cu, String pkg) {
        Map<String, String> simpleToFqn = new HashMap<>();
        List<String> wildcardPkgs = new ArrayList<>();
        Map<String, String> staticImports = new HashMap<>();
        for (ImportDeclaration imp : cu.getImports()) {
            String impName = imp.getNameAsString();
            if (imp.isAsterisk()) {
                if (imp.isStatic()) continue; // ignore static wildcards — too noisy
                wildcardPkgs.add(impName);
            } else {
                int dot = impName.lastIndexOf('.');
                String simple = dot < 0 ? impName : impName.substring(dot + 1);
                if (imp.isStatic()) {
                    staticImports.put(simple, dot < 0 ? impName : impName.substring(0, dot));
                } else {
                    simpleToFqn.put(simple, impName);
                }
            }
        }
        return new FileImports(Map.copyOf(simpleToFqn), List.copyOf(wildcardPkgs),
            Map.copyOf(staticImports), pkg == null ? "" : pkg);
    }

    /**
     * Resolve a {@link com.github.javaparser.ast.type.Type} or {@link ClassOrInterfaceType}
     * to an FQN using only imports + same-package + java.lang implicit. Returns the simple
     * name when nothing matches — so downstream FQN-based MERGE still creates SOME node.
     */
    private static String resolveTypeText(Type type, FileImports imports) {
        if (type == null) return null;
        return resolveTypeTextString(type.asString(), imports);
    }
    private static String resolveTypeText(ClassOrInterfaceType type, FileImports imports) {
        if (type == null) return null;
        // Prefer NameWithScope to catch already-qualified type references inline.
        String written = type.getNameWithScope();
        return resolveTypeTextString(written, imports);
    }

    /**
     * Resolve an {@code extends} / {@code implements} type reference to its FQN using
     * JavaParser's SymbolSolver, falling back to the import heuristic.
     *
     * <p>The heuristic in {@link #resolveTypeTextString} is guesswork that gets supertypes
     * wrong in three ways the solver gets right:
     * <ul>
     *   <li><b>Nested types.</b> {@code implements Outer.Inner} contains a dot, so the
     *       heuristic short-circuits on "already qualified" and emits the literal
     *       {@code Outer.Inner} instead of {@code com.pkg.Outer.Inner}.</li>
     *   <li><b>Unresolvable simple names.</b> When no import matches it returns the bare
     *       simple name, MERGEing a phantom {@code :Class {fqn:"Foo"}} node that unifies with
     *       nothing. The solver reaches jar types through the JarTypeSolver, so framework
     *       supertypes land on their real FQN.</li>
     *   <li><b>Inherited / wildcard-imported nested types</b>, which imports alone cannot
     *       disambiguate.</li>
     * </ul>
     *
     * <p>This matters more than a typical FQN cleanup because EXTENDS / IMPLEMENTS feed
     * {@link GlobalIndex#transitiveAncestors} — which CallResolver uses for unqualified and
     * inherited-field resolution, and {@link #emitOverrides} uses to find override parents.
     * A wrong supertype FQN silently truncates every one of those ancestor walks.
     *
     * <p>Catches {@link Throwable}, not just UnsolvedSymbolException: files above
     * {@code LARGE_FILE_THRESHOLD_BYTES} are parsed with no SymbolResolver attached (see
     * {@code JavaProjectParser.buildParserNoSymbols}), and {@code resolve()} then throws
     * {@link IllegalStateException} — which must degrade to the heuristic, not escape into
     * the per-file catch and discard the whole file.
     */
    private static String resolveSuperTypeFqn(ClassOrInterfaceType type, FileImports imports) {
        if (type == null) return null;
        try {
            ResolvedType rt = type.resolve();
            if (rt.isReferenceType()) {
                String fqn = rt.asReferenceType().getQualifiedName();
                if (fqn != null && !fqn.isEmpty()) return fqn;
            }
        } catch (Throwable t) {
            // No solver attached, missing jar, or unresolvable source — fall through.
        }
        return resolveTypeText(type, imports);
    }
    private static String resolveTypeTextString(String text, FileImports imports) {
        if (text == null) return null;
        int lt = text.indexOf('<');
        if (lt > 0) text = text.substring(0, lt);
        text = text.trim();
        while (text.endsWith("[]")) text = text.substring(0, text.length() - 2).trim();
        if (text.isEmpty()) return null;
        if (text.contains(".")) return text; // already qualified
        String fqn = imports.resolveSimpleName(text);
        if (fqn != null) return fqn;
        if (isJavaLangSimple(text)) return "java.lang." + text;
        return text;
    }

    /**
     * Build the parameter-types string for a method declaration, resolved via imports.
     * This produces FQNs that match what BodyCollector emits for callers, so CALL edges
     * unify cleanly in Neo4j (no fuzzy reconciliation needed).
     */
    private static String buildImportResolvedParamTypes(MethodDeclaration md, FileImports imports) {
        // StringBuilder sb = new StringBuilder();
        // for (int i = 0; i < md.getParameters().size(); i++) {
        //     if (i > 0) sb.append(',');
        //     String resolved = resolveTypeText(md.getParameter(i).getType(), imports);
        //     sb.append(resolved == null ? "?" : resolved);
        // }
        // return sb.toString();

        try {
            ResolvedMethodDeclaration r = md.resolve();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.getNumberOfParams(); i++) {
                if (i > 0) sb.append(',');
                try { sb.append(r.getParam(i).getType().describe()); }
                catch (Throwable t) { sb.append('?'); }
            }
            return sb.toString();
        } catch (Throwable t) {
            return rawParamTypes(md);
        }
    }

    private static String rawParamTypes(MethodDeclaration md) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < md.getParameters().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(md.getParameter(i).getType().asString());
        }
        return sb.toString();
    }

    private static String rawParamTypes(ConstructorDeclaration cd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cd.getParameters().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cd.getParameter(i).getType().asString());
        }
        return sb.toString();
    }

    private static String buildImportResolvedCtorParamTypes(ConstructorDeclaration cd, FileImports imports) {
        // StringBuilder sb = new StringBuilder();
        // for (int i = 0; i < cd.getParameters().size(); i++) {
        //     if (i > 0) sb.append(',');
        //     String resolved = resolveTypeText(cd.getParameter(i).getType(), imports);
        //     sb.append(resolved == null ? "?" : resolved);
        // }
        // return sb.toString();

         try {
            ResolvedConstructorDeclaration r = cd.resolve();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.getNumberOfParams(); i++) {
                if (i > 0) sb.append(',');
                try { sb.append(r.getParam(i).getType().describe()); }
                catch (Throwable t) { sb.append('?'); }
            }
            return sb.toString();
        } catch (Throwable t) {
            return rawParamTypes(cd);
        }
    }

    private static String simpleNameOf(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /**
     * Heuristic: is this class a likely framework-called entry-point owner?
     * SPMP/ADMP/ADSM conventions: {@code *Listener} (CSV-import + lifecycle hooks),
     * {@code *Action} (Struts-style), {@code *Flow} (form-flow controller),
     * {@code *Handler} (workflow request handlers), {@code *Job} / {@code *Task}.
     */
    private static boolean isAdmpEntryClass(String simple) {
        if (simple == null || simple.isEmpty()) return false;
        return simple.endsWith("Listener") || simple.endsWith("Action")
            || simple.endsWith("Flow") || simple.endsWith("Handler")
            || simple.endsWith("Job") || simple.endsWith("Task")
            || simple.endsWith("Servlet");
    }
    /** ADMP entry-point lifecycle method names. */
    private static boolean isAdmpEntryMethod(String name) {
        switch (name) {
            case "doAction": case "importCSVDetailsForAutomation":
            case "preMgmtActions": case "postMgmtActions":
            case "csvImport": case "handleRequest":
            case "execute": case "process": case "submit":
            case "run": case "start":
                return true;
            default:
                return false;
        }
    }

    private static boolean isJavaLangSimple(String name) {
        switch (name) {
            case "String": case "Object": case "Integer": case "Long": case "Boolean":
            case "Double": case "Float": case "Byte": case "Character": case "Short":
            case "Number": case "Math": case "System": case "Thread": case "Throwable":
            case "Exception": case "RuntimeException": case "Error": case "Class":
            case "Void": case "Iterable": case "Comparable": case "CharSequence":
            case "StringBuilder": case "StringBuffer":
                return true;
            default:
                return false;
        }
    }

    private static String sha1(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }
}
