# Architecture

This document covers **how impact-cli is built**: the high-level architecture, the
tech stack, and what each Java module/package does. For instructions on running the
tool see [USAGE.md](USAGE.md).

---

## 1. Architecture at a glance

```
                ┌─────────────────────────────────────────────────────────────────┐
                │  impact-cli  (single Java fat-jar, ~41 MB)                       │
                │                                                                  │
                │   ┌───── ingest ──────────────────────────────────────────────┐  │
                │   │                                                            │  │
                │   │   JavaParser (NO SymbolSolver)       tree-sitter           │  │
                │   │       │  three-pass index-driven         (JS, C#)          │  │
                │   │       ▼  extractor                       │                  │  │
                │   │   CoreExtractor                          ▼                  │  │
                │   │   ├─ Pass 1: walk decls, build GlobalIndex (frozen maps)   │  │
                │   │   ├─ Pass 1b: BodyCollector single-walk per method,        │  │
                │   │   │            scope-stack typed locals, collects CallSites │  │
                │   │   ├─ Pass 2: CallResolver — 7 strategies (enclosing-this /  │  │
                │   │   │            this / super / static-class / local-var /    │  │
                │   │   │            field-ref / constructor) → CALLS edges       │  │
                │   │   └─ Pass 3: emitOverrides via index ancestor walk          │  │
                │   │                                                              │  │
                │   │   + JsEmberResolver, CsSharepointResolver,                  │  │
                │   │     HbsTemplateResolver, HtmlPageResolver,                  │  │
                │   │     PowerShellScriptResolver — tree-sitter / jsoup walks    │  │
                │   │                                                              │  │
                │   │   + boundary resolvers (BoundaryResolver interface):         │  │
                │   │         ServletResolver, SchedulerResolver,                  │  │
                │   │         TaskRegistryResolver, DbTableResolver,               │  │
                │   │         PowerShellInvocationResolver,                        │  │
                │   │         MessageConstantResolver,                             │  │
                │   │         RestApiXmlResolver, DbSchemaXmlResolver              │  │
                │   │       │                                                      │  │
                │   │       ▼                                                      │  │
                │   │   ExtractionBatch  ──►  Neo4jWriter (UNWIND-batched MERGE)   │  │
                │   │                            via CypherClient (Bolt or HTTP)   │  │
                │   └────────────────────────────────────────────────────────────┘  │
                │                                                                  │
                │   ┌───── analyze ─────────────────────────────────────────────┐  │
                │   │                                                            │  │
                │   │   jgit DiffFormatter / PatchFileDiffSource                 │  │
                │   │       │                                                    │  │
                │   │       ▼                                                    │  │
                │   │   HunkToSymbolResolver (Java) + TreeSitterHunkResolver    │  │
                │   │       (JS/C#) + DeletedJavaSymbolScanner                  │  │
                │   │       │                                                    │  │
                │   │       ▼                                                    │  │
                │   │   PolyglotResolver (file-level + downstream graph lookup) │  │
                │   │       │                                                    │  │
                │   │       ▼                                                    │  │
                │   │   SliceExecutor — runs Cypher slices                      │  │
                │   │   ├─► forward reach     (changed → :CALLS* → leaves)      │  │
                │   │   ├─► backward reach    (entry points → :CALLS* → changed)│  │
                │   │   ├─► DB tables         (:READS_TABLE / :WRITES_TABLE)    │  │
                │   │   ├─► risk scoring      (entry-point breadth)             │  │
                │   │   ├─► override expansion (virtual dispatch)               │  │
                │   │   ├─► feature classifier (TaskType / Action / Report)     │  │
                │   │   └─► affected-X aggregators (APIs, DB, Schedules, UI)   │  │
                │   │       │                                                    │  │
                │   │       ▼                                                    │  │
                │   │   ImpactReport (record) ──►  HtmlReportRenderer            │  │
                │   │                          ──►  JsonReportRenderer            │  │
                │   │                          ──►  TestCaseGenerator + MarkdownWriter │
                │   └────────────────────────────────────────────────────────────┘  │
                └────────────────────────────────────────────────────────────────────┘
                                            │
                                            ▼
                                 ┌──────────────────┐
                                 │  Neo4j 5.x       │
                                 │  (Community OK)  │
                                 └──────────────────┘
```

**Two pipelines, one graph.**

* **`ingest`** parses the source tree once and writes a polyglot call-graph to Neo4j.
  Java is the spine; JS / HBS / C# / XML / HTML / PowerShell are linked as boundary
  nodes (REST endpoints, scheduled jobs, task handlers, DB tables, etc.).
* **`analyze`** takes a diff (a `.patch` file or `--base/--head` git revisions), maps
  every hunk to its smallest enclosing AST symbol, then runs Cypher slices against the
  ingested graph to produce an impact report.

The graph is the persistent state — once a repo is ingested, you can run any number of
`analyze` queries against it without re-parsing source.

### 1.1 Web mode (P9 + P10) — same jar, REST + SPA

The same fat-jar can also run as a Spring Boot REST server with an embedded React SPA:

