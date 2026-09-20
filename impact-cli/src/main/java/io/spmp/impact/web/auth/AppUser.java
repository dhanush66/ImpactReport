package io.spmp.impact.web.auth;

import java.time.Instant;
import java.util.List;

/**
 * P9.6 — Web-app user record. Backed by a {@code :AppUser} node in Neo4j (keyed
 * on {@code username}). The password is stored as a BCrypt hash; the plain
 * password is never persisted.
 *
 * <p>Roles are stored as a list on the node (Neo4j list property). Three roles
 * recognized by the auth filter:
 * <ul>
 *   <li>{@code VIEWER} — read-only (GET /repos, GET /reports, GET /health)</li>
 *   <li>{@code DEV} — VIEWER + POST /analyze, POST /ingest</li>
 *   <li>{@code ADMIN} — DEV + POST /cypher, POST /testcases, user management</li>
 * </ul>
 */
public record AppUser(
    String username,
    String passwordHash,     // BCrypt
    List<String> roles,      // any of VIEWER / DEV / ADMIN
    Instant createdAt
) {
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
    /** Convenience: any of the requested roles satisfies the check. */
    public boolean hasAnyRole(String... anyOf) {
        if (roles == null || roles.isEmpty()) return false;
        for (String r : anyOf) if (roles.contains(r)) return true;
        return false;
    }
}
