package io.spmp.impact.web.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.spmp.impact.web.jobs.JobRegistry;
import io.spmp.impact.web.jobs.JobState;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P9.8 — Live log tail for one job, served at {@code /ws/jobs/{id}}.
 *
 * <p>On connect:
 * <ol>
 *   <li>Replays the existing log buffer (line-by-line, oldest first) so a client
 *       reconnecting mid-job picks up where it left off.</li>
 *   <li>Subscribes to the job's listener list so subsequent lines stream live.</li>
 *   <li>If the job is already terminal, sends a {@code status} frame and closes.</li>
 * </ol>
 *
 * <p>Frame format — each is a JSON object on a single line:
 * <pre>
 *   {"type":"log",    "seq":42, "stream":"stdout", "text":"...", "at":"..."}
 *   {"type":"status", "status":"SUCCEEDED", "error":null}
 * </pre>
 */
public class JobLogWebSocketHandler extends TextWebSocketHandler {

    private final JobRegistry jobs;
    private final ObjectMapper json = new ObjectMapper();
    /** Active subscriptions, keyed on Spring's session id. */
    private final ConcurrentHashMap<String, Runnable> unsubscribes = new ConcurrentHashMap<>();

    public JobLogWebSocketHandler(JobRegistry jobs) { this.jobs = jobs; }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String jobId = extractJobId(session);
        JobState job = jobs.get(jobId);
        if (job == null) {
            send(session, Map.of("type", "error", "message", "no such job: " + jobId));
            session.close(CloseStatus.NOT_ACCEPTABLE);
            return;
        }

        // 1) Replay existing buffer
        for (JobState.LogLine line : job.snapshotBuffer()) {
            send(session, lineFrame(line));
        }

        // 2) If terminal, send status and close — no live updates coming.
        if (job.isTerminal()) {
            send(session, statusFrame(job));
            session.close(CloseStatus.NORMAL);
            return;
        }

        // 3) Subscribe to live lines
        Runnable cancel = job.subscribe(line -> {
            try {
                if (session.isOpen()) send(session, lineFrame(line));
            } catch (IOException ignored) { /* client gone — they'll reconnect */ }
            // Whenever a line is appended after the job hits a terminal state, push the
            // final status frame too. (In practice the worker thread emits no logs after
            // markSucceeded/markFailed, but be safe.)
            if (job.isTerminal()) {
                try { send(session, statusFrame(job)); } catch (IOException ignored) {}
            }
        });
        unsubscribes.put(session.getId(), cancel);

        // 4) Watcher: poll the job's terminal flag once per second so we can deliver the
        //    final status frame even when no more log lines follow the markSucceeded call.
        new Thread(() -> {
            try {
                while (session.isOpen() && !job.isTerminal()) Thread.sleep(1000);
                if (session.isOpen()) {
                    send(session, statusFrame(job));
                    session.close(CloseStatus.NORMAL);
                }
            } catch (Throwable ignored) {}
        }, "jobs-ws-watch-" + jobId).start();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Runnable r = unsubscribes.remove(session.getId());
        if (r != null) r.run();
    }

    private static String extractJobId(WebSocketSession session) {
        // URI: /ws/jobs/{id}
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(slash + 1);
    }

    private void send(WebSocketSession s, Map<String, Object> frame) throws IOException {
        if (!s.isOpen()) return;
        s.sendMessage(new TextMessage(json.writeValueAsString(frame)));
    }

    private static Map<String, Object> lineFrame(JobState.LogLine line) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "log");
        m.put("seq", line.seq());
        m.put("at", line.at().toString());
        m.put("stream", line.stream());
        m.put("text", line.text());
        return m;
    }

    private static Map<String, Object> statusFrame(JobState job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "status");
        m.put("status", job.status().name());
        if (job.error() != null) m.put("error", job.error());
        if (job.finishedAt() != null) m.put("finishedAt", job.finishedAt().toString());
        return m;
    }
}
