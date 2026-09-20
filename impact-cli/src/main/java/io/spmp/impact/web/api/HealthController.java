package io.spmp.impact.web.api;

import io.spmp.impact.graph.Neo4jWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P9 — {@code GET /api/v1/health}. Reports server liveness + whether the configured
 * Neo4j is reachable. Cheap enough for a load-balancer probe.
 *
 * <p>P9.6 — exempt from JWT auth (see {@code JwtAuthFilter}). Returning 200 here
 * is the standard liveness signal.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @Value("${impact.neo4j.uri}") String neo4jUri;
    private final Neo4jWriter writer;

    public HealthController(Neo4jWriter writer) { this.writer = writer; }

    @GetMapping
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "UP");
        out.put("version", "0.1.0");
        out.put("neo4jUri", neo4jUri);

        boolean ok = false;
        String error = null;
        try (var res = writer.session().run("RETURN 1 AS one")) {
            ok = res.hasNext() && res.next().get("one").asInt(0) == 1;
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        out.put("neo4jReachable", ok);
        if (!ok) out.put("neo4jError", error);
        return out;
    }
}
