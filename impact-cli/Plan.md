# Impact Analysis Tool — Neo4j Call-Graph (SPMP Load Balancing)

## Context

You need an **enterprise-grade impact analysis tool** for the SPMP codebase at `D:\SPMP\LoadBalancerV1\spmp-SPMP_4509_Load_Balancing\` (polyglot: Java 900 files / 122k LOC, C# 157, Ember 706, PowerShell 95). Given a **git diff** (branch vs branch, commit vs commit), it must answer: *"What user-visible features, scheduled jobs, REST endpoints, and DB tables can this change affect?"*

A plain language-level call graph is not enough — this codebase routes work through string-keyed dispatch (`ManagementTaskRegistry`), JGroups message constants, servlets, schedulers, and PowerShell process invocations. The tool must encode these synthetic edges as first-class graph relationships in Neo4j so blast-radius queries traverse them.

**v1 scope (this plan):** Java only. Output: CLI that prints a report + writes HTML/JSON. Diff source: `git diff`.
**v2 (deferred):** Ember/JS, C#, PowerShell extractors.

> Note: the working source tree is not currently a git repo. The tool assumes the user operates on a git-initialized copy (one-time `git init && git add . && git commit -m base` against the patched source). Document this in the README.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  impact-cli  (single Java fat-jar)                               │
│                                                                  │
│  ingest ──► [JavaParser + SymbolSolver]                          │
│              │                                                    │
│              ├─► Core extractor   (Class/Method/Field, CALLS)    │
│              ├─► Boundary resolvers (synthetic edges)             │
│              │     • Servlet entry points                         │
│              │     • Scheduler / ManagementJob entry points       │
│              │     • ManagementTaskRegistry → handler dispatch   │
│              │     • DistributedTaskMessageConstants            │
│              │     • META-INF/services SPI                       │
│              │     • ProcessBuilder → .ps1 invocations           │
│              │     • SelectQuery/UpdateQuery → DB table tags     │
│              │                                                    │
│              └─► Neo4j writer (UNWIND batched MERGE, idx-backed) │
│                                                                  │
│  analyze ──► [jgit DiffFormatter]                                │
│              │                                                    │
│              ├─► Hunk → enclosing symbol resolver (AST walk)     │
│              ├─► Cypher: forward + backward slices               │
│              └─► HTML (FreeMarker) + JSON (Jackson) report       │
└──────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌──────────────────┐
                    │   Neo4j 5.x       │
                    │   (Community OK)  │
                    └──────────────────┘
```

---

## Neo4j Graph Schema

### Node labels

| Label | Key | Purpose |
|---|---|---|
| `:Repo` | `id` | Top-level container (multi-repo ready) |
| `:Commit` | `sha` | Snapshot version (every ingest = one commit) |
| `:Package` | `name` | Java package |
| `:File` | `path` + `commit_sha` | Source file at a commit |
| `:Class` | `fqn` | Class/Interface. Sub-labels: `:Interface`, `:Servlet`, `:Scheduler`, `:Job`, `:TaskHandler` |
| `:Method` | `fqn` + `signature` | Sub-labels: `:Constructor`, `:EntryPoint` |
| `:Field` | `fqn` | Used for read/write edges |
| `:RestEndpoint` | `url` | From servlet inference + REST XML |
| `:DbTable` | `name` | Tagged from SelectQuery/UpdateQuery |
| `:TaskType` | `id` (string) | e.g. `"GrantPermission"` from ManagementTaskRegistry |
| `:MessageConstant` | `value` | `DIST_TASK_*` constants |
| `:SpiService` | `interface_fqn` | META-INF/services entries |
| `:PsScript` | `name` | `.ps1` filename (placeholder until v2) |

### Edge types

