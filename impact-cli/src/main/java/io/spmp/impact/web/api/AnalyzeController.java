package io.spmp.impact.web.api;

import io.spmp.impact.analyze.SliceExecutor;
import io.spmp.impact.diff.DeletedJavaSymbolScanner;
import io.spmp.impact.diff.HunkReconciler;
import io.spmp.impact.diff.HunkToSymbolResolver;
import io.spmp.impact.diff.JgitDiffSource;
import io.spmp.impact.diff.PatchFileDiffSource;
import io.spmp.impact.diff.PolyglotResolver;
import io.spmp.impact.extract.JavaProjectParser;
import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.model.DiffModels.ChangedSymbol;
import io.spmp.impact.model.DiffModels.FileChange;
import io.spmp.impact.model.ImpactReport;
import io.spmp.impact.model.ImpactReport.PolyglotChange;
import io.spmp.impact.web.ReportCache;
import io.spmp.impact.web.jobs.JobRegistry;
import io.spmp.impact.web.jobs.JobState;
import io.spmp.impact.web.jobs.TeePrintStream;
import org.eclipse.jgit.lib.ObjectId;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * P9.4 + P9.8 — Async {@code POST /api/v1/analyze}.
 *
 * <p>Returns {@code {jobId}} immediately; the SliceExecutor pipeline runs on a
 * small background thread pool. Stdout from the pipeline is teed into the
 * {@link JobState}'s log buffer via {@link TeePrintStream}, so the SPA can
 * live-tail progress at {@code /ws/jobs/{jobId}} just like ingest jobs.
 *
 * <p>On success, the produced {@link ImpactReport} is stashed in {@link ReportCache}
 * and {@code job.result} carries the {@code reportId}, summary stats, and
 * convenience URLs for the HTML / Markdown / JSON downloads.
 */
@RestController
@RequestMapping("/api/v1/analyze")
public class AnalyzeController {

    /** Default BFS depth when the client omits it. Set high enough that the
     *  traversal naturally exhausts the graph on real-world call chains —
     *  any cycle is bounded by Cypher's distinct-path semantics. */
    private static final int DEFAULT_DEPTH = 100;

    private final Neo4jWriter writer;
    private final ReportCache reportCache;
    private final JobRegistry jobs;

