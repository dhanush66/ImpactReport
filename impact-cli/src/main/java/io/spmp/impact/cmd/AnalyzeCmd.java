package io.spmp.impact.cmd;

import io.spmp.impact.analyze.SliceExecutor;
import io.spmp.impact.diff.DeletedJavaSymbolScanner;
import io.spmp.impact.diff.HunkReconciler;
import io.spmp.impact.diff.HunkToSymbolResolver;
import io.spmp.impact.diff.JgitDiffSource;
import io.spmp.impact.diff.PatchFileDiffSource;
import io.spmp.impact.diff.PolyglotResolver;
import io.spmp.impact.extract.JavaProjectParser;
import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.model.DiffModels.ChangedSymbol;
import io.spmp.impact.model.DiffModels.FileChange;
import io.spmp.impact.model.ImpactReport;
import io.spmp.impact.model.ImpactReport.PolyglotChange;
import io.spmp.impact.report.HtmlReportRenderer;
import io.spmp.impact.report.JsonReportRenderer;
import org.eclipse.jgit.lib.ObjectId;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;

@Command(name = "analyze",
    description = "Compute symbol-level impact of a change. " +
                  "Accepts either a unified .patch file or a base/head git revision pair. " +
                  "P2 prints changed symbols; P3 will add Cypher slices + HTML/JSON reports.")
public class AnalyzeCmd implements Callable<Integer> {

    @Option(names = "--repo",
        description = "Path to the project root. " +
                      "For --patch mode, patch paths are resolved against this. " +
                      "For --base/--head mode, this must be a git working tree. " +
                      "Required unless --remote-repo is supplied.")
    Path repo;

    @Option(names = "--patch",
        description = "Unified .patch file (git diff format). Alternative to --base/--head; mutually exclusive.")
    Path patchFile;

    @Option(names = "--base",
        description = "Base revision (branch / tag / sha). Required unless --patch is given.")
    String base;

    @Option(names = "--head",
        description = "Head revision (default: HEAD). Used only with --base.")
    String head = "HEAD";

    @Option(names = "--src",
        description = "Java source root for SymbolSolver (default: <repo>/source/java if present, else <repo>).")
    Path src;

    @Option(names = "--output", description = "Output format: stdout|html|json|both (default: stdout in P2)")
    String output = "stdout";

    @Option(names = "--out", description = "Output file path (P3)")
    Path out;

    @Option(names = "--depth", description = "Max BFS depth for slice queries (P3, default: 6)")
    int depth = 6;

    @Option(names = "--fail-on", description = "Exit non-zero if risk reaches threshold: LOW|MEDIUM|HIGH (P3)")
    String failOn;

    @Option(names = "--no-gen", description = "Skip generated-test-case output (just the impact slices).")
    boolean noGen;

    @Option(names = "--gen-md", description = "Path for the generated-testcases.md sibling file. Default: alongside --out.")
    Path genMdPath;

    @Option(names = "--show-coverage", description = "Render the existing-test-library coverage panel in the HTML report (off by default).")
    boolean showCoverage;

    @Mixin
    Neo4jOptions neo;

    @Mixin
    RemoteRepoOptions remote;

