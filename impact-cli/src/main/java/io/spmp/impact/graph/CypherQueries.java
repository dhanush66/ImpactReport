package io.spmp.impact.graph;

/**
 * Parameterized Cypher slice queries.
 *
 * <p>Variable-length path bounds (e.g. {@code *1..6}) cannot be parameterized in Cypher,
 * so we pass them in via {@link String#format} at call-site. Depth is clamped to [1..10]
 * by the caller. Everything else is a real query parameter.
 *
 * <p>Query result rows are intentionally returned in a single shape per query so the
 * SliceExecutor can deserialize without conditionals.
 */
public final class CypherQueries {
    private CypherQueries() {}

    /**
     * Forward blast radius — for each changed method, every method it can transitively call
     * within {@code depth} hops. Includes :CALLS edges; future-proofed to include
     * :STARTS_THREAD, :DISPATCHES_TO and :INVOKES_SCRIPT when P4/P5 resolvers land.
     */
    public static final String FORWARD_REACH_FMT = """
        UNWIND $changed AS fqn
        MATCH (src:Method {fqn: fqn})
        OPTIONAL MATCH (src)-[:CALLS|STARTS_THREAD*1..%d]->(dst:Method)
        WITH fqn, [d IN collect(DISTINCT dst.fqn) WHERE d IS NOT NULL] AS reachable
        RETURN fqn, reachable
        """;

    // ── Backward reachability: changed method → exposed / scheduled entry points ──
    //
    // These are BUILT, not String.format'ed, because the call walk is unrolled one hop at a
    // time. See backwardWalk() for why.

    /**
     * Seeds the walk: one row per changed FQN, with the visited/frontier accumulators primed
     * to the changed method itself. Starting {@code visited} non-empty is what preserves the
     * old {@code *0..} semantics — a changed method that IS the entry point still reports
     * itself, with no caller required.
     */
    private static final String BACKWARD_SEED = """
        UNWIND $changed AS fqn
        MATCH (m:Method {fqn: fqn})
        WITH fqn, [m] AS visited, [m] AS frontier
        """;

    /**
     * One breadth-first hop backwards along :CALLS / :STARTS_THREAD, deduplicated against
     * everything already seen.
     *
     * <p>This replaces a {@code [:CALLS|STARTS_THREAD*0..depth]} variable-length pattern,
     * which times out on this graph. The cause is not the size of the answer — it is that a
     * variable-length pattern yields one row per PATH, and the call graph is cyclic, so the
     * path count explodes combinatorially while the set of reachable NODES stays tiny.
     * Measured on {@code GroupUsersListener.init} (52,745 :Method / 368,248 :CALLS edges):
     * the variable-length form finds 93 distinct callers at depth 8 in ~2s, and at depth 10
     * does not finish in 120s — not even under {@code count(DISTINCT)}, because the DISTINCT
     * is applied only after the expansion has already enumerated every path. Deduplicating
     * per hop bounds the work by the node count instead: same walk, 145 callers, ~2.8s.
     *
     * <p>The {@code CASE WHEN size(frontier) = 0} guard matters: {@code UNWIND []} destroys
     * the row, which would silently drop a changed method from the results as soon as its
     * frontier is exhausted — i.e. on every walk shorter than the depth bound.
     */
    private static final String BACKWARD_HOP = """
        UNWIND (CASE WHEN size(frontier) = 0 THEN [null] ELSE frontier END) AS f
        OPTIONAL MATCH (p:Method)-[:CALLS|STARTS_THREAD]->(f)
        WITH fqn, visited, collect(DISTINCT p) AS found
        WITH fqn, visited, [x IN found WHERE x IS NOT NULL AND NOT x IN visited] AS frontier
        WITH fqn, visited + frontier AS visited, frontier
        """;