```
Browser  ──HTTPS──►  impact.jar [web subcommand]
                       │
                       ├─ Jetty (embedded) serves:
                       │     /api/v1/*          → @RestController beans  ──► SliceExecutor / IngestCmd.runProgrammatically
                       │     /ws/jobs/{id}      → JobLogWebSocketHandler ──► JobRegistry / JobState (live tail)
                       │     /  + /assets/*     → React SPA from classpath:/static/  (Vite build output)
                       │     /{deep/links}      → SpaForwardingConfig → /index.html  (history-mode fallback)
                       │
                       ├─ Auth: JwtAuthFilter checks Bearer token on /api/v1/* (except /health, /auth/login).
                       │       BCrypt password hashes on :AppUser nodes, HS256 JWT signed with IMPACT_JWT_SECRET.
                       │       AdminBootstrap @PostConstruct seeds admin/<random> on first start.
                       │
                       └─ Job tracking: ingest runs on a background thread pool. TeePrintStream redirects
                          the worker's System.out into the JobState ring buffer (last 2,000 lines) +
                          fan-out to WebSocket listeners. Same SliceExecutor / Neo4jWriter as the CLI.
```

The CLI and web paths share the same in-process classes — `IngestCmd.runProgrammatically(IngestParams)`
is the single entry to the pipeline, called from both `picocli` and the REST controller. There's no
second analysis pipeline to keep in sync.

---

## 2. Tech stack

| Layer | Library | Why |
|---|---|---|
| Build | **Maven** 3.9+, **maven-shade-plugin** | Single deployable fat-jar |
| Runtime | **Java 17** (GraalVM tested) | Records, pattern-matching switch, sealed types |
| CLI | **picocli** 4.7 | Sub-commands, type-safe options, auto-help |
| Java parser | **JavaParser** 3.25 (without SymbolSolver) | Source-level AST. Required for accurate line numbers in hunk-to-symbol mapping. SymbolSolver was **removed from the extract path** in 2026-05 — it hung indefinitely on monster files (e.g. `ReportResultUtil.java` 369 KB / 7,000 lines). Replaced with a three-pass **index-driven** resolver (Pass 1 builds class/method/field/import indices; Pass 2 resolves call sites via index lookup in ~2 µs each). The analyze-side hunk resolver still uses JavaParser; SymbolSolver-attached parsers remain available there. |
| JS / C# parser | **tree-sitter** (`io.github.bonede:tree-sitter` 0.25 + grammar jars) | Production-grade incremental parser. Used both at ingest time (identifying components, classes, URL string literals) and at diff time (mapping JS/C# hunks to function/method symbols) |
| HTML parser | **jsoup** 1.17 | Robust HTML5 parser — extracts page titles, embedded URLs |
| XML parser | **JAXP** (built into JDK) | Used for `data-dictionary.xml`, `ADSProductAPIS.xml`, `SPMPServletActions.xml`, `web.xml` |
| Diff parser | **jgit** 6.9 | Reads unified-diff `.patch` files **and** real git working trees. Same `FileHeader`/`Edit`/`Hunk` API for both modes. |
| Graph DB | **Neo4j** 5.x Community | Edge-heavy data (~50k :CALLS edges in our test repo). Cypher's variable-length path matching is exactly what slice queries need. |
| Graph transport | **Neo4j Java Driver** 5.20 (Bolt) **or** plain **HttpURLConnection** (HTTP) | Two implementations of one `CypherClient` interface — auto-selected by URI scheme. HTTP path is used when sandboxes block raw socket I/O. |
| HTML reports | **FreeMarker** 2.3 | Familiar template syntax; safe HTML escaping by default |
| JSON | **Jackson** 2.17 | Records serialize cleanly with the default ObjectMapper |
| Test-case import | **Apache POI** 5.2 | Reads the user's `TestCases.xlsx` (optional input) |
| Web server | **Spring Boot** 3.3 (web + websocket) + **Jetty** 12 (embedded) | Exposes the analyze / ingest pipeline as REST + WebSocket. Jetty rather than Tomcat after an early Tomcat NIO-init regression on a Windows test host; Jetty's NIO model worked through the same regression in retries. |
| Password hashing | **spring-security-crypto** 6.3 (BCrypt cost 12) | Stored on `:AppUser.passwordHash`. Full spring-boot-starter-security is **not** pulled in — only the crypto primitives. |
| JWT | **jjwt** 0.12 (`jjwt-api` + `jjwt-impl` + `jjwt-jackson`) | HS256 8-hour tokens for the web app. Signing key from `IMPACT_JWT_SECRET` or `--jwt-secret`. |
| SPA (P10) | **React** 18 + **TypeScript** 5 + **Vite** 5 + **Tailwind** 3 + **react-router-dom** 6 | Single-page UI for login / dashboard / analyze / jobs / live log tail. Vite production build emits to `impact-cli/src/main/resources/static/`, so the shaded jar serves both REST and SPA on the same origin. |
| Tests | **JUnit 5** + Testcontainers Neo4j | Integration tests against a real Neo4j container |

