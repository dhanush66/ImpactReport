package io.spmp.impact.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * P9.6 — JWT Bearer auth on every {@code /api/v1/*} request, with two exemptions:
 * <ul>
 *   <li>{@code GET /api/v1/health}   — loadbalancer probes, must work without creds.</li>
 *   <li>{@code POST /api/v1/auth/login} — the login endpoint itself.</li>
 * </ul>
 *
 * <p>On success: attaches the resolved {@link AppUser} as the request attribute
 * {@code impact.principal} so controllers can {@code @RequestAttribute} it.
 *
 * <p>On failure: returns {@code 401 Unauthorized} with a JSON body that names
 * which check failed (missing-token / expired-token / bad-signature / no-such-user).
 *
 * <p>Role-based authz is delegated to the controllers — this filter only verifies
 * identity. Some endpoints (e.g. {@code POST /api/v1/cypher} in the future) will
 * additionally enforce {@code user.hasRole("ADMIN")}.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    /** Paths that bypass authentication. Must be kept tight. */
    private static final Set<String> ALWAYS_PUBLIC = Set.of(
        "/api/v1/health",
        "/api/v1/auth/login"
    );

    private final JwtService jwt;
    private final AppUserService users;
    private final ObjectMapper json = new ObjectMapper();

    public JwtAuthFilter(JwtService jwt, AppUserService users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();

        // Anonymous routes — let them through.
        if (isPublic(path)) {
            chain.doFilter(req, resp);
            return;
        }
        // Only enforce auth on our /api/v1/* surface. Anything else (static files,
        // health, custom resources) goes straight through.
        if (!path.startsWith("/api/v1/")) {
            chain.doFilter(req, resp);
            return;
        }

        String token = JwtService.extractBearer(req.getHeader("Authorization"));
        if (token == null) {
            deny(resp, "missing-token", "Authorization header missing or not Bearer-formatted");
            return;
        }
        Claims claims;
        try {
            claims = jwt.parse(token);
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            deny(resp, "expired-token", "token expired at " + e.getClaims().getExpiration());
            return;
        } catch (JwtException e) {
            deny(resp, "bad-token", e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        String username = claims.getSubject();
        if (username == null || username.isBlank()) {
            deny(resp, "bad-token", "token missing subject claim");
            return;
        }
        var userOpt = users.findByUsername(username);
        if (userOpt.isEmpty()) {
            // Token is valid signature-wise but the user was deleted — same handling.
            deny(resp, "no-such-user", "user '" + username + "' no longer exists");
            return;
        }
        AppUser user = userOpt.get();
        // Tokens are bound to the roles at issue-time, but we re-snapshot from the
        // live user node so revoking a role takes effect on next request.
        req.setAttribute("impact.principal", user);
        req.setAttribute("impact.principal.roles", user.roles());
        chain.doFilter(req, resp);
    }

    private boolean isPublic(String path) {
        if (ALWAYS_PUBLIC.contains(path)) return true;
        // Allow OPTIONS preflight from the SPA without a token
        return path.startsWith("/api/v1/health");
    }

    private void deny(HttpServletResponse resp, String code, String message) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "unauthorized");
        body.put("code", code);
        body.put("message", message);
        body.put("at", Instant.now().toString());
        json.writeValue(resp.getOutputStream(), body);
    }

    /** Static helper for controllers needing the principal without DI noise. */
    public static AppUser principal(HttpServletRequest req) {
        Object p = req.getAttribute("impact.principal");
        return p instanceof AppUser u ? u : null;
    }

    /** Static helper: does the current request have any of these roles? */
    @SuppressWarnings("unchecked")
    public static boolean hasAnyRole(HttpServletRequest req, String... roles) {
        Object rs = req.getAttribute("impact.principal.roles");
        if (!(rs instanceof List<?> list)) return false;
        for (String want : roles) if (list.contains(want)) return true;
        return false;
    }
}
