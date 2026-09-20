package io.spmp.impact.report;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateExceptionHandler;
import io.spmp.impact.model.ImpactReport;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders an {@link ImpactReport} to a single-file HTML using FreeMarker.
 * The template lives at {@code src/main/resources/templates/report.ftl} and is loaded
 * from the classpath at runtime so this works inside the shaded fat-jar.
 */
public final class HtmlReportRenderer {

    private static final Configuration CFG = buildConfig();

    private HtmlReportRenderer() {}

    private static Configuration buildConfig() {
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setClassForTemplateLoading(HtmlReportRenderer.class, "/templates");
        cfg.setDefaultEncoding("UTF-8");
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        cfg.setFallbackOnNullLoopVariable(false);
        return cfg;
    }

    public static String renderToString(ImpactReport report) {
        return renderToString(report, false);
    }

    public static String renderToString(ImpactReport report, boolean showCoverage) {
        try (StringWriter sw = new StringWriter()) {
            Template tpl = CFG.getTemplate("report.ftl");
            tpl.process(toModel(report, showCoverage), sw);
            return sw.toString();
        } catch (Exception e) {
            throw new RuntimeException("FreeMarker render failed: " + e.getMessage(), e);
        }
    }

    public static void renderToFile(ImpactReport report, Path out) throws IOException {
        renderToFile(report, out, false);
    }

    public static void renderToFile(ImpactReport report, Path out, boolean showCoverage) throws IOException {
        Files.writeString(out, renderToString(report, showCoverage), StandardCharsets.UTF_8);
    }

    private static java.util.List<Map<String, Object>> featuresToMap(java.util.List<ImpactReport.FeatureRef> feats) {
        if (feats == null || feats.isEmpty()) return java.util.List.of();
        return feats.stream().map(f -> {
            Map<String, Object> fm = new HashMap<>();
            fm.put("kind", f.kind());
            fm.put("id", f.id());
            fm.put("displayName", f.displayName());
            return fm;
        }).toList();
    }

    private static java.util.List<Map<String, Object>> discoveryToMap(java.util.List<ImpactReport.DiscoveryPath> paths) {
        if (paths == null || paths.isEmpty()) return java.util.List.of();
        return paths.stream().map(dp -> {
            Map<String, Object> dpm = new HashMap<>();
            dpm.put("pattern", dp.pattern());
            // Convert PathNodes to list of maps
            java.util.List<Map<String, Object>> nodeList = dp.nodes().stream().map(n -> {
                Map<String, Object> nm = new HashMap<>();
                nm.put("nodeType", n.nodeType());
                nm.put("name", n.name() == null ? "" : n.name());
                nm.put("highlighted", n.highlighted());
                return nm;
            }).toList();
            dpm.put("nodes", nodeList);
            dpm.put("edges", dp.edges());
            return dpm;
        }).toList();
    }

    /**
     * Per-feature aggregation: every URL / DB table / schedule / UI component the patch
     * affects, grouped by {@link ImpactReport.FeatureRef} (Task or Action display name).
     * Returns a list of maps that FreeMarker iterates in {@code report.ftl}. Records
     * without explicit feature attribution fall into a synthetic "Unclassified" bucket
     * so they're still visible.
     */
    

    private static java.util.List<FeatureAggregate> pickBuckets(
            java.util.LinkedHashMap<String, FeatureAggregate> store,
            java.util.List<ImpactReport.FeatureRef> feats) {
        if (feats == null || feats.isEmpty()) {
            return java.util.List.of(store.computeIfAbsent("_unclassified",
                k -> new FeatureAggregate("Unclassified", "(no feature attribution)")));
        }
        java.util.List<FeatureAggregate> out = new java.util.ArrayList<>(feats.size());
        for (var f : feats) {
            String key = f.kind() + "::" + f.displayName();
            out.add(store.computeIfAbsent(key, k -> new FeatureAggregate(f.kind(), f.displayName())));
        }
        return out;
    }

    private static int kindPriority(String kind) {
        return switch (kind == null ? "" : kind) {
            case "TaskType"   -> 0;
            case "Action"     -> 1;
            case "Servlet"    -> 2;
            case "Scheduler"  -> 3;
            case "Job"        -> 4;
            case "Report"     -> 5;
            case "RestEndpoint" -> 6;
            default           -> 9;
        };
    }