---

## 3. Module split

`impact-cli` is one Maven module today (a multi-module split is on the v2 roadmap).
Inside, the Java packages are organised by **pipeline stage**:

```
io.spmp.impact
├── Main                          ← picocli entry point; wires up sub-commands
├── cmd                           ← CLI commands (one class per `impact <verb>`)
│   ├── IngestCmd                 ← runProgrammatically(IngestParams) entry shared with web layer
│   ├── AnalyzeCmd
│   ├── QueryCmd
│   ├── WipeCmd
│   ├── SnapshotsCmd
│   ├── TestCasesCmd
│   ├── ReposCmd                  ← P3 Zoho Repository API discovery
│   ├── WebCmd                    ← P9 — boots Spring Boot WebApplication
│   ├── UsersCmd                  ← P9.6 — CRUD on :AppUser via Neo4j
│   ├── TreeSitterSmokeCmd        ← internal: verifies JNI bindings load
│   ├── Neo4jOptions              ← shared --neo4j / --user / --pass mixin
│   └── RemoteRepoOptions         ← shared --remote-* mixin for Zoho integration
├── extract                       ← INGEST pipeline (source → graph)
│   ├── JavaProjectParser         ← per-thread parser pool; parseFileNoSymbols (no SymbolSolver)
│   ├── CoreExtractor             ← three-pass orchestration: decls → body collection → resolve → emitOverrides
│   ├── GlobalIndex               ← class/method/field/imports indices; frozen after Pass 1 for lock-free reads
│   ├── BodyCollector             ← manual recursive AST walker with scope-stack typed locals; emits CallSite records
│   ├── CallResolver              ← 7-strategy resolver (enclosing-this/this/super/static-class/local-var/field-ref/constructor); async TSV debug log
│   ├── FileImports               ← flat per-file import record (no CU retention) + JDK common-types fallback
│   ├── ExtractionBatch           ← accumulator: lists of nodes, edges, and pending CallSites
│   ├── BoundaryResolver          ← interface implemented by every resolver
│   └── resolver                  ← one class per boundary type
│       ├── ServletResolver
│       ├── SchedulerResolver
│       ├── TaskRegistryResolver
│       ├── MessageConstantResolver
│       ├── DbTableResolver               (Java AST: SelectQuery / Table.getTable / Column.getColumn / new Column)
│       ├── PowerShellInvocationResolver  (Java code → .ps1 literal sites)
│       ├── PowerShellScriptResolver      (whole-repo .ps1 walker with noise filters)
│       ├── RestApiXmlResolver            (parses ADSProductAPIS.xml-style configs; multi-root for deps)
│       ├── DbSchemaXmlResolver           (parses data-dictionary.xml; multi-root for deps)
│       ├── HtmlPageResolver              (jsoup walk of *.html)
│       ├── JsEmberResolver           (tree-sitter walk of *.js)
│       ├── HbsTemplateResolver       (regex walk of *.hbs)
│       └── CsSharepointResolver      (tree-sitter walk of *.cs)
├── graph                         ← Neo4j persistence
│   ├── Neo4jWriter               ← UNWIND-batched MERGE for every node/edge kind
│   ├── Schema                    ← constraints + indexes (idempotent bootstrap)
│   ├── CypherQueries             ← slice queries (forward / backward / DB / risk / etc.)
│   └── txn                       ← transport abstraction
│       ├── CypherClient              (interface)
│       ├── BoltCypherClient          (Neo4j Java Driver)
│       └── HttpCypherClient          (raw HttpURLConnection — sandbox-safe)
├── diff                          ← ANALYZE-side: patch parsing + hunk mapping
│   ├── JgitDiffSource            ← git-mode diff via jgit
│   ├── PatchFileDiffSource       ← unified-diff .patch file via jgit Patch
│   ├── HunkToSymbolResolver      ← Java: hunk lines → smallest enclosing AST symbol
│   ├── TreeSitterHunkResolver    ← JS/C#: same, via tree-sitter
│   ├── DeletedJavaSymbolScanner  ← surfaces Java methods/classes removed in `-` lines
│   └── PolyglotResolver          ← file-level resolver for non-Java patch files (JS / HBS / C# / XML / .properties / JSON)
├── analyze
│   └── SliceExecutor             ← runs all Cypher slices, builds ImpactReport
├── testgen                       ← P8 — test-case knowledge base + tagging
│   ├── TestCaseBatch
│   ├── TestCaseTagger
│   ├── ingest
│   │   ├── TestCasesMdIngestor
│   │   └── TestCasesXlsxIngestor
│   └── generate                  ← P8' — plain-English test-case generator
│       ├── TestCaseGenerator
│       ├── AreaClassifier            (package → area code)
│       ├── EnglishTranslator         (CamelCase → "English Words")
│       ├── StepTemplates             (per-kind step templates)
│       └── MarkdownWriter            (writes generated-testcases.md)
├── report
│   ├── HtmlReportRenderer        ← FreeMarker + report.ftl → HTML
│   └── JsonReportRenderer        ← Jackson → JSON
├── remote                        ← P3 — Zoho Repository API
│   ├── RemoteRepositoryClient        (JDK HttpClient — GET only)
│   ├── RemoteRepoMaterializer        (jgit clone → ~/.impact-cli/cache/<org>/<repo>/<sha>)
│   └── RemoteRepoException
├── web                           ← P9 — Spring Boot REST server (started by WebCmd)
│   ├── WebApplication                @SpringBootApplication entry; installs TeePrintStream pre-Spring
│   ├── Neo4jConfig                   singleton Neo4jWriter bean shared by all controllers
│   ├── SpaForwardingConfig           history-mode fallback so SPA deep links survive reload
│   ├── api/                          REST controllers
│   │   ├── HealthController          GET  /api/v1/health
│   │   ├── AuthController            POST /api/v1/auth/login + GET /api/v1/auth/whoami
│   │   ├── ReposController           GET  /api/v1/repos
│   │   ├── AnalyzeController         POST /api/v1/analyze (sync)
│   │   └── IngestController          POST /api/v1/ingest (async) + GET /api/v1/jobs[/{id}]
│   ├── auth/                         BCrypt + JWT
│   │   ├── AppUser                   record {username, passwordHash, roles, createdAt}
│   │   ├── AppUserService            CRUD + BCrypt verify
│   │   ├── JwtService                HS256 issue/parse; key from impact.jwt.secret
│   │   ├── JwtAuthFilter             OncePerRequestFilter for /api/v1/* (except /health, /auth/login)
│   │   └── AdminBootstrap            @PostConstruct seeds admin/<random> on empty Neo4j
│   ├── jobs/                         P9.8 — async job tracking
│   │   ├── JobState                  ring buffer (last 2000 lines) + listener fan-out
│   │   ├── JobRegistry               singleton concurrent map
│   │   └── TeePrintStream            ThreadLocal-bound System.out fanout for live logs
│   └── ws/                           P9.8 — WebSocket live tail
│       ├── WebSocketConfig           registers /ws/jobs/* handler
│       └── JobLogWebSocketHandler    buffer replay + live subscribe + terminal status frame
└── model                         ← shared record types (no behavior)
    ├── DiffModels                    (FileChange, ChangedSymbol, ChangeNature)
    ├── GraphNodes                    (FileNode, ClassNode, MethodNode, …)
    ├── GraphEdges                    (CallEdge, ExtendsEdge, …)
    └── ImpactReport                  (top-level report record + nested types)
```