    /**
     * Projection for REST entry points. Consumes {@code fqn} + {@code visited} (the root
     * candidates) and emits the row shape SliceExecutor deserializes.
     *
     * <p>The :EXPOSES gate is a CASE inside the {@code collect}, not a WHERE: {@code collect}
     * drops nulls, so non-exposed candidates vanish from the list while the row itself
     * survives. A WHERE here would filter the row away entirely and lose the changed method
     * from the result map whenever it has no exposed entry point.
     */
    private static final String ENTRY_POINTS_TAIL = """
        UNWIND visited AS root
        OPTIONAL MATCH (rootClass:Class)-[:CONTAINS]->(root)
        OPTIONAL MATCH (rootClass)-[:EXPOSES]->(classUrl:RestEndpoint)
        OPTIONAL MATCH (root)-[:EXPOSES]->(methodUrl:RestEndpoint)
        WITH fqn, root, rootClass,
             collect(DISTINCT classUrl.url) + collect(DISTINCT methodUrl.url) AS rawUrls
        WITH fqn, root, rootClass, [u IN rawUrls WHERE u IS NOT NULL] AS urls
        WITH fqn, collect(DISTINCT CASE WHEN EXISTS { (root)-[:EXPOSES]->(:RestEndpoint) } THEN {
              fqn:       root.fqn,
              labels:    labels(root) + CASE WHEN rootClass IS NULL THEN [] ELSE labels(rootClass) END,
              owner:     coalesce(rootClass.fqn, root.owner_fqn),
              rest_urls: urls
            } END) AS entry_points
        RETURN fqn,
               [ep IN entry_points WHERE ep.fqn IS NOT NULL] AS entry_points
        """;

    /**
     * Projection for scheduled entry points. Task names ride in {@code rest_urls} because
     * EntryPointRef is reused for both kinds of entry point.
     */
    private static final String SCHEDULE_TAIL = """
        UNWIND visited AS root
        OPTIONAL MATCH (rootClass:Class)-[:CONTAINS]->(root)
        OPTIONAL MATCH (rootClass)-[:SCHEDULES]->(classTask:ScheduledTask)
        OPTIONAL MATCH (root)-[:SCHEDULES]-(methodTask:ScheduledTask)
        WITH fqn, root, rootClass,
             collect(DISTINCT classTask.task_name) + collect(DISTINCT methodTask.task_name) AS rawTaskNames
        WITH fqn, root, rootClass, [t IN rawTaskNames WHERE t IS NOT NULL] AS taskNames
        WITH fqn, collect(DISTINCT CASE WHEN EXISTS { (root)-[:SCHEDULES]-(:ScheduledTask) } THEN {
              fqn:       root.fqn,
              labels:    labels(root) + CASE WHEN rootClass IS NULL THEN [] ELSE labels(rootClass) END,
              owner:     coalesce(rootClass.fqn, root.owner_fqn),
              rest_urls: taskNames
            } END) AS entry_points
        RETURN fqn,
               [ep IN entry_points WHERE ep.fqn IS NOT NULL] AS entry_points
        """;

    /**
     * Backward reachability — for each changed method, every method that reaches it within
     * {@code depth} :CALLS / :STARTS_THREAD hops and exposes a REST endpoint, including the
     * changed method itself when it is the exposed entry point.
     *
     * <p>Shape: <i>call walk → :EXPOSES gate</i>. No :OVERRIDES traversal happens here —
     * virtual-dispatch coverage comes from {@code SliceExecutor.expandWithOverrideParents},
     * which adds each changed method's {@code (m)-[:OVERRIDES*1..5]->(parent)} ancestors to
     * {@code $changed} before this query runs, so callers of a base declaration arrive as
     * their own rows.
     */
    public static String backwardEntryPoints(int depth) {
        return backwardWalk(depth) + ENTRY_POINTS_TAIL;
    }

    /** The :SCHEDULES analogue of {@link #backwardEntryPoints}. */
    public static String backwardSchedule(int depth) {
        return backwardWalk(depth) + SCHEDULE_TAIL;
    }

    /**
     * Seed + {@code depth} backward hops — the deduplicating equivalent of
     * {@code [:CALLS|STARTS_THREAD*0..depth]}, which times out on this graph (see
     * {@link #BACKWARD_HOP}).
     *
     * <p>Unrolled because Cypher has no loop construct and this deployment has no APOC —
     * {@code apoc.path.subgraphNodes} would express the same walk directly, but
     * {@code SHOW PROCEDURES} lists zero {@code apoc.path.*} procedures here.
     */
    private static String backwardWalk(int depth) {
        int d = clampDepth(depth);
        return BACKWARD_SEED + BACKWARD_HOP.repeat(d);
    }