| Type | From → To | Notes |
|---|---|---|
| `:CONTAINS` | Repo/Package/File/Class → child | Hierarchy |
| `:EXTENDS`, `:IMPLEMENTS` | Class → Class/Interface | Type hierarchy |
| `:OVERRIDES` | Method → Method | Needed for virtual call resolution |
| `:CALLS` | Method → Method | `{kind: 'direct'\|'virtual'\|'static'\|'reflective'}` |
| `:READS`, `:WRITES` | Method → Field | Field-level dependency |
| `:READS_TABLE`, `:WRITES_TABLE` | Method → DbTable | From DB query AST scan |
| `:EXPOSES` | Class(:Servlet) → RestEndpoint | Inferred from servlet name / web.xml |
| `:HANDLES` | Class(:TaskHandler) → TaskType | From ManagementTaskRegistry.register() |
| `:DISPATCHES_TO` | Method → Class(:TaskHandler) | Synthetic: `ManagementTaskRegistry.get(taskType)` |
| `:SENDS_MESSAGE`, `:RECEIVES_MESSAGE` | Method ↔ MessageConstant | JGroups routing |
| `:INVOKES_SCRIPT` | Method → PsScript | `ProcessBuilder("powershell.exe", ...)` |
| `:PROVIDES` | Class → SpiService | META-INF/services impls |
| `:CHANGED_IN` | Method/Class/File → Commit | Powers diff queries |

### Indexes / constraints (must-haves)

```cypher
CREATE CONSTRAINT method_fqn IF NOT EXISTS FOR (m:Method) REQUIRE m.fqn IS UNIQUE;
CREATE CONSTRAINT class_fqn  IF NOT EXISTS FOR (c:Class)  REQUIRE c.fqn IS UNIQUE;
CREATE CONSTRAINT file_pk    IF NOT EXISTS FOR (f:File)   REQUIRE (f.path, f.commit_sha) IS UNIQUE;
CREATE INDEX method_simple   IF NOT EXISTS FOR (m:Method) ON (m.simple_name);
CREATE INDEX commit_sha      IF NOT EXISTS FOR (c:Commit) ON (c.sha);
```

---

## Boundary resolvers (the value-add over a vanilla call graph)

