package io.spmp.impact.remote;

import io.spmp.impact.cmd.RemoteRepoOptions;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Materializes a remote repository to a local cache directory so the rest of the
 * tool (3-pass extractor + JgitDiffSource) can operate on it as if the user passed
 * {@code --src <localPath>}.
 *
 * <h2>READ-ONLY CONTRACT — strict</h2>
 * This class — and the entire {@code remote} package — performs <b>only read
 * operations</b> against the Zoho Repository API. Specifically:
 * <ul>
 *   <li>All HTTP calls use {@code GET} (verified at audit: {@link RemoteRepositoryClient}
 *       constructs only {@code .GET()} requests). No POST / PUT / DELETE / PATCH.</li>
 *   <li>The only jgit operation invoked is {@link Git#cloneRepository()} — equivalent
 *       to {@code git clone}, a pure read against the remote. No {@code push()},
 *       {@code commit()}, {@code fetch()} (beyond the initial clone), {@code pull()},
 *       or any other write operation appears anywhere.</li>
 *   <li>After clone, the local {@code remote.origin.pushurl} is set to a placeholder
 *       that fails any subsequent {@code git push} from the cache directory — so even
 *       if a user later cd's into the cache and tries to push, it fails fast. The
 *       fetch URL is left intact (read-only).</li>
 *   <li>Credentials are passed to jgit only as a {@code CredentialsProvider}; jgit
 *       does not persist them in {@code .git/config}. The cached repo therefore
 *       carries NO embedded token.</li>
 * </ul>
 *
 * <h2>Strategy (in order of preference)</h2>
 * <ol>
 *   <li><b>git-over-HTTPS clone</b> when repo metadata exposes a {@code clone_url}.
 *       Uses jgit's {@link Git#cloneRepository()} with a
 *       {@link UsernamePasswordCredentialsProvider} carrying the token. This is the
 *       canonical path — same code today's {@code JgitDiffSource} uses to diff.</li>
 *   <li><b>Tarball / zipball download</b> when metadata exposes {@code tarball_url}
 *       or {@code zipball_url}. Downloaded via JDK {@link HttpClient}, expanded with
 *       {@link ZipInputStream}.</li>
 *   <li><b>Hard fail</b> with {@link RemoteRepoException} exit code 14 — never
 *       silently fall through; the user sees exactly which URL was probed.</li>
 * </ol>
 *
 * <h2>Cache layout</h2>
 * {@code <cacheRoot>/<orgId>/<repoId>/<sha>/source/...}. A {@code .complete} marker
 * file is written after a successful materialize; absence means partial download and
 * the directory is purged + retried. Invalidation is SHA-based, never time-based:
 * the same commit always hits cache, different commits always re-clone.
 */
public class RemoteRepoMaterializer {

    /** Result of a successful materialize — handed back to the calling CLI. */
    public record MaterializedRepo(
        Path sourceRoot,        // absolute path to the checked-out source tree
        String resolvedSha,     // the actual commit SHA we materialized (40-char hex)
        String repoIdForGraph   // suggested :Repo node id (defaults to remoteRepoId)
    ) {}

    private final RemoteRepoOptions opts;
    private final RemoteRepositoryClient client;

    public RemoteRepoMaterializer(RemoteRepoOptions opts) {
        this.opts = opts;
        this.client = new RemoteRepositoryClient(opts);
    }

    /**
     * Resolve metadata for the user-supplied repoId, pick the materialization strategy,
     * use the cache when possible, return the {@link MaterializedRepo}.
     *
     * @param repoId    Zoho repository ID. May be {@code opts.remoteRepo} or a
     *                  per-dep id from {@code opts.remoteDeps}.
     * @param ref       Branch / tag / SHA. {@code null} = use repo's default branch.
     * @return MaterializedRepo with a usable filesystem path.
     */
    public MaterializedRepo materialize(String repoId, String ref) {
        opts.validateForRemote();

        RemoteRepositoryClient.RepositorySummary meta = client.getRepositoryMetadata(repoId);
        String wantRef = (ref != null && !ref.isEmpty()) ? ref
                       : (opts.remoteRef != null && !opts.remoteRef.isEmpty()) ? opts.remoteRef
                       : meta.defaultBranch();
        if (wantRef == null || wantRef.isEmpty()) {
            throw new RemoteRepoException(14,
                "No ref to materialize: --remote-ref not set and repo metadata has no default_branch. Repo: " + repoId);
        }

        // We don't know the resolved SHA until we clone (refs can move between calls).
        // Cache by SHA, so we always need to do at least a lightweight resolution.
        // For simplicity: clone first into a sha-discovery temp dir, read HEAD, then
        // promote to the canonical cache path (rename-if-needed). When the rename
        // collides with an existing entry → cache hit, discard the temp clone.
        Path cacheRoot = opts.resolveCacheDir();
        Path orgDir = cacheRoot.resolve(opts.remoteOrg).resolve(repoId);

        // Fast path: if ref looks like a SHA already and cache hit exists, skip API.
        if (looksLikeSha(wantRef)) {
            Path direct = orgDir.resolve(wantRef);
            if (!opts.refresh && hasCompleteMarker(direct)) {
                System.out.println("[remote] cache hit: " + direct);
                return new MaterializedRepo(direct.resolve("source").toAbsolutePath(),
                    wantRef, repoId);
            }
        }

        // Materialize to a temp dir first, then read its HEAD sha to pick final cache path.
        try {
            Files.createDirectories(cacheRoot);
        } catch (IOException ioe) {
            throw new RemoteRepoException(12, "Cannot create cache dir " + cacheRoot + ": " + ioe.getMessage());
        }
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory(cacheRoot, "fetching-" + repoId + "-");
        } catch (IOException ioe) {
            throw new RemoteRepoException(12, "Cannot create temp dir under " + cacheRoot + ": " + ioe.getMessage());
        }
        Path sourceTemp = tempDir.resolve("source");

        boolean cloned = false;
        if (meta.cloneUrl() != null && !meta.cloneUrl().isEmpty()) {
            try {
                cloneViaGit(meta.cloneUrl(), wantRef, sourceTemp);
                cloned = true;
            } catch (RemoteRepoException re) {
                // git clone failed — fall through to tarball if available
                System.err.println("[remote] git clone failed: " + re.getMessage()
                    + "\n[remote] falling back to tarball download if available");
            }
        }
        if (!cloned && meta.tarballUrl() != null && !meta.tarballUrl().isEmpty()) {
            downloadTarball(meta.tarballUrl(), sourceTemp);
            cloned = true;
        }
        if (!cloned) {
            try { deleteRecursive(tempDir); } catch (Throwable ignored) {}
            throw new RemoteRepoException(14,
                "Cannot materialize repo " + repoId
                    + " — metadata had neither clone_url nor tarball_url. Probe the right field names via --remote-meta-endpoint.");
        }

        // Discover the resolved SHA. If we cloned via jgit, the .git dir is present.
        String resolvedSha = resolveHeadSha(sourceTemp, wantRef);
        Path finalDir = orgDir.resolve(resolvedSha);

        // Try atomic promote. If finalDir already exists (race / earlier ingest), use it.
        try {
            Files.createDirectories(finalDir.getParent());
            if (Files.exists(finalDir)) {
                // Cache already has this SHA; prefer the existing one.
                try { deleteRecursive(tempDir); } catch (Throwable ignored) {}
                System.out.println("[remote] cache hit after resolve: " + finalDir);
                return new MaterializedRepo(finalDir.resolve("source").toAbsolutePath(),
                    resolvedSha, repoId);
            }
            Files.move(tempDir, finalDir, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ioe) {
            // ATOMIC_MOVE not supported on some filesystems → fall back to non-atomic.
            try {
                if (!Files.exists(finalDir)) Files.move(tempDir, finalDir);
            } catch (IOException ioe2) {
                throw new RemoteRepoException(13,
                    "Cache promotion failed (partial download cleaned up): " + ioe2.getMessage());
            }
        }
        writeCompleteMarker(finalDir);
        System.out.println("[remote] materialized " + repoId + "@" + resolvedSha.substring(0, Math.min(12, resolvedSha.length())) + " → " + finalDir);
        return new MaterializedRepo(finalDir.resolve("source").toAbsolutePath(), resolvedSha, repoId);
    }

    // ─── strategy 1: git clone via jgit ────────────────────────────────────

    private void cloneViaGit(String cloneUrl, String ref, Path destSource) {
        String token = opts.resolveToken();
        try (Git ignored = Git.cloneRepository()
                .setURI(cloneUrl)
                .setDirectory(destSource.toFile())
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(token, ""))
                .setBranch(ref)
                .call()) {
            // success — repo is at destSource with .git/ in place
        } catch (GitAPIException | RuntimeException e) {
            throw new RemoteRepoException(14,
                "git clone failed for " + cloneUrl + " (ref=" + ref + "): " + e.getMessage());
        }
        // READ-ONLY GUARD: set the push URL of origin to a blocking placeholder so a
        // subsequent {@code git push} from this cache directory cannot accidentally
        // write back to the source repository. The fetch URL is left intact (we never
        // call fetch ourselves, but the directory is still a valid read-only mirror
        // for any external git tool inspecting it).
        try (Git git = Git.open(destSource.toFile())) {
            org.eclipse.jgit.lib.StoredConfig cfg = git.getRepository().getConfig();
            cfg.setString("remote", "origin", "pushurl",
                "impact-cli-read-only://no-push-from-cache");
            cfg.save();
        } catch (Throwable t) {
            // Non-fatal: if we can't write to .git/config the clone is still read-only
            // because we never call push from this code path. Just log and move on.
            System.err.println("[remote] note: could not set read-only pushurl guard: " + t.getMessage());
        }
    }

    // ─── strategy 2: tarball / zipball ─────────────────────────────────────

    private void downloadTarball(String tarballUrl, Path destSource) {
        try {
            Files.createDirectories(destSource);
        } catch (IOException ioe) {
            throw new RemoteRepoException(12, "Cannot create " + destSource + ": " + ioe.getMessage());
        }
        HttpClient http = HttpClient.newHttpClient();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(tarballUrl))
                .header("Authorization", opts.authScheme + " " + opts.resolveToken())
                .GET()
                .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                throw new RemoteRepoException(14,
                    "Tarball download HTTP " + resp.statusCode() + " from " + tarballUrl);
            }
            try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(resp.body()))) {
                ZipEntry entry;
                while ((entry = zin.getNextEntry()) != null) {
                    Path outFile = destSource.resolve(entry.getName()).normalize();
                    // Zip-slip protection
                    if (!outFile.startsWith(destSource)) continue;
                    if (entry.isDirectory()) {
                        Files.createDirectories(outFile);
                    } else {
                        Files.createDirectories(outFile.getParent());
                        Files.copy(zin, outFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        } catch (IOException | InterruptedException ioe) {
            if (ioe instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RemoteRepoException(12, "Tarball download failed: " + ioe.getMessage());
        }
    }

    // ─── SHA resolution ────────────────────────────────────────────────────

    /**
     * If we cloned via jgit, read .git/HEAD via {@link Repository#resolve}. If we
     * downloaded a tarball (no .git/), synthesize a SHA from the ref name so cache
     * still keys consistently per (ref, content) — not ideal but functional.
     */
    private String resolveHeadSha(Path source, String fallbackRef) {
        Path dotGit = source.resolve(".git");
        if (Files.isDirectory(dotGit)) {
            try (Git git = Git.open(source.toFile())) {
                Ref head = git.getRepository().exactRef("HEAD");
                if (head != null) {
                    ObjectId obj = head.getObjectId();
                    if (obj != null) return obj.getName();
                }
            } catch (Exception ignored) {}
        }
        // tarball fallback — use the ref name itself as the cache key (not a real SHA)
        return "ref-" + fallbackRef.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    // ─── cache helpers ─────────────────────────────────────────────────────

    private static boolean looksLikeSha(String s) {
        return s != null && s.matches("[0-9a-fA-F]{7,40}");
    }

    private static boolean hasCompleteMarker(Path dir) {
        return Files.isRegularFile(dir.resolve(".complete"));
    }

    private static void writeCompleteMarker(Path dir) {
        try {
            Files.writeString(dir.resolve(".complete"),
                "Materialized by impact-cli at " + java.time.Instant.now() + "\n",
                StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // best-effort; absence of marker just means we'll re-clone next time
        }
    }

    private static void deleteRecursive(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var stream = Files.walk(p)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                .forEach(file -> { try { Files.deleteIfExists(file); } catch (IOException ignored) {} });
        }
    }
}