    private static int clampDepth(int depth) {
        return Math.max(1, Math.min(10, depth));
    }

    /**
     * DB tables touched on any path forward from the change. Requires :READS_TABLE /
     * :WRITES_TABLE edges (P5). Returns empty lists today; the query is forward-compatible.
     */
    public static final String DB_TABLES_FMT = """
        UNWIND $changed AS fqn
        MATCH (src:Method {fqn: fqn})
        OPTIONAL MATCH (src)-[:CALLS*0..%d]->(m:Method)-[r:READS_TABLE|WRITES_TABLE]->(t:DbTable)
        WITH fqn,
             collect(DISTINCT CASE WHEN type(r) = 'READS_TABLE'  THEN t.name END) AS reads,
             collect(DISTINCT CASE WHEN type(r) = 'WRITES_TABLE' THEN t.name END) AS writes
        RETURN fqn,
               [t IN reads  WHERE t IS NOT NULL] AS reads,
               [t IN writes WHERE t IS NOT NULL] AS writes
        """;

    /**
     * Risk grading — entry-point fan-in + sensitive-package presence (clustering.distributed.*).
     * Returns one row per changed FQN with risk LOW|MEDIUM|HIGH.
     */
    public static final String RISK_FMT = """
        UNWIND $changed AS fqn
        MATCH (m:Method {fqn: fqn})
        OPTIONAL MATCH (root)-[:CALLS|STARTS_THREAD*1..%d]->(m)
        WHERE root:RestEndpoint OR root:ScheduledTask
              OR (root)-[:EXPOSES]->(:RestEndpoint)
        WITH fqn, m.owner_fqn AS owner, count(DISTINCT root) AS entry_count
        WITH fqn, owner, entry_count,
             (owner CONTAINS 'clustering.distributed' OR owner CONTAINS 'management.distributed') AS sensitive
        RETURN fqn,
               entry_count,
               sensitive,
               CASE
                   WHEN entry_count > 5 OR sensitive THEN 'HIGH'
                   WHEN entry_count > 1              THEN 'MEDIUM'
                   ELSE 'LOW'
               END AS risk
        """;

    /**
     * Returns the query with the depth substituted into EVERY {@code %d} placeholder.
     * Clamps depth to [1,10].
     *
     * <p>Supplies one argument per placeholder rather than exactly one argument. A query
     * here can legitimately need the same depth in more than one spot, and the previous
     * fixed single-argument call blew up with
     * {@code MissingFormatArgumentException: Format specifier '%d'} the moment a second one
     * was added. Counting them keeps adding a walk to a query a query-only edit.
     *
     * <p>{@code %d} is the only specifier these queries use; there is deliberately no support
     * for others, since every substitution here is a path bound.
     */
    public static String withDepth(String fmt, int depth) {
        int d = Math.max(1, Math.min(10, depth));
        int placeholders = 0;
        for (int i = fmt.indexOf("%d"); i >= 0; i = fmt.indexOf("%d", i + 2)) placeholders++;
        if (placeholders == 0) return fmt;
        Object[] args = new Object[placeholders];
        java.util.Arrays.fill(args, d);
        return String.format(fmt, args);
    }

    // ─── P8: TestCase coverage ─────────────────────────────────────────

    /**
     * For each changed method, find :TestCase nodes that cover any reachable graph node
     * (entry points, the method itself, classes it lives in). Higher confidence + more
     * touched targets = higher relevance score.
     */
    public static final String COVERING_TEST_CASES_FMT = """
        UNWIND $changed AS fqn
        MATCH (m:Method {fqn: fqn})
        OPTIONAL MATCH (root)-[:CALLS|STARTS_THREAD*1..%d]->(m)
        WHERE root:RestEndpoint OR root:ScheduledTask
              OR (root)-[:EXPOSES]->(:RestEndpoint)
        WITH fqn, collect(DISTINCT root) AS roots
        UNWIND (roots + []) AS r
        OPTIONAL MATCH (tc:TestCase)-[c:COVERS]->(r)
        WHERE tc IS NOT NULL
        WITH fqn, tc, max(c.confidence) AS conf, count(DISTINCT r) AS hits
        WHERE tc IS NOT NULL
        WITH fqn, collect(DISTINCT {id: tc.id, title: tc.title, area: tc.area, confidence: conf, hits: hits}) AS covers
        RETURN fqn, covers
        """;

