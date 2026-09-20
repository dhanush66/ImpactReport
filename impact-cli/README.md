# impact-cli

A polyglot code-impact analysis tool. Given a source tree and a patch (or git diff),
it tells you — in plain English a non-developer can read — **what features, REST APIs,
database tables, schedulers, and UI components the change can affect**.

It is built around a Neo4j call-graph database that stores Java classes/methods/calls
plus boundary nodes for REST endpoints, DB tables, scheduled jobs, Ember JS files, HBS
templates, C# adapters and PowerShell scripts. A diff is mapped to its smallest enclosing
symbols and the graph is sliced forward + backward to produce an HTML / Markdown / JSON
impact report.

---

## Quick start

```bash
# 1. Build the fat-jar (one time)
cd impact-cli
mvn -DskipTests package

# 2. Start Neo4j (Docker — easiest)
docker run -d --name neo4j -p 7474:7474 -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/neo4j-password neo4j:5

# 3. Ingest the source tree
java -jar target/impact.jar ingest \
  --src /path/to/my-project/source/java \
  --neo4j http://localhost:7474 \
  --user neo4j --pass neo4j-password

# 4. Analyze a patch
java -jar target/impact.jar analyze \
  --patch /path/to/change.patch \
  --repo /path/to/my-project \
  --output both --out impact-report.html \
  --neo4j http://localhost:7474 \
  --user neo4j --pass neo4j-password
```

Open `impact-report.html` in a browser and `generated-testcases.md` in any markdown viewer.

### Or — run the web UI instead of the CLI

```bash
# 5. Start the embedded Spring Boot server (Jetty + JWT auth + bundled React SPA)
java -jar target/impact.jar web --port 8080 \
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
# First start prints a one-time admin password to stdout — save it.
```

Then open `http://localhost:8080/` in a browser:

- **Login** with `admin` + the bootstrap password
- **Dashboard** lists every ingested repo + commit
- **Analyze** runs a patch through the slice queries and renders the impact summary
- **Jobs** lists every async ingest/analyze run; click an ID for live log tail (WebSocket)

See [docs/USAGE.md §8.5](docs/USAGE.md) for the full REST + WS endpoint reference.

### Or — fetch from the Zoho Repository API (no local clone needed)

```bash
# 1. Discover repos
java -jar target/impact.jar repos --remote-org <orgId> --repo-token $IMPACT_REPO_TOKEN

# 2. Ingest from a remote repo
java -jar target/impact.jar ingest \
  --remote-org <orgId> --remote-repo <repoId> --repo-token $IMPACT_REPO_TOKEN \
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password

# 3. Analyze a commit range without ever touching disk
java -jar target/impact.jar analyze \
  --remote-org <orgId> --remote-repo <repoId> --repo-token $IMPACT_REPO_TOKEN \
  --remote-base <baseSha> --remote-head <headSha> \
  --output both --out impact-report.html \
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
```

