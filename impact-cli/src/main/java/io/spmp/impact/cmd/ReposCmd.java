package io.spmp.impact.cmd;

import io.spmp.impact.remote.RemoteRepoException;
import io.spmp.impact.remote.RemoteRepositoryClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Discovery command: list repositories available to the configured Zoho token
 * before invoking {@code ingest} or {@code analyze} with the chosen repo id.
 *
 * <pre>
 * impact repos --remote-org abc --repo-token $TOKEN
 *   repo_id                  name                       default_branch  last_commit
 *   d7f3a1...                spmp-load-balancing        main            51dac98446
 *   88b2cc...                adsm-issue-fixes           main            1660081c13
 *   ...
 * </pre>
 *
 * <p>Useful as a one-time discovery step. Output is a fixed-width table on stdout,
 * pipe-friendly (e.g. {@code impact repos ... | grep spmp | awk '{print $1}'}).
 *
 * <p>Exit codes: see {@link RemoteRepoException}. {@code 0} on success even when
 * the list is empty (zero repos is a valid answer).
 */
@Command(name = "repos",
    description = "List repositories available to the configured --repo-token on the Zoho Repository API.")
public class ReposCmd implements Callable<Integer> {

    @Mixin
    RemoteRepoOptions remote;

    @Override
    public Integer call() {
        // For `repos`, --remote-repo is NOT required (we're listing). Only org + token.
        if (remote.remoteOrg == null || remote.remoteOrg.isEmpty()) {
            System.err.println("error: --remote-org <orgId> is required");
            return 11;
        }
        if (remote.resolveToken() == null) {
            System.err.println("error: --repo-token <token> or env IMPACT_REPO_TOKEN is required");
            return 10;
        }
        try {
            RemoteRepositoryClient client = new RemoteRepositoryClient(remote);
            List<RemoteRepositoryClient.RepositorySummary> repos = client.listRepositories();
            if (repos.isEmpty()) {
                System.out.println("(no repositories returned for org " + remote.remoteOrg + ")");
                return 0;
            }
            printTable(repos);
            return 0;
        } catch (RemoteRepoException re) {
            System.err.println(re.getMessage());
            return re.exitCode();
        } catch (Throwable t) {
            System.err.println("error: " + t.getClass().getSimpleName() + " — " + t.getMessage());
            return 1;
        }
    }

    /**
     * Fixed-width columns. Right-pads each column to the widest value in it so the
     * output stays grep-friendly. Truncates long names/SHAs with a "…" suffix.
     */
    private static void printTable(List<RemoteRepositoryClient.RepositorySummary> repos) {
        int idW = Math.max(8, repos.stream().mapToInt(r -> safe(r.repoId()).length()).max().orElse(8));
        int nameW = Math.max(4, repos.stream().mapToInt(r -> safe(r.name()).length()).max().orElse(4));
        int branchW = Math.max(14, repos.stream().mapToInt(r -> safe(r.defaultBranch()).length()).max().orElse(14));
        int shaW = 12;
        // Cap excessive widths so a single long name doesn't blow out the table.
        idW = Math.min(idW, 40);
        nameW = Math.min(nameW, 50);
        branchW = Math.min(branchW, 30);

        String fmt = "%-" + idW + "s  %-" + nameW + "s  %-" + branchW + "s  %-" + shaW + "s%n";
        System.out.printf(fmt, "repo_id", "name", "default_branch", "last_commit");
        System.out.println("-".repeat(idW + nameW + branchW + shaW + 6));
        for (var r : repos) {
            System.out.printf(fmt,
                truncate(safe(r.repoId()), idW),
                truncate(safe(r.name()), nameW),
                truncate(safe(r.defaultBranch()), branchW),
                truncate(safe(r.lastCommitSha()), shaW));
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String truncate(String s, int w) {
        if (s.length() <= w) return s;
        return s.substring(0, Math.max(1, w - 1)) + "…";
    }
}
