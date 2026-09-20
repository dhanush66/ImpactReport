package io.spmp.impact.testgen;

import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.testgen.TestCaseBatch.CoversEdge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Manual override rules for {@code :TestCase}-{@code :COVERS}-{@code Method|Class|...}
 * edges. Auto-tagging by {@link TestCaseTagger} is keyword-based and inevitably
 * mis-tags some pairs; this lets an admin curate the result by editing a small
 * YAML-subset file and re-running {@code impact testcases --glossary <file>}.
 *
 * <p>File format (deliberately tiny — no SnakeYAML dependency):
 * <pre>
 * # glossary.yaml — manual COVERS-edge overrides
 * #
 * # Top-level key  = test-case ID (must match the :TestCase node's id)
 * # add: / remove: = which operation to apply
 * # &lt;Kind&gt;: &lt;key&gt;     = a single rule
 * # &lt;Kind&gt;:           = multi-value list (one - per line)
 * #   - key1
 * #   - key2
 * #
 * # Recognised kinds: Method | Class | RestEndpoint | TaskType | DbTable
 * #                 | MessageConstant | Scheduler
 *
 * LBF-DIST-006:
 *   add:
 *     Method: com.foo.Bar.baz
 *     TaskType: GrantPermission
 *   remove:
 *     DbTable: SomeTable
 *
 * LBF-CFG-002:
 *   add:
 *     RestEndpoint: /RestAPI/WC/Clustering/updateSchedulerNode
 *   remove:
 *     Method:
 *       - com.acme.Foo.bar
 *       - com.acme.Foo.baz
 * </pre>
 *
 * <p>Rules are applied <em>after</em> the auto-tagging pass:
 * <ul>
 *   <li>{@code add} — appends a {@link CoversEdge} with confidence 1.0 (manual = authoritative).
 *   <li>{@code remove} — drops any matching edge already in the batch <em>and</em> issues a
 *       Cypher {@code DELETE} so previously persisted edges from earlier runs go away too.
 * </ul>
 */
public final class GlossaryOverrides {

    private static final java.util.Set<String> VALID_KINDS = java.util.Set.of(
        "Method", "Class", "RestEndpoint", "TaskType",
        "DbTable", "MessageConstant", "Scheduler");

    public record Rule(String testCaseId, String op /*"add"|"remove"*/, String kind, String key) {}

    public record Stats(int rules, int adds, int removesInMemory, int removesInGraph) {}

    private GlossaryOverrides() {}

    /** Parse the file and return a flat rule list. Validates kinds and ops. */
    public static List<Rule> parse(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("glossary file not found: " + file);
        }
        List<String> lines = Files.readAllLines(file);
        List<Rule> out = new ArrayList<>();