Each resolver is a separate `ASTVisitor` run after the core extractor. Reference files (all paths relative to `spmp-SPMP_4509_Load_Balancing\spmp-SPMP_4509_Load_Balancing\source\java\`):

| Resolver | Reference file | What it emits |
|---|---|---|
| **TaskRegistry resolver** | `com\manageengine\spmp\management\distributed\ManagementTaskRegistry.java` | `(handler)-[:HANDLES]->(TaskType)` for each `register(new XxxTaskHandler())` in static init |
| **Dispatcher resolver** | `com\manageengine\spmp\common\server\clustering\distributed\coordinator\DistributedTaskCoordinator.java` | `(coordinator method)-[:DISPATCHES_TO]->(any :TaskHandler)` whenever `ManagementTaskRegistry.get(taskType)` is called |
| **Message constant resolver** | `com\manageengine\spmp\common\server\clustering\distributed\message\DistributedTaskMessageConstants.java` | One `:MessageConstant` per `public static final String` |
| **Servlet resolver** | any class extending `HttpServlet` (e.g. `migration\copycontents\servlet\AddCopyContentTask.java`) | Mark `:Servlet` label; create `:RestEndpoint` from class FQN convention; link `doGet`/`doPost` as `:EntryPoint` |
| **Scheduler/Job resolver** | `*Scheduler.java`, classes implementing `Task` / `ManagementJob` (e.g. `spmp\management\tasks\ManagementTaskScheduler.java`) | Mark `:Scheduler` / `:Job` label; mark trigger method as `:EntryPoint` |
| **SPI resolver** | `META-INF\services\com.zoho.versioning.Component` | `(class)-[:PROVIDES]->(:SpiService {interface_fqn})`. Treat impl class methods as additionally reachable. |
| **DB-table resolver** | `com\manageengine\migration\copycontents\handler\CopyContentActionHandler.java:75` (SelectQuery), `CopyContentStatusManager.java:224` (UpdateQuery), `com\manageengine\spmp\util\DBUtil.java:42` (executeQuery) | Resolve string constant arg → `:DbTable` node; emit `:READS_TABLE`/`:WRITES_TABLE` |
| **PowerShell invocation resolver** | `PowerShellConfigurator.java:133`, `ChangeDBServer.java:506` (`ProcessBuilder`), plus any `.ps1` string literal | `(method)-[:INVOKES_SCRIPT]->(:PsScript)` |

These resolvers **reuse the same JavaParser SymbolSolver** instance — no second pass needed.

---

## Diff → impact pipeline

1. **Get diff:** `org.eclipse.jgit.diff.DiffFormatter` between `<base>` and `<head>` revs. No subprocess shelling.
2. **Per file edit:** group hunks by file path.
3. **Hunk → symbol mapping (the only subtle step):**
   - Re-parse `<head>` version of file with JavaParser.
   - For each hunk's line range, walk AST and pick the smallest `MethodDeclaration` / `ConstructorDeclaration` / `ClassOrInterfaceDeclaration` whose line range contains the hunk.
   - Distinguish **signature change** (parameter list / return type / annotations modified) vs **body-only change** — signature changes propagate to callers, body changes only to callees.
4. **Mark changed nodes:** add `:Changed` label or `change_id` property scoped to this analysis run (cleared at start).
5. **Run Cypher slices:**

```cypher
// Forward blast radius (what this change can affect downstream)
MATCH (m:Method:Changed)
MATCH path = (m)-[:CALLS|DISPATCHES_TO|INVOKES_SCRIPT*1..6]->(d)
RETURN m.fqn AS source, collect(DISTINCT d.fqn) AS downstream;

// Backward reachability to entry points (which user features call this?)
MATCH (m:Method:Changed)
MATCH path = (root:EntryPoint)-[:CALLS|DISPATCHES_TO*1..10]->(m)
RETURN m.fqn AS changed, collect(DISTINCT {
  root: root.fqn,
  kind: labels(root),
  rest_url: [(root)<-[:EXPOSES]-(:Servlet)-[:EXPOSES]->(u:RestEndpoint) | u.url][0]
}) AS reaches_from;

// DB tables touched on any path from change
MATCH (m:Method:Changed)-[:CALLS*0..6]->(:Method)-[r:READS_TABLE|WRITES_TABLE]->(t:DbTable)
RETURN DISTINCT t.name, type(r);

// Risk score (entry-point breadth × cross-package hops)
MATCH (m:Method:Changed)
OPTIONAL MATCH (root:EntryPoint)-[:CALLS|DISPATCHES_TO*1..10]->(m)
WITH m, count(DISTINCT root) AS entry_points
RETURN m.fqn, entry_points,
       CASE WHEN entry_points > 5 THEN 'HIGH'
            WHEN entry_points > 1 THEN 'MEDIUM'
            ELSE 'LOW' END AS risk;
```

6. **Render:** FreeMarker template → HTML report; Jackson → JSON for CI consumption.

---

## CLI surface

```
impact ingest --src <path> --neo4j bolt://host:7687 --user neo4j --pass ***
              [--commit <sha>]                # default: current HEAD
              [--incremental]                 # only re-parse files changed since last commit

impact analyze --base <ref> --head <ref> --output html [--out report.html]
               [--depth 6]                    # max BFS depth (default 6)
               [--fail-on HIGH]               # exit non-zero for CI gating

impact query "<cypher>"                       # ad-hoc graph query
impact wipe --commit <sha>                    # remove a snapshot
```

---

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Parser | **JavaParser 3.25+ with SymbolSolver** | Source-level (keeps line numbers — needed for diff mapping). SymbolSolver resolves overloads + virtual calls. Soot would be more precise but kills incremental + line tracking. |
| Graph | **Neo4j 5.x Community** | At repo scale (~25k nodes, ~200k edges) Community is ample. GDS optional for centrality/PageRank in v2. |
| Driver | Neo4j Java Driver 5.x | Batch via `UNWIND $rows MERGE ...` for ingestion (~10× faster than per-row). |
| Diff | **jgit** | Java-native, no shelling out. Handles bare and worktree repos. |
| Build | Maven, fat-jar via Shade plugin | Single `impact.jar` deployable. |
| Templates | FreeMarker | HTML report. |
| Tests | JUnit 5 + Testcontainers (Neo4j) | Integration tests against a real Neo4j. |

---

## Files / modules to create (v1)

```
impact-cli/
├── pom.xml
├── src/main/java/io/spmp/impact/
│   ├── Main.java                          # picocli entry
│   ├── cmd/{Ingest,Analyze,Query,Wipe}Cmd.java
│   ├── extract/
│   │   ├── JavaProjectParser.java         # JavaParser + SymbolSolver setup
│   │   ├── CoreExtractor.java             # Class/Method/Field/CALLS
│   │   └── resolver/
│   │       ├── ServletResolver.java
│   │       ├── SchedulerResolver.java
│   │       ├── TaskRegistryResolver.java
│   │       ├── MessageConstantResolver.java
│   │       ├── SpiResolver.java
│   │       ├── DbTableResolver.java
│   │       └── PowerShellInvocationResolver.java
│   ├── graph/
│   │   ├── Neo4jWriter.java               # UNWIND-batched MERGE
│   │   ├── Schema.java                    # constraints + indexes bootstrap
│   │   └── CypherQueries.java             # slice queries as constants
│   ├── diff/
│   │   ├── JgitDiffSource.java
│   │   └── HunkToSymbolResolver.java      # AST walk for enclosing symbol
│   └── report/
│       ├── HtmlReportRenderer.java        # FreeMarker
│       └── JsonReportRenderer.java        # Jackson
├── src/main/resources/templates/report.ftl
└── src/test/java/...                      # JUnit + Testcontainers
```

---

## Phased delivery

| Phase | Deliverable | ~Days |
|---|---|---|
| **P1** | Core extractor: File/Class/Method/CALLS/EXTENDS into Neo4j. `ingest` works. | 3 |
| **P2** | jgit diff + hunk-to-symbol resolver. `analyze` prints changed methods. | 1 |
| **P3** | Forward/backward Cypher slices + HTML/JSON report. | 2 |
| **P4** | Boundary resolvers: Servlet, Scheduler, TaskRegistry (highest ROI for this repo). | 2 |
| **P5** | Remaining resolvers: SPI, DB tables, PowerShell, MessageConstants. | 2 |
| **P6** | Incremental ingest, commit snapshots, CI-gate `--fail-on`. | 1 |
| **Total v1** | | **~11 days** |

---

## Verification

End-to-end smoke test using assets already in the working directory:

1. **Bootstrap:**
   ```
   cd D:\SPMP\LoadBalancerV1\spmp-SPMP_4509_Load_Balancing\spmp-SPMP_4509_Load_Balancing
   git init && git add . && git commit -m "base"
   # then revert to pre-patch tip and commit again, OR apply the .patch on a branch
   ```
2. **Run Neo4j locally** (Docker): `docker run -p 7474:7474 -p 7687:7687 neo4j:5`
3. **Ingest:** `java -jar impact.jar ingest --src source/java --neo4j bolt://localhost:7687`
4. **Sanity Cypher** (in Neo4j Browser):
   - `MATCH (m:Method) RETURN count(m);` → expect ~8–10k.
   - `MATCH (:Class:TaskHandler)-[:HANDLES]->(t:TaskType) RETURN t.id;` → expect ~10 task types (`GrantPermission`, `RemovePermission`, etc.).
   - `MATCH (:Class:Servlet) RETURN count(*);` → expect dozens.
5. **Diff test:** modify `DistributedTaskCoordinator.submitTask` body on a branch; run:
   ```
   java -jar impact.jar analyze --base main --head feature/test --output html
   ```
   Expected in report: changed symbol = `submitTask`; backward reach includes `ManagementTaskHandler.handle`, `ListItemsDataCollector.run`, `SharingAccessDataCollector.run`; risk = HIGH (clustering.distributed.* on path).
6. **JUnit + Testcontainers**: each resolver has a fixture file + asserts the expected nodes/edges land in a containerized Neo4j.
7. **CI-gate behavior:** verify `--fail-on HIGH` returns exit code 2 on the above diff.

---

## Out of scope for v1 CLI (explicit non-goals)

- C#, Ember/JS, PowerShell extractors (v2 — boundary nodes are stubs in v1).
- Reflective call resolution beyond `Class.forName(<string-literal>)` (Phase 2 if needed).
- Multi-repo correlation.

> Web UI / REST service / test-case generation move from "out of scope" to **v2** below.

---

# v2 — Web Application + Test Case Generation (Zoho Catalyst hosted)

## v2 Context

After v1 ships the CLI, the team wants two things on top of it:

1. **A web UI** so non-CLI users (QA, release managers, tech leads) can run impact analysis from a browser.
2. **Test-case generation from impact analysis** — the high-value pivot. Given a diff, surface the **existing test cases** (from `TestCases_LoadBalancing.xlsx` / `TestCases.md`) that cover the changed surface, plus a list of **coverage gaps** and **auto-drafted test-case specs** for those gaps.

Hosting target: **Zoho Catalyst** (PaaS).

### User-confirmed v2 decisions

| Question | Choice |
|---|---|
| Web app scope | Generate test cases from impact analysis (primary) |
| Frontend stack | React + TypeScript + Vite |
| Auth | Basic username/password, Neo4j-backed user store (`:AppUser` nodes) |
| Backend host | Zoho Catalyst — framework chosen to fit Catalyst's runtime |

### Open item: Neo4j hosting on Catalyst

Catalyst does **not** offer a managed Neo4j. Three options, ranked:

1. **Self-host Neo4j on a separate VM (on-prem or cloud)** — backend connects from Catalyst over Bolt+TLS. Most realistic for an enterprise tool that handles internal source code.
2. **Run Neo4j inside an AppSail container** — possible but stateful workloads on AppSail are awkward (no persistent volume guarantees comparable to a real DB host).
3. **Replace Neo4j with Catalyst DataStore** — would force a redesign back to relational + recursive CTEs. Rejected (we already chose Neo4j).

Default to (1). Confirm during P7.

---

## v2 Architecture

```
┌─────────────────── Zoho Catalyst ───────────────────┐         ┌──────────────────────┐
│                                                      │         │   Neo4j 5.x          │
│   App Hosting                AppSail                 │ Bolt+TLS│   (self-hosted VM    │
│   ┌─────────────────┐       ┌───────────────────┐    │◄───────►│    or on-prem)       │
│   │ React + TS      │       │ Spring Boot 3.x   │    │         │                      │
│   │ + Vite SPA      │──────►│ (Java 17)         │    │         └──────────────────────┘
│   │ (static build)  │ /api  │                   │    │
│   └─────────────────┘       │ • impact-core lib │    │         ┌──────────────────────┐
│                             │ • impact-testgen  │    │ HTTPS   │   Git host           │
│   Catalyst Auth             │ • REST endpoints  │    │◄───────►│  (GitHub / Bitbucket │
│   (Zoho login or            │ • WebSocket logs  │    │  read   │   self-hosted Gitea) │
│   Basic via :AppUser)       └───────────────────┘    │         └──────────────────────┘
│                                       │              │
│   Catalyst Cache  ◄────── slice result cache         │
│   Catalyst File Store ◄── HTML reports, test specs   │
└──────────────────────────────────────────────────────┘
```

### New module structure

The current `impact-cli` Maven project is refactored into a **multi-module Maven build**:

```
impact-cli/                          (parent pom only)
├── impact-core/                     # NEW — extracted from current src
│     ├── extract/  (JavaProjectParser, CoreExtractor, BoundaryResolver, resolvers)
│     ├── graph/    (Schema, Neo4jWriter, CypherQueries)
│     ├── diff/     (JgitDiffSource, HunkToSymbolResolver)
│     ├── model/    (GraphNodes, GraphEdges, ImpactReport)
│     └── report/   (HtmlReportRenderer, JsonReportRenderer)
├── impact-cli/                      # thin wrapper: picocli + Main (delegates to core)
├── impact-testgen/                  # NEW — test-case knowledge base + coverage queries
│     ├── ingest/   (TestCasesXlsxIngestor, TestCasesMdIngestor → :TestCase nodes)
│     ├── match/    (CoverageQuery — matches impact to :TestCase via tags)
│     └── gen/      (GapSpecDrafter — drafts human-readable test-case specs for gaps)
└── impact-web/                      # NEW — Spring Boot + React
      ├── api/      (Spring Boot 3 backend, depends on impact-core + impact-testgen)
      └── ui/       (React + TS + Vite SPA — separate npm project)
```

`impact-cli` continues to ship as a fat-jar exactly as planned. `impact-core` becomes a regular library JAR that both CLI and web app depend on.

---

## Test-case generation design

### Inputs to the testgen pipeline

1. **Existing test cases** parsed from `TestCases.md` (markdown) and `TestCases_LoadBalancing.xlsx`. Each row becomes a `:TestCase` node with the structured fields from the spreadsheet (test ID, area, steps, expected result, severity).
2. **Tagging the test cases** — automatically and/or curated:
   - Free-text → keyword scan → match to entry-point FQNs / REST URLs / DB tables / task types in the graph.
   - Manual override file (`testcase-tags.yaml`) for cases the automatic tagger can't link.
3. **Impact report** (from `analyze` core call) — the set of reached entry points, REST URLs, DB tables, task types, schedulers.

### Output for a given diff

```
1. RECOMMENDED EXISTING TEST CASES (covering reached surface)
   ├── TC-LBF-014  "Failover when primary scheduler-node dies"
   ├── TC-LBF-021  "Distributed RemovePermission across 3 nodes"
   └── TC-LBF-029  "Schedule report when only one secondary alive"

2. COVERAGE GAPS (reached entry points with no covering :TestCase)
   ├── /RestAPI/WC/Clustering/updateSchedulerNode  (HIGH risk — no test)
   └── Scheduler: METrackerUpdateScheduler         (MEDIUM — no test)

3. AUTO-DRAFTED TEST-CASE SPECS for the gaps above
   ├── Draft TC: "updateSchedulerNode persists choice and survives a primary restart"
   │     Pre: cluster of 2 nodes; both healthy
   │     Steps: 1) call API with secondary-as-failover ... 2) ... 3) ...
   │     Expected: ... (filled from impact report — DB row appears, etc.)
   └── (similar for METrackerUpdateScheduler)
```

### New graph nodes/edges for test cases

| Label | Key | Notes |
|---|---|---|
| `:TestCase` | `id` | Imported from md/xlsx; carries title, area, severity, steps |
| `:TestSuite` | `name` | Container (e.g., "LBF Validation Plan") |

| Edge | From → To | Source |
|---|---|---|
| `:COVERS` | TestCase → Method/RestEndpoint/Scheduler/Job/DbTable/TaskType | Auto-tagged + manual overrides |
| `:IN_SUITE` | TestCase → TestSuite | Import |
| `:DRAFTED_FOR` | TestCase → Commit | For auto-drafted gap specs |

### Coverage Cypher (representative)

```cypher
// Existing test cases covering this diff
MATCH (m:Method:Changed)
MATCH (root:EntryPoint)-[:CALLS|DISPATCHES_TO*1..10]->(m)
MATCH (tc:TestCase)-[:COVERS]->(root)
RETURN DISTINCT tc.id, tc.title, count(DISTINCT root) AS coverage_score
ORDER BY coverage_score DESC;

// Coverage gaps
MATCH (m:Method:Changed)
MATCH (root:EntryPoint)-[:CALLS|DISPATCHES_TO*1..10]->(m)
WHERE NOT EXISTS { (:TestCase)-[:COVERS]->(root) }
RETURN DISTINCT root.fqn, labels(root);
```

---

## Web application design

### Backend — Spring Boot 3.x on Catalyst AppSail

**Why Spring Boot:**
- Same Java toolchain as `impact-core` / `impact-cli` — reuses them as library JARs without rewriting in another language.
- AppSail supports any container; ship as a Dockerfile (Eclipse Temurin 21 base image, fat-jar copy, `EXPOSE 8080`).
- Mature: Spring Security for basic-auth, Spring Data Neo4j (or raw driver) for graph access, Spring WebSocket for live progress.

**Endpoints (v2 minimal set):**

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/auth/login` | Username/password → JWT (Neo4j-backed `:AppUser`) |
| `POST` | `/api/v1/auth/logout` | Token invalidate |
| `GET`  | `/api/v1/repos` | Available ingested repos + commits |
| `POST` | `/api/v1/ingest` | Trigger ingest (async; returns job ID) |
| `GET`  | `/api/v1/jobs/{id}` | Job status + logs |
| `POST` | `/api/v1/analyze` | Body: `{ repo, base, head }` → impact report ID |
| `GET`  | `/api/v1/reports/{id}` | Impact + coverage + drafted gap specs (JSON) |
| `GET`  | `/api/v1/reports/{id}/html` | Server-rendered HTML version (FreeMarker) |
| `POST` | `/api/v1/testcases/import` | Upload `TestCases.xlsx` or `.md` → `:TestCase` nodes |
| `POST` | `/api/v1/cypher` | Ad-hoc Cypher (admin role only) |
| `WS`   | `/ws/jobs/{id}` | Live tail of ingest/analyze logs |

**Auth model:**
- On startup, ensure a constraint `:AppUser(username)` is unique.
- Bcrypt password hash stored on `:AppUser`.
- JWT signing key from env var `IMPACT_JWT_SECRET`.
- Roles: `VIEWER` (read reports, list), `DEV` (run analyze), `ADMIN` (ingest, Cypher, user mgmt).

**Caching:**
- Catalyst Cache for slice query results, keyed by `(commit_sha, changed_symbol_set_hash)`. TTL 1 hour.
- Reports persisted to Catalyst File Store under `reports/{report_id}.html|json`.

### Frontend — React + TypeScript + Vite

**Pages (v2):**

| Route | Purpose |
|---|---|
| `/login` | Username/password form |
| `/` | Dashboard — recent reports, repo health, recent commits |
| `/analyze` | Form: pick repo, base, head → kicks off analyze → renders report |
| `/reports/:id` | Full impact + coverage + gap specs (the heart of v2) |
| `/reports/:id/graph` | Embedded graph view (Cytoscape.js, filtered to changed + reached) |
| `/testcases` | List `:TestCase` library, search/filter, tag override editor |
| `/admin` | Ingests, jobs, users, Cypher console (admin role) |

**Key libraries:**
- React Router 6, TanStack Query (data fetching/cache), Zod (schema validation).
- Cytoscape.js for graph visualization (lighter and more performant than react-flow for read-only graphs).
- Tailwind CSS + shadcn/ui for the design system (matches enterprise tooling aesthetic).

**Build & deploy:**
- `npm run build` → `dist/` → uploaded to Catalyst App Hosting.
- Backend URL injected at build time via `VITE_API_BASE_URL` (Catalyst project's AppSail endpoint).
- SPA history fallback configured in `app-config.json` (`"appRouting": true`).

---

## Zoho Catalyst deployment

### Resources used

| Catalyst resource | Used for |
|---|---|
| **AppSail** | Spring Boot backend (Java/Dockerfile) |
| **App Hosting** | React SPA static site |
| **Catalyst Cache** | Slice result cache (Memcached-like API) |
| **Catalyst File Store** | Persisted HTML/JSON reports |
| **Catalyst Logs** | App + access logs |
| **Catalyst Auth (optional)** | If we move from `:AppUser` to Zoho login later |

### Deployment artifacts (new files)

```
impact-web/api/
├── Dockerfile                       # Temurin 21 + impact-web-api.jar
├── application.yml                  # Spring config (Neo4j URL, JWT, CORS)
└── catalyst-appsail.json            # AppSail config: port, env vars, memory

impact-web/ui/
├── catalyst.json                    # Catalyst project descriptor
├── app-config.json                  # App-Hosting routing config
└── .env.production                  # VITE_API_BASE_URL=<AppSail URL>
```

### Topology decision (open)

- **Neo4j** lives outside Catalyst (separate VM, hardened, TLS on Bolt 7687).
- Backend reaches it via outbound HTTPS/Bolt+TLS — Catalyst allows arbitrary outbound network.
- Source code repos accessed via outbound git over HTTPS (Bitbucket Server / GitHub Enterprise).

---

## Updated phased delivery

> Execution kicks off with the **smoke-test gate** (verifying v1 P1 actually runs against the SPMP source tree) before any further code work — per your "proceed 1" instruction.

| Phase | Deliverable | ~Days |
|---|---|---|
| **P1 gate** | `mvn package` → `impact.jar`; start Neo4j; smoke-test `ingest` against `…\source\java`; sanity-check counts. | 0.5 |
| **P2** | jgit diff + hunk-to-symbol resolver. `analyze` prints changed methods. | 1 |
| **P3** | Forward/backward Cypher slices + HTML/JSON report. | 2 |
| **P4** | Boundary resolvers: Servlet, Scheduler, TaskRegistry. | 2 |
| **P5** | Remaining resolvers: SPI, DB tables, PowerShell, MessageConstants. | 2 |
| **P6** | Incremental ingest, commit snapshots, CI-gate `--fail-on`. | 1 |
| **P7** | **Refactor** to multi-module Maven (`impact-core` + `impact-cli`); decide & document Neo4j host. | 1 |
| **P8** | **impact-testgen** — ingest `TestCases.md` + `.xlsx` as `:TestCase` nodes; auto-tag via keyword scan; manual override yaml; coverage Cypher. | 3 |
| **P9** | **impact-web/api** — Spring Boot 3, basic auth on `:AppUser`, REST endpoints, WebSocket logs, FreeMarker HTML render. | 4 |
| **P10** | **impact-web/ui** — React+TS+Vite SPA: login, dashboard, analyze, report, testcases, admin. Cytoscape.js graph view. | 5 |
| **P11** | **Catalyst deploy** — Dockerfile, AppSail config, App Hosting config, env wiring, smoke deploy with a tiny demo dataset. | 2 |
| **Total v2 add-on** | | **~15 days** |
| **Combined v1 + v2** | | **~26 days** |

---

## Acceptance criteria additions for v2

8. Import `TestCases_LoadBalancing.xlsx` → at least 30 `:TestCase` nodes, ≥ 60% auto-tagged against existing `:RestEndpoint` / `:Scheduler` / `:DbTable` nodes.
9. Web `/analyze` for the `submitTask` test diff returns a report showing:
   - ≥ 2 existing covering test cases,
   - ≥ 1 coverage gap with an auto-drafted spec,
   - Cytoscape graph view rendering the changed-symbol neighborhood under 2 s.
10. Login → analyze → report flow works end-to-end in a Catalyst dev environment.
11. AppSail deploy succeeds; cold start to first request < 30 s.

---

## Critical files / modules to create for v2

```
impact-core/                                            # (refactor target)
impact-testgen/src/main/java/io/spmp/impact/testgen/
   ├── ingest/TestCasesXlsxIngestor.java                # Apache POI parser
   ├── ingest/TestCasesMdIngestor.java                  # markdown table parser
   ├── ingest/TestCaseTagger.java                       # keyword → graph node match
   ├── match/CoverageQuery.java                         # Cypher: covers + gaps
   └── gen/GapSpecDrafter.java                          # gap → human-readable spec

impact-web/api/src/main/java/io/spmp/impact/web/
   ├── WebApplication.java                              # Spring Boot main
   ├── config/{SecurityConfig,Neo4jConfig,CorsConfig}.java
   ├── auth/{AuthController,JwtService,AppUserService}.java
   ├── api/{AnalyzeController,ReportController,TestCaseController,IngestController}.java
   ├── ws/JobLogWebSocket.java
   └── render/HtmlReportController.java                 # delegates to impact-core renderer

impact-web/ui/                                          # standalone npm project
   ├── package.json, vite.config.ts, tsconfig.json
   ├── src/api/client.ts                                # typed fetch wrapper
   ├── src/pages/{Login,Dashboard,Analyze,Report,TestCases,Admin}.tsx
   ├── src/components/{GraphView,CoverageTable,GapSpecCard,RiskBadge}.tsx
   └── src/lib/{auth,query,types}.ts
```

---

## v2 Verification (Catalyst smoke test)

1. **Local-first:** run `impact-web/api` against local Neo4j + `impact-web/ui` via Vite dev server. Login as seeded `admin`, run `analyze` against the SPMP repo, see test-case coverage in the report.
2. **Catalyst preview:** push to a Catalyst dev project. AppSail builds the Java container, App Hosting serves the SPA. Smoke the same flow against a self-hosted Neo4j VM.
3. **Test-case import smoke:** upload `TestCases_LoadBalancing.xlsx`; confirm `:TestCase` count and `:COVERS` edge count.

---

## Risks & mitigations (v2)

| Risk | Mitigation |
|---|---|
| Neo4j connectivity from Catalyst (latency/egress) | Self-host Neo4j in the same cloud region as the Catalyst project; keep slice queries cached. |
| AppSail cold start hurts user experience | Set min instances ≥ 1; lazy-init non-critical Spring beans. |
| Auto-tagging test cases is noisy | Show coverage edges with `confidence` score; let admins curate via the testcase override yaml. |
| Refactor risk in P7 (splitting modules can break the CLI) | Keep the CLI fat-jar smoke test as a P7 acceptance gate before merging. |
| Spreadsheet schema drift in `TestCases.xlsx` | Driver detects header changes and surfaces a "schema mismatch" error instead of silently dropping rows. |