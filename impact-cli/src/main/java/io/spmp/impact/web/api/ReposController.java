package io.spmp.impact.web.api;

import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.txn.CypherClient.CRecord;
import io.spmp.impact.graph.txn.CypherClient.CResult;
import io.spmp.impact.graph.txn.CypherClient.CValue;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P9 — {@code GET /api/v1/repos}. Lists every {@code :Repo} + its {@code :Commit}
 * snapshots in the connected Neo4j. Same data as the {@code snapshots} CLI command,
 * shaped for JSON consumers.
 *
 * <p>P9.6 — JWT-protected; any of VIEWER / DEV / ADMIN may read it.
 */
@RestController
@RequestMapping("/api/v1/repos")
public class ReposController {

    private final Neo4jWriter writer;

    public ReposController(Neo4jWriter writer) { this.writer = writer; }

    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (CResult res = writer.session().run(
                "MATCH (r:Repo) "
              + "OPTIONAL MATCH (r)-[:HAS_SNAPSHOT]->(c:Commit) "
              + "OPTIONAL MATCH (f:File {repo_id: r.id}) "
              + "WITH r, c, count(DISTINCT f) AS fileCount "
              + "RETURN r.id AS repoId, "
              + "       r.source_root AS sourceRoot, "
              + "       collect(DISTINCT c.sha) AS commits, "
              + "       fileCount "
              + "ORDER BY repoId")) {
            while (res.hasNext()) {
                CRecord rec = res.next();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("repoId", rec.get("repoId").asString(""));
                m.put("sourceRoot", rec.get("sourceRoot").asString(""));
                m.put("commits", rec.get("commits").asList(CValue::asString));
                m.put("fileCount", rec.get("fileCount").asInt(0));
                out.add(m);
            }
        } catch (Throwable t) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
            out.add(err);
        }
        return out;
    }

    private static final List<String> CONSTRAINT_NAMES = List.of(
        "repo_id", "commit_sha", "package_name", "file_pk", "class_fqn",
        "method_fqn", "field_fqn", "rest_url", "dbtable_name", "dbcolumn_key",
        "htmlpage_file", "jsfile_path", "csfile_path", "tasktype_id", "msgconst_value",
        "spisvc_fqn", "psscript_name", "testcase_id", "testsuite_name", "hbstemplate_path",
        "notificationtype_id", "emailtemplate_id", "auditcategory_id", "scheduledtask_fqn",
        "eventtype_fqn", "property_key", "featureflag_id", "permission_id", "validator_id",
        "externalsystem_id", "logchannel_name", "state_key", "requestparam_name",
        "orchprofile_id"
        // appuser_username is intentionally excluded — :AppUser nodes are preserved for login
    );

    /**
     * DELETE /api/v1/repos/all — wipe the ENTIRE graph. Equivalent to
     * {@code impact wipe --all}. Destructive; client should confirm.
     */
    @DeleteMapping("/all")
    public ResponseEntity<?> wipeAll() {
        long deleted;
        long constraintsDropped = 0;
        try {
            try (CResult r = writer.session().run(
                    "MATCH (n) WHERE NOT n:AppUser WITH n LIMIT 1000000 DETACH DELETE n RETURN count(n) AS n")) {
                deleted = r.hasNext() ? r.next().get("n").asLong(0L) : 0L;
            }
            for (String name : CONSTRAINT_NAMES) {
                try (CResult r = writer.session().run("DROP CONSTRAINT " + name + " IF EXISTS")) {
                    r.consume();
                    constraintsDropped++;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", "all");
        body.put("nodesDeleted", deleted);
        body.put("constraintsDropped", constraintsDropped);
        return ResponseEntity.ok(body);
    }

    /**
     * DELETE /api/v1/repos/{repoId} — wipe one repo's graph slice:
     * <ul>
     *   <li>Classes contained by that repo's files</li>
     *   <li>Methods/fields contained by those classes, with all attached edges</li>
     *   <li>Files, repo node, and unshared commits</li>
     *   <li>Any now-orphaned non-auth boundary/config nodes</li>
     * </ul>
     *
     * <p>Class/Method/Field nodes are FQN-keyed. If two repos intentionally share the
     * same FQN node, deleting one repo will remove that shared node and its edges too.
     * Use {@code DELETE /all} for a full database reset.
     */
    @DeleteMapping("/{repoId}")
    public ResponseEntity<?> wipeRepo(@PathVariable("repoId") String repoId) {
        if (repoId == null || repoId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "repoId is required"));
        }
        long files = 0L, repos = 0L, commits = 0L, symbols = 0L, stubs = 0L, orphans = 0L;
        try {
            // Step 1: Delete all Class/Method/Field owned by this repo's commits.
            // commit_sha catches source symbols at any depth (inner/nested classes, etc.)
            // that lack a direct File-[:CONTAINS]->Class edge.
            try (CResult r = writer.session().run(
                "MATCH (rp:Repo {id: $r})-[:HAS_SNAPSHOT]->(c:Commit) "
              + "WITH collect(c.sha) AS shas "
              + "MATCH (n) WHERE (n:Class OR n:Method OR n:Field) AND n.commit_sha IN shas "
              + "WITH n LIMIT 1000000 DETACH DELETE n RETURN count(n) AS n",
                Map.of("r", repoId))) {
                symbols = r.hasNext() ? r.next().get("n").asLong(0L) : 0L;
            }
            // Step 1b: Remove stub Method nodes (no commit_sha, no Class parent) created
            // by table/boundary resolvers via MERGE. They lose meaning once the repo is gone.
            try (CResult r = writer.session().run(
                "MATCH (m:Method) WHERE m.commit_sha IS NULL AND NOT ()-[:CONTAINS]->(m) "
              + "WITH m LIMIT 1000000 DETACH DELETE m RETURN count(m) AS n")) {
                stubs += r.hasNext() ? r.next().get("n").asLong(0L) : 0L;
            }
            // Step 1c: Remove stub Class nodes (no commit_sha, no container parent) left
            // by writeExposes/writeExtends/writeInjects resolvers. Must run before the
            // RestEndpoint sweep — these stubs are the SOURCES of EXPOSES edges, so
            // RestEndpoints still appear "referenced" until the stubs are deleted first.
            try (CResult r = writer.session().run(
                "MATCH (c:Class) WHERE c.commit_sha IS NULL AND NOT ()-[:CONTAINS]->(c) "
              + "WITH c LIMIT 1000000 DETACH DELETE c RETURN count(c) AS n")) {
                stubs += r.hasNext() ? r.next().get("n").asLong(0L) : 0L;
            }
            // Step 1d: Remove DbTable nodes no longer referenced by any Method. Their
            // DbColumn children become edge-free after DETACH DELETE and are caught by
            // the orphan sweep at the end.
            try (CResult r = writer.session().run(
                "MATCH (t:DbTable) "
              + "WHERE NOT ()-[:READS_TABLE]->(t) AND NOT ()-[:WRITES_TABLE]->(t) "
              + "WITH t LIMIT 1000000 DETACH DELETE t RETURN count(t) AS n")) {
                stubs += r.hasNext() ? r.next().get("n").asLong(0L) : 0L;
            }
            // Step 1e: Remove RestEndpoint nodes no longer exposed by any Class or Method.
            // Runs after stub Class deletion so all EXPOSES sources are already gone.
            try (CResult r = writer.session().run(
                "MATCH (ep:RestEndpoint) WHERE NOT ()-[:EXPOSES]->(ep) "
              + "WITH ep LIMIT 1000000 DETACH DELETE ep RETURN count(ep) AS n")) {
                stubs += r.hasNext() ? r.next().get("n").asLong(0L) : 0L;
            }
            try (CResult r = writer.session().run(
                "MATCH (f:File {repo_id: $r}) DETACH DELETE f RETURN count(f) AS n",
                Map.of("r", repoId))) {
                files = r.hasNext() ? r.next().get("n").asLong(0L) : 0L;
            }
            // Drop commits that aren't shared with any other :Repo. OPTIONAL MATCH
            // form for portability across Neo4j 4.x / 5.x EXISTS-subquery syntaxes.
            try (CResult r = writer.session().run(
                "MATCH (rp:Repo {id: $r})-[:HAS_SNAPSHOT]->(c:Commit) "
              + "OPTIONAL MATCH (other:Repo)-[:HAS_SNAPSHOT]->(c) "
              + "WHERE other.id IS NOT NULL AND other.id <> $r "
              + "WITH c, count(other) AS sharedWith "
              + "WHERE sharedWith = 0 "
              + "DETACH DELETE c "
              + "RETURN count(c) AS n",
                Map.of("r", repoId))) {
                commits = r.hasNext() ? r.next().get("n").asLong(0L) : 0L;
            }
            try (CResult r = writer.session().run(
                "MATCH (rp:Repo {id: $r}) DETACH DELETE rp RETURN count(rp) AS n",
                Map.of("r", repoId))) {
                repos = r.hasNext() ? r.next().get("n").asLong(0L) : 0L;
            }
            // Remove boundary/config nodes that were only linked to the deleted repo slice.
            // Preserve auth users and remaining repo/snapshot bookkeeping.
            try (CResult r = writer.session().run(
                "MATCH (n) "
              + "WHERE NOT n:AppUser AND NOT n:Repo AND NOT n:Commit AND NOT (n)--() "
              + "WITH n LIMIT 1000000 "
              + "DETACH DELETE n "
              + "RETURN count(n) AS n")) {
                orphans = r.hasNext() ? r.next().get("n").asLong(0L) : 0L;
            }
        } catch (Throwable t) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", t.getClass().getSimpleName() + ": " + t.getMessage()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("scope", "repo");
        body.put("repoId", repoId);
        body.put("symbolsDeleted", symbols);
        body.put("stubNodesDeleted", stubs);
        body.put("filesDeleted", files);
        body.put("commitsDeleted", commits);
        body.put("repoNodesDeleted", repos);
        body.put("orphanNodesDeleted", orphans);
        return ResponseEntity.ok(body);
    }
}
