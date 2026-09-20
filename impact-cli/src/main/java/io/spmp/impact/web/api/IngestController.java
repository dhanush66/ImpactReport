package io.spmp.impact.web.api;

import io.spmp.impact.cmd.IngestCmd;
import io.spmp.impact.cmd.IngestCmd.IngestParams;
import io.spmp.impact.web.jobs.JobRegistry;
import io.spmp.impact.web.jobs.JobState;
import io.spmp.impact.web.jobs.TeePrintStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * P9.8 — Async ingest endpoint + job listing.
 *
 * <ul>
 *   <li>{@code POST /api/v1/ingest} — start an ingest; returns {@code {jobId}}
 *       immediately. Body mirrors the core CLI flags (src, commit, repoId).
 *       Polyglot-root overrides, dependent-repo, incremental, and
 *       unified-symbol-solver flags are CLI-only (see {@code impact ingest --help});
 *       the web pipeline always runs with {@link IngestParams}'s defaults
 *       (auto-detected roots, single repo, full re-parse, per-repo solver).</li>
 *   <li>{@code GET  /api/v1/jobs}      — list recent jobs.</li>
 *   <li>{@code GET  /api/v1/jobs/{id}} — detail + last N log lines + status.</li>
 * </ul>
 *
 * <p>The actual extraction runs on a small fixed thread pool so concurrent
 * ingest requests serialize gracefully. {@link TeePrintStream} routes the
 * pipeline's {@code System.out.println} calls into the job's log buffer for
 * live tailing via WebSocket.
 */
@RestController
@RequestMapping("/api/v1")
public class IngestController {

    private final JobRegistry jobs;
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "impact-ingest-worker");
        t.setDaemon(true);
        return t;
    });

    @Value("${impact.neo4j.uri}") String neo4jUri;
    @Value("${impact.neo4j.user}") String neo4jUser;
    @Value("${impact.neo4j.pass}") String neo4jPass;

    public IngestController(JobRegistry jobs) { this.jobs = jobs; }

    /** Request body for {@code POST /api/v1/ingest}. */
    public record IngestRequest(
        String srcRoot,
        String commit,
        String repoId
    ) {}

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody IngestRequest req) {
        // ── Validate request ──
        if (req == null || req.srcRoot() == null || req.srcRoot().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "srcRoot is required"));
        }
        if (req.repoId() == null || req.repoId().isBlank()) {
            // The pipeline used to default repoId from the source dir name, but that
            // gave surprising results (e.g. "java_source") and tripped a downstream NPE
            // when the path didn't end in a clean segment. Force the caller to choose.
            return ResponseEntity.badRequest()
                .body(Map.of("error", "repoId is required — pick a short stable identifier (e.g. 'adsm', 'spmp')"));
        }
        Path src = Path.of(req.srcRoot());
        if (!Files.isDirectory(src)) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "srcRoot not a directory: " + src));
        }

        // ── Build params bag ──
        // Polyglot-root overrides, dependent repos, incremental, and unified-symbol-solver
        // are CLI-only; IngestParams' defaults (null/false) trigger the pipeline's own
        // auto-detection / single-repo / full-reparse / per-repo-solver behavior.
        IngestParams p = new IngestParams();
        p.src       = src;
        p.commit    = req.commit();
        p.repoId    = req.repoId();
        p.neo4jUri  = neo4jUri;
        p.neo4jUser = neo4jUser;
        p.neo4jPass = neo4jPass;

        // ── Register the job + spawn the worker ──
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("srcRoot", req.srcRoot());
        snapshot.put("repoId",  req.repoId());
        snapshot.put("commit",  req.commit());
        JobState job = jobs.create(JobState.Kind.INGEST, snapshot);

        pool.submit(() -> runJob(job, p));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", job.id);
        body.put("status", job.status().name());
        body.put("createdAt", job.createdAt.toString());
        return ResponseEntity.accepted().body(body);
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> list(@RequestParam(value = "max", defaultValue = "50") int max) {
        return jobs.listRecent(max).stream().map(IngestController::summary).toList();
    }

    @GetMapping("/jobs/{id}")
    public ResponseEntity<?> detail(@PathVariable("id") String id,
                                    @RequestParam(value = "tail", defaultValue = "200") int tail) {
        JobState job = jobs.get(id);
        if (job == null) return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "no such job", "id", id));
        Map<String, Object> m = summary(job);
        List<JobState.LogLine> all = job.snapshotBuffer();
        int from = Math.max(0, all.size() - Math.max(1, tail));
        m.put("log", all.subList(from, all.size()).stream().map(IngestController::logToMap).toList());
        return ResponseEntity.ok(m);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Worker logic
    // ──────────────────────────────────────────────────────────────────────

    private void runJob(JobState job, IngestParams params) {
        TeePrintStream.bind(job);    // route this thread's System.out into the job log
        job.markRunning();
        try {
            int exit = IngestCmd.runProgrammatically(params);
            if (exit == 0) {
                job.markSucceeded(Map.of("exitCode", 0));
            } else {
                job.markFailed("ingest exited with code " + exit);
            }
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage());
            // Print the full trace into the job log too, so the WS client sees it.
            t.printStackTrace(System.err);
            job.markFailed(msg);
        } finally {
            TeePrintStream.unbind();
        }
    }

    private static Map<String, Object> summary(JobState job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.id);
        m.put("kind", job.kind.name());
        m.put("status", job.status().name());
        m.put("createdAt", job.createdAt.toString());
        if (job.startedAt() != null)  m.put("startedAt",  job.startedAt().toString());
        if (job.finishedAt() != null) m.put("finishedAt", job.finishedAt().toString());
        if (job.error() != null)      m.put("error", job.error());
        if (job.result() != null && !job.result().isEmpty()) m.put("result", job.result());
        if (job.params != null && !job.params.isEmpty()) m.put("params", job.params);
        return m;
    }

    private static Map<String, Object> logToMap(JobState.LogLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", line.seq());
        m.put("at",  line.at().toString());
        m.put("stream", line.stream());
        m.put("text", line.text());
        return m;
    }
}
