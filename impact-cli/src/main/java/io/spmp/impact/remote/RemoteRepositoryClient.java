package io.spmp.impact.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.spmp.impact.cmd.RemoteRepoOptions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin client over the Zoho Repository API. Uses JDK 17 {@link HttpClient} — no
 * Apache HttpClient dependency added (keeps the shaded jar lean; Jackson is already
 * a dep for the existing JSON report renderer).
 *
 * <p>Endpoint paths come from {@link RemoteRepoOptions} (placeholder substitution
 * on {orgId} / {repoId}) so the user can override every shape without recompiling
 * when the Zoho docs are confirmed. Defaults match the URL pattern documented in
 * the public overview ({@code /orgs/{org_id}/repos/{repository_id}/api/v1}).
 *
 * <p><b>Exit code contract</b> — methods throw {@link RemoteRepoException} with a
 * non-zero {@link RemoteRepoException#exitCode()}; callers should
 * {@code System.err.println(ex.getMessage())} + {@code System.exit(ex.exitCode())}.
 *
 * <p><b>Auth</b> — header {@code Authorization: <scheme> <token>}, scheme defaults
 * to {@code Zoho-oauthtoken} (Zoho convention), overridable for non-Zoho APIs.
 */
public class RemoteRepositoryClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final RemoteRepoOptions opts;
    private final HttpClient http;

    public RemoteRepositoryClient(RemoteRepoOptions opts) {
        this.opts = opts;
        this.http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * Repository summary fields the rest of the tool consumes. Optional fields are
     * left {@code null} when the API doesn't expose them — callers fall back to
     * derived values (e.g. clone via {@code base_url/orgs/.../git} when {@code cloneUrl}
     * is absent).
     */
    public record RepositorySummary(
        String repoId,
        String name,
        String defaultBranch,
        String lastCommitSha,
        String cloneUrl,
        String tarballUrl
    ) {}

    /** List repositories accessible to the configured org + token. */
    public List<RepositorySummary> listRepositories() {
        String path = opts.listEndpoint.replace("{orgId}", urlSafe(opts.remoteOrg));
        URI uri = URI.create(opts.remoteBaseUrl + path);
        JsonNode root = httpGetJson(uri, "list repositories");
        return parseRepoList(root);
    }

    /** Fetch metadata for one repository — used to discover clone_url + default branch. */
    public RepositorySummary getRepositoryMetadata(String repoId) {
        String path = opts.metaEndpoint
            .replace("{orgId}", urlSafe(opts.remoteOrg))
            .replace("{repoId}", urlSafe(repoId));
        URI uri = URI.create(opts.remoteBaseUrl + path);
        JsonNode root = httpGetJson(uri, "repository metadata for " + repoId);
        return parseRepoSummary(repoId, root);
    }

    // ─── HTTP wiring ────────────────────────────────────────────────────────

    private JsonNode httpGetJson(URI uri, String contextDescription) {
        try {
            HttpRequest req = HttpRequest.newBuilder(uri)
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .header("Authorization", opts.authScheme + " " + opts.resolveToken())
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            String body = resp.body();
            if (status == 401 || status == 403) {
                throw new RemoteRepoException(10,
                    "Zoho API auth failed (" + status + ") for " + contextDescription
                        + " — check --repo-token / IMPACT_REPO_TOKEN."
                        + "\nURL: " + uri
                        + "\nBody (first 500 chars): " + truncate(body, 500));
            }
            if (status == 404) {
                throw new RemoteRepoException(11,
                    "Endpoint not found (404) for " + contextDescription
                        + ". The default Zoho overview only documents URL roots; the actual"
                        + " path may differ. Override via the matching --remote-*-endpoint flag."
                        + "\nURL: " + uri);
            }
            if (status >= 400) {
                throw new RemoteRepoException(14,
                    "Zoho API returned " + status + " for " + contextDescription
                        + "\nURL: " + uri
                        + "\nBody (first 500 chars): " + truncate(body, 500));
            }
            try {
                return MAPPER.readTree(body);
            } catch (Exception e) {
                throw new RemoteRepoException(14,
                    "Response from " + uri + " was not valid JSON: " + e.getMessage()
                        + "\nBody (first 500 chars): " + truncate(body, 500));
            }
        } catch (IOException ioe) {
            throw new RemoteRepoException(12,
                "Network error contacting Zoho API at " + uri + " — " + ioe.getMessage());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RemoteRepoException(12, "Interrupted while contacting " + uri);
        }
    }

    // ─── JSON shape parsing — lenient, accepts several field names ─────────

    /**
     * Parse a list response shape. Accepts:
     * <ul>
     *   <li>Top-level JSON array of repo objects</li>
     *   <li>{@code {"data": [...]}}</li>
     *   <li>{@code {"repositories": [...]}}</li>
     *   <li>{@code {"repos": [...]}}</li>
     * </ul>
     * If none match, throws with a description of the actual shape.
     */
    private List<RepositorySummary> parseRepoList(JsonNode root) {
        JsonNode array = null;
        if (root.isArray()) {
            array = root;
        } else if (root.isObject()) {
            for (String key : new String[]{ "data", "repositories", "repos", "items", "results" }) {
                if (root.has(key) && root.get(key).isArray()) { array = root.get(key); break; }
            }
        }
        if (array == null) {
            throw new RemoteRepoException(14,
                "List-repos response had no recognisable array. Expected top-level array OR an object with key data/repositories/repos/items/results. Got: "
                    + truncate(root.toString(), 300));
        }
        List<RepositorySummary> out = new ArrayList<>(array.size());
        for (JsonNode r : array) {
            String id = firstString(r, "id", "repo_id", "repository_id", "_id");
            if (id == null) continue;        // skip malformed entries rather than fail
            out.add(parseRepoSummary(id, r));
        }
        return out;
    }

    private RepositorySummary parseRepoSummary(String id, JsonNode r) {
        return new RepositorySummary(
            id,
            firstString(r, "name", "repo_name", "full_name"),
            firstString(r, "default_branch", "defaultBranch", "main_branch"),
            firstString(r, "last_commit_sha", "lastCommitSha", "head_sha", "head"),
            firstString(r, "clone_url", "cloneUrl", "https_url", "git_url"),
            firstString(r, "tarball_url", "zipball_url", "archive_url")
        );
    }

    private static String firstString(JsonNode obj, String... keys) {
        if (obj == null) return null;
        for (String k : keys) {
            JsonNode v = obj.get(k);
            if (v != null && v.isTextual() && !v.asText().isEmpty()) return v.asText();
        }
        return null;
    }

    private static String urlSafe(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…[truncated]";
    }
}
