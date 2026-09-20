package io.spmp.impact.extract;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.TypeSolver;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;

import io.spmp.impact.extract.resolver.ResolverUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.Collection;

/**
 * Sets up JavaParser + SymbolSolver against the source root.
 * Best-effort: SymbolSolver may fail to resolve types from JARs we don't have on classpath —
 * the extractor downgrades unresolved calls to {kind: 'unresolved'} rather than dropping them.
 */
public class JavaProjectParser {

    /** Primary source root (kept for backward compatibility of {@code sourceRoot()}). */
    private final Path sourceRoot;
    /**
     * Ordered map of repoId → absolute source roots. A single repoId may have MULTIPLE
     * roots (a typical case: ADManager Plus has both {@code source/java_source/} and
     * {@code web/adsm/src/} under one logical repo). Primary repo first; dependency repos
     * follow. Used by {@link #listJavaFilesByRepo()} to attribute every scanned file
     * to its owning repo. The list per repo is iterated in insertion order.
     */
    private final java.util.LinkedHashMap<String, java.util.List<Path>> rootsByRepoId;
    /**
     * Per-repo parsers (isolated mode) — each repo gets a {@link JavaParser} whose
     * SymbolSolver only knows that repo's source. Avoids the combinatorial-lookup cost
     * of a single CombinedTypeSolver scanning every root for every type reference.
     * Empty when {@link #useUnifiedSymbols} is true.
     */
    private final java.util.Map<String, JavaParser> parsersByRepoId;
    /**
     * Shared parser — used in unified-symbols mode (every root in one SymbolSolver),
     * and as a fallback parser for {@link #parseFile(Path)} / {@link #parseSource(byte[])}
     * call sites that don't know the source repo (e.g. analyze-side hunk resolution).
     */
    private JavaParser sharedParser;
    /** True if a single combined SymbolSolver covers all roots; false for per-repo isolation. */
    private final boolean useUnifiedSymbols;
    /**
     * Repo IDs for which we deliberately skip SymbolSolver entirely (parse-only mode).
     * The AST is still fully parsed (Range/lines/symbols all intact) — only per-call
     * type resolution is degraded. Used for very large dep repos (e.g. ADSM's webclient
     * at 1,114 files where SymbolSolver gets stuck for 10+ minutes on single files
     * containing deep generics + huge if-else towers).
     */
    private final java.util.Set<String> liteRepoIds = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Mark these repos as "lite" — parsed without SymbolSolver. Must be called before parseFileThreadSafe. */
    public void setLiteRepos(java.util.Collection<String> ids) {
        liteRepoIds.clear();
        if (ids != null) liteRepoIds.addAll(ids);
    }

    public java.util.Set<String> liteRepos() { return java.util.Set.copyOf(liteRepoIds); }

    public JavaProjectParser(Path sourceRoot) {
        this(wrapSingletons(singleRootMap(sourceRoot, "primary")), /* useUnifiedSymbols = */ true);
    }

    /**
     * Static factory — back-compat single-path entry point. Each repoId maps to exactly
     * one path. Used by call sites that haven't migrated to the multi-path constructor.
     * Generic-erasure prevents this from being an overload, so it's a factory instead.
     */
    public static JavaProjectParser fromSingleRoots(java.util.LinkedHashMap<String, Path> rootsByRepoId) {
        return new JavaProjectParser(wrapSingletons(rootsByRepoId), /* useUnifiedSymbols = */ false);
    }

    public static JavaProjectParser fromSingleRoots(java.util.LinkedHashMap<String, Path> rootsByRepoId,
                                                    boolean useUnifiedSymbols) {
        return new JavaProjectParser(wrapSingletons(rootsByRepoId), useUnifiedSymbols);
    }

    /** Wrap each Path value as a singleton list — bridges the old single-path API to the new multi-path one. */
    private static java.util.LinkedHashMap<String, java.util.List<Path>> wrapSingletons(
            java.util.LinkedHashMap<String, Path> simple) {
        if (simple == null) return null;
        java.util.LinkedHashMap<String, java.util.List<Path>> out = new java.util.LinkedHashMap<>();
        for (var e : simple.entrySet()) {
            out.put(e.getKey(), new java.util.ArrayList<>(java.util.List.of(e.getValue())));
        }
        return out;
    }

