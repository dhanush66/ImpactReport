package io.spmp.impact.cmd;

import io.spmp.impact.web.WebApplication;
import org.springframework.context.ConfigurableApplicationContext;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * P9 — {@code impact web} subcommand. Boots the Spring Boot REST server and blocks
 * until the JVM is killed. Connection settings come from the standard {@link Neo4jOptions}
 * mixin so the same URI/user/pass that work for the CLI also work for the server.
 *
 * <p>Example:
 * <pre>java -jar impact.jar web --port 8080 \
 *   --neo4j http://localhost:7474 --user neo4j --pass &lt;pw&gt;</pre>
 *
 * <p>Once started:
 * <ul>
 *   <li>{@code GET  /api/v1/health}     — server + Neo4j reachability</li>
 *   <li>{@code GET  /api/v1/repos}      — ingested repo snapshots</li>
 *   <li>{@code POST /api/v1/analyze}    — run an impact analysis (returns ImpactReport JSON)</li>
 * </ul>
 */
@Command(name = "web", description = "Start the Spring Boot REST server (P9).")
public class WebCmd implements Callable<Integer> {

    @Option(names = "--port", description = "HTTP port to bind (default 8080).")
    int port = 8080;

    @Option(names = "--jwt-secret",
        description = "JWT signing key. Falls back to env IMPACT_JWT_SECRET. "
                    + "If unset a random key is generated per-start (tokens won't survive restart).")
    String jwtSecret;

    @Mixin
    Neo4jOptions neo;

    @Override
    public Integer call() throws Exception {
        System.out.println("[web] starting Spring Boot REST server on port " + port);
        System.out.println("[web] Neo4j: " + neo.resolveUri() + "  (user=" + neo.resolveUser() + ")");
        String secret = jwtSecret != null && !jwtSecret.isBlank()
            ? jwtSecret
            : System.getenv("IMPACT_JWT_SECRET");
        ConfigurableApplicationContext ctx =
            WebApplication.start(port, neo.resolveUri(), neo.resolveUser(), neo.resolvePass(), secret);
        System.out.println("[web] ready — endpoints:");
        System.out.println("[web]   GET  http://localhost:" + port + "/api/v1/health");
        System.out.println("[web]   POST http://localhost:" + port + "/api/v1/auth/login");
        System.out.println("[web]   GET  http://localhost:" + port + "/api/v1/repos");
        System.out.println("[web]   POST http://localhost:" + port + "/api/v1/analyze");
        System.out.println("[web]   POST http://localhost:" + port + "/api/v1/ingest      (P9.8)");
        System.out.println("[web]   GET  http://localhost:" + port + "/api/v1/jobs[/{id}] (P9.8)");
        System.out.println("[web]   WS   ws://localhost:"   + port + "/ws/jobs/{id}       (P9.8)");
        // Block forever; user kills with Ctrl-C or sends SIGTERM
        ctx.registerShutdownHook();
        Thread.currentThread().join();
        return 0;
    }
}
