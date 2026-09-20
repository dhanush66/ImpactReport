package io.spmp.impact.cmd;

import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.Schema;
import io.spmp.impact.testgen.GlossaryOverrides;
import io.spmp.impact.testgen.TestCaseBatch;
import io.spmp.impact.testgen.TestCaseTagger;
import io.spmp.impact.testgen.ingest.TestCasesMdIngestor;
import io.spmp.impact.testgen.ingest.TestCasesXlsxIngestor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "testcases",
    description = "Import a test-case library (.md or .xlsx) into Neo4j, then auto-tag each test case against graph nodes.")
public class TestCasesCmd implements Callable<Integer> {

    @Option(names = "--md",   description = "Path to a markdown file with | ID | Title | Steps | Expected | tables.")
    Path mdFile;

    @Option(names = "--xlsx", description = "Path to a .xlsx workbook with test cases.")
    Path xlsxFile;

    @Option(names = "--no-tag", description = "Skip the auto-tagging step (just ingest TestCase nodes).")
    boolean noTag;

    @Option(names = "--glossary",
        description = "Manual override file (YAML-subset). Adds/removes :COVERS edges that the auto-tagger got wrong. "
                    + "Applied after --no-tag (so you can also use it solo to curate edges). See GlossaryOverrides.java javadoc for the schema.")
    Path glossaryFile;

    @Mixin
    Neo4jOptions neo;

    @Override
    public Integer call() throws Exception {
        if (mdFile == null && xlsxFile == null && glossaryFile == null) {
            System.err.println("[testcases] Provide --md <file> and/or --xlsx <file> (or --glossary <file> for curate-only).");
            return 2;
        }

        TestCaseBatch batch = new TestCaseBatch();
        if (mdFile != null)   TestCasesMdIngestor.ingest(mdFile,   batch);
        if (xlsxFile != null) TestCasesXlsxIngestor.ingest(xlsxFile, batch);

        boolean curateOnly = (mdFile == null && xlsxFile == null && glossaryFile != null);
        if (batch.size() == 0 && !curateOnly) {
            System.err.println("[testcases] No test cases parsed — check the input format.");
            return 1;
        }

        try (Neo4jWriter writer = new Neo4jWriter(neo.resolveUri(), neo.resolveUser(), neo.resolvePass())) {
            Schema.bootstrap(writer);

            if (!curateOnly) {
                writer.writeTestCaseBatch(batch);
                System.out.printf("[testcases] wrote %d test cases, %d suites, %d in-suite edges%n",
                    batch.testCases.size(), batch.suites.size(), batch.inSuite.size());

                if (!noTag) {
                    int edges = TestCaseTagger.tag(writer, batch);
                    writer.writeTestCaseBatch(batch);  // writes the COVERS edges populated by tagger
                    System.out.printf("[testcases] persisted %d COVERS edges%n", edges);
                }
            }

            // ── Apply manual overrides last so they always win against the auto-tagger ──
            if (glossaryFile != null) {
                List<GlossaryOverrides.Rule> rules = GlossaryOverrides.parse(glossaryFile);
                GlossaryOverrides.Stats stats = GlossaryOverrides.apply(rules, batch, writer);
                // Persist the new edges (and re-persist any unchanged ones for batch idempotency).
                writer.writeTestCaseBatch(batch);
                System.out.printf("[testcases] glossary: %d rules — added %d, removed %d (mem) / %d (graph)%n",
                    stats.rules(), stats.adds(), stats.removesInMemory(), stats.removesInGraph());
            }
        }
        return 0;
    }
}
