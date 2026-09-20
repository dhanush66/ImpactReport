package io.spmp.impact.testgen;

import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.txn.CypherClient.CRecord;
import io.spmp.impact.graph.txn.CypherClient.CResult;
import io.spmp.impact.testgen.TestCaseBatch.CoversEdge;
import io.spmp.impact.testgen.TestCaseBatch.TestCaseNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-tags every {@link TestCaseNode} with {@link CoversEdge}s against graph nodes,
 * using identifier-style keyword extraction from the test case's title + steps + expected.
 *
 * <p>Strategy: load all candidate keys from Neo4j (class names, method names, task type IDs,
 * REST URLs, DB table names, message constants), then for each test case:
 * <ol>
 *   <li>Tokenize the combined text into identifier-like terms.</li>
 *   <li>For each candidate node, check if its key appears as a whole-word token.</li>
 *   <li>Emit a COVERS edge with confidence:
 *     <ul>
 *       <li>1.0 — exact-match unique identifier (TaskType, MessageConstant value, DbTable name)</li>
 *       <li>0.8 — class/method simple name appears whole-word</li>
 *       <li>0.6 — REST URL substring</li>
 *     </ul>
 *   </li>
 * </ol>
 */
public final class TestCaseTagger {

    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private TestCaseTagger() {}

    public static int tag(Neo4jWriter writer, TestCaseBatch batch) {
        Catalog catalog = loadCatalog(writer);
        int edges = 0;
        for (TestCaseNode tc : batch.testCases) {
            Set<String> tokens = tokenize(tc.title() + " " + tc.steps() + " " + tc.expected());
            edges += matchAndEmit(tc, tokens, catalog, batch);
        }
        System.out.printf("[TestCaseTagger] catalog: %d methods, %d classes, %d task-types, %d rest-urls, %d db-tables, %d msg-constants%n",
            catalog.methodSimple.size(), catalog.classSimple.size(), catalog.taskTypes.size(),
            catalog.restUrls.size(), catalog.dbTables.size(), catalog.msgConstants.size());
        System.out.printf("[TestCaseTagger] emitted %d COVERS edges across %d test cases%n",
            edges, batch.testCases.size());
        return edges;
    }

    private static int matchAndEmit(TestCaseNode tc, Set<String> tokens, Catalog cat, TestCaseBatch batch) {
        Set<String> claimedKeys = new HashSet<>();   // dedupe per test case
        int n = 0;

        // exact matches first (high confidence)
        for (String token : tokens) {
            if (cat.taskTypes.contains(token)) {
                if (claimedKeys.add("TaskType:" + token)) {
                    batch.covers.add(new CoversEdge(tc.id(), "TaskType", token, 1.0));
                    n++;
                }
            }
            if (cat.msgConstants.contains(token)) {
                if (claimedKeys.add("MessageConstant:" + token)) {
                    batch.covers.add(new CoversEdge(tc.id(), "MessageConstant", token, 1.0));
                    n++;
                }
            }
            if (cat.dbTables.contains(token)) {
                if (claimedKeys.add("DbTable:" + token)) {
                    batch.covers.add(new CoversEdge(tc.id(), "DbTable", token, 1.0));
                    n++;
                }
            }
        }

        // class & method simple names (medium confidence)
        for (String token : tokens) {
            List<String> classFqns = cat.classSimple.getOrDefault(token, Collections.emptyList());
            for (String fqn : classFqns) {
                if (claimedKeys.add("Class:" + fqn)) {
                    batch.covers.add(new CoversEdge(tc.id(), "Class", fqn, 0.8));
                    n++;
                }
            }
            List<String> methodFqns = cat.methodSimple.getOrDefault(token, Collections.emptyList());
            for (String fqn : methodFqns) {
                if (claimedKeys.add("Method:" + fqn)) {
                    batch.covers.add(new CoversEdge(tc.id(), "Method", fqn, 0.8));
                    n++;
                }
            }
        }

        // REST URLs — substring on the whole text (URLs contain slashes / special chars)
        String haystack = tc.title() + " " + tc.steps() + " " + tc.expected();
        for (String url : cat.restUrls) {
            // Use a fragment of the URL for matching (the class name or last segment)
            String fragment = url.startsWith("servlet:") ? url.substring("servlet:".length()) : url;
            if (fragment.length() >= 4 && haystack.contains(fragment)) {
                if (claimedKeys.add("RestEndpoint:" + url)) {
                    batch.covers.add(new CoversEdge(tc.id(), "RestEndpoint", url, 0.6));
                    n++;
                }
            }
        }
        return n;
    }

    private static Set<String> tokenize(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = WORD.matcher(text == null ? "" : text);
        while (m.find()) out.add(m.group());
        return out;
    }

    // ─── catalog loader ────────────────────────────────────────────────

    private static Catalog loadCatalog(Neo4jWriter writer) {
        Catalog c = new Catalog();
        load(writer, "MATCH (m:Method) WHERE m.simple_name IS NOT NULL AND m.start_line IS NOT NULL AND m.start_line > 0 " +
                     "RETURN m.simple_name AS name, m.fqn AS fqn",
            r -> c.methodSimple.computeIfAbsent(r.get("name").asString(), k -> new ArrayList<>())
                              .add(r.get("fqn").asString()));
        load(writer, "MATCH (c:Class) WHERE c.simple_name IS NOT NULL AND c.start_line IS NOT NULL AND c.start_line > 0 " +
                     "RETURN c.simple_name AS name, c.fqn AS fqn",
            r -> c.classSimple.computeIfAbsent(r.get("name").asString(), k -> new ArrayList<>())
                             .add(r.get("fqn").asString()));
        load(writer, "MATCH (t:TaskType) RETURN t.id AS id",
            r -> c.taskTypes.add(r.get("id").asString()));
        load(writer, "MATCH (mc:MessageConstant) RETURN mc.value AS v",
            r -> c.msgConstants.add(r.get("v").asString()));
        load(writer, "MATCH (d:DbTable) RETURN d.name AS n",
            r -> c.dbTables.add(r.get("n").asString()));
        load(writer, "MATCH (r:RestEndpoint) RETURN r.url AS u",
            r -> c.restUrls.add(r.get("u").asString()));
        return c;
    }

    private static void load(Neo4jWriter writer, String cypher, java.util.function.Consumer<CRecord> sink) {
        try (CResult r = writer.session().run(cypher)) {
            while (r.hasNext()) sink.accept(r.next());
        }
    }

    private static final class Catalog {
        final java.util.Map<String, List<String>> methodSimple = new java.util.HashMap<>();
        final java.util.Map<String, List<String>> classSimple  = new java.util.HashMap<>();
        final Set<String> taskTypes    = new HashSet<>();
        final Set<String> msgConstants = new HashSet<>();
        final Set<String> dbTables     = new HashSet<>();
        final Set<String> restUrls     = new HashSet<>();
    }
}
