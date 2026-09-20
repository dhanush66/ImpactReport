package io.spmp.impact.graph;

import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.graph.txn.CypherClient;
import io.spmp.impact.graph.txn.CypherClient.CResult;
import io.spmp.impact.model.GraphEdges.*;
import io.spmp.impact.model.GraphNodes.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Thin wrapper around the Cypher client. UNWIND-batched MERGE for fast ingest. */
public class Neo4jWriter implements AutoCloseable {

    /** Batch size for UNWIND chunks. Empirically ~5000 is a sweet spot for the embedded driver. */
    private static final int BATCH = 5000;

    private final CypherClient client;

    public Neo4jWriter(String uri, String user, String pass) {
        this.client = CypherClient.open(uri, user, pass);
    }

    /**
     * Returns the underlying Cypher client. Callers must NOT close it —
     * the writer owns its lifecycle and closes it from {@link #close()}.
     */
    public CypherClient session() { return client; }

    public void upsertRepoAndCommit(String repoId, String commitSha) {
        upsertRepoAndCommit(repoId, commitSha, null);
    }

    /**
     * Same as {@link #upsertRepoAndCommit(String, String)} but also stamps a
     * {@code source_root} property on the :Repo node so the web UI can populate
     * its repo picker without making the user retype the disk path.
     *
     * <p>If {@code sourceRoot} is null/blank the existing property (if any) is
     * preserved — useful when an analyze-only flow upserts the repo without
     * knowing the original ingest path.
     */
    public void upsertRepoAndCommit(String repoId, String commitSha, String sourceRoot) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("repo", repoId);
        params.put("sha", commitSha);
        params.put("src", sourceRoot);
        try (CResult r = client.run(
            "MERGE (r:Repo {id: $repo}) "
          + "  ON CREATE SET r.source_root = $src "
          + "  ON MATCH  SET r.source_root = COALESCE($src, r.source_root) "
          + "MERGE (c:Commit {sha: $sha}) "
          + "MERGE (r)-[:HAS_SNAPSHOT]->(c)",
            params)) {
            r.consume();
        }
    }

    /**
     * Load (file path → SHA-1 content hash) for an existing snapshot. Used by
     * {@code ingest --incremental} to skip files whose content hasn't changed.
     * Returns an empty map for first-time ingests.
     */
    public Map<String, String> loadFileHashes(String commitSha) {
        Map<String, String> out = new HashMap<>();
        try (CResult result = client.run(
            "MATCH (f:File {commit_sha: $sha}) RETURN f.path AS path, f.content_hash AS hash",
            Map.of("sha", commitSha))) {
            while (result.hasNext()) {
                var rec = result.next();
                String path = rec.get("path").asString(null);
                String hash = rec.get("hash").asString(null);
                if (path != null && hash != null && !hash.isEmpty()) {
                    out.put(path, hash);
                }
            }
        }
        return out;
    }

    public void writeBatch(ExtractionBatch batch) {
        // Packages first (derived from class list)
        Set<String> pkgs = new HashSet<>();
        for (ClassNode c : batch.classes) pkgs.add(c.pkg());
        writePackages(pkgs);

        writeFiles(batch.files);
        writeClasses(batch.classes, batch.commitSha);
        writeMethods(batch.methods, batch.commitSha);
        writeFields(batch.fields);

        writeClassFileEdges(batch.classToFile);
        writeExtends(batch.extendsEdges);
        writeImplements(batch.implementsEdges);
        writeOverrides(batch.overrides);
        writeCalls(batch.calls);
        writeFieldAccess(batch.fieldAccess);

        // P4 boundary nodes/edges
        writeTaskTypes(batch.taskTypes);
        writeRestEndpoints(batch.restEndpoints);
        writeHandles(batch.handles);
        writeDispatchesTo(batch.dispatchesTo);
        writeThreadStartEdges(batch.threadStartEdges);
        writeExposes(batch.exposes);

        // P5 boundary nodes/edges
        writeDbTables(batch.dbTables);
        writePsScripts(batch.psScripts);
        writeMessageConstants(batch.messageConstants);
        writeDbTableEdges(batch.dbTableEdges);
        writeInvokesScript(batch.invokesScript);
        writeMessageConstantEdges(batch.messageConstantEdges);

        // L1b: DB schema nodes/edges
        writeDbColumns(batch.dbColumns);
        writeHasColumnEdges(batch.hasColumn);

        // L2: HTML pages
        writeHtmlPages(batch.htmlPages);
        writeHtmlReferences(batch.htmlReferences);

        // L4: JS/Ember
        writeJsFiles(batch.jsFiles);
        writeJsCallsApi(batch.jsCallsApi);

        // L6: C#
        writeCsFiles(batch.csFiles);
        writeCsCallsApi(batch.csCallsApi);
        writeCsInvokesScript(batch.csInvokesScript);

        // L9 (PD-4): HBS templates + JS imports + JS-renders-template
        writeHbsTemplates(batch.hbsTemplates);
        writeHbsUsesComponent(batch.hbsUsesComponent);
        writeJsImports(batch.jsImports);
        writeJsRendersTemplate(batch.jsRendersTemplate);

        // §4.1: boundary-edge promotion (N1–M1, N4–N6)
        writeNotificationTypes(batch.notificationTypes);
        writeEmailTemplates(batch.emailTemplates);
        writeAuditCategories(batch.auditCategories);
        writeScheduledTasks(batch.scheduledTasks);
        writeEventTypes(batch.eventTypes);
        writeProperties(batch.properties);
        writeFeatureFlags(batch.featureFlags);
        writePermissions(batch.permissions);
        writeValidators(batch.validators);
        writeExternalSystems(batch.externalSystems);
        writeLogChannels(batch.logChannels);
        writeStates(batch.states);
        writeSendsNotification(batch.sendsNotification);
        writeSendsEmail(batch.sendsEmail);
        writeWritesAudit(batch.writesAudit);
        writeSchedules(batch.schedules);
        writeSchedulesToMethod(batch.schedulesToMethod);
        writeCancelsSchedule(batch.cancelsSchedule);
        writePublishesEvent(batch.publishesEvent);
        writeListensFor(batch.listensFor);
        writeInstantiatesHandler(batch.instantiatesHandler);
        writeReadsProperty(batch.readsProperty);
        writeGatedBy(batch.gatedBy);
        writeRequiresPermission(batch.requiresPermission);
        writeValidatesInput(batch.validatesInput);
        writeCallsExternal(batch.callsExternal);
        writeWritesLog(batch.writesLog);
        writeTransitionsState(batch.transitionsState);
        writeInstantiates(batch.instantiates);
        writeSingletonOf(batch.singletonOf);
        writeInjects(batch.injects);
        writeRequestParams(batch.requestParams);
        writeReadsParam(batch.readsParam);
        writeOrchestrationProfiles(batch.orchestrationProfiles);
        writeTriggersOrchestration(batch.triggersOrchestration);
        writeUserSchedules(batch.userSchedules);
        // Convert any remaining legacy gatesDispatch edges into unified INSTANTIATES
        if (!batch.gatesDispatch.isEmpty()) {
            List<io.spmp.impact.model.GraphEdges.InstantiatesEdge> converted = new ArrayList<>();
            for (var gd : batch.gatesDispatch) converted.add(gd.toInstantiatesEdge());
            writeInstantiates(converted);
        }
        writeHandlesAttribute(batch.handlesAttribute);
    }

    // ─── L9 / PD-4 writers ────────────────────────────────────────────

    private void writeHbsTemplates(List<io.spmp.impact.model.GraphNodes.HbsTemplateNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        Map<String, List<String>> byRole = new HashMap<>();
        for (var n : nodes) {
            rows.add(Map.of(
                "path",      n.path(),
                "simple",    n.simpleName(),
                "role",      n.role() == null ? "Other" : n.role(),
                "filePath",  n.filePath() == null ? "" : n.filePath()
            ));
            byRole.computeIfAbsent(n.role() == null ? "Other" : n.role(), k -> new ArrayList<>()).add(n.path());
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (h:HbsTemplate {path: row.path}) " +
            "  SET h.simple_name = row.simple, h.role = row.role, h.file_path = row.filePath"
        );
        for (var e : byRole.entrySet()) {
            String label = sanitizeLabel("Hbs" + e.getKey());
            if (label == null) continue;
            List<Map<String, Object>> subset = new ArrayList<>();
            for (String p : e.getValue()) subset.add(Map.of("path", p));
            chunked(subset,
                "UNWIND $rows AS row MATCH (h:HbsTemplate {path: row.path}) SET h:" + label);
        }
    }

    private void writeHbsUsesComponent(List<io.spmp.impact.model.GraphEdges.HbsUsesComponentEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("hbs", e.hbsPath(), "js", e.jsPath()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MATCH (h:HbsTemplate {path: row.hbs}) " +
            "MATCH (j:JsFile {path: row.js}) " +
            "MERGE (h)-[:USES_COMPONENT]->(j)"
        );
    }

    private void writeJsImports(List<io.spmp.impact.model.GraphEdges.JsImportsEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("from", e.fromJsPath(), "to", e.toJsPath()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MATCH (a:JsFile {path: row.from}) " +
            "MATCH (b:JsFile {path: row.to}) " +
            "MERGE (a)-[:IMPORTS]->(b)"
        );
    }

    private void writeJsRendersTemplate(List<io.spmp.impact.model.GraphEdges.JsRendersTemplateEdge> edges) {
        // Best-effort: only emit if the target HBS exists. We use OPTIONAL MATCH instead of
        // requiring the node, so we don't fabricate missing :HbsTemplate placeholders.
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("js", e.jsPath(), "hbs", e.hbsPath()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MATCH (j:JsFile {path: row.js}) " +
            "WITH j, row " +
            "MATCH (h:HbsTemplate {path: row.hbs}) " +
            "MERGE (j)-[:RENDERS_TEMPLATE]->(h)"
        );
    }

    private void writeHtmlPages(List<io.spmp.impact.model.GraphNodes.HtmlPageNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) {
            rows.add(Map.of(
                "filename", n.filename(),
                "title",    n.title()    == null ? "" : n.title(),
                "filePath", n.filePath() == null ? "" : n.filePath()
            ));
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (p:HtmlPage {filename: row.filename}) " +
            "  SET p.title = row.title, p.file_path = row.filePath"
        );
    }

    private void writeHtmlReferences(List<io.spmp.impact.model.GraphEdges.HtmlPageReferencesEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("filename", e.pageFilename(), "url", e.restUrl()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MATCH (p:HtmlPage {filename: row.filename}) " +
            "MATCH (r:RestEndpoint) " +
            "  WHERE r.url = row.url OR r.url STARTS WITH (row.url + '?') " +
            "MERGE (p)-[:REFERENCES]->(r)"
        );
    }

    // ─── L4 JS files + role labels ────────────────────────────────────

    private void writeJsFiles(List<io.spmp.impact.model.GraphNodes.JsFileNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        Map<String, List<String>> byRole = new HashMap<>();
        for (var n : nodes) {
            rows.add(Map.of(
                "path",      n.path(),
                "simple",    n.simpleName(),
                "role",      n.role(),
                "filePath",  n.filePath() == null ? "" : n.filePath()
            ));
            byRole.computeIfAbsent(n.role(), k -> new ArrayList<>()).add(n.path());
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (j:JsFile {path: row.path}) " +
            "  SET j.simple_name = row.simple, j.role = row.role, j.file_path = row.filePath"
        );
        // Apply role as a sub-label (Js + role, e.g. :JsRoute / :JsController / :JsModel)
        for (var e : byRole.entrySet()) {
            String label = sanitizeLabel("Js" + e.getKey());
            if (label == null) continue;
            List<Map<String, Object>> subset = new ArrayList<>();
            for (String p : e.getValue()) subset.add(Map.of("path", p));
            chunked(subset,
                "UNWIND $rows AS row MATCH (j:JsFile {path: row.path}) SET j:" + label);
        }
    }

    // ─── L6 C# files ─────────────────────────────────────────────────

    private void writeCsFiles(List<io.spmp.impact.model.GraphNodes.CsFileNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        Map<String, List<String>> byRole = new HashMap<>();
        for (var n : nodes) {
            rows.add(Map.of(
                "path",     n.path(),
                "simple",   n.simpleName(),
                "role",     n.role(),
                "filePath", n.filePath() == null ? "" : n.filePath()
            ));
            byRole.computeIfAbsent(n.role(), k -> new ArrayList<>()).add(n.path());
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (c:CsFile {path: row.path}) " +
            "  SET c.simple_name = row.simple, c.role = row.role, c.file_path = row.filePath"
        );
        for (var e : byRole.entrySet()) {
            String label = sanitizeLabel("Cs" + e.getKey());
            if (label == null) continue;
            List<Map<String, Object>> subset = new ArrayList<>();
            for (String p : e.getValue()) subset.add(Map.of("path", p));
            chunked(subset,
                "UNWIND $rows AS row MATCH (c:CsFile {path: row.path}) SET c:" + label);
        }
    }

    private void writeCsCallsApi(List<io.spmp.impact.model.GraphEdges.CsCallsApiEdge> edges) {
        // Split into two groups:
        //   internal — SPMP URLs that should already exist as :RestEndpoint (pre-created
        //              by RestApiXmlResolver / ServletResolver). MATCH-only to avoid noise.
        //   external — SharePoint / Graph / etc. URLs prefixed with "external:". MERGE so
        //              they get materialised the first time a C# file references them.
        List<Map<String, Object>> internal = new ArrayList<>();
        List<Map<String, Object>> external = new ArrayList<>();
        for (var e : edges) {
            Map<String, Object> row = Map.of("path", e.csFilePath(), "url", e.restUrl());
            if (e.restUrl() != null && e.restUrl().startsWith("external:")) external.add(row);
            else internal.add(row);
        }
        chunked(internal,
            "UNWIND $rows AS row " +
            "MATCH (c:CsFile {path: row.path}) " +
            "MATCH (r:RestEndpoint) " +
            "  WHERE r.url = row.url OR r.url STARTS WITH (row.url + '?') " +
            "MERGE (c)-[:CALLS_API]->(r)"
        );
        chunked(external,
            "UNWIND $rows AS row " +
            "MATCH (c:CsFile {path: row.path}) " +
            "MERGE (r:RestEndpoint {url: row.url}) " +
            "  ON CREATE SET r.class_fqn = '' " +
            "MERGE (c)-[:CALLS_API]->(r)"
        );
    }

    private void writeCsInvokesScript(List<io.spmp.impact.model.GraphEdges.CsInvokesScriptEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("path", e.csFilePath(), "script", e.scriptName()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MATCH (c:CsFile {path: row.path}) " +
            "MERGE (p:PsScript {name: row.script}) " +
            "MERGE (c)-[:INVOKES_SCRIPT]->(p)"
        );
    }

    private void writeJsCallsApi(List<io.spmp.impact.model.GraphEdges.JsCallsApiEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("path", e.jsFilePath(), "url", e.restUrl()));
        // Two-pass strategy:
        //   1. Link to any existing :RestEndpoint with the URL OR url+'?…' suffix variant
        //      (covers XML-declared URLs with operation qualifiers).
        //   2. For any URL that didn't match in step 1, materialise a bare :RestEndpoint
        //      so the edge exists. Catches URLs that aren't declared in any XML config
        //      we parse — e.g. ADSM's /api/json/... endpoints registered via
        //      @WebServlet annotations rather than ADSProductAPIs.xml.
        chunked(rows,
            "UNWIND $rows AS row " +
            "MATCH (j:JsFile {path: row.path}) " +
            "OPTIONAL MATCH (existing:RestEndpoint) " +
            "  WHERE existing.url = row.url OR existing.url STARTS WITH (row.url + '?') " +
            "WITH j, row, collect(DISTINCT existing) AS existings " +
            "FOREACH (e IN existings | MERGE (j)-[:CALLS_API]->(e)) " +
            "FOREACH (_ IN CASE WHEN size(existings) = 0 THEN [1] ELSE [] END | " +
            "  MERGE (r:RestEndpoint {url: row.url}) " +
            "    ON CREATE SET r.class_fqn = '' " +
            "  MERGE (j)-[:CALLS_API]->(r) " +
            ")"
        );
    }

    private void writeDbColumns(List<io.spmp.impact.model.GraphNodes.DbColumnNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var c : nodes) {
            Map<String, Object> r = new HashMap<>();
            r.put("table", c.table());
            r.put("name", c.name());
            r.put("dataType", c.dataType() == null ? "" : c.dataType());
            r.put("maxSize", c.maxSize() == null ? -1 : c.maxSize());
            r.put("nullable", c.nullable());
            r.put("pkLike", c.pkLike());
            rows.add(r);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (col:DbColumn {table: row.table, name: row.name}) " +
            "  SET col.data_type = row.dataType, " +
            "      col.max_size  = row.maxSize, " +
            "      col.nullable  = row.nullable, " +
            "      col.pk_like   = row.pkLike"
        );
    }

    private void writeHasColumnEdges(List<io.spmp.impact.model.GraphEdges.HasColumnEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("table", e.tableName(), "col", e.columnName()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (t:DbTable {name: row.table}) " +
            "MERGE (c:DbColumn {table: row.table, name: row.col}) " +
            "MERGE (t)-[:HAS_COLUMN]->(c)"
        );
    }

    // ─── P5 boundary writes ───────────────────────────────────────────

    private void writeDbTables(List<io.spmp.impact.model.GraphNodes.DbTableNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("name", n.name()));
        chunked(rows, "UNWIND $rows AS row MERGE (t:DbTable {name: row.name})");
    }

    private void writePsScripts(List<io.spmp.impact.model.GraphNodes.PsScriptNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("name", n.name()));
        chunked(rows, "UNWIND $rows AS row MERGE (p:PsScript {name: row.name})");
    }

    private void writeMessageConstants(List<io.spmp.impact.model.GraphNodes.MessageConstantNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("value", n.value(), "owner", n.ownerFqn() == null ? "" : n.ownerFqn()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:MessageConstant {value: row.value}) " +
            "  ON CREATE SET m.owner_fqn = row.owner"
        );
    }

    private void writeDbTableEdges(List<io.spmp.impact.model.GraphEdges.DbTableEdge> edges) {
        List<Map<String, Object>> reads = new ArrayList<>();
        List<Map<String, Object>> writes = new ArrayList<>();
        for (var e : edges) {
            String[] fromParts = splitMethodFqn(e.fromMethodFqn());
            Map<String, Object> row = new HashMap<>();
            row.put("from", e.fromMethodFqn());
            row.put("fromOwner", fromParts[0]);
            row.put("fromSimple", fromParts[1]);
            row.put("table", e.tableName());
            if (e.write()) writes.add(row); else reads.add(row);
        }
        chunked(reads,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " +
            "  ON CREATE SET m.simple_name = row.fromSimple, m.owner_fqn = row.fromOwner " +
            "MERGE (t:DbTable {name: row.table}) " +
            "MERGE (m)-[:READS_TABLE]->(t)"
        );
        chunked(writes,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " +
            "  ON CREATE SET m.simple_name = row.fromSimple, m.owner_fqn = row.fromOwner " +
            "MERGE (t:DbTable {name: row.table}) " +
            "MERGE (m)-[:WRITES_TABLE]->(t)"
        );
    }

    private void writeInvokesScript(List<io.spmp.impact.model.GraphEdges.InvokesScriptEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) {
            String[] fromParts = splitMethodFqn(e.fromMethodFqn());
            Map<String, Object> row = new HashMap<>();
            row.put("from", e.fromMethodFqn());
            row.put("fromOwner", fromParts[0]);
            row.put("fromSimple", fromParts[1]);
            row.put("script", e.scriptName());
            rows.add(row);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " +
            "  ON CREATE SET m.simple_name = row.fromSimple, m.owner_fqn = row.fromOwner " +
            "MERGE (p:PsScript {name: row.script}) " +
            "MERGE (m)-[:INVOKES_SCRIPT]->(p)"
        );
    }

    private void writeMessageConstantEdges(List<io.spmp.impact.model.GraphEdges.MessageConstantEdge> edges) {
        List<Map<String, Object>> sends = new ArrayList<>();
        List<Map<String, Object>> recvs = new ArrayList<>();
        for (var e : edges) {
            String[] fromParts = splitMethodFqn(e.fromMethodFqn());
            Map<String, Object> row = new HashMap<>();
            row.put("from", e.fromMethodFqn());
            row.put("fromOwner", fromParts[0]);
            row.put("fromSimple", fromParts[1]);
            row.put("value", e.constantValue());
            (e.sending() ? sends : recvs).add(row);
        }
        chunked(sends,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " +
            "  ON CREATE SET m.simple_name = row.fromSimple, m.owner_fqn = row.fromOwner " +
            "MERGE (c:MessageConstant {value: row.value}) " +
            "MERGE (m)-[:SENDS_MESSAGE]->(c)"
        );
        chunked(recvs,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " +
            "  ON CREATE SET m.simple_name = row.fromSimple, m.owner_fqn = row.fromOwner " +
            "MERGE (c:MessageConstant {value: row.value}) " +
            "MERGE (m)-[:RECEIVES_MESSAGE]->(c)"
        );
    }

    // ─── P4 boundary writes ───────────────────────────────────────────

    private void writeTaskTypes(List<io.spmp.impact.model.GraphNodes.TaskTypeNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("id", n.id()));
        chunked(rows, "UNWIND $rows AS row MERGE (t:TaskType {id: row.id})");
    }

    private void writeRestEndpoints(List<io.spmp.impact.model.GraphNodes.RestEndpointNode> nodes) {
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("url", n.url(), "class_fqn", n.classFqn() == null ? "" : n.classFqn()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (r:RestEndpoint {url: row.url}) " +
            "  ON CREATE SET r.class_fqn = row.class_fqn"
        );
    }

    private void writeHandles(List<io.spmp.impact.model.GraphEdges.HandlesEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("class_fqn", e.handlerClassFqn(), "task_id", e.taskTypeId()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (c:Class {fqn: row.class_fqn}) " +
            "MERGE (t:TaskType {id: row.task_id}) " +
            "MERGE (c)-[:HANDLES]->(t)"
        );
    }

    private void writeThreadStartEdges(List<io.spmp.impact.model.GraphEdges.ThreadStartEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) {
            String[] fromParts = splitMethodFqn(e.fromMethodFqn());
            String[] toParts   = splitMethodFqn(e.toRunMethodFqn());
            Map<String, Object> row = new HashMap<>();
            row.put("from",      e.fromMethodFqn());
            row.put("to",        e.toRunMethodFqn());
            row.put("fromOwner", fromParts[0]);
            row.put("fromSimple",fromParts[1]);
            row.put("toOwner",   toParts[0]);
            row.put("toSimple",  toParts[1]);
            rows.add(row);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (a:Method {fqn: row.from}) " +
            "  ON CREATE SET a.simple_name = row.fromSimple, a.owner_fqn = row.fromOwner " +
            "MERGE (b:Method {fqn: row.to}) " +
            "  ON CREATE SET b.simple_name = row.toSimple,   b.owner_fqn = row.toOwner " +
            "MERGE (a)-[:STARTS_THREAD]->(b)"
        );
    }

    private void writeDispatchesTo(List<io.spmp.impact.model.GraphEdges.DispatchesToEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) {
            String[] toParts = splitMethodFqn(e.toMethodFqn());
            String[] fromParts = splitMethodFqn(e.fromMethodFqn());
            Map<String, Object> row = new HashMap<>();
            row.put("from", e.fromMethodFqn());
            row.put("to", e.toMethodFqn());
            row.put("fromOwner", fromParts[0]);
            row.put("fromSimple", fromParts[1]);
            row.put("toOwner", toParts[0]);
            row.put("toSimple", toParts[1]);
            rows.add(row);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (a:Method {fqn: row.from}) " +
            "  ON CREATE SET a.simple_name = row.fromSimple, a.owner_fqn = row.fromOwner " +
            "MERGE (b:Method {fqn: row.to}) " +
            "  ON CREATE SET b.simple_name = row.toSimple,   b.owner_fqn = row.toOwner " +
            "MERGE (a)-[:DISPATCHES_TO]->(b)"
        );
    }

    private void writeExposes(List<io.spmp.impact.model.GraphEdges.ExposesEdge> edges) {
        // Split into method-granularity (target_method_simple_name non-empty) and class-granularity
        List<Map<String, Object>> methodRows = new ArrayList<>();
        List<Map<String, Object>> classRows  = new ArrayList<>();
        for (var e : edges) {
            String tgt = e.targetMethodSimpleName() == null ? "" : e.targetMethodSimpleName();
            if (!tgt.isEmpty()) {
                methodRows.add(Map.of(
                    "class_fqn", e.classFqn(),
                    "url",       e.url(),
                    "tgt_simple", tgt
                ));
            } else {
                classRows.add(Map.of(
                    "class_fqn", e.classFqn(),
                    "url",       e.url()
                ));
            }
        }
        // Method-granularity: (Method)-[:EXPOSES]->(RestEndpoint)
        // Uses OPTIONAL MATCH + fallback to Class when the Method node doesn't exist
        // (e.g. XML resolved a method name but the Java file wasn't in the parse scope).
        if (!methodRows.isEmpty()) {
            chunked(methodRows,
                "UNWIND $rows AS row " +
                "MERGE (r:RestEndpoint {url: row.url}) " +
                "WITH row, r " +
                "OPTIONAL MATCH (m:Method {owner_fqn: row.class_fqn, simple_name: row.tgt_simple}) " +
                "WITH row, r, collect(m)[0] AS mFound " +
                "FOREACH (_ IN CASE WHEN mFound IS NOT NULL THEN [1] ELSE [] END | " +
                "  MERGE (mFound)-[:EXPOSES]->(r) ) " +
                "FOREACH (_ IN CASE WHEN mFound IS NULL THEN [1] ELSE [] END | " +
                "  MERGE (c:Class {fqn: row.class_fqn}) " +
                "  MERGE (c)-[:EXPOSES]->(r) )"
            );
        }
        // Class-granularity fallback: (Class)-[:EXPOSES]->(RestEndpoint)
        if (!classRows.isEmpty()) {
            chunked(classRows,
                "UNWIND $rows AS row " +
                "MERGE (c:Class {fqn: row.class_fqn}) " +
                "MERGE (r:RestEndpoint {url: row.url}) " +
                "MERGE (c)-[:EXPOSES]->(r)"
            );
        }
    }

    // ─── nodes ────────────────────────────────────────────────────────

    private void writePackages(Set<String> pkgs) {
        List<Map<String, Object>> rows = new ArrayList<>(pkgs.size());
        for (String p : pkgs) rows.add(Map.of("name", p == null ? "" : p));
        chunked(rows, "UNWIND $rows AS row MERGE (p:Package {name: row.name})");
    }

    private void writeFiles(List<FileNode> files) {
        List<Map<String, Object>> rows = new ArrayList<>(files.size());
        for (FileNode f : files) {
            rows.add(Map.of(
                "path", f.path(),
                "pkg", f.pkg() == null ? "" : f.pkg(),
                "repo", f.repoId(),
                "sha", f.commitSha(),
                "hash", f.contentHash() == null ? "" : f.contentHash()
            ));
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (f:File {path: row.path, commit_sha: row.sha}) " +
            "  SET f.repo_id = row.repo, f.content_hash = row.hash, f.pkg = row.pkg " +
            "WITH f, row " +
            "MATCH (p:Package {name: row.pkg}) " +
            "MERGE (p)-[:CONTAINS]->(f) " +
            "WITH f, row " +
            "MATCH (c:Commit {sha: row.sha}) " +
            "MERGE (f)-[:IN_COMMIT]->(c)"
        );
    }

    private void writeClasses(List<ClassNode> classes, String commitSha) {
        List<Map<String, Object>> rows = new ArrayList<>(classes.size());
        for (ClassNode c : classes) {
            Map<String, Object> m = new HashMap<>();
            m.put("fqn", c.fqn());
            m.put("simple", c.simpleName());
            m.put("pkg", c.pkg() == null ? "" : c.pkg());
            m.put("file", c.filePath());
            m.put("isInterface", c.isInterface());
            m.put("isAbstract", c.isAbstract());
            m.put("start", c.startLine());
            m.put("end", c.endLine());
            m.put("extras", c.extraLabels() == null ? List.of() : c.extraLabels());
            m.put("sha", commitSha);
            rows.add(m);
        }
        // Base label + dynamic sub-labels via apoc-free pattern: set each label via CALL { ... }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (c:Class {fqn: row.fqn}) " +
            "  SET c.simple_name = row.simple, " +
            "      c.pkg = row.pkg, " +
            "      c.file_path = row.file, " +
            "      c.is_interface = row.isInterface, " +
            "      c.is_abstract = row.isAbstract, " +
            "      c.start_line = row.start, " +
            "      c.end_line = row.end, " +
            "      c.commit_sha = row.sha, " +
            "      c.extra_labels = row.extras " +
            "FOREACH (_ IN CASE WHEN row.isInterface THEN [1] ELSE [] END | SET c:Interface) " +
            "WITH c, row " +
            "MATCH (p:Package {name: row.pkg}) " +
            "MERGE (p)-[:CONTAINS]->(c)"
        );

        // Apply extra labels in a second pass (Neo4j 5 supports dynamic labels via CALL).
        // Without APOC we MATCH + SET each label class-by-class.
        Map<String, List<String>> byLabel = new HashMap<>();
        for (ClassNode c : classes) {
            if (c.extraLabels() == null) continue;
            for (String label : c.extraLabels()) {
                byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(c.fqn());
            }
        }
        for (var e : byLabel.entrySet()) {
            String label = sanitizeLabel(e.getKey());
            if (label == null) continue;
            List<Map<String, Object>> rs = new ArrayList<>();
            for (String fqn : e.getValue()) rs.add(Map.of("fqn", fqn));
            chunked(rs, "UNWIND $rows AS row MATCH (c:Class {fqn: row.fqn}) SET c:" + label);
        }
    }

    private void writeMethods(List<MethodNode> methods, String commitSha) {
        List<Map<String, Object>> rows = new ArrayList<>(methods.size());
        for (MethodNode m : methods) {
            Map<String, Object> row = new HashMap<>();
            row.put("fqn", m.fqn());
            row.put("sig", m.signature());
            row.put("simple", m.simpleName());
            row.put("owner", m.ownerFqn());
            row.put("ret", m.returnType() == null ? "" : m.returnType());
            row.put("isStatic", m.isStatic());
            row.put("isCtor", m.isConstructor());
            row.put("start", m.startLine());
            row.put("end", m.endLine());
            row.put("extras", m.extraLabels() == null ? List.of() : m.extraLabels());
            row.put("sha", commitSha);
            rows.add(row);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.fqn}) " +
            "  SET m.signature = row.sig, " +
            "      m.simple_name = row.simple, " +
            "      m.owner_fqn = row.owner, " +
            "      m.return_type = row.ret, " +
            "      m.is_static = row.isStatic, " +
            "      m.is_constructor = row.isCtor, " +
            "      m.start_line = row.start, " +
            "      m.end_line = row.end, " +
            "      m.commit_sha = row.sha, " +
            "      m.extra_labels = row.extras " +
            "FOREACH (_ IN CASE WHEN row.isCtor THEN [1] ELSE [] END | SET m:Constructor) " +
            "WITH m, row " +
            "MATCH (c:Class {fqn: row.owner}) " +
            "MERGE (c)-[:CONTAINS]->(m)"
        );

        // Apply extra labels (EntryPoint etc.)
        Map<String, List<String>> byLabel = new HashMap<>();
        for (MethodNode m : methods) {
            if (m.extraLabels() == null) continue;
            for (String label : m.extraLabels()) {
                byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(m.fqn());
            }
        }
        for (var e : byLabel.entrySet()) {
            String label = sanitizeLabel(e.getKey());
            if (label == null) continue;
            List<Map<String, Object>> rs = new ArrayList<>();
            for (String fqn : e.getValue()) rs.add(Map.of("fqn", fqn));
            chunked(rs, "UNWIND $rows AS row MATCH (m:Method {fqn: row.fqn}) SET m:" + label);
        }
    }

    private void writeFields(List<FieldNode> fields) {
        List<Map<String, Object>> rows = new ArrayList<>(fields.size());
        for (FieldNode f : fields) {
            rows.add(Map.of(
                "fqn", f.fqn(),
                "simple", f.simpleName(),
                "owner", f.ownerFqn(),
                "type", f.type() == null ? "" : f.type(),
                // Task #98: store constant value for static-final fields. Empty for
                // non-constants so the property is present on every node (the post-pass
                // filters on `constant_value <> ''`).
                "constVal", f.constantValue() == null ? "" : f.constantValue()
            ));
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (f:Field {fqn: row.fqn}) " +
            "  SET f.simple_name = row.simple, f.owner_fqn = row.owner, f.type = row.type, " +
            "      f.constant_value = row.constVal " +
            "WITH f, row " +
            "MATCH (c:Class {fqn: row.owner}) " +
            "MERGE (c)-[:CONTAINS]->(f)"
        );
    }

    // ─── Task #98: ConstantIndex post-pass cleanup ──────────────────────

    /**
     * Reverse-map numeric edge ids on `:Permission` and `:AuditCategory` nodes to
     * symbolic names by joining against `:Field {constant_value: ...}` nodes. Runs
     * once at the END of ingest, after every batch is written (including streaming
     * flushes). Idempotent — re-running has no effect since the second pass finds
     * no numeric-id nodes that match (they've already been re-keyed to symbolic).
     *
     * <p>Algorithm:
     * <ol>
     *   <li>For each `:Permission` (and `:AuditCategory`) node whose id is purely
     *       numeric (a Long literal like {@code "1914"} or {@code "1914L"}).</li>
     *   <li>Find `:Field` nodes whose owner class's simple name ENDS WITH
     *       "Constants" (filter the haystack — random classes named for the constants
     *       value are unlikely to be the right semantic target) and whose
     *       {@code constant_value} matches.</li>
     *   <li>If EXACTLY ONE such field exists, MERGE a new node with the symbolic
     *       name, COPY all incoming :REQUIRES_PERMISSION / :WRITES_AUDIT edges, and
     *       DELETE the numeric-id node. Collisions (multiple Constants classes use
     *       the same numeric value) are left untouched — the numeric id stays so QA
     *       can disambiguate manually.</li>
     * </ol>
     * Reports the count of re-keyed nodes to stdout.
     */
    public void runConstantIndexCleanup() {
        // Permission edges → prefer fields in Action/Permission-suffixed Constants classes.
        runConstantIndexCleanupForLabel("Permission", "REQUIRES_PERMISSION",
            new String[] { "ActionConstants", "PermissionConstants", "ActionIds", "ActionIDs" });
        // Audit edges → prefer fields in *AuditConstants classes (e.g. AdminAuditConstants).
        runConstantIndexCleanupForLabel("AuditCategory", "WRITES_AUDIT",
            new String[] { "AuditConstants", "AuditCategoryConstants" });
    }

    /**
     * Bridges the Thread.start() → Thread.run() JVM dispatch gap that static analysis
     * cannot see. For every Thread subclass in the graph, any method that calls its
     * constructor ({@code <init>}) is also the effective dispatcher to its {@code run()}
     * method (since start() will dispatch to run() at runtime). Adds a synthetic
     * {@code DISPATCHES_TO} edge from the constructor-calling method to {@code run()}.
     *
     * <p>This is idempotent (uses MERGE) and must be called after all CALLS edges are
     * written. It enables backward-reach queries to traverse from a Thread subclass's
     * run() method up through the action method that creates and starts the thread.
     *
     * @return the number of new DISPATCHES_TO edges created (0 if already present)
     */
    /**
     * Removes stale synthetic DISPATCHES_TO edges that were created by the old
     * thread-dispatch bridge (constructor-caller → run()). Superseded by
     * ThreadStartResolver which now emits proper STARTS_THREAD edges instead.
     * Safe to call on a fresh graph (returns 0) or one that still has the old edges.
     */
    public long cleanupThreadDispatchBridgeEdges() {
        // Delete synthetic DISPATCHES_TO edges where the target is a Thread subclass run().
        // These were created by the former runThreadDispatchBridge() and are now replaced
        // by CALLS → STARTS_THREAD chains emitted by ThreadStartResolver.
        String cypher =
            "MATCH (threadClass:Class)-[:EXTENDS]->(parent) " +
            "WHERE parent.fqn = 'java.lang.Thread' " +
            "MATCH (threadClass)-[:CONTAINS]->(run:Method) " +
            "WHERE run.fqn ENDS WITH '.run()' " +
            "MATCH (caller:Method)-[d:DISPATCHES_TO]->(run) " +
            "WHERE d.synthetic = true AND d.reason IS NULL " +
            "DELETE d " +
            "RETURN count(d) AS deleted";
        try (CResult r = session().run(cypher)) {
            return r.hasNext() ? r.next().get("deleted").asLong() : 0L;
        }
    }

    /**
     * Bridges the virtual-dispatch gap for method overrides. When a caller calls a BASE
     * class method and a subclass OVERRIDES it, static analysis records the CALLS edge
     * to the base — but runtime dispatch goes to the override. This adds a synthetic
     * DISPATCHES_TO edge from each caller of a base method to all overriding methods.
     *
     * <p>Scoped to `:NotificationMacro` class methods to limit edge explosion while
     * covering the primary use case (parseMacroForAdmin base → WFNotificationMacro
     * override dispatch from NotificationTrigger.sendNotification).
     *
     * @return the number of new DISPATCHES_TO edges created (0 if already present)
     */
    public long runNotificationMacroVirtualDispatchBridge() {
        String cypher =
            "MATCH (macroClass:Class:NotificationMacro)-[:CONTAINS]->(override:Method) " +
            "MATCH (override)-[:OVERRIDES]->(base:Method) " +
            "MATCH (caller:Method)-[:CALLS]->(base) " +
            "MERGE (caller)-[d:DISPATCHES_TO]->(override) " +
            "ON CREATE SET d.synthetic = true, d.reason = 'virtual-dispatch-macro' " +
            "RETURN count(d) AS added";
        try (CResult r = session().run(cypher)) {
            return r.hasNext() ? r.next().get("added").asLong() : 0L;
        }
    }

    /**
     * For each numeric-id
     * node, the candidate-Field set is filtered to:
     *   • First-pass: fields whose owner-class simple name matches ANY of {@code preferredSuffixes}
     *     (e.g. for Permission edges, fields in {@code AdmAuditConstants} are dropped because
     *     they're audit-domain, not permission-domain).
     *   • Second-pass: if zero domain-preferred candidates, retry with any {@code *Constants}
     *     class (last-resort fallback so unambiguous matches still re-key).
     * The same disambiguation rule applies: only re-key when EXACTLY ONE candidate
     * survives the filter.
     */
    private void runConstantIndexCleanupForLabel(String nodeLabel, String edgeType, String[] preferredSuffixes) {
        // Build the suffix-match clause from the preference list.
        StringBuilder suffixClause = new StringBuilder();
        for (int i = 0; i < preferredSuffixes.length; i++) {
            if (i > 0) suffixClause.append(" OR ");
            suffixClause.append("f.owner_fqn ENDS WITH '").append(preferredSuffixes[i]).append("'");
        }
        try (CResult r = session().run(
            "MATCH (n:" + nodeLabel + ") " +
            "WHERE n.id =~ '^-?[0-9]+L?$' " +
            "WITH n, " +
            "     CASE WHEN n.id ENDS WITH 'L' THEN substring(n.id, 0, size(n.id) - 1) " +
            "          ELSE n.id END AS numVal " +
            // First-pass: domain-preferred Constants classes.
            "OPTIONAL MATCH (f:Field) " +
            "WHERE f.constant_value = numVal AND (" + suffixClause + ") " +
            "WITH n, collect(DISTINCT f.simple_name) AS preferred, numVal " +
            // Second-pass fallback: any *Constants class, but only when preferred is empty.
            "OPTIONAL MATCH (f2:Field) " +
            "WHERE size(preferred) = 0 " +
            "  AND f2.constant_value = numVal " +
            "  AND f2.owner_fqn ENDS WITH 'Constants' " +
            "WITH n, preferred, collect(DISTINCT f2.simple_name) AS fallback " +
            "WITH n, CASE WHEN size(preferred) > 0 THEN preferred ELSE fallback END AS candidates " +
            "WHERE size(candidates) = 1 " +
            "WITH n, candidates[0] AS symName " +
            "MERGE (sym:" + nodeLabel + " {id: symName}) " +
            "WITH n, sym " +
            "OPTIONAL MATCH (caller)-[old:" + edgeType + "]->(n) " +
            "WITH n, sym, collect(caller) AS callers " +
            "FOREACH (c IN callers | MERGE (c)-[:" + edgeType + "]->(sym)) " +
            "DETACH DELETE n " +
            "RETURN count(*) AS renamed")) {
            if (r.hasNext()) {
                int renamed = r.next().get("renamed").asInt(0);
                System.out.printf("[ingest] ConstantIndex cleanup: re-keyed %d :%s nodes by symbolic name (edge=:%s, preferred=%s)%n",
                    renamed, nodeLabel, edgeType, java.util.Arrays.toString(preferredSuffixes));
            }
        } catch (Throwable t) {
            System.err.println("[ingest] ConstantIndex cleanup for :" + nodeLabel + " failed: " + t.getMessage());
        }
    }

    // ─── edges ────────────────────────────────────────────────────────

    private void writeClassFileEdges(List<ClassFileEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("fqn", e.classFqn(), "path", e.filePath()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MATCH (c:Class {fqn: row.fqn}) " +
            "MATCH (f:File {path: row.path}) " +
            "MERGE (f)-[:CONTAINS]->(c)"
        );
    }

    private void writeExtends(List<ExtendsEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) {
            String[] toParts = splitClassFqn(e.toClassFqn());
            Map<String, Object> row = new HashMap<>();
            row.put("from", e.fromClassFqn());
            row.put("to", e.toClassFqn());
            row.put("toPkg", toParts[0]);
            row.put("toSimple", toParts[1]);
            rows.add(row);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (parent:Class {fqn: row.to}) " +
            "  ON CREATE SET parent.simple_name = row.toSimple, parent.pkg = row.toPkg " +
            "MERGE (child:Class  {fqn: row.from}) " +
            "MERGE (child)-[:EXTENDS]->(parent)"
        );
    }

    /** Splits a class FQN like "pkg.sub.Simple" into ["pkg.sub", "Simple"]. */
    private static String[] splitClassFqn(String fqn) {
        if (fqn == null || fqn.isEmpty()) return new String[]{"", ""};
        int dot = fqn.lastIndexOf('.');
        if (dot < 0) return new String[]{"", fqn};
        return new String[]{fqn.substring(0, dot), fqn.substring(dot + 1)};
    }

    private void writeImplements(List<ImplementsEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) {
            String[] toParts = splitClassFqn(e.toInterfaceFqn());
            Map<String, Object> row = new HashMap<>();
            row.put("from", e.fromClassFqn());
            row.put("to", e.toInterfaceFqn());
            row.put("toPkg", toParts[0]);
            row.put("toSimple", toParts[1]);
            rows.add(row);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (iface:Class {fqn: row.to}) " +
            "  ON CREATE SET iface.simple_name = row.toSimple, iface.pkg = row.toPkg " +
            "MERGE (cls:Class   {fqn: row.from}) " +
            "MERGE (cls)-[:IMPLEMENTS]->(iface)"
        );
    }

    private void writeOverrides(List<OverridesEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("from", e.fromMethodFqn(), "to", e.toMethodFqn()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (a:Method {fqn: row.from}) " +
            "MERGE (b:Method {fqn: row.to}) " +
            "MERGE (a)-[:OVERRIDES]-(b)"
        );
    }

    private void writeCalls(List<CallEdge> edges) {
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) {
            String[] fromParts = splitMethodFqn(e.fromMethodFqn());
            String[] toParts   = splitMethodFqn(e.toMethodFqn());
            Map<String, Object> row = new HashMap<>();
            row.put("from", e.fromMethodFqn());
            row.put("to", e.toMethodFqn());
            row.put("kind", e.kind() == null ? "direct" : e.kind());
            row.put("fromOwner", fromParts[0]);
            row.put("fromSimple", fromParts[1]);
            row.put("toOwner", toParts[0]);
            row.put("toSimple", toParts[1]);
            rows.add(row);
        }
        // ON CREATE enrich stubs so they're searchable by simple_name / owner_fqn.
        // ON MATCH does nothing — if writeMethods already populated the node with full
        // metadata, we don't want to clobber its line numbers or labels.
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (a:Method {fqn: row.from}) " +
            "  ON CREATE SET a.simple_name = row.fromSimple, a.owner_fqn = row.fromOwner " +
            "MERGE (b:Method {fqn: row.to}) " +
            "  ON CREATE SET b.simple_name = row.toSimple,   b.owner_fqn = row.toOwner " +
            "MERGE (a)-[r:CALLS {kind: row.kind}]->(b)"
        );
    }

    /** Splits a method FQN like "pkg.Owner.simple(p1,p2)" into ["pkg.Owner", "simple"]. */
    private static String[] splitMethodFqn(String fqn) {
        if (fqn == null || fqn.isEmpty()) return new String[]{"", ""};

        // Find the last '(' that is at paren-depth 0 (= start of the outermost param list).
        // For chained calls like "Owner.chain().method(param)" the first '(' is inside
        // chain() at depth 0, but the LAST '(' at depth 0 is the one before "param" —
        // which is what we want to split on.
        int depth = 0;
        int outerParen = -1;
        for (int i = 0; i < fqn.length(); i++) {
            char c = fqn.charAt(i);
            if (c == '(') {
                if (depth == 0) outerParen = i;
                depth++;
            } else if (c == ')') {
                depth--;
            }
        }

        String prefix = outerParen > 0 ? fqn.substring(0, outerParen) : fqn;

        // Find the last '.' at depth 0 within the prefix (skips dots inside "chain()" segments).
        depth = 0;
        int lastDot = -1;
        for (int i = 0; i < prefix.length(); i++) {
            char c = prefix.charAt(i);
            if      (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == '.' && depth == 0) lastDot = i;
        }

        if (lastDot < 0) return new String[]{"", prefix};
        return new String[]{prefix.substring(0, lastDot), prefix.substring(lastDot + 1)};
    }

    private void writeFieldAccess(List<FieldAccessEdge> edges) {
        List<Map<String, Object>> reads = new ArrayList<>();
        List<Map<String, Object>> writes = new ArrayList<>();
        for (var e : edges) {
            Map<String, Object> row = Map.of("from", e.fromMethodFqn(), "to", e.toFieldFqn());
            if (e.write()) writes.add(row); else reads.add(row);
        }
        chunked(reads,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " +
            "MERGE (f:Field  {fqn: row.to}) " +
            "MERGE (m)-[:READS]->(f)");
        chunked(writes,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " +
            "MERGE (f:Field  {fqn: row.to}) " +
            "MERGE (m)-[:WRITES]->(f)");
    }

    // ─── §4.1 expansion writers (boundary-edge promotion rules N1–M1, N4–N6) ─

    /**
     * All §4.1 writers follow the same shape: UNWIND-batched MERGE for the node OR
     * a (Method)-[:RELATION]->(Node) edge with MERGE-on-method ensuring the source
     * exists even if the call site survived the Pass-2 resolution unresolved.
     * Each helper short-circuits on empty input so empty lists incur zero IO.
     *
     * <p>IMPORTANT — when a writer creates a NEW :Method node via {@code MERGE (m:Method
     * {fqn: row.from})} (because the §4.1 resolver's FQN doesn't match an existing
     * graph FQN — e.g. source-text vs SymbolSolver-resolved param types), the writer
     * must ALSO set {@code owner_fqn} + {@code simple_name} on {@code ON CREATE}.
     * Without those properties the fuzzy reconciliation in {@link io.spmp.impact.analyze.SliceExecutor}
     * can't find the node (it matches by owner+simple), and the §4.1 edges sit on an
     * unreachable phantom. The {@link #methodRow} helper precomputes those parts.
     */

    /** Build a row that carries the FQN plus its parsed owner + simple-name for ON CREATE SET. */
    private static Map<String, Object> methodRow(String fromFqn, String... extraKVs) {
        String[] parts = splitMethodFqn(fromFqn);
        Map<String, Object> row = new HashMap<>();
        row.put("from", fromFqn);
        row.put("fromOwner",  parts[0]);
        row.put("fromSimple", parts[1]);
        for (int i = 0; i + 1 < extraKVs.length; i += 2) row.put(extraKVs[i], extraKVs[i + 1]);
        return row;
    }
    /** Variant for numeric extras (delayMillis on :SCHEDULES). */
    private static Map<String, Object> methodRow(String fromFqn, String k, long v) {
        Map<String, Object> row = methodRow(fromFqn);
        row.put(k, v);
        return row;
    }
    /** Common Cypher snippet to ensure owner/simple are stamped on first creation. */
    private static final String ENSURE_METHOD_PROPS =
        "  ON CREATE SET m.owner_fqn = row.fromOwner, m.simple_name = row.fromSimple ";
    private static final String ENSURE_SCHEDULED_TASK_PROPS =
        "  ON CREATE SET s.task_class_fqn = row.fqn, s.task_name = row.taskName ";
    private void writeNotificationTypes(List<io.spmp.impact.model.GraphNodes.NotificationTypeNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("id", n.id()));
        chunked(rows, "UNWIND $rows AS row MERGE (n:NotificationType {id: row.id})");
    }

    private void writeEmailTemplates(List<io.spmp.impact.model.GraphNodes.EmailTemplateNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("id", n.id()));
        chunked(rows, "UNWIND $rows AS row MERGE (e:EmailTemplate {id: row.id})");
    }

    private void writeAuditCategories(List<io.spmp.impact.model.GraphNodes.AuditCategoryNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("id", n.id()));
        chunked(rows, "UNWIND $rows AS row MERGE (a:AuditCategory {id: row.id})");
    }

    private void writeScheduledTasks(List<io.spmp.impact.model.GraphNodes.ScheduledTaskNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes){
            Map<String, Object> row = new HashMap<>();
            row.put("fqn", n.taskClassFqn());
            row.put("taskName", n.taskName());
            rows.add(row);
        }
        chunked(rows, "UNWIND $rows AS row MERGE (s:ScheduledTask {task_class_fqn: row.fqn}) " +
            "  ON CREATE SET s.task_name = row.taskName " +
            "  ON MATCH SET s.task_name = coalesce(s.task_name, row.taskName)");
    }

    private void writeEventTypes(List<io.spmp.impact.model.GraphNodes.EventTypeNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("fqn", n.fqn()));
        chunked(rows, "UNWIND $rows AS row MERGE (e:EventType {fqn: row.fqn})");
    }

    private void writeProperties(List<io.spmp.impact.model.GraphNodes.PropertyNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("key", n.key()));
        chunked(rows, "UNWIND $rows AS row MERGE (p:Property {key: row.key})");
    }

    private void writeFeatureFlags(List<io.spmp.impact.model.GraphNodes.FeatureFlagNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("id", n.id()));
        chunked(rows, "UNWIND $rows AS row MERGE (f:FeatureFlag {id: row.id})");
    }

    private void writePermissions(List<io.spmp.impact.model.GraphNodes.PermissionNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("id", n.id()));
        chunked(rows, "UNWIND $rows AS row MERGE (p:Permission {id: row.id})");
    }

    private void writeValidators(List<io.spmp.impact.model.GraphNodes.ValidatorNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("id", n.id()));
        chunked(rows, "UNWIND $rows AS row MERGE (v:Validator {id: row.id})");
    }

    private void writeExternalSystems(List<io.spmp.impact.model.GraphNodes.ExternalSystemNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of(
            "id", n.id(),
            "baseUrl", n.baseUrl() == null ? "" : n.baseUrl()
        ));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (x:ExternalSystem {id: row.id}) " +
            "  ON CREATE SET x.base_url = row.baseUrl");
    }

    private void writeLogChannels(List<io.spmp.impact.model.GraphNodes.LogChannelNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("name", n.name()));
        chunked(rows, "UNWIND $rows AS row MERGE (l:LogChannel {name: row.name})");
    }

    private void writeStates(List<io.spmp.impact.model.GraphNodes.StateNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("entity", n.entity(), "to", n.to()));
        chunked(rows, "UNWIND $rows AS row MERGE (s:State {entity: row.entity, to: row.to})");
    }

    // ─── §4.1 edge writers ──

    private void writeSendsNotification(List<io.spmp.impact.model.GraphEdges.SendsNotificationEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "id", e.notificationTypeId()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (n:NotificationType {id: row.id}) " +
            "MERGE (m)-[:SENDS_NOTIFICATION]->(n)");
    }

    private void writeSendsEmail(List<io.spmp.impact.model.GraphEdges.SendsEmailEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "id", e.templateId()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (t:EmailTemplate {id: row.id}) " +
            "MERGE (m)-[:SENDS_EMAIL]->(t)");
    }

    private void writeWritesAudit(List<io.spmp.impact.model.GraphEdges.WritesAuditEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "id", e.categoryId()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (a:AuditCategory {id: row.id}) " +
            "MERGE (m)-[:WRITES_AUDIT]->(a)");
    }

    private void writeSchedules(List<io.spmp.impact.model.GraphEdges.SchedulesEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) {
            Map<String, Object> row = methodRow(e.fromMethodFqn(), "fqn", e.taskClassFqn());
            row.put("delay", e.delayMillis());
            rows.add(row);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (s:ScheduledTask {task_class_fqn: row.fqn}) " +
            "MERGE (m)-[r:SCHEDULES]->(s) " +
            "  ON CREATE SET r.delay_millis = row.delay");
    }

    private void writeSchedulesToMethod(List<io.spmp.impact.model.GraphEdges.SchedulesToMethodEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) {
            Map<String, Object> row = new HashMap<>();
            row.put("fqn", e.fromTaskclassFqn());
            row.put("taskName", e.ToMethodFqn());
            rows.add(row);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (s:ScheduledTask {task_class_fqn: row.fqn}) " + ENSURE_SCHEDULED_TASK_PROPS +
            "MERGE (t:Method {fqn: row.taskName}) " +
            "MERGE (s)-[:SCHEDULES]->(t)");
    }

    private void writeCancelsSchedule(List<io.spmp.impact.model.GraphEdges.CancelsScheduleEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "fqn", e.taskClassFqn()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (s:ScheduledTask {task_class_fqn: row.fqn}) " +
            "MERGE (m)-[:CANCELS_SCHEDULE]->(s)");
    }

    private void writePublishesEvent(List<io.spmp.impact.model.GraphEdges.PublishesEventEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "fqn", e.eventTypeFqn()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (e:EventType {fqn: row.fqn}) " +
            "MERGE (m)-[:PUBLISHES_EVENT]->(e)");
    }

    private void writeListensFor(List<io.spmp.impact.model.GraphEdges.ListensForEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "fqn", e.eventTypeFqn()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (e:EventType {fqn: row.fqn}) " +
            "MERGE (m)-[:LISTENS_FOR]->(e)");
    }

    private void writeInstantiatesHandler(List<io.spmp.impact.model.GraphEdges.InstantiatesHandlerEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "fqn", e.handlerClassFqn()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (c:Class {fqn: row.fqn}) " +
            "MERGE (m)-[:INSTANTIATES_HANDLER]->(c)");
    }

    private void writeReadsProperty(List<io.spmp.impact.model.GraphEdges.ReadsPropertyEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "key", e.propertyKey()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (p:Property {key: row.key}) " +
            "MERGE (m)-[:READS_PROPERTY]->(p)");
    }

    private void writeGatedBy(List<io.spmp.impact.model.GraphEdges.GatedByEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "id", e.flagId()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (f:FeatureFlag {id: row.id}) " +
            "MERGE (m)-[:GATED_BY]->(f)");
    }

    private void writeRequiresPermission(List<io.spmp.impact.model.GraphEdges.RequiresPermissionEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "id", e.permissionId()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (p:Permission {id: row.id}) " +
            "MERGE (m)-[:REQUIRES_PERMISSION]->(p)");
    }

    private void writeValidatesInput(List<io.spmp.impact.model.GraphEdges.ValidatesInputEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "id", e.validatorId()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (v:Validator {id: row.id}) " +
            "MERGE (m)-[:VALIDATES_INPUT]->(v)");
    }

    private void writeCallsExternal(List<io.spmp.impact.model.GraphEdges.CallsExternalEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(),
            "id", e.systemId(),
            "urlSample", e.urlSample() == null ? "" : e.urlSample()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (x:ExternalSystem {id: row.id}) " +
            "MERGE (m)-[r:CALLS_EXTERNAL]->(x) " +
            "  ON CREATE SET r.url_sample = row.urlSample");
    }

    private void writeWritesLog(List<io.spmp.impact.model.GraphEdges.WritesLogEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "name", e.channelName()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (l:LogChannel {name: row.name}) " +
            "MERGE (m)-[:WRITES_LOG]->(l)");
    }

    private void writeTransitionsState(List<io.spmp.impact.model.GraphEdges.TransitionsStateEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(),
            "entity", e.entity(),
            "to", e.toState(),
            "fromState", e.fromState() == null ? "" : e.fromState()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (s:State {entity: row.entity, to: row.to}) " +
            "MERGE (m)-[r:TRANSITIONS_STATE]->(s) " +
            "  ON CREATE SET r.from_state = row.fromState");
    }

    private void writeInstantiates(List<io.spmp.impact.model.GraphEdges.InstantiatesEdge> edges) {
        if (edges.isEmpty()) return;
        // Partition: basic (FQN-targeted, no block) vs block-annotated (simple-name-targeted)
        List<Map<String, Object>> basicRows = new ArrayList<>();
        List<Map<String, Object>> blockRows = new ArrayList<>();
        for (var e : edges) {
            if (e.blockStartLine() > 0) {
                // Block-annotated edge (from NotificationAuditResolver)
                Map<String, Object> row = methodRow(e.fromMethodFqn(),
                    "simple", e.targetSimpleName() != null ? e.targetSimpleName() : "");
                row.put("blockStart", (long) e.blockStartLine());
                row.put("blockEnd",   (long) e.blockEndLine());
                row.put("siblings",   e.siblingMethods() != null ? e.siblingMethods() : java.util.List.of());
                blockRows.add(row);
            } else {
                // Basic edge (from ClassShapeResolver)
                basicRows.add(methodRow(e.fromMethodFqn(),
                    "fqn", e.classFqn(),
                    "via", e.via() == null ? "" : e.via()));
            }
        }
        // Write basic INSTANTIATES edges (one per method→class pair)
        if (!basicRows.isEmpty()) {
            chunked(basicRows,
                "UNWIND $rows AS row " +
                "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
                "MERGE (c:Class {fqn: row.fqn}) " +
                "MERGE (m)-[r:INSTANTIATES]->(c) " +
                "  ON CREATE SET r.via = row.via");
        }
        // Write block-annotated INSTANTIATES edges (multiple per method→class pair,
        // keyed on block_start_line). Target resolved by simple_name since the
        // notification resolver doesn't have the FQN at extraction time.
        if (!blockRows.isEmpty()) {
            chunked(blockRows,
                "UNWIND $rows AS row " +
                "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
                "WITH m, row " +
                "OPTIONAL MATCH (cls:Class {simple_name: row.simple}) " +
                "WITH m, row, cls WHERE cls IS NOT NULL " +
                "MERGE (m)-[r:INSTANTIATES {block_start_line: row.blockStart}]->(cls) " +
                "  ON CREATE SET r.block_end_line = row.blockEnd, r.sibling_methods = row.siblings " +
                "  ON MATCH SET r.sibling_methods = row.siblings");
        }
    }

    private void writeSingletonOf(List<io.spmp.impact.model.GraphEdges.SingletonOfEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("fqn", e.classFqn()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (c:Class {fqn: row.fqn}) " +
            "MERGE (c)-[:SINGLETON_OF]->(c)");
    }

    private void writeInjects(List<io.spmp.impact.model.GraphEdges.InjectsEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(Map.of("from", e.fromClassFqn(), "dep", e.dependencyClassFqn()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (a:Class {fqn: row.from}) " +
            "MERGE (b:Class {fqn: row.dep}) " +
            "MERGE (a)-[:INJECTS]->(b)");
    }

    private void writeRequestParams(List<io.spmp.impact.model.GraphNodes.RequestParamNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("name", n.name()));
        chunked(rows, "UNWIND $rows AS row MERGE (p:RequestParam {name: row.name})");
    }

    private void writeReadsParam(List<io.spmp.impact.model.GraphEdges.ReadsParamEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) {
            Map<String, Object> row = methodRow(e.fromMethodFqn(), "name", e.paramName());
            row.put("value", e.branchValue() == null ? "" : e.branchValue());
            row.put("blockStart", (long) e.blockStartLine());
            row.put("blockEnd",   (long) e.blockEndLine());
            rows.add(row);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (p:RequestParam {name: row.name}) " +
            // One edge per (method, param, value, blockStart) tuple. MERGE keys on
            // value + block_start_line so different branches with the same value
            // (rare but possible) produce separate edges. block_end_line is set on
            // create only — it's metadata for the analyze-side hunk-range filter.
            "MERGE (m)-[r:READS_PARAM {value: row.value, block_start_line: row.blockStart}]->(p) " +
            "  ON CREATE SET r.block_end_line = row.blockEnd");
    }

    // ─── D5: orchestration trigger writers ────────────────────────────

    private void writeOrchestrationProfiles(List<io.spmp.impact.model.GraphNodes.OrchestrationProfileNode> nodes) {
        if (nodes.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(nodes.size());
        for (var n : nodes) rows.add(Map.of("id", n.id()));
        chunked(rows, "UNWIND $rows AS row MERGE (o:OrchestrationProfile {id: row.id})");
    }

    private void writeTriggersOrchestration(List<io.spmp.impact.model.GraphEdges.TriggersOrchestrationEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "id", e.actionId() == null ? "" : e.actionId()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (o:OrchestrationProfile {id: row.id}) " +
            "MERGE (m)-[:TRIGGERS_ORCHESTRATION]->(o)");
    }

    // ─── D7 Layer C: dispatch-block writer (DEPRECATED — now unified into writeInstantiates) ────

    /**
     * @deprecated Replaced by block-annotated path in {@link #writeInstantiates}.
     * Kept temporarily for backward compatibility during migration.
     */
    @Deprecated(forRemoval = true)
    private void writeGatesDispatch(List<io.spmp.impact.model.GraphEdges.GatesDispatchEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) {
            Map<String, Object> row = methodRow(e.fromMethodFqn(), "simple", e.macroSimpleName());
            row.put("blockStart", (long) e.blockStartLine());
            row.put("blockEnd",   (long) e.blockEndLine());
            row.put("siblings",   e.siblingMethods() != null ? e.siblingMethods() : java.util.List.of());
            rows.add(row);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            // Match the destination class by simple_name. The :NotificationMacro
            // label is applied by NotificationMacroResolver in afterAll, which runs
            // AFTER streaming flushes — so requiring the label here would drop edges
            // from earlier flushes. The analyze-side query filters by label.
            "WITH m, row " +
            "OPTIONAL MATCH (cls:Class {simple_name: row.simple}) " +
            "WITH m, row, cls WHERE cls IS NOT NULL " +
            "MERGE (m)-[r:GATES_DISPATCH {block_start_line: row.blockStart}]->(cls) " +
            "  ON CREATE SET r.block_end_line = row.blockEnd, r.macro_simple = row.simple, r.sibling_methods = row.siblings " +
            "  ON MATCH SET r.sibling_methods = row.siblings");
    }

    // ─── D7: handles-attribute writer (macro attribute narrowing) ─────

    private void writeHandlesAttribute(List<io.spmp.impact.model.GraphEdges.HandlesAttributeEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        for (var e : edges) {
            Map<String, Object> row = methodRow(e.fromMethodFqn(), "simple", e.macroSimpleName());
            row.put("attr",       e.attributeName());
            row.put("blockStart", (long) e.blockStartLine());
            row.put("blockEnd",   (long) e.blockEndLine());
            rows.add(row);
        }
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "WITH m, row " +
            "OPTIONAL MATCH (cls:Class {simple_name: row.simple}) " +
            "WITH m, row, cls WHERE cls IS NOT NULL " +
            "MERGE (m)-[r:HANDLES_ATTRIBUTE {attribute: row.attr, block_start_line: row.blockStart}]->(cls) " +
            "  ON CREATE SET r.block_end_line = row.blockEnd " +
            "  ON MATCH SET r.block_end_line = row.blockEnd");
    }

    // ─── D6: user-schedule writer ─────────────────────────────────────

    private void writeUserSchedules(List<io.spmp.impact.model.GraphEdges.UserSchedulesEdge> edges) {
        if (edges.isEmpty()) return;
        List<Map<String, Object>> rows = new ArrayList<>(edges.size());
        // Reuse :ScheduledTask as the destination node — same key (schedule identifier).
        // The edge type :USER_SCHEDULES distinguishes user-configured schedules from
        // system :SCHEDULES (D2 SchedulerHandler.createScheduler). A schedule can be
        // both — the two edges coexist on the same :ScheduledTask node.
        for (var e : edges) rows.add(methodRow(e.fromMethodFqn(), "id", e.scheduleId() == null ? "" : e.scheduleId()));
        chunked(rows,
            "UNWIND $rows AS row " +
            "MERGE (m:Method {fqn: row.from}) " + ENSURE_METHOD_PROPS +
            "MERGE (t:ScheduledTask {task_class_fqn: row.id}) " +
            "MERGE (m)-[:USER_SCHEDULES]->(t)");
    }

    // ─── infrastructure ───────────────────────────────────────────────

    private void chunked(List<Map<String, Object>> rows, String cypher) {
        if (rows.isEmpty()) return;
        for (int i = 0; i < rows.size(); i += BATCH) {
            final List<Map<String, Object>> chunk = rows.subList(i, Math.min(rows.size(), i + BATCH));
            client.executeWrite(tx -> {
                try (CResult r = tx.run(cypher, Map.of("rows", chunk))) {
                    r.consume();
                }
                return null;
            });
        }
    }

    // ─── Test-case (P8) writes ────────────────────────────────────────

    public void writeTestCaseBatch(io.spmp.impact.testgen.TestCaseBatch batch) {
        // TestSuites
        List<Map<String, Object>> suiteRows = new ArrayList<>();
        for (var s : batch.suites) suiteRows.add(Map.of("name", s.name()));
        chunked(suiteRows, "UNWIND $rows AS row MERGE (s:TestSuite {name: row.name})");

        // TestCases
        List<Map<String, Object>> tcRows = new ArrayList<>();
        for (var tc : batch.testCases) {
            Map<String, Object> r = new HashMap<>();
            r.put("id", tc.id());
            r.put("title", nullToEmpty(tc.title()));
            r.put("area", nullToEmpty(tc.area()));
            r.put("steps", nullToEmpty(tc.steps()));
            r.put("expected", nullToEmpty(tc.expected()));
            r.put("source", nullToEmpty(tc.source()));
            tcRows.add(r);
        }
        chunked(tcRows,
            "UNWIND $rows AS row " +
            "MERGE (t:TestCase {id: row.id}) " +
            "  SET t.title = row.title, t.area = row.area, " +
            "      t.steps = row.steps, t.expected = row.expected, t.source = row.source"
        );

        // IN_SUITE edges
        List<Map<String, Object>> inSuiteRows = new ArrayList<>();
        for (var e : batch.inSuite) inSuiteRows.add(Map.of("tc", e.testCaseId(), "suite", e.suiteName()));
        chunked(inSuiteRows,
            "UNWIND $rows AS row " +
            "MATCH (t:TestCase {id: row.tc}) " +
            "MATCH (s:TestSuite {name: row.suite}) " +
            "MERGE (t)-[:IN_SUITE]->(s)"
        );

        // COVERS edges — match by node kind. Group by kind so each Cypher is simple
        // and we don't have to invent a multi-label MATCH inside UNION.
        Map<String, List<Map<String, Object>>> byKind = new HashMap<>();
        for (var e : batch.covers) {
            Map<String, Object> row = Map.of(
                "tc", e.testCaseId(),
                "key", e.targetKey(),
                "conf", e.confidence()
            );
            byKind.computeIfAbsent(e.targetKind(), k -> new ArrayList<>()).add(row);
        }
        runCoversByKind(byKind.get("Method"),
            "MATCH (target:Method {fqn: row.key})");
        runCoversByKind(byKind.get("Class"),
            "MATCH (target:Class {fqn: row.key})");
        runCoversByKind(byKind.get("RestEndpoint"),
            "MATCH (target:RestEndpoint {url: row.key})");
        runCoversByKind(byKind.get("TaskType"),
            "MATCH (target:TaskType {id: row.key})");
        runCoversByKind(byKind.get("DbTable"),
            "MATCH (target:DbTable {name: row.key})");
        runCoversByKind(byKind.get("MessageConstant"),
            "MATCH (target:MessageConstant {value: row.key})");
    }

    private void runCoversByKind(List<Map<String, Object>> rows, String matchClause) {
        if (rows == null || rows.isEmpty()) return;
        chunked(rows,
            "UNWIND $rows AS row " +
            "MATCH (t:TestCase {id: row.tc}) " +
            matchClause + " " +
            "MERGE (t)-[r:COVERS]->(target) " +
            "  SET r.confidence = row.conf"
        );
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }

    /** Only allow identifier-safe labels — block Cypher injection via extra-label strings. */
    private static String sanitizeLabel(String s) {
        if (s == null || s.isEmpty()) return null;
        if (!Character.isJavaIdentifierStart(s.charAt(0))) return null;
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) return null;
        }
        return s;
    }

    @Override
    public void close() {
        try { client.close(); } catch (Exception ignored) {}
    }
}
