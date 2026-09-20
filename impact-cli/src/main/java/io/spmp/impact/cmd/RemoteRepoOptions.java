package io.spmp.impact.cmd;

import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.Map;

/**
 * Shared flags for selecting a repository from the Zoho Repository API instead of a
 * local filesystem path. Mounted as {@code @Mixin remote} on {@link IngestCmd},
 * {@link AnalyzeCmd}, and {@link ReposCmd}.
 *
 * <p>Pattern mirrors {@link Neo4jOptions} — env-var fallback for the token so secrets
 * don't end up in shell history.
 *
 * <p>Endpoint shapes are parameterized via flags. The published Zoho overview
 * (https://prezohoweb.zoho.com/repository/api/overview.html) only documents URL
 * roots; the concrete paths for listing repos / fetching metadata / authentication
 * details are not in the public overview. Defaults match the documented URL pattern
 * {@code /orgs/{org_id}/repos/{repository_id}/api/v1} — users can override every
 * shape via the corresponding flag once full Zoho docs are obtained or to support
 * alternative deployments.
 */
public class RemoteRepoOptions {

    @Option(names = "--remote-repo",
        description = "Repository ID on the Zoho Repository API. When set, the source tree is fetched and cached locally; --src is not required.")
    public String remoteRepo;

    @Option(names = "--remote-org",
        description = "Zoho organisation ID that owns --remote-repo. Required whenever --remote-repo is set (matches the API URL pattern /orgs/{org_id}/repos/{repository_id}/api/v1).")
    public String remoteOrg;

    @Option(names = "--remote-base-url",
        description = "Base URL for the Zoho Repository API (default: ${DEFAULT-VALUE}). Override for staging deployments / self-hosted setups.",
        defaultValue = "https://prezohoweb.zoho.com/repository")
    public String remoteBaseUrl;

    @Option(names = "--repo-token",
        description = "Auth token for the Zoho Repository API. Falls back to env var IMPACT_REPO_TOKEN. Required when --remote-repo is set.")
    public String repoToken;

    @Option(names = "--remote-auth-scheme",
        description = "Authorization header scheme (default: ${DEFAULT-VALUE}). The Authorization header is sent as '<scheme> <token>'.",
        defaultValue = "Zoho-oauthtoken")
    public String authScheme;

    @Option(names = "--remote-cache",
        description = "Cache directory for materialized repos (default: ${user.home}/.impact-cli/cache). Cache key = (orgId, repoId, resolvedSha).")
    public Path cacheDir;

    @Option(names = "--remote-ref",
        description = "Branch / tag / SHA to materialize. Default: the repo's default branch from metadata.")
    public String remoteRef;

    @Option(names = "--remote-refresh",
        description = "Force re-clone even if cache hit. Default: re-use cache when (orgId, repoId, sha) matches.")
    public boolean refresh;

    /**
     * Dep repos via remote: {@code --remote-dep <localId>=<remoteRepoId>}. Parallels
     * the existing {@code --dep <id>=<path>} flag for multi-repo ingest. Each entry is
     * materialized into the cache and joined into the in-process {@code rootsByRepoId}
     * map alongside any local-path deps.
     */
    @Option(names = "--remote-dep",
        description = "Dependency repo as <localId>=<remoteRepoId>. Repeatable. Fetched from the same Zoho org as --remote-repo.")
    public Map<String, String> remoteDeps = new java.util.LinkedHashMap<>();

    // ── Analyze-side flags (no-ops on ingest, picocli silently ignores) ────

    @Option(names = "--remote-base",
        description = "Base revision SHA for analyze. Pair with --remote-head. Mutually exclusive with --patch.")
    public String remoteBase;

    @Option(names = "--remote-head",
        description = "Head revision SHA for analyze (default: HEAD of materialized tree).")
    public String remoteHead;

    // ── Endpoint-shape overrides (escape hatches when the API docs change) ──

    @Option(names = "--remote-list-endpoint",
        description = "Override path template for listing repositories (default: ${DEFAULT-VALUE}). Placeholders: {orgId}.",
        defaultValue = "/orgs/{orgId}/api/v1/repos")
    public String listEndpoint;

    @Option(names = "--remote-meta-endpoint",
        description = "Override path template for repository metadata (default: ${DEFAULT-VALUE}). Placeholders: {orgId}, {repoId}.",
        defaultValue = "/orgs/{orgId}/repos/{repoId}/api/v1")
    public String metaEndpoint;

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Returns the explicit --repo-token, then $IMPACT_REPO_TOKEN, then {@code null}. */
    public String resolveToken() {
        if (repoToken != null && !repoToken.isEmpty()) return repoToken;
        String env = System.getenv("IMPACT_REPO_TOKEN");
        return (env != null && !env.isEmpty()) ? env : null;
    }

    /** Returns the explicit --remote-cache, else {@code ${user.home}/.impact-cli/cache}. */
    public Path resolveCacheDir() {
        if (cacheDir != null) return cacheDir.toAbsolutePath();
        String home = System.getProperty("user.home", ".");
        return Path.of(home, ".impact-cli", "cache").toAbsolutePath();
    }

    /** True iff --remote-repo is set (the gate for entering the remote workflow). */
    public boolean isRemoteEnabled() {
        return remoteRepo != null && !remoteRepo.isEmpty();
    }

    /**
     * Validate that the user supplied a token + org when --remote-repo is set.
     * Throws {@link IllegalStateException} with a clear message — the caller should
     * print it and exit with code 10 (auth) or 11 (missing arg).
     */
    public void validateForRemote() {
        if (!isRemoteEnabled()) return;
        if (remoteOrg == null || remoteOrg.isEmpty()) {
            throw new IllegalStateException("--remote-repo requires --remote-org <orgId>");
        }
        if (resolveToken() == null) {
            throw new IllegalStateException("--remote-repo requires --repo-token <token> or env var IMPACT_REPO_TOKEN");
        }
    }
}