    /**
     * Multi-repo constructor with explicit symbol-solver mode selection.
     *
     * <p><b>Isolated mode</b> (default for multi-repo): each repo gets its own
     * {@code JavaParser}. SymbolSolver lookups inside one repo's files only scan that
     * repo's source tree. Cross-repo type references fall back to AST-level resolution
     * (import-driven FQN reconstruction in {@code CoreExtractor.resolveTypeName}).
     * This is the fast path — multi-repo ingest scales linearly with file count rather
     * than quadratically with (file count × root count).
     *
     * <p><b>Unified mode</b> (single-root default; multi-repo opt-in via {@code --unified-symbols}):
     * one CombinedTypeSolver covers every root. Cross-repo types resolve through SymbolSolver
     * for full accuracy. Slower for multi-repo with large dependency trees because every
     * unresolved type triggers a scan across all roots.
     *
     * @param rootsByRepoId      ordered map: primary repo first, then deps. Non-empty.
     * @param useUnifiedSymbols  {@code true} to share one SymbolSolver across roots;
     *                           {@code false} to isolate per-repo.
     */
    /**
     * Primary multi-path constructor. Each repoId can map to MULTIPLE Java source
     * roots (e.g. ADMP's {@code source/java_source/} + {@code web/adsm/src/}).
     *
     * @param rootsByRepoIdMulti ordered map: primary repo first, then deps. Each value
     *                            is a non-empty list of Java source roots all belonging
     *                            to that repo. Files under any of these roots will be
     *                            attributed to the corresponding repoId.
     * @param useUnifiedSymbols  see other constructors.
     */
    public JavaProjectParser(java.util.LinkedHashMap<String, java.util.List<Path>> rootsByRepoIdMulti,
                             boolean useUnifiedSymbols) {
        if (rootsByRepoIdMulti == null || rootsByRepoIdMulti.isEmpty()) {
            throw new IllegalArgumentException("rootsByRepoId must contain at least one entry");
        }
        // Normalise every path under every repoId
        java.util.LinkedHashMap<String, java.util.List<Path>> normalised = new java.util.LinkedHashMap<>();
        for (var e : rootsByRepoIdMulti.entrySet()) {
            java.util.List<Path> paths = e.getValue();
            if (paths == null || paths.isEmpty()) {
                throw new IllegalArgumentException("repo '" + e.getKey() + "' has no source roots");
            }
            java.util.List<Path> norm = new java.util.ArrayList<>(paths.size());
            for (Path p : paths) norm.add(p.toAbsolutePath().normalize());
            normalised.put(e.getKey(), norm);
        }
        this.rootsByRepoId = normalised;
        this.sourceRoot = normalised.values().iterator().next().get(0);   // primary repo's first root
        this.useUnifiedSymbols = useUnifiedSymbols;

        if (useUnifiedSymbols) {
            // Flatten every path from every repo into one SymbolSolver
            java.util.List<Path> flat = new java.util.ArrayList<>();
            for (var paths : normalised.values()) flat.addAll(paths);
            this.sharedParser = buildParser(flat, this.sourceRoot);
            this.parsersByRepoId = java.util.Map.of();
        } else {
            // Isolated: one parser per repo, whose SymbolSolver covers ALL paths of that repo.
            java.util.Map<String, JavaParser> map = new java.util.HashMap<>();
            for (var e : normalised.entrySet()) {
                map.put(e.getKey(), buildParser(e.getValue(), e.getValue().get(0)));
            }
            this.parsersByRepoId = java.util.Collections.unmodifiableMap(map);
            // Shared parser falls back to the primary repo's parser for analyze-side hunk resolution.
            this.sharedParser = map.get(normalised.keySet().iterator().next());
        }
    }

    /**
     * Bounded CU cache size per JavaParserTypeSolver. Default in JavaParser is 100,000 —
     * enough to retain every CompilationUnit it ever parses, which for a 4,000+-file
     * multi-repo ingest holds hundreds of MB of AST. 1024 is plenty for active working
     * set (deepest active recursion during a single type lookup is ~50 nodes); evicted
     * entries are re-parsed if needed (rare).
     */
    private static final long TYPE_SOLVER_CACHE_SIZE = 1024L;

    /**
     * Files above this size get parsed WITHOUT a SymbolSolver. The AST (so Range, class
     * names, method declarations, call expressions) is fully preserved — we just skip
     * the per-call type-resolution step. SymbolSolver's cost is roughly quadratic in
     * the number of local variables inside a method body, so a few monster files
     * (e.g. ADSM webclient's {@code ReportResultUtil.java} at 369 KB / 7,000+ lines /
     * 1,000+ if-statements) dominate ingest time and heap. The graph still gets every
     * :Class / :Method / :CALLS node from these files; the only loss is precise param
     * types (they show as {@code ?}), which the fuzzy-signature matcher in
     * {@code CoreExtractor.emitOverrides} already tolerates.
     */
    private static final long LARGE_FILE_THRESHOLD_BYTES = 150_000L;

