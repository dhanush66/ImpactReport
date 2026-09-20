package io.spmp.impact.web.api;

import io.spmp.impact.report.HtmlReportRenderer;
import io.spmp.impact.web.ReportCache;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * P10 — Serve cached {@link io.spmp.impact.model.ImpactReport}s back in JSON or
 * HTML so the SPA can offer a "Download report" button without re-running the
 * analysis pipeline.
 *
 * <ul>
 *   <li>{@code GET /api/v1/reports/{id}/html} — FreeMarker-rendered HTML with a
 *       {@code Content-Disposition: attachment} header so the browser saves it as
 *       <code>impact-report-{id}.html</code></li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportCache cache;

    public ReportController(ReportCache cache) { this.cache = cache; }

    @GetMapping("/{id}/html")
    public ResponseEntity<?> getHtml(@PathVariable("id") String id) {
        var entry = cache.get(id);
        if (entry == null) return notFound(id);
        String html = HtmlReportRenderer.renderToString(entry.report(), entry.showCoverage());
        return asAttachment(html.getBytes(StandardCharsets.UTF_8), MediaType.TEXT_HTML,
            "impact-report-" + id + ".html");
    }

    private static ResponseEntity<byte[]> asAttachment(byte[] body, MediaType type, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setContentDisposition(
            ContentDisposition.attachment().filename(filename).build());
        headers.setContentLength(body.length);
        return new ResponseEntity<>(body, headers, org.springframework.http.HttpStatus.OK);
    }

    private static ResponseEntity<?> notFound(String id) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.NOT_FOUND)
            .body(Map.of("error", "report not found or expired (30 min TTL)", "reportId", id));
    }
}
