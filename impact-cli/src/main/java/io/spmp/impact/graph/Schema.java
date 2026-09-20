package io.spmp.impact.graph;

import io.spmp.impact.graph.txn.CypherClient;
import io.spmp.impact.graph.txn.CypherClient.CResult;

import java.util.List;

/** Bootstraps Neo4j constraints + indexes. Idempotent. */
public final class Schema {

    private static final List<String> STATEMENTS = List.of(
        "CREATE CONSTRAINT repo_id          IF NOT EXISTS FOR (r:Repo)            REQUIRE r.id IS UNIQUE",
        "CREATE CONSTRAINT commit_sha       IF NOT EXISTS FOR (c:Commit)          REQUIRE c.sha IS UNIQUE",
        "CREATE CONSTRAINT package_name     IF NOT EXISTS FOR (p:Package)         REQUIRE p.name IS UNIQUE",
        "CREATE CONSTRAINT file_pk          IF NOT EXISTS FOR (f:File)            REQUIRE (f.path, f.commit_sha) IS UNIQUE",
        "CREATE CONSTRAINT class_fqn        IF NOT EXISTS FOR (c:Class)           REQUIRE c.fqn IS UNIQUE",
        "CREATE CONSTRAINT method_fqn       IF NOT EXISTS FOR (m:Method)          REQUIRE m.fqn IS UNIQUE",
        "CREATE CONSTRAINT field_fqn        IF NOT EXISTS FOR (f:Field)           REQUIRE f.fqn IS UNIQUE",
        "CREATE CONSTRAINT rest_url         IF NOT EXISTS FOR (r:RestEndpoint)    REQUIRE r.url IS UNIQUE",
        "CREATE CONSTRAINT dbtable_name     IF NOT EXISTS FOR (t:DbTable)         REQUIRE t.name IS UNIQUE",
        "CREATE CONSTRAINT dbcolumn_key     IF NOT EXISTS FOR (c:DbColumn)        REQUIRE (c.table, c.name) IS UNIQUE",
        "CREATE CONSTRAINT htmlpage_file    IF NOT EXISTS FOR (p:HtmlPage)        REQUIRE p.filename IS UNIQUE",
        "CREATE CONSTRAINT jsfile_path      IF NOT EXISTS FOR (j:JsFile)          REQUIRE j.path IS UNIQUE",
        "CREATE CONSTRAINT csfile_path      IF NOT EXISTS FOR (c:CsFile)          REQUIRE c.path IS UNIQUE",
        "CREATE CONSTRAINT tasktype_id      IF NOT EXISTS FOR (t:TaskType)        REQUIRE t.id IS UNIQUE",
        "CREATE CONSTRAINT msgconst_value   IF NOT EXISTS FOR (m:MessageConstant) REQUIRE m.value IS UNIQUE",
        "CREATE CONSTRAINT spisvc_fqn       IF NOT EXISTS FOR (s:SpiService)      REQUIRE s.interface_fqn IS UNIQUE",
        "CREATE CONSTRAINT psscript_name    IF NOT EXISTS FOR (p:PsScript)        REQUIRE p.name IS UNIQUE",
        "CREATE CONSTRAINT testcase_id      IF NOT EXISTS FOR (t:TestCase)        REQUIRE t.id IS UNIQUE",
        "CREATE CONSTRAINT testsuite_name   IF NOT EXISTS FOR (s:TestSuite)       REQUIRE s.name IS UNIQUE",
        "CREATE CONSTRAINT hbstemplate_path IF NOT EXISTS FOR (h:HbsTemplate)     REQUIRE h.path IS UNIQUE",
        // §4.1 expansion (boundary-edge promotion rules N1–M1, N4–N6)
        "CREATE CONSTRAINT notificationtype_id IF NOT EXISTS FOR (n:NotificationType) REQUIRE n.id IS UNIQUE",
        "CREATE CONSTRAINT emailtemplate_id    IF NOT EXISTS FOR (e:EmailTemplate)    REQUIRE e.id IS UNIQUE",
        "CREATE CONSTRAINT auditcategory_id    IF NOT EXISTS FOR (a:AuditCategory)    REQUIRE a.id IS UNIQUE",
        "CREATE CONSTRAINT scheduledtask_fqn   IF NOT EXISTS FOR (s:ScheduledTask)    REQUIRE s.task_class_fqn IS UNIQUE",
        "CREATE CONSTRAINT eventtype_fqn       IF NOT EXISTS FOR (e:EventType)        REQUIRE e.fqn IS UNIQUE",
        "CREATE CONSTRAINT property_key        IF NOT EXISTS FOR (p:Property)         REQUIRE p.key IS UNIQUE",
        "CREATE CONSTRAINT featureflag_id      IF NOT EXISTS FOR (f:FeatureFlag)      REQUIRE f.id IS UNIQUE",
        "CREATE CONSTRAINT permission_id       IF NOT EXISTS FOR (p:Permission)       REQUIRE p.id IS UNIQUE",
        "CREATE CONSTRAINT validator_id        IF NOT EXISTS FOR (v:Validator)        REQUIRE v.id IS UNIQUE",
        "CREATE CONSTRAINT externalsystem_id   IF NOT EXISTS FOR (x:ExternalSystem)   REQUIRE x.id IS UNIQUE",
        "CREATE CONSTRAINT logchannel_name     IF NOT EXISTS FOR (l:LogChannel)       REQUIRE l.name IS UNIQUE",
        "CREATE CONSTRAINT state_key           IF NOT EXISTS FOR (s:State)            REQUIRE (s.entity, s.to) IS UNIQUE",
        "CREATE CONSTRAINT requestparam_name   IF NOT EXISTS FOR (p:RequestParam)     REQUIRE p.name IS UNIQUE",
        // D5: orchestration trigger destinations
        "CREATE CONSTRAINT orchprofile_id      IF NOT EXISTS FOR (o:OrchestrationProfile) REQUIRE o.id IS UNIQUE",
        // P9.6: web-app user store
        "CREATE CONSTRAINT appuser_username  IF NOT EXISTS FOR (u:AppUser)         REQUIRE u.username IS UNIQUE",

        "CREATE INDEX method_simple_name    IF NOT EXISTS FOR (m:Method)          ON (m.simple_name)",
        "CREATE INDEX class_simple_name     IF NOT EXISTS FOR (c:Class)           ON (c.simple_name)",
        "CREATE INDEX method_owner          IF NOT EXISTS FOR (m:Method)          ON (m.owner_fqn)",
        "CREATE INDEX file_commit           IF NOT EXISTS FOR (f:File)            ON (f.commit_sha)"
    );

    public static void bootstrap(Neo4jWriter writer) {
        // IMPORTANT: do NOT close the writer's client here — it's shared for the
        // lifetime of the writer. try-with-resources used to close it, breaking
        // every subsequent operation.
        CypherClient client = writer.session();
        for (String stmt : STATEMENTS) {
            try (CResult r = client.run(stmt)) {
                r.consume();
            }
        }
    }

    private Schema() {}
}