The web SPA lives in a sibling Node project:

```
impact-web/ui/                       ← P10 — React + TS + Vite SPA
├── package.json, vite.config.ts     ← outputs to ../../impact-cli/src/main/resources/static/
├── tsconfig.json, tailwind.config.js, postcss.config.js, index.html
└── src/
    ├── main.tsx, App.tsx, index.css
    ├── api/client.ts                  typed fetch wrapper, JWT storage, wsJobsUrl helper
    └── pages/                         Login, Dashboard, AnalyzePage, Jobs, JobDetail, Layout
```

---

## 4. The graph schema in one picture

```
                            ┌──────┐
                            │ Repo │
                            └──┬───┘
                       :HAS_SNAPSHOT
                               │
                               ▼
                           ┌────────┐
                           │ Commit │
                           └────────┘
                               ▲
                               │ :IN_COMMIT
                           ┌───┴────┐
              :CONTAINS    │  File  │
        ┌──────────────────┴────┬───┘
        │                       │
        ▼                       ▼
   ┌─────────┐  :CONTAINS   ┌────────┐  :CONTAINS  ┌────────┐
   │ Package │ ───────────► │ Class  │ ──────────► │ Method │
   └─────────┘              └──┬─────┘             └──┬─────┘
                               │                       │
                  :EXTENDS    :EXPOSES    :HANDLES     :CALLS / :DISPATCHES_TO
                  :IMPLEMENTS    │           │           :OVERRIDES
                  :CONTAINS      ▼           ▼           :READS / :WRITES
                          ┌─────────────┐ ┌──────────┐   :READS_TABLE / :WRITES_TABLE
                          │RestEndpoint │ │ TaskType │   :INVOKES_SCRIPT
                          └──────┬──────┘ └──────────┘   :SENDS_MESSAGE / :RECEIVES_MESSAGE
                                 │                            │
                  :CALLS_API     │   :REFERENCES              ▼
        ┌────────────────────────┤◄──────────────┐    ┌─────────────────┐
        │              ┌─────────┘               │    │  Field /        │
   ┌────┴──────┐       │                    ┌────┴────┴┐ DbTable /       │
   │ JsFile    │   ┌───┴────┐               │HtmlPage  │ DbColumn /      │
   │           │◄─►│ CsFile │               └──────────┘ PsScript /      │
   │ :IMPORTS  │   └────────┘                            MessageConstant │
   │ :RENDERS_ │                                         └────────────────┘
   │  TEMPLATE │
   └─────┬─────┘
         │ :USES_COMPONENT (reverse)
         ▼
   ┌──────────────┐
   │ HbsTemplate  │
   └──────────────┘
```