        String currentTcId = null;
        String currentOp = null;            // "add" | "remove"
        String pendingKind = null;          // when last line was "<Kind>:" with no inline value (list mode)

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            // Strip comments + trailing whitespace; preserve leading indentation
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw);
            String trimmed = line.stripTrailing();
            if (trimmed.isBlank()) continue;

            int indent = leadingSpaces(trimmed);
            String content = trimmed.substring(indent);

            // List item under a "<Kind>:" header
            if (content.startsWith("- ")) {
                if (pendingKind == null || currentTcId == null || currentOp == null) {
                    throw new IOException("line " + (i+1) + ": list item with no enclosing kind/op");
                }
                String key = content.substring(2).trim();
                if (!key.isEmpty()) out.add(new Rule(currentTcId, currentOp, pendingKind, key));
                continue;
            }
            // Reset list-mode kind on any non-list line
            pendingKind = null;

            int colon = content.indexOf(':');
            if (colon < 0) {
                throw new IOException("line " + (i+1) + ": expected ':' in '" + content + "'");
            }
            String key = content.substring(0, colon).trim();
            String value = content.substring(colon + 1).trim();

            if (indent == 0) {
                // Top-level test-case id
                currentTcId = key;
                currentOp = null;
                continue;
            }
            // Indent 2 — "add:" / "remove:"
            if (indent == 2) {
                if (currentTcId == null) {
                    throw new IOException("line " + (i+1) + ": '" + key + "' outside any test-case id");
                }
                if (!"add".equals(key) && !"remove".equals(key)) {
                    throw new IOException("line " + (i+1) + ": op must be 'add' or 'remove', got '" + key + "'");
                }
                currentOp = key;
                if (!value.isEmpty()) {
                    throw new IOException("line " + (i+1) + ": '" + key + ":' takes no inline value");
                }
                continue;
            }
            // Indent >= 4 — "<Kind>: <key>" or "<Kind>:" (list-mode header)
            if (currentTcId == null || currentOp == null) {
                throw new IOException("line " + (i+1) + ": kind '" + key + "' outside an add:/remove: block");
            }
            if (!VALID_KINDS.contains(key)) {
                throw new IOException("line " + (i+1) + ": unknown kind '" + key
                    + "' — valid: " + VALID_KINDS);
            }
            if (value.isEmpty()) {
                pendingKind = key;            // following "- value" lines bind here
            } else {
                out.add(new Rule(currentTcId, currentOp, key, value));
            }
        }
        return out;
    }

    /**
     * Apply the rules to a populated {@link TestCaseBatch}:
     *  - {@code add} appends edges to {@code batch.covers}.
     *  - {@code remove} drops matching in-memory edges <em>and</em> issues a Cypher
     *    DELETE for any persisted edges so re-running with a glossary fully reconciles.
     */
    public static Stats apply(List<Rule> rules, TestCaseBatch batch, Neo4jWriter writer) {
        // Pre-expand bare Method keys (no '(') to every overload's full FQN. The graph
        // stores Method FQNs with their full param list, so an admin writing
        // "Method: com.foo.Bar.baz" really means "every baz overload on Bar" — make it work.
        rules = expandBareMethodKeys(rules, writer);

        // Index existing edges so add rules dedupe correctly.
        Map<String, CoversEdge> byKey = new LinkedHashMap<>();
        for (CoversEdge ce : batch.covers) {
            byKey.put(edgeKey(ce.testCaseId(), ce.targetKind(), ce.targetKey()), ce);
        }

        int adds = 0, removesMem = 0, removesGraph = 0;
        List<Rule> graphDeletes = new ArrayList<>();

        for (Rule r : rules) {
            String k = edgeKey(r.testCaseId(), r.kind(), r.key());
            if ("add".equals(r.op())) {
                if (!byKey.containsKey(k)) {
                    CoversEdge ce = new CoversEdge(r.testCaseId(), r.kind(), r.key(), 1.0);
                    batch.covers.add(ce);
                    byKey.put(k, ce);
                    adds++;
                }
            } else { // remove
                if (byKey.remove(k) != null) {
                    batch.covers.removeIf(e ->
                        e.testCaseId().equals(r.testCaseId())
                            && e.targetKind().equals(r.kind())
                            && e.targetKey().equals(r.key()));
                    removesMem++;
                }
                // Always queue a graph delete — covers the case where the edge was
                // persisted by an earlier run but isn't in this batch's covers list.
                graphDeletes.add(r);
            }
        }

        if (writer != null && !graphDeletes.isEmpty()) {
            removesGraph = deleteFromGraph(writer, graphDeletes);
        }
        return new Stats(rules.size(), adds, removesMem, removesGraph);
    }

    /**
     * For every Method rule whose key has no parenthesis (e.g. "com.foo.Bar.baz"),
     * query the graph for all overloads (FQN starts with key + "(") and emit one rule
     * per overload. Class/RestEndpoint/etc. rules pass through unchanged.
     */
    private static List<Rule> expandBareMethodKeys(List<Rule> in, Neo4jWriter writer) {
        if (writer == null) return in;
        // Collect unique bare keys first to query in one batch
        java.util.LinkedHashSet<String> bareKeys = new java.util.LinkedHashSet<>();
        for (Rule r : in) {
            if ("Method".equals(r.kind()) && r.key() != null && r.key().indexOf('(') < 0) {
                bareKeys.add(r.key());
            }
        }
        if (bareKeys.isEmpty()) return in;

        Map<String, List<String>> expansion = new LinkedHashMap<>();
        try (var res = writer.session().run(
            "UNWIND $keys AS k " +
            "MATCH (m:Method) " +
            "WHERE m.fqn STARTS WITH k + '(' " +
            "RETURN k AS key, collect(m.fqn) AS fqns",
            Map.of("keys", new ArrayList<>(bareKeys)))) {
            while (res.hasNext()) {
                var rec = res.next();
                String key = rec.get("key").asString("");
                List<String> fqns = rec.get("fqns").asList(v -> v.asString(""));
                if (!key.isEmpty() && !fqns.isEmpty()) expansion.put(key, fqns);
            }
        } catch (Throwable t) {
            System.err.println("[glossary] bare-method expansion failed: " + t.getMessage());
            return in;
        }

        // Warn about any bare keys that didn't resolve so the user can fix the typo
        for (String k : bareKeys) {
            if (!expansion.containsKey(k)) {
                System.err.println("[glossary] WARN: Method '" + k
                    + "' matched no graph node — typo? Use the full FQN incl. '(...)' for precise targeting.");
            }
        }

        List<Rule> out = new ArrayList<>(in.size());
        for (Rule r : in) {
            if ("Method".equals(r.kind()) && r.key() != null && r.key().indexOf('(') < 0) {
                List<String> fqns = expansion.get(r.key());
                if (fqns == null) {
                    out.add(r);   // leave the unresolved rule alone; writer will silently drop it
                } else {
                    for (String fqn : fqns) {
                        out.add(new Rule(r.testCaseId(), r.op(), r.kind(), fqn));
                    }
                }
            } else {
                out.add(r);
            }
        }
        return out;
    }

    private static int deleteFromGraph(Neo4jWriter writer, List<Rule> deletes) {
        // Group by target kind so we can MATCH the right node label once per group.
        Map<String, List<Rule>> byKind = new LinkedHashMap<>();
        for (Rule r : deletes) byKind.computeIfAbsent(r.kind(), k -> new ArrayList<>()).add(r);

        int total = 0;
        for (var e : byKind.entrySet()) {
            String kind = e.getKey();
            List<Map<String, String>> pairs = new ArrayList<>();
            for (Rule r : e.getValue()) {
                pairs.add(Map.of("tcId", r.testCaseId(), "key", r.key()));
            }
            String keyProp = switch (kind) {
                case "Method", "Class" -> "fqn";
                case "RestEndpoint"     -> "url";
                case "TaskType"         -> "id";
                case "DbTable"          -> "name";
                case "MessageConstant"  -> "value";
                case "Scheduler"        -> "fqn";   // schedulers are :Class nodes
                default                 -> "fqn";
            };
            String label = "Scheduler".equals(kind) ? "Class" : kind;
            String cypher =
                "UNWIND $rows AS row " +
                "MATCH (tc:TestCase {id: row.tcId})-[c:COVERS]->(t:" + label + " {" + keyProp + ": row.key}) " +
                "DELETE c " +
                "RETURN count(c) AS n";
            try (var res = writer.session().run(cypher, Map.of("rows", pairs))) {
                if (res.hasNext()) total += res.next().get("n").asInt(0);
            } catch (Throwable t) {
                System.err.println("[glossary] graph-delete failed for kind=" + kind + ": " + t.getMessage());
            }
        }
        return total;
    }

    private static String edgeKey(String tcId, String kind, String key) {
        return tcId + "" + kind + "" + key;
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }
}
