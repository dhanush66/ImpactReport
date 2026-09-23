package io.spmp.impact.cmd;

import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.CoreExtractor;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.extract.JavaProjectParser;
import io.spmp.impact.extract.resolver.DbSchemaXmlResolver;
import io.spmp.impact.extract.resolver.DbTableResolver;
import io.spmp.impact.extract.resolver.SecurityXmlResolver;
import io.spmp.impact.extract.resolver.ServletForwardConfigResolver;
import io.spmp.impact.extract.resolver.WebXmlResolver;
import io.spmp.impact.extract.resolver.RestApiXmlResolver;
import io.spmp.impact.extract.resolver.SchedulerResolver;
import io.spmp.impact.extract.resolver.ServletResolver;
import io.spmp.impact.extract.resolver.ThreadStartResolver;
import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.Schema;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import io.spmp.impact.extract.resolver.ResolverUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

@Command(name = "ingest", description = "Parse Java sources and write the call graph to Neo4j.")
public class IngestCmd implements Callable<Integer> {

    @Option(names = "--src",
        description = "Root directory of Java sources (recursive). Required unless --remote-repo is supplied.")
    Path src;

    @Option(names = "--commit", description = "Commit SHA tag for this snapshot (default: 'HEAD').")
    String commit = "HEAD";

    @Option(names = "--repo-id", description = "Repository identifier (default: derived from src dir name).")
    String repoId;

    @Option(names = "--incremental", description = "Skip files whose hash matches the previous ingestion.")
    boolean incremental;

    @Option(names = "--xml-conf",
        description = "Directory containing REST-API config XML (default: auto-detected at <src>/../../product_package/conf).")
    Path xmlConf;

    @Option(names = "--html-root",
        description = "Directory containing source/html/*.html files (default: auto-detected at <src>/../html).")
    Path htmlRoot;

    @Option(names = "--js-root",
        description = "Directory containing the Ember app sources (default: auto-detected at <src>/../ember/app).")
    Path jsRoot;

    @Option(names = "--cs-root",
        description = "Directory containing C# sources (default: auto-detected at <src>/../c_sharp).")
    Path csRoot;

    /**
     * Multi-repo / dependent-repo support. Each entry is {@code <repoId>=<javaSourceRoot>}.
     * The dependency's source is fed to the same SymbolSolver as the primary, so
     * cross-repo inheritance/calls resolve cleanly — closes the OVERRIDES gap when a
     * product extends framework classes that live in a sibling repo. Per-dep XML schema
     * roots are auto-detected at {@code <dep-src>/../../product_package/conf} if present.
     */
    @Option(names = "--dep",
        description = "Dependency repo as <repoId>=<javaSourceRoot>. Repeatable. "
                    + "Ingested into the same graph; FQN-keyed Class/Method nodes merge naturally across repos.")
    Map<String, Path> deps = new java.util.LinkedHashMap<>();

    /**
     * SymbolSolver strategy for multi-repo ingest. Default is per-repo isolation
     * (fast, scales linearly). Unified mode shares one CombinedTypeSolver across all
     * roots — more accurate cross-repo type resolution but pays a combinatorial scan
     * cost when any dep has large source files (e.g. ADSM webclient has 200KB+ Java
     * files that drive SymbolSolver into multi-minute hangs in unified mode).
     */
    @Option(names = "--unified-symbols",
        description = "Use a single SymbolSolver across all repos (slower but resolves cross-repo "
                    + "types through SymbolSolver). Default: per-repo isolation + AST-level import "
                    + "fallback for cross-repo edges.")
    boolean unifiedSymbols;

    /**
     * Repo IDs whose Java files are parsed WITHOUT a SymbolSolver. The AST is still fully
     * built; param-type FQNs on a fraction of CALLS edges degrade to {@code ?}. Use this
     * for repos with pathological resolve patterns (large generated code, deep generic
     * chains, huge if-else towers) where SymbolSolver gets stuck for 10+ minutes per file.
     * Concrete example: ADSM webclient (web/adsm/src) has 1,114 files where a normal
     * ingest wedges on single files for 15+ minutes each; lite mode finishes in ~minutes
     * with the only loss being lower-precision param types on cross-repo call edges.
     */
    /**
     * Optional override for the security-XML walker root. If unset, {@link SecurityXmlResolver}
     * walks each repo's top-level directory (same set used by the PowerShell-script walker)
     * looking for {@code WEB-INF/security/security*.xml} files. ADManager Plus's REST URLs
     * are declared there ({@code <url path="/api/json/...">}).
     */
    @Option(names = "--security-xml-root",
        description = "Explicit web/WEB-INF/security root override. By default every repo's "
                    + "top dir is walked for WEB-INF/security/security*.xml files.")
    Path securityXmlRoot;

    @Option(names = "--audit-log",
        description = "Write a detailed per-section audit log to this path. Captures per-resolver "
                    + "counts, per-call-kind resolution percentages, per-file timeouts, and a "
                    + "post-ingest phantom-edge audit. Use when investigating 'what relationships "
                    + "are we missing?'")
    Path auditLog;

    @Mixin
    Neo4jOptions neo;

    @Mixin
    RemoteRepoOptions remote;

    /**
     * P9.8 — Programmatic entry point for the web/api layer. Bypasses picocli but
     * runs the exact same {@link #call()} body so CLI and REST produce identical
     * results. Caller supplies a {@link IngestParams} bag; this populates the
     * package-private fields and invokes the pipeline.
     *
     * <p>Remote-repo path is NOT exposed here for v1 — web ingests assume the source
     * is already on disk (or has been materialized by an earlier {@code repos} call).
     */
    public static int runProgrammatically(IngestParams p) throws Exception {
        IngestCmd cmd = new IngestCmd();
        cmd.src       = p.src;
        if (p.commit != null && !p.commit.isEmpty()) cmd.commit = p.commit;
        cmd.repoId    = p.repoId;
        cmd.xmlConf   = p.xmlConf;
        cmd.htmlRoot  = p.htmlRoot;
        cmd.jsRoot    = p.jsRoot;
        cmd.csRoot    = p.csRoot;
        if (p.deps != null) cmd.deps = new java.util.LinkedHashMap<>(p.deps);
        cmd.incremental    = p.incremental;
        cmd.unifiedSymbols = p.unifiedSymbols;
        // Default the audit log path to logs/ingest-audit-<timestamp>.log when the
        // web caller didn't provide one — keeps all log files in one place
        // (impact-cli/logs/ when the JAR is launched from the impact-cli/ directory).
        // Sibling files (*.calls.tsv) land in the same dir via enableAuditLog's
        // resolveSibling.
        if (p.auditLog != null) {
            cmd.auditLog = p.auditLog;
        } else {
            String ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            cmd.auditLog = Path.of("logs", "ingest-audit-" + ts + ".log");
        }
        cmd.securityXmlRoot = p.securityXmlRoot;
        cmd.neo = new Neo4jOptions();
        cmd.neo.uri  = p.neo4jUri;
        cmd.neo.user = p.neo4jUser;
        cmd.neo.pass = p.neo4jPass;
        // Web doesn't drive remote-repo materialization (yet) — pass an empty mixin
        // so isRemoteEnabled() returns false.
        cmd.remote = new RemoteRepoOptions();
        return cmd.call();
    }

