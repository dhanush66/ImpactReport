package io.spmp.impact.web;

import io.spmp.impact.model.ImpactReport;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P10 — In-memory cache of {@link ImpactReport}s produced by recent
 * {@code POST /api/v1/analyze} calls. Keyed on a short UUID returned to the
 * client as {@code reportId}; the client uses it to download the HTML
 * (or re-fetch the JSON) without re-running the analysis.
 *
 * <p>TTL: 30 min. Reports are evicted on every {@link #store} call so the
 * cache never grows beyond active reports. Memory cost is the size of the
 * ImpactReport record — typically &lt;5 MB even on large patches.
 */
@Service
public class ReportCache {

    private static final Duration TTL = Duration.ofMinutes(30);

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    /** Store a freshly computed report. Returns the short ID to hand back to the client. */
    public String store(ImpactReport report, boolean showCoverage) {
        evictExpired();
        String id = UUID.randomUUID().toString().substring(0, 12);
        entries.put(id, new Entry(report, showCoverage, Instant.now()));
        return id;
    }

    public Entry get(String id) {
        if (id == null) return null;
        Entry e = entries.get(id);
        if (e == null) return null;
        if (Duration.between(e.createdAt, Instant.now()).compareTo(TTL) > 0) {
            entries.remove(id);
            return null;
        }
        return e;
    }

    private void evictExpired() {
        Instant now = Instant.now();
        List<String> toDrop = new ArrayList<>();
        for (var e : entries.entrySet()) {
            if (Duration.between(e.getValue().createdAt, now).compareTo(TTL) > 0) {
                toDrop.add(e.getKey());
            }
        }
        toDrop.forEach(entries::remove);
    }

    public record Entry(ImpactReport report, boolean showCoverage, Instant createdAt) {}
}