Sub-labels stack on top of `:Class` / `:Method` (e.g. a servlet handler is
`Class:Servlet`, a scheduled `Task` is `Class:Job`, a method tagged via the
`SchedulerResolver` becomes `Method:EntryPoint`).

---

## 4.1 Additional rules under consideration (roadmap)

The graph today captures call-flow, URLs, DB tables, schedulers, JGroups
messages, scripts, and the cross-language JS/HBS/C# pieces. These are good
enough for **most** impact questions, but several user-visible effects — notification
delivery, audit writes, feature-flag gating, permission requirements — currently
get surfaced only via name-matching heuristics over the forward-reach FQN list.

This section catalogues the additional rules we'd add to lift those heuristics
into first-class graph edges. Each rule is written in plain English with a code
example and a sample Cypher edge form. They are **not implemented yet** —
this section is the prioritised roadmap.

### Why promote heuristics to edges?

When a heuristic (e.g. *"if any forward-reach FQN contains the word 'Notification', say notification delivery"*) becomes a real edge, three things improve:

1. **Precision**: instead of "notification delivery", the report names *which* notification — e.g. `WF_REQUEST_REJECTED`.
2. **Diff-awareness**: when a patch adds a new constant (a new email template, a new audit category), the new edge appears in the graph diff — you can ask "which patches added new audit categories last month?".
3. **Composability**: edges combine in Cypher. "Find every changed method that sends a notification AND writes an audit row AND requires the ADMIN permission" is a one-liner once the edges exist.

### A. Notification & Audit edges

#### Rule N1 — `:SENDS_NOTIFICATION` (Method → NotificationType)

> Whenever a Java method calls `PushNotificationTrigger`, `NotificationMacro.init()`, or `NotificationManager.send(...)`, draw an arrow from that method to a `:NotificationType` node named after the constant being passed.

```java
PushNotificationTrigger pt = new PushNotificationTrigger(notifMap);
NotificationMacro.init(NotifConstants.WF_REQUEST_REJECTED);  // ← constant captured
```

Graph:
```
(approveRequest:Method)-[:SENDS_NOTIFICATION]->(:NotificationType {id:"WF_REQUEST_REJECTED"})
```

**Why it matters**: today the report says *"likely user-visible effect: notification delivery"* via name-matching. With a real edge the report can pin down *which* notification, and a patch that adds a new notification constant becomes a graph diff.

#### Rule N2 — `:SENDS_EMAIL` (Method → EmailTemplate)

> Calls to `MailUtil.sendMail(...)`, `EmailSender.send(...)`, or anything taking a `template_id` argument become a graph edge to an `:EmailTemplate` node.

```java
MailUtil.sendMail(toAddr, "REJECT_NOTIFICATION_TEMPLATE", params);
```

Graph:
```
(approveRequest:Method)-[:SENDS_EMAIL]->(:EmailTemplate {id:"REJECT_NOTIFICATION_TEMPLATE"})
```

**Why it matters**: lets the test case generator say *"verify email using template `REJECT_NOTIFICATION_TEMPLATE` is received"* instead of generic *"email delivered"*.

#### Rule N3 — `:WRITES_AUDIT` (Method → AuditCategory)

> Calls to `AuditUtil.logAction(cat, ...)`, `TechnicianAuditLogger.log(...)`, or `AuditManager.add(...)` become an edge to an `:AuditCategory` node keyed on the category constant.

```java
AuditUtil.logAction(AuditConstants.WORKFLOW_REJECT, requestId, userId);
```

Graph:
```
(approveRequest:Method)-[:WRITES_AUDIT]->(:AuditCategory {id:"WORKFLOW_REJECT"})
```

**Why it matters**: the test case generator can say *"open Admin → Tech Audit → Workflow Reject and verify a new row"* — concrete, executable.

### B. Orchestration & "future schedule" coupling

#### Rule O1 — `:SCHEDULES` (Method → ScheduledTask)

> When code calls `ScheduleManager.schedule(taskInstance, delay)`, `Quartz.scheduleJob(...)`, `Timer.schedule(...)`, or any `*Executor.schedule(...)`, link the calling method to a `:ScheduledTask` node identified by the runnable/Task class FQN.

```java
WFSLATask sla = new WFSLATask(requestId);
ScheduleManager.schedule(sla, 60_000);     // schedules WFSLATask for 60s
```

Graph:
```
(approveRequest:Method)-[:SCHEDULES]->(:ScheduledTask {taskClassFqn:"...WFSLATask"})
```

**Why it matters**: this is the link we currently fake via the `shares-data` heuristic in `runAffectedSchedules` (table-coupling). With this rule, the report can say *"the patched reject path explicitly schedules `WFSLATask` to run in 60 seconds"* — control-flow proof, not heuristic.

#### Rule O2 — `:UNSCHEDULES` / `:CANCELS_SCHEDULE` (Method → ScheduledTask)

