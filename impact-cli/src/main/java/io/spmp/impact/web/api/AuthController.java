package io.spmp.impact.web.api;

import io.spmp.impact.web.auth.AppUser;
import io.spmp.impact.web.auth.AppUserService;
import io.spmp.impact.web.auth.JwtService;
import io.spmp.impact.web.auth.JwtService.IssuedToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * P9.6 — Login + whoami.
 *
 * <ul>
 *   <li>{@code POST /api/v1/auth/login} — body {@code {username, password}} →
 *       {@code {token, expiresAt, username, roles}} on success, 401 on bad creds.</li>
 *   <li>{@code GET  /api/v1/auth/whoami} — requires a valid Bearer token, returns
 *       the principal's username + roles. Useful for the UI to validate a stored
 *       token without making a heavier API call.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AppUserService users;
    private final JwtService jwt;

    public AuthController(AppUserService users, JwtService jwt) {
        this.users = users;
        this.jwt = jwt;
    }

    public record LoginRequest(String username, String password) {}

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        if (req == null || req.username() == null || req.password() == null
            || req.username().isBlank() || req.password().isBlank()) {
            return resp(HttpStatus.BAD_REQUEST,
                Map.of("error", "username and password are required"));
        }
        Optional<AppUser> uo = users.findByUsername(req.username());
        if (uo.isEmpty() || !users.verify(req.password(), uo.get().passwordHash())) {
            // Same error for "no such user" and "wrong password" — don't leak which.
            return resp(HttpStatus.UNAUTHORIZED, Map.of("error", "invalid credentials"));
        }
        AppUser user = uo.get();
        IssuedToken t = jwt.issue(user);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", t.token());
        body.put("expiresAt", t.expiresAt().toString());
        body.put("username", user.username());
        body.put("roles", user.roles());
        return ResponseEntity.ok(body);
    }

    /** Echoes the authenticated user back. Useful for UI session validation. */
    @GetMapping("/whoami")
    public Map<String, Object> whoami(@org.springframework.web.bind.annotation.RequestAttribute(
            value = "impact.principal", required = false) AppUser principal) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (principal == null) {
            m.put("authenticated", false);
        } else {
            m.put("authenticated", true);
            m.put("username", principal.username());
            m.put("roles", principal.roles());
        }
        return m;
    }

    private static ResponseEntity<Map<String, Object>> resp(HttpStatus code, Map<String, Object> body) {
        return ResponseEntity.status(code).body(body);
    }
}
