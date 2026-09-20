package io.spmp.impact.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jReactiveDataAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jReactiveRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.data.neo4j.Neo4jRepositoriesAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.Map;

/**
 * P9 — Spring Boot main for the impact-cli web mode.
 *
 * <p>Boots the embedded Tomcat + Jackson + Spring Web stack, scoped to the
 * {@code io.spmp.impact.web} package so the picocli-driven CLI commands are
 * untouched. Started via the {@code web} subcommand:
 *
 * <pre>java -jar impact.jar web --port 8080 --neo4j http://localhost:7474 --user neo4j --pass &lt;pw&gt;</pre>
 *
 * <p>Connection details are pushed in as Spring properties so the REST controllers
 * can pull them from the environment without going back through picocli.
 */
@SpringBootApplication(exclude = {
    // We don't use Spring Data Neo4j — Neo4jWriter wraps the raw Java driver directly,
    // and the auto-config tries to bind to a URI/user/pass via Spring properties that
    // would conflict with the CLI-style flags we pass. Disable the whole chain.
    Neo4jAutoConfiguration.class,
    Neo4jDataAutoConfiguration.class,
    Neo4jRepositoriesAutoConfiguration.class,
    Neo4jReactiveDataAutoConfiguration.class,
    Neo4jReactiveRepositoriesAutoConfiguration.class,
})
public class WebApplication {

    /**
     * Start the web application. Returns the live {@link ConfigurableApplicationContext}
     * so callers can wait on it / shut it down.
     */
    public static ConfigurableApplicationContext start(int port,
                                                       String neo4jUri,
                                                       String neo4jUser,
                                                       String neo4jPass,
                                                       String jwtSecret) {
        // Windows JDK 17+ ships a WEPoll-based NIO selector that fails to initialize
        // its loopback pipe (UnixDomainSockets path), causing both Tomcat and Neo4j's
        // Netty driver to crash on startup. Force the classic WindowsSelectorProvider
        // to side-step the regression. Harmless on Linux/macOS (the property only
        // affects Windows-specific selector init).
        if (System.getProperty("os.name", "").toLowerCase().contains("win")
            && System.getProperty("java.nio.channels.spi.SelectorProvider") == null) {
            System.setProperty("java.nio.channels.spi.SelectorProvider",
                "sun.nio.ch.WindowsSelectorProvider");
        }

        Map<String, Object> props = new HashMap<>();
        props.put("server.port", port);
        props.put("impact.neo4j.uri", neo4jUri);
        props.put("impact.neo4j.user", neo4jUser);
        // Don't log the password — Spring's PropertySource won't echo it as long as the
        // key isn't displayed by default.
        props.put("impact.neo4j.pass", neo4jPass == null ? "" : neo4jPass);
        // P9.6: signing key for JWT (resolved by JwtService — see its javadoc).
        props.put("impact.jwt.secret", jwtSecret == null ? "" : jwtSecret);
        // Quieter Spring banner — the CLI prints its own startup line.
        props.put("spring.main.banner-mode", "off");

        // P9.8: route System.out / System.err through a tee that fans writes out to
        // both the original console AND any per-thread JobState bound by the
        // ingest/analyze worker. Install BEFORE Spring boots so its own startup logs
        // also flow through the same path.
        installLoggingTee();

        SpringApplication app = new SpringApplication(WebApplication.class);
        app.setDefaultProperties(props);
        return app.run();
    }

    /** Replace {@code System.out / System.err} once, idempotently. */
    private static volatile boolean teeInstalled = false;
    private static synchronized void installLoggingTee() {
        if (teeInstalled) return;
        java.io.PrintStream origOut = System.out;
        java.io.PrintStream origErr = System.err;
        System.setOut(new io.spmp.impact.web.jobs.TeePrintStream(origOut, "stdout"));
        System.setErr(new io.spmp.impact.web.jobs.TeePrintStream(origErr, "stderr"));
        teeInstalled = true;
    }
}
