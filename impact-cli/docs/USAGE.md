# Usage

Every CLI command, every flag, and the Neo4j Cypher commands you'll want for spot-checking
the graph.

Prerequisites covered in [PREREQUISITES.md](PREREQUISITES.md). Start your Neo4j instance
before any of these commands.

---

## 1. Build the jar (one time)

```powershell
cd impact-cli
mvn -DskipTests package
# Produces:  target/impact.jar  (fat-jar, ~41 MB)
```

If `mvn` is missing see [PREREQUISITES.md §3](PREREQUISITES.md#3-maven-39).
If the build fails, run with `-e` for the stack trace, or `-X` for full debug.

---

## 2. The CLI surface

```
java -jar target/impact.jar <subcommand> [options]
```

Subcommands:

| Verb | Purpose |
|---|---|
| `ingest` | Parse a source tree and write the call graph to Neo4j |
| `analyze` | Take a `.patch` (or `git diff`), compute impact, write report files |
| `query` | Run an ad-hoc Cypher query against the graph |
| `wipe` | Delete a commit snapshot or the whole graph |
| `snapshots` | List ingested commit snapshots |
| `testcases` | Import existing test cases from `.md` / `.xlsx` and tag them against the graph |
| `repos` | List repositories available to the configured Zoho token (no graph access needed) |
| `ts-smoke` | Internal: verify the tree-sitter JNI bindings load on this host |

All subcommands share these connection flags via the `Neo4jOptions` mixin:

| Flag | Default | Notes |
|---|---|---|
| `--neo4j <uri>` | `bolt://localhost:7687` | URI scheme picks the transport: `bolt://` / `neo4j://` → native Bolt; `http://` / `https://` → REST transactional API |
| `--user <name>` | `neo4j` | |
| `--pass <pw>` | env `NEO4J_PASS` else `neo4j` | Or set the env var so it's not in shell history |

---

## 3. `ingest` — build the graph

Parses the source tree, runs all resolvers (Java + REST XML + DB XML + HTML + JS + HBS +
C# + …), and writes everything to Neo4j as one snapshot keyed on `--commit`.

```powershell
java -jar target/impact.jar ingest `
  --src "C:\proj\my-product\source\java" `
  --neo4j http://localhost:7474 `
  --user neo4j `
  --pass neo4j-password
```

### Options

| Flag | Default | Notes |
|---|---|---|
| `--src <dir>` | **required** | Root directory of Java sources (recursive). Typically `<repo>/src/main/java` or `<repo>/source/java`. |
| `--repo-id <id>` | name of `--src` parent | Tags every node with this repo id (multi-repo ready) |
| `--commit <sha>` | `HEAD` | Snapshot key. Use a real SHA for incremental ingest. |
| `--incremental` | off | Skip files whose content-hash matches the previous ingest of this commit |
| `--js-root <dir>` | auto: `<src>/../ember/app` | Ember JS root for `JsEmberResolver` |
| `--html-root <dir>` | auto: `<src>/../html` | HTML page root for `HtmlPageResolver` |
| `--cs-root <dir>` | auto: `<src>/../c_sharp` | C# source root for `CsSharepointResolver` |
| `--xml-conf <dir>` | auto: `<src>/../../product_package/conf` | XML config root for `RestApiXmlResolver` + `DbSchemaXmlResolver` |
| `--dep <repoId>=<javaRoot>` | none | **Multi-repo** — register a dependency repo (repeatable). Its Java source is fed to the same global index so cross-repo inheritance/calls resolve, and its `product_package/conf` is auto-detected for schema/REST XML. See [§13 Multi-repo ingest](#13-multi-repo-ingest-dependent-repos). |
| `--audit-log <path>` | none | Write a detailed per-section audit log (PASS1/INDEX/PASS2/PASS3/CALL_KINDS/RESOLVER_STRATEGY + post-ingest graph audit). A `<path>.calls.tsv` sibling file records every call site with resolve strategy + nanos for ad-hoc analysis. |

### Architecture (since 2026-05)

The Java extractor is a **three-pass index-driven pipeline** — no SymbolSolver hangs, no timeouts, no skips:

1. **Pass 1** (parallel, per file, no SymbolSolver): walk every type declaration once with a manual recursive visitor; emit `:Class` / `:Method` / `:Field` nodes + `:EXTENDS` / `:IMPLEMENTS` edges; collect `CallSite` records into a per-thread side table.
2. **Index freeze**: convert all build-phase maps to immutable copies for lock-free reads.
3. **Pass 2** (parallel): for each `CallSite`, run the 7-strategy resolver (`enclosing-this` / `this` / `super` / `static-class` / `local-var` / `field-ref` / `constructor`) using the frozen index. Ambiguous lookups emit ALL candidate edges as `kind="ambiguous-of-N"` (over-approximation is safe for impact analysis).
4. **Pass 3**: `:OVERRIDES` via memoized ancestor walks.

Net: 4,351 Java files across 3 repos finishes in ~3 min on `-Xmx4g`. The previous SymbolSolver-based path hung indefinitely on monster files; the index path is O(1) per call. The graph reaches **106% of the original SymbolSolver baseline OVERRIDES count** while running 100× faster. Flags from the old approach (`--lite-repo`, `--per-file-timeout`) are no longer needed and have been removed.

### What it prints

```
[ingest] src=C:\proj\my-product\source\java
[ingest] repo=my-product commit=HEAD
[TaskRegistryResolver] handlers=9  taskTypes=9
[DbTableResolver] query-sites=710  resolved=116  unique-tables=76
[MessageConstantResolver] constants=32  sites=12
[RestApiXmlResolver] resolved 132 real REST endpoints from XML config
[DbSchemaXmlResolver] resolved 306 tables, 2425 columns from data-dictionary.xml
[HtmlPageResolver] indexed 28 html pages, 0 URL references
[JsEmberResolver] indexed 706 JS files, 69 URL refs, 61 import edges
[HbsTemplateResolver] indexed 358 HBS templates, 919 USES_COMPONENT edges
[CsSharepointResolver] indexed 138 CS files, 14 URL refs, 5 script refs
[CoreExtractor] emitted 379 :OVERRIDES edges
[ingest] parsed 900 files, 934 classes, 5487 methods, 75972 call edges
[ingest] graph write complete.
```

Typical timing: **30–60 s** for ~1000 Java files on a developer laptop.

### Worked example — generating the SPMP graph

Building the call graph for the SPMP Load-Balancing repo (900 Java files + 706 Ember JS
files + 358 HBS templates + 138 C# files + 28 HTML pages):

```powershell
# 0. Start Neo4j (one-time) — Docker is easiest
docker run -d --name impact-neo4j -p 7474:7474 -p 7687:7687 `
  -e NEO4J_AUTH=neo4j/neo4j-password `
  -e NEO4J_dbms_memory_heap_max__size=2G `
  neo4j:5

# 1. Wipe any prior snapshot (skip on first run)
java -jar target\impact.jar wipe --all `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password

# 2. Ingest — note --js-root, --html-root, --cs-root, --xml-conf so every layer is parsed
java -Xmx4g -jar target\impact.jar ingest `
  --src       "D:\SPMP\LoadBalancerV1\spmp-SPMP_4509_Load_Balancing\spmp-SPMP_4509_Load_Balancing\source\java" `
  --js-root   "D:\SPMP\LoadBalancerV1\spmp-SPMP_4509_Load_Balancing\spmp-SPMP_4509_Load_Balancing\web\spmp\emberapp" `
  --html-root "D:\SPMP\LoadBalancerV1\spmp-SPMP_4509_Load_Balancing\spmp-SPMP_4509_Load_Balancing\web\spmp\html" `
  --cs-root   "D:\SPMP\LoadBalancerV1\spmp-SPMP_4509_Load_Balancing\spmp-SPMP_4509_Load_Balancing\source\c_sharp" `
  --xml-conf  "D:\SPMP\LoadBalancerV1\spmp-SPMP_4509_Load_Balancing\spmp-SPMP_4509_Load_Balancing\product_package\conf" `
  --repo-id   spmp `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

Console output (annotated — each line maps to one resolver / one pipeline phase):

```
[ingest] src=…\source\java  repo=spmp  commit=HEAD
[CoreExtractor] Pass 1 — declarations (parallel) ... 900 files in 18.4s
[CoreExtractor] Index frozen: 934 classes, 5487 methods, 12064 fields
[CoreExtractor] Pass 1b — body collection ... 81923 call-sites in 22.1s
[CoreExtractor] Pass 2 — call resolution ... 75972 edges (virtual=62%, static=18%, ambiguous=12%, unresolved=8%) in 14.7s
[CoreExtractor] Pass 3 — :OVERRIDES via memoized ancestor walks ... 379 edges in 0.9s
[ServletResolver]      marked 47 :Servlet classes, 47 :RestEndpoint nodes
[SchedulerResolver]    marked 6 :Scheduler / :Job classes
[TaskRegistryResolver] handlers=9  taskTypes=9
[MessageConstantResolver] constants=32  sites=12
[DbTableResolver]      query-sites=710  resolved=116  unique-tables=76
[RestApiXmlResolver]   resolved 132 real REST endpoints from XML config
[DbSchemaXmlResolver]  resolved 306 tables, 2425 columns from data-dictionary.xml
[HtmlPageResolver]     indexed 28 html pages, 0 URL references
[JsEmberResolver]      indexed 706 JS files, 69 URL refs, 1135 import edges
[HbsTemplateResolver]  indexed 358 HBS templates, 2458 USES_COMPONENT edges
[CsSharepointResolver] indexed 138 CS files, 14 URL refs, 5 script refs
[PowerShellResolver]   indexed 114 .ps1 scripts
[ingest] graph write phase 1/3 — nodes ........ 25.4 s
[ingest] graph write phase 2/3 — edges ........ 41.2 s
[ingest] graph write phase 3/3 — boundary refs .. 6.8 s
[ingest] parsed 900 files, 934 classes, 5487 methods, 75972 call edges
[ingest] graph write complete in 73.4 s. Total: 130.5 s
```

**Verify the graph populated correctly** — run these via `impact query "..."` or the
Neo4j Browser:

```cypher
// (1) Node counts by label — expect every layer represented
MATCH (n) RETURN labels(n)[0] AS label, count(*) AS n ORDER BY n DESC;
```

Expected output for the SPMP single-repo ingest:

```
label              n
─────────────────────
Method           5487
Field           12064
Class             934
JsFile            706
DbColumn         2425
HbsTemplate       358
DbTable           306
CsFile            138
RestEndpoint      132
PsScript          114
HtmlPage           28
MessageConstant    32
TaskType            9
Commit              1
Repo                1
```

```cypher
// (2) Edge counts by type — every cross-layer relation should be non-zero
MATCH ()-[r]->() RETURN type(r) AS edge, count(*) AS n ORDER BY n DESC;
```

Expected output (20 edge types):

```
edge                  n
──────────────────────────
CONTAINS          18573
CALLS             75972
HAS_COLUMN         2425
USES_COMPONENT     2458
IMPORTS            1135
READS              8412
WRITES             3201
OVERRIDES           379
EXTENDS             449
IMPLEMENTS          213
EXPOSES             132
HANDLES               9
DISPATCHES_TO        24
CALLS_API            83
INVOKES_SCRIPT        5
RENDERS_TEMPLATE    104
READS_TABLE         412
WRITES_TABLE        298
SENDS_MESSAGE        12
HAS_SNAPSHOT          1
```

```cypher
// (3) Sample cross-layer slice — every REST URL with its exposing servlet + JS callers
MATCH (c:Class:Servlet)-[:EXPOSES]->(u:RestEndpoint)
OPTIONAL MATCH (js:JsFile)-[:CALLS_API]->(u)
RETURN u.url AS url, c.simple_name AS servlet, count(DISTINCT js) AS js_callers
ORDER BY js_callers DESC LIMIT 10;
```

If any **edge count is zero** that shouldn't be, that layer's resolver didn't fire — check
that `--js-root` / `--html-root` / `--cs-root` / `--xml-conf` actually point to existing
directories (the tool prints `[Resolver] skipped: <dir> not found` when a path is missing).

### Worked example — multi-repo graph (ADSM + ADSF)

When the primary product depends on a framework repo, ingest both together so cross-repo
inheritance / call edges resolve. See [§13 Multi-repo ingest](#13-multi-repo-ingest-dependent-repos)
for the full recipe; the short version is:

```powershell
java -Xmx6g -jar target\impact.jar ingest `
  --src       "D:\repos\adsm-...\source\java_source" `
  --js-root   "D:\repos\adsm-...\web\adsm\emberapp" `
  --html-root "D:\repos\adsm-...\web\adsm\html" `
  --cs-root   "D:\repos\adsm-...\source\c_sharp" `
  --xml-conf  "D:\repos\adsm-...\product_package\conf" `
  --repo-id   adsm `
  --dep       "adsf=D:\repos\adsf-...\source\java_source" `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

The dep's `product_package/conf` / `c_sharp/` / `.ps1` files are auto-detected. Expected
key counts after the ADSM+ADSF combined run:

| Layer | ADSM alone | ADSM + ADSF dep |
|---|---:|---:|
| `:Method` | ~22 000 | ~39 700 |
| `:OVERRIDES` edges | **2** | **2 974** |
| Cross-repo OVERRIDES | 0 | **201** |
| `:DbTable` | 76 | 1 326 |
| `:DbColumn` | 2 425 | 7 629 |

The jump from 2 → 2,974 OVERRIDES is the headline: every ADSM child class extending an
ADSF base class now has a resolvable parent method to point at, so backward-slice queries
from a patched override can reach callers of the base declaration.

---

## 3.5 Remote repository (Zoho API)

Instead of pointing the tool at a local filesystem path, you can have it **fetch the
source directly from the Zoho Repository API**
(https://prezohoweb.zoho.com/repository/api/overview.html). Useful for QA / PM
workflows where the user doesn't have a local clone but knows the repo ID.

### Discover

```powershell
java -jar target/impact.jar repos `
  --remote-org <orgId> `
  --repo-token $env:IMPACT_REPO_TOKEN
```

Prints a table:

```
repo_id      name                       default_branch  last_commit
-----------  -------------------------  --------------  ------------
d7f3a1...    spmp-load-balancing        main            51dac98446
88b2cc...    adsm-issue-fixes           main            1660081c13
```

### Ingest from a remote repo

```powershell
java -jar target/impact.jar ingest `
  --remote-org <orgId> `
  --remote-repo <repoId> `
  --repo-token $env:IMPACT_REPO_TOKEN `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

The tool will: (1) fetch repo metadata, (2) clone via HTTPS using the token as
credentials, (3) materialize the working tree under `~/.impact-cli/cache/<orgId>/<repoId>/<sha>/`,
and (4) run the standard 3-pass extractor against the materialized tree. Subsequent
invocations with the same resolved SHA hit cache instantly.

For multi-repo ingest, pair `--remote-repo` (primary) with `--remote-dep`:

```powershell
java -jar target/impact.jar ingest `
  --remote-org abc `
  --remote-repo <adsm-repo-id> `
  --remote-dep adsf=<adsf-repo-id> `
  --remote-dep webclient=<webclient-repo-id> `
  --repo-token $env:IMPACT_REPO_TOKEN `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

### Analyze a commit range from a remote repo

```powershell
java -jar target/impact.jar analyze `
  --remote-org <orgId> `
  --remote-repo <repoId> `
  --repo-token $env:IMPACT_REPO_TOKEN `
  --remote-base <baseSha> `
  --remote-head <headSha> `
  --output both `
  --out report.html `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

The repo is materialized exactly as in ingest; the existing `JgitDiffSource` then
computes the diff between the two SHAs locally — no dependency on a Zoho patch-fetch
endpoint.

### Flags

| Flag | Default | Notes |
|---|---|---|
| `--remote-repo <repoId>` | — | Zoho repository ID. Triggers remote mode; mutually exclusive with `--src` / `--repo` (filesystem-path inputs). |
| `--remote-org <orgId>` | — | Zoho organization ID. Required when `--remote-repo` is set. |
| `--repo-token <token>` | env `IMPACT_REPO_TOKEN` | Auth token. Falls back to the env var so secrets stay out of shell history. |
| `--remote-base-url <url>` | `https://prezohoweb.zoho.com/repository` | Override for staging / self-hosted Zoho deployments. |
| `--remote-auth-scheme <scheme>` | `Zoho-oauthtoken` | Authorization header scheme — sent as `<scheme> <token>`. |
| `--remote-ref <branch\|tag\|sha>` | repo's `default_branch` | Branch / tag / SHA to materialize. |
| `--remote-cache <dir>` | `~/.impact-cli/cache` | Cache directory. Layout: `<dir>/<orgId>/<repoId>/<resolvedSha>/`. |
| `--remote-refresh` | off | Force re-clone even if cache hit. |
| `--remote-dep <localId>=<repoId>` | — | (ingest only) Dep repo for multi-repo ingest. Repeatable. |
| `--remote-base <sha>` | — | (analyze only) Base SHA. Pair with `--remote-head`. |
| `--remote-head <sha>` | repo HEAD | (analyze only) Head SHA. |
| `--remote-list-endpoint <path>` | `/orgs/{orgId}/api/v1/repos` | Endpoint-shape override for list-repos. Placeholders: `{orgId}`. |
| `--remote-meta-endpoint <path>` | `/orgs/{orgId}/repos/{repoId}/api/v1` | Endpoint-shape override for repo metadata. Placeholders: `{orgId}`, `{repoId}`. |

### Exit codes (remote-specific)

| Code | Meaning |
|---|---|
| `10` | Auth failed (HTTP 401 / 403). Check `--repo-token`. |
| `11` | Required arg missing (e.g. `--remote-org`) OR resource not found (HTTP 404). |
| `12` | Network error reaching Zoho API. |
| `13` | Partial download; cache invalidated, retry suggested. |
| `14` | API response shape mismatch — likely the endpoint path differs from the default; override via `--remote-list-endpoint` / `--remote-meta-endpoint`. |

### Notes

- The public Zoho Repository API overview only documents URL roots; concrete path
  shapes are parameterized via the endpoint-override flags so they're adjustable once
  full Zoho docs are confirmed.
- The tool never calls a "fetch patch" endpoint — once the repo is materialized,
  jgit computes diffs locally. Works regardless of whether Zoho exposes patch
  endpoints.
- Cache invalidation is SHA-based, never time-based. Re-running with the same SHA
  is always free; a different SHA always re-clones.

### Read-only contract — strict

The Zoho-integration code is **read-only by design**. Specifically:

| Operation | Why it's read-only |
|---|---|
| HTTP calls to the Zoho API | All requests use `GET` only — no `POST` / `PUT` / `DELETE` / `PATCH` anywhere in `RemoteRepositoryClient` |
| `git clone` via jgit | Pure read against the remote — equivalent to running `git clone` manually |
| `git push` / `git commit` / `git fetch` (post-clone) | **NEVER called** — verified by audit (`grep .push\(\|.commit\(\|.fetch\( remote/` returns no matches) |
| Cached repo's push URL | After clone, the local `remote.origin.pushurl` is set to a placeholder (`impact-cli-read-only://no-push-from-cache`) so any subsequent `git push` from the cache directory fails fast |
| Token persistence | The Zoho token is passed to jgit only as a `CredentialsProvider`; jgit does **not** persist it in `.git/config`. The cached repo carries no embedded credentials. |

The tool **only reads** from the Zoho Repository API — there is no code path that
writes back, modifies, or otherwise affects the source repository.

---

## 4. `analyze` — generate the impact report

Two modes: **patch file** (recommended — works without a git tree) and **base/head git refs**.

### Patch file mode

```powershell
java -jar target/impact.jar analyze `
  --patch "C:\proj\changes\my-change.patch" `
  --repo "C:\proj\my-product" `
  --output both `
  --out "C:\reports\impact-report.html" `
  --neo4j http://localhost:7474 `
  --user neo4j `
  --pass neo4j-password
```

### Git mode

```powershell
java -jar target/impact.jar analyze `
  --base main `
  --head feature/permission-batches `
  --repo "C:\proj\my-product" `
  --output both `
  --out "C:\reports\impact-report.html" `
  --neo4j http://localhost:7474 `
  --user neo4j `
  --pass neo4j-password
```

### Options

| Flag | Default | Notes |
|---|---|---|
| `--patch <file>` | — | Unified-diff `.patch` file. Mutually exclusive with `--base`. |
| `--base <ref>` | — | Base git revision. Requires `--repo` to be a git working tree. |
| `--head <ref>` | `HEAD` | Head git revision. Used only with `--base`. |
| `--repo <path>` | **required** | Project root. For `--patch`, used to resolve patch-relative paths and read HEAD-side file content. |
| `--src <path>` | auto: `<repo>/source/java` if present, else `<repo>` | Java source root. Used by the analyze-side hunk resolver to re-parse changed files; **not** required to match ingest's `--src`. |
| `--output <fmt>` | `stdout` | `stdout` (console only) / `json` / `html` / `both` (writes both + Markdown) |
| `--out <path>` | `impact-report.html` | Output file path. For `both`, the JSON/MD siblings derive from this. |
| `--gen-md <path>` | sibling of `--out` | Override path for the generated test-cases markdown |
| `--no-gen` | off | Skip generated-test-case output (just the slice report) |
| `--depth <n>` | `6` | Max BFS depth for the Cypher slice queries (forward + backward) |
| `--fail-on <level>` | — | Exit code 2 if overall risk ≥ `LOW`/`MEDIUM`/`HIGH`. Use in CI gates. |
| `--show-coverage` | off | Render the optional Test-Case Coverage panel (requires `testcases import` first) |

### What it prints

```
[analyze] repo=C:\proj\my-product
[analyze] patch=C:\proj\changes\my-change.patch
[analyze] files in patch: 72  (java=57, polyglot=15)
... per-file symbol listing ...
[analyze] changed symbols: 395
[analyze] running slices against http://localhost:7474 (depth=6) ...
[analyze] polyglot changes resolved: 15
[analyze] fqn reconciliation: 390 input methods -> 407 graph methods (unmapped: 0)
[analyze] virtual-dispatch expansion: 407 changed -> 410 after override-parents (+3)
[analyze] overall risk: HIGH   (HIGH=361 MEDIUM=4 LOW=45)
[analyze] entry points reached: 1139   forward reach total: 8847
[analyze] impact by layer: java=390/5  rest=22  db=12/85cols  js=0  html=0  cs=2  ps=0  tasks=9  msgs=7
[analyze] polyglot in patch: total=15  js=1  hbs=1  cs=1  xml=6  properties=5  other=1
[analyze] affected: APIs=23  DB tables=13  Schedules=13  UI components=4
[analyze] generated 39 test cases across 8 area(s): CFG=9 CS=1 DB=1 DIST=3 HBS=1 JS=1 MGMT=21 REST=2
[analyze] wrote JSON: C:\reports\impact-report.json
[analyze] wrote HTML: C:\reports\impact-report.html
[analyze] wrote MD:   C:\reports\generated-testcases.md
```

How to read those output files is covered in [REPORT_GUIDE.md](REPORT_GUIDE.md).

### Worked example — SPMP polyglot patch

Running `analyze` against the SPMP Load-Balancing patch
(`51dac98446...cccb0f841b.patch`, 72 files, 395 changed symbols) produces these report
artifacts:

```powershell
java -Xmx4g -jar target\impact.jar analyze `
  --patch "D:\SPMP\LoadBalancerV1\51dac98446...cccb0f841b.patch" `
  --repo  "D:\SPMP\LoadBalancerV1\spmp-SPMP_4509_Load_Balancing\spmp-SPMP_4509_Load_Balancing" `
  --output both `
  --out   "D:\SPMP\LoadBalancerV1\spmp-impact-report.html" `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

The **"Affected by Task / Action"** section is the most useful pivot for QA — every URL,
DB table, schedule, and UI component the patch can touch, grouped by the user-visible
feature it belongs to. Top of `generated-testcases.md` now looks like:

```markdown
## Affected by Task / Action (38 — 23 Actions, 1 Unclassified, 9 TaskTypes, 2 TaskHandlers, 2 Schedulers, 1 Report)

Every URL, DB table, schedule and UI component this patch can affect, **grouped by the
user-visible Task or Action** it belongs to. Useful for QA: one row per feature = one
set of regression tests to run.

### Actions — 23 features (23 URLs · 25 DB tables · 0 schedules · 1 UI components)

| Risk | Feature | URLs | DB tables | Schedules | UI components |
| --- | --- | --- | --- | --- | --- |
| **HIGH** | **Add Site Collection Administrator** | servlet:AddSiteCollectionAdministrator | ManagementOperationResults, ManagementOperationsHistory | — | — |
| **HIGH** | **Check Permissions** | servlet:CheckPermissions | ManagementOperationResults, ManagementOperationsHistory | — | — |
| **MEDIUM** | **update Scheduler Node** | /RestAPI/WC/Clustering/updateSchedulerNode | — | — | components/clustering/clustering-postconfig.js |
… (20 more Action rows) …

### TaskTypes — 9 features (0 URLs · 0 DB tables · 9 schedules · 0 UI components)

| Risk | Feature | URLs | DB tables | Schedules | UI components |
| --- | --- | --- | --- | --- | --- |
| **MEDIUM** | **Grant Permission** | — | — | GrantPermissionTaskHandler | — |
| **MEDIUM** | **Check Permission** | — | — | CheckPermissionTaskHandler | — |
… (7 more TaskType rows) …

### Schedulers — 2 features (0 URLs · 1 DB tables · 2 schedules · 0 UI components)
### Reports — 1 feature (0 URLs · 1 DB tables · 0 schedules · 0 UI components)
### TaskHandlers — 2 features (0 URLs · 0 DB tables · 2 schedules · 0 UI components)
### Unclassifieds — 1 feature (0 URLs · 8 DB tables · 0 schedules · 3 UI components)
```

The HTML report (`spmp-impact-report.html`) renders the same data — a separate `<h3>`
heading and table per kind, with risk colour-coded:

```
Affected by Task / Action (38)
─────────────────────────────────
Actions — 23 features  (23 URLs · 25 DB tables · 0 schedules · 1 UI components)
  ┌──────────┬────────────────────────────┬─────────────────┬─────────────┐
  │ Risk     │ Feature                    │ URLs            │ DB tables   │ …
  ├──────────┼────────────────────────────┼─────────────────┼─────────────┤
  │ HIGH     │ Add Site Collection Admin… │ servlet:AddSit… │ Management… │
  │ HIGH     │ Check Permissions          │ servlet:CheckP… │ Management… │
  …
  └──────────┴────────────────────────────┴─────────────────┴─────────────┘

TaskTypes — 9 features  (0 URLs · 0 DB tables · 9 schedules · 0 UI components)
  ┌──────────┬────────────────────────┬─────┬─────────────┬────────────────────────────┐
  │ Risk     │ Feature                │ URLs│ DB tables   │ Schedules                  │ …
  ├──────────┼────────────────────────┼─────┼─────────────┼────────────────────────────┤
  │ MEDIUM   │ Grant Permission       │ —   │ —           │ GrantPermissionTaskHandler │
  …
```

**How to use it** — pick a row from any subsection (e.g. *"update Scheduler Node"* under
Actions, MEDIUM risk). The Feature column tells QA *what to test* in plain English; the
URLs / DB tables / Schedules / UI components columns tell them *which surfaces to
exercise* for that one feature. One row = one regression test family.

Other useful sections in the same report:

| Section | What it answers |
|---|---|
| **APIs Affected** (23 rows) | Every REST URL the patch can hit, with the feature it belongs to and the JS / C# / HTML callers per URL |
| **Database Tables Affected** (13 rows) | Every DB table touched, with column-level detail and writer-vs-reader method lists |
| **Schedules Affected** (13 rows) | Every scheduled job / task handler reached, with the TaskType display name |
| **UI Components Affected** (4 rows) | Every Ember JS file / HBS template touched directly or reachable via the API graph |
| **Generated Test Cases** (39 rows) | Plain-English manual test cases, grouped by area (DIST, MGMT, REPT, CFG, DB, JS, HBS, CS, REST) |

See [REPORT_GUIDE.md](REPORT_GUIDE.md) for a section-by-section walkthrough and risk-scoring rules.

---

## 5. `query` — ad-hoc Cypher

```powershell
java -jar target/impact.jar query `
  "MATCH (n) RETURN labels(n)[0] AS label, count(*) AS n ORDER BY n DESC" `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

Prints one line per result row as a `{column=value, …}` map.

Use this for spot-checking after ingest, debugging slice queries, or scripted exports.
See the [Cypher cheatsheet](#9-neo4j-cypher-cheatsheet) below.

---

## 6. `wipe` — delete a snapshot or the whole graph

```powershell
# Wipe everything (destructive)
java -jar target/impact.jar wipe --all `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password

# Wipe a specific commit snapshot
java -jar target/impact.jar wipe --commit a1b2c3d4 `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

Either `--all` or `--commit` is required. No prompt — make sure you mean it.

---

## 7. `snapshots` — list ingested commits

```powershell
java -jar target/impact.jar snapshots `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

```
COMMIT_SHA                          FILES  REPOS
──────────────────────────────────────────────────────
HEAD                                  900  [my-product]

1 snapshot.
```

---

## 8. `testcases` — import existing test cases (optional)

If you maintain a `TestCases.xlsx` or `.md` file with manual test cases, import them
into the graph so the report can recommend which existing tests cover the change.

### Step 1 — Import + auto-tag

```powershell
java -jar target/impact.jar testcases `
  --md   "C:\qa\TestCases.md" `
  --xlsx "C:\qa\TestCases.xlsx" `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

Each row becomes a `:TestCase` node. A keyword scan auto-tags them against
`:Method`, `:Class`, `:TaskType`, `:DbTable`, `:RestEndpoint`, `:MessageConstant`
nodes via `:COVERS` edges. Use `--no-tag` to skip auto-tagging if you want to
populate edges purely from a glossary file.

Expected output:

```
[TestCasesMdIngestor] TestCases.md: parsed 147 test cases across 6 suites
[testcases] wrote 147 test cases, 6 suites, 147 in-suite edges
[TestCaseTagger] catalog: 3263 methods, 925 classes, 9 task-types, 364 rest-urls, 367 db-tables, 32 msg-constants
[TestCaseTagger] emitted 1364 COVERS edges across 147 test cases
[testcases] persisted 1364 COVERS edges
```

### Step 2 — Curate auto-tags with a glossary file

The keyword scan is good but not perfect; some auto-tagged edges will be wrong, and
some real coverage relationships won't have any keyword to latch on to. Use
`--glossary <file>` to add / remove edges manually.

```powershell
# Curate-only — apply overrides without re-ingesting test cases
java -jar target/impact.jar testcases `
  --glossary "C:\qa\glossary.yaml" `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password

# Or combined with ingest in one go
java -jar target/impact.jar testcases `
  --md       "C:\qa\TestCases.md" `
  --glossary "C:\qa\glossary.yaml" `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

**`glossary.yaml` schema** (deliberately tiny — no SnakeYAML dep):

```yaml
# Top-level key = test-case ID (must match the :TestCase node's id)
# add: / remove: = operation to apply
# <Kind>: <key>  = a single rule
# Valid kinds: Method | Class | RestEndpoint | TaskType | DbTable | MessageConstant | Scheduler

LBF-DIST-018:
  add:
    Method: com.manageengine.spmp.management.tasks.ManagementTaskHandler.startTask
    TaskType: GrantPermission
  remove:
    DbTable: SomeTable                # silently no-op if no such edge exists

LBF-CFG-003:
  add:
    Method:                            # multi-value list shorthand
      - com.foo.Bar.baz
      - com.foo.Bar.qux
    RestEndpoint: /RestAPI/WC/Clustering/updateSchedulerNode?updateSchedulerNode
```

**Key-format rules:**

| Kind | Key format | Notes |
|---|---|---|
| `Method` | `<class.fqn>.<simpleName>` *(no params)* | Auto-expands to **every overload** of that method on that class. Or pass the exact `<class.fqn>.<simpleName>(paramFqn,…)` for a single overload. Bare names that match no graph node print a warning. |
| `Class` | `<class.fqn>` | E.g. `com.foo.Bar` |
| `RestEndpoint` | exact `url` property from the graph | URLs in SPMP look like `/RestAPI/WC/Foo?operation`. Use `impact query "MATCH (r:RestEndpoint) RETURN r.url"` to discover the exact form. |
| `TaskType` | task type `id` | E.g. `GrantPermission` |
| `DbTable` | table `name` | E.g. `ManagementOperationResults` |
| `MessageConstant` | constant `value` | E.g. `DIST_TASK_HEARTBEAT` |
| `Scheduler` | class `fqn` | Resolved against `:Class` nodes |

**`add` rules** get persisted with `confidence = 1.0` (manual = authoritative — wins
against auto-tagged edges with lower confidence).

**`remove` rules** drop the matching edge both in memory (if running together with
ingest) **and** with a Cypher `DELETE` against the graph, so glossary curation
survives across re-runs.

Expected output:

```
[glossary] WARN: Method 'com.foo.Bar.doesNotExist' matched no graph node — typo? Use the full FQN incl. '(...)' for precise targeting.
[testcases] glossary: 6 rules — added 5, removed 0 (mem) / 1 (graph)
```

### Step 3 — Run analyze with `--show-coverage`

Pass `--show-coverage` to `analyze` to surface coverage analysis in the report:

```powershell
java -jar target/impact.jar analyze `
  --patch "C:\changes\fix-789.patch" `
  --repo  "C:\proj\my-product" `
  --output both --out "C:\reports\fix-789.html" `
  --show-coverage `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

The console summary picks up an extra line:

```
[analyze] polyglot enrichment: 24 owners — js=0 hbs=0 cs=2 ps=0
[analyze] test coverage: 16 cases recommended, 22 coverage gap(s)  (library: 147)
```

The `polyglot enrichment` line reports how many entry-point owner classes the tool found
cross-language counterparts for. Each non-zero count drives extra steps in the generated
test cases:

| Counterpart | Source edge | Step added |
|---|---|---|
| `js` | `Class -[:EXPOSES]-> RestEndpoint <-[:CALLS_API]- JsFile` | "Open the SPMP admin UI on the screen rendered by the `<comp>` component." |
| `hbs` | `… <-[:CALLS_API]- JsFile <-[:USES_COMPONENT]- HbsTemplate` | "Watch the browser console while the screen renders — the `<file>.hbs` template should mount without missing-binding errors." |
| `cs` | C# `:CsFile` whose `simple_name` matches the Java owner | "On the SharePoint server side, verify `<class>.cs` completes without errors in the C# adapter log." |
| `ps` | `Class -[:CONTAINS]-> Method -[:INVOKES_SCRIPT]-> PsScript` | "Confirm `<script>.ps1` exited cleanly (check the PS log)." |

If your code uses classic JSP/HTML routing rather than an Ember UI you'll see `js=0
hbs=0` — that's expected; the tool falls back to a generic "navigate to the page that
triggers X" step.

And the report files gain a **Test-Case Coverage** section in:
- **HTML** — a metrics row + a sortable table of recommended tests + a gap list.
- **Markdown** (`generated-testcases.md`) — same data as a per-row pipe table.
- **JSON** — the `coverage` field on the top-level report object (always populated when
  test cases exist in the graph, regardless of `--show-coverage`).

If no `:TestCase` nodes exist, the section says so and points back to step 1.

---

## 8.5 `web` — Spring Boot REST server (P9)

Start the same analyze pipeline behind a REST API so non-CLI users (QA, release
managers, web UI) can consume it. Backed by Spring Boot 3 with Jetty as the embedded
servlet container; JWT auth via `Authorization: Bearer <token>` on every endpoint
except `/health` and `/auth/login`.

```powershell
java -jar target/impact.jar web `
  --port  8080 `
  --jwt-secret "your-stable-256-bit-secret" `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

`--jwt-secret` may be omitted (falls back to env `IMPACT_JWT_SECRET`); if both are
absent, a random key is generated per-start and tokens won't survive restarts.

The server logs the available endpoints on startup:

```
[web] starting Spring Boot REST server on port 8080
[web] Neo4j: http://localhost:7474  (user=neo4j)
[web] ready — endpoints:
[web]   GET  http://localhost:8080/api/v1/health
[web]   GET  http://localhost:8080/api/v1/repos
[web]   POST http://localhost:8080/api/v1/analyze
```

### Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET`  | `/api/v1/health` | public | Liveness + Neo4j reachability check. |
| `POST` | `/api/v1/auth/login` | public | Body `{username, password}` → `{token, expiresAt, username, roles}` on success, 401 on bad creds. |
| `GET`  | `/api/v1/auth/whoami` | Bearer | Echoes the authenticated principal's username + roles. |
| `GET`  | `/api/v1/repos`  | Bearer | List ingested repos + commits + file counts (same data as `impact snapshots`). |
| `POST` | `/api/v1/analyze` | Bearer | Run an impact analysis (synchronous). Body `{repoPath, patchPath?, base?, head?, srcRoot?, depth?, showCoverage?}`. Returns the full `ImpactReport` JSON, identical to `analyze --output json`. |
| `POST` | `/api/v1/ingest`  | Bearer | **Async** — start an ingest job. Body matches the CLI `ingest` flags (srcRoot, commit, repoId, deps, …). Returns `{jobId, status, createdAt}` immediately. Poll `/jobs/{id}` or subscribe to `ws://…/ws/jobs/{id}` for live progress. |
| `GET`  | `/api/v1/jobs`    | Bearer | List recent jobs (default last 50). Returns `[{id, kind, status, createdAt, …}, …]`. |
| `GET`  | `/api/v1/jobs/{id}` | Bearer | Job detail + last N (default 200) log lines. Use `?tail=2000` for the full buffer. |
| `WS`   | `/ws/jobs/{id}`   | (open in v1) | Live log tail. On connect, replays the existing buffer line-by-line, then streams new lines as `{"type":"log","seq":…,"stream":"stdout","text":"…"}` frames. Sends `{"type":"status","status":"SUCCEEDED"}` when the job ends. |

**Bearer auth** — every endpoint except `/health` and `/auth/login` requires the
header `Authorization: Bearer <token>`. Tokens are HS256 JWTs signed with the key
from `--jwt-secret` (or env `IMPACT_JWT_SECRET`); default TTL is 8 hours. Missing
/ expired / bad tokens return 401 with a JSON body naming the failure mode
(`missing-token` / `expired-token` / `bad-token` / `no-such-user`).

### Sample requests

```bash
# 1. Health (public)
curl http://localhost:8080/api/v1/health

# 2. Log in to get a bearer token. (Use the admin/<random> creds printed at first
#    server start, or any user created via `impact users create`.)
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"<the-bootstrap-password>"}' \
  | jq -r .token)

# 3. Validate the token
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/auth/whoami

# 4. List repos (now requires the bearer)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/repos

# 5. Analyze (patch-mode)
curl -X POST http://localhost:8080/api/v1/analyze \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "repoPath":  "C:\\proj\\my-product",
    "patchPath": "C:\\changes\\fix-789.patch",
    "depth":     6,
    "showCoverage": true
  }'

# 6. Start an async ingest job
JOB=$(curl -s -X POST http://localhost:8080/api/v1/ingest \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
    "srcRoot": "C:\\proj\\my-product\\source\\java",
    "repoId":  "my-product",
    "commit":  "HEAD"
  }' | jq -r .jobId)

# 7. Poll job status + tail log
curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/api/v1/jobs/$JOB?tail=20"

# 8. Live tail via WebSocket (using wscat)
wscat -c "ws://localhost:8080/ws/jobs/$JOB"
# → {"type":"log","seq":1,"stream":"stdout","text":"[ingest] src=C:\\proj\\…"}
# → {"type":"log","seq":2,"stream":"stdout","text":"[CoreExtractor] Pass 1 — declarations …"}
# → …
# → {"type":"status","status":"SUCCEEDED","finishedAt":"…"}
```

### Architecture

Spring Boot is **embedded** (no external app server). The `web` subcommand calls
`SpringApplication.run()` from inside the same shaded jar that drives the CLI;
picocli stays the entry point. Controllers reuse the existing `SliceExecutor` /
`Neo4jWriter` / `JavaProjectParser` classes directly — there's no parallel code
path, so anything that works in `impact analyze` works the same way over REST.

| Component | File |
|---|---|
| Spring Boot main | `web/WebApplication.java` (excludes `Neo4jAutoConfiguration` — we own the driver lifecycle) |
| `web` subcommand | `cmd/WebCmd.java` |
| `GET /health`    | `web/api/HealthController.java` |
| `GET /repos`     | `web/api/ReposController.java` |
| `POST /analyze`  | `web/api/AnalyzeController.java` (delegates to `SliceExecutor.run()`) |

### Bootstrap admin (first server start)

On first start, if no `:AppUser` nodes exist in Neo4j, the server seeds an
`admin/<random-24-char-password>` account and prints the password to **stdout
exactly once**. Save it immediately:

```
════════════════════════════════════════════════════════════════════════
 [admin-bootstrap] no :AppUser nodes found — seeded a default admin.
    username: admin
    password: rlMdgWVk_2paYkdTOHETKQXX
  ▶ Save this password NOW — it is printed once and not stored anywhere.
════════════════════════════════════════════════════════════════════════
```

The password is BCrypt-hashed in Neo4j (`u.passwordHash`). It is never logged
or persisted as plaintext.

### Managing users via the CLI

```powershell
# List
java -jar target/impact.jar users list `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password

# Create (omit --password to be prompted interactively — keeps it out of shell history)
java -jar target/impact.jar users create `
  --username alice --role DEV `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password

# Delete
java -jar target/impact.jar users delete bob `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

Roles: **VIEWER** (read-only), **DEV** (analyze + ingest), **ADMIN** (everything).
Multiple roles allowed via `--role VIEWER,DEV`.

### Web UI (P10)

Open `http://localhost:8080/` in a browser to land on the SPA. The shaded jar
ships the production-built React app under `classpath:/static/`, so no separate
hosting is required.

Pages:

| Route | Purpose |
|---|---|
| `/login` | Username/password — POST to `/api/v1/auth/login`, store JWT in localStorage |
| `/`      | **Dashboard** — list of ingested repos + Neo4j health badge |
| `/analyze` | Form for `POST /api/v1/analyze`; rendered impact summary on response |
| `/jobs`  | List of recent ingest/analyze jobs with status pills, polled every 3s |
| `/jobs/:id` | Live log tail over `/ws/jobs/{id}` + parameters + terminal status |

All authenticated pages route through `RequireAuth`, which redirects to `/login`
on missing/expired token. Any 401 from the API dispatches the
`impact:unauthorized` event → App.tsx routes back to `/login?next=<current>`.

#### Developing the UI locally

```powershell
cd impact-web/ui
npm install         # one-time
npm run dev         # http://localhost:5173, hot reload, /api+/ws proxied to :8080
```

The Vite dev server proxies `/api/*` and `/ws/*` to `http://localhost:8080`, so
the SPA's same-origin paths work without CORS configuration.

#### Production build

```powershell
cd impact-web/ui
npm run build       # → emits to ../../impact-cli/src/main/resources/static/

cd ../../impact-cli
mvn package -DskipTests
# The fat jar now bundles the SPA. Visit http://localhost:8080
```

The build emits `index.html` + `assets/index-<hash>.js` + `assets/index-<hash>.css`
into Spring Boot's `static/` resource path. `SpaForwardingConfig` adds history-mode
fallback so deep links (`/jobs/abc123`) survive a hard reload instead of 404-ing.

### Async ingest — job lifecycle

`POST /api/v1/ingest` runs the same pipeline as the CLI `ingest` command on a
background worker thread. The endpoint returns immediately with a `jobId`; the
worker streams its `System.out` output into a per-job ring buffer (last 2,000
lines retained) so a slow web client can backfill on connect.

```
client                          server                          neo4j
  │ POST /api/v1/ingest          │                                │
  │ ───────────────────────────► │                                │
  │ 202 {jobId:"a1b2c3d4"}       │                                │
  │ ◄─────────────────────────── │                                │
  │                              │  worker thread starts          │
  │ WS /ws/jobs/a1b2c3d4         │  ┌──────────────────────────┐  │
  │ ───────────────────────────► │  │ IngestCmd.runProgrammat. │──┼─►  Schema.bootstrap
  │ {type:"log",text:"[ingest]…"}│  │   System.out tee → job   │  │   parse files
  │ ◄─────────────────────────── │  │   log buffer + listeners │  │   write batches
  │ {type:"log",text:"[Core…]…"} │  └──────────────────────────┘  │
  │ ◄─────────────────────────── │                                │
  │ …                            │  job.markSucceeded()           │
  │ {type:"status",status:"SUCC"}│                                │
  │ ◄─────────────────────────── │                                │
```

Status values: `PENDING` (not yet picked up) → `RUNNING` → `SUCCEEDED` / `FAILED` /
`CANCELLED`. The pool is sized at 2 worker threads, so concurrent ingest requests
will queue rather than overload Neo4j.

### Deferred to later phases

- **Role-based endpoint enforcement** — auth filter validates identity; per-endpoint role checks (e.g. `/cypher` admin-only) are P9.7.
- **WebSocket auth** — `/ws/jobs/{id}` is open in v1 (no Bearer required). Add a `?token=...` query param + filter in P9.7.
- **Async `/analyze` job** — the current `POST /analyze` is synchronous (blocks until done). Wrapping it in the same async-job pattern is a small follow-up.
- **Cytoscape graph view** — P10 (React SPA).

### Troubleshooting

If startup fails with `java.io.IOException: Unable to establish loopback connection`
and a `UnixDomainSockets.connect0` line in the stack trace, the JVM cannot open an
NIO selector — usually an antivirus / corporate-security tool is briefly blocking
loopback socket connections while it scans the freshly-shaded jar. Workarounds:

1. **Re-run after a few seconds** — most AV scanners release the lock after the
   first scan completes.
2. **Whitelist `java.exe` and `target/impact.jar`** in Windows Defender / your
   corporate AV.
3. **Run inside WSL2 or Docker** — Linux JDK builds aren't affected. Recommended
   for production.

The rest of the tool (`ingest`, `analyze`, `testcases`, `query`) is unaffected by
this — those commands don't open NIO selectors.

---

## 9. Neo4j Cypher cheatsheet

Run any of these via `impact query "..."` or directly in the Neo4j Browser at
`http://localhost:7474/browser/`.

### Sanity checks after ingest

```cypher
// Node counts by label
MATCH (n)
RETURN labels(n)[0] AS label, count(*) AS n
ORDER BY n DESC;

// Edge counts by type
MATCH ()-[r]->()
RETURN type(r) AS edge, count(*) AS n
ORDER BY n DESC;

// REST endpoints + which class exposes each
MATCH (c:Class)-[:EXPOSES]->(r:RestEndpoint)
RETURN c.simple_name AS owner, r.url AS url
ORDER BY url
LIMIT 30;

// Task types + their handler classes
MATCH (c:Class)-[:HANDLES]->(t:TaskType)
RETURN t.id AS taskType, c.simple_name AS handler
ORDER BY taskType;

// Tables + column counts
MATCH (t:DbTable)
OPTIONAL MATCH (t)-[:HAS_COLUMN]->(col:DbColumn)
RETURN t.name AS table, count(col) AS columns
ORDER BY columns DESC
LIMIT 20;
```

### Useful debugging queries

```cypher
// Find a method by simple name
MATCH (m:Method {simple_name: 'submitTask'})
RETURN m.fqn, m.start_line, m.end_line;

// Show all callers of a method (1 hop)
MATCH (caller:Method)-[:CALLS]->(m:Method {simple_name: 'submitTask'})
RETURN caller.fqn AS caller LIMIT 25;

// Show what a method reaches forward (up to depth 3)
MATCH (m:Method {simple_name: 'handleSave'})
MATCH path = (m)-[:CALLS|DISPATCHES_TO*1..3]->(downstream:Method)
RETURN DISTINCT downstream.fqn AS reached LIMIT 30;

// Find every entry point that can reach a given method
MATCH (m:Method {simple_name: 'finalizeBatch'})
MATCH path = (ep:EntryPoint)-[:CALLS|DISPATCHES_TO*1..10]->(m)
RETURN DISTINCT ep.fqn AS entryPoint, labels(ep) AS labels LIMIT 20;

// Tables a method writes (directly or transitively)
MATCH (m:Method {simple_name: 'submitTask'})
MATCH (m)-[:CALLS*0..5]->(:Method)-[:WRITES_TABLE]->(t:DbTable)
RETURN DISTINCT t.name AS table;
```

### Cleanup

```cypher
// Delete every node + edge (irreversible)
MATCH (n) DETACH DELETE n;

// Drop a specific commit snapshot
MATCH (n) WHERE n.commit_sha = 'a1b2c3d4'
DETACH DELETE n;
```

---

## 10. Common workflows

### One-shot impact analysis on a `.patch`

```powershell
# 1. Start Neo4j (Docker)
docker start impact-neo4j

# 2. Ingest (one time per code version)
java -jar target/impact.jar ingest `
  --src "C:\proj\my-product\source\java" `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password

# 3. Analyze the change
java -jar target/impact.jar analyze `
  --patch "C:\changes\fix-789.patch" `
  --repo "C:\proj\my-product" `
  --output both --out "C:\reports\fix-789.html" `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password

# 4. Open the report
start "" "C:\reports\fix-789.html"
```

### CI gate that fails on HIGH-risk changes

```bash
java -jar target/impact.jar analyze \
  --patch "$PR_PATCH" \
  --repo "$WORKSPACE" \
  --output json --out "$WORKSPACE/impact.json" \
  --fail-on HIGH \
  --neo4j http://neo4j:7474 --user neo4j --pass "$NEO4J_PASS"
# Exit code 2 if risk is HIGH; pipeline can pin the report URL on the PR.
```

### Remote-repo workflow (no local clone)

```powershell
# 1. Set the Zoho token once per shell
$env:IMPACT_REPO_TOKEN = "<paste-Zoho-PAT-here>"

# 2. Discover which repos the token can see
java -jar target/impact.jar repos --remote-org abc

# 3. Ingest the primary product + a framework dep, in one call
java -Xmx4g -jar target/impact.jar ingest `
  --remote-org abc `
  --remote-repo  d7f3a1...          # primary (e.g. adsm) `
  --remote-dep   adsf=88b2cc...     # shared framework `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password

# 4. Analyze a commit range without ever touching disk
java -jar target/impact.jar analyze `
  --remote-org abc `
  --remote-repo d7f3a1... `
  --remote-base 51dac98446 `
  --remote-head cccb0f841b `
  --output both --out "D:\reports\adsm-impact.html" `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password

# 5. Re-run step 4 the next day — cache hit, no network calls for clone
```

The materialized cache lives at `~/.impact-cli/cache/<orgId>/<repoId>/<sha>/` and survives
reboots. Different SHAs cache independently; re-analyzing the same SHA always hits cache.

### Comparing impact across two branches

```bash
# Ingest branch A
java -jar target/impact.jar ingest --src .../java --commit branchA \
  --neo4j http://... --user ... --pass ...

# Ingest branch B
java -jar target/impact.jar ingest --src .../java --commit branchB --incremental \
  --neo4j http://... --user ... --pass ...

# Then compare with custom Cypher
java -jar target/impact.jar query "
  MATCH (a:Method {commit_sha: 'branchA'}), (b:Method {commit_sha: 'branchB'})
  WHERE a.fqn = b.fqn AND a.signature <> b.signature
  RETURN a.fqn AS fqn, a.signature AS sigA, b.signature AS sigB
" --neo4j http://... --user ... --pass ...
```

---

## 11. Exit codes

| Code | Meaning |
|---|---|
| `0` | Success |
| `1` | Java exception (bug — check stderr) |
| `2` | Bad CLI invocation, OR `analyze --fail-on` threshold reached |

---

## 12. Where do I go next?

* **Understanding the report you just generated**: [REPORT_GUIDE.md](REPORT_GUIDE.md)
* **Worked example with sample source code**: [URL_DETECTION_EXAMPLE.md](URL_DETECTION_EXAMPLE.md)
* **How the tool is built**: [ARCHITECTURE.md](ARCHITECTURE.md)

---

## 13. Multi-repo ingest (dependent repos)

Enterprise products layer on top of shared framework repos — e.g. a product like ADSM
(`com.manageengine.admp.*`) extends classes that live in the ADSF framework repo
(`com.manageengine.ads.fw.*`). If you ingest only the primary product, every parent
class from the framework appears in the graph as a stub with no methods, so
`:OVERRIDES` detection cannot connect a patched override back to callers of the parent
declaration.

**Solution: pass the framework as a `--dep`.** A single `ingest` invocation accepts the
primary repo plus one or more dependency repos. All Java source roots are walked by the
three-pass extractor and registered in the same {@code GlobalIndex} — cross-repo class /
method / field references resolve via index lookup. Class/Method nodes are FQN-keyed in
the graph — same FQN across repos merges to one node automatically.

The tool also auto-detects each dep's **C# source directories** (`c_sharp/` and
`c_source/` at any depth) and **PowerShell scripts** (whole-repo walk with noise
filters). XML config dirs (`product_package/conf`) are similarly probed per-dep.

### Syntax

```powershell
java -jar impact.jar ingest `
  --src       "<primary-java-root>" `
  --repo-id   "<primary-name>" `
  --commit    "<sha>" `
  --js-root   "..." --html-root "..." --cs-root "..." --xml-conf "..." `
  `
  --dep       adsf="<adsf-java-root>" `
  --dep       audit="<audit-java-root>" `
  --neo4j ... --user ... --pass ...
```

- `--dep <repoId>=<javaRoot>` is repeatable. Add as many as you need.
- For each dep, the tool **auto-detects** `<dep-java-root>/../../product_package/conf` —
  any `data-dictionary.xml` / `*-dd.xml` / `ADSProductAPIs.xml` files found there join
  the primary's schema/REST XML pipeline.
- The same `--commit` is used for the primary and every dep. Per-dep commit pinning is
  on the v2 roadmap.

### Worked example: ADSM + ADSF

```powershell
$adsm = "D:\repos\adsm-...\adsm-..."
$adsf = "D:\repos\adsf-...\adsf-..."

java -Xmx4g -jar target\impact.jar ingest `
  --src       "$adsm\source\java_source" `
  --repo-id   adsm `
  --js-root   "$adsm\web\adsm\emberapp" `
  --html-root "$adsm\web\adsm\html" `
  --cs-root   "$adsm\source\c_sharp" `
  --xml-conf  "$adsm\product_package\conf" `
  --dep       "adsf=$adsf\source\java_source" `
  --neo4j http://localhost:7474 --user neo4j --pass <pw>
```

Console output gains two new lines near the top, then the resolver counts reflect both repos:

```
[ingest] dep=adsf src=…\adsf-…\source\java_source
[ingest] dep=adsf xml-conf=…\adsf-…\product_package\conf
[DbSchemaXmlResolver]  resolved 1326 tables, 7629 columns from 24 schema XML file(s)
[CoreExtractor]        emitted 2974 :OVERRIDES edges
[ingest]               parsed 3237 files, 3857 classes, 39649 methods, 504523 call edges
[ingest]               graph write complete.
```

For comparison, ADSM ingested alone yields **2** `:OVERRIDES` edges (because every
`:EXTENDS` lands on a stub framework class). With `--dep adsf=…` it climbs to **2,974**,
of which **201** are cross-repo edges (ADSM child → ADSF parent).

### Verification queries

After the multi-repo ingest, run these to confirm everything wired up correctly:

```cypher
// Both repos materialised
MATCH (r:Repo) RETURN r.id ORDER BY r.id;
// Expect: adsf, adsm

// Per-repo file counts
MATCH (f:File) RETURN f.repo_id AS repo, count(*) AS files ORDER BY repo;
// Expect counts roughly matching the source-tree file counts

// EXTENDS pairs with methods on both sides (was 0 before --dep)
MATCH (c:Class)-[:EXTENDS]->(p:Class)
WHERE EXISTS{(c)-[:CONTAINS]->(:Method)}
  AND EXISTS{(p)-[:CONTAINS]->(:Method)}
RETURN count(*) AS pairsWithMethods;

// Cross-repo OVERRIDES sample
MATCH (child:Method)-[:OVERRIDES]->(parent:Method)
MATCH (cf:File)-[:CONTAINS*]->(:Class)-[:CONTAINS]->(child)
MATCH (pf:File)-[:CONTAINS*]->(:Class)-[:CONTAINS]->(parent)
WHERE cf.repo_id <> pf.repo_id
RETURN cf.repo_id AS childRepo, pf.repo_id AS parentRepo, count(*) AS edges;
```

### Caveats

- **Heap:** A multi-repo ingest holds every source file's AST during Pass 1 plus the
  shared {@code GlobalIndex}. AST is dropped before Pass 2 (resolution), so peak heap
  is bounded. For ADSM (1,341 files) + ADSF (1,902 files) + webclient (1,114 files)
  budget `-Xmx4g` for clean runs or `-Xmx6g` for headroom on streaming flushes. See
  [PREREQUISITES.md](PREREQUISITES.md#hardware).
- **`--js-root` / `--html-root` flags apply only to the primary.** C# (`--cs-root`)
  and PowerShell scripts are **auto-detected per-dep** — the tool walks up from each
  dep's Java root looking for `c_sharp/` / `c_source/` siblings and finds `.ps1`
  files in the repo root with a noise-filtered deep scan.
  ADSF has no Ember UI so this is safe in our reference case; if your dep has its own
  UI/C# you'd extend the flag set in a future iteration.
- **Single `--commit` for all repos.** Most users pin dep versions via git checkout, so
  a single commit per ingest is fine. Per-dep commits would be a future feature.
- **Same FQN across repos collapses to one node.** This is intentional and powers the
  cross-repo edges. If two repos somehow define `com.foo.Bar` independently, the second
  write would clobber the first's metadata. Detect with:
  ```cypher
  MATCH (c:Class)<-[:CONTAINS]-(f:File)
  WITH c, count(DISTINCT f.repo_id) AS repos
  WHERE repos > 1 RETURN c.fqn LIMIT 10;
  ```