> Calls to `ScheduleManager.cancel(taskId)` / `Timer.cancel()` / `Future.cancel(true)` become an edge to the same `:ScheduledTask` node.

**Why it matters**: catches the *"the patch's reject path **clears** the pending SLA timer"* case — a regression test should verify the timer IS cancelled when reject succeeds.

#### Rule O3 — `:PUBLISHES_EVENT` / `:LISTENS_FOR` (Method ↔ EventType)

> `applicationEventPublisher.publishEvent(new WorkflowRejectedEvent(id))` becomes `(:Method)-[:PUBLISHES_EVENT]->(:EventType)`. Methods annotated `@EventListener(WorkflowRejectedEvent.class)` become `(:Method)-[:LISTENS_FOR]->(:EventType)`.

```java
// Publisher side
applicationEventPublisher.publishEvent(new WorkflowRejectedEvent(requestId));

// Subscriber side
@EventListener
public void onRejected(WorkflowRejectedEvent ev) { ... }
```

Graph:
```
(approveRequest:Method)-[:PUBLISHES_EVENT]->(:EventType {fqn:"...WorkflowRejectedEvent"})
(onRejected:Method)-[:LISTENS_FOR]->(:EventType {fqn:"...WorkflowRejectedEvent"})
```

**Why it matters**: today async pub-sub is invisible to the call graph. With these edges, *"every listener that runs when a workflow is rejected"* becomes a one-hop query.

#### Rule O4 — `:INSTANTIATES_HANDLER` (Method → Class)

> `new XxxTaskHandler()` from inside a registry / coordinator method becomes an edge from the calling method to the handler class.

Already partly captured via `:CALLS → <init>`, but a typed edge makes *"what task handlers does this method wire up?"* a one-line Cypher.

### C. Configuration & feature-flag awareness

#### Rule C1 — `:READS_PROPERTY` (Method → Property)

> Calls to `System.getProperty("foo")`, `ConfigManager.get("foo")`, `@Value("${foo}")`, or `PropertiesUtil.read("foo")` become edges to a `:Property` node keyed on the property name.

```java
int expirySec = Integer.parseInt(ConfigManager.get("workflow.reject.expiry.seconds"));
```

Graph:
```
(approveRequest:Method)-[:READS_PROPERTY]->(:Property {key:"workflow.reject.expiry.seconds"})
```

**Why it matters**: when a `.properties` file hunk lands in the patch (the polyglot resolver already flags these), the report can list every method that reads that property — *"changing `workflow.reject.expiry.seconds` affects 12 methods"*.

#### Rule C2 — `:GATED_BY` (Method → FeatureFlag)

> Calls to `FeatureFlagManager.isEnabled("loadBalancing")` or `LBConfig.isLoadBalancingMode()` become edges to a `:FeatureFlag` node.

```java
if (LBConfig.isLoadBalancingMode()) {
    DistributedTaskCoordinator.submit(task);
}
```

Graph:
```
(approveRequest:Method)-[:GATED_BY]->(:FeatureFlag {id:"loadBalancing"})
```

**Why it matters**: cleanly answers *"what changes only when load-balancing mode is on?"* — critical for the SPMP load-balancing rollout, and lets the test case generator emit two scenarios per flag-gated method (flag-on, flag-off).

### D. Security & access boundaries

#### Rule S1 — `:REQUIRES_PERMISSION` (Method → Permission)

> `@PreAuthorize("hasRole('ADMIN')")`, `AdmAccessChecker.check(perm)`, or any `AccessManager.verify(...)` call becomes an edge to a `:Permission` node.

```java
@PreAuthorize("hasAuthority('WORKFLOW_REJECT')")
public String approveRequest(...) { ... }
```

Graph:
```
(approveRequest:Method)-[:REQUIRES_PERMISSION]->(:Permission {id:"WORKFLOW_REJECT"})
```

**Why it matters**: every patched URL surfaces *"requires permission X"* — QA knows which user role to test as. Today the report says *"open Admin UI"*; with this it says *"as Admin role, with WORKFLOW_REJECT permission, open…"*.

#### Rule S2 — `:VALIDATES_INPUT` (Method → Validator)

> Calls to `Validator.validate(...)`, `@Valid` parameter usage, or `InputSanitizer.scan(...)` become edges to a `:Validator` node.

**Why it matters**: catches the *"the patch changes a validation rule"* case before it ships — every downstream method receiving validated input is impacted.

### E. External integration boundaries

#### Rule E1 — `:CALLS_EXTERNAL` (Method → ExternalSystem)

> HTTP-client calls (`HttpClient.send`, `RestTemplate.exchange`, `WebClient.post`) whose URL starts with a non-SPMP host become edges to an `:ExternalSystem` node (e.g., `SharePoint`, `Graph`, `Slack`).

```java
WebClient.create("https://graph.microsoft.com/v1.0/users").get().retrieve();
```

Graph:
```
(syncUsers:Method)-[:CALLS_EXTERNAL]->(:ExternalSystem {id:"Graph", baseUrl:"graph.microsoft.com"})
```