    /**
     * Pre-group the flat pivot rows by their {@code kind} field. Emits a list of
     * group maps in the same priority order used for the row sort, so the HTML
     * template can iterate without extra logic. Each group carries totals so the
     * template can show "Actions — 19 features (19 URLs · 30 DB tables · ...)".
     */
    private static java.util.List<Map<String, Object>> groupByKind(java.util.List<Map<String, Object>> flat) {
        java.util.LinkedHashMap<String, java.util.List<Map<String, Object>>> store = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : flat) {
            String kind = String.valueOf(row.get("kind"));
            store.computeIfAbsent(kind, k -> new java.util.ArrayList<>()).add(row);
        }
        java.util.List<Map<String, Object>> groups = new java.util.ArrayList<>(store.size());
        for (var entry : store.entrySet()) {
            Map<String, Object> g = new HashMap<>();
            g.put("kind", entry.getKey());
            g.put("rows", entry.getValue());
            g.put("featureCount", entry.getValue().size());
            int urlT = 0, dbT = 0, schT = 0, uiT = 0;
            for (Map<String, Object> r : entry.getValue()) {
                urlT += (int) r.getOrDefault("urlCount", 0);
                dbT += (int) r.getOrDefault("dbTableCount", 0);
                schT += (int) r.getOrDefault("scheduleCount", 0);
                uiT += (int) r.getOrDefault("uiComponentCount", 0);
            }
            g.put("urlTotal", urlT);
            g.put("dbTableTotal", dbT);
            g.put("scheduleTotal", schT);
            g.put("uiComponentTotal", uiT);
            groups.add(g);
        }
        return groups;
    }

    private static final class FeatureAggregate {
        final String kind;
        final String displayName;
        final java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashSet<String> dbTables = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashSet<String> schedules = new java.util.LinkedHashSet<>();
        final java.util.LinkedHashSet<String> uiComponents = new java.util.LinkedHashSet<>();
        String worstRisk = "LOW";
        FeatureAggregate(String kind, String displayName) {
            this.kind = kind; this.displayName = displayName;
        }
        void promoteRisk(String r) { if (rank(r) > rank(worstRisk)) worstRisk = r; }
        static int rank(String r) {
            return switch (r == null ? "" : r) { case "HIGH" -> 3; case "MEDIUM" -> 2; case "LOW" -> 1; default -> 0; };
        }
    }

    /**
     * Convert the report to a generic Map model so FreeMarker can navigate it without
     * needing custom ObjectWrappers for our records.
     */
    private static Map<String, Object> toModel(ImpactReport report, boolean showCoverage) {
        Map<String, Object> m = new HashMap<>();
        m.put("generated", report.generated());
        m.put("diffSource", report.diffSource());
        m.put("repoPath", report.repoPath());
        m.put("totalChangedSymbols", report.totalChangedSymbols());
        m.put("totalEntryPointsReachedApi", report.totalEntryPointsApi());
        m.put("totalEntryPointsReachedSchedule", report.totalEntryPointsSchedule());
       // m.put("totalForwardReach", report.totalForwardReach());
        m.put("kindCounts", report.kindCounts());
        m.put("riskCounts", report.riskCounts());
        m.put("overallRisk", report.overallRisk());
        // Cross-component impact aggregation (Impact by Layer section)
        if (report.layerImpact() != null) {
            var li = report.layerImpact();
            Map<String, Object> lm = new HashMap<>();
            lm.put("dbTablesTouched",             li.dbTablesTouched());
            lm.put("dbTablesWritten",             li.dbTablesWritten());
            lm.put("dbTablesRead",                li.dbTablesRead());
            lm.put("dbTableNames",                li.dbTableNames());
            lm.put("dbColumnsOnAffectedTables",   li.dbColumnsOnAffectedTables());
            m.put("layerImpact", lm);
        }

        // AFF: pre-aggregated affected-API list
        if (report.apisAffected() != null && !report.apisAffected().isEmpty()) {
            m.put("apisAffected", report.apisAffected().stream().map(a -> {
                Map<String, Object> am = new HashMap<>();
                am.put("url", a.url());
                am.put("source", a.source());
                am.put("ownerClassFqn", a.ownerClassFqn() == null ? "" : a.ownerClassFqn());
                am.put("ownerSimpleName", a.ownerSimpleName() == null ? "" : a.ownerSimpleName());
                am.put("changedSymbolsReaching", a.changedSymbolsReaching());
                am.put("risk", a.risk());
                // P1: request params read by the dispatcher target method (one row per
                // (name, value) tuple — e.g. (actionName, reject)).
                java.util.List<Map<String, Object>> params = new java.util.ArrayList<>();
                am.put("paramsRead", params);
                am.put("discoveryPaths", discoveryToMap(a.discoveryPaths()));
                return am;
            }).toList());
        }

        // schedulesAffected is intentionally NOT fed to the template. Schedule impact is
        // surfaced via the per-symbol "Schedule Entry Points" column (EntryPointRef). Leaving
        // this out keeps the template's `<#if schedulesAffected??>` block dormant (it renders
        // `a.url`, which is null for ScheduledTask rows and would abort the render).
         if (report.schedulesAffected() != null && !report.schedulesAffected().isEmpty()) {
            m.put("schedulesAffected", report.schedulesAffected().stream().map(a -> {
                Map<String, Object> am = new HashMap<>();
                am.put("name", a.url());
                am.put("source", a.source());
                am.put("ownerClassFqn", a.ownerClassFqn() == null ? "" : a.ownerClassFqn());
                am.put("ownerSimpleName", a.ownerSimpleName() == null ? "" : a.ownerSimpleName());
                am.put("changedSymbolsReaching", a.changedSymbolsReaching());
                am.put("risk", a.risk());
                java.util.List<Map<String, Object>> params = new java.util.ArrayList<>();
                am.put("paramsRead", params);
                am.put("discoveryPaths", discoveryToMap(a.discoveryPaths()));
                return am;
            }).toList());
         }
        // AFF: pre-aggregated affected-schedule list
        // if (report.schedulesAffected() != null && !report.schedulesAffected().isEmpty()) {
        //     m.put("schedulesAffected", report.schedulesAffected().stream().map(a -> {
        //         Map<String, Object> am = new HashMap<>();
        //         am.put("ownerClassFqn", a.ownerClassFqn() == null ? "" : a.ownerClassFqn());
        //         am.put("ownerSimpleName", a.ownerSimpleName() == null ? "" : a.ownerSimpleName());
        //         am.put("kind", a.kind());
        //         am.put("source", a.source());
        //         am.put("dbTablesWritten", a.dbTablesWritten());
        //         am.put("dbTablesRead", a.dbTablesRead());
        //         am.put("changedSymbolsReaching", a.changedSymbolsReaching());
        //         am.put("risk", a.risk());
        //         return am;
        //     }).toList());
        // }
        // AFF: pre-aggregated affected-DB-tables list
        if (report.dbTablesAffected() != null && !report.dbTablesAffected().isEmpty()) {
            m.put("dbTablesAffected", report.dbTablesAffected().stream().map(t -> {
                Map<String, Object> tm = new HashMap<>();
                tm.put("name", t.name());
                tm.put("source", t.source());
                tm.put("changedColumns", t.changedColumns());
                tm.put("writerCount", t.writerCount());
                tm.put("readerCount", t.readerCount());
                tm.put("sampleWriters", t.sampleWriters());
                tm.put("sampleReaders", t.sampleReaders());
                tm.put("features", featuresToMap(t.features()));
                tm.put("risk", t.risk());
                return tm;
            }).toList());
        }
        

        m.put("symbols", report.symbols().stream().map(s -> {
            Map<String, Object> sm = new HashMap<>();
            sm.put("fqn", s.fqn());
            sm.put("kind", s.kind());
            sm.put("nature", s.nature());
            sm.put("filePath", s.filePath());
            sm.put("startLine", s.startLine());
            sm.put("endLine", s.endLine());
            sm.put("risk", s.risk());
            sm.put("sensitivePackage", s.sensitivePackage());
            sm.put("entryPointCount", s.entryPointCount());
            sm.put("entryPointsApi", s.entryPointsApi().stream().map(ep -> {
                Map<String, Object> em = new HashMap<>();
                em.put("fqn", ep.fqn());
                em.put("labels", ep.labels());
                em.put("owner", ep.owner() == null ? "" : ep.owner());
                em.put("restUrls", ep.restUrls());
                return em;
            }).toList());
            sm.put("entryPointsSchedule", s.entryPointsSchedule().stream().map(ep -> {
                Map<String, Object> em = new HashMap<>();
                em.put("fqn", ep.fqn());
                em.put("labels", ep.labels());
                em.put("owner", ep.owner() == null ? "" : ep.owner());
                em.put("restUrls", ep.restUrls());
                return em;
            }).toList());
            // sm.put("forwardReach", s.forwardReach());
            // sm.put("readsTables", s.readsTables());
            // sm.put("writesTables", s.writesTables());
            return sm;
        }).toList());
        return m;
    }
}
