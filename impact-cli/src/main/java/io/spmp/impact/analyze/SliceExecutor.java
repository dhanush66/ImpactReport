package io.spmp.impact.analyze;

import io.spmp.impact.graph.CypherQueries;
import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.txn.CypherClient.CRecord;
import io.spmp.impact.graph.txn.CypherClient.CResult;
import io.spmp.impact.graph.txn.CypherClient.CValue;
import io.spmp.impact.model.DiffModels.ChangedSymbol;
import io.spmp.impact.model.ImpactReport;
import io.spmp.impact.model.ImpactReport.CoverageGap;
import io.spmp.impact.model.ImpactReport.CoverageSummary;
import io.spmp.impact.model.ImpactReport.CoveringTestCase;
import io.spmp.impact.model.ImpactReport.EntryPointRef;
import io.spmp.impact.model.ImpactReport.AffectedApi;
import io.spmp.impact.model.ImpactReport.AffectedDbTable;
import io.spmp.impact.model.ImpactReport.AffectedSchedule;
import io.spmp.impact.model.ImpactReport.FeatureRef;
import io.spmp.impact.model.ImpactReport.LayerImpact;
import io.spmp.impact.model.ImpactReport.OwnerEnrichment;
import io.spmp.impact.model.ImpactReport.PolyglotChange;
import io.spmp.impact.model.ImpactReport.SymbolImpact;
import io.spmp.impact.testgen.generate.EnglishTranslator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Runs the four Cypher slice queries against Neo4j and assembles an {@link ImpactReport}.
 *
 * <p>Strategy:
 *  1. Collapse the input symbol list to a list of method-level FQNs (CLASS / INTERFACE entries
 *     don't have direct CALLS edges; their "reach" is captured indirectly via their methods).
 *  2. Run forward, backward, dbtables, risk queries once, with $changed = methodFqns.
 *  3. Merge the per-FQN results back into one SymbolImpact per input ChangedSymbol.
 *  4. Aggregate to overall risk + counts.
 */
public class SliceExecutor {

    private final Neo4jWriter writer;
    private final int depth;

    public SliceExecutor(Neo4jWriter writer, int depth) {
        this.writer = writer;
        this.depth = depth;
    }

    public ImpactReport run(String diffSource, String repoPath, List<ChangedSymbol> changes) {
        return run(diffSource, repoPath, changes, List.of());
    }

    public ImpactReport run(String diffSource, String repoPath,
                            List<ChangedSymbol> changes,
                            List<PolyglotChange> polyglotChanges) {
        List<String> changedMethodFqns = changes.stream()
            .filter(c -> c.kind() == ChangedSymbol.Kind.METHOD || c.kind() == ChangedSymbol.Kind.CONSTRUCTOR)
            .map(ChangedSymbol::fqn)
            .distinct()
            .collect(Collectors.toList());

        // FQN reconciliation: the patch-side hunk resolver produces FQNs with
        // SymbolSolver-resolved param types (e.g. java.lang.Long, ?), but the graph's
        // :Method nodes for lite-mode dep repos store source-text params (e.g. Long,
        // HttpServletRequest). Same method, different FQN string. Without this step
        // every slice query lookup misses and we report 0 entry points / 0 reach for
        // every patch that touches a lite-mode file. Resolve each input FQN to its
        // matching graph :Method node FQN via fuzzy (owner + simple + param-count).
        Map<String, List<String>> inputToGraphFqns = resolveFuzzyMethodFqns(changedMethodFqns);
        List<String> reconciled = new ArrayList<>();
        Map<String, List<String>> graphToInputs = new HashMap<>();
        for (var e : inputToGraphFqns.entrySet()) {
            for (String gf : e.getValue()) {
                reconciled.add(gf);
                graphToInputs.computeIfAbsent(gf, k -> new ArrayList<>()).add(e.getKey());
            }
        }
        // Deduplicate while preserving order; ensures we don't UNWIND the same graph FQN twice.
        reconciled = new ArrayList<>(new LinkedHashSet<>(reconciled));
        int graphMatchCount = reconciled.size();
        int unmappedInputs = (int) changedMethodFqns.stream()
            .filter(in -> !inputToGraphFqns.containsKey(in) || inputToGraphFqns.get(in).isEmpty())
            .count();
        System.out.printf("[analyze] fqn reconciliation: %d input methods -> %d graph methods (unmapped: %d)%n",
            changedMethodFqns.size(), graphMatchCount, unmappedInputs);

        // Virtual-dispatch expansion: if a changed method overrides a parent method,
        // callers of the parent should also count as reaching the changed method.
        // Expand $changed to include every ancestor reachable via :OVERRIDES, so the
        // existing backward-slice queries pick those callers up automatically.
        // We expand on the RECONCILED set (graph FQNs) so the :OVERRIDES match finds real nodes.
        List<String> methodFqns = expandWithOverrideParents(reconciled);

        //Map<String, ForwardRow>  forwardRaw  = runForward(methodFqns);
        Map<String, BackwardRow> backwardRawApi = runBackwardApi(methodFqns);
        Map<String, BackwardRow> backwardRawSchedule = runBackwardSchedule(methodFqns);
        //Map<String, DbTablesRow> dbTablesRaw = runDbTables(methodFqns);
        Map<String, RiskRow>     riskRaw     = runRisk(methodFqns);
        //CoverageSummary          coverage    = runCoverage(methodFqns);

        // Re-key per-input by aggregating across every graph FQN that mapped to it.
        //Map<String, ForwardRow>  forward  = aggregateForwardByInput(forwardRaw,  graphToInputs);
        Map<String, BackwardRow> backwardApi = aggregateBackwardByInput(backwardRawApi, graphToInputs);
        Map<String, BackwardRow> backwardSchedule = aggregateBackwardByInput(backwardRawSchedule, graphToInputs);
        //Map<String, DbTablesRow> dbTables = aggregateDbTablesByInput(dbTablesRaw, graphToInputs);
        Map<String, RiskRow>     risk     = aggregateRiskByInput(riskRaw, graphToInputs);

        List<SymbolImpact> symbols = new ArrayList<>(changes.size());
        for (ChangedSymbol cs : changes) {
            String fqn = cs.fqn();
            //ForwardRow f = forward.get(fqn);
            BackwardRow b = backwardApi.get(fqn);
            BackwardRow s = backwardSchedule.get(fqn);
            //DbTablesRow d = dbTables.get(fqn);
            RiskRow r = risk.get(fqn);

            //List<String> fwd = f != null ? f.reachable : List.of();
            // Try the graph-truth path first — :SENDS_NOTIFICATION / :SENDS_EMAIL /
            // :WRITES_AUDIT / :SCHEDULES walked from this method's forward reach. Falls
            // back to the name-matching heuristic when none of those edges exist
            // (i.e. the §4.1 resolvers haven't been run yet, or the call sites use a
            // pattern they don't recognise).
            //
            // FQN reconciliation: cs.fqn() carries "?" placeholders for unresolved
            // param types. The graph stores the source-text form. Look the reconciled
            // graph FQN up from inputToGraphFqns so the MATCH actually hits a real
            // :Method node.
            List<String> graphFqns = inputToGraphFqns.getOrDefault(fqn, List.of(fqn));
            symbols.add(new SymbolImpact(
                fqn,
                cs.kind().name(),
                cs.nature().name(),
                cs.filePath(),
                cs.startLine(),
                cs.endLine(),
                r != null ? r.risk : "LOW",
                r != null && r.sensitive,
                r != null ? r.entryCount : 0,
                b != null ? b.entryPoints : List.of(),
                s != null ? s.entryPoints : List.of(),
                //fwd,
                //d != null ? d.reads : List.of(),
                //d != null ? d.writes : List.of(),
                cs.hunkStartLine(),
                cs.hunkEndLine()
            ));
        }

        Map<String, Long> kindCounts = changes.stream()
            .collect(Collectors.groupingBy(
                c -> c.kind() + "/" + c.nature(),
                Collectors.counting()));

        Map<String, Long> riskCounts = symbols.stream()
            .collect(Collectors.groupingBy(SymbolImpact::risk, Collectors.counting()));
        riskCounts.putIfAbsent("HIGH", 0L);
        riskCounts.putIfAbsent("MEDIUM", 0L);
        riskCounts.putIfAbsent("LOW", 0L);

        int totalEntryPointsApi = symbols.stream()
            .mapToInt(s -> s.entryPointsApi().size())
            .sum();
        int totalEntryPointsSchedule = symbols.stream()
            .mapToInt(s -> s.entryPointsSchedule().size())
            .sum();
        //int totalForwardReach = symbols.stream()
        //    .mapToInt(s -> s.forwardReach().size())
        //    .sum();

        // Escalate polyglot risk based on the Java-side classes their downstream reach hits.
        // If any class named in a PolyglotChange.javaClassesExposing is also an entry-point
        // owner that the Java slice marked HIGH, escalate the polyglot change to HIGH.
        Set<String> highRiskJavaClasses = new HashSet<>();
        for (SymbolImpact s : symbols) {
            if (!"HIGH".equals(s.risk())) continue;
            for (EntryPointRef ep : s.entryPointsApi()) {
                String o = ep.owner() == null || ep.owner().isEmpty() ? ep.fqn() : ep.owner();
                if (o != null && !o.isEmpty()) highRiskJavaClasses.add(o);
            }
        }
        List<PolyglotChange> escalated = new ArrayList<>(polyglotChanges.size());
        for (PolyglotChange pc : polyglotChanges) {
            String r = pc.risk();
            for (String cls : pc.javaClassesExposing()) {
                if (highRiskJavaClasses.contains(cls)) { r = "HIGH"; break; }
            }
            escalated.add(new PolyglotChange(
                pc.filePath(), pc.language(), pc.role(), pc.changeType(), pc.hunkCount(),
                pc.restUrlsCalled(), pc.javaClassesExposing(), 
                pc.dbTablesAffected(), pc.dbColumnsAffected(), pc.restEndpointsAffected(),
                pc.javaCallersOfThisFile(), r,
                pc.writersOfAffectedTables(), pc.readersOfAffectedTables(),
                pc.changedSymbolsInFile()
            ));
        }
        for (PolyglotChange pc : escalated) {
            riskCounts.merge(pc.risk(), 1L, Long::sum);
        }

        String overallRisk = riskCounts.getOrDefault("HIGH", 0L) > 0 ? "HIGH"
                           : riskCounts.getOrDefault("MEDIUM", 0L) > 0 ? "MEDIUM"
                           : "LOW";

        ImpactReport pre = new ImpactReport(
            Instant.now().toString(),
            diffSource,
            repoPath,
            changes.size(),
            totalEntryPointsApi,
            totalEntryPointsSchedule,
            //totalForwardReach,
            kindCounts,
            riskCounts,
            overallRisk,
            symbols,                   // generatedTests filled in next
            null,                           // layerImpact filled in next
            escalated,                      // polyglotChanges (risk-adjusted)
            List.of(),                      // apisAffected (computed below)
            List.of(),                      // schedulesAffected (computed below),                      // uiComponentsAffected (computed below)
            List.of()
        );
        // Collect owner-class FQNs of the report's entry points — used by layer-impact analysis.
        Set<String> ownerFqns = new HashSet<>();
        for (SymbolImpact s : pre.symbols()) {
            for (EntryPointRef ep : s.entryPointsApi()) {
                String o = ep.owner();
                if (o == null || o.isEmpty()) o = ep.fqn();
                ownerFqns.add(o);
            }
            for (EntryPointRef ep : s.entryPointsSchedule()) {
                String o = ep.owner();
                if (o == null || o.isEmpty()) o = ep.fqn();
                ownerFqns.add(o);
            }
        }
        LayerImpact layer = runLayerImpact(pre, methodFqns, ownerFqns, escalated);

        // ── AFF: aggregate "what specifically got affected" lists ──
        // Build a feature index up front (class FQN → list of FeatureRefs) — every
        // downstream aggregator (APIs, DB tables, UI) consults it to attach user-facing names.
        java.util.Set<String> featureOwners = collectFeatureOwnerCandidates(pre);
        Map<String, List<FeatureRef>> featureIndex = classifyFeatures(featureOwners);

        List<AffectedApi> apis = runDiscoveryPathApis(pre, featureIndex, inputToGraphFqns);
        List<AffectedApi> schedules = runDiscoveryPathSchedules(pre, featureIndex, inputToGraphFqns);
        //Reference for Db tables
        //List<AffectedSchedule> schedules = runAffectedSchedules(pre, featureIndex, methodFqns);
        List<AffectedDbTable> affectedDbTables = runAffectedDbTables(pre, featureIndex);

        // §4.1: aggregate Notifications / Audits / Properties / FeatureFlags /
        // Permissions / Events / ExternalSystems / StateTransitions reached by the
        // slice. Each is one Cypher query that walks forward-reach to the relevant
        // boundary node. Empty list when the §4.1 resolvers haven't run yet — the
        // report sections just show "none" in that case.
        // CRITICAL: use the RECONCILED methodFqns (graph-FQN form) rather than
        // pre.symbols().fqn(). The patch-side FQNs carry "?" placeholders for
        // unresolved param types (e.g. WorkFlowAction.approveRequest(?,?)) while the
        // graph stores the source-text form ("HttpServletRequest,HttpServletResponse").
        // A direct MATCH on the patch FQN never resolves; queries return 0 rows
        // even when the underlying edges exist. The methodFqns list (built at the
        // top of this method via resolveFuzzyMethodFqns + expandWithOverrideParents)
        // is what every other slice query uses.
        List<String> changedFqns = methodFqns;
        // First diagnostic: confirm the graph actually carries the new edges. If counts
        // are all zero the resolvers haven't run yet (re-ingest required), or the
        // resolvers detected nothing at this codebase's call sites — either way the
        // user needs to know before debugging the analyze side.
        logBoundaryEdgeCounts();
        // Build hunk-row list for §8c2 Layer C gating-block detection. Each ChangedSymbol
        // carries (fqn, hunkStartLine, hunkEndLine); the gates-dispatch query needs the
        // hunk range, not just the method FQN, to test line-range overlap.
        java.util.List<Map<String, Object>> hunkRows = new java.util.ArrayList<>();
        for (var s : pre.symbols()) {
            if (s.fqn() == null || s.fqn().isEmpty()) continue;
            // Fall back to the method's full declaration range when the diff carries no
            // explicit hunk lines — matches the pattern used by the :READS_PARAM filter
            // below. Avoids the (0,0) trap that would never overlap any block range.
            int hStart = s.hunkStartLine() > 0 ? s.hunkStartLine() : s.startLine();
            int hEnd   = s.hunkEndLine()   > 0 ? s.hunkEndLine()   : s.endLine();
            if (hStart <= 0 || hEnd < hStart) continue;
            // The patch-side fqn carries SymbolSolver "?" placeholders for unresolved
            // param types. The graph stores source-text params. Expand to every graph
            // FQN this patch fqn reconciled to (see line ~79). Without this, the
            // MATCH (caller:Method {fqn: row.fqn}) in the Layer C query never hits.
            List<String> graphFqns = inputToGraphFqns.getOrDefault(s.fqn(), List.of(s.fqn()));
            for (String gf : graphFqns) {
                hunkRows.add(Map.of(
                    "fqn",    gf,
                    "hStart", (long) hStart,
                    "hEnd",   (long) hEnd
                ));
            }
        }


        return new ImpactReport(
            pre.generated(),
            pre.diffSource(),
            pre.repoPath(),
            pre.totalChangedSymbols(),
            pre.totalEntryPointsApi(),
            pre.totalEntryPointsSchedule(),
            //pre.totalForwardReach(),
            pre.kindCounts(),
            pre.riskCounts(),
            pre.overallRisk(),
            pre.symbols(),
            layer,
            pre.polyglotChanges(),
            apis,
            schedules,
            affectedDbTables
        );
    }

    // ─── §4.1 Affected-X aggregators ─────────────────────────────────────────
    // Pattern: walk forward from each changed method via :CALLS|:DISPATCHES_TO at
    // most `depth` hops, MATCH to the boundary node via the new edge type, group by
    // boundary-node key, count reaching symbols, sample a few emitting methods.
    // Risk = MEDIUM by default (these are side effects worth verifying); promoted to
    // HIGH only when a reaching SymbolImpact is itself HIGH-risk.

    /**
     * Diagnostic: at the start of every analyze run, ask the graph how many §4.1
     * boundary edges it carries. If everything is zero we know the resolvers haven't
     * populated the graph (the typical cause: ingest hasn't been re-run since the
     * §4.1 resolvers shipped). The lines appear in the server log AND in the job's
     * tailable output via TeePrintStream.
     */
    private void logBoundaryEdgeCounts() {
        try (CResult r = writer.session().run(
            "RETURN " +
            "  size([()-[:SENDS_NOTIFICATION]->() | 1])      AS sn, " +
            "  size([()-[:SENDS_EMAIL]->() | 1])             AS se, " +
            "  size([()-[:WRITES_AUDIT]->() | 1])            AS wa, " +
            "  size([()-[:SCHEDULES]->() | 1])               AS sch, " +
            "  size([()-[:PUBLISHES_EVENT]->() | 1])         AS pe, " +
            "  size([()-[:LISTENS_FOR]->() | 1])             AS lf, " +
            "  size([()-[:READS_PROPERTY]->() | 1])          AS rp, " +
            "  size([()-[:GATED_BY]->() | 1])                AS gb, " +
            "  size([()-[:REQUIRES_PERMISSION]->() | 1])     AS rpe, " +
            "  size([()-[:VALIDATES_INPUT]->() | 1])         AS vi, " +
            "  size([()-[:CALLS_EXTERNAL]->() | 1])          AS ce, " +
            "  size([()-[:WRITES_LOG]->() | 1])              AS wl, " +
            "  size([()-[:TRANSITIONS_STATE]->() | 1])       AS ts, " +
            "  size([()-[:INSTANTIATES]->() | 1])            AS ins, " +
            "  size([()-[:INJECTS]->() | 1])                 AS inj, " +
            "  size([()-[:SINGLETON_OF]->() | 1])            AS so, " +
            "  size([()-[:TRIGGERS_ORCHESTRATION]->() | 1])  AS tro, " +
            "  size([()-[:USER_SCHEDULES]->() | 1])          AS us, " +
            "  size([()-[:GATES_DISPATCH]->() | 1]) + size([()-[r:INSTANTIATES]->() WHERE r.block_start_line IS NOT NULL | 1]) AS gd")) {
            if (r.hasNext()) {
                CRecord rec = r.next();
                int sn = rec.get("sn").asInt(0), se = rec.get("se").asInt(0), wa = rec.get("wa").asInt(0);
                int sch = rec.get("sch").asInt(0), pe = rec.get("pe").asInt(0), lf = rec.get("lf").asInt(0);
                int rp = rec.get("rp").asInt(0), gb = rec.get("gb").asInt(0);
                int rpe = rec.get("rpe").asInt(0), vi = rec.get("vi").asInt(0);
                int ce = rec.get("ce").asInt(0), wl = rec.get("wl").asInt(0), ts = rec.get("ts").asInt(0);
                int ins = rec.get("ins").asInt(0), inj = rec.get("inj").asInt(0), so = rec.get("so").asInt(0);
                int tro = rec.get("tro").asInt(0), us = rec.get("us").asInt(0);
                int gd = rec.get("gd").asInt(0);
                int total = sn + se + wa + sch + pe + lf + rp + gb + rpe + vi + ce + wl + ts + ins + inj + so + tro + us + gd;
                System.out.printf("[analyze] §4.1 graph edges in Neo4j: total=%d (sends_notif=%d sends_email=%d writes_audit=%d schedules=%d publishes=%d listens=%d reads_prop=%d gated_by=%d req_perm=%d valid=%d ext=%d log=%d state=%d instantiates=%d injects=%d singleton=%d triggers_orch=%d user_sched=%d dispatch_blocks=%d)%n",
                    total, sn, se, wa, sch, pe, lf, rp, gb, rpe, vi, ce, wl, ts, ins, inj, so, tro, us, gd);
                if (total == 0) {
                    System.out.println("[analyze] §4.1 graph edges: NONE PRESENT — the §4.1 resolvers haven't populated the graph. RE-INGEST is required for these sections to appear.");
                }
            }
        } catch (Throwable t) {
            System.err.println("[analyze] §4.1 boundary-edge count probe failed: " + t.getMessage());
        }
    }
    

    
    /** Format an emitting method's FQN as a short "ClassName.method()" for the sample list. */
    private static String shortMethod(String fqn) {
        if (fqn == null || fqn.isEmpty()) return "";
        int paren = fqn.indexOf('(');
        String prefix = paren > 0 ? fqn.substring(0, paren) : fqn;
        int dot = prefix.lastIndexOf('.');
        if (dot <= 0) return prefix + "()";
        String ownerSimple;
        int dot2 = prefix.lastIndexOf('.', dot - 1);
        ownerSimple = dot2 < 0 ? prefix.substring(0, dot) : prefix.substring(dot2 + 1, dot);
        return ownerSimple + "." + prefix.substring(dot + 1) + "()";
    }

    // ─── Feature classifier ────────────────────────────────────────────────

    /**
     * Build the candidate set of owner-class FQNs whose features we want to know about.
     * Includes: every entry-point owner, every reach-class, every polyglot Java owner,
     * every patched class itself.
     */
    private java.util.Set<String> collectFeatureOwnerCandidates(ImpactReport pre) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (SymbolImpact s : pre.symbols()) {
            // The class itself (or owner of a method/ctor symbol)
            String fqn = s.fqn();
            int paren = fqn.indexOf('(');
            String prefix = paren > 0 ? fqn.substring(0, paren) : fqn;
            if ("CLASS".equals(s.kind()) || "INTERFACE".equals(s.kind())) {
                out.add(prefix);
            } else {
                int dot = prefix.lastIndexOf('.');
                if (dot > 0) out.add(prefix.substring(0, dot));
            }
            for (var ep : s.entryPointsApi()) {
                String o = ep.owner() == null || ep.owner().isEmpty() ? ep.fqn() : ep.owner();
                if (o != null && !o.isEmpty()) out.add(o);
            }
            for (var ep : s.entryPointsSchedule()) {
                String o = ep.owner() == null || ep.owner().isEmpty() ? ep.fqn() : ep.owner();
                if (o != null && !o.isEmpty()) out.add(o);
            }

        }
        for (PolyglotChange pc : pre.polyglotChanges()) {
            if (pc.javaClassesExposing() != null) out.addAll(pc.javaClassesExposing());
        }
        return out;
    }

    /**
     * For each owner class FQN, query the graph for labels + HANDLES edges, and translate
     * to a list of user-facing FeatureRefs.
     */
    private Map<String, List<FeatureRef>> classifyFeatures(java.util.Set<String> owners) {
        Map<String, List<FeatureRef>> out = new HashMap<>();
        if (owners.isEmpty()) return out;
        try (CResult r = writer.session().run(
            "UNWIND $owners AS o " +
            "MATCH (c:Class {fqn: o}) " +
            "OPTIONAL MATCH (c)-[:HANDLES]->(tt:TaskType) " +
            "RETURN o AS owner, c.simple_name AS simple, labels(c) AS lbls, " +
            "       collect(DISTINCT tt.id) AS taskTypes",
            Map.of("owners", new ArrayList<>(owners)))) {
            while (r.hasNext()) {
                CRecord rec = r.next();
                String owner = rec.get("owner").asString("");
                if (owner.isEmpty()) continue;
                String simple = rec.get("simple").asString(simpleName(owner));
                List<String> labels = rec.get("lbls").asList(CValue::asString);
                List<String> taskTypeIds = filterNonEmpty(rec.get("taskTypes").asList(CValue::asString));

                List<FeatureRef> features = new ArrayList<>();
                // TaskType — strongest user-facing identifier
                for (String tt : taskTypeIds) {
                    features.add(new FeatureRef("TaskType", tt, EnglishTranslator.className(tt)));
                }
                // Report — naming heuristic (SPMP convention)
                if (looksLikeReport(simple)) {
                    String trimmed = stripReportSuffix(simple);
                    features.add(new FeatureRef("Report", simple, EnglishTranslator.className(trimmed)));
                }

                // Scheduler / Job
                if (labels.contains("Scheduler")) {
                    features.add(new FeatureRef("Scheduler", simple, EnglishTranslator.className(simple)));
                }
                if (labels.contains("Job")) {
                    features.add(new FeatureRef("Job", simple, EnglishTranslator.className(simple)));
                }
                // If nothing else stuck but it's a TaskHandler, surface that with its TaskType
                if (features.isEmpty() && (labels.contains("TaskHandler") || !taskTypeIds.isEmpty())) {
                    String label = EnglishTranslator.className(EnglishTranslator.stripSuffix(simple));
                    features.add(new FeatureRef("TaskHandler", simple, label));
                }
                if (!features.isEmpty()) out.put(owner, features);
            }
        } catch (Throwable t) {
            System.err.println("[SliceExecutor] feature classification failed: " + t.getMessage());
        }
        return out;
    }

    private static boolean looksLikeReport(String simple) {
        if (simple == null) return false;
        return simple.endsWith("Report")
            || simple.endsWith("ReportGenerator")
            || simple.endsWith("DataCollector");
    }

    private static String stripReportSuffix(String simple) {
        if (simple == null) return "";
        if (simple.endsWith("ReportGenerator")) return simple.substring(0, simple.length() - "ReportGenerator".length());
        if (simple.endsWith("DataCollector"))   return simple.substring(0, simple.length() - "DataCollector".length());
        if (simple.endsWith("Report"))           return simple.substring(0, simple.length() - "Report".length());
        return simple;
    }

    /** Dedupe + cap a flat list of FeatureRefs by (kind, id). */
    private static List<FeatureRef> dedupeFeatures(List<FeatureRef> in, int cap) {
        if (in == null || in.isEmpty()) return List.of();
        java.util.LinkedHashMap<String, FeatureRef> seen = new java.util.LinkedHashMap<>();
        for (FeatureRef f : in) {
            seen.putIfAbsent(f.kind() + "|" + f.id(), f);
            if (seen.size() >= cap) break;
        }
        return new ArrayList<>(seen.values());
    }

    // ─── AFF: affected-API aggregation ────────────────────────────────────────

    /**
     * Union of:
     *   1. URLs exposed by Java entry-point owner classes the slice reaches (java-reached)
     *   2. URLs declared/changed in XML hunks (xml-declared)
     * For each unique URL, batch query for owner Java class + JS/CS/HTML callers.
     */
    private List<AffectedApi> runDiscoveryPathApis(ImpactReport pre, Map<String, List<FeatureRef>> featureIndex, Map<String, List<String>> inputToGraphFqns) {
        List<AffectedApi> out = new ArrayList<>();
        // Step 1: collect URLs + per-URL "reaching" count from the Java slice
        java.util.Map<String, Integer> reachingByUrl = new LinkedHashMap<>();
        java.util.Map<String, String>  riskByUrl     = new HashMap<>();
        // Track discovery paths per URL: how was each URL found?
        java.util.Map<String, java.util.List<ImpactReport.DiscoveryPath>> discoveryByUrl = new HashMap<>();
        // Batch-resolve shortest call chains for all (changed → entryPoint) pairs so the
        // discovery path can show every intermediate hop instead of the collapsed :CALLS*.
        java.util.Map<String, java.util.List<String>> callChainByKey = new java.util.HashMap<>();
        // Parallel map: edgeTypes[i] = the graph relationship type for the i-th hop in the chain.
        // Has chain.size()+1 entries: covers src→chain[0], chain[0]→chain[1], ..., chain[n-1]→dst.
        java.util.Map<String, java.util.List<String>> callEdgeTypesByKey = new java.util.HashMap<>();
        java.util.Map<String, OwnerAndCallers> details = new HashMap<>();
        {
            java.util.List<java.util.Map<String, Object>> pairs = new java.util.ArrayList<>();
            for (SymbolImpact s : pre.symbols()) {
                // Use graph-form FQN for the MATCH: s.fqn() may contain "?" placeholders
                // from the patch parser that don't match the resolved FQN stored in Neo4j.
                // Pass ALL graph FQNs (both short-form and fully-qualified) as dstFqns so
                // the Cypher query can find whichever version has actual CALLS edges.
                List<String> graphFqns = inputToGraphFqns.getOrDefault(s.fqn(), List.of(s.fqn()));
                List<String> dstFqns = graphFqns.isEmpty() ? java.util.List.of(s.fqn()) : new java.util.ArrayList<>(graphFqns);
                if (s.entryPointsApi().isEmpty()) continue; // no entry points → no discovery paths
                for (var ep : s.entryPointsApi()) {
                    if (ep.restUrls() == null || ep.restUrls().isEmpty()) continue;
                    details.putIfAbsent(ep.restUrls().get(0), new OwnerAndCallers(ep.owner()));
                    String key = s.fqn() + "|" + ep.fqn();
                    if (!callChainByKey.containsKey(key)) {
                        callChainByKey.put(key, java.util.List.of()); // placeholder
                        callEdgeTypesByKey.put(key, java.util.List.of());
                        pairs.add(java.util.Map.of("src", ep.fqn(), "dstFqns", dstFqns, "key", key));
                    }
                }
            }
            if (!pairs.isEmpty()) {
                try (CResult cr = writer.session().run(CypherQueries.CALL_CHAIN_QUERY,
                        java.util.Map.of("pairs", pairs))) {
                    while (cr.hasNext()) {
                        CRecord rec = cr.next();
                        String k = rec.get("k").asString("");
                        java.util.List<String> chain = rec.get("chain").asList(CValue::asString);
                        java.util.List<String> edgeTypes = rec.get("edgeTypes").asList(CValue::asString);
                        if (!k.isEmpty()) {
                            callChainByKey.put(k, chain);
                            callEdgeTypesByKey.put(k, edgeTypes);
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[runDiscoveryPathApis] call-chain query failed: " + t.getMessage());
                    t.printStackTrace();
                }
            }
        }
        for (SymbolImpact s : pre.symbols()) {
            for (var ep : s.entryPointsApi()) {
                if (ep.restUrls() == null) continue;
                for (String u : ep.restUrls()) {
                    if (u == null || u.isEmpty()) continue;
                    reachingByUrl.merge(u, 1, Integer::sum);
                    // promote risk to highest seen
                    String prev = riskByUrl.get(u);
                    if (prev == null || rank(s.risk()) > rank(prev)) riskByUrl.put(u, s.risk());
                    // Build graph path in real call direction: entry-point first, patched method last.
                    // shortestPath goes src=execute → dst=parseMacrosAdmin, so chain intermediates
                    // are [execute-side .. parseMacrosAdmin-side] (excluding both endpoints).
                    // Display: (RestEndpoint) ←:EXPOSES← execute() →:CALLS→ ... →:CALLS→ parseMacrosAdmin()
                    java.util.List<ImpactReport.PathNode> nodes = new java.util.ArrayList<>();
                    java.util.List<String> edges = new java.util.ArrayList<>();
                    String chainKey = s.fqn() + "|" + ep.fqn();
                    java.util.List<String> chain = callChainByKey.getOrDefault(chainKey, java.util.List.of());
                    // edgeTypes has chain.size()+1 entries: one per hop (src→chain[0], ..., chain[n-1]→dst)
                    java.util.List<String> edgeTypes = callEdgeTypesByKey.getOrDefault(chainKey, java.util.List.of());
                    nodes.add(new ImpactReport.PathNode("RestEndpoint", u, false));
                    edges.add(":EXPOSES");
                    nodes.add(new ImpactReport.PathNode("Method", shortMethod(ep.fqn()), false));
                    for (int _i = 0; _i < chain.size(); _i++) {
                        String edgeLabel = _i < edgeTypes.size() ? ":" + edgeTypes.get(_i) : ":CALLS";
                        edges.add(edgeLabel);
                        nodes.add(new ImpactReport.PathNode("Method", chain.get(_i) + "()", false));
                    }
                    String lastEdge = chain.isEmpty()
                        ? ":CALLS*"
                        : (edgeTypes.size() > chain.size() ? ":" + edgeTypes.get(chain.size()) : ":CALLS");
                    edges.add(lastEdge);
                    nodes.add(new ImpactReport.PathNode("Method", methodSimpleName(s.fqn()) + "()", true));
                    discoveryByUrl.computeIfAbsent(u, k -> new java.util.ArrayList<>())
                        .add(new ImpactReport.DiscoveryPath("forward-slice", nodes, edges));
                }
            }
        }
        // Step 1b: include URLs exposed by the CHANGED method's OWN owner class.
        // When the patched method IS itself an entry point (e.g. a Struts action method
        // mapped by an external XML routing config), the backward slice finds zero
        // entry points "above" it — there's nothing further back. The URL serving the
        // patched method is still affected. Catch that case here.
        //
        // Granularity filter: dispatcher-style classes (Struts WorkFlowAction with ~48
        // handler methods, one per URL via URL-last-segment routing) host MANY URLs on
        // one Java class. Without filtering, patching ONE method over-reports the other
        // 47 URLs. We filter by the :EXPOSES edge's target_method_simple_name property:
        //   - empty → legacy class-granularity (HttpServlet / REST-XML mappings where
        //     class = URL is true) — keep the URL unconditionally.
        //   - non-empty → dispatcher edge — keep only when it equals the patched
        //     method's simple name.
        java.util.List<Map<String, Object>> changedSyms = new java.util.ArrayList<>();
        java.util.Map<String, String> riskByOwner = new HashMap<>();
        for (SymbolImpact s : pre.symbols()) {
            String fqn = s.fqn();
            if (fqn == null || fqn.isEmpty()) continue;
            int paren = fqn.indexOf('(');
            String prefix = paren > 0 ? fqn.substring(0, paren) : fqn;
            int lastDot = prefix.lastIndexOf('.');
            if (lastDot <= 0) continue;
            String owner = prefix.substring(0, lastDot);
            String simple = prefix.substring(lastDot + 1);
            changedSyms.add(Map.of("owner", owner, "simple", simple));
            String prev = riskByOwner.get(owner);
            if (prev == null || rank(s.risk()) > rank(prev)) riskByOwner.put(owner, s.risk());
        }
        if (!changedSyms.isEmpty()) {
            try (CResult r = writer.session().run(
                "UNWIND $rows AS row "
              // Method-granularity: the patched method itself EXPOSES the URL
              + "OPTIONAL MATCH (m:Method {owner_fqn: row.owner, simple_name: row.simple})-[:EXPOSES]->(re1:RestEndpoint) "
              // Class-granularity fallback: whole class exposes (HttpServlet style)
              + "OPTIONAL MATCH (c:Class {fqn: row.owner})-[:EXPOSES]->(re2:RestEndpoint) "
              + "WITH row, "
              + "     [x IN collect(DISTINCT re1.url) WHERE x IS NOT NULL | {url: x, via: 'method'}] + "
              + "     [x IN collect(DISTINCT re2.url) WHERE x IS NOT NULL | {url: x, via: 'class'}] AS hits "
              + "UNWIND hits AS hit "
              + "WITH DISTINCT row, hit.url AS url, hit.via AS via "
              + "RETURN row.owner AS owner, row.simple AS methodSimple, url, via",
                Map.of("rows", changedSyms))) {
                while (r.hasNext()) {
                    CRecord rec = r.next();
                    String owner = rec.get("owner").asString("");
                    String u = rec.get("url").asString("");
                    String methodSimple = rec.get("methodSimple").asString("");
                    String via = rec.get("via").asString("class");
                    if (u.isEmpty()) continue;
                    reachingByUrl.merge(u, 1, Integer::sum);
                    String ownerRisk = riskByOwner.getOrDefault(owner, "LOW");
                    String prev = riskByUrl.get(u);
                    if (prev == null || rank(ownerRisk) > rank(prev)) riskByUrl.put(u, ownerRisk);
                    // Build graph path based on actual edge source
                    java.util.List<ImpactReport.PathNode> nodes;
                    java.util.List<String> edges;
                    String methodLabel = methodSimple.isEmpty() ? "?" : methodSimple + "()";
                    if ("method".equals(via)) {
                        // Direct: (Method:changed) -[:EXPOSES]-> (RestEndpoint)
                        nodes = java.util.List.of(
                            new ImpactReport.PathNode("Method", methodLabel, true),
                            new ImpactReport.PathNode("RestEndpoint", u, false)
                        );
                        edges = java.util.List.of(":EXPOSES");
                    } else {
                        // Via class: (Class:owner) -[:CONTAINS]-> (Method:changed), (Class:owner) -[:EXPOSES]-> (RestEndpoint)
                        String classSimple = owner.contains(".") ? owner.substring(owner.lastIndexOf('.') + 1) : owner;
                        nodes = java.util.List.of(
                            new ImpactReport.PathNode("Method", methodLabel, true),
                            new ImpactReport.PathNode("Class", classSimple, false),
                            new ImpactReport.PathNode("RestEndpoint", u, false)
                        );
                        edges = java.util.List.of(":CONTAINS", ":EXPOSES");
                    }
                    discoveryByUrl.computeIfAbsent(u, k -> new java.util.ArrayList<>())
                        .add(new ImpactReport.DiscoveryPath("owner-class-exposes", nodes, edges));
                }
            } catch (Throwable t) {
                System.err.println("[runDiscoveryPathApis] owner-class EXPOSES lookup failed: " + t.getMessage());
            }
        }


        // Step 3: union + source classification
        java.util.LinkedHashSet<String> allUrls = new java.util.LinkedHashSet<>(reachingByUrl.keySet());
        if (allUrls.isEmpty()) return List.of();

       

        

        // Step 5: assemble, dedupe, sort by risk then URL
        // List<AffectedApi> out = new ArrayList<>();
        for (String url : allUrls) {
            boolean fromJava = reachingByUrl.containsKey(url);

            String src;
            if      (fromJava ) src = "patched-method";
            else                                    src = "";
            OwnerAndCallers d = details.getOrDefault(url, EMPTY_OAC);
            // Merge cs callers from patched-CS map even if details lookup didn't include them
            String risk = riskByUrl.getOrDefault(url,  "MEDIUM" );
            List<FeatureRef> features = featureIndex.getOrDefault(d.ownerFqn, List.of());
            // Fallback 1: synthesize an Action feature from servlet-prefixed URLs.
            if (features.isEmpty() && url.startsWith("servlet:")) {
                String servletSimple = url.substring("servlet:".length());
                features = List.of(new FeatureRef("Action", servletSimple, EnglishTranslator.className(servletSimple)));
            }
            // Fallback 2: for real /RestAPI/... URLs, derive an Action label from
            // the last URL segment (the operation name). Better than "—".
            if (features.isEmpty() && url.startsWith("/")) {
                String[] segs = url.split("[/?]");
                String last = "";
                for (int i = segs.length - 1; i >= 0; i--) {
                    if (segs[i] != null && !segs[i].isEmpty()) { last = segs[i]; break; }
                }
                if (!last.isEmpty()) {
                    features = List.of(new FeatureRef("Action", last, EnglishTranslator.className(last)));
                }
            }
            // Fallback 3: for external SharePoint/Graph URLs, expose them as an "ExternalApi"
            // feature so the UI/QA tester can see *which* external system they hit.
            if (features.isEmpty() && url.startsWith("external:")) {
                String[] parts = url.split(":", 3);
                String system = parts.length > 1 ? parts[1] : "external";
                String display = "SharePoint".equalsIgnoreCase(system) ? "SharePoint CSOM"
                                : "graph".equalsIgnoreCase(system)      ? "Microsoft Graph"
                                : system;
                features = List.of(new FeatureRef("ExternalApi", system, display));
            }

            // Collect discovery paths for this URL (add XML/C# sources if applicable)
            java.util.List<ImpactReport.DiscoveryPath> paths = new java.util.ArrayList<>(
                discoveryByUrl.getOrDefault(url, List.of()));
            
            out.add(new AffectedApi(
                url, src, d.ownerFqn, simpleName(d.ownerFqn),
                reachingByUrl.getOrDefault(url, 0),
                risk,
                paths
            ));
        }
        out.sort((a, b) -> {
            int rr = Integer.compare(rank(b.risk()), rank(a.risk()));
            if (rr != 0) return rr;
            return a.url().compareTo(b.url());
        });
        return out;
    }

    private List<AffectedApi> runDiscoveryPathSchedules(ImpactReport pre, Map<String, List<FeatureRef>> featureIndex, Map<String, List<String>> inputToGraphFqns) {
        List<AffectedApi> out = new ArrayList<>();
        // Step 1: collect URLs + per-URL "reaching" count from the Java slice
        java.util.Map<String, Integer> reachingByUrl = new LinkedHashMap<>();
        java.util.Map<String, String>  riskByUrl     = new HashMap<>();
        // Track discovery paths per URL: how was each URL found?
        java.util.Map<String, java.util.List<ImpactReport.DiscoveryPath>> discoveryByUrl = new HashMap<>();
        // Batch-resolve shortest call chains for all (changed → entryPoint) pairs so the
        // discovery path can show every intermediate hop instead of the collapsed :CALLS*.
        java.util.Map<String, java.util.List<String>> callChainByKey = new java.util.HashMap<>();
        // Parallel map: edgeTypes[i] = the graph relationship type for the i-th hop in the chain.
        // Has chain.size()+1 entries: covers src→chain[0], chain[0]→chain[1], ..., chain[n-1]→dst.
        java.util.Map<String, java.util.List<String>> callEdgeTypesByKey = new java.util.HashMap<>();
        java.util.Map<String, OwnerAndCallers> details = new HashMap<>();
        {
            java.util.List<java.util.Map<String, Object>> pairs = new java.util.ArrayList<>();
            for (SymbolImpact s : pre.symbols()) {
                // Use graph-form FQN for the MATCH: s.fqn() may contain "?" placeholders
                // from the patch parser that don't match the resolved FQN stored in Neo4j.
                // Pass ALL graph FQNs (both short-form and fully-qualified) as dstFqns so
                // the Cypher query can find whichever version has actual CALLS edges.
                List<String> graphFqns = inputToGraphFqns.getOrDefault(s.fqn(), List.of(s.fqn()));
                List<String> dstFqns = graphFqns.isEmpty() ? java.util.List.of(s.fqn()) : new java.util.ArrayList<>(graphFqns);
                if (s.entryPointsSchedule().isEmpty()) continue; // no entry points → no discovery paths
                for (var ep : s.entryPointsSchedule()) {
                    if (ep.restUrls() == null || ep.restUrls().isEmpty()) continue;
                    details.putIfAbsent(ep.restUrls().get(0), new OwnerAndCallers(ep.owner()));
                    String key = s.fqn() + "|" + ep.fqn();
                    if (!callChainByKey.containsKey(key)) {
                        callChainByKey.put(key, java.util.List.of()); // placeholder
                        callEdgeTypesByKey.put(key, java.util.List.of());
                        pairs.add(java.util.Map.of("src", ep.fqn(), "dstFqns", dstFqns, "key", key));
                    }
                }
            }
            if (!pairs.isEmpty()) {
                try (CResult cr = writer.session().run(CypherQueries.CALL_CHAIN_QUERY,
                        java.util.Map.of("pairs", pairs))) {
                    while (cr.hasNext()) {
                        CRecord rec = cr.next();
                        String k = rec.get("k").asString("");
                        java.util.List<String> chain = rec.get("chain").asList(CValue::asString);
                        java.util.List<String> edgeTypes = rec.get("edgeTypes").asList(CValue::asString);
                        if (!k.isEmpty()) {
                            callChainByKey.put(k, chain);
                            callEdgeTypesByKey.put(k, edgeTypes);
                        }
                    }
                } catch (Throwable t) {
                    System.err.println("[runDiscoveryPathApis] call-chain query failed: " + t.getMessage());
                    t.printStackTrace();
                }
            }
        }
        for (SymbolImpact s : pre.symbols()) {
            for (var ep : s.entryPointsSchedule()) {
                if (ep.restUrls() == null) continue;
                for (String u : ep.restUrls()) {
                    if (u == null || u.isEmpty()) continue;
                    reachingByUrl.merge(u, 1, Integer::sum);
                    // promote risk to highest seen
                    String prev = riskByUrl.get(u);
                    if (prev == null || rank(s.risk()) > rank(prev)) riskByUrl.put(u, s.risk());
                    // Build graph path in real call direction: entry-point first, patched method last.
                    // shortestPath goes src=execute → dst=parseMacrosAdmin, so chain intermediates
                    // are [execute-side .. parseMacrosAdmin-side] (excluding both endpoints).
                    // Display: (RestEndpoint) ←:EXPOSES← execute() →:CALLS→ ... →:CALLS→ parseMacrosAdmin()
                    java.util.List<ImpactReport.PathNode> nodes = new java.util.ArrayList<>();
                    java.util.List<String> edges = new java.util.ArrayList<>();
                    String chainKey = s.fqn() + "|" + ep.fqn();
                    java.util.List<String> chain = callChainByKey.getOrDefault(chainKey, java.util.List.of());
                    // edgeTypes has chain.size()+1 entries: one per hop (src→chain[0], ..., chain[n-1]→dst)
                    java.util.List<String> edgeTypes = callEdgeTypesByKey.getOrDefault(chainKey, java.util.List.of());
                    nodes.add(new ImpactReport.PathNode("RestEndpoint", u, false));
                    edges.add(":EXPOSES");
                    nodes.add(new ImpactReport.PathNode("Method", shortMethod(ep.fqn()), false));
                    for (int _i = 0; _i < chain.size(); _i++) {
                        String edgeLabel = _i < edgeTypes.size() ? ":" + edgeTypes.get(_i) : ":CALLS";
                        edges.add(edgeLabel);
                        nodes.add(new ImpactReport.PathNode("Method", chain.get(_i) + "()", false));
                    }
                    String lastEdge = chain.isEmpty()
                        ? ":CALLS*"
                        : (edgeTypes.size() > chain.size() ? ":" + edgeTypes.get(chain.size()) : ":CALLS");
                    edges.add(lastEdge);
                    nodes.add(new ImpactReport.PathNode("Method", methodSimpleName(s.fqn()) + "()", true));
                    discoveryByUrl.computeIfAbsent(u, k -> new java.util.ArrayList<>())
                        .add(new ImpactReport.DiscoveryPath("forward-slice", nodes, edges));
                }
            }
        }
        // Step 1b: include URLs exposed by the CHANGED method's OWN owner class.
        // When the patched method IS itself an entry point (e.g. a Struts action method
        // mapped by an external XML routing config), the backward slice finds zero
        // entry points "above" it — there's nothing further back. The URL serving the
        // patched method is still affected. Catch that case here.
        //
        // Granularity filter: dispatcher-style classes (Struts WorkFlowAction with ~48
        // handler methods, one per URL via URL-last-segment routing) host MANY URLs on
        // one Java class. Without filtering, patching ONE method over-reports the other
        // 47 URLs. We filter by the :EXPOSES edge's target_method_simple_name property:
        //   - empty → legacy class-granularity (HttpServlet / REST-XML mappings where
        //     class = URL is true) — keep the URL unconditionally.
        //   - non-empty → dispatcher edge — keep only when it equals the patched
        //     method's simple name.
        java.util.List<Map<String, Object>> changedSyms = new java.util.ArrayList<>();
        java.util.Map<String, String> riskByOwner = new HashMap<>();
        for (SymbolImpact s : pre.symbols()) {
            String fqn = s.fqn();
            if (fqn == null || fqn.isEmpty()) continue;
            int paren = fqn.indexOf('(');
            String prefix = paren > 0 ? fqn.substring(0, paren) : fqn;
            int lastDot = prefix.lastIndexOf('.');
            if (lastDot <= 0) continue;
            String owner = prefix.substring(0, lastDot);
            String simple = prefix.substring(lastDot + 1);
            changedSyms.add(Map.of("owner", owner, "simple", simple));
            String prev = riskByOwner.get(owner);
            if (prev == null || rank(s.risk()) > rank(prev)) riskByOwner.put(owner, s.risk());
        }
        if (!changedSyms.isEmpty()) {
            try (CResult r = writer.session().run(
                "UNWIND $rows AS row "
              // Method-granularity: the patched method itself EXPOSES the URL
              + "OPTIONAL MATCH (m:Method {owner_fqn: row.owner, simple_name: row.simple})-[:STARTS_THREAD]->(re1:RestEndpoint) "
              // Class-granularity fallback: whole class exposes (HttpServlet style)
              + "OPTIONAL MATCH (c:Class {fqn: row.owner})-[:EXPOSES]->(re2:RestEndpoint) "
              + "WITH row, "
              + "     [x IN collect(DISTINCT re1.url) WHERE x IS NOT NULL | {url: x, via: 'method'}] + "
              + "     [x IN collect(DISTINCT re2.url) WHERE x IS NOT NULL | {url: x, via: 'class'}] AS hits "
              + "UNWIND hits AS hit "
              + "WITH DISTINCT row, hit.url AS url, hit.via AS via "
              + "RETURN row.owner AS owner, row.simple AS methodSimple, url, via",
                Map.of("rows", changedSyms))) {
                while (r.hasNext()) {
                    CRecord rec = r.next();
                    String owner = rec.get("owner").asString("");
                    String u = rec.get("url").asString("");
                    String methodSimple = rec.get("methodSimple").asString("");
                    String via = rec.get("via").asString("class");
                    if (u.isEmpty()) continue;
                    reachingByUrl.merge(u, 1, Integer::sum);
                    String ownerRisk = riskByOwner.getOrDefault(owner, "LOW");
                    String prev = riskByUrl.get(u);
                    if (prev == null || rank(ownerRisk) > rank(prev)) riskByUrl.put(u, ownerRisk);
                    // Build graph path based on actual edge source
                    java.util.List<ImpactReport.PathNode> nodes;
                    java.util.List<String> edges;
                    String methodLabel = methodSimple.isEmpty() ? "?" : methodSimple + "()";
                    if ("method".equals(via)) {
                        // Direct: (Method:changed) -[:EXPOSES]-> (RestEndpoint)
                        nodes = java.util.List.of(
                            new ImpactReport.PathNode("Method", methodLabel, true),
                            new ImpactReport.PathNode("RestEndpoint", u, false)
                        );
                        edges = java.util.List.of(":EXPOSES");
                    } else {
                        // Via class: (Class:owner) -[:CONTAINS]-> (Method:changed), (Class:owner) -[:EXPOSES]-> (RestEndpoint)
                        String classSimple = owner.contains(".") ? owner.substring(owner.lastIndexOf('.') + 1) : owner;
                        nodes = java.util.List.of(
                            new ImpactReport.PathNode("Method", methodLabel, true),
                            new ImpactReport.PathNode("Class", classSimple, false),
                            new ImpactReport.PathNode("RestEndpoint", u, false)
                        );
                        edges = java.util.List.of(":CONTAINS", ":EXPOSES");
                    }
                    discoveryByUrl.computeIfAbsent(u, k -> new java.util.ArrayList<>())
                        .add(new ImpactReport.DiscoveryPath("owner-class-exposes", nodes, edges));
                }
            } catch (Throwable t) {
                System.err.println("[runDiscoveryPathApis] owner-class EXPOSES lookup failed: " + t.getMessage());
            }
        }


        // Step 3: union + source classification
        java.util.LinkedHashSet<String> allUrls = new java.util.LinkedHashSet<>(reachingByUrl.keySet());
        if (allUrls.isEmpty()) return List.of();

       

        

        // Step 5: assemble, dedupe, sort by risk then URL
        //List<AffectedApi> out = new ArrayList<>();
        for (String url : allUrls) {
            boolean fromJava = reachingByUrl.containsKey(url);

            String src;
            if      (fromJava ) src = "patched-method";
            else                                    src = "";
            OwnerAndCallers d = details.getOrDefault(url, EMPTY_OAC);
            // Merge cs callers from patched-CS map even if details lookup didn't include them
            String risk = riskByUrl.getOrDefault(url,  "MEDIUM" );
            List<FeatureRef> features = featureIndex.getOrDefault(d.ownerFqn, List.of());
            // Fallback 1: synthesize an Action feature from servlet-prefixed URLs.
            if (features.isEmpty() && url.startsWith("servlet:")) {
                String servletSimple = url.substring("servlet:".length());
                features = List.of(new FeatureRef("Action", servletSimple, EnglishTranslator.className(servletSimple)));
            }
            // Fallback 2: for real /RestAPI/... URLs, derive an Action label from
            // the last URL segment (the operation name). Better than "—".
            if (features.isEmpty() && url.startsWith("/")) {
                String[] segs = url.split("[/?]");
                String last = "";
                for (int i = segs.length - 1; i >= 0; i--) {
                    if (segs[i] != null && !segs[i].isEmpty()) { last = segs[i]; break; }
                }
                if (!last.isEmpty()) {
                    features = List.of(new FeatureRef("Action", last, EnglishTranslator.className(last)));
                }
            }
            // Fallback 3: for external SharePoint/Graph URLs, expose them as an "ExternalApi"
            // feature so the UI/QA tester can see *which* external system they hit.
            if (features.isEmpty() && url.startsWith("external:")) {
                String[] parts = url.split(":", 3);
                String system = parts.length > 1 ? parts[1] : "external";
                String display = "SharePoint".equalsIgnoreCase(system) ? "SharePoint CSOM"
                                : "graph".equalsIgnoreCase(system)      ? "Microsoft Graph"
                                : system;
                features = List.of(new FeatureRef("ExternalApi", system, display));
            }

            // Collect discovery paths for this URL (add XML/C# sources if applicable)
            java.util.List<ImpactReport.DiscoveryPath> paths = new java.util.ArrayList<>(
                discoveryByUrl.getOrDefault(url, List.of()));
            
            out.add(new AffectedApi(
                url, src, d.ownerFqn, simpleName(d.ownerFqn),
                reachingByUrl.getOrDefault(url, 0),
                risk,
                paths
            ));
        }
        out.sort((a, b) -> {
            int rr = Integer.compare(rank(b.risk()), rank(a.risk()));
            if (rr != 0) return rr;
            return a.url().compareTo(b.url());
        });
        return out;
    }

    private static final OwnerAndCallers EMPTY_OAC = new OwnerAndCallers("");

    private record OwnerAndCallers(String ownerFqn) {}

    private static List<String> filterNonEmpty(List<String> in) {
        if (in == null || in.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) if (s != null && !s.isEmpty()) out.add(s);
        return out;
    }

    // ─── AFF: affected-schedule aggregation ─────────────────────────────────

    /**
     * Filter entry points to Scheduler / Job / TaskHandler kinds and aggregate per-owner.
     * Each schedule row also pulls its DB tables + handled task types from the graph.
     */
    private List<AffectedSchedule> runAffectedSchedules(ImpactReport pre, Map<String, List<FeatureRef>> featureIndex, List<String> reconciledMethodFqns) {
        // (a) Aggregate per-owner-class with kind + trigger-method set + reach count
        //     — derived from upstream entry points reaching changed methods.
        java.util.Map<String, ScheduleAcc> byOwner = new LinkedHashMap<>();
        for (SymbolImpact s : pre.symbols()) {
            for (var ep : s.entryPointsApi()) {
                if (ep.labels() == null) continue;
                String kind = pickScheduleKind(ep.labels());
                if (kind == null) continue;
                String owner = ep.owner() == null || ep.owner().isEmpty() ? ep.fqn() : ep.owner();
                if (owner == null || owner.isEmpty()) continue;
                ScheduleAcc acc = byOwner.computeIfAbsent(owner, k -> new ScheduleAcc(k, kind));
                acc.triggerMethods.add(methodSimpleName(ep.fqn()));
                acc.reachingSymbols.add(s.fqn());
                if (rank(s.risk()) > rank(acc.risk)) acc.risk = s.risk();
                if (acc.source == null) acc.source = "reached";
            }
        }

        // (b) NEW: include changed classes (and changed methods' owner classes) that themselves
        //     carry :ScheduledTask / :Job / :TaskHandler labels. This catches "the patch ADDS a new
        //     TaskHandler class" — which the existing slice misses because the slice walks
        //     UPSTREAM from changed methods, not at the changed nodes themselves.
        java.util.Set<String> candidateOwners = new java.util.LinkedHashSet<>();
        java.util.Map<String, String> ownerToNature = new java.util.HashMap<>(); // for source classification
        for (SymbolImpact s : pre.symbols()) {
            String fqn = s.fqn();
            if ("DELETED".equals(s.nature())) continue;
            if ("CLASS".equals(s.kind()) || "INTERFACE".equals(s.kind())) {
                candidateOwners.add(fqn);
                ownerToNature.merge(fqn, s.nature(), (a, b) -> rankNature(b) > rankNature(a) ? b : a);
            } else if ("METHOD".equals(s.kind()) || "CONSTRUCTOR".equals(s.kind())) {
                int paren = fqn.indexOf('(');
                String prefix = paren > 0 ? fqn.substring(0, paren) : fqn;
                int dot = prefix.lastIndexOf('.');
                if (dot > 0) {
                    String ownerFqn = prefix.substring(0, dot);
                    candidateOwners.add(ownerFqn);
                    ownerToNature.merge(ownerFqn, s.nature(), (a, b) -> rankNature(b) > rankNature(a) ? b : a);
                }
            }
        }
        if (!candidateOwners.isEmpty()) {
            // Match by label OR by the presence of a :HANDLES edge to :TaskType — the
            // ingest only adds the :TaskHandler sub-label to a few classes (via naming
            // heuristics on base classes), but every actual handler class gets a HANDLES
            // edge from the TaskRegistryResolver. The OR catches both populations.
            try (CResult r = writer.session().run(
                "UNWIND $owners AS o " +
                "MATCH (c:Class {fqn: o}) " +
                "OPTIONAL MATCH (c)-[:HANDLES]->(htt:TaskType) " +
                "WITH c, count(htt) AS handlesCount " +
                "WHERE 'TaskHandler' IN labels(c) OR 'TaskEngineTask' IN labels(c) OR 'DelayedTask' IN labels(c) OR 'Scheduler' IN labels(c) OR 'Job' IN labels(c) OR handlesCount > 0 " +
                "OPTIONAL MATCH (c)-[:CONTAINS]->(m:Method) WHERE 'RestEndpoint' IN labels(m) OR 'ScheduledEntryPoint' IN labels(m) " +
                "OPTIONAL MATCH (c)-[:HANDLES]->(tt:TaskType) " +
                "OPTIONAL MATCH (c)-[:CONTAINS]->(wm:Method)-[:WRITES_TABLE]->(wt:DbTable) " +
                "OPTIONAL MATCH (c)-[:CONTAINS]->(rm:Method)-[:READS_TABLE]->(rt:DbTable) " +
                "RETURN c.fqn AS owner, " +
                "       labels(c) AS lbls, " +
                "       handlesCount AS hc, " +
                "       collect(DISTINCT m.simple_name) AS triggers, " +
                "       collect(DISTINCT tt.id) AS tts, " +
                "       collect(DISTINCT wt.name) AS writes, " +
                "       collect(DISTINCT rt.name) AS reads",
                Map.of("owners", new ArrayList<>(candidateOwners)))) {
                while (r.hasNext()) {
                    CRecord rec = r.next();
                    String owner = rec.get("owner").asString("");
                    if (owner.isEmpty()) continue;
                    List<String> lbls = rec.get("lbls").asList(CValue::asString);
                    int handles = rec.get("hc").asInt(0);
                    String kind = lbls.contains("TaskHandler")    ? "TaskHandler"
                                : handles > 0                    ? "TaskHandler"   // edge-based fallback
                                : lbls.contains("TaskEngineTask") ? "TaskEngineTask"
                                : lbls.contains("DelayedTask")   ? "DelayedTask"
                                : lbls.contains("Scheduler")     ? "Scheduler"
                                : lbls.contains("Job")           ? "Job" : null;
                    if (kind == null) continue;
                    ScheduleAcc acc = byOwner.computeIfAbsent(owner, k -> new ScheduleAcc(k, kind));
                    acc.triggerMethods.addAll(filterNonEmpty(rec.get("triggers").asList(CValue::asString)));
                    acc.taskTypes.addAll(filterNonEmpty(rec.get("tts").asList(CValue::asString)));
                    acc.writes.addAll(filterNonEmpty(rec.get("writes").asList(CValue::asString)));
                    acc.reads.addAll(filterNonEmpty(rec.get("reads").asList(CValue::asString)));
                    // Classify source by ownerToNature
                    String nat = ownerToNature.get(owner);
                    String src;
                    if ("ADDED".equals(nat))          src = "patch-added";
                    else if ("DELETED".equals(nat))   src = "patch-deleted";
                    else                              src = "patch-modified";
                    // If the upstream-reaching path already set this as "reached", keep that;
                    // otherwise mark with the patch source. Reached wins because it conveys MORE info.
                    if (acc.source == null || "reached".equals(acc.source) == false) acc.source = src;
                    // Risk: schedule classes added by the patch get MEDIUM by default,
                    // promoted to HIGH if any reaching SymbolImpact was HIGH (won't happen
                    // for patch-added since they're roots, but we leave the promotion in).
                    if (rank("MEDIUM") > rank(acc.risk)) acc.risk = "MEDIUM";
                }
            } catch (Throwable t) {
                System.err.println("[SliceExecutor] schedule-from-changed-classes lookup failed: " + t.getMessage());
            }
        }

        // (c) PD-7: data-flow reach — any Scheduler/Job/TaskHandler class whose contained
        //     methods read/write a DbTable that the patched code also touches. This catches
        //     the async-coupled case the call-graph misses (e.g. WFSLATask reads
        //     WFRequestSLA, which the reject path writes; the scheduler never appears in
        //     the upstream backward slice because it's triggered by the scheduler runtime,
        //     not by any HTTP request that called the patched method). Without this, the
        //     report shows the table but loses the "WHO consumes this table" attribution.
        java.util.Set<String> patchTables = new java.util.LinkedHashSet<>();
        // for (SymbolImpact s : pre.symbols()) {
        //     if (s.writesTables() != null) patchTables.addAll(s.writesTables());
        //     if (s.readsTables()  != null) patchTables.addAll(s.readsTables());
        // }
        if (!patchTables.isEmpty()) {
            // Broadened scheduler-like detection: original logic only matched explicit
            // :ScheduledTask / :Job / :TaskHandler labels OR a :HANDLES edge. That misses
            // many SPMP classes that ARE schedulers in practice but never got the label
            // — e.g. WFSLATask (Task suffix, but doesn't implement an interface literally
            // named "Task") and WFSlaHandler (Handler suffix; called BY a scheduler).
            //
            // Additional signals we now accept:
            //   • class simple_name ends in Task / Job / Scheduler / ScheduleHandler;
            //   • class contains any Method labeled :EntryPoint (which the SchedulerResolver
            //     stamps on run / execute / executeTask / runTask methods of Task/Job/
            //     Scheduler classes — catches the cases where the class label is missing
            //     but the trigger method tag exists);
            //   • class contains a Method whose simple_name is one of the canonical
            //     trigger names (run / execute / executeTask / runTask) — works even
            //     when neither label nor EntryPoint stamping happened during ingest.
            //
            // Each match is tagged with a fallback kind so the report still attributes it
            // sensibly. The "shares-data" source value flags the link as data-flow only,
            // so QA knows it's a heuristic (not a guaranteed control-flow reach).
            try (CResult r = writer.session().run(
                "UNWIND $tables AS t " +
                "MATCH (sched:Class)-[:CONTAINS]->(m:Method) " +
                "WHERE 'Scheduler' IN labels(sched) OR 'Job' IN labels(sched) " +
                "   OR 'TaskHandler' IN labels(sched) " +
                "   OR 'TaskEngineTask' IN labels(sched) " +
                "   OR 'DelayedTask' IN labels(sched) " +
                "   OR EXISTS{(sched)-[:HANDLES]->(:TaskType)} " +
                "   OR sched.simple_name ENDS WITH 'Task' " +
                "   OR sched.simple_name ENDS WITH 'Job' " +
                "   OR sched.simple_name ENDS WITH 'Scheduler' " +
                "   OR sched.simple_name ENDS WITH 'ScheduleHandler' " +
                "   OR EXISTS{(sched)-[:CONTAINS]->(em:Method) WHERE 'RestEndpoint' IN labels(em) OR 'ScheduledEntryPoint' IN labels(em) " +
                "             AND em.simple_name IN ['run','execute','executeTask','runTask']} " +
                "   OR EXISTS{(sched)-[:CONTAINS]->(em2:Method) " +
                "             WHERE em2.simple_name IN ['run','execute','executeTask','runTask']} " +
                "OPTIONAL MATCH (m)-[wr:WRITES_TABLE]->(:DbTable {name: t}) " +
                "OPTIONAL MATCH (m)-[rr:READS_TABLE]->(:DbTable {name: t}) " +
                "WITH sched, t, count(wr) AS wn, count(rr) AS rn " +
                "WHERE wn > 0 OR rn > 0 " +
                "RETURN sched.fqn AS owner, sched.simple_name AS simple, labels(sched) AS lbls, " +
                "       collect(DISTINCT t) AS sharedTables, " +
                "       sum(wn) AS sharedWrites, sum(rn) AS sharedReads",
                Map.of("tables", new ArrayList<>(patchTables)))) {
                int dataFlowAdded = 0;
                while (r.hasNext()) {
                    CRecord rec = r.next();
                    String owner = rec.get("owner").asString("");
                    if (owner.isEmpty()) continue;
                    String simple = rec.get("simple").asString("");
                    List<String> lbls = rec.get("lbls").asList(CValue::asString);
                    // Pick the most specific kind we can derive — labels first, then suffix.
                    String kind;
                    if      (lbls.contains("TaskHandler"))         kind = "TaskHandler";
                    else if (lbls.contains("TaskEngineTask"))      kind = "TaskEngineTask";
                    else if (lbls.contains("DelayedTask"))         kind = "DelayedTask";
                    else if (lbls.contains("Scheduler"))           kind = "Scheduler";
                    else if (lbls.contains("Job"))                 kind = "Job";
                    else if (simple.endsWith("Scheduler") || simple.endsWith("ScheduleHandler"))
                                                                   kind = "Scheduler";
                    else if (simple.endsWith("Job"))               kind = "Job";
                    else if (simple.endsWith("Task"))              kind = "TaskEngineTask";  // Task class = taskengine scheduled task
                    else                                           kind = "Scheduler";
                    ScheduleAcc acc = byOwner.computeIfAbsent(owner, k -> new ScheduleAcc(k, kind));
                    // Only set source if not already attributed to a stronger signal
                    // (call-graph reach or direct patch). "shares-data" is the weakest.
                    if (acc.source == null) acc.source = "shares-data";
                    // Promote risk to MEDIUM by default for data-flow-linked schedules so
                    // they don't disappear at the bottom; HIGH if any reaching symbol is HIGH.
                    if (rank("MEDIUM") > rank(acc.risk)) acc.risk = "MEDIUM";
                    dataFlowAdded++;
                }
                if (dataFlowAdded > 0) {
                    System.out.printf("[SliceExecutor] data-flow schedule detection added %d candidate(s) (tables=%s)%n",
                        dataFlowAdded, patchTables);
                }
            } catch (Throwable t) {
                System.err.println("[SliceExecutor] data-flow schedule lookup failed: " + t.getMessage());
                t.printStackTrace();
            }
        }

        // (d) §4.1 O1 :SCHEDULES — explicit control-flow proof. Walks forward from each
        //     changed method (including transitive call hops) and lights up any
        //     :ScheduledTask the slice can reach via :SCHEDULES. Source = "schedules"
        //     — stronger than "shares-data" because it's an actual call site, not a
        //     data overlap.
        // Use the RECONCILED graph FQNs — input FQNs from the patch carry "?"
        // placeholders that don't match the graph's source-text-typed param lists.
        java.util.List<String> changedMethodFqnsForScheduleEdge =
            reconciledMethodFqns == null ? java.util.List.of() : reconciledMethodFqns;
        if (!changedMethodFqnsForScheduleEdge.isEmpty()) {
            int d = Math.max(1, Math.min(10, depth));
            try (CResult r = writer.session().run(
                "UNWIND $changed AS fqn " +
                "MATCH (m:Method {fqn: fqn}) " +
                "OPTIONAL MATCH (m)-[:CALLS|DISPATCHES_TO*0.." + d + "]->(reach:Method)-[:SCHEDULES]->(t:ScheduledTask) " +
                "WITH reach, t WHERE t IS NOT NULL " +
                "MATCH (sched:Class {fqn: t.task_class_fqn}) " +
                "RETURN sched.fqn AS owner, sched.simple_name AS simple, labels(sched) AS lbls, " +
                "       collect(DISTINCT reach.fqn) AS reachingMethods",
                Map.of("changed", changedMethodFqnsForScheduleEdge))) {
                int scheduledAdded = 0;
                while (r.hasNext()) {
                    CRecord rec = r.next();
                    String owner = rec.get("owner").asString("");
                    if (owner.isEmpty()) continue;
                    String simple = rec.get("simple").asString("");
                    List<String> lbls = rec.get("lbls").asList(CValue::asString);
                    String kind;
                    if      (lbls.contains("TaskHandler"))    kind = "TaskHandler";
                    else if (lbls.contains("TaskEngineTask")) kind = "TaskEngineTask";
                    else if (lbls.contains("DelayedTask"))    kind = "DelayedTask";
                    else if (lbls.contains("Scheduler"))      kind = "Scheduler";
                    else if (lbls.contains("Job"))            kind = "Job";
                    else if (simple.endsWith("Job"))          kind = "Job";
                    else                                      kind = "TaskEngineTask";
                    ScheduleAcc acc = byOwner.computeIfAbsent(owner, k -> new ScheduleAcc(k, kind));
                    // "schedules" is the strongest async-coupling source — control-flow proof,
                    // not heuristic. Overrides "shares-data" but yields to "reached" /
                    // "patch-*" which carry stricter call-graph reach.
                    if (acc.source == null || "shares-data".equals(acc.source)) acc.source = "schedules";
                    // Bump risk to MEDIUM (HIGH if any reaching symbol is HIGH-risk).
                    if (rank("MEDIUM") > rank(acc.risk)) acc.risk = "MEDIUM";
                    // Add the reaching methods to the reach set so the report shows a count.
                    for (var rm : rec.get("reachingMethods").asList(CValue::asString)) {
                        if (rm != null && !rm.isEmpty()) acc.reachingSymbols.add(rm);
                    }
                    scheduledAdded++;
                }
                if (scheduledAdded > 0) {
                    System.out.printf("[SliceExecutor] :SCHEDULES schedule detection added %d candidate(s)%n",
                        scheduledAdded);
                }
            } catch (Throwable t) {
                System.err.println("[SliceExecutor] :SCHEDULES lookup failed: " + t.getMessage());
            }
        }

        if (byOwner.isEmpty()) return List.of();

        // Batch graph query for DB tables + task types for each owner
        try (CResult r = writer.session().run(
            "UNWIND $owners AS o " +
            "MATCH (c:Class {fqn: o}) " +
            "OPTIONAL MATCH (c)-[:CONTAINS]->(m:Method) " +
            "OPTIONAL MATCH (m)-[:WRITES_TABLE]->(wt:DbTable) " +
            "OPTIONAL MATCH (m)-[:READS_TABLE]->(rt:DbTable) " +
            "OPTIONAL MATCH (c)-[:HANDLES]->(tt:TaskType) " +
            "RETURN o AS owner, " +
            "       collect(DISTINCT wt.name) AS writes, " +
            "       collect(DISTINCT rt.name) AS reads, " +
            "       collect(DISTINCT tt.id) AS tts",
            Map.of("owners", new ArrayList<>(byOwner.keySet())))) {
            while (r.hasNext()) {
                CRecord rec = r.next();
                String o = rec.get("owner").asString("");
                ScheduleAcc acc = byOwner.get(o);
                if (acc == null) continue;
                acc.writes.addAll(filterNonEmpty(rec.get("writes").asList(CValue::asString)));
                acc.reads.addAll(filterNonEmpty(rec.get("reads").asList(CValue::asString)));
                acc.taskTypes.addAll(filterNonEmpty(rec.get("tts").asList(CValue::asString)));
            }
        } catch (Throwable t) {
            System.err.println("[SliceExecutor] affected-schedule lookup failed: " + t.getMessage());
        }

        // Assemble + sort
        List<AffectedSchedule> out = new ArrayList<>();
        for (ScheduleAcc acc : byOwner.values()) {
            List<FeatureRef> features = new ArrayList<>(featureIndex.getOrDefault(acc.ownerFqn, List.of()));
            // For TaskHandlers without an explicit feature row (label missing on concrete class),
            // synthesize a TaskType feature from the task-type ids the slice already discovered.
            if (features.isEmpty() && !acc.taskTypes.isEmpty()) {
                for (String tt : acc.taskTypes) {
                    features.add(new FeatureRef("TaskType", tt, EnglishTranslator.className(tt)));
                }
            }
            out.add(new AffectedSchedule(
                acc.ownerFqn,
                simpleName(acc.ownerFqn),
                acc.kind,
                acc.source == null ? "reached" : acc.source,
                new ArrayList<>(acc.writes),
                new ArrayList<>(acc.reads),
                acc.reachingSymbols.size(),
                acc.risk ));
        }
        out.sort((a, b) -> {
            // patch-added/modified first, then reached; within group by risk then reach count
            int sa = scheduleSourcePriority(a.source());
            int sb = scheduleSourcePriority(b.source());
            if (sa != sb) return Integer.compare(sa, sb);
            int rr = Integer.compare(rank(b.risk()), rank(a.risk()));
            if (rr != 0) return rr;
            return Integer.compare(b.changedSymbolsReaching(), a.changedSymbolsReaching());
        });
        return out;
    }

    // ─── AFF: affected-DB-table aggregation ─────────────────────────────────

    /**
     * Promote DB-table impact out of the layer summary into a dedicated section.
     * Sources:
     *   (a) Tables declared/changed in a data-dictionary.xml hunk → "schema-changed",
     *       carry the changed column list.
     *   (b) Tables that any changed Java method writes/reads (via :WRITES_TABLE /
     *       :READS_TABLE forward slice) → "java-reached".
     * For each table we list a capped sample of writer + reader methods, and attribute
     * features by walking the writers' owner classes through the feature index.
     */
    private List<AffectedDbTable> runAffectedDbTables(ImpactReport pre, Map<String, List<FeatureRef>> featureIndex) {
        // Collect (a) schema-changed tables + their columns
        Map<String, java.util.LinkedHashSet<String>> schemaCols = new LinkedHashMap<>();
        for (PolyglotChange pc : pre.polyglotChanges()) {
            if (pc.dbTablesAffected() != null) {
                for (String t : pc.dbTablesAffected()) schemaCols.computeIfAbsent(t, k -> new java.util.LinkedHashSet<>());
            }
            if (pc.dbColumnsAffected() != null) {
                for (String tc : pc.dbColumnsAffected()) {
                    int dot = tc.indexOf('.');
                    if (dot > 0) {
                        String tbl = tc.substring(0, dot);
                        String col = tc.substring(dot + 1);
                        schemaCols.computeIfAbsent(tbl, k -> new java.util.LinkedHashSet<>()).add(col);
                    }
                }
            }
        }

        // Collect (b) tables aggregated from per-symbol writes/reads; also collect the
        // entry-point owner classes for each table so we can attribute features (a table
        // touched by Permission Management code gets the Permission Management features).
        // PD-6: split write-evidence from read-only-evidence. A table that the patched
        // code never writes (directly or transitively) — only READS via some downstream
        // chain — is regression-safety, not evidence of the fix. The "source" enum
        // surfaces this distinction so the FreeMarker template can demote it visually.
        java.util.LinkedHashSet<String> javaReachedByWrite = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> javaReachedByRead  = new java.util.LinkedHashSet<>();
        Map<String, Integer> reachByTable = new HashMap<>();
        Map<String, String> riskByTable = new HashMap<>();
        Map<String, java.util.LinkedHashSet<String>> entryPointOwnersByTable = new HashMap<>();
        for (SymbolImpact s : pre.symbols()) {
            java.util.LinkedHashSet<String> epOwners = new java.util.LinkedHashSet<>();
            for (var ep : s.entryPointsApi()) {
                String o = ep.owner() == null || ep.owner().isEmpty() ? ep.fqn() : ep.owner();
                if (o != null && !o.isEmpty()) epOwners.add(o);
            }
            // for (String t : s.writesTables()) {
            //     javaReachedByWrite.add(t);
            //     reachByTable.merge(t, 1, Integer::sum);
            //     String prev = riskByTable.get(t);
            //     if (prev == null || rank(s.risk()) > rank(prev)) riskByTable.put(t, s.risk());
            //     entryPointOwnersByTable.computeIfAbsent(t, k -> new java.util.LinkedHashSet<>()).addAll(epOwners);
            // }
            // for (String t : s.readsTables()) {
            //     javaReachedByRead.add(t);
            //     reachByTable.merge(t, 1, Integer::sum);
            //     String prev = riskByTable.get(t);
            //     if (prev == null || rank(s.risk()) > rank(prev)) riskByTable.put(t, s.risk());
            //     entryPointOwnersByTable.computeIfAbsent(t, k -> new java.util.LinkedHashSet<>()).addAll(epOwners);
            // }
        }
        // Union for the all-tables walk; the source enum below disambiguates.
        java.util.LinkedHashSet<String> javaReached = new java.util.LinkedHashSet<>(javaReachedByWrite);
        javaReached.addAll(javaReachedByRead);

        java.util.LinkedHashSet<String> allTables = new java.util.LinkedHashSet<>(schemaCols.keySet());
        allTables.addAll(javaReached);
        if (allTables.isEmpty()) return List.of();

        // Batch lookup: per table, fetch up to 10 writer + 10 reader method FQNs + their owners
        Map<String, TableDetails> details = new HashMap<>();
        try (CResult r = writer.session().run(
            "UNWIND $tables AS t " +
            "OPTIONAL MATCH (mw:Method)-[:WRITES_TABLE]->(:DbTable {name: t}) " +
            "WITH t, collect(DISTINCT {fqn: mw.fqn, owner: mw.owner_fqn})[..10] AS writers " +
            "OPTIONAL MATCH (mr:Method)-[:READS_TABLE]->(:DbTable {name: t}) " +
            "RETURN t AS table, writers, " +
            "       collect(DISTINCT {fqn: mr.fqn, owner: mr.owner_fqn})[..10] AS readers, " +
            "       size([(m1:Method)-[:WRITES_TABLE]->(:DbTable {name: t}) | m1]) AS writerCount, " +
            "       size([(m2:Method)-[:READS_TABLE]->(:DbTable {name: t}) | m2]) AS readerCount",
            Map.of("tables", new ArrayList<>(allTables)))) {
            while (r.hasNext()) {
                CRecord rec = r.next();
                String tbl = rec.get("table").asString("");
                if (tbl.isEmpty()) continue;
                List<CValue> wlist = rec.get("writers").asList(v -> v);
                List<CValue> rlist = rec.get("readers").asList(v -> v);
                int writerCount = rec.get("writerCount").asInt(0);
                int readerCount = rec.get("readerCount").asInt(0);
                List<String> wSamples = new ArrayList<>();
                java.util.LinkedHashSet<String> ownersForFeatures = new java.util.LinkedHashSet<>();
                for (CValue v : wlist) {
                    String fqn = v.get("fqn").asString(null);
                    String owner = v.get("owner").asString(null);
                    if (fqn != null && !fqn.isEmpty()) {
                        wSamples.add(formatMethodShort(owner, fqn));
                        if (owner != null && !owner.isEmpty()) ownersForFeatures.add(owner);
                    }
                }
                List<String> rSamples = new ArrayList<>();
                for (CValue v : rlist) {
                    String fqn = v.get("fqn").asString(null);
                    String owner = v.get("owner").asString(null);
                    if (fqn != null && !fqn.isEmpty()) {
                        rSamples.add(formatMethodShort(owner, fqn));
                        if (owner != null && !owner.isEmpty()) ownersForFeatures.add(owner);
                    }
                }
                details.put(tbl, new TableDetails(writerCount, readerCount, wSamples, rSamples, ownersForFeatures));
            }
        } catch (Throwable t) {
            System.err.println("[SliceExecutor] DB-tables aggregation failed: " + t.getMessage());
        }

        // Assemble per-table rows
        List<AffectedDbTable> out = new ArrayList<>();
        for (String tbl : allTables) {
            boolean fromSchema = schemaCols.containsKey(tbl);
            boolean fromJava   = javaReached.contains(tbl);
            boolean patchedWrites = javaReachedByWrite.contains(tbl);
            String src;
            if (fromSchema && fromJava) {
                src = "both";
            } else if (fromSchema) {
                src = "schema-changed";
            } else if (patchedWrites) {
                src = "java-reached";          // patch reaches a WRITES_TABLE site — actual evidence
            } else {
                // Patch only reads (or transitively reads) this table — no write evidence.
                // Surface as a regression-safety check, not as "fix lands here".
                src = "regression-safety";
            }
            TableDetails d = details.getOrDefault(tbl,
                new TableDetails(0, 0, List.of(), List.of(), new java.util.LinkedHashSet<>()));
            // Resolve features two ways:
            //  (1) the writers'/readers' own owner-class (rarely a feature class — usually a DB util),
            //  (2) the entry-point owners of changed methods that touch this table (the
            //      important one — surfaces "this table is used by GrantPermission, etc.").
            List<FeatureRef> feats = new ArrayList<>();
            for (String owner : d.featureOwners) {
                feats.addAll(featureIndex.getOrDefault(owner, List.of()));
            }
            for (String epOwner : entryPointOwnersByTable.getOrDefault(tbl, new java.util.LinkedHashSet<>())) {
                feats.addAll(featureIndex.getOrDefault(epOwner, List.of()));
            }
            String risk = riskByTable.getOrDefault(tbl, fromSchema ? "MEDIUM" : "LOW");
            out.add(new AffectedDbTable(
                tbl, src,
                new ArrayList<>(schemaCols.getOrDefault(tbl, new java.util.LinkedHashSet<>())),
                d.writerCount, d.readerCount,
                d.writerSamples, d.readerSamples,
                dedupeFeatures(feats, 8),
                risk
            ));
        }
        // Sort: schema-changed first (highest blast-radius), then by risk, then by name
        out.sort((a, b) -> {
            int sa = dbTableSourcePriority(a.source());
            int sb = dbTableSourcePriority(b.source());
            if (sa != sb) return Integer.compare(sa, sb);
            int rr = Integer.compare(rank(b.risk()), rank(a.risk()));
            if (rr != 0) return rr;
            return a.name().compareTo(b.name());
        });
        return out;
    }

    private static int dbTableSourcePriority(String src) {
        return switch (src == null ? "" : src) {
            case "schema-changed" -> 0;
            case "both"           -> 1;
            case "java-reached"   -> 2;
            default               -> 9;
        };
    }

    /** Reformat a method FQN "pkg.Owner.method(p1,p2)" → "Owner.method()". */
    private static String formatMethodShort(String owner, String fqn) {
        if (fqn == null) return "";
        int paren = fqn.indexOf('(');
        String prefix = paren > 0 ? fqn.substring(0, paren) : fqn;
        int dot = prefix.lastIndexOf('.');
        String methodName = dot < 0 ? prefix : prefix.substring(dot + 1);
        String ownerSimple = owner == null ? "" : simpleName(owner);
        return ownerSimple.isEmpty() ? methodName + "()" : ownerSimple + "." + methodName + "()";
    }

    private record TableDetails(
        int writerCount, int readerCount,
        List<String> writerSamples, List<String> readerSamples,
        java.util.Set<String> featureOwners
    ) {}

    private static int scheduleSourcePriority(String src) {
        return switch (src == null ? "" : src) {
            case "patch-added"    -> 0;
            case "patch-modified" -> 1;
            case "patch-deleted"  -> 2;
            case "reached"        -> 3;
            case "schedules"      -> 4;   // control-flow proof of scheduling — equal to shares-data tier
            case "shares-data"    -> 5;
            default               -> 9;
        };
    }

    private static String pickScheduleKind(List<String> labels) {
        // Priority: TaskHandler > TaskEngineTask > DelayedTask > Scheduler > Job
        if (labels.contains("TaskHandler"))    return "TaskHandler";
        if (labels.contains("TaskEngineTask")) return "TaskEngineTask";
        if (labels.contains("DelayedTask"))    return "DelayedTask";
        if (labels.contains("Scheduler"))      return "Scheduler";
        if (labels.contains("Job"))            return "Job";
        return null;
    }

    private static String methodSimpleName(String methodFqn) {
        if (methodFqn == null) return "";
        int paren = methodFqn.indexOf('(');
        String prefix = paren > 0 ? methodFqn.substring(0, paren) : methodFqn;
        int dot = prefix.lastIndexOf('.');
        return dot < 0 ? prefix : prefix.substring(dot + 1);
    }

    private static final class ScheduleAcc {
        final String ownerFqn;
        String kind;
        String source;             // "reached" | "patch-added" | "patch-modified" | "patch-deleted"
        String risk = "LOW";
        final java.util.Set<String> triggerMethods = new LinkedHashSet<>();
        final java.util.Set<String> reachingSymbols = new HashSet<>();
        final java.util.Set<String> writes = new LinkedHashSet<>();
        final java.util.Set<String> reads = new LinkedHashSet<>();
        final java.util.Set<String> taskTypes = new LinkedHashSet<>();
        ScheduleAcc(String ownerFqn, String kind) { this.ownerFqn = ownerFqn; this.kind = kind; }
    }

    private static int rankNature(String nat) {
        return switch (nat == null ? "" : nat) {
            case "ADDED"     -> 4;
            case "DELETED"   -> 3;
            case "SIGNATURE" -> 2;
            case "BODY"      -> 1;
            default          -> 0;
        };
    }

    private static int rank(String r) {
        return switch (r == null ? "" : r.toUpperCase()) {
            case "HIGH" -> 3; case "MEDIUM" -> 2; case "LOW" -> 1; default -> 0;
        };
    }

    /**
     * Graph-truth lookup of the changed method's user-visible side effects via the
     * §4.1 :SENDS_NOTIFICATION / :SENDS_EMAIL / :WRITES_AUDIT / :SCHEDULES edges. Walks
     * up to {@code depth} hops of :CALLS|:DISPATCHES_TO from the changed method to
     * pick up effects emitted by downstream methods, not just the patched one. Returns
     * an empty string when no edges resolve (which is the case until the §4.1
     * resolvers have been run on the graph) — the caller falls back to the name-
     * matching heuristic.
     */
    private String lookupGraphEffect(String changedSymbolFqn) {
        if (changedSymbolFqn == null || changedSymbolFqn.isEmpty()) return "";
        int d = Math.max(1, Math.min(10, depth));
        try (CResult r = writer.session().run(
            "MATCH (m:Method {fqn: $fqn}) " +
            "OPTIONAL MATCH (m)-[:CALLS|DISPATCHES_TO*0.." + d + "]->(reach:Method) " +
            "OPTIONAL MATCH (reach)-[:SENDS_NOTIFICATION]->(n:NotificationType) " +
            "OPTIONAL MATCH (reach)-[:SENDS_EMAIL]->(e:EmailTemplate) " +
            "OPTIONAL MATCH (reach)-[:WRITES_AUDIT]->(a:AuditCategory) " +
            "OPTIONAL MATCH (reach)-[:SCHEDULES]->(st:ScheduledTask) " +
            "OPTIONAL MATCH (reach)-[:TRANSITIONS_STATE]->(state:State) " +
            "OPTIONAL MATCH (reach)-[:TRIGGERS_ORCHESTRATION]->(orch:OrchestrationProfile) " +
            "OPTIONAL MATCH (reach)-[:USER_SCHEDULES]->(ust:ScheduledTask) " +
            "RETURN collect(DISTINCT n.id)            AS notifs, " +
            "       collect(DISTINCT e.id)            AS emails, " +
            "       collect(DISTINCT a.id)            AS audits, " +
            "       collect(DISTINCT st.task_class_fqn) AS scheds, " +
            "       collect(DISTINCT state.entity + '→' + state.to) AS states, " +
            "       collect(DISTINCT orch.id)         AS orchs, " +
            "       collect(DISTINCT ust.task_class_fqn) AS uscheds",
            Map.of("fqn", changedSymbolFqn))) {
            if (!r.hasNext()) return "";
            CRecord rec = r.next();
            List<String> parts = new java.util.ArrayList<>();
            List<String> notifs = rec.get("notifs").asList(CValue::asString).stream()
                .filter(s -> s != null && !s.isEmpty()).limit(3).toList();
            if (!notifs.isEmpty()) parts.add("notification delivery (" + String.join(", ", notifs) + ")");
            List<String> emails = rec.get("emails").asList(CValue::asString).stream()
                .filter(s -> s != null && !s.isEmpty()).limit(3).toList();
            if (!emails.isEmpty()) parts.add("email delivery (" + String.join(", ", emails) + ")");
            List<String> audits = rec.get("audits").asList(CValue::asString).stream()
                .filter(s -> s != null && !s.isEmpty()).limit(3).toList();
            if (!audits.isEmpty()) parts.add("audit log entry (" + String.join(", ", audits) + ")");
            List<String> scheds = rec.get("scheds").asList(CValue::asString).stream()
                .filter(s -> s != null && !s.isEmpty()).limit(3).toList();
            if (!scheds.isEmpty()) {
                List<String> simple = scheds.stream().map(SliceExecutor::simpleName).toList();
                parts.add("schedules " + String.join(", ", simple));
            }
            List<String> states = rec.get("states").asList(CValue::asString).stream()
                .filter(s -> s != null && !s.isEmpty() && !s.equals("→")).limit(3).toList();
            if (!states.isEmpty()) parts.add("state transitions (" + String.join(", ", states) + ")");
            List<String> orchs = rec.get("orchs").asList(CValue::asString).stream()
                .filter(s -> s != null && !s.isEmpty()).limit(3).toList();
            if (!orchs.isEmpty()) parts.add("orchestration trigger (" + String.join(", ", orchs) + ")");
            List<String> uscheds = rec.get("uscheds").asList(CValue::asString).stream()
                .filter(s -> s != null && !s.isEmpty()).limit(3).toList();
            if (!uscheds.isEmpty()) parts.add("user schedule config (" + String.join(", ", uscheds) + ")");
            return String.join(", ", parts);
        } catch (Throwable t) {
            // Don't fail the whole report just because the graph-truth lookup hit
            // something unexpected — fall back to the name-matching heuristic.
            return "";
        }
    }



    /** Split CamelCase / dotted identifiers into lowercase tokens. Filters single-char tokens. */
    private static void addCamelTokens(String s, Set<String> out) {
        if (s == null || s.isEmpty()) return;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) || c == '.' || c == '_' || c == '(' || c == ')' || c == '<' || c == '>' || c == ',' || c == ' ' || c == '?') {
                if (cur.length() > 1) out.add(cur.toString().toLowerCase());
                cur.setLength(0);
                if (Character.isUpperCase(c)) cur.append(Character.toLowerCase(c));
            } else if (Character.isLetterOrDigit(c)) {
                cur.append(c);
            } else {
                if (cur.length() > 1) out.add(cur.toString().toLowerCase());
                cur.setLength(0);
            }
        }
        if (cur.length() > 1) out.add(cur.toString().toLowerCase());
    }


    

    private static int sourcePriority(String src) {
        return switch (src == null ? "" : src) {
            case "patched"                       -> 0;
            case "calls-affected-api"            -> 1;
            case "uses-affected-component"       -> 2;
            case "renders-template-of-affected"  -> 3;
            default                              -> 9;
        };
    }

    private static String stripEmberPrefix(String path) {
        if (path == null) return "";
        int idx = path.indexOf("source/ember/app/");
        if (idx >= 0) return path.substring(idx + "source/ember/app/".length());
        return path;
    }

    private static String stripCsRootPrefix(String path) {
        if (path == null) return "";
        int idx = path.indexOf("source/c_sharp/");
        if (idx >= 0) return path.substring(idx + "source/c_sharp/".length());
        return path;
    }

    private static final class UiAcc {
        final String filePath;
        final String language;
        String role;
        String source;
        String risk = "LOW";
        final java.util.Set<String> restUrlsCalled = new LinkedHashSet<>();
        final java.util.Set<String> usesComponents = new LinkedHashSet<>();
        final java.util.Set<String> usedByTemplates = new LinkedHashSet<>();
        UiAcc(String filePath, String language, String role) {
            this.filePath = filePath; this.language = language; this.role = role;
        }
    }

    /** Cross-component aggregation — one snapshot of "what does this patch reach across all layers". */
    private LayerImpact runLayerImpact(ImpactReport pre, List<String> methodFqns,
                                       Set<String> entryPointOwners,
                                       List<PolyglotChange> polyglotChanges) {
        final int SAMPLE = 20;       


        // DB tables — aggregate from per-symbol DB lists
        Set<String> dbWrites = new TreeSet<>(), dbReads = new TreeSet<>();
        // for (SymbolImpact s : pre.symbols()) {
        //     if (s.writesTables() != null) dbWrites.addAll(s.writesTables());
        //     if (s.readsTables() != null) dbReads.addAll(s.readsTables());
        // }
        Set<String> dbAll = new TreeSet<>();
        dbAll.addAll(dbWrites); dbAll.addAll(dbReads);

        

        // DB columns count for affected tables
        int dbCols = 0;
        if (!dbAll.isEmpty()) {
            try (CResult r = writer.session().run(
                "UNWIND $tables AS t " +
                "MATCH (:DbTable {name: t})-[:HAS_COLUMN]->(c:DbColumn) " +
                "RETURN count(c) AS n",
                Map.of("tables", new ArrayList<>(dbAll)))) {
                if (r.hasNext()) dbCols = r.next().get("n").asInt(0);
            }
        }


        return new LayerImpact(
            dbAll.size(),
            dbWrites.size(),
            dbReads.size(),
            sample(dbAll, SAMPLE),
            dbCols
        );
    }

    /**
     * Expand the changed-method set with every method it overrides (transitively up the
     * inheritance chain). Lets backward-slice queries find callers of interface/parent
     * methods that resolve via virtual dispatch to a changed override.
     */
    private List<String> expandWithOverrideParents(List<String> originals) {
        if (originals.isEmpty()) return originals;
        Set<String> expanded = new LinkedHashSet<>(originals);
        try (CResult r = writer.session().run(
            "UNWIND $changed AS fqn " +
            "MATCH (m:Method {fqn: fqn}) " +
            "OPTIONAL MATCH (m)-[:OVERRIDES*1..5]->(parent:Method) " +
            "RETURN collect(DISTINCT parent.fqn) AS parents",
            Map.of("changed", originals))) {
            if (r.hasNext()) {
                r.next().get("parents").asList(v -> v.asString(null))
                    .stream().filter(s -> s != null && !s.isEmpty())
                    .forEach(expanded::add);
            }
        } catch (Throwable t) {
            System.err.println("[SliceExecutor] override-expansion failed: " + t.getMessage());
        }
        int added = expanded.size() - originals.size();
        if (added > 0) {
            System.out.printf("[analyze] virtual-dispatch expansion: %d changed -> %d after override-parents (+%d)%n",
                originals.size(), expanded.size(), added);
        }
        return new ArrayList<>(expanded);
    }

    private static <T> List<T> sample(java.util.Collection<T> all, int max) {
        if (all.isEmpty()) return List.of();
        List<T> out = new ArrayList<>(all);
        return out.size() <= max ? out : out.subList(0, max);
    }

    // ─── per-query runners ────────────────────────────────────────────

    private Map<String, ForwardRow> runForward(List<String> fqns) {
        Map<String, ForwardRow> out = new HashMap<>();
        if (fqns.isEmpty()) return out;
        String q = CypherQueries.withDepth(CypherQueries.FORWARD_REACH_FMT, depth);
        try (CResult result = writer.session().run(q, Map.of("changed", fqns))) {
            while (result.hasNext()) {
                CRecord rec = result.next();
                String fqn = rec.get("fqn").asString();
                List<String> reach = rec.get("reachable").asList(CValue::asString);
                out.put(fqn, new ForwardRow(reach));
            }
        }
        return out;
    }

    private Map<String, BackwardRow> runBackwardApi(List<String> fqns) {
        Map<String, BackwardRow> out = new HashMap<>();
        if (fqns.isEmpty()) return out;
        String q = CypherQueries.backwardEntryPoints(depth);
        try (CResult result = writer.session().run(q, Map.of("changed", fqns))) {
            while (result.hasNext()) {
                CRecord rec = result.next();
                String fqn = rec.get("fqn").asString();
                List<CValue> epValues = rec.get("entry_points").asList(v -> v);
                List<EntryPointRef> eps = new ArrayList<>(epValues.size());
                for (CValue v : epValues) {
                    String epFqn = v.get("fqn").asString(null);
                    if (epFqn == null) continue;
                    List<String> labels = v.get("labels").isNull() ? List.of() : v.get("labels").asList(CValue::asString);
                    String owner = v.get("owner").asString(null);
                    List<String> urls = v.get("rest_urls").isNull() ? List.of() : v.get("rest_urls").asList(CValue::asString);
                    eps.add(new EntryPointRef(epFqn, labels, owner, urls));
                }
                out.put(fqn, new BackwardRow(eps));
            }
        }
        return out;
    }

    private Map<String, BackwardRow> runBackwardSchedule(List<String> fqns) {
        Map<String, BackwardRow> out = new HashMap<>();
        if (fqns.isEmpty()) return out;
        String q = CypherQueries.backwardSchedule(depth);
        try (CResult result = writer.session().run(q, Map.of("changed", fqns))) {
            while (result.hasNext()) {
                CRecord rec = result.next();
                String fqn = rec.get("fqn").asString();
                List<CValue> epValues = rec.get("entry_points").asList(v -> v);
                List<EntryPointRef> eps = new ArrayList<>(epValues.size());
                for (CValue v : epValues) {
                    String epFqn = v.get("fqn").asString(null);
                    if (epFqn == null) continue;
                    List<String> labels = v.get("labels").isNull() ? List.of() : v.get("labels").asList(CValue::asString);
                    String owner = v.get("owner").asString(null);
                    // Schedule entry points reuse EntryPointRef.restUrls to carry the ScheduledTask name(s).
                    List<String> taskNames = v.get("rest_urls").isNull() ? List.of() : v.get("rest_urls").asList(CValue::asString);
                    eps.add(new EntryPointRef(epFqn, labels, owner, taskNames));
                }
                out.put(fqn, new BackwardRow(eps));
            }
        }
        return out;
    }

    /**
     * Forward-reach depth for DB-table attribution. Capped at a small constant rather
     * than tracking the unbounded slice depth: a method N hops downstream that happens
     * to read a table is only PLAUSIBLY affected by an upstream change while N stays
     * small. At 20+ hops we collect every table the application touches indirectly
     * (e.g. ADSMReports, WFRequestSLA) — none of which the user considers "affected"
     * by a one-line change in an action method's body. Empirically, 4 covers the
     * direct callee + a couple of utility wrappers (DBUtil.executeQuery → table).
     */
    private static final int DB_TABLES_REACH_CAP = 4;

    private Map<String, DbTablesRow> runDbTables(List<String> fqns) {
        Map<String, DbTablesRow> out = new HashMap<>();
        if (fqns.isEmpty()) return out;
        int dbDepth = Math.min(depth, DB_TABLES_REACH_CAP);
        String q = CypherQueries.withDepth(CypherQueries.DB_TABLES_FMT, dbDepth);
        try (CResult result = writer.session().run(q, Map.of("changed", fqns))) {
            while (result.hasNext()) {
                CRecord rec = result.next();
                out.put(
                    rec.get("fqn").asString(),
                    new DbTablesRow(
                        rec.get("reads").asList(CValue::asString),
                        rec.get("writes").asList(CValue::asString)
                    )
                );
            }
        }
        return out;
    }

    private Map<String, RiskRow> runRisk(List<String> fqns) {
        Map<String, RiskRow> out = new HashMap<>();
        if (fqns.isEmpty()) return out;
        String q = CypherQueries.withDepth(CypherQueries.RISK_FMT, depth);
        try (CResult result = writer.session().run(q, Map.of("changed", fqns))) {
            while (result.hasNext()) {
                CRecord rec = result.next();
                out.put(
                    rec.get("fqn").asString(),
                    new RiskRow(
                        rec.get("risk").asString(),
                        rec.get("entry_count").asInt(0),
                        rec.get("sensitive").asBoolean(false)
                    )
                );
            }
        }
        return out;
    }

    /** Polyglot enrichment: per owner-class, fetch JS callers, C# counterparts, .ps1 invocations. */
    private Map<String, OwnerEnrichment> runEnrichment(Set<String> ownerFqnSet) {
        if (ownerFqnSet.isEmpty()) return Map.of();
        List<String> ownerFqns = new ArrayList<>(ownerFqnSet);

        // 1) JS callers per owner via REST endpoints the owner class exposes.
        Map<String, List<String>> jsByOwner = new HashMap<>();
        Map<String, List<String>> urlsByOwner = new HashMap<>();
        try (CResult jsRes = writer.session().run(
            "UNWIND $owners AS o " +
            "OPTIONAL MATCH (m:Method {owner_fqn: o})-[:EXPOSES]->(r1:RestEndpoint) " +
            "OPTIONAL MATCH (c:Class {fqn: o})-[:EXPOSES]->(r2:RestEndpoint) " +
            "WITH o, collect(DISTINCT r1) + collect(DISTINCT r2) AS allRe " +
            "UNWIND allRe AS r " +
            "WITH DISTINCT o, r WHERE r IS NOT NULL " +
            "OPTIONAL MATCH (j:JsFile)-[:CALLS_API]->(r) " +
            "RETURN o AS owner, " +
            "       collect(DISTINCT r.url) AS urls, " +
            "       collect(DISTINCT j.simple_name) AS jsCallers",
            Map.of("owners", ownerFqns))) {
            while (jsRes.hasNext()) {
                CRecord rec = jsRes.next();
                String o = rec.get("owner").asString();
                List<String> urls = rec.get("urls").asList(CValue::asString).stream()
                    .filter(s -> s != null && !s.isEmpty()).toList();
                List<String> js = rec.get("jsCallers").asList(CValue::asString).stream()
                    .filter(s -> s != null && !s.isEmpty()).toList();
                urlsByOwner.put(o, urls);
                jsByOwner.put(o, js);
            }
        }

        // 2) .ps1 scripts invoked from any method in this owner class.
        Map<String, List<String>> psByOwner = new HashMap<>();
        try (CResult psRes = writer.session().run(
            "UNWIND $owners AS o " +
            "MATCH (c:Class {fqn: o})-[:CONTAINS]->(m:Method)-[:INVOKES_SCRIPT]->(p:PsScript) " +
            "RETURN o AS owner, collect(DISTINCT p.name) AS scripts",
            Map.of("owners", ownerFqns))) {
            while (psRes.hasNext()) {
                CRecord rec = psRes.next();
                psByOwner.put(rec.get("owner").asString(),
                    rec.get("scripts").asList(CValue::asString));
            }
        }

        // 3) C# counterparts: build candidate simple names in Java (the natural name,
        //    plus the same name stripped of "TaskHandler"/"Handler" suffix, plus that
        //    stripped name pluralised). Then a single Cypher pass finds which exist.
        Map<String, List<String>> csByOwner = new HashMap<>();
        Map<String, Set<String>> candidatesByOwner = new HashMap<>();
        Set<String> allCandidates = new HashSet<>();
        for (String o : ownerFqns) {
            String simple = simpleName(o);
            Set<String> cands = new LinkedHashSet<>();
            cands.add(simple);
            String stripped = EnglishTranslator.stripSuffix(simple);
            if (!stripped.equals(simple) && !stripped.isEmpty()) {
                cands.add(stripped);
                cands.add(stripped + "s");
            }
            candidatesByOwner.put(o, cands);
            allCandidates.addAll(cands);
        }
        if (!allCandidates.isEmpty()) {
            Set<String> existingCsNames = new HashSet<>();
            try (CResult csRes = writer.session().run(
                "UNWIND $names AS n " +
                "MATCH (cs:CsFile {simple_name: n}) " +
                "RETURN cs.simple_name AS name",
                Map.of("names", new ArrayList<>(allCandidates)))) {
                while (csRes.hasNext()) existingCsNames.add(csRes.next().get("name").asString());
            }
            for (var e : candidatesByOwner.entrySet()) {
                List<String> hits = new ArrayList<>();
                for (String cand : e.getValue()) {
                    if (existingCsNames.contains(cand) && !hits.contains(cand)) hits.add(cand);
                }
                csByOwner.put(e.getKey(), hits);
            }
        }

        // 4) HBS templates that USES_COMPONENT any JS caller of this owner's REST URLs.
        //    Walks: Method -[EXPOSES]-> RestEndpoint <-[CALLS_API]- JsFile <-[USES_COMPONENT]- HbsTemplate.
        //    (Also handles Class -[EXPOSES]-> fallback for class-granularity)
        Map<String, List<String>> hbsByOwner = new HashMap<>();
        try (CResult hbsRes = writer.session().run(
            "UNWIND $owners AS o " +
            "OPTIONAL MATCH (m:Method {owner_fqn: o})-[:EXPOSES]->(r1:RestEndpoint) " +
            "OPTIONAL MATCH (c:Class {fqn: o})-[:EXPOSES]->(r2:RestEndpoint) " +
            "WITH o, collect(DISTINCT r1) + collect(DISTINCT r2) AS allRe " +
            "UNWIND allRe AS r " +
            "WITH DISTINCT o, r WHERE r IS NOT NULL " +
            "MATCH (j:JsFile)-[:CALLS_API]->(r) " +
            "OPTIONAL MATCH (h:HbsTemplate)-[:USES_COMPONENT]->(j) " +
            "RETURN o AS owner, collect(DISTINCT h.path) AS hbs",
            Map.of("owners", ownerFqns))) {
            while (hbsRes.hasNext()) {
                CRecord rec = hbsRes.next();
                List<String> hbs = rec.get("hbs").asList(CValue::asString).stream()
                    .filter(s -> s != null && !s.isEmpty())
                    .map(SliceExecutor::trimHbsPath)
                    .distinct()
                    .toList();
                hbsByOwner.put(rec.get("owner").asString(), hbs);
            }
        }

        // Assemble
        Map<String, OwnerEnrichment> out = new HashMap<>();
        for (String o : ownerFqns) {
            out.put(o, new OwnerEnrichment(
                jsByOwner.getOrDefault(o, List.of()),
                csByOwner.getOrDefault(o, List.of()),
                psByOwner.getOrDefault(o, List.of()),
                urlsByOwner.getOrDefault(o, List.of()),
                hbsByOwner.getOrDefault(o, List.of())
            ));
        }
        return out;
    }

    /** Shorten "templates/components/foo/bar.hbs" to "foo/bar.hbs" — the leading
     *  "templates/components/" or "app/templates/" prefix is noise for a tester. */
    private static String trimHbsPath(String path) {
        if (path == null) return "";
        String[] prefixes = {
            "app/templates/components/", "templates/components/",
            "app/templates/", "templates/"
        };
        for (String p : prefixes) {
            if (path.startsWith(p)) return path.substring(p.length());
        }
        return path;
    }

    private static String simpleName(String fqn) {
        if (fqn == null) return "";
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    private CoverageSummary runCoverage(List<String> methodFqns) {
        // Always report :TestCase availability so the report can say "0 test cases ingested"
        int available;
        try (CResult r = writer.session().run("MATCH (t:TestCase) RETURN count(t) AS n")) {
            available = r.hasNext() ? r.next().get("n").asInt(0) : 0;
        } catch (Throwable t) { available = 0; }
        if (methodFqns.isEmpty() || available == 0) {
            return new CoverageSummary(available, 0, 0, List.of(), List.of());
        }

        // Recommended test cases — aggregate per TC id
        Map<String, Aggregator> agg = new HashMap<>();
        String coversQ = CypherQueries.withDepth(CypherQueries.COVERING_TEST_CASES_FMT, depth);
        try (CResult coversRes = writer.session().run(coversQ, Map.of("changed", methodFqns))) {
            while (coversRes.hasNext()) {
                CRecord rec = coversRes.next();
                List<CValue> list = rec.get("covers").asList(v -> v);
                for (CValue v : list) {
                    String id = v.get("id").asString(null);
                    if (id == null) continue;
                    Aggregator a = agg.computeIfAbsent(id, k -> new Aggregator());
                    a.title = v.get("title").asString(a.title);
                    a.area  = v.get("area").asString(a.area);
                    a.confidence = Math.max(a.confidence, v.get("confidence").asDouble(0));
                    a.hits += v.get("hits").asInt(0);
                }
            }
        }
        List<CoveringTestCase> recommended = new ArrayList<>();
        agg.forEach((id, a) -> recommended.add(new CoveringTestCase(id, a.title, a.area, a.confidence, a.hits)));
        recommended.sort((x, y) -> Integer.compare(y.reachedEntryPoints(), x.reachedEntryPoints()));

        // Coverage gaps
        List<CoverageGap> gaps = new ArrayList<>();
        String gapsQ = CypherQueries.withDepth(CypherQueries.COVERAGE_GAPS_FMT, depth);
        try (CResult gapsRes = writer.session().run(gapsQ, Map.of("changed", methodFqns))) {
            while (gapsRes.hasNext()) {
                CRecord rec = gapsRes.next();
                String fqn = rec.get("uncovered").asString(null);
                if (fqn == null) continue;
                List<String> labels = rec.get("labels").isNull() ? List.of() : rec.get("labels").asList(CValue::asString);
                String owner = rec.get("owner").asString(null);
                List<String> reaches = rec.get("reaching_changes").isNull()
                    ? List.of() : rec.get("reaching_changes").asList(CValue::asString);
                gaps.add(new CoverageGap(fqn, labels, owner, reaches));
            }
        }

        return new CoverageSummary(available, recommended.size(), gaps.size(), recommended, gaps);
    }

    private static final class Aggregator {
        String title = "";
        String area  = "";
        double confidence = 0;
        int hits = 0;
    }

    private record ForwardRow(List<String> reachable) {}
    private record BackwardRow(List<EntryPointRef> entryPoints) {}
    private record DbTablesRow(List<String> reads, List<String> writes) {}
    private record RiskRow(String risk, int entryCount, boolean sensitive) {}

    // ─── FQN reconciliation ──────────────────────────────────────────────
    //
    // Patch-side and ingest-side FQNs can differ because of:
    //  - lite-mode dep repos store source-text param types (Long, HttpServletRequest)
    //    while the analyze SymbolSolver resolves them to FQNs (java.lang.Long, ?)
    //  - some param positions land as `?` on one side but as a real FQN on the other
    //  - generics formatting can vary (ArrayList<Vector> vs java.util.ArrayList<java.util.Vector>)
    //
    // We resolve each input FQN to all :Method nodes in the graph that share the same
    // owner_fqn + simple_name + param-count. Param-count is a strong-enough filter to
    // distinguish overloads while tolerating the param-type-string variance.

    /**
     * Resolve each input FQN to all matching {@code :Method} graph nodes. Returns a
     * preserved-order map: input FQN → list of graph FQNs. Inputs that match a real
     * node exactly are returned with a single-element list (themselves); inputs that
     * don't match exactly fall through to a fuzzy lookup.
     */
    private Map<String, List<String>> resolveFuzzyMethodFqns(List<String> inputFqns) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (inputFqns == null || inputFqns.isEmpty()) return out;

        // First pass: see which inputs match an existing :Method.fqn exactly.
        Set<String> matchedExact = new HashSet<>();
        try (CResult r = writer.session().run(
            "UNWIND $fqns AS f " +
            "OPTIONAL MATCH (m:Method {fqn: f}) " +
            "WITH f, m WHERE m IS NOT NULL " +
            "RETURN collect(DISTINCT f) AS hits",
            Map.of("fqns", inputFqns))) {
            if (r.hasNext()) {
                r.next().get("hits").asList(CValue::asString).stream()
                    .filter(s -> s != null && !s.isEmpty()).forEach(matchedExact::add);
            }
        } catch (Throwable t) {
            System.err.println("[SliceExecutor] exact-match phase failed: " + t.getMessage());
        }

        // Build the fuzzy-lookup parameter list for the unmatched inputs.
        List<Map<String, Object>> fuzzyKeys = new ArrayList<>();
        Map<String, String> fuzzyKeyToInput = new HashMap<>();
        for (String in : inputFqns) {
            if (matchedExact.contains(in)) { out.put(in, List.of(in)); continue; }
            FqnParts parts = parseFqnParts(in);
            if (parts == null) { out.put(in, List.of()); continue; }
            String key = parts.ownerFqn + "##" + parts.simpleName + "##" + parts.paramCount;
            fuzzyKeyToInput.put(key, in);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("owner", parts.ownerFqn);
            row.put("name", parts.simpleName);
            row.put("paramCount", parts.paramCount);
            row.put("key", key);
            fuzzyKeys.add(row);
        }

        if (!fuzzyKeys.isEmpty()) {
            // For each (owner, name, paramCount), match candidate :Methods and pick those whose
            // FQN's "(...)" payload has the same comma-separated-parts count. Empty payload = 0 params.
            try (CResult r = writer.session().run(
                "UNWIND $keys AS k " +
                "MATCH (m:Method {owner_fqn: k.owner, simple_name: k.name}) " +
                // The param-count check is done in Cypher to keep the round-trip count low.
                // We compute it by scanning the substring between '(' and ')' in m.fqn.
                "WITH k, m, " +
                "     CASE WHEN m.fqn ENDS WITH '()' THEN 0 " +
                "          ELSE size(split(substring(m.fqn, " +
                "                          size(split(m.fqn, '(')[0]) + 1, " +
                "                          size(m.fqn) - size(split(m.fqn, '(')[0]) - 2), ',')) " +
                "     END AS pc " +
                "WHERE pc = k.paramCount " +
                "RETURN k.key AS key, collect(DISTINCT m.fqn) AS fqns",
                Map.of("keys", fuzzyKeys))) {
                while (r.hasNext()) {
                    CRecord rec = r.next();
                    String key = rec.get("key").asString();
                    List<String> fqns = rec.get("fqns").asList(CValue::asString).stream()
                        .filter(s -> s != null && !s.isEmpty()).collect(Collectors.toList());
                    String input = fuzzyKeyToInput.get(key);
                    if (input != null) out.put(input, fqns);
                }
            } catch (Throwable t) {
                System.err.println("[SliceExecutor] fuzzy-match phase failed: " + t.getMessage());
            }
        }

        // Make sure every input has an entry (even an empty list) for downstream code.
        for (String in : inputFqns) {
            out.computeIfAbsent(in, k -> List.of());
        }
        return out;
    }

    private record FqnParts(String ownerFqn, String simpleName, int paramCount) {}

    /**
     * Parse an FQN of the form {@code owner.simple(p1,p2,...)} into its parts.
     * The owner segment is everything before the last "." preceding "(". Param-count
     * is computed by splitting the "(...)" payload on top-level commas (commas inside
     * generic angle-brackets are NOT counted).
     */
    private static FqnParts parseFqnParts(String fqn) {
        if (fqn == null) return null;
        int paren = fqn.indexOf('(');
        if (paren < 0) return null;
        String before = fqn.substring(0, paren);
        int lastDot = before.lastIndexOf('.');
        if (lastDot < 0) return null;
        String owner = before.substring(0, lastDot);
        String name = before.substring(lastDot + 1);
        int closeParen = fqn.lastIndexOf(')');
        String paramStr = closeParen > paren + 1 ? fqn.substring(paren + 1, closeParen) : "";
        int paramCount = countTopLevelParams(paramStr);
        return new FqnParts(owner, name, paramCount);
    }

    /** Count comma-separated top-level params (ignoring commas inside {@code <...>}). */
    private static int countTopLevelParams(String s) {
        if (s == null || s.isEmpty()) return 0;
        int depth = 0;
        int count = 1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) count++;
        }
        return count;
    }

    /** Merge per-graph-fqn forward results by their associated input FQN. */
    private static Map<String, ForwardRow> aggregateForwardByInput(
            Map<String, ForwardRow> raw, Map<String, List<String>> graphToInputs) {
        Map<String, ForwardRow> out = new HashMap<>();
        for (var e : raw.entrySet()) {
            List<String> inputs = graphToInputs.getOrDefault(e.getKey(), List.of(e.getKey()));
            for (String in : inputs) {
                ForwardRow cur = out.get(in);
                if (cur == null) {
                    out.put(in, new ForwardRow(new ArrayList<>(e.getValue().reachable)));
                } else {
                    Set<String> u = new LinkedHashSet<>(cur.reachable);
                    u.addAll(e.getValue().reachable);
                    out.put(in, new ForwardRow(new ArrayList<>(u)));
                }
            }
        }
        return out;
    }

    private static Map<String, BackwardRow> aggregateBackwardByInput(
            Map<String, BackwardRow> raw, Map<String, List<String>> graphToInputs) {
        Map<String, BackwardRow> out = new HashMap<>();
        for (var e : raw.entrySet()) {
            List<String> inputs = graphToInputs.getOrDefault(e.getKey(), List.of(e.getKey()));
            for (String in : inputs) {
                BackwardRow cur = out.get(in);
                if (cur == null) {
                    out.put(in, new BackwardRow(new ArrayList<>(e.getValue().entryPoints)));
                } else {
                    // Dedupe by entry-point FQN.
                    Map<String, EntryPointRef> byFqn = new LinkedHashMap<>();
                    for (var ep : cur.entryPoints) byFqn.putIfAbsent(ep.fqn(), ep);
                    for (var ep : e.getValue().entryPoints) byFqn.putIfAbsent(ep.fqn(), ep);
                    out.put(in, new BackwardRow(new ArrayList<>(byFqn.values())));
                }
            }
        }
        return out;
    }

    private static Map<String, DbTablesRow> aggregateDbTablesByInput(
            Map<String, DbTablesRow> raw, Map<String, List<String>> graphToInputs) {
        Map<String, DbTablesRow> out = new HashMap<>();
        for (var e : raw.entrySet()) {
            List<String> inputs = graphToInputs.getOrDefault(e.getKey(), List.of(e.getKey()));
            for (String in : inputs) {
                DbTablesRow cur = out.get(in);
                if (cur == null) {
                    out.put(in, new DbTablesRow(
                        new ArrayList<>(e.getValue().reads),
                        new ArrayList<>(e.getValue().writes)));
                } else {
                    Set<String> reads = new LinkedHashSet<>(cur.reads);
                    reads.addAll(e.getValue().reads);
                    Set<String> writes = new LinkedHashSet<>(cur.writes);
                    writes.addAll(e.getValue().writes);
                    out.put(in, new DbTablesRow(new ArrayList<>(reads), new ArrayList<>(writes)));
                }
            }
        }
        return out;
    }

    private static Map<String, RiskRow> aggregateRiskByInput(
            Map<String, RiskRow> raw, Map<String, List<String>> graphToInputs) {
        Map<String, RiskRow> out = new HashMap<>();
        for (var e : raw.entrySet()) {
            List<String> inputs = graphToInputs.getOrDefault(e.getKey(), List.of(e.getKey()));
            for (String in : inputs) {
                RiskRow cur = out.get(in);
                RiskRow incoming = e.getValue();
                if (cur == null) { out.put(in, incoming); continue; }
                // Keep the most severe risk + accumulate entry-count.
                int maxRisk = Math.max(riskRank(cur.risk), riskRank(incoming.risk));
                String mergedRisk = riskFromRank(maxRisk);
                out.put(in, new RiskRow(
                    mergedRisk,
                    cur.entryCount + incoming.entryCount,
                    cur.sensitive || incoming.sensitive));
            }
        }
        return out;
    }

    private static int riskRank(String r) {
        return switch (r == null ? "" : r) { case "HIGH" -> 3; case "MEDIUM" -> 2; case "LOW" -> 1; default -> 0; };
    }
    private static String riskFromRank(int r) {
        return switch (r) { case 3 -> "HIGH"; case 2 -> "MEDIUM"; default -> "LOW"; };
    }
}
