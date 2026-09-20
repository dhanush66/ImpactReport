# Prerequisites

Everything you need installed and configured **before you can build or run impact-cli**.

---

## 1. Hardware

| Resource | Minimum | Recommended |
|---|---|---|
| CPU | 2 cores | 4+ cores (parallel parsing) |
| RAM | 4 GB free | 8 GB+ (Maven build needs ~2 GB, ingest needs ~2 GB, Neo4j needs ~2 GB, web server needs ~1 GB). **For multi-repo ingest** (`--dep` with one or more dep repos), run the JVM with `-Xmx4g` minimum — a combined ADSM + ADSF + webclient ingest holds ~4,400 ASTs during Pass 1 and ~600 MB of {@code GlobalIndex} afterwards. AST is dropped after Pass 1b so peak heap stays bounded. `-Xmx6g` recommended for headroom on streaming flushes. |
| Disk | 1 GB | 5 GB (Neo4j data + Maven `.m2` cache + npm cache + ingest cache) |
| OS | Windows 10 / macOS 12 / Linux any | tested on Windows 10 + GraalVM JDK 17 |

---

## 2. JDK 17

The fat-jar is compiled to Java 17 bytecode and uses records / pattern-matching switch.
Any JDK 17 distribution works — Oracle, GraalVM, Temurin/Eclipse, Amazon Corretto.

### Verify

```powershell
java -version
# expect:  openjdk version "17.x.x"  or  "21.x.x" (newer is fine)
```

### Set `JAVA_HOME`

Windows (PowerShell):
```powershell
$env:JAVA_HOME = "C:\Users\<you>\.jdks\graalvm-jdk-17.0.11"
$env:PATH      = "$env:JAVA_HOME\bin;$env:PATH"
```

Linux / macOS:
```bash
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
export PATH=$JAVA_HOME/bin:$PATH
```

### Notes

* **Java 8 is not supported.** Records and modern switch syntax break the build.
* **Java 21 works** — only the language level is 17; the runtime is forward-compatible.
* GraalVM is **not required** — used in development but any Hotspot JDK 17 is identical.

---

## 3. Maven 3.9+

Maven is needed to build the fat-jar (`mvn -DskipTests package`). Once built, the jar
itself is self-contained.

### Verify

```powershell
mvn -version
# expect:  Apache Maven 3.9.x  or  4.x
```

### Install