    /** Parameter bag for {@link #runProgrammatically(IngestParams)}. Fields map 1:1 to the CLI flags. */
    public static final class IngestParams {
        public Path src;
        public String commit;
        public String repoId;
        public Path xmlConf;
        public Path htmlRoot;
        public Path jsRoot;
        public Path csRoot;
        public java.util.Map<String, Path> deps;
        public boolean incremental;
        public boolean unifiedSymbols;
        public Path auditLog;
        public Path securityXmlRoot;
        public String neo4jUri;
        public String neo4jUser;
        public String neo4jPass;
    }

    @Override
    public Integer call() throws Exception {
        // ── Remote-repo branch: materialize from Zoho API, then continue as if --src/--dep
        //    were filesystem paths. All downstream pipeline code is unchanged.
        if (remote.isRemoteEnabled()) {
            if (src != null) {
                System.err.println("error: --src and --remote-repo are mutually exclusive");
                return 11;
            }
            try {
                remote.validateForRemote();
                io.spmp.impact.remote.RemoteRepoMaterializer mat =
                    new io.spmp.impact.remote.RemoteRepoMaterializer(remote);
                io.spmp.impact.remote.RemoteRepoMaterializer.MaterializedRepo primary =
                    mat.materialize(remote.remoteRepo, remote.remoteRef);
                // The materialized tree has the FULL repo at sourceRoot. Probe for a
                // {@code source/java} (or similar) sub-folder so the extractor doesn't
                // also walk node_modules / docs / etc. Falls back to the whole tree.
                src = pickJavaRoot(primary.sourceRoot());
                if (repoId == null) repoId = primary.repoIdForGraph();
                if ("HEAD".equals(commit)) commit = primary.resolvedSha();
                System.out.println("[ingest] remote source root: " + src);
                // Materialize every --remote-dep into a per-id filesystem path.
                if (remote.remoteDeps != null) {
                    for (var entry : remote.remoteDeps.entrySet()) {
                        var depMat = mat.materialize(entry.getValue(), null);
                        Path depJava = pickJavaRoot(depMat.sourceRoot());
                        deps.put(entry.getKey(), depJava);
                        System.out.println("[ingest] remote dep " + entry.getKey()
                            + " (" + entry.getValue() + "@" + truncateSha(depMat.resolvedSha())
                            + ") → " + depJava);
                    }
                }
            } catch (io.spmp.impact.remote.RemoteRepoException re) {
                System.err.println(re.getMessage());
                return re.exitCode();
            }
        }

        if (src == null) {
            System.err.println("error: either --src or --remote-repo must be supplied");
            return 11;
        }
        if (repoId == null) repoId = src.toAbsolutePath().getFileName().toString();

        // Build the ordered repo set: primary first, then deps in declaration order.
        // Each repoId can have MULTIPLE Java source roots (e.g. ADMP's source/java_source
        // + web/adsm/src/ both belong to the same logical "adsm" repo).
        java.util.LinkedHashMap<String, java.util.List<Path>> rootsByRepoId = new java.util.LinkedHashMap<>();
        java.util.List<Path> primaryRoots = discoverJavaRoots(src);
        if (primaryRoots.isEmpty()) {
            System.err.println("error: no Java source roots found under " + src.toAbsolutePath());
            return 11;
        }
        rootsByRepoId.put(repoId, primaryRoots);
        if (deps != null) {
            for (var e : deps.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty()) continue;
                if (e.getKey().equals(repoId)) {
                    System.err.println("[ingest] dep repo-id '" + e.getKey() + "' collides with --repo-id; skipping");
                    continue;
                }
                java.util.List<Path> depRoots = discoverJavaRoots(e.getValue());
                if (depRoots.isEmpty()) {
                    System.err.println("[ingest] dep '" + e.getKey() + "' has no Java source roots under " + e.getValue());
                    continue;
                }
                rootsByRepoId.put(e.getKey(), depRoots);
            }
        }

        // The "repo top" recorded as :Repo.source_root: prefer the user-supplied --src
        // (more meaningful in the UI than the first auto-detected sub-root).
        java.util.LinkedHashMap<String, Path> repoTopBySrc = new java.util.LinkedHashMap<>();
        repoTopBySrc.put(repoId, src);
        if (deps != null) {
            for (var e : deps.entrySet()) {
                if (e.getKey() == null || e.getKey().equals(repoId)) continue;
                if (rootsByRepoId.containsKey(e.getKey())) repoTopBySrc.put(e.getKey(), e.getValue());
            }
        }

        System.out.println("[ingest] src=" + src.toAbsolutePath());
        System.out.println("[ingest] repo=" + repoId + " commit=" + commit);
        if (primaryRoots.size() > 1 || (!primaryRoots.get(0).equals(src.toAbsolutePath().normalize()))) {
            for (Path p : primaryRoots) {
                System.out.println("[ingest]   java-root: " + p);
            }
        }
        if (rootsByRepoId.size() > 1) {
            for (var e : rootsByRepoId.entrySet()) {
                if (e.getKey().equals(repoId)) continue;
                System.out.println("[ingest] dep=" + e.getKey() + " roots=" + e.getValue());
            }
        }