    /**
     * Build a JavaParser WITHOUT a SymbolSolver — same AST quality (Range, types, calls
     * preserved) but every {@code resolve()} call throws immediately. CoreExtractor's
     * existing fallback chain handles this gracefully:
     *  - {@code resolveTypeName} → falls through to {@code resolveViaImports} (import-based FQN)
     *  - {@code recordCall} → emits CALL edge with {@code kind='unresolved'}, scope-based FQN
     *  - {@code resolvedParamTypesForDecl} → falls back to source-text param types
     *
     * <p>Used for files / repos where full SymbolSolver resolution is pathologically slow
     * (large generated code, deep generic chains, big if-else towers). The file's
     * :Class / :Method / :Field / :CALLS / :EXTENDS nodes + edges are all still emitted —
     * only param-type precision on a fraction of CALL edges is degraded, which the fuzzy
     * signature matcher in {@code CoreExtractor.signaturesCompatible} already tolerates.
     */
    private static JavaParser buildParserNoSymbols() {
        ParserConfiguration cfg = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
            .setAttributeComments(false);
        return new JavaParser(cfg);
    }
    public static volatile CombinedTypeSolver sharedTypeSolver;
    /** Build a JavaParser whose SymbolSolver covers {@code roots}, tuned for low memory. */
    private static JavaParser buildParser(Collection<Path> roots, Path primaryForSiblingHeuristic) {
        // Parser config used for type-solver-internal parses (where memory dominates).
        // - setAttributeComments(false) — drop Javadoc/comment AST nodes (~10% AST memory).
        // We deliberately KEEP tokens (default behaviour). setStoreTokens(false) drops
        // the per-Node Range, which breaks hunk-to-symbol mapping in AnalyzeCmd and makes
        // every ingested :Class/:Method record start_line=0. The real memory wins are
        // the bounded JavaParserTypeSolver cache + streaming flush in CoreExtractor.
        CombinedTypeSolver typeSolver = sharedTypeSolver;
        if (typeSolver == null) {
            synchronized (JavaProjectParser.class) {
                typeSolver = sharedTypeSolver;
                if (typeSolver == null) {
                    typeSolver = new CombinedTypeSolver();
                    ParserConfiguration slim = new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
                    .setAttributeComments(false);

                    typeSolver.add(new ReflectionTypeSolver());
                    for (Path root : roots) {
                        try {
                            // Bounded-cache constructor — caps the per-solver CompilationUnit cache.
                            typeSolver.add(new JavaParserTypeSolver(root, slim, TYPE_SOLVER_CACHE_SIZE));
                        } catch (Exception ignore) {
                            // If a root has no Java directly, JavaParserTypeSolver may still work for
                            // nested dirs. The extractor handles unresolved nodes gracefully.
                        }
                    }
                    // Legacy heuristic: probe for source/java alongside the primary root.
                    if (primaryForSiblingHeuristic != null) {
                        Path parent = primaryForSiblingHeuristic.getParent();
                        if (parent != null) {
                            try {
                                Path siblingJava = parent.resolve("java");
                                if (Files.isDirectory(siblingJava) && !siblingJava.equals(primaryForSiblingHeuristic)) {
                                    typeSolver.add(new JavaParserTypeSolver(siblingJava, slim, TYPE_SOLVER_CACHE_SIZE));
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    //For jar files
                    for (Path jar : discoverJarDependencies(roots)) {
                        try { typeSolver.add(new JarTypeSolver(jar)); }
                        catch (Exception e) {
                            System.err.println("[jar-solver-skip] " + jar + " : " + e.getMessage());
                        }
                    }
                    sharedTypeSolver = typeSolver;
                }
            }
            
        }
        
        // Main parser config — same as slim above + the combined SymbolSolver.
        // Tokens stay enabled so Range/line info is populated on every Node.
        ParserConfiguration cfg = new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_17)
            .setAttributeComments(false)
            .setSymbolResolver(new JavaSymbolSolver(sharedTypeSolver));
        return new JavaParser(cfg);
    }

    private static List<Path> discoverJarDependencies(Collection<Path> sourceRoots) {
        LinkedHashSet<Path> jars = new LinkedHashSet<>();
        for (Path sourceRoot : sourceRoots == null ? List.<Path>of() : sourceRoots) {
            if (sourceRoot == null) continue;
            Path probe = sourceRoot.toAbsolutePath().normalize();
            while (probe != null) {
                addJarsUnder(jars, probe.resolve("lib"));
                probe = probe.getParent();
            }
        }
        return new ArrayList<>(jars);
    }

    private static void addJarsUnder(Set<Path> jars, Path libDir) {
        if (libDir == null || !Files.isDirectory(libDir)) return;
        try (var stream = Files.walk(libDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".jar"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .forEach(jars::add);
        } catch (IOException ignored) {}
    }

    private static java.util.LinkedHashMap<String, Path> singleRootMap(Path root, String key) {
        java.util.LinkedHashMap<String, Path> m = new java.util.LinkedHashMap<>();
        m.put(key, root);
        return m;
    }

    public Path sourceRoot() { return sourceRoot; }

    /** Ordered repoId → list-of-source-roots map, primary first. A repoId may have multiple roots. */
    public java.util.LinkedHashMap<String, java.util.List<Path>> rootsByRepoId() { return rootsByRepoId; }

    /** Parse a Java source given as raw bytes (e.g. a git blob). Uses the shared/primary parser. */
    public Optional<CompilationUnit> parseSource(byte[] bytes) {
        try {
            ParseResult<CompilationUnit> r = sharedParser.parse(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
            return r.getResult();
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    /**
     * Walk only — return all Java file paths under {@code sourceRoot} (primary root only)
     * without parsing. Kept for backward compatibility; multi-repo callers should prefer
     * {@link #listJavaFilesByRepo()}.
     */
    public List<Path> listJavaFiles() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            stream
                .filter(p -> p.toString().endsWith(".java"))
                .filter(Files::isRegularFile)
                .sorted()
                .forEach(out::add);
        }
        return out;
    }

    /**
     * Walk every registered repo root and return {@link RepoFile} records tagged with the
     * owning repoId. Files are returned in repo declaration order (primary first), and
     * sorted within each repo. Useful for multi-repo ingest where every file must carry
     * its owning {@code repo_id} into the {@code :File} node.
     */
    public List<RepoFile> listJavaFilesByRepo() throws IOException {
        List<RepoFile> out = new ArrayList<>();
        for (var entry : rootsByRepoId.entrySet()) {
            String repoId = entry.getKey();
            for (Path root : entry.getValue()) {
                if (!Files.isDirectory(root)) continue;
                try (Stream<Path> stream = Files.walk(root)) {
                    stream
                        .filter(p -> p.toString().endsWith(".java"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .forEach(p -> out.add(new RepoFile(p, repoId)));
                }
            }
        }
        return out;
    }

    /** A Java file path together with the repo id it belongs to. */
    public record RepoFile(Path path, String repoId) {}

    /** Parse a single file using the shared/primary parser. */
    public Optional<CompilationUnit> parseFile(Path p) {
        return doParseFile(sharedParser, p);
    }

    /**
     * Parse a single file using the parser bound to {@code repoId}. Falls back to the
     * shared parser if no per-repo parser is configured (e.g. unified-symbols mode or
     * single-root setups). This is the fast path for multi-repo ingest: every file is
     * parsed by a SymbolSolver scoped to its own repo only.
     */
    public Optional<CompilationUnit> parseFile(Path p, String repoId) {
        JavaParser parser = (parsersByRepoId.isEmpty() || repoId == null)
            ? sharedParser
            : parsersByRepoId.getOrDefault(repoId, sharedParser);
        return doParseFile(parser, p);
    }

    /**
     * Thread-local pool of per-(thread, repoId) JavaParser instances. JavaParser +
     * SymbolSolver is NOT safe for concurrent use of a shared instance — the type-solver
     * cache writes during parse / resolve calls. Each worker thread lazily builds its own
     * parser stack the first time it asks for a given repoId. The per-thread typeSolver
     * caches are bounded ({@link #TYPE_SOLVER_CACHE_SIZE}), so memory stays proportional
     * to (worker count) × (repo count) × cache cap — bounded and predictable.
     */
    private final ThreadLocal<java.util.Map<String, JavaParser>> threadLocalParsers =
        ThreadLocal.withInitial(java.util.HashMap::new);

    /**
     * Parse a single file using a parser private to the calling thread. The first call
     * from each (thread, repoId) pair builds a fresh JavaParser whose SymbolSolver only
     * sees that repo's root. Subsequent calls on the same thread reuse it. This is the
     * thread-safe variant of {@link #parseFile(Path, String)} used by parallelized ingest.
     */
    public Optional<CompilationUnit> parseFileThreadSafe(Path p, String repoId) {
        String key = repoId == null ? "__shared__" : repoId;
        JavaParser parser = threadLocalParsers.get().computeIfAbsent(key, k -> {
            // For the shared/primary key, use the primary root; otherwise every root
            // registered under this repoId so SymbolSolver sees the whole module tree.
            java.util.List<Path> roots;
            Path primaryForHeuristic;
            if (repoId == null || !rootsByRepoId.containsKey(repoId)) {
                roots = java.util.List.of(sourceRoot);
                primaryForHeuristic = sourceRoot;
            } else {
                roots = rootsByRepoId.get(repoId);
                primaryForHeuristic = roots.get(0);
            }
            boolean lite = repoId != null && liteRepoIds.contains(repoId);
            long buildStart = System.nanoTime();
            JavaParser built = lite ? buildParserNoSymbols() : buildParser(roots, primaryForHeuristic);
            long buildMs = (System.nanoTime() - buildStart) / 1_000_000L;
            System.out.printf("[JavaProjectParser] thread '%s' built %s parser for repo='%s' (%d root(s), %dms)%n",
                Thread.currentThread().getName(),
                lite ? "LITE (no-symbol)" : "symbol-solving",
                k, roots.size(), buildMs);
            return built;
        });
        return doParseFile(parser, p);
    }

    /** Release this thread's parsers. Call from each worker thread before pool shutdown. */
    public void releaseThreadLocalParsers() {
        threadLocalParsers.remove();
    }

    /**
     * Per-thread NO-SYMBOL parser (one instance per worker thread). Reused as the
     * "lite fallback" target when a file's full-SymbolSolver parse times out — we
     * give up on precise per-call type resolution for THIS file specifically, but
     * the file is still fully ingested (every :Class, :Method, :CALLS via AST-import
     * fallback, etc.). This is the surgical "don't bypass anything" pattern: 99% of
     * files keep full precision; only the pathological ones degrade.
     */
    private final ThreadLocal<JavaParser> threadLocalLiteParser =
        ThreadLocal.withInitial(this::createNewParserInstance);

    private JavaParser createNewParserInstance() {
        return JavaProjectParser.buildParser(List.of(sourceRoot), null);
    }
    /** Parse a file using THIS thread's lite (no-SymbolSolver) parser. Always available. */
    public Optional<CompilationUnit> parseFileNoSymbolsThreadSafe(Path p) {
        return doParseFile(threadLocalLiteParser.get(), p);
    }

    /**
     * Convenience: parse a file without any SymbolSolver, using a shared parser. Thread-safe
     * because each call gets its own ParseResult from JavaParser's internal pool. The
     * three-pass extractor (Pass 1a, Pass 1b in {@link CoreExtractor}) uses this — it
     * never needs SymbolSolver because resolution happens via {@link GlobalIndex} lookup
     * in Pass 2. Much faster than {@link #parseFileThreadSafe} which builds a full
     * SymbolSolver type-solver stack per thread.
     */
    public Optional<CompilationUnit> parseFileNoSymbols(Path p) {
        // Reuse the thread-local lite parser — already cached per worker.
        return parseFileNoSymbolsThreadSafe(p);
    }

    private static Optional<CompilationUnit> doParseFile(JavaParser parser, Path p) {
        try {
            ParseResult<CompilationUnit> r = parser.parse(p);
            if (r.getResult().isPresent()) return r.getResult();
            System.err.println("[parse-skip] " + p + " : " + r.getProblems());
        } catch (IOException ex) {
            System.err.println("[parse-error] " + p + " : " + ex.getMessage());
        }
        return Optional.empty();
    }

    /** True when this parser uses one combined SymbolSolver across all roots. */
    public boolean useUnifiedSymbols() { return useUnifiedSymbols; }

    /** Walks {@code sourceRoot} and returns all parseable Java compilation units with their absolute paths. */
    public List<ParsedFile> parseAll() throws IOException {
        List<ParsedFile> out = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            stream
                .filter(p -> p.toString().endsWith(".java"))
                .filter(Files::isRegularFile)
                .sorted()
                .forEach(p -> {
                    try {
                        ParseResult<CompilationUnit> r = sharedParser.parse(p);
                        if (r.getResult().isPresent()) {
                            out.add(new ParsedFile(p, r.getResult().get()));
                        } else {
                            System.err.println("[parse-skip] " + p + " : " + r.getProblems());
                        }
                    } catch (IOException ex) {
                        System.err.println("[parse-error] " + p + " : " + ex.getMessage());
                    }
                });
        }
        return out;
    }

    public record ParsedFile(Path path, CompilationUnit cu) {}
}
