package io.spmp.impact.extract;

import io.spmp.impact.extract.BodyCollector.CallSite;
import io.spmp.impact.extract.BodyCollector.FieldAccessSite;
import io.spmp.impact.extract.BodyCollector.ScopeKind;
import io.spmp.impact.extract.GlobalIndex.MethodSig;
import io.spmp.impact.model.GraphEdges.CallEdge;
import io.spmp.impact.model.GraphEdges.FieldAccessEdge;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resolves {@link CallSite} records produced by {@link BodyCollector} into
 * {@link CallEdge}s using only the {@link GlobalIndex} + per-file imports.
 * No SymbolSolver is ever invoked.
 *
 * <p><b>Resolution chain:</b> see the per-{@link ScopeKind} branches in {@link #resolve}.
 * The strategies, in selection order:
 * <ol>
 *   <li>{@code enclosing-this} — {@code foo(x)} → owner = enclosing class FQN</li>
 *   <li>{@code this} — {@code this.foo(x)} → owner = enclosing class FQN</li>
 *   <li>{@code super} — {@code super.foo(x)} → owner = first parent in extends chain</li>
 *   <li>{@code static-class} — {@code Class.foo(x)} → owner = imports.resolveSimpleName(Class)</li>
 *   <li>{@code local-var} — {@code v.foo(x)} → owner = v's declared type (already resolved by BodyCollector)</li>
 *   <li>{@code field-ref} — {@code field.foo(x)} → owner = field's declared type</li>
 *   <li>{@code constructor} — {@code new T(x)} → emit one edge to T.&lt;init&gt;</li>
 * </ol>
 *
 * <p>For each candidate owner, we look up {@code methodsOn(owner, simpleName)} in the
 * index. If empty, we walk transitive ancestors. If multiple candidates match the arity,
 * we emit ALL as {@code kind="ambiguous-of-N"} — over-approximation is safe for impact
 * analysis (better than picking one wrong and missing the true caller).
 *
 * <p><b>Debug log:</b> when {@link #setCallTsvPath(Path)} is set, every resolved call is
 * written to a TSV via an async background writer. Workers enqueue formatted lines into
 * a {@link BlockingQueue}; one writer thread drains the queue. This keeps per-worker
 * resolution latency below ~2 µs.
 */
public final class CallResolver {

    private final GlobalIndex index;

    // ─── Debug-log infrastructure ────────────────────────────────────────
    private final BlockingQueue<String> tsvQueue = new LinkedBlockingQueue<>(8192);
    private volatile Thread tsvWriter;
    private volatile PrintWriter tsvOut;
    /** Sentinel inserted on shutdown to signal the writer thread to exit. */
    private static final String TSV_POISON = "POISON";

    // ─── Strategy counters (atomic — read at end-of-run) ─────────────────
    private final Map<String, AtomicLong> strategyCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, AtomicLong> strategyNanos  = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong ambiguous = new AtomicLong();
    private final AtomicLong unresolved = new AtomicLong();
    private final AtomicLong totalCalls = new AtomicLong();

    public CallResolver(GlobalIndex index) {
        this.index = index;
    }

    /** Enable per-call TSV logging. Must be called before {@link #resolve}. */
    public void setCallTsvPath(Path tsvPath) {
        if (tsvPath == null) return;
        try {
            Files.createDirectories(tsvPath.getParent() == null
                ? Path.of(".") : tsvPath.getParent());
            this.tsvOut = new PrintWriter(Files.newBufferedWriter(tsvPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING));
            tsvOut.println("fromFqn\tsimpleName\tarity\tscopeKind\tstrategy\tresultFqn\tcandidateCount\tnanos");
            this.tsvWriter = new Thread(this::drainTsvQueue, "call-resolver-tsv-writer");
            this.tsvWriter.setDaemon(true);
            this.tsvWriter.start();
        } catch (IOException ioe) {
            System.err.println("[CallResolver] failed to open TSV log: " + ioe.getMessage());
        }
    }

    private void drainTsvQueue() {
        try {
            while (true) {
                String line = tsvQueue.poll(2, TimeUnit.SECONDS);
                if (line == null) {
                    if (tsvOut != null) tsvOut.flush();
                    continue;
                }
                if (TSV_POISON.equals(line)) break;
                if (tsvOut != null) tsvOut.println(line);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            if (tsvOut != null) {
                tsvOut.flush();
                tsvOut.close();
            }
        }
    }

    /** Stop the TSV writer and flush. Idempotent. */
    public void shutdown() {
        if (tsvWriter != null) {
            tsvQueue.offer(TSV_POISON);
            try { tsvWriter.join(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    // ─── Resolution ──────────────────────────────────────────────────────

    /**
     * Resolve a single call site. Returns 0..N CallEdges (0 = no candidates; >1 = ambiguous).
     */
    public List<CallEdge> resolve(CallSite cs) {
        long t0 = System.nanoTime();
        totalCalls.incrementAndGet();
        Outcome outcome = doResolve(cs);
        long elapsedNs = System.nanoTime() - t0;
        record(outcome.strategy, elapsedNs);
        if (outcome.edges.isEmpty()) unresolved.incrementAndGet();
        else if (outcome.edges.size() > 1) ambiguous.incrementAndGet();
        // Async-log each emitted edge.
        if (tsvWriter != null) {
            int candCount = outcome.edges.size();
            String result = candCount == 0 ? "" : outcome.edges.get(0).toMethodFqn();
            tsvQueue.offer(String.join("\t",
                cs.fromMethodFqn(),
                cs.simpleName(),
                String.valueOf(cs.arity()),
                cs.kind().name(),
                outcome.strategy,
                result,
                String.valueOf(candCount),
                String.valueOf(elapsedNs)));
        }
        return outcome.edges;
    }

    /** Resolve a field-access site. Returns 0..1 FieldAccessEdges. */
    public List<FieldAccessEdge> resolveFieldAccess(FieldAccessSite fa) {
        String owner = fieldOwnerFor(fa);
        if (owner == null) return List.of();
        // Try direct field lookup, then walk ancestors.
        GlobalIndex.FieldInfo fi = index.fieldOn(owner, fa.fieldName());
        if (fi == null) {
            for (String ancestor : index.transitiveAncestors(owner)) {
                fi = index.fieldOn(ancestor, fa.fieldName());
                if (fi != null) break;
            }
        }
        if (fi == null) return List.of();
        return List.of(new FieldAccessEdge(fa.fromMethodFqn(), fi.fqn(), fa.isWrite()));
    }

    private String fieldOwnerFor(FieldAccessSite fa) {
        return switch (fa.kind()) {
            case EXPLICIT_THIS, ENCLOSING_THIS -> fa.enclosingClassFqn();
            case LOCAL_VAR, FIELD_REF -> fa.resolvedScopeFqn();
            case STATIC_CLASS -> {
                FileImports imp = index.importsOf(fa.filePath());
                String fqn = imp.resolveSimpleName(fa.scopeText());
                yield fqn != null ? fqn : fa.scopeText();
            }
            default -> null;
        };
    }

    private record Outcome(String strategy, List<CallEdge> edges) {}

    private Outcome doResolve(CallSite cs) {
        switch (cs.kind()) {
            case ENCLOSING_THIS:
                return resolveEnclosingThis(cs);
            case EXPLICIT_THIS:
                return resolveOwnerAndEmit(cs, cs.enclosingClassFqn(), "this", true);
            case EXPLICIT_SUPER: {
                List<String> parents = index.directParents(cs.enclosingClassFqn());
                if (parents.isEmpty()) return emitUnresolved(cs, "super-no-parent");
                // Walk all extends parents (interfaces too — default methods are valid super.foo)
                return resolveAcrossOwners(cs, parents, "super", false);
            }
            case STATIC_CLASS: {
                FileImports imp = index.importsOf(cs.filePath());
                String owner = imp.resolveSimpleName(cs.scopeText());
                if (owner == null) return emitUnresolved(cs, "static-class-unresolved-import");
                return resolveOwnerAndEmit(cs, owner, "static-class", true);
            }
            case LOCAL_VAR: {
                if (cs.resolvedScopeFqn() == null) return emitUnresolved(cs, "local-var-unresolved");
                return resolveOwnerAndEmit(cs, cs.resolvedScopeFqn(), "local-var", true);
            }
            case FIELD_REF: {
                // Try to look up the field on the enclosing class (most common: this.field.foo()).
                // scopeText may be "this.field" or "field"; pick the last segment as the field name.
                String fieldName = lastDot(cs.scopeText());
                GlobalIndex.FieldInfo fi = index.fieldOn(cs.enclosingClassFqn(), fieldName);
                if (fi == null) {
                    // Walk ancestors.
                    for (String anc : index.transitiveAncestors(cs.enclosingClassFqn())) {
                        fi = index.fieldOn(anc, fieldName);
                        if (fi != null) break;
                    }
                }
                if (fi == null) return emitUnresolved(cs, "field-ref-unknown-field");
                // The field's declared type may be a simple-name; resolve via the field's home file.
                FileImports imp = index.importsOf(
                    index.classOf(fi.ownerFqn()) != null
                        ? index.classOf(fi.ownerFqn()).filePath()
                        : cs.filePath());
                String typeFqn = fi.typeText();
                if (typeFqn != null && !typeFqn.contains(".")) {
                    String stripped = stripGenerics(typeFqn);
                    String resolved = imp.resolveSimpleName(stripped);
                    if (resolved != null) typeFqn = resolved;
                }
                if (typeFqn == null) return emitUnresolved(cs, "field-ref-untyped");
                return resolveOwnerAndEmit(cs, typeFqn, "field-ref", true);
            }
            case CONSTRUCTOR: {
                String owner = cs.resolvedScopeFqn() != null
                    ? cs.resolvedScopeFqn() : cs.scopeText();
                if (owner == null || owner.isEmpty()) return emitUnresolved(cs, "ctor-unresolved-type");
                // We always emit ONE edge to "<owner>.<init>" — concrete constructor selection
                // doesn't matter for impact reach (any constructor body change ripples back).
                String toFqn = owner + ".<init>(?)";
                return new Outcome("constructor",
                    List.of(new CallEdge(cs.fromMethodFqn(), toFqn, "constructor")));
            }
            case UNKNOWN_NAME:
                return emitUnresolved(cs, "unknown-name");
            case CHAINED:
                return emitUnresolved(cs, "chained-call");
            default:
                return emitUnresolved(cs, "unhandled-kind");
        }
    }

    /**
     * Look up methods on {@code owner} (and ancestors if needed), filter by arity,
     * emit one edge per surviving candidate. If multiple survive, emit all as
     * {@code kind="ambiguous-of-N"}.
     */
    private Outcome resolveOwnerAndEmit(CallSite cs, String owner, String strategy, boolean walkAncestors) {
        List<MethodSig> direct = index.methodsOn(owner, cs.simpleName());
        List<MethodSig> arity = filterByArity(direct, cs.arity());
        if (!arity.isEmpty()) return emit(cs, arity, strategy);
        if (walkAncestors) {
            // Try ancestors — needed for inherited methods.
            for (String anc : index.transitiveAncestors(owner)) {
                List<MethodSig> ancMs = index.methodsOn(anc, cs.simpleName());
                List<MethodSig> ancArity = filterByArity(ancMs, cs.arity());
                if (!ancArity.isEmpty()) return emit(cs, ancArity, strategy + "-inherited");
            }
        }
        // Last resort: emit an edge to "owner.simpleName(?)" so the call is still in the graph
        // (Neo4j MERGE creates a placeholder Method node — downstream queries see it as orphan).
        String placeholder = owner + "." + cs.simpleName() + "(?)";
        return new Outcome(strategy + "-no-arity-match",
            List.of(new CallEdge(cs.fromMethodFqn(), placeholder, "unresolved-arity")));
    }

    /**
     * Resolve an unqualified (implicit-{@code this}) call. Per Java scoping, name lookup for
     * {@code foo()} inside a nested class searches the innermost enclosing class + its supers,
     * then each ENCLOSING OUTER class + its supers, moving outward. The enclosing outer classes
     * are prefixes of the dotted {@code enclosingClassFqn} (e.g. {@code pkg.Outer.Inner} →
     * {@code pkg.Outer}); we peel one trailing segment at a time and stop once a prefix is no
     * longer a known {@code :Class} (that's the package boundary). Only implicit {@code this}
     * searches outward — {@code EXPLICIT_THIS} ({@code this.foo()}) binds to the innermost
     * instance and is left as-is.
     */
    private Outcome resolveEnclosingThis(CallSite cs) {
        Outcome inner = resolveOwnerAndEmit(cs, cs.enclosingClassFqn(), "enclosing-this", true);
        if (isResolved(inner)) return inner;
        String cur = cs.enclosingClassFqn();
        while (cur != null && cur.contains(".")) {
            cur = cur.substring(0, cur.lastIndexOf('.'));
            if (index.classOf(cur) == null) break;   // reached the package — stop walking outward
            Outcome outer = resolveOwnerAndEmit(cs, cur, "enclosing-outer-this", true);
            if (isResolved(outer)) return outer;
        }
        // No enclosing class declared it — keep the innermost placeholder (unchanged behavior).
        return inner;
    }

    /** True when an Outcome is a real resolution, not a {@code "unresolved-arity"} placeholder. */
    private static boolean isResolved(Outcome o) {
        return !o.edges.isEmpty() && !"unresolved-arity".equals(o.edges.get(0).kind());
    }

    private Outcome resolveAcrossOwners(CallSite cs, List<String> owners, String strategy, boolean walkAncestors) {
        for (String owner : owners) {
            Outcome o = resolveOwnerAndEmit(cs, owner, strategy, walkAncestors);
            if (!o.edges.isEmpty() && !"unresolved-arity".equals(o.edges.get(0).kind())) return o;
        }
        // None hit; fall through to unresolved.
        return emitUnresolved(cs, strategy + "-no-owner-hit");
    }

    private static List<MethodSig> filterByArity(List<MethodSig> candidates, int arity) {
        if (candidates.isEmpty()) return candidates;
        List<MethodSig> out = new ArrayList<>(candidates.size());
        for (MethodSig m : candidates) {
            if (m.paramCount() == arity) out.add(m);
        }
        if (!out.isEmpty()) return out;
        // Tolerate varargs / mismatched-but-close arities: any method with arity ≤ args is a candidate.
        for (MethodSig m : candidates) {
            // Allow caller-arity-≥-method-arity (varargs) and exact match (already covered above).
            if (m.paramCount() <= arity) out.add(m);
        }
        return out;
    }

    private static Outcome emit(CallSite cs, List<MethodSig> matches, String strategy) {
        if (matches.size() == 1) {
            MethodSig m = matches.get(0);
            return new Outcome(strategy,
                List.of(new CallEdge(cs.fromMethodFqn(), m.fqn(),
                    m.isStatic() ? "static" : "virtual")));
        }
        // Ambiguous — emit ALL. Over-approximation is safe for impact analysis.
        List<CallEdge> edges = new ArrayList<>(matches.size());
        String kind = "ambiguous-of-" + matches.size();
        for (MethodSig m : matches) {
            edges.add(new CallEdge(cs.fromMethodFqn(), m.fqn(), kind));
        }
        return new Outcome(strategy + "-ambiguous", edges);
    }

    private static Outcome emitUnresolved(CallSite cs, String reason) {
        // Even unresolved calls go into the graph as a placeholder so the count is preserved.
        // The placeholder uses the scope text + simple name so downstream queries can dedupe.
        String scope = cs.scopeText() == null || cs.scopeText().isEmpty()
            ? "?" : cs.scopeText();
        String toFqn = scope + "." + cs.simpleName() + "(?)";
        return new Outcome(reason,
            List.of(new CallEdge(cs.fromMethodFqn(), toFqn, "unresolved")));
    }

    private static String lastDot(String s) {
        if (s == null) return "";
        int i = s.lastIndexOf('.');
        return i < 0 ? s : s.substring(i + 1);
    }

    private static String stripGenerics(String s) {
        if (s == null) return null;
        int lt = s.indexOf('<');
        return lt < 0 ? s : s.substring(0, lt).trim();
    }

    private void record(String strategy, long ns) {
        strategyCounts.computeIfAbsent(strategy, k -> new AtomicLong()).incrementAndGet();
        strategyNanos.computeIfAbsent(strategy, k -> new AtomicLong()).addAndGet(ns);
    }

    // ─── End-of-run reporting ────────────────────────────────────────────

    /** Strategy-breakdown summary. Returns lines suitable for stdout + audit log. */
    public List<String> summaryLines() {
        List<String> lines = new ArrayList<>();
        long total = totalCalls.get();
        lines.add(String.format("CallResolver totals: %d call sites resolved (%d ambiguous, %d unresolved)",
            total, ambiguous.get(), unresolved.get()));
        // Sort by count desc.
        var sorted = new TreeMap<Long, List<String>>(java.util.Comparator.reverseOrder());
        strategyCounts.forEach((k, v) -> sorted.computeIfAbsent(v.get(), x -> new ArrayList<>()).add(k));
        for (var entry : sorted.entrySet()) {
            for (String strategy : entry.getValue()) {
                long cnt = entry.getKey();
                long ns = strategyNanos.getOrDefault(strategy, new AtomicLong()).get();
                long avgNs = cnt == 0 ? 0 : ns / cnt;
                double pct = total == 0 ? 0 : 100.0 * cnt / total;
                lines.add(String.format("  %-32s %8d (%5.1f%%)  avg %d ns",
                    strategy, cnt, pct, avgNs));
            }
        }
        return lines;
    }
}