    /**
     * Shortest call-chain between known (entryPoint, changedMethod) pairs.
     * Input rows: [{src: entryPointFqn, dst: changedMethodFqn, key: "src|dst"}]
     * The entry point CALLS (transitively) the changed method, so the path direction
     * is src → ... → dst. We return the intermediate simple names reversed so they
     * read left-to-right from the patched method outward toward the entry point:
     *   changedMethod → intermediateN → ... → intermediate1 → entryPoint
     *
     * <p>{@code dst <> src} is required, not cosmetic: since {@link #backwardEntryPoints}
     * seeds its walk with the changed method itself, the entry point CAN BE that method (a
     * directly-exposed handler like WorkFlowAction.commitRequest). Handing shortestPath
     * identical start and end nodes makes Neo4j throw
     * "shortest path algorithm does not work when the start and end nodes are the same",
     * which failed the whole batch. Dropping the self-pair is also the semantically right
     * answer — there is no chain to walk, the endpoint IS the patched method — and the
     * caller pre-seeds an empty chain per key, so the row simply renders as
     * (RestEndpoint) ←:EXPOSES← method() with no intermediates.
     */
    public static final String CALL_CHAIN_QUERY = """
        UNWIND $pairs AS pair
        MATCH (src:Method {fqn: pair.src})
        MATCH (dst:Method) WHERE dst.fqn IN pair.dstFqns AND dst <> src
        OPTIONAL MATCH path = shortestPath((src)-[:CALLS|STARTS_THREAD|OVERRIDES*1..100]->(dst))
        WITH pair.key AS k, path
        ORDER BY CASE WHEN path IS NULL THEN 9999 ELSE length(path) END ASC
        WITH k, collect(path)[0] AS best
        RETURN k,
               CASE WHEN best IS NULL THEN []
                    ELSE [n IN nodes(best)[1..-1] | n.simple_name]
               END AS chain,
               CASE WHEN best IS NULL THEN []
                    ELSE [r IN relationships(best) | type(r)]
               END AS edgeTypes
        """;

    /**
     * Schedule-side twin of {@link #CALL_CHAIN_QUERY}. Carries the same {@code dst <> src}
     * guard and for the same reason: {@link #backwardSchedule} can report the changed
     * method as its own schedule entry point (e.g. DailyReportTask.executeTask, which the
     * ScheduledTask points straight at), and shortestPath rejects identical endpoints.
     */
    public static final String CALL_CHAIN_SCHEDULE_QUERY = """
        UNWIND $pairs AS pair
        MATCH (src:Method {fqn: pair.src})
        MATCH (dst:Method) WHERE dst.fqn IN pair.dstFqns AND dst <> src
        OPTIONAL MATCH path = shortestPath((src)-[:CALLS|STARTS_THREAD*1..100]->(dst))
        WITH pair.key AS k, path
        ORDER BY CASE WHEN path IS NULL THEN 9999 ELSE length(path) END ASC
        WITH k, collect(path)[0] AS best
        RETURN k,
               CASE WHEN best IS NULL THEN []
                    ELSE [n IN nodes(best)[1..-1] | n.simple_name]
               END AS chain,
               CASE WHEN best IS NULL THEN []
                    ELSE [r IN relationships(best) | type(r)]
               END AS edgeTypes
        """;

    /**
     * Coverage gaps — entry points reached by the change that no :TestCase covers.
     * Returns one row per reached root, with the FQNs of all changed methods that reach it.
     */
    public static final String COVERAGE_GAPS_FMT = """
        UNWIND $changed AS fqn
        MATCH (m:Method {fqn: fqn})
        MATCH (root)-[:CALLS|STARTS_THREAD*1..%d]->(m)
        WHERE (root:RestEndpoint OR root:ScheduledTask 
               OR (root)-[:EXPOSES]->(:RestEndpoint))
          AND NOT EXISTS { (:TestCase)-[:COVERS]->(root) }
        WITH DISTINCT root, collect(DISTINCT fqn) AS reaching_changes
        RETURN root.fqn AS uncovered, labels(root) AS labels,
               root.owner_fqn AS owner, reaching_changes
        ORDER BY uncovered
        """;
}