    /** Tiny pool — analyze is CPU-heavy; two concurrent runs is plenty for one Neo4j. */
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "impact-analyze-worker");
        t.setDaemon(true);
        return t;
    });

    public AnalyzeController(Neo4jWriter writer, ReportCache reportCache, JobRegistry jobs) {
        this.writer = writer;
        this.reportCache = reportCache;
        this.jobs = jobs;
    }

    public record AnalyzeRequest(
        String repoPath,
        String patchPath,
        String base,
        String head,
        String srcRoot,
        Integer depth,
        Boolean showCoverage
    ) {}

    @PostMapping
    public ResponseEntity<?> analyze(@RequestBody AnalyzeRequest req) {
        // ── Validate synchronously so a bad request never produces a stranded job ──
        if (req == null || req.repoPath() == null || req.repoPath().isEmpty()) {
            return ResponseEntity.badRequest().body(err("repoPath is required"));
        }
        Path repo = Path.of(req.repoPath());
        if (!Files.isDirectory(repo)) {
            return ResponseEntity.badRequest().body(err("repoPath not a directory: " + repo));
        }
        boolean patchMode = req.patchPath() != null && !req.patchPath().isEmpty();
        boolean gitMode   = req.base() != null && !req.base().isEmpty();
        if (patchMode == gitMode) {
            return ResponseEntity.badRequest()
                .body(err("Provide exactly one of patchPath or base (with optional head)"));
        }
        if (patchMode && !Files.isRegularFile(Path.of(req.patchPath()))) {
            return ResponseEntity.badRequest()
                .body(err("patchPath not a file: " + req.patchPath()));
        }

        // ── Register the job ──
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("repoPath",  req.repoPath());
        if (req.patchPath() != null) snapshot.put("patchPath", req.patchPath());
        if (req.base() != null)      snapshot.put("base",      req.base());
        if (req.head() != null)      snapshot.put("head",      req.head());
        snapshot.put("depth", req.depth() != null ? req.depth() : DEFAULT_DEPTH);
        snapshot.put("showCoverage", Boolean.TRUE.equals(req.showCoverage()));
        JobState job = jobs.create(JobState.Kind.ANALYZE, snapshot);

        // ── Hand off to a background thread ──
        pool.submit(() -> runJob(job, req, repo));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.id);
        body.put("status", job.status().name());
        body.put("createdAt", job.createdAt.toString());
        return ResponseEntity.accepted().body(body);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Worker logic — bound to the job's log buffer for the duration of the run
    // ──────────────────────────────────────────────────────────────────────

    private void runJob(JobState job, AnalyzeRequest req, Path repo) {
        TeePrintStream.bind(job);
        job.markRunning();
        try {
            Path src = req.srcRoot() != null && !req.srcRoot().isEmpty()
                ? Path.of(req.srcRoot())
                : resolveSourceRoot(repo);
            int depth = req.depth() != null ? req.depth() : DEFAULT_DEPTH;
            boolean showCoverage = Boolean.TRUE.equals(req.showCoverage());

            boolean patchMode = req.patchPath() != null && !req.patchPath().isEmpty();
            List<FileChange> javaChanges;
            List<FileChange> polyglotChanges;
            List<ChangedSymbol> deletedSymbols;
            Function<String, byte[]> reader;
            String diffSource;

            if (patchMode) {
                Path patch = Path.of(req.patchPath());
                List<FileChange> all = PatchFileDiffSource.readAll(patch);
                javaChanges     = new ArrayList<>();
                polyglotChanges = new ArrayList<>();
                for (FileChange fc : all) {
                    String p = fc.newPath() != null ? fc.newPath() : fc.oldPath();
                    if (p != null && p.endsWith(".java")) javaChanges.add(fc);
                    else polyglotChanges.add(fc);
                }
                deletedSymbols = DeletedJavaSymbolScanner.scan(patch);
                reader = path -> {
                    Path disk = repo.resolve(path);
                    if (!Files.isRegularFile(disk)) return null;
                    try { return Files.readAllBytes(disk); }
                    catch (IOException e) { return null; }
                };
                diffSource = "patch:" + patch.toAbsolutePath();
                runPipeline(job, repo, src, depth, showCoverage,
                    javaChanges, polyglotChanges, deletedSymbols, reader, diffSource, patch);
            } else {
                String head = req.head() != null && !req.head().isEmpty() ? req.head() : "HEAD";
                try (JgitDiffSource diff = new JgitDiffSource(repo)) {
                    javaChanges     = diff.diffJava(req.base(), head);
                    polyglotChanges = new ArrayList<>();
                    deletedSymbols  = new ArrayList<>();
                    ObjectId headTree = diff.resolveTree(head);
                    reader = path -> {
                        try { return diff.readBlob(headTree, path); }
                        catch (IOException e) { return null; }
                    };
                    diffSource = "git:" + req.base() + ".." + head;
                    runPipeline(job, repo, src, depth, showCoverage,
                        javaChanges, polyglotChanges, deletedSymbols, reader, diffSource, null);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            job.markFailed(t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage()));
        } finally {
            TeePrintStream.unbind();
        }
    }

    private void runPipeline(JobState job, Path repo, Path src, int depth, boolean showCoverage,
                             List<FileChange> javaChanges,
                             List<FileChange> polyglotChanges,
                             List<ChangedSymbol> deletedSymbols,
                             Function<String, byte[]> reader,
                             String diffSource, Path patchFile) throws IOException {
        // 1. Resolve hunks → ChangedSymbol list
        System.out.println("[analyze] resolving hunks → enclosing AST symbols …");
        JavaProjectParser parser = new JavaProjectParser(src);
        HunkToSymbolResolver resolver = new HunkToSymbolResolver(parser);
        List<ChangedSymbol> all = new ArrayList<>();
        int skippedNoBlob = 0, skippedNoSymbol = 0;
        for (FileChange fc : javaChanges) {
            if (fc.hunkRanges().isEmpty()) continue;
            byte[] bytes = reader.apply(fc.newPath());
            if (bytes == null) { skippedNoBlob++; continue; }
            // Reconcile patch hunk line numbers against actual workspace source.
            List<int[]> reconciledHunks = patchFile != null
                ? HunkReconciler.reconcile(patchFile, fc.newPath(), fc.hunkRanges(), bytes)
                : fc.hunkRanges();
            List<ChangedSymbol> symbols = resolver.resolve(fc.newPath(), bytes, reconciledHunks);
            if (symbols.isEmpty()) { skippedNoSymbol++; continue; }
            all.addAll(symbols);
        }
        if (deletedSymbols != null) all.addAll(deletedSymbols);

        // Deduplicate by (filePath, fqn, kind): two separate patch hunks landing inside the
        // same method each resolve to their own ChangedSymbol for that method
        // (HunkToSymbolResolver processes hunks independently) — merge them into one entry so
        // they don't show up as duplicate rows in the report's "Symbol Details" table.
        all = io.spmp.impact.model.DiffModels.mergeDuplicateSymbols(all);

        System.out.printf("[analyze] changed symbols: %d  (skipped %d no-blob, %d no-symbol)%n",
            all.size(), skippedNoBlob, skippedNoSymbol);

        if (all.isEmpty() && polyglotChanges.isEmpty()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("diffSource", diffSource);
            r.put("note", "patch contains no resolvable Java symbols or polyglot files");
            r.put("filesInDiff", javaChanges.size() + polyglotChanges.size());
            r.put("skippedNoBlob", skippedNoBlob);
            r.put("skippedNoSymbol", skippedNoSymbol);
            job.markSucceeded(r);
            return;
        }

        // 2. Slice executor
        System.out.printf("[analyze] running slices (depth=%d) …%n", depth);
        SliceExecutor exec = new SliceExecutor(writer, depth);
        List<PolyglotChange> polyResolved = new ArrayList<>();
        if (!polyglotChanges.isEmpty()) {
            PolyglotResolver polyResolver = new PolyglotResolver(writer, repo);
            for (FileChange fc : polyglotChanges) {
                try { polyResolved.add(polyResolver.resolve(fc)); }
                catch (Throwable ignore) { /* per-file failure shouldn't abort the run */ }
            }
        }
        ImpactReport report = exec.run(diffSource, repo.toAbsolutePath().toString(), all, polyResolved);

        // 3. Cache the report so HTML/MD/JSON downloads work
        String reportId = reportCache.store(report, showCoverage);
        System.out.println("[analyze] complete — reportId=" + reportId);

        // 4. Stash summary stats + download URLs on the job's result
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("reportId", reportId);
        result.put("htmlUrl",  "/api/v1/reports/" + reportId + "/html");
        result.put("changedSymbols", all.size());
        result.put("skippedNoBlob", skippedNoBlob);
        result.put("skippedNoSymbol", skippedNoSymbol);
        result.put("showCoverage", showCoverage);
        // Promote the headline numbers from ImpactReport for JobDetail to render without re-fetching.
        result.put("overallRisk", report.overallRisk());
        result.put("totalChangedSymbols", report.totalChangedSymbols());
        result.put("apisAffected", report.apisAffected() == null ? 0 : report.apisAffected().size());
        result.put("dbTablesAffected", report.dbTablesAffected() == null ? 0 : report.dbTablesAffected().size());
        result.put("schedulesAffected", report.schedulesAffected() == null ? 0 : report.schedulesAffected().size());
        job.markSucceeded(result);
    }

    private static Path resolveSourceRoot(Path repo) {
        // Check common Java source root conventions in priority order.
        // source/java_source — ADSM/ADMP product layout
        // source/java        — generic ManageEngine product layout
        // src/main/java      — Maven standard layout
        for (String cand : new String[]{"source/java_source", "source/java", "src/main/java"}) {
            Path p = repo.resolve(cand);
            if (Files.isDirectory(p)) return p;
        }
        return repo;
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }
}
