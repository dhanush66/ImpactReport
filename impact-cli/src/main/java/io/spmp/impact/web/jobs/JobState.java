package io.spmp.impact.web.jobs;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * P9.8 — Mutable state for one async job (ingest or analyze).
 *
 * <p>Holds the running status, a bounded ring buffer of the last {@link #MAX_LOG_LINES}
 * log lines (so a slow web client can still backfill on connect), and a list of
 * registered listeners that get notified on every new log line (the WebSocket
 * handlers).
 *
 * <p>Thread-safety: all mutators are synchronized; readers get a defensive copy.
 * Bounded buffer prevents OOM if no client ever connects to drain.
 */
public final class JobState {

    /** Hard ceiling on retained log lines. The ingest produces O(50) lines total for SPMP, so this is generous. */
    public static final int MAX_LOG_LINES = 2_000;

    public enum Kind  { INGEST, ANALYZE }
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    public final String id;
    public final Kind kind;
    public final Map<String, Object> params;     // snapshot of the request body
    public final Instant createdAt;

    private volatile Status status = Status.PENDING;
    private volatile Instant startedAt;
    private volatile Instant finishedAt;
    private volatile String  error;               // populated on FAILED
    private volatile Map<String, Object> result;  // small summary on SUCCEEDED

    // log ring buffer + monotonic seq so the WS can reconcile after reconnect
    private final Deque<LogLine> buffer = new ArrayDeque<>(MAX_LOG_LINES);
    private final AtomicLong seq = new AtomicLong();
    private final List<Consumer<LogLine>> listeners = new ArrayList<>();

    // Optional persistent log mirror. Opened lazily on first appendLog() so empty
    // jobs (created then discarded) don't leave empty files behind. Writes are best-
    // effort: any IOException is silently logged to stderr — never block the job
    // pipeline on disk problems. Lifetime = process; closed on terminal transition.
    private volatile BufferedWriter fileWriter;
    private final Path logFile;

    /** Where every job's full log is persisted. Resolved at JobState construction. */
    private static final Path LOG_DIR = Path.of("logs", "jobs");
    private static final DateTimeFormatter FILE_TS =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    public record LogLine(long seq, Instant at, String stream /* "stdout"|"stderr" */, String text) {}

    public JobState(String id, Kind kind, Map<String, Object> params) {
        this.id = id;
        this.kind = kind;
        this.params = params == null ? Map.of() : Map.copyOf(params);
        this.createdAt = Instant.now();
        // Job log file name uses the job's id + creation date so multiple runs of the
        // same job id (impossible in practice but defensive) don't collide.
        String day = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        this.logFile = LOG_DIR.resolve(day + "-" + (kind == null ? "job" : kind.name().toLowerCase()) + "-" + id + ".log");
    }

    public Status status()   { return status; }
    public Instant startedAt()  { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public String  error()      { return error; }
    public Map<String, Object> result() { return result; }

    public synchronized void markRunning() {
        this.status = Status.RUNNING;
        this.startedAt = Instant.now();
    }

    public synchronized void markSucceeded(Map<String, Object> result) {
        this.status = Status.SUCCEEDED;
        this.finishedAt = Instant.now();
        this.result = result == null ? Map.of() : result;
        closeFileWriter();
    }

    public synchronized void markFailed(String error) {
        this.status = Status.FAILED;
        this.finishedAt = Instant.now();
        this.error = error;
        closeFileWriter();
    }

    /** Append a log line — called from the {@code TeePrintStream} bound to this job. */
    public void appendLog(String stream, String text) {
        LogLine line = new LogLine(seq.incrementAndGet(), Instant.now(), stream, text);
        List<Consumer<LogLine>> snapshot;
        synchronized (this) {
            buffer.addLast(line);
            while (buffer.size() > MAX_LOG_LINES) buffer.removeFirst();
            snapshot = new ArrayList<>(listeners);
        }
        // Mirror to disk under logs/jobs/<id>.log. Best-effort: failures fall through
        // to stderr but never break the job.
        writeToFile(line);
        // Notify outside the lock so a slow WS listener doesn't block log production.
        for (Consumer<LogLine> l : snapshot) {
            try { l.accept(line); } catch (Throwable ignored) {}
        }
    }

    /** Lazy-open + write one line. Synchronized on the writer so concurrent appendLog
     *  calls from different threads (TeePrintStream stdout vs stderr) don't interleave. */
    private void writeToFile(LogLine line) {
        BufferedWriter w = this.fileWriter;
        if (w == null) {
            synchronized (this) {
                w = this.fileWriter;
                if (w == null && status != Status.SUCCEEDED && status != Status.FAILED) {
                    try {
                        if (logFile.getParent() != null) {
                            Files.createDirectories(logFile.getParent());
                        }
                        w = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                        // Header — useful when triaging an old log file by itself.
                        w.write("# Job " + id + " kind=" + (kind == null ? "?" : kind.name())
                            + " created=" + createdAt + " params=" + params);
                        w.newLine();
                        w.flush();
                        this.fileWriter = w;
                    } catch (IOException e) {
                        System.err.println("[JobState] failed to open log file " + logFile + ": " + e.getMessage());
                        return;
                    }
                }
            }
        }
        if (w == null) return; // open failed AND we're already terminal
        try {
            synchronized (w) {
                w.write(FILE_TS.format(line.at()) + " [" + line.stream() + "] " + line.text());
                if (!line.text().endsWith("\n")) w.newLine();
                w.flush();
            }
        } catch (IOException e) {
            System.err.println("[JobState] write to " + logFile + " failed: " + e.getMessage());
        }
    }

    /** Closed on terminal transition so the OS doesn't hold the file open forever. */
    private void closeFileWriter() {
        BufferedWriter w = this.fileWriter;
        if (w == null) return;
        try {
            synchronized (w) {
                w.write("# Job " + id + " " + status + " at " + finishedAt);
                w.newLine();
                w.flush();
                w.close();
            }
        } catch (IOException ignored) {
            // Closing failure is non-fatal — the file content was already flushed line-by-line.
        }
        this.fileWriter = null;
    }

    /** Absolute path of this job's persisted log file (for the report UI). */
    public Path logFilePath() {
        return logFile.toAbsolutePath();
    }

    /** Snapshot of the current buffer — used by the WS handler to replay on connect. */
    public synchronized List<LogLine> snapshotBuffer() {
        return new ArrayList<>(buffer);
    }

    /** Register a listener to receive future log lines. Returns a handle to unregister. */
    public synchronized Runnable subscribe(Consumer<LogLine> listener) {
        listeners.add(listener);
        return () -> { synchronized (this) { listeners.remove(listener); } };
    }

    /** True after a terminal state — useful for "should the WS stay open?" checks. */
    public boolean isTerminal() {
        return status == Status.SUCCEEDED || status == Status.FAILED || status == Status.CANCELLED;
    }
}