Today the `external:` URL prefix half-captures this in `AffectedApi`. Promoting to a first-class edge unifies SharePoint CSOM + Graph REST + future integrations behind one query.

#### Rule E2 — `:WRITES_LOG` (Method → LogChannel) *(use sparingly — every method logs)*

> Only when a logger uses a non-default channel name: `LoggerFactory.getLogger("audit.security")`, or marker-based loggers `log.atInfo().addKeyValue("audit", true)`.

**Why it matters**: separates ops-significant logs from chatter; lets the report say *"this patch writes to `audit.security` channel"*.

### F. State machines

#### Rule M1 — `:TRANSITIONS_STATE` (Method → State)

> Calls like `request.setStatus(REJECTED)`, `wfviRequest.updateRejectRequestTaskStatus(...)` become edges to a `:State` node keyed by `(entity, to)`.

```java
request.setStatus(WorkflowStatus.REJECTED);
```

Graph:
```
(approveRequest:Method)-[:TRANSITIONS_STATE]->(:State {entity:"WorkflowRequest", to:"REJECTED"})
```

**Why it matters**: today the report says *"`updateRejectRequestTaskStatus` is in forward-reach"* — opaque. With this edge, *"the patch transitions WorkflowRequest to REJECTED"* is a one-liner. The state machine model also lets you check *"are all transitions to REJECTED covered by tests?"*.

### G. Class-object rules (object lifecycle modelling — partial)

Worth doing partially, not fully. Per-instance lifecycle modelling (tracking each `new Foo()` allocation as its own node) is expensive in graph size and rarely useful at impact-analysis granularity. But three class-level shape rules ARE useful:

#### Rule N4 — `:INSTANTIATES` (Method → Class)

> When code does `new Foo()`, `Foo.getInstance()`, or `factory.create(Foo.class)`, draw an arrow from the caller to the class.

```java
WorkFlowAction action = new WorkFlowAction();
```

Graph:
```
(coordinator:Method)-[:INSTANTIATES]->(WorkFlowAction:Class)
```

**Why it matters**: detects orchestration — *"who creates handler instances?"* — without having to traverse through `<init>` constructor methods.

#### Rule N5 — `:SINGLETON_OF` (Class → Class)

> When a class holds a static field referencing itself (`private static Foo instance;` plus a `getInstance()` accessor), mark it as a singleton with a self-loop.

```java
public final class WorkFlowAction {
    private static WorkFlowAction INSTANCE = new WorkFlowAction();
    public static WorkFlowAction getInstance() { return INSTANCE; }
}
```

Graph:
```
(WorkFlowAction:Class)-[:SINGLETON_OF]->(WorkFlowAction:Class)
```

**Why it matters**: tells you a class has shared mutable state. Patching a singleton has wider blast radius than patching a class that's freshly instantiated per request — the report should escalate risk accordingly.

#### Rule N6 — `:INJECTS` (Class → Class)

> When one class receives another via `@Autowired`, `@Inject`, constructor injection, or setter injection, draw an arrow.

```java
@Component
public class WorkflowController {
    @Autowired private WFRequestService service;
}
```

Graph:
```
(WorkflowController:Class)-[:INJECTS]->(WFRequestService:Class)
```

**Why it matters**: maps the Spring/CDI dependency graph alongside the call graph. Catches *"X is wired into Y"* couplings that JavaParser's SymbolSolver may not resolve through interfaces or proxy beans — a patch that changes a `@Component`'s contract surfaces every `@Autowired` consumer.

### H. What we deliberately do NOT model

- **Per-instance lifecycles** (each `new Foo()` allocation as a separate node). Static analysis can't tell which `new Foo()` ended up at runtime location X without escape + alias analysis. Mostly adds noise.
- **Local variable flow.** Existing `:READS` / `:WRITES` on `:Field` is enough; tracking local vars per-method would 10× the graph for marginal gain.
- **JDK / third-party library internals.** Stubs for `java.lang.*` / `org.springframework.*` etc. stay as flat method nodes. We don't parse the JDK; we don't follow into the framework's internals.

### Suggested implementation order (by ROI)

| Order | Rule(s) | Why first | Estimate |
|---|---|---|---|
| 1 | **O1 `:SCHEDULES`** | Directly fixes the WFSLATask blind-spot. Control-flow proof beats `shares-data` heuristic. | 1 day; new `SchedulerCallResolver`. |
| 2 | **N1 `:SENDS_NOTIFICATION`** + **N3 `:WRITES_AUDIT`** | Makes the test case generator emit concrete user-visible expectations. Reuses the `MessageConstantResolver` pattern. | 1 day combined. |
| 3 | **C1 `:READS_PROPERTY`** + **C2 `:GATED_BY`** | High value for the LBF rollout — flag-gated paths need two test runs. | 1 day combined. |
| 4 | **N4 / N5 / N6** (class-object subset) | Improves accuracy on Spring/CDI-wired apps. Skip until a real case demands it. | 2 days when needed. |
| 5 | **S1 `:REQUIRES_PERMISSION`** | High-impact for the dense-permission ADMP surface. Pairs naturally with patched URLs. | 1 day. |
| Later | O2, O3, O4, N2, S2, E1, E2, M1 | Add as concrete cases arise. None block the v1 impact analyser. | Variable. |