    @Override
    public Integer call() throws Exception {
        // ── Remote-repo branch: materialize from Zoho API into the local cache, then
        //    run the existing JgitDiffSource path against the materialized .git/ tree.
        //    --remote-base / --remote-head map directly onto --base / --head once the
        //    repo is on disk. All analyze-side logic downstream is unchanged.
        if (remote.isRemoteEnabled()) {
            if (repo != null) {
                System.err.println("error: --repo and --remote-repo are mutually exclusive");
                return 11;
            }
            try {
                remote.validateForRemote();
                io.spmp.impact.remote.RemoteRepoMaterializer mat =
                    new io.spmp.impact.remote.RemoteRepoMaterializer(remote);
                String headRefForMaterialize = remote.remoteHead != null ? remote.remoteHead : remote.remoteRef;
                io.spmp.impact.remote.RemoteRepoMaterializer.MaterializedRepo m =
                    mat.materialize(remote.remoteRepo, headRefForMaterialize);
                repo = m.sourceRoot();
                if (remote.remoteBase != null && base == null) base = remote.remoteBase;
                if (remote.remoteHead != null && "HEAD".equals(head)) head = remote.remoteHead;
                System.out.println("[analyze] remote repo materialized: " + repo
                    + " @" + (m.resolvedSha() == null ? "?" : m.resolvedSha().substring(0, Math.min(12, m.resolvedSha().length()))));
            } catch (io.spmp.impact.remote.RemoteRepoException re) {
                System.err.println(re.getMessage());
                return re.exitCode();
            }
        }

        if (repo == null) {
            System.err.println("error: either --repo or --remote-repo must be supplied");
            return 11;
        }
        if ((patchFile == null) == (base == null)) {
            System.err.println("[analyze] Provide exactly one of --patch <file> OR --base <rev> (or --remote-base when using --remote-repo).");
            return 2;
        }

        Path sourceRoot = resolveSourceRoot();
        System.out.println("[analyze] repo=" + repo.toAbsolutePath());
        if (patchFile != null) {
            System.out.println("[analyze] patch=" + patchFile.toAbsolutePath());
        } else {
            System.out.println("[analyze] base=" + base + "  head=" + head);
        }
        System.out.println("[analyze] source root for SymbolSolver=" + sourceRoot);

        return patchFile != null ? runPatch(sourceRoot) : runGit(sourceRoot);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Patch-file mode: parse .patch, read HEAD file content from working tree
    // ──────────────────────────────────────────────────────────────────────
    private int runPatch(Path sourceRoot) throws Exception {
        List<FileChange> all = PatchFileDiffSource.readAll(patchFile);
        List<FileChange> javaChanges = new ArrayList<>();
        List<FileChange> polyglotChanges = new ArrayList<>();
        for (FileChange fc : all) {
            String p = fc.newPath() != null ? fc.newPath() : fc.oldPath();
            if (p != null && p.endsWith(".java")) javaChanges.add(fc);
            else polyglotChanges.add(fc);
        }
        // Scan deleted Java symbols from `-` lines in the raw patch (the graph reflects
        // post-patch state, so removed methods/classes don't exist as nodes).
        List<ChangedSymbol> deletedSymbols = DeletedJavaSymbolScanner.scan(patchFile);

        System.out.println("[analyze] files in patch: " + all.size()
            + "  (java=" + javaChanges.size()
            + ", polyglot=" + polyglotChanges.size()
            + (deletedSymbols.isEmpty() ? "" : ", deleted-java-symbols=" + deletedSymbols.size()) + ")");
        if (javaChanges.isEmpty() && polyglotChanges.isEmpty() && deletedSymbols.isEmpty()) {
            System.out.println("[analyze] (patch is empty — nothing to do)");
            return 0;
        }

        Function<String, byte[]> reader = path -> {
            Path disk = repo.resolve(path);
            if (!Files.isRegularFile(disk)) return null;
            try { return Files.readAllBytes(disk); }
            catch (IOException e) { return null; }
        };
        return processChanges(javaChanges, polyglotChanges, deletedSymbols, reader, sourceRoot);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Git mode: jgit diff, read HEAD blobs from the tree
    // ──────────────────────────────────────────────────────────────────────
    private int runGit(Path sourceRoot) throws Exception {
        try (JgitDiffSource diff = new JgitDiffSource(repo)) {
            // Git mode stays Java-only for now (polyglot resolution still works at the
            // file level if extended in v3; the slice path is unchanged).
            List<FileChange> changes = diff.diffJava(base, head);
            System.out.println("[analyze] Java files in diff: " + changes.size());
            if (changes.isEmpty()) {
                System.out.println("[analyze] (no Java changes — nothing to do)");
                return 0;
            }
            ObjectId headTree = diff.resolveTree(head);
            Function<String, byte[]> reader = path -> {
                try { return diff.readBlob(headTree, path); }
                catch (IOException e) { return null; }
            };
            return processChanges(changes, List.of(), List.of(), reader, sourceRoot);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Shared processing: resolve hunks to symbols, then run Cypher slices + render
    // ──────────────────────────────────────────────────────────────────────
    private int processChanges(List<FileChange> changes,
                               List<FileChange> polyglotChanges,
                               List<ChangedSymbol> preScannedDeletedSymbols,
                               Function<String, byte[]> reader,
                               Path sourceRoot) throws IOException {
        JavaProjectParser parser = new JavaProjectParser(sourceRoot);
        HunkToSymbolResolver resolver = new HunkToSymbolResolver(parser);

        List<ChangedSymbol> all = new ArrayList<>();
        int skippedNoBlob = 0, skippedNoSymbol = 0;
        for (FileChange fc : changes) {
            System.out.println();
            System.out.println("  " + fc.changeType() + "  " + fc.newPath()
                + (fc.hunkRanges().isEmpty() ? "" : "  (" + fc.hunkRanges().size() + " hunks)"));
            if (fc.hunkRanges().isEmpty()) continue;

            byte[] bytes = reader.apply(fc.newPath());
            if (bytes == null) {
                System.out.println("    [skip] cannot read HEAD content for " + fc.newPath());
                skippedNoBlob++;
                continue;
            }
            // Reconcile patch hunk line numbers against actual workspace source.
            // The workspace file may be newer than the patch's B-side, causing line shifts.
            List<int[]> reconciledHunks = patchFile != null
                ? HunkReconciler.reconcile(patchFile, fc.newPath(), fc.hunkRanges(), bytes)
                : fc.hunkRanges();
            List<ChangedSymbol> symbols = resolver.resolve(fc.newPath(), bytes, reconciledHunks);
            if (symbols.isEmpty()) {
                System.out.println("    [skip] no enclosing AST symbol matched any hunk");
                skippedNoSymbol++;
                continue;
            }
            for (ChangedSymbol cs : symbols) {
                System.out.printf("    L%d-%d  %-12s %-9s  %s%n",
                    cs.startLine(), cs.endLine(),
                    cs.kind(), cs.nature(),
                    cs.fqn());
                all.add(cs);
            }
        }

        // Merge in DELETED Java symbols scanned from raw patch `-` lines. These are nodes
        // that no longer exist in the post-patch graph; the slice executor will treat them
        // gracefully (no entry-point reach), and they'll surface in the report as a
        // dedicated "Deleted" section so QA can verify callers are also gone.
        int deletedCount = 0;
        if (preScannedDeletedSymbols != null) {
            for (ChangedSymbol cs : preScannedDeletedSymbols) {
                all.add(cs);
                deletedCount++;
                System.out.printf("    [DEL]   %-12s %-9s  %s%n",
                    cs.kind(), cs.nature(), cs.fqn());
            }
        }

        // Deduplicate by (filePath, fqn, kind): two separate patch hunks landing inside the same
        // method each resolve to their own ChangedSymbol for that method (HunkToSymbolResolver
        // processes hunks independently), but its own LinkedHashSet dedup keys on full record
        // equality — which also compares hunkStartLine/hunkEndLine — so both survive as distinct
        // entries and show up as duplicate rows in the "Symbol Details" report table. Merge them
        // here, widening the hunk range to cover every contributing hunk.
        int beforeDedup = all.size();
        all = io.spmp.impact.model.DiffModels.mergeDuplicateSymbols(all);
        int dedupedCount = beforeDedup - all.size();

        System.out.println();
        System.out.println("[analyze] changed symbols: " + all.size()
            + (skippedNoBlob > 0 ? "  (skipped " + skippedNoBlob + " — no HEAD content)" : "")
            + (skippedNoSymbol > 0 ? "  (skipped " + skippedNoSymbol + " — no enclosing symbol)" : "")
            + (deletedCount > 0 ? "  (incl. " + deletedCount + " deleted)" : "")
            + (dedupedCount > 0 ? "  (merged " + dedupedCount + " duplicate hunk(s) into existing symbols)" : ""));

        if (all.isEmpty() && (polyglotChanges == null || polyglotChanges.isEmpty())) return 0;

        // ── Slices against Neo4j ──
        System.out.println("[analyze] running slices against " + neo.resolveUri() + " (depth=" + depth + ") ...");
        ImpactReport report;
        try (Neo4jWriter writer = new Neo4jWriter(neo.resolveUri(), neo.resolveUser(), neo.resolvePass())) {
            SliceExecutor exec = new SliceExecutor(writer, depth);
            String diffSource = patchFile != null
                ? "patch:" + patchFile.toAbsolutePath()
                : "git:" + base + ".." + head;
            // Resolve polyglot file changes into graph-aware PolyglotChange records.
            List<PolyglotChange> polyResolved = new ArrayList<>();
            if (polyglotChanges != null && !polyglotChanges.isEmpty()) {
                PolyglotResolver polyResolver = new PolyglotResolver(writer, repo);
                for (FileChange fc : polyglotChanges) {
                    try {
                        polyResolved.add(polyResolver.resolve(fc));
                    } catch (Throwable t) {
                        System.err.println("[analyze] polyglot resolve failed for "
                            + fc.newPath() + ": " + t.getMessage());
                    }
                }
                System.out.println("[analyze] polyglot changes resolved: " + polyResolved.size());
            }
            report = exec.run(diffSource, repo.toAbsolutePath().toString(), all, polyResolved);
        }
        System.out.printf("[analyze] overall risk: %s   (HIGH=%d MEDIUM=%d LOW=%d)%n",
            report.overallRisk(),
            report.riskCounts().getOrDefault("HIGH", 0L),
            report.riskCounts().getOrDefault("MEDIUM", 0L),
            report.riskCounts().getOrDefault("LOW", 0L));
       // System.out.printf("[analyze] entry points reached: %d   forward reach total: %d%n",
        //        report.totalEntryPointsApi(),
        //    report.totalForwardReach());

        // AFF: print the per-category counts so the operator sees them in the console too
        int apiN  = report.apisAffected()         == null ? 0 : report.apisAffected().size();
        int schN  = report.schedulesAffected()    == null ? 0 : report.schedulesAffected().size();
        int dbN   = report.dbTablesAffected()     == null ? 0 : report.dbTablesAffected().size();
        if (apiN + schN  + dbN > 0) {
            System.out.printf("[analyze] affected: APIs=%d  DB tables=%d  Schedules=%d %n",
                apiN, dbN, schN);
        }

        // ── Render ──
        Path outPath = out != null
            ? out
            : Path.of("impact-report." + (output.equals("json") ? "json" : "html"));

        switch (output) {
            case "stdout" -> {
                // nothing extra — symbols already printed
            }
            case "json" -> {
                JsonReportRenderer.renderToFile(report, outPath);
                System.out.println("[analyze] wrote JSON: " + outPath.toAbsolutePath());
            }
            case "html" -> {
                HtmlReportRenderer.renderToFile(report, outPath, showCoverage);
                System.out.println("[analyze] wrote HTML: " + outPath.toAbsolutePath());
            }
            case "both" -> {
                Path jsonOut = outPath.resolveSibling(stripExt(outPath) + ".json");
                Path htmlOut = outPath.resolveSibling(stripExt(outPath) + ".html");
                JsonReportRenderer.renderToFile(report, jsonOut);
                HtmlReportRenderer.renderToFile(report, htmlOut, showCoverage);
                System.out.println("[analyze] wrote JSON: " + jsonOut.toAbsolutePath());
                System.out.println("[analyze] wrote HTML: " + htmlOut.toAbsolutePath());
            }
            default -> System.err.println("[analyze] unknown --output value: " + output + " (use stdout|json|html|both)");
        }

 

        // ── CI fail-on gate ──
        if (failOn != null && exceedsThreshold(report.overallRisk(), failOn)) {
            System.err.println("[analyze] FAIL: overall risk " + report.overallRisk()
                + " >= threshold " + failOn);
            return 2;
        }
        return 0;
    }

    private static String stripExt(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(0, dot);
    }

    private static boolean exceedsThreshold(String actual, String threshold) {
        int a = rank(actual), t = rank(threshold);
        return a >= t;
    }

    private static int rank(String r) {
        return switch (r == null ? "" : r.toUpperCase()) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    private Path resolveSourceRoot() {
        if (src != null) return src.toAbsolutePath();
        // Check common Java source root conventions in priority order.
        // source/java_source — ADSM/ADMP product layout
        // source/java        — generic ManageEngine product layout
        // src/main/java      — Maven standard layout
        for (String cand : new String[]{"source/java_source", "source/java", "src/main/java"}) {
            Path p = repo.resolve(cand);
            if (Files.isDirectory(p)) return p.toAbsolutePath();
        }
        return repo.toAbsolutePath();
    }
}