The first call materializes the repo into `~/.impact-cli/cache/<orgId>/<repoId>/<sha>/`; subsequent runs with the same SHA hit the cache instantly. See [docs/USAGE.md](docs/USAGE.md#remote-repository-zoho-api) for all `--remote-*` flags.

---

## Documentation

| Doc | Purpose |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How the tool is built — architecture diagram, tech stack, module split, and what every package does |
| [docs/PREREQUISITES.md](docs/PREREQUISITES.md) | What you need installed before you can run the tool — JDK, Maven, Neo4j, OS notes |
| [docs/USAGE.md](docs/USAGE.md) | Every CLI command with all its flags + the Neo4j Cypher commands for spot-checking the graph |
| [docs/REPORT_GUIDE.md](docs/REPORT_GUIDE.md) | How to read the impact report — what each section means, how to interpret the risk scores |
| [docs/URL_DETECTION_EXAMPLE.md](docs/URL_DETECTION_EXAMPLE.md) | Worked example with sample source code — exactly how the tool figures out which REST URLs a change affects |

---

## What's in the report

Every run produces three files (with `--output both`):

- `impact-report.html` — interactive HTML report (open in browser)
- `impact-report.json` — structured JSON for CI gates / further tooling
- `generated-testcases.md` — plain-English test cases per affected area (drop-in for QA)

Each report contains these sections, top-to-bottom:

1. **Summary** — total changed symbols, overall risk, layer impact (Java / REST / DB-tables+columns / JS / HTML / C# / PowerShell / TaskTypes / JGroups messages)
2. **Affected by Task / Action** — high-level **pivot**: every URL, DB table, schedule, and UI component grouped by the user-visible Task or Action it belongs to. One row per feature = one set of regression tests to run. The flat per-category tables follow for drill-down.
3. **APIs Affected** — every REST URL the change can hit, with the user-facing **Feature** name (Action / Report / TaskType / ExternalApi) plus reachable JS / C# / HTML callers per URL
4. **Database Tables Affected** — every DB table touched, with **column-level detail**, **writer + reader method lists**, and per-table risk grading
5. **Schedules Affected** — every scheduled job / task handler / scheduler reached, with the **TaskType** display name
6. **UI Components Affected** — every Ember JS file / HBS template touched or reached via the API graph
7. **Polyglot Changes in Patch** — JS / HBS / C# / XML / properties hunks in the diff
8. **Deleted Symbols** — Java methods / classes removed in the patch (callers may break)
9. **Generated Test Cases** — plain-English test cases grouped by area (DIST / MGMT / REPT / CFG / DB / JS / HBS / CS / REST), with concrete line numbers, method names, and table columns embedded
10. **Symbol Detail** — per-changed-symbol forward + backward reach (developer view)

### Graph node types

| Label | Sub-labels (where applicable) |
|---|---|
| `:Method` | `:Constructor`, `:EntryPoint` |
| `:Class` | `:Interface`, `:Servlet`, `:Scheduler`, `:Job`, `:TaskHandler` |
| `:JsFile` | `:JsRoute`, `:JsController`, `:JsModel`, `:JsComponent`, `:JsService`, `:JsHelper`, `:JsAdapter`, `:JsSerializer`, `:JsUtil`, `:JsMixin`, `:JsOther` (Ember pods + flat layout) |
| `:HbsTemplate` | `:HbsComponentTemplate`, `:HbsOther` |
| `:CsFile` | `:CsOther` (extensible) |
| `:Field` · `:File` · `:Package` · `:RestEndpoint` · `:DbTable` · `:DbColumn` · `:HtmlPage` · `:PsScript` · `:TaskType` · `:MessageConstant` · `:Repo` · `:Commit` | (no sub-labels) |

### Graph edge types

| Edge | From → To |
|---|---|
| `:CALLS {kind}` | `:Method → :Method` (kinds: `virtual`, `static`, `constructor`, `ambiguous-of-N`, `unresolved`) |
| `:OVERRIDES` | `:Method → :Method` |
| `:EXTENDS` / `:IMPLEMENTS` | `:Class → :Class` |
| `:READS` / `:WRITES` | `:Method → :Field` |
| `:READS_TABLE` / `:WRITES_TABLE` | `:Method → :DbTable` |
| `:HAS_COLUMN` | `:DbTable → :DbColumn` |
| `:EXPOSES` | `:Class → :RestEndpoint` |
| `:CALLS_API` | `:JsFile / :CsFile → :RestEndpoint` |
| `:IMPORTS` | `:JsFile → :JsFile` (Ember addon-aware) |
| `:USES_COMPONENT` | `:HbsTemplate → :JsFile` (pods + flat layout) |
| `:RENDERS_TEMPLATE` | `:JsFile → :HbsTemplate` |
| `:INVOKES_SCRIPT` | `:Method / :CsFile → :PsScript` |
| `:HANDLES` | `:Class → :TaskType` |
| `:DISPATCHES_TO` | `:Method → :Class` |
| `:SENDS_MESSAGE` / `:RECEIVES_MESSAGE` | `:Method → :MessageConstant` |
| `:REFERENCES` | `:HtmlPage → :RestEndpoint` |
| `:CONTAINS` | `:Package → :Class → :Method / :Field` |
| `:HAS_SNAPSHOT` | `:Repo → :Commit` |

---

## Source layout

```
LoadBalancerV1/
├── impact-cli/                              ← CLI + REST server (Java, Maven)
│   ├── pom.xml
│   ├── README.md                            ← this file
│   ├── docs/                                ← documentation
│   │   ├── ARCHITECTURE.md
│   │   ├── PREREQUISITES.md
│   │   ├── USAGE.md
│   │   ├── REPORT_GUIDE.md
│   │   └── URL_DETECTION_EXAMPLE.md
│   ├── src/main/java/io/spmp/impact/
│   │   ├── Main.java                        ← CLI entry point (picocli)
│   │   ├── cmd/                             ← per-command classes (ingest, analyze, web, users, …)
│   │   ├── extract/                         ← ingest pipeline (JavaParser + tree-sitter)
│   │   ├── graph/                           ← Neo4j writer, schema, Cypher queries
│   │   ├── diff/                            ← patch parser + hunk → symbol mappers
│   │   ├── analyze/                         ← Cypher slice executor
│   │   ├── testgen/                         ← test-case generator + glossary overrides
│   │   ├── report/                          ← HTML + JSON renderers
│   │   ├── remote/                          ← Zoho Repository API integration
│   │   ├── web/                             ← Spring Boot REST server + JWT auth
│   │   │   ├── WebApplication.java          ← @SpringBootApplication entry
│   │   │   ├── Neo4jConfig.java             ← shared Neo4jWriter bean
│   │   │   ├── SpaForwardingConfig.java     ← history-mode fallback for the React SPA
│   │   │   ├── api/                         ← REST controllers
│   │   │   ├── auth/                        ← AppUser, JwtService, JwtAuthFilter, AdminBootstrap
│   │   │   ├── jobs/                        ← JobRegistry + JobState + TeePrintStream
│   │   │   └── ws/                          ← /ws/jobs/{id} live log tail
│   │   └── model/                           ← record types (graph nodes/edges, impact report)
│   └── src/main/resources/
│       ├── templates/report.ftl             ← FreeMarker HTML template
│       └── static/                          ← prebuilt React SPA (index.html + assets/)
│
└── impact-web/ui/                           ← React + TypeScript + Vite SPA (P10)
    ├── package.json
    ├── vite.config.ts                       ← builds into ../../impact-cli/src/main/resources/static/
    ├── tsconfig.json, tailwind.config.js, postcss.config.js, index.html
    ├── README.md
    └── src/
        ├── main.tsx, App.tsx, index.css
        ├── api/client.ts                    ← typed fetch wrapper + JWT storage
        └── pages/                           ← Login, Dashboard, Analyze, Jobs, JobDetail, Layout
```
