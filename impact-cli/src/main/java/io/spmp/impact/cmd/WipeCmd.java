package io.spmp.impact.cmd;

import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.txn.CypherClient.CResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "wipe", description = "Remove a snapshot (commit) or the entire graph.")
public class WipeCmd implements Callable<Integer> {

    @Option(names = "--commit", description = "Commit SHA to wipe. If omitted with --all, wipes everything.")
    String commit;

    @Option(names = "--all", description = "Wipe the entire graph (DESTRUCTIVE).")
    boolean all;

    @Mixin
    Neo4jOptions neo;

    @Override
    public Integer call() {
        if (!all && (commit == null || commit.isEmpty())) {
            System.err.println("Specify --commit <sha> or --all.");
            return 2;
        }
        try (Neo4jWriter writer = new Neo4jWriter(neo.resolveUri(), neo.resolveUser(), neo.resolvePass())) {
            if (all) {
                try (CResult r = writer.session().run("MATCH (n) DETACH DELETE n")) {
                    r.consume();
                }
                System.out.println("[wipe] all nodes deleted.");
            } else {
                try (CResult r = writer.session().run(
                        "MATCH (n) WHERE n.commit_sha = $sha DETACH DELETE n",
                        Map.of("sha", commit))) {
                    r.consume();
                }
                System.out.println("[wipe] snapshot " + commit + " deleted.");
            }
        }
        return 0;
    }
}
