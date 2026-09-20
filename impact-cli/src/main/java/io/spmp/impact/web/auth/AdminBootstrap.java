package io.spmp.impact.web.auth;

import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.Schema;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P9.6 — On server start, if zero {@code :AppUser} nodes exist, seed an
 * {@code admin/<random>} user and print the password to stdout exactly once.
 * Aligned with the same one-shot-secret pattern used by the JWT signing key.
 *
 * <p>This is the chicken-and-egg solution for first-time deployments: someone
 * has to be the first admin, and they shouldn't have to run a separate
 * setup script.
 */
@Component
public class AdminBootstrap {

    private static final String ADMIN_USERNAME = "admin";
    /** Default seed password for the bootstrap admin. Intentionally simple for dev/demo —
     *  document loudly so prod deployments override it. */
    private static final String DEFAULT_ADMIN_PASSWORD = "admin";

    private final AppUserService users;
    private final Neo4jWriter writer;

    public AdminBootstrap(AppUserService users, Neo4jWriter writer) {
        this.users = users;
        this.writer = writer;
    }

    @PostConstruct
    public void seedIfEmpty() {
        // Make sure the AppUser uniqueness constraint exists — Schema.bootstrap is
        // idempotent so running it on every server start is safe even if some other
        // process (CLI ingest) has already done it.
        try { Schema.bootstrap(writer); } catch (Throwable t) {
            System.err.println("[admin-bootstrap] WARN: schema bootstrap failed: " + t.getMessage());
        }

        if (users.anyExists()) return;

        try {
            users.create(ADMIN_USERNAME, DEFAULT_ADMIN_PASSWORD, List.of("ADMIN"));
            System.out.println();
            System.out.println("════════════════════════════════════════════════════════════════════════");
            System.out.println(" [admin-bootstrap] no :AppUser nodes found — seeded a default admin.");
            System.out.println("    username: " + ADMIN_USERNAME);
            System.out.println("    password: " + DEFAULT_ADMIN_PASSWORD);
            System.out.println();
            System.out.println("  ⚠  This is a development-grade default. For production, immediately:");
            System.out.println("       impact users delete admin");
            System.out.println("       impact users create --username admin --role ADMIN --password <strong>");
            System.out.println("════════════════════════════════════════════════════════════════════════");
            System.out.println();
        } catch (Throwable t) {
            System.err.println("[admin-bootstrap] failed to seed admin user: " + t.getMessage());
        }
    }
}
