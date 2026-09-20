package io.spmp.impact.web.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * P9.6 — Issues and verifies HS256 JWTs for the web app.
 *
 * <p><b>Signing key</b>: comes from {@code impact.jwt.secret} (set on server start
 * from the env var {@code IMPACT_JWT_SECRET} or from a flag). If unset, generates
 * a one-shot random 256-bit key — fine for dev, but tokens will not survive a
 * server restart. Production: pin {@code IMPACT_JWT_SECRET}.
 *
 * <p><b>Token TTL</b>: 8 hours by default. Bearer-token model — there is no
 * refresh; clients re-login when the token expires.
 */
@Service
public class JwtService {

    private static final String ISSUER = "impact-cli";
    private static final Duration TTL = Duration.ofHours(8);

    private final SecretKey signingKey;

    public JwtService(@Value("${impact.jwt.secret:}") String configuredSecret) {
        this.signingKey = resolveKey(configuredSecret);
    }

    /** Issue a token for the user. Stores username + roles in the claims. */
    public IssuedToken issue(AppUser user) {
        Instant now = Instant.now();
        Instant exp = now.plus(TTL);
        String token = Jwts.builder()
            .issuer(ISSUER)
            .subject(user.username())
            .claim("roles", user.roles())
            .issuedAt(java.util.Date.from(now))
            .expiration(java.util.Date.from(exp))
            .signWith(signingKey)
            .compact();
        return new IssuedToken(token, exp);
    }

    /** Verify + parse claims. Throws {@link JwtException} on invalid/expired tokens. */
    public Claims parse(String token) {
        Jws<Claims> jws = Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer(ISSUER)
            .build()
            .parseSignedClaims(token);
        return jws.getPayload();
    }

    /** Convenience: lift the roles claim back into a List. */
    @SuppressWarnings("unchecked")
    public List<String> rolesOf(Claims claims) {
        Object r = claims.get("roles");
        if (r instanceof List<?> list) return (List<String>) list;
        return List.of();
    }

    public record IssuedToken(String token, Instant expiresAt) {}

    /**
     * Resolve the signing key:
     *  - If {@code configured} is non-blank, treat it as either Base64-encoded
     *    bytes (preferred) or a plain UTF-8 secret. Both are coerced to a
     *    256-bit key via SHA-256 inside Keys.hmacShaKeyFor when shorter than 32 bytes.
     *  - Otherwise generate a random 256-bit key on first call (warn).
     */
    private static SecretKey resolveKey(String configured) {
        if (configured != null && !configured.isBlank()) {
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(configured);
                if (bytes.length < 32) bytes = padTo32(bytes);
            } catch (IllegalArgumentException notB64) {
                byte[] raw = configured.getBytes(StandardCharsets.UTF_8);
                bytes = raw.length >= 32 ? raw : padTo32(raw);
            }
            return Keys.hmacShaKeyFor(bytes);
        }
        // Dev fallback — log loudly that tokens don't survive restarts.
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        System.err.println("[JwtService] WARN: impact.jwt.secret not set — "
            + "generated a one-shot signing key. Tokens will be invalidated on restart. "
            + "Set IMPACT_JWT_SECRET (or --jwt-secret on the CLI) for persistence.");
        return Keys.hmacShaKeyFor(random);
    }

    private static byte[] padTo32(byte[] src) {
        byte[] out = new byte[32];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, 32));
        return out;
    }

    /** Field exposed for filter convenience — looks at the Authorization header form. */
    public static String extractBearer(String authorizationHeader) {
        if (authorizationHeader == null) return null;
        String h = authorizationHeader.trim();
        if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String t = h.substring(7).trim();
            return t.isEmpty() ? null : t;
        }
        return null;
    }

    /** Tiny helper so callers needn't import claim keys. */
    public static Map<String, Object> claimsAsMap(Claims c) {
        return new java.util.LinkedHashMap<>(c);
    }
}
