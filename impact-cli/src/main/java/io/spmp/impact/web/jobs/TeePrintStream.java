package io.spmp.impact.web.jobs;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * P9.8 — A {@link PrintStream} that fans every write out to (a) the original
 * {@code System.out/err} so logs still appear in the server's console, and (b) the
 * {@link JobState} currently bound to the calling thread via a {@link ThreadLocal}.
 *
 * <p>Installed once at server start by {@link io.spmp.impact.web.WebApplication}.
 * Job runner code calls {@link #bind(JobState, String)} before invoking the
 * ingest/analyze pipeline, then {@link #unbind()} in a finally block. Other
 * threads (Spring's request workers, Jetty's IO threads) see the no-op binding
 * and only their writes go to the console.
 *
 * <p>Buffers writes line-by-line so log listeners get one logical line per
 * {@code println()} call — partial writes are accumulated until a {@code \n}.
 */
public final class TeePrintStream extends PrintStream {

    private final PrintStream delegate;
    private final String streamName;        // "stdout" | "stderr"
    private final ThreadLocal<StringBuilder> linebuf = ThreadLocal.withInitial(StringBuilder::new);

    /** Thread-local pointer to the job whose log to append to. */
    private static final ThreadLocal<JobState> CURRENT_JOB = new ThreadLocal<>();

    public TeePrintStream(PrintStream delegate, String streamName) {
        super(new NullStream(), false, StandardCharsets.UTF_8);
        this.delegate = delegate;
        this.streamName = streamName;
    }

    /** Bind a job to the calling thread; subsequent writes from this thread tee to it. */
    public static void bind(JobState job) {
        CURRENT_JOB.set(job);
    }

    public static void unbind() {
        CURRENT_JOB.remove();
    }

    public static JobState current() { return CURRENT_JOB.get(); }

    @Override
    public void write(int b) {
        delegate.write(b);
        accumulate((char) b);
    }

    @Override
    public void write(byte[] buf, int off, int len) {
        delegate.write(buf, off, len);
        for (int i = 0; i < len; i++) accumulate((char) (buf[off + i] & 0xFF));
    }

    @Override
    public void flush() {
        delegate.flush();
        // Don't flush partial lines to the job buffer — println adds the trailing newline.
    }

    @Override
    public void close() {
        // Don't close the delegate — it's System.out/err, the JVM owns its lifecycle.
    }

    private void accumulate(char c) {
        JobState job = CURRENT_JOB.get();
        if (job == null) return;          // Not in a job context — console-only output.
        if (c == '\n') {
            StringBuilder sb = linebuf.get();
            // Strip a trailing \r so Windows line endings don't show up in the log
            int len = sb.length();
            if (len > 0 && sb.charAt(len - 1) == '\r') sb.setLength(len - 1);
            job.appendLog(streamName, sb.toString());
            sb.setLength(0);
        } else {
            linebuf.get().append(c);
        }
    }

    private static final class NullStream extends OutputStream {
        @Override public void write(int b) { /* swallow — real output goes through the override above */ }
    }
}
