package io.spmp.impact.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Surface the real cause of 500s to the SPA instead of Spring's generic
 * "Internal Server Error" body. Also writes the full stack trace to the server
 * log so operators can grep for it.
 *
 * <p>Order matters: more specific handlers (e.g. validation) should be added
 * later if needed; the catch-all here just makes "unknown" errors actionable.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 404s on static resources are routine browser noise (e.g. Chrome probes
     * {@code /.well-known/appspecific/com.chrome.devtools.json}). Return a plain
     * 404 without logging the stack trace.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> staticNotFound(NoResourceFoundException t) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "not found", "path", t.getResourcePath()));
    }

    @ExceptionHandler(Throwable.class)
    public ResponseEntity<?> any(Throwable t) {
        // Always log the full trace so the operator can correlate with what the user saw.
        System.err.println("[web] unhandled exception in REST handler:");
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        System.err.println(sw);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "(no message)" : t.getMessage()));
        body.put("type", t.getClass().getName());
        body.put("at",   Instant.now().toString());
        // Don't leak the full stack to the client by default — but include the
        // immediate caused-by chain so the user has a fighting chance of
        // understanding without server-side log access.
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            body.put("cause", cause.getClass().getSimpleName() + ": "
                + (cause.getMessage() == null ? "(no message)" : cause.getMessage()));
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
