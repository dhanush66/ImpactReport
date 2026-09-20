package io.spmp.impact.cmd;

import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.txn.CypherClient.CRecord;
import io.spmp.impact.graph.txn.CypherClient.CResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

@Command(name = "snapshots", description = "List all ingested commit snapshots with file counts.")
public class SnapshotsCmd implements Callable<Integer> {

    @Mixin
    Neo4jOptions neo;

    @Override
    public Integer call() {
        try (Neo4jWriter writer = new Neo4jWriter(neo.resolveUri(), neo.resolveUser(), neo.resolvePass());
             CResult r = writer.session().run(
                 "MATCH (c:Commit) " +
                 "OPTIONAL MATCH (f:File {commit_sha: c.sha}) " +
                 "OPTIONAL MATCH (repo:Repo)-[:HAS_SNAPSHOT]->(c) " +
                 "RETURN c.sha AS sha, count(DISTINCT f) AS files, collect(DISTINCT repo.id) AS repos " +
                 "ORDER BY sha"
             )) {
            System.out.printf("%-30s %10s  %s%n", "COMMIT_SHA", "FILES", "REPOS");
            System.out.println("─".repeat(72));
            int total = 0;
            while (r.hasNext()) {
                CRecord rec = r.next();
                System.out.printf("%-30s %10d  %s%n",
                    rec.get("sha").asString(),
                    rec.get("files").asInt(0),
                    rec.get("repos").asList(v -> v.asString()));
                total++;
            }
            if (total == 0) {
                System.out.println("(no snapshots — run `ingest` first)");
            } else {
                System.out.printf("%n%d snapshot%s.%n", total, total == 1 ? "" : "s");
            }
        }
        return 0;
    }
}
