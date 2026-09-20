package io.spmp.impact.web.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * P9.x — Accept patch (.patch / .diff) file uploads from the SPA so users can
 * point analyze at a local file they picked via the browser's native file dialog.
 *
 * <p>The SPA flow:
 * <pre>
 *   1) User picks a file via &lt;input type="file"&gt;
 *   2) POST /api/v1/uploads/patch  (multipart/form-data with the file)
 *      → server saves to a temp dir, returns {path, size}
 *   3) SPA passes that path to POST /api/v1/analyze as {patchPath: "..."}
 * </pre>
 *
 * <p>Uploaded files live under the {@code impact.temp.dir} configured in
 * {@code application.properties} (default: {@code temp}, relative to the working
 * directory), inside an {@code impact-uploads-<UUID>/} subdirectory. They are
 * deleted on JVM exit; not actively reaped between runs — the OS no longer cleans
 * this location since it's outside {@code java.io.tmpdir}, so a stale-file reaper
 * or manual cleanup is recommended for long-running deployments.
 */
@RestController
@RequestMapping("/api/v1/uploads")
public class UploadController {

    /** Hard cap to keep a malicious / mistaken upload from filling the disk. */
    private static final long MAX_PATCH_BYTES = 100L * 1024 * 1024;   // 100 MB

    /**
     * Base directory for upload scratch space — overridable via
     * {@code impact.temp.dir} in {@code application.properties}. The default is
     * relative to the working directory (the launchers run from the folder
     * holding the jar), which keeps the distribution portable and stops temp
     * files sprawling across {@code %TEMP%}.
     */
    @Value("${impact.temp.dir:temp}")
    private String tempDirProperty;

    @PostMapping("/patch")
    public ResponseEntity<?> uploadPatch(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "no file uploaded"));
        }
        if (file.getSize() > MAX_PATCH_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(Map.of("error", "file too large (max " + (MAX_PATCH_BYTES / 1024 / 1024) + " MB)",
                             "size", file.getSize()));
        }

        // Sanitize the original filename — strip any path components a malicious
        // client tried to inject (the browser doesn't send them but be defensive).
        String original = file.getOriginalFilename();
        String safeName = sanitize(original);
        if (safeName.isEmpty()) safeName = "upload.patch";
        if (!hasAcceptableExt(safeName)) safeName += ".patch";

        // Ensure the configured temp root exists; createTempDirectory will fail
        // when the parent is missing. createDirectories is idempotent.
        Path tempRoot = Path.of(tempDirProperty);
        Files.createDirectories(tempRoot);
        Path tempDir = Files.createTempDirectory(tempRoot,
            "impact-uploads-" + UUID.randomUUID().toString().substring(0, 8) + "-");
        Path target = tempDir.resolve(safeName);
        // Mark for delete-on-exit so test runs don't leave clutter
        tempDir.toFile().deleteOnExit();
        target.toFile().deleteOnExit();
        file.transferTo(target);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("path", target.toAbsolutePath().toString());
        body.put("originalName", original);
        body.put("size", file.getSize());
        return ResponseEntity.ok(body);
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        int lastSlash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String basename = lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
        // Drop any character that isn't basename-safe.
        return basename.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static boolean hasAcceptableExt(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".patch") || lower.endsWith(".diff") || lower.endsWith(".txt");
    }
}