### Adding a rule — checklist (matches §7 Extension Points)

For each rule above, the work is the same shape:

1. **Resolver**: new class under `extract/resolver/` implementing `BoundaryResolver`. Walks the AST per file, emits new edge records into `ExtractionBatch`.
2. **Edge record**: new `record` in `model/GraphEdges.java` (and `model/GraphNodes.java` if it introduces a new node label like `:NotificationType`).
3. **Schema constraint**: add a constraint to `graph/Schema.java` if the new node has a uniqueness key.
4. **Writer method**: new `writeXxx(...)` in `graph/Neo4jWriter.java` with UNWIND-batched MERGE.
5. **Slice query** (optional): new Cypher constant + `SliceExecutor` method if you want the new edge to flow into the report.
6. **Report field** (optional): new field on `ImpactReport`, render in `HtmlReportRenderer.toModel()` + `report.ftl`, append to `MarkdownWriter` if QA-relevant.
7. **Test-case generator hook** (optional): extend `TestCaseGenerator` / `StepTemplates` to consume the new edge for richer expected results.

The skeleton work per rule is small (~150-250 LoC); the design work is choosing the right node key (e.g. is a `:Permission` keyed by `(id)` or by `(id, scope)`?) and writing the call-site detection heuristic robustly enough to survive code-style variation.

---

## 5. Why two transports?

`CypherClient` is an interface with two implementations:

| Transport | When used | Why |
|---|---|---|
| `BoltCypherClient` | URI starts with `bolt://` / `neo4j://` | Native binary protocol — fastest. Uses Netty under the hood. |
| `HttpCypherClient` | URI starts with `http://` / `https://` | Plain HTTP POST against Neo4j's `/db/neo4j/tx/commit` endpoint via raw `HttpURLConnection`. **No NIO Selector — works inside sandboxed environments** that block loopback socket binds (where Netty fails at startup). |

Auto-selected by URI scheme — the rest of the codebase never sees the difference.

---

## 6. End-to-end flow for a single `analyze` run

1. **Parse the patch** — `PatchFileDiffSource.readAll()` returns a `FileChange` per file. Java files go to the Java path; everything else (JS, HBS, C#, XML, properties, JSON) goes to the polyglot path.
2. **Map each Java hunk to a `ChangedSymbol`** — `HunkToSymbolResolver` walks the post-image AST and picks the smallest method/constructor/class whose line range overlaps the hunk (priority: members **inside** the hunk first, fall back to enclosing).
3. **Scan deleted Java symbols** — `DeletedJavaSymbolScanner` reads `-` lines from the raw patch and pulls method/class signatures out of them (graph nodes don't exist for them; surface as a "deleted" warning panel).
4. **Resolve polyglot files** — `PolyglotResolver` queries Neo4j for each non-Java file's role/owner/callers (e.g. for a JS file: who imports it? which HBS templates use it? which Java classes expose the URLs it calls?).
5. **Run Cypher slices** — `SliceExecutor.run()` issues batched UNWIND queries:
   * Forward reach (changed → :CALLS\* → leaves) per changed FQN.
   * Backward to entry points (servlets / schedulers / jobs / task handlers).
   * DB table reads/writes.
   * Risk scoring (entry-point breadth × sensitive-package flag).
   * Virtual-dispatch override expansion (so callers of `Parent.foo()` count when `Child.foo()` changes).
6. **Build affected-X lists** — per-API, per-DB-table, per-schedule, per-UI-component aggregators each run their own batched query + attribute a user-facing **Feature** (TaskType / Action / Report / Scheduler).
7. **Generate test cases** — `TestCaseGenerator.generate()` groups reached entry points by owner class, picks a step template per kind, fills in DB-write expectations.
8. **Render** — `HtmlReportRenderer` (FreeMarker), `JsonReportRenderer` (Jackson), `MarkdownWriter` (handwritten). All three consume the same `ImpactReport` record.

---

## 7. Extension points

* **New language**: add a `Resolver` under `extract/resolver/`. Implement `BoundaryResolver` (gives you a hook called during ingest after the Java AST walk). Wire it into `IngestCmd`. Add node/edge records under `model/` if you need new label types.
* **New slice query**: add a constant to `graph/CypherQueries.java` and a method to `SliceExecutor`. Add a new field on `ImpactReport` to surface the result. Update `HtmlReportRenderer.toModel()` and `report.ftl`.
* **New test-case template**: edit `testgen/generate/StepTemplates.java` (per entry-point kind) and `EnglishTranslator.java` (for any new naming conventions).
* **New diff source**: implement a class returning `List<FileChange>` from your input format (e.g. GitHub PR API). Use it in `AnalyzeCmd.run*()`.

See [USAGE.md](USAGE.md) for the CLI surface and [REPORT_GUIDE.md](REPORT_GUIDE.md)
for reading the output.
