package io.spmp.impact.web.auth;

import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.txn.CypherClient.CRecord;
import io.spmp.impact.graph.txn.CypherClient.CResult;
import io.spmp.impact.graph.txn.CypherClient.CValue;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P9.6 — CRUD over {@code :AppUser} nodes in Neo4j. Used by both the CLI
 * {@code impact users …} subcommand and the web {@code AuthController}.
 *
 * <p>Password hashing uses BCrypt at cost factor 12 (~250ms per hash, ~250ms per
 * verify on a developer laptop — fast enough for a login flow, slow enough to
 * frustrate brute-forcers).
 */
@Service
public class AppUserService {

    /** Valid Neo4j-key-safe usernames: 3-64 chars, letters/digits/._- */
    private static final Pattern USERNAME_OK =
        Pattern.compile("^[A-Za-z0-9._-]{3,64}$");

    /** Three-role role model. Anything else is rejected by validateRoles. */
    public static final Set<String> VALID_ROLES = Set.of("VIEWER", "DEV", "ADMIN");

    private final Neo4jWriter writer;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    public AppUserService(Neo4jWriter writer) {
        this.writer = writer;
    }

    /** Hash a plain-text password — exposed for tests / bootstrap. */
    public String hash(String plain) {
        return encoder.encode(plain);
    }

    public boolean verify(String plain, String hash) {
        if (plain == null || hash == null) return false;
        try { return encoder.matches(plain, hash); }
        catch (Throwable t) { return false; }
    }

    /** Create a new user. Fails if the username already exists. */
    public AppUser create(String username, String plainPassword, List<String> roles) {
        validateUsername(username);
        validateRoles(roles);
        if (plainPassword == null || plainPassword.length() < 4) {
            throw new IllegalArgumentException("password must be at least 4 characters");
        }
        String hash = hash(plainPassword);
        Instant now = Instant.now();
        // MERGE-with-ON-CREATE-only so a re-run fails loudly instead of silently overwriting an existing password.
        try (CResult r = writer.session().run(
            "MERGE (u:AppUser {username: $u}) "
          + "ON CREATE SET u.passwordHash = $h, u.roles = $roles, u.createdAt = $now "
          + "ON MATCH  SET u._exists = true "
          + "RETURN u._exists IS NOT NULL AS existed",
            Map.of("u", username, "h", hash, "roles", roles, "now", now.toString()))) {
            boolean existed = r.hasNext() && r.next().get("existed").asBoolean(false);
            if (existed) {
                // clean up the bookkeeping flag and report a clear error
                writer.session().run("MATCH (u:AppUser {username: $u}) REMOVE u._exists",
                    Map.of("u", username));
                throw new IllegalStateException("user already exists: " + username);
            }
        }
        return new AppUser(username, hash, roles, now);
    }

    /** Look up by username. Empty if no such user. */
    public Optional<AppUser> findByUsername(String username) {
        if (username == null || username.isEmpty()) return Optional.empty();
        try (CResult r = writer.session().run(
            "MATCH (u:AppUser {username: $u}) "
          + "RETURN u.username AS username, u.passwordHash AS passwordHash, "
          + "       u.roles AS roles, u.createdAt AS createdAt",
            Map.of("u", username))) {
            if (!r.hasNext()) return Optional.empty();
            CRecord rec = r.next();
            return Optional.of(new AppUser(
                rec.get("username").asString(""),
                rec.get("passwordHash").asString(""),
                rec.get("roles").asList(CValue::asString),
                parseInstant(rec.get("createdAt").asString(""))
            ));
        }
    }

    /** List every user (usernames + roles + createdAt — no password hashes). */
    public List<AppUser> list() {
        List<AppUser> out = new ArrayList<>();
        try (CResult r = writer.session().run(
            "MATCH (u:AppUser) "
          + "RETURN u.username AS username, u.roles AS roles, u.createdAt AS createdAt "
          + "ORDER BY u.username")) {
            while (r.hasNext()) {
                CRecord rec = r.next();
                out.add(new AppUser(
                    rec.get("username").asString(""),
                    "",  // never expose hash via list()
                    rec.get("roles").asList(CValue::asString),
                    parseInstant(rec.get("createdAt").asString(""))));
            }
        }
        return out;
    }

    /** True if at least one user exists. Used at bootstrap to decide whether to seed admin. */
    public boolean anyExists() {
        try (CResult r = writer.session().run("MATCH (u:AppUser) RETURN count(u) AS n")) {
            return r.hasNext() && r.next().get("n").asInt(0) > 0;
        }
    }

    /**
     * Reset an existing user's password. Returns true if a user matched; false otherwise.
     * Useful for recovering access when the bootstrap password was lost or for rotating
     * admin credentials.
     */
    public boolean setPassword(String username, String newPlainPassword) {
        if (newPlainPassword == null || newPlainPassword.length() < 4) {
            throw new IllegalArgumentException("password must be at least 4 characters");
        }
        String hash = hash(newPlainPassword);
        try (CResult r = writer.session().run(
            "MATCH (u:AppUser {username: $u}) SET u.passwordHash = $h RETURN count(u) AS n",
            Map.of("u", username, "h", hash))) {
            return r.hasNext() && r.next().get("n").asInt(0) > 0;
        }
    }

    public boolean delete(String username) {
        try (CResult r = writer.session().run(
            "MATCH (u:AppUser {username: $u}) DETACH DELETE u RETURN count(u) AS n",
            Map.of("u", username))) {
            return r.hasNext() && r.next().get("n").asInt(0) > 0;
        }
    }

    private static void validateUsername(String u) {
        if (u == null || !USERNAME_OK.matcher(u).matches()) {
            throw new IllegalArgumentException(
                "username must be 3-64 chars: letters / digits / . _ -  (got '" + u + "')");
        }
    }
    private static void validateRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new IllegalArgumentException("at least one role is required (VIEWER / DEV / ADMIN)");
        }
        for (String r : roles) {
            if (!VALID_ROLES.contains(r)) {
                throw new IllegalArgumentException(
                    "invalid role '" + r + "' — valid: " + VALID_ROLES);
            }
        }
    }
    private static Instant parseInstant(String s) {
        if (s == null || s.isEmpty()) return Instant.EPOCH;
        try { return Instant.parse(s); }
        catch (Throwable t) { return Instant.EPOCH; }
    }
}