        try (Neo4jWriter writer = new Neo4jWriter(neo.resolveUri(), neo.resolveUser(), neo.resolvePass())) {
            Schema.bootstrap(writer);
            // Create a :Repo+:Commit pair for every repo. source_root = the user-supplied
            // path (the repo top dir), not the auto-detected Java sub-root — gives the
            // Repos page a meaningful display that the user can re-use as analyze repoPath.
            for (var entry : rootsByRepoId.entrySet()) {
                Path repoTop = repoTopBySrc.get(entry.getKey());
                String srcAbs = repoTop == null ? null : repoTop.toAbsolutePath().toString();
                writer.upsertRepoAndCommit(entry.getKey(), commit, srcAbs);
            }

            // Per-repo isolation by default. Multi-repo with shared SymbolSolver is opt-in
            // via --unified-symbols. For single-repo (no --dep) both modes behave the same,
            // so we keep the more-accurate unified mode in that case.
            boolean useUnified = unifiedSymbols || rootsByRepoId.size() == 1;
            JavaProjectParser parser = new JavaProjectParser(rootsByRepoId, useUnified);
            if (!useUnified) {
                System.out.println("[ingest] symbol-solver mode: isolated per-repo (use --unified-symbols to override)");
            }
            // The three-pass extractor never uses SymbolSolver — `--lite-repo` is no longer
            // meaningful and has been removed. The parser still keeps SymbolSolver-attached
            // instances around for the analyze (hunk-to-symbol) side, which is unaffected.
            Path resolvedXmlConf = resolveXmlConfRoot();
            Path resolvedHtmlRoot = resolveHtmlRoot();
            Path resolvedJsRoot = resolveJsRoot();
            Path resolvedCsRoot = resolveCsRoot();

            // Collect XML-config roots: primary + every dep's auto-detected conf dir.
            // ADSF's 20+ data-dictionary.xml files live at <dep-java-root>/../../product_package/conf/adsf/*.
            java.util.List<Path> allXmlConfRoots = new java.util.ArrayList<>();
            if (resolvedXmlConf != null) allXmlConfRoots.add(resolvedXmlConf);
            // Same pattern for C# source roots — auto-detect <dep-java-root>/../c_sharp.
            // ADSF/ADMP often has its own .cs files (password-sync agent, tray app, etc.)
            // that the primary repo doesn't, but they're part of the same product impact surface.
            java.util.List<Path> allCsRoots = new java.util.ArrayList<>();
            // Track canonical paths so we don't auto-detect the same physical c_sharp dir
            // twice (happens when two deps share a parent — e.g. webclient lives under the
            // same source tree as the primary, so its probe finds the primary's c_sharp).
            java.util.Set<Path> seenCs = new java.util.HashSet<>();
            if (resolvedCsRoot != null) {
                allCsRoots.add(resolvedCsRoot);
                seenCs.add(resolvedCsRoot.toAbsolutePath().normalize());
            }
            for (var e : rootsByRepoId.entrySet()) {
                if (e.getKey().equals(repoId)) continue;
                // Use the user-supplied dep top dir (not the auto-detected Java sub-root)
                // for sibling-dir probes — XML conf + C# roots typically live as siblings
                // of source/, not under the Java root itself.
                Path depTop = repoTopBySrc.getOrDefault(e.getKey(), e.getValue().get(0));
                Path depConf = autoDetectXmlConf(depTop);
                if (depConf != null) {
                    allXmlConfRoots.add(depConf);
                    System.out.println("[ingest] dep=" + e.getKey() + " xml-conf=" + depConf);
                }
                for (Path depCs : autoDetectAllCsRoots(depTop)) {
                    Path canonical = depCs.toAbsolutePath().normalize();
                    if (seenCs.add(canonical)) {
                        allCsRoots.add(depCs);
                        System.out.println("[ingest] dep=" + e.getKey() + " cs-root=" + depCs);
                    }
                }
            }

            // PowerShell script discovery — find each repo's TOP-LEVEL directory and
            // hand it to the script walker. The walker does its own recursive walk with
            // noise filters, so we don't need to enumerate every possible subdirectory
            // (help/, projectdocs/marketing/scripts-kb-page/, etc.). One root per repo.
            java.util.List<Path> allPsRoots = new java.util.ArrayList<>();
            java.util.Set<Path> seenPs = new java.util.HashSet<>();
            Path primaryRepoRoot = inferRepoRoot(src);
            if (primaryRepoRoot != null) {
                Path canonical = primaryRepoRoot.toAbsolutePath().normalize();
                if (seenPs.add(canonical)) allPsRoots.add(primaryRepoRoot);
            }
            for (var e : rootsByRepoId.entrySet()) {
                if (e.getKey().equals(repoId)) continue;
                Path depTop = repoTopBySrc.getOrDefault(e.getKey(), e.getValue().get(0));
                Path depRoot = inferRepoRoot(depTop);
                if (depRoot != null) {
                    Path canonical = depRoot.toAbsolutePath().normalize();
                    if (seenPs.add(canonical)) {
                        allPsRoots.add(depRoot);
                        System.out.println("[ingest] dep=" + e.getKey() + " ps-root=" + depRoot);
                    }
                }
            }

            // Security-XML roots: explicit override OR every repo's top-level dir (same set
            // as PowerShell). SecurityXmlResolver walks `WEB-INF/security/security*.xml`
            // under these roots; ADMP-style `<url path="/api/json/...">` declarations
            // become :RestEndpoint nodes + :EXPOSES edges via path-segment heuristic.
            java.util.List<Path> allSecurityRoots = securityXmlRoot != null
                ? java.util.List.of(securityXmlRoot.toAbsolutePath())
                : allPsRoots;

            List<BoundaryResolver> resolvers = List.of(
                new WebXmlResolver(webxmlXmlRoots()),
                new ServletResolver(),
                new SchedulerResolver(taskflowXmlPathsFromConfig(), delayedTaskCategoryXmlFromConfig(),
                    ResolverUtils.javaParserFacade(
                        rootsByRepoId.values().stream().flatMap(List::stream).collect(java.util.stream.Collectors.toList()))),
                new ThreadStartResolver(),
                new DbTableResolver(),
                new RestApiXmlResolver(AdspProductApiXmlFromConfig(), ResolverUtils.javaParserFacade(
                        rootsByRepoId.values().stream().flatMap(List::stream).collect(java.util.stream.Collectors.toList()))),
                new io.spmp.impact.extract.resolver.ServletApiXmlResolver(servletApiXmlFromConfig()),
                new DbSchemaXmlResolver(allXmlConfRoots),
                new SecurityXmlResolver(securityapiv2XmlRoots()),
                new io.spmp.impact.extract.resolver.AdmpApiDetailsXmlResolver(admpApiDetailsXmlFromConfig()),
                new io.spmp.impact.extract.resolver.StrutsConfigXmlResolver(strutsConfigXmlFromConfig()),
                new io.spmp.impact.extract.resolver.ReportsXmlResolver(reportXmlFromConfig()),
                new io.spmp.impact.extract.resolver.NotificationMacroResolver(),
                //new io.spmp.impact.extract.resolver.IMgmtListenerResolver(),
                new io.spmp.impact.extract.resolver.InterfaceResolver(),
                // Class.forName(...) dispatch -> CALLS edges. Resolves FQNs in afterAll via
                // the frozen GlobalIndex (see BoundaryResolver.indexFrozen); the XML dir
                // supplies the CLASS_NAME candidate set for table-backed targets.
                new io.spmp.impact.extract.resolver.ReflectionResolver(tableXmlDirFromConfig()),
                //new ServletForwardConfigResolver(allSecurityRoots, allXmlConfRoots),
                // Must be LAST: reads batch.exposes from all XML resolvers above,
                // expands class-level URL mappings (no method in XML) into per-method
                // virtual endpoints: e.g. /WorkFlow.do → /WorkFlow.do/approveRequest.
                new io.spmp.impact.extract.resolver.ClassMethodExpansionResolver()
            );
            CoreExtractor extractor = new CoreExtractor(parser, repoId, commit, resolvers);
            if (auditLog != null) {
                extractor.enableAuditLog(auditLog);
                System.out.println("[ingest] audit log: " + auditLog.toAbsolutePath());
            }

            // Streaming flush: drain leaf collections every 500 files and write them to
            // Neo4j immediately, so the in-memory ExtractionBatch stays small even when
            // ingesting thousands of files across multiple repos. Cuts peak heap roughly
            // in half on a 4k-file multi-repo run.
            final Neo4jWriter w = writer;
            extractor.setPartialFlushSink(delta -> {
                try { w.writeBatch(delta); }
                catch (Throwable t) { System.err.println("[ingest] partial flush failed: " + t.getMessage()); }
            }, 500);

            if (incremental) {
                Map<String, String> prevHashes = writer.loadFileHashes(commit);
                extractor.setIncremental(true, prevHashes);
                System.out.printf("[ingest] incremental mode: %d files previously ingested under commit '%s'%n",
                    prevHashes.size(), commit);
            }

            long t0 = System.currentTimeMillis();
            ExtractionBatch batch = extractor.run();
            long parseMs = System.currentTimeMillis() - t0;

            System.out.printf("[ingest] parsed %d files, %d classes, %d methods, %d call edges  (%d ms)%n",
                batch.fileCount(), batch.classCount(), batch.methodCount(), batch.callCount(), parseMs);
            if (incremental) {
                System.out.printf("[ingest] incremental: skipped %d unchanged files%n", extractor.skippedUnchanged());
            }
            System.out.printf("[ingest] boundary: %d task types, %d HANDLES, %d DISPATCHES_TO, %d REST endpoints%n",
                batch.taskTypes.size(), batch.handles.size(), batch.dispatchesTo.size(), batch.restEndpoints.size());
            System.out.printf("[ingest] boundary P5: %d DB-table edges, %d PS scripts, %d INVOKES_SCRIPT, %d msg constants, %d msg-edges%n",
                batch.dbTableEdges.size(), batch.psScripts.size(), batch.invokesScript.size(),
                batch.messageConstants.size(), batch.messageConstantEdges.size());

            writer.writeBatch(batch);
            // Task #98: ConstantIndex post-pass — reverse-map numeric :Permission /
            // :AuditCategory ids to symbolic names by joining against :Field nodes
            // with matching constant_value in *Constants classes. Idempotent.
            writer.runConstantIndexCleanup();
            // Remove stale synthetic DISPATCHES_TO(Thread ctor caller → run) edges that
            // were created by the former thread-dispatch bridge. ThreadStartResolver now
            // emits proper STARTS_THREAD edges (className.start() → run()) instead.
            {
                long deleted = writer.cleanupThreadDispatchBridgeEdges();
                if (deleted > 0) {
                    System.out.printf("[ingest] thread-dispatch bridge cleanup: removed %d stale DISPATCHES_TO edge(s)%n", deleted);
                }
            }
            // Bridge virtual dispatch gap for NotificationMacro overrides: callers of base
            // class parseMacro* methods get DISPATCHES_TO edges to concrete overrides.
            // Enables backward-reach from macro methods to entry points via CALLS chain.
            {
                long added = writer.runNotificationMacroVirtualDispatchBridge();
                System.out.printf("[ingest] notification-macro virtual dispatch bridge: %d DISPATCHES_TO edge(s) added%n", added);
            }
            System.out.println("[ingest] graph write complete.");

            // Post-ingest graph audit: answer "what fraction of CALL edges unify with real
            // :Method nodes?" — the real "did we miss any relationship" metric. Phantom
            // edges (targets that don't exist as :Method nodes) are invisible to back-reach
            // queries; high phantom % = many relationships missed.
            if (auditLog != null) {
                try (java.io.PrintWriter aw = new java.io.PrintWriter(
                        java.nio.file.Files.newBufferedWriter(auditLog,
                            java.nio.charset.StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.APPEND))) {
                    aw.println();
                    aw.println("=== POST-INGEST GRAPH AUDIT ===");
                    runPostIngestAudit(writer, aw);
                    aw.flush();
                } catch (Throwable t) {
                    System.err.println("[ingest] post-ingest audit failed: " + t.getMessage());
                }
            }
        }
        return 0;
    }

    /**
     * Run a battery of Cypher checks against the freshly-written graph and append the
     * results to the audit log. The point: surface "invisible" failure modes — phantom
     * CALL edges, orphan Method nodes, EXTENDS edges to classes without children, etc.
     * — so we can see at-a-glance which relationships were dropped and where to focus.
     */
    private static void runPostIngestAudit(io.spmp.impact.graph.Neo4jWriter writer, java.io.PrintWriter aw) {
        var session = writer.session();
        // 1. Per-label node counts
        try (var rs = session.run("MATCH (n) RETURN labels(n)[0] AS lbl, count(*) AS n ORDER BY n DESC")) {
            aw.println("Node counts by label:");
            while (rs.hasNext()) {
                var r = rs.next();
                aw.println("  " + r.get("lbl").asString("(null)") + " : " + r.get("n").asLong());
            }
        } catch (Throwable t) { aw.println("  ERROR node-counts: " + t.getMessage()); }

        // 2. CALL kind breakdown — total + by kind
        try (var rs = session.run(
            "MATCH ()-[r:CALLS]->() RETURN r.kind AS kind, count(*) AS n ORDER BY n DESC")) {
            aw.println("CALLS edges by kind:");
            while (rs.hasNext()) {
                var r = rs.next();
                aw.println("  " + r.get("kind").asString("(null)") + " : " + r.get("n").asLong());
            }
        } catch (Throwable t) { aw.println("  ERROR call-kinds: " + t.getMessage()); }

        // 3. Orphan Method nodes — phantom CALL targets. A real :Method node has a
        // :Class CONTAINS it. Methods without CONTAINS are synthetic nodes created
        // when a CALL edge pointed at an FQN we never extracted a real method for.
        long orphanMethods = 0L;
        try (var rs = session.run(
            "MATCH (m:Method) WHERE NOT EXISTS{ (:Class)-[:CONTAINS]->(m) } RETURN count(m) AS n")) {
            if (rs.hasNext()) {
                orphanMethods = rs.next().get("n").asLong();
            }
        } catch (Throwable t) { aw.println("  ERROR orphans: " + t.getMessage()); }
        long totalMethods = 0L;
        try (var rs = session.run("MATCH (m:Method) RETURN count(m) AS n")) {
            if (rs.hasNext()) totalMethods = rs.next().get("n").asLong();
        } catch (Throwable ignored) {}
        double orphanPct = totalMethods > 0 ? 100.0 * orphanMethods / totalMethods : 0;
        aw.printf("Phantom (orphan) Method nodes: %d / %d total (%.1f%%) — these are CALL edge targets%n",
            orphanMethods, totalMethods, orphanPct);
        aw.println("Lower is better. ~5-10%% is normal (3rd-party JDK/library methods).");

        try (var rs = session.run(
            "MATCH (m:Method) WHERE NOT EXISTS{ (:Class)-[:CONTAINS]->(m) } " +
            "RETURN m.fqn AS fqn ORDER BY m.fqn LIMIT 20")) {
            aw.println("Sample orphan target FQNs:");
            while (rs.hasNext()) aw.println("  - " + rs.next().get("fqn").asString());
        } catch (Throwable ignored) {}

        // 4. RestEndpoint owners — these are the back-reach targets
        try (var rs = session.run(
            "MATCH (m:Method:RestEndpoint) " +
            "RETURN m.owner_fqn AS owner, count(*) AS n ORDER BY n DESC LIMIT 30")) {
            aw.println("Top owners of :RestEndpoint methods (back-reach targets):");
            while (rs.hasNext()) {
                var r = rs.next();
                aw.println("  " + r.get("owner").asString("(null)") + " : " + r.get("n").asLong());
            }
        } catch (Throwable t) { aw.println("  ERROR ep-owners: " + t.getMessage()); }

        // 5. OVERRIDES distribution
        try (var rs = session.run(
            "MATCH (child:Method)-[:OVERRIDES]->(parent:Method) RETURN count(*) AS total")) {
            if (rs.hasNext()) aw.println("OVERRIDES edges total: " + rs.next().get("total").asLong());
        } catch (Throwable t) { aw.println("  ERROR overrides: " + t.getMessage()); }

        // 6. Per-repo file counts (sanity-check that all repos got ingested)
        try (var rs = session.run(
            "MATCH (f:File) RETURN f.repo_id AS repo, count(*) AS n ORDER BY n DESC")) {
            aw.println("Files per repo:");
            while (rs.hasNext()) {
                var r = rs.next();
                aw.println("  " + r.get("repo").asString("(null)") + " : " + r.get("n").asLong());
            }
        } catch (Throwable t) { aw.println("  ERROR per-repo: " + t.getMessage()); }
        aw.flush();
    }

    /**
     * Auto-detect the {@code product_package/conf} directory.
     * If {@code --src} ends with {@code .../source/java}, the conf dir is at
     * {@code <repo>/product_package/conf} where {@code <repo>} is the dir containing {@code source}.
     */
    private Path resolveXmlConfRoot() {
        if (xmlConf != null) return xmlConf.toAbsolutePath();
        Path probe = src.toAbsolutePath();
        // climb past java/ and source/ to find the project root
        for (int i = 0; i < 4 && probe != null; i++) {
            Path candidate = probe.resolve("product_package").resolve("conf");
            if (java.nio.file.Files.isDirectory(candidate)) return candidate;
            probe = probe.getParent();
        }
        return null;
    }

    //Get the web_headers.xml & web_footer.xml path from config.properties in the impact-cli working directory.
    private List<Path> webxmlXmlRoots() {
        Path configFile = Path.of("config.properties").toAbsolutePath();
        if (!Files.isRegularFile(configFile)) {
            System.out.println("[ingest] webxmlXmlRoots: config.properties not found at " + configFile);
            return null;
        }   
        List<Path> webXmlList = new ArrayList<Path>();
        String webHeader = "WebHeader";
        String webFooter = "WebFooter";
        try {
            // Read all lines into a List so we can filter it multiple times.
            // Files.lines() is a one-shot stream — reusing it after the first terminal
            // operation (findFirst) throws IllegalStateException.
            List<String> allLines = Files.readAllLines(configFile);
            Path repoRoot = inferRepoRoot(src);
            if (repoRoot == null) repoRoot = src.toAbsolutePath();

            Optional<String> matchingHeaderLine = allLines.stream()
                .map(String::trim)
                .filter(line -> line.startsWith(webHeader))
                .findFirst();

            if (matchingHeaderLine.isPresent()) {
                String[] parts = matchingHeaderLine.get().split("=", 2);
                if (parts.length == 2) {
                    String extractedValue = parts[1].trim();
                    boolean hasDriveLetter = extractedValue.length() >= 2
                        && Character.isLetter(extractedValue.charAt(0))
                        && extractedValue.charAt(1) == ':';
                    Path candidate;
                    if (hasDriveLetter) {
                        candidate = Path.of(extractedValue).normalize();
                    } else {
                        String relative = extractedValue.replaceAll("^[/\\\\]+", "");
                        candidate = repoRoot.resolve(relative).toAbsolutePath().normalize();
                    }
                    System.out.println("[ingest] WebHeader path from config: " + candidate);
                    if (Files.isRegularFile(candidate)) {
                        webXmlList.add(candidate);
                    }
                }
            } else {
                System.out.println("[ingest] WebHeader key not found in " + configFile);
            }

            Optional<String> matchingFooterLine = allLines.stream()
                .map(String::trim)
                .filter(line -> line.startsWith(webFooter))
                .findFirst();
            if (matchingFooterLine.isPresent()) {
                String[] partsFooter = matchingFooterLine.get().split("=", 2);
                if (partsFooter.length == 2) {
                    String extractedValue2 = partsFooter[1].trim();
                    boolean hasDriveLetter2 = extractedValue2.length() >= 2
                        && Character.isLetter(extractedValue2.charAt(0))
                        && extractedValue2.charAt(1) == ':';
                    Path candidate2;
                    if (hasDriveLetter2) {
                        candidate2 = Path.of(extractedValue2).normalize();
                    } else {
                        String relative = extractedValue2.replaceAll("^[/\\\\]+", "");
                        candidate2 = repoRoot.resolve(relative).toAbsolutePath().normalize();
                    }
                    System.out.println("[ingest] WebFooter path from config: " + candidate2);
                    if (Files.isRegularFile(candidate2)) {
                        webXmlList.add(candidate2);
                    }
                }
            } else {
                System.out.println("[ingest] WebFooter key not found in " + configFile);
            }

            return webXmlList.isEmpty() ? null : webXmlList;
        } catch (java.io.IOException e) {
            System.err.println("[ingest] failed to read " + configFile + ": " + e.getMessage());
        }
        return null;
    }

    // Get the securityapiv2.xml path from config.properties in the impact-cli working directory.
    private Path securityapiv2XmlRoots(){
        Path configFile = Path.of("config.properties").toAbsolutePath();
        if (!Files.isRegularFile(configFile)) {
            System.out.println("[ingest] securityapiv2XmlRoots: config.properties not found at " + configFile);
            return null;    
        }
        String targetVariable = "SecurityApiV2";
        try (Stream<String> lines = Files.lines(configFile)) {
            Optional<String> matchingLine = lines
                .map(String::trim)
                .filter(line -> line.startsWith(targetVariable))
                .findFirst();

            if (matchingLine.isPresent()) {
                String[] parts = matchingLine.get().split("=", 2);
                if (parts.length == 2) {
                    String extractedValue = parts[1].trim();
                    Path repoRoot = inferRepoRoot(src);
                    if (repoRoot == null) repoRoot = src.toAbsolutePath();
                    boolean hasDriveLetter = extractedValue.length() >= 2
                        && Character.isLetter(extractedValue.charAt(0))
                        && extractedValue.charAt(1) == ':';
                    Path candidate;
                    if (hasDriveLetter) {
                        candidate = Path.of(extractedValue).normalize();
                    } else {
                        String relative = extractedValue.replaceAll("^[/\\\\]+", "");
                        candidate = repoRoot.resolve(relative).toAbsolutePath().normalize();
                    }
                    System.out.println("[ingest] SecurityApiV2 path from config: " + candidate);
                    return candidate;
                }
            } else {
                System.out.println("[ingest] SecurityApiV2 key not found in " + configFile);
            }
        } catch (java.io.IOException e) {
            System.err.println("[ingest] failed to read " + configFile + ": " + e.getMessage());
        }
        return null;
    }

    // Get the ADSProductAPIs.xml path from config.properties in the impact-cli working directory.
    private Path AdspProductApiXmlFromConfig() {
        return pathFromConfig("ADSProductAPIs", "AdspProductApiXmlFromConfig");
    }

    // Get the servlet-api.xml path from config.properties in the impact-cli working directory.
    private Path servletApiXmlFromConfig() {
        return pathFromConfig("ServletAPI", "servletApiXmlFromConfig");
    }

    // Get the ADMPAPIDetails.xml path from config.properties in the impact-cli working directory.
    private Path admpApiDetailsXmlFromConfig() {
        return pathFromConfig("ADMPAPIDetails", "admpApiDetailsXmlFromConfig");
    }

    // Get the Reports.xml path from config.properties in the impact-cli working directory.
    // Parsed by ReportsXmlResolver for report_id -> class_name (concrete listener classes).
    private Path reportXmlFromConfig() {
        return pathFromConfig("ReportXML", "reportXmlFromConfig");
    }

    /**
     * Directory of table-seed XML (every file there names a table as its row element and the
     * columns as attributes). ReflectionResolver scans it to map a table to its CLASS_NAME
     * column, which is the candidate set for {@code Class.forName(row.get("CLASS_NAME"))}.
     */
    private Path tableXmlDirFromConfig() {
        return pathFromConfig("TableXmlDir", "tableXmlDirFromConfig");
    }

    // Get the struts-config.xml path from config.properties in the impact-cli working directory.
    private Path strutsConfigXmlFromConfig() {
        return pathFromConfig("StrutsConfig", "strutsConfigXmlFromConfig");
    }

    // Get the taskflow.xml path from config.properties in the impact-cli working directory.
    private Path taskflowXmlFromConfig() {
        return pathFromConfig("TaskFlow", "taskflowXmlFromConfig");
    }

    // Get the ADSPatchUpdateManager.xml path from config.properties in the impact-cli working directory.
    private Path adsPatchUpdateManagerXmlFromConfig() {
        return pathFromConfig("ADSPatchUpdateManager", "adsPatchUpdateManagerXmlFromConfig");
    }

    // All task-mapping XML files (taskflow.xml + ADSPatchUpdateManager.xml) that follow the
    // same TaskEngine_Task task_name="..." class_name="..." schema, merged for SchedulerResolver.
    private List<Path> taskflowXmlPathsFromConfig() {
        return java.util.stream.Stream.of(taskflowXmlFromConfig(), adsPatchUpdateManagerXmlFromConfig())
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toList());
    }

    // Get the DelayedTaskCategory.xml path from config.properties in the impact-cli working directory.
    // Parsed separately by SchedulerResolver for ONLY its <BackgroundTaskDetails> tags.
    private Path delayedTaskCategoryXmlFromConfig() {
        return pathFromConfig("DelayedTaskCategory", "delayedTaskCategoryXmlFromConfig");
    }

    /**
     * Shared helper: reads a single path value for {@code key} from {@code config.properties}
     * (CWD-relative) and resolves it against the inferred repo root if not absolute.
     */
    private Path pathFromConfig(String key, String callerTag) {
        Path configFile = Path.of("config.properties").toAbsolutePath();
        if (!Files.isRegularFile(configFile)) {
            System.out.println("[ingest] " + callerTag + ": config.properties not found at " + configFile);
            return null;
        }
        try (Stream<String> lines = Files.lines(configFile)) {
            Optional<String> match = lines.map(String::trim)
                .filter(line -> line.startsWith(key + "=") || line.equals(key))
                .findFirst();
            if (match.isPresent()) {
                String[] parts = match.get().split("=", 2);
                if (parts.length == 2) {
                    String value = parts[1].trim();
                    Path repoRoot = inferRepoRoot(src);
                    if (repoRoot == null) repoRoot = src.toAbsolutePath();
                    boolean hasDriveLetter = value.length() >= 2
                        && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
                    if (hasDriveLetter) return Path.of(value).normalize();
                    String relative = value.replaceAll("^[/\\\\]+", "");
                    Path candidate = repoRoot.resolve(relative).toAbsolutePath().normalize();
                    System.out.println("[ingest] " + key + " path from config: " + candidate);
                    return candidate;
                }
            } else {
                System.out.println("[ingest] " + key + " key not found in " + configFile);
            }
        } catch (java.io.IOException e) {
            System.err.println("[ingest] failed to read " + configFile + ": " + e.getMessage());
        }
        return null;
    }

    /** Default: sibling of the Java source root (i.e. {@code <src>/../html}). */
    private Path resolveHtmlRoot() {
        if (htmlRoot != null) return htmlRoot.toAbsolutePath();
        Path probe = src.toAbsolutePath();
        Path parent = probe.getParent();
        if (parent != null) {
            Path candidate = parent.resolve("html");
            if (java.nio.file.Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    /** Default: sibling of the Java source root (i.e. {@code <src>/../ember/app}). */
    private Path resolveJsRoot() {
        if (jsRoot != null) return jsRoot.toAbsolutePath();
        Path probe = src.toAbsolutePath();
        Path parent = probe.getParent();
        if (parent != null) {
            Path candidate = parent.resolve("ember").resolve("app");
            if (java.nio.file.Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    /** Default: sibling of the Java source root (i.e. {@code <src>/../c_sharp}). */
    private Path resolveCsRoot() {
        if (csRoot != null) return csRoot.toAbsolutePath();
        Path probe = src.toAbsolutePath();
        Path parent = probe.getParent();
        if (parent != null) {
            Path candidate = parent.resolve("c_sharp");
            if (java.nio.file.Files.isDirectory(candidate)) return candidate;
        }
        return null;
    }

    /**
     * For a dependency repo's Java source root, walk up looking for a sibling
     * {@code product_package/conf} directory. Same logic as {@link #resolveXmlConfRoot()}
     * but on the dep root instead of {@code --src}. Returns {@code null} if not found.
     */
    private static Path autoDetectXmlConf(Path depJavaRoot) {
        if (depJavaRoot == null) return null;
        Path probe = depJavaRoot.toAbsolutePath();
        for (int i = 0; i < 4 && probe != null; i++) {
            Path candidate = probe.resolve("product_package").resolve("conf");
            if (java.nio.file.Files.isDirectory(candidate)) return candidate;
            probe = probe.getParent();
        }
        return null;
    }

    /**
     * Auto-detect a dep repo's C# source root. Walks up from the dep's Java source root
     * looking for {@code source/c_sharp} (the convention used by ADMP / ADSF / similar).
     * The dep's Java root is typically {@code <repo>/source/java_source} so we climb one
     * directory and check for the {@code c_sharp} sibling.
     */
    private static Path autoDetectCsRoot(Path depJavaRoot) {
        if (depJavaRoot == null) return null;
        Path probe = depJavaRoot.toAbsolutePath();
        for (int i = 0; i < 4 && probe != null; i++) {
            Path candidate = probe.resolve("c_sharp");
            if (java.nio.file.Files.isDirectory(candidate)) return candidate;
            // Also try the sibling-of-source layout: <repo>/source/c_sharp
            Path source = probe.resolve("source").resolve("c_sharp");
            if (java.nio.file.Files.isDirectory(source)) return source;
            probe = probe.getParent();
        }
        return null;
    }

    /**
     * Auto-detect ALL plausible C# roots under a dep (some products keep .cs files in
     * both {@code source/c_sharp} and {@code source/c_source/fw/src/...}). Returns every
     * directory that exists, so the caller can walk all of them.
     */
    /**
     * Walk up from a Java source path until we find a directory that looks like the repo's
     * top level — has either a {@code source/} or {@code product_package/} sibling, or
     * stops climbing after 4 levels. Used to give resolvers a sensible whole-repo walk
     * scope without requiring users to specify every possible subdirectory.
     */
    private static Path inferRepoRoot(Path javaOrSrcRoot) {
        if (javaOrSrcRoot == null) return null;
        Path probe = javaOrSrcRoot.toAbsolutePath();
        for (int i = 0; i < 4 && probe != null; i++) {
            Path source = probe.resolve("source");
            Path pkg = probe.resolve("product_package");
            if (java.nio.file.Files.isDirectory(source) || java.nio.file.Files.isDirectory(pkg)) {
                return probe;
            }
            probe = probe.getParent();
        }
        // Fallback: hand back the original; the walker will still find what's there.
        return javaOrSrcRoot.toAbsolutePath();
    }

    private static java.util.List<Path> autoDetectAllCsRoots(Path depRepoOrJavaRoot) {
        if (depRepoOrJavaRoot == null) return java.util.List.of();
        java.util.List<Path> out = new java.util.ArrayList<>();
        Path probe = depRepoOrJavaRoot.toAbsolutePath();
        for (int i = 0; i < 4 && probe != null; i++) {
            for (String dir : new String[]{ "c_sharp", "c_source",
                                            "source/c_sharp", "source/c_source" }) {
                Path candidate = probe;
                for (String seg : dir.split("/")) candidate = candidate.resolve(seg);
                if (java.nio.file.Files.isDirectory(candidate)) {
                    Path canonical = candidate.toAbsolutePath().normalize();
                    if (!out.contains(canonical)) out.add(canonical);
                }
            }
            probe = probe.getParent();
        }
        return out;
    }

    /**
     * Discover ALL Java source roots under a single repo top.
     *
     * <p>Convention-driven probe — different products organize sources differently:
     * <ul>
     *   <li>SPMP: {@code source/java/}</li>
     *   <li>ADManager Plus: {@code source/java_source/} (server) + {@code web/adsm/src/} (Struts webclient)</li>
     *   <li>ADSF framework: {@code source/java_source/}</li>
     *   <li>Maven-style: {@code src/main/java/}</li>
     * </ul>
     *
     * <p>If the given path itself looks like a Java root (contains a {@code com}/{@code org}
     * package dir, OR contains {@code .java} files at any depth without being one of the
     * known parent layouts), it's used as-is — preserves back-compat with users who pass
     * {@code --src .../source/java} directly.
     *
     * <p>Otherwise we treat it as a repo top and collect every detected sub-module:
     * {@code source/java}, {@code source/java_source}, {@code src/main/java}, plus every
     * {@code web/<app>/src} directory that contains Java files.
     *
     * <p>All returned roots will be ingested under the SAME repoId — they're sub-modules
     * of one logical repository, not separate repos.
     */
    private static java.util.List<Path> discoverJavaRoots(Path repoTop) {
        if (repoTop == null) return java.util.List.of();
        Path top = repoTop.toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(top)) return java.util.List.of();
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        // Commenting this section due to duplicates in ExtractionBatch run() -> parser.listJavaFilesByRepo();
        // // Conventional fixed sub-paths
        // Path[] fixed = {
        //     top.resolve("source").resolve("java"),
        //     top.resolve("source").resolve("java_source"),
        //     top.resolve("src").resolve("main").resolve("java"),
        //     top.resolve("java"),
        // };
        // for (Path c : fixed) {
        //     if (java.nio.file.Files.isDirectory(c)) roots.add(c.toAbsolutePath().normalize());
        // }

        // // web/<app>/src — Struts/Tomcat webclient layout (e.g. ADMP's web/adsm/src)
        // Path web = top.resolve("web");
        // if (java.nio.file.Files.isDirectory(web)) {
        //     try (var stream = java.nio.file.Files.list(web)) {
        //         stream.filter(java.nio.file.Files::isDirectory).forEach(appDir -> {
        //             Path src = appDir.resolve("src");
        //             if (java.nio.file.Files.isDirectory(src)) {
        //                 roots.add(src.toAbsolutePath().normalize());
        //             }
        //         });
        //     } catch (java.io.IOException ignore) {}
        // }

        // // If the given path itself contains .java files, treat it as a Java root
        // // directly (back-compat for --src .../source/java_source). Also climb back
        // // to the repo top and add sibling web/<app>/src roots. ADMP/ADSM commonly
        // // passes --src <repo>/source/java_source while important Struts actions such
        // // as WorkFlowAction live under <repo>/web/adsm/src; without this sibling
        // // discovery, /WorkFlow.do remains only a class-level XML endpoint and the
        // // GATES_DISPATCH edges from WorkFlowAction.approveRequest are missing.
        // if (containsJavaFiles(top)) {
        //     roots.add(top);
        //     Path inferredTop = inferRepoRoot(top);
        //     if (inferredTop != null && !inferredTop.toAbsolutePath().normalize().equals(top)) {
        //         Path siblingWeb = inferredTop.resolve("web");
        //         if (java.nio.file.Files.isDirectory(siblingWeb)) {
        //             try (var stream = java.nio.file.Files.list(siblingWeb)) {
        //                 stream.filter(java.nio.file.Files::isDirectory).forEach(appDir -> {
        //                     Path src = appDir.resolve("src");
        //                     if (java.nio.file.Files.isDirectory(src)) {
        //                         roots.add(src.toAbsolutePath().normalize());
        //                     }
        //                 });
        //             } catch (java.io.IOException ignore) {}
        //         }
        //     }
        // }
        roots.add(top);
        return new java.util.ArrayList<>(roots);
    }

    /** Quick check: does this directory or its descendants contain at least one .java file? */
    private static boolean containsJavaFiles(Path dir) {
        try (var stream = java.nio.file.Files.walk(dir, 8)) {
            return stream.anyMatch(p ->
                java.nio.file.Files.isRegularFile(p) && p.toString().endsWith(".java"));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Given the root of a materialized remote repo, pick the directory inside it that
     * looks like a Java source root. Probes the conventional paths first
     * ({@code source/java}, {@code source/java_source}, {@code src/main/java}); falls
     * back to the root itself when nothing better is found (the extractor will still
     * walk recursively but may waste cycles on non-Java content).
     */
    private static Path pickJavaRoot(Path materializedRoot) {
        if (materializedRoot == null) return null;
        Path[] candidates = {
            materializedRoot.resolve("source").resolve("java"),
            materializedRoot.resolve("source").resolve("java_source"),
            materializedRoot.resolve("src").resolve("main").resolve("java"),
            materializedRoot.resolve("java")
        };
        for (Path c : candidates) {
            if (java.nio.file.Files.isDirectory(c)) return c.toAbsolutePath();
        }
        return materializedRoot.toAbsolutePath();
    }

    /** Short, audit-friendly SHA prefix for log lines. */
    private static String truncateSha(String sha) {
        if (sha == null) return "?";
        return sha.length() <= 12 ? sha : sha.substring(0, 12);
    }
}