* **Windows**: install via Chocolatey (`choco install maven`) or download from
  [maven.apache.org](https://maven.apache.org/download.cgi) and add `bin/` to `PATH`.
* **macOS**: `brew install maven`
* **Linux (apt)**: `sudo apt install maven`

### Notes

* **No `mvnw` wrapper is checked into this repo** (intentional — the CI environment supplies Maven). If you want a wrapper, run `mvn wrapper:wrapper` once.
* Maven needs `MAVEN_OPTS=-Xmx2g` for the shade plugin on memory-constrained machines.

---

## 3.5 Node.js 18+ and npm 9+ — only if building the web UI (P10)

The CLI alone (ingest / analyze / testcases / query / wipe / snapshots) needs
**only JDK + Maven + Neo4j**. The React SPA shipped with the `web` subcommand
is **prebuilt and checked in** to `impact-cli/src/main/resources/static/`, so a
plain `mvn package` produces a fully working `impact.jar` including the SPA.

You need Node.js **only if you want to edit the SPA** and rebuild it.

### Verify

```powershell
node --version
# expect: v18.x.x or newer (tested on v24.15.0)
npm --version
# expect: 9.x.x or newer (tested on 11.12.1)
```

### Install

* **Windows / macOS**: install from [nodejs.org](https://nodejs.org/) (LTS channel) or via [nvm-windows](https://github.com/coreybutler/nvm-windows) / [nvm](https://github.com/nvm-sh/nvm).
* **Linux**: use your distro's nodejs package or nvm.

### One-time SPA build

When you change anything under `impact-web/ui/src/**`:

```powershell
cd impact-web/ui
npm install                # one time per checkout
npm run build              # → emits to ../../impact-cli/src/main/resources/static/

cd ../../impact-cli
mvn -DskipTests package    # bundle the fresh SPA into the jar
```

The Vite dev server (`npm run dev`) is faster for UI iteration — it proxies
`/api/*` and `/ws/*` to the embedded Spring Boot at port 8080, so you don't
need to rebuild the jar on every UI change.

### Notes

* Node is **only** a build-time dep for the UI. The runtime is plain JDK 17 — no Node process needed in production.
* If you don't touch `impact-web/ui/`, you can ignore this section entirely. The prebuilt SPA is committed to `impact-cli/src/main/resources/static/`.

---

## 4. Neo4j 5.x

The graph database is the persistent state. **Community Edition is sufficient** —
no enterprise features are used.

### Option A — Docker (easiest)

```bash
docker run -d \
  --name impact-neo4j \
  -p 7474:7474 \
  -p 7687:7687 \
  -e NEO4J_AUTH=neo4j/neo4j-password \
  -v neo4j_data:/data \
  neo4j:5
```

That exposes:
* **`http://localhost:7474`** — REST/HTTP transactional API + Neo4j Browser web UI
* **`bolt://localhost:7687`** — native Bolt binary protocol (faster, used by default)

### Option B — native install

* **Windows**: download the [Community zip](https://neo4j.com/deployment-center/), unzip, run `bin\neo4j.bat console`.
* **macOS**: `brew install neo4j` then `neo4j start`.
* **Linux (apt)**: follow the [official APT instructions](https://neo4j.com/docs/operations-manual/current/installation/linux/debian/) — Neo4j publishes its own apt repo.

### Set the password (first-run only)

If you used `NEO4J_AUTH=neo4j/...` in Docker, the password is already set. For a manual install:

```powershell
# Connect via cypher-shell once to set the password
cypher-shell -u neo4j -p neo4j
# It prompts you to change the default — pick any password.
```

### Verify Neo4j is reachable

```powershell
# HTTP transactional endpoint should respond
curl -u neo4j:neo4j-password http://localhost:7474/db/neo4j/tx/commit `
     -H "Content-Type: application/json" `
     -d '{"statements":[{"statement":"RETURN 1 AS ok"}]}'
# expect: {"results":[...{"row":[1]}...],"errors":[]}
```

Or use the **Neo4j Browser** at `http://localhost:7474/browser/` (uname `neo4j`, your password) and run:

```cypher
RETURN 1 AS ok;
```

### Notes

* The default Neo4j database is named `neo4j`. impact-cli hardcodes `/db/neo4j/tx/commit` for HTTP; if you renamed it, change `HttpCypherClient.java`.
* **Bolt vs HTTP**: impact-cli auto-selects by URI scheme. `bolt://` is faster (~3× on big ingests); `http://` works in sandboxes that block raw socket binds.

---

## 4.5 Environment variables for the web server (P9)

Optional but recommended in production. The `web` subcommand reads these:

| Variable | Purpose | Default if unset |
|---|---|---|
| `IMPACT_JWT_SECRET` | HS256 signing key for JWTs issued by `POST /api/v1/auth/login`. Base64-encoded bytes preferred; plain text accepted (padded to 32 bytes). | Random key generated on each start — tokens won't survive a restart. Fine for dev; **bad** for prod. |
| `NEO4J_PASS` | Fallback when `--pass` is omitted. Lets the CLI / web server start without the password in shell history. | Falls back to literal `neo4j`. |
| `IMPACT_REPO_TOKEN` | Zoho Repository API auth token for the optional `--remote-*` flow (CLI only). | None — `--remote-*` won't work without it. |

```powershell
# Generate a stable JWT secret once and pin it (Windows PowerShell example)
$secret = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Maximum 256 -Minimum 0 } | ForEach-Object { [byte]$_ }))
[Environment]::SetEnvironmentVariable("IMPACT_JWT_SECRET", $secret, "User")
```

```bash
# Linux / macOS — drop into ~/.bashrc / ~/.zshrc
export IMPACT_JWT_SECRET="$(openssl rand -base64 32)"
export NEO4J_PASS="your-neo4j-password"
```

The first time the `web` subcommand starts with an empty `:AppUser` set, it
auto-creates an `admin` user and prints the generated password to **stdout
exactly once**. Save it immediately — there is no recovery path other than
deleting the `:AppUser` node and restarting the server.

---

## 5. Git (optional — only for `--base/--head` mode)

`analyze` has two diff modes:
* **`--patch <file.patch>`** — needs nothing beyond a unified-diff file. **Recommended.**
* **`--base <ref> --head <ref>`** — needs the source tree to be a git working tree.

For the second mode, `git` must be on `PATH` AND `--repo <path>` must point at a `.git` directory.

```powershell
git --version
# expect:  git version 2.x or 3.x
```

---

## 6. Source tree to analyze

The source tree must be **on local disk** (impact-cli does not clone). It needs:

* A Java source root somewhere (e.g. `<repo>/src/main/java` or `<repo>/source/java`). Pass it as `--src`.
* Optional polyglot roots — auto-detected if they sit at sibling paths (`<src>/../ember/app`, `<src>/../c_sharp`, `<src>/../html`, `<src>/../../product_package/conf`). Override with `--js-root`, `--cs-root`, `--html-root`, `--xml-conf`.

The Java source must **parse cleanly with JavaParser** (since 2026-05 the call extractor
no longer uses SymbolSolver — types are resolved via per-file imports + a JDK common-types
registry + the in-memory {@code GlobalIndex}). In practice: a syntactically valid Java
source tree is sufficient; missing JDK classpath entries cause some param-type FQNs to
fall back to {@code java.lang.X} / {@code java.util.X} via the registry, then to import
lookup, then to source-text (last resort). The fuzzy override matcher tolerates remaining
`?` placeholders.

---

## 7. Quick smoke test (after install)

```powershell
# 1. JDK + Maven (always required)
java -version
mvn -version

# 2. (Optional — only if you'll rebuild the SPA) Node + npm
node --version
npm --version

# 3. Neo4j HTTP
curl -u neo4j:neo4j-password http://localhost:7474/db/neo4j/tx/commit `
     -H "Content-Type: application/json" `
     -d '{"statements":[{"statement":"RETURN 1"}]}'

# 4. Build the jar (also bundles the prebuilt SPA)
cd impact-cli
mvn -q -DskipTests package
# expect: BUILD SUCCESS, target/impact.jar exists

# 5. CLI smoke — list every subcommand
java -jar target/impact.jar --help
# expect subcommands:
#   ingest, analyze, query, wipe, snapshots, testcases,
#   ts-smoke, repos, web, users

# 6. Graph smoke (no data yet — should return 0 nodes)
java -jar target/impact.jar query "MATCH (n) RETURN count(n) AS n" `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
# expect: {n=0}

# 7. (Optional — web stack) Start the REST server + SPA
java -jar target/impact.jar web --port 8080 `
  --neo4j http://localhost:7474 --user neo4j --pass neo4j-password
# First run prints a bootstrap admin password to stdout. Save it.
# Then open http://localhost:8080/ in a browser → Login page.
# Health check from another shell:
curl http://localhost:8080/api/v1/health
# expect: {"status":"UP","version":"0.1.0","neo4jUri":"...","neo4jReachable":true}
```

If steps 1, 3, 4, 5, 6 succeed you're ready to run `ingest` from the CLI.
If step 7 also succeeds, the web UI works too. See [USAGE.md](USAGE.md) for the
full command + endpoint reference.
