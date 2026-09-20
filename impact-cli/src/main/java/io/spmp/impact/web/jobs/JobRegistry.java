package io.spmp.impact.web.jobs;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P9.8 — Process-wide registry of {@link JobState} keyed on job ID.
 *
 * <p>Single instance (Spring {@code @Service}). Stores every job from creation
 * through any terminal state until {@link #evictOld(Duration)} reaps it.
 *
 * <p>Job IDs are short UUIDs (8 hex chars) so they're URL-friendly. Collisions
 * are astronomically unlikely at our throughput (a few jobs/day), but we
 * regenerate on the off chance.
 */
@Service
public class JobRegistry {

    private final ConcurrentHashMap<String, JobState> jobs = new ConcurrentHashMap<>();

    /** Create + register a new job. */
    public JobState create(JobState.Kind kind, Map<String, Object> params) {
        String id;
        do { id = shortId(); } while (jobs.containsKey(id));
        JobState s = new JobState(id, kind, params);
        jobs.put(id, s);
        return s;
    }

    /** Fetch a job by ID (null if unknown / evicted). */
    public JobState get(String id) {
        return id == null ? null : jobs.get(id);
    }

    /** Snapshot of every job, recent-first. */
    public List<JobState> listRecent(int max) {
        List<JobState> out = new ArrayList<>(jobs.values());
        out.sort((a, b) -> b.createdAt.compareTo(a.createdAt));
        return out.size() <= max ? out : out.subList(0, max);
    }

    /** Drop completed jobs older than {@code maxAge} from memory. */
    public int evictOld(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        int removed = 0;
        for (var e : jobs.entrySet()) {
            JobState s = e.getValue();
            if (s.isTerminal() && s.finishedAt() != null && s.finishedAt().isBefore(cutoff)) {
                jobs.remove(e.getKey());
                removed++;
            }
        }
        return removed;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
