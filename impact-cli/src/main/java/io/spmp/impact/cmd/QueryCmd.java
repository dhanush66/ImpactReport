package io.spmp.impact.cmd;

import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.txn.CypherClient.CResult;
import io.spmp.impact.graph.txn.CypherClient.CRecord;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(name = "query", description = "Run an ad-hoc Cypher query.")
public class QueryCmd implements Callable<Integer> {

    @Parameters(index = "0", description = "Cypher query string.")
    String cypher;

    @Mixin
    Neo4jOptions neo;

    @Override
    public Integer call() {
        try (Neo4jWriter writer = new Neo4jWriter(neo.resolveUri(), neo.resolveUser(), neo.resolvePass());
             CResult result = writer.session().run(cypher)) {
            while (result.hasNext()) {
                CRecord r = result.next();
                System.out.println(r.asMap());
            }
        }
        return 0;
    }
}
