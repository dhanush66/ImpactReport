package io.spmp.impact.diff;

import io.spmp.impact.graph.Neo4jWriter;
import io.spmp.impact.graph.txn.CypherClient.CRecord;
import io.spmp.impact.graph.txn.CypherClient.CResult;
import io.spmp.impact.graph.txn.CypherClient.CValue;
import io.spmp.impact.model.DiffModels.FileChange;
import io.spmp.impact.model.ImpactReport.HunkSymbol;
import io.spmp.impact.model.ImpactReport.PolyglotChange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a non-Java {@link FileChange} into a {@link PolyglotChange} that captures both
 * the file-level change and graph-resolved downstream effects.
 *
 * <p>Per language we do:
 * <ul>
 *   <li><b>JS</b>: look up the {@code :JsFile} node, walk {@code :CALLS_API} → {@code :RestEndpoint},
 *       and back through {@code :EXPOSES} ← {@code :Class} to name the Java owners.</li>
 *   <li><b>HBS</b>: file-level only (Ember templates aren't ingested yet — flagged for v3).</li>
 *   <li><b>C#</b>: walk {@code :CALLS_API} and {@code :INVOKES_SCRIPT} from the {@code :CsFile} node.</li>
 *   <li><b>XML</b>: re-read the file from disk, extract the &lt;table&gt; / &lt;column&gt; / URL
 *       elements whose source line falls inside a hunk. Three special files get
 *       structured parsing — data-dictionary.xml, ADSProductAPIS.xml, SPMPServletActions.xml —
 *       the rest get file-level treatment.</li>
 *   <li><b>properties / json / other</b>: file-level.</li>
 * </ul>
 *
 * <p><b>Risk:</b> escalates to HIGH if anything downstream (Java class or DB table named
 * in a hunk) is marked HIGH in the wider impact slice. Defaults to MEDIUM for any non-empty
 * downstream set; LOW otherwise. Final risk is recomputed by SliceExecutor once it
 * knows the Java slice's outcome.
 */
public final class PolyglotResolver {

    private final Neo4jWriter writer;
    private final Path repoRoot;

    public PolyglotResolver(Neo4jWriter writer, Path repoRoot) {
        this.writer = writer;
        this.repoRoot = repoRoot;
    }

    public PolyglotChange resolve(FileChange fc) {
        String newPath = fc.newPath();
        String lang = languageOf(newPath);
        int hunkCount = fc.hunkRanges() == null ? 0 : fc.hunkRanges().size();

        switch (lang) {
            case "JS":         return resolveJs(fc, hunkCount);
            case "HBS":        return resolveHbs(fc, hunkCount);
            case "CS":         return resolveCs(fc, hunkCount);
            case "XML":        return resolveXml(fc, hunkCount);
            case "PROPERTIES": return base(fc, lang, "Properties", hunkCount, "LOW");
            case "JSON":       return base(fc, lang, "Json",       hunkCount, "LOW");
            default:           return base(fc, "OTHER", "",        hunkCount, "LOW");
        }
    }

    // ─── JS (Ember) ───────────────────────────────────────────────────

    private PolyglotChange resolveJs(FileChange fc, int hunkCount) {
        String path = fc.newPath();
        String graphPath = stripEmberAppPrefix(path);
        List<String> urls = new ArrayList<>();
        List<String> javaClasses = new ArrayList<>();
        String role = "";
        // PD-4: who already imports this JS file? + which HBS templates render its component?
        List<String> jsImporters = new ArrayList<>();
        List<String> hbsUsers = new ArrayList<>();

        try (CResult r = writer.session().run(
            "MATCH (j:JsFile {path: $path}) " +
            "OPTIONAL MATCH (j)-[:CALLS_API]->(re:RestEndpoint) " +
            "OPTIONAL MATCH (cls:Class)-[:EXPOSES]->(re) " +
            "OPTIONAL MATCH (importer:JsFile)-[:IMPORTS]->(j) " +
            "OPTIONAL MATCH (h:HbsTemplate)-[:USES_COMPONENT]->(j) " +
            "RETURN j.role AS role, " +
            "       collect(DISTINCT re.url) AS urls, " +
            "       collect(DISTINCT cls.fqn) AS classes, " +
            "       collect(DISTINCT importer.path) AS importers, " +
            "       collect(DISTINCT h.path) AS hbsUsers",
            Map.of("path", graphPath))) {
            if (r.hasNext()) {
                CRecord rec = r.next();
                role = rec.get("role").asString("");
                urls.addAll(rec.get("urls").asList(CValue::asString));
                javaClasses.addAll(rec.get("classes").asList(CValue::asString));
                jsImporters.addAll(rec.get("importers").asList(CValue::asString));
                hbsUsers.addAll(rec.get("hbsUsers").asList(CValue::asString));
                urls.removeIf(s -> s == null || s.isEmpty());
                javaClasses.removeIf(s -> s == null || s.isEmpty());
                jsImporters.removeIf(s -> s == null || s.isEmpty());
                hbsUsers.removeIf(s -> s == null || s.isEmpty());
            }
        } catch (Throwable t) {
            // Graph lookup is best-effort; the file-level change still gets reported.
        }
        // PD-3: tree-sitter walk to identify which functions/methods the hunks fall into.
        List<HunkSymbol> hunkSymbols = parseHunkSymbols(fc, /* js= */ true);

        String risk = (urls.isEmpty() && jsImporters.isEmpty() && hbsUsers.isEmpty()) ? "LOW" : "MEDIUM";
        // Pack the importers + HBS users into the existing javaCallersOfThisFile slot for
        // backward compatibility with the renderers: they show that slot as "owner/users".
        List<String> combined = new ArrayList<>();
        for (String s : jsImporters) combined.add("js:" + s);
        for (String s : hbsUsers)    combined.add("hbs:" + s);
        return new PolyglotChange(path, "JS", role, fc.changeType(), hunkCount,
            urls, javaClasses, List.of(), List.of(), List.of(),  combined, risk,
            List.of(), List.of(),
            hunkSymbols);
    }

    // ─── HBS (Ember templates — file-level only for v1) ──────────────

    private PolyglotChange resolveHbs(FileChange fc, int hunkCount) {
        // PD-4: look up the :HbsTemplate node directly, find its owning JS component
        // (RENDERS_TEMPLATE edge), and the components it itself uses (USES_COMPONENT).
        // The patch path under the repo is "source/ember/app/...", while the graph stores
        // paths relative to source/ember/app — strip the prefix if present.
        String path = fc.newPath();
        String hbsPath = stripEmberAppPrefix(path);
        String role = "Template";
        List<String> ownerJs = new ArrayList<>();   // JS components that render this template
        List<String> usedComponents = new ArrayList<>(); // components this template invokes

        try (CResult r = writer.session().run(
            "MATCH (h:HbsTemplate {path: $hbs}) " +
            "OPTIONAL MATCH (j:JsFile)-[:RENDERS_TEMPLATE]->(h) " +
            "OPTIONAL MATCH (h)-[:USES_COMPONENT]->(comp:JsFile) " +
            "RETURN h.role AS role, " +
            "       collect(DISTINCT j.path) AS owners, " +
            "       collect(DISTINCT comp.path) AS used",
            Map.of("hbs", hbsPath))) {
            if (r.hasNext()) {
                CRecord rec = r.next();
                role = rec.get("role").asString(role);
                ownerJs.addAll(rec.get("owners").asList(CValue::asString));
                usedComponents.addAll(rec.get("used").asList(CValue::asString));
                ownerJs.removeIf(s -> s == null || s.isEmpty());
                usedComponents.removeIf(s -> s == null || s.isEmpty());
            }
        } catch (Throwable t) {
            // best-effort
        }
        // Fallback path-convention guess if the graph doesn't have the template indexed.
        if (ownerJs.isEmpty()) {
            String guess = hbsPath.replace("templates/components/", "components/")
                                   .replaceFirst("\\.hbs$", ".js");
            if (!guess.equals(hbsPath)) {
                try (CResult r = writer.session().run(
                    "MATCH (j:JsFile {path: $path}) RETURN j.path AS p",
                    Map.of("path", guess))) {
                    if (r.hasNext()) ownerJs.add(r.next().get("p").asString(""));
                } catch (Throwable ignored) {}
            }
        }
        // Pack both lists into the existing javaCallersOfThisFile slot (renderers display
        // it as "owner / users"). Prefix each entry so consumers can tell them apart.
        List<String> combined = new ArrayList<>();
        for (String s : ownerJs)        combined.add("js:" + s);
        for (String s : usedComponents) combined.add("uses:" + s);

        String risk = combined.isEmpty() ? "LOW" : "MEDIUM";
        return new PolyglotChange(path, "HBS", role, fc.changeType(), hunkCount,
            List.of(), List.of(), List.of(), List.of(), List.of(), combined, risk,
            List.of(), List.of(), List.of() /* HBS: no tree-sitter support yet */);
    }

    /** Convert "source/ember/app/templates/foo.hbs" → "templates/foo.hbs" if applicable. */
    private static String stripEmberAppPrefix(String path) {
        if (path == null) return "";
        int idx = path.indexOf("source/ember/app/");
        if (idx >= 0) return path.substring(idx + "source/ember/app/".length());
        return path;
    }

    // ─── C# ───────────────────────────────────────────────────────────

    private PolyglotChange resolveCs(FileChange fc, int hunkCount) {
        String path = fc.newPath();
        List<String> urls = new ArrayList<>();
        List<String> javaClasses = new ArrayList<>();
        List<String> psScripts = new ArrayList<>();
        String role = "";

        try (CResult r = writer.session().run(
            "MATCH (c:CsFile {path: $path}) " +
            "OPTIONAL MATCH (c)-[:CALLS_API]->(re:RestEndpoint) " +
            "OPTIONAL MATCH (cls:Class)-[:EXPOSES]->(re) " +
            "OPTIONAL MATCH (c)-[:INVOKES_SCRIPT]->(p:PsScript) " +
            "RETURN c.role AS role, " +
            "       collect(DISTINCT re.url) AS urls, " +
            "       collect(DISTINCT cls.fqn) AS classes, " +
            "       collect(DISTINCT p.name) AS scripts",
            Map.of("path", path))) {
            if (r.hasNext()) {
                CRecord rec = r.next();
                role = rec.get("role").asString("");
                urls.addAll(rec.get("urls").asList(CValue::asString));
                javaClasses.addAll(rec.get("classes").asList(CValue::asString));
                psScripts.addAll(rec.get("scripts").asList(CValue::asString));
                urls.removeIf(s -> s == null || s.isEmpty());
                javaClasses.removeIf(s -> s == null || s.isEmpty());
                psScripts.removeIf(s -> s == null || s.isEmpty());
            }
        } catch (Throwable t) {
            // best-effort
        }
        // PD-3: tree-sitter walk for C#.
        List<HunkSymbol> hunkSymbols = parseHunkSymbols(fc, /* js= */ false);

        String risk = (!urls.isEmpty() || !psScripts.isEmpty()) ? "MEDIUM" : "LOW";
        return new PolyglotChange(path, "CS", role, fc.changeType(), hunkCount,
            urls, javaClasses, psScripts, List.of(), List.of(), List.of(), risk,
            List.of(), List.of(),
            hunkSymbols);
    }

    // ─── XML (data-dictionary / REST API config / servlet actions) ───

    private static final Pattern TABLE_RE          = Pattern.compile("<table\\b[^>]*\\sname=\"([^\"]+)\"");
    private static final Pattern COLUMN_RE         = Pattern.compile("<column\\b[^>]*\\sname=\"([^\"]+)\"");
    private static final Pattern URL_RE            = Pattern.compile("<url\\b[^>]*>([^<]+)</url>");
    private static final Pattern API_URL_RE        = Pattern.compile("<api-url\\b[^>]*>([^<]+)</api-url>");
    // ADSProductAPIS.xml form: <ADSProductAPIs ... API_URL="/RestAPI/Foo" ... />
    private static final Pattern API_URL_ATTR_RE   = Pattern.compile("\\sAPI_URL=\"([^\"]+)\"");
    // SPMPServletActions.xml form: <Action SERVLET_NAME="..."> or attribute SERVLET_URL="/servlet/..."
    private static final Pattern SERVLET_URL_RE    = Pattern.compile("\\sSERVLET_URL=\"([^\"]+)\"");
    private static final Pattern SERVLET_NAME_ATTR = Pattern.compile("\\sSERVLET_NAME=\"([^\"]+)\"");
    private static final Pattern SERVLET_CLASS_RE  = Pattern.compile("\\sSERVLET_CLASS_NAME=\"([^\"]+)\"");
    private static final Pattern SERVLET_RE        = Pattern.compile("<servlet-name\\b[^>]*>([^<]+)</servlet-name>");

    private PolyglotChange resolveXml(FileChange fc, int hunkCount) {
        String path = fc.newPath();
        String fileName = pathTail(path);
        String role;
        if (fileName.equals("data-dictionary.xml"))             role = "DataDictionary";
        else if (fileName.equals("ADSProductAPIS.xml"))         role = "RestApi";
        else if (fileName.equals("SPMPServletActions.xml"))     role = "ServletActions";
        else if (fileName.equals("web.xml"))                    role = "WebConfig";
        else                                                    role = "Other";

        Set<String> tables   = new LinkedHashSet<>();
        Set<String> cols     = new LinkedHashSet<>();
        Set<String> urls     = new LinkedHashSet<>();
        Set<String> servlets = new LinkedHashSet<>();

        // Read the on-disk file and walk hunks to extract structured elements.
        // We do a per-line scan because the patch's hunk ranges are post-image line numbers
        // — the disk file matches those numbers exactly after the patch has been applied.
        Path disk = repoRoot.resolve(path);
        if (Files.isRegularFile(disk) && fc.hunkRanges() != null && !fc.hunkRanges().isEmpty()) {
            try {
                List<String> lines = Files.readAllLines(disk);
                String currentTable = null;   // for <column> attribution
                for (int[] range : fc.hunkRanges()) {
                    int start = Math.max(1, range[0]);
                    int end   = Math.min(lines.size(), range[1]);
                    // Walk back from the hunk start to find an enclosing <table> for column attribution.
                    currentTable = findEnclosingTable(lines, start);
                    for (int i = start; i <= end; i++) {
                        String line = lines.get(i - 1);
                        // Update current table whenever a new <table name="..."> appears inside the hunk.
                        Matcher mt = TABLE_RE.matcher(line);
                        if (mt.find()) {
                            currentTable = mt.group(1);
                            tables.add(currentTable);
                        }
                        Matcher mc = COLUMN_RE.matcher(line);
                        while (mc.find()) {
                            String col = mc.group(1);
                            if (currentTable != null) {
                                cols.add(currentTable + "." + col);
                            } else {
                                cols.add(col);
                            }
                        }
                        Matcher mu = URL_RE.matcher(line);
                        while (mu.find()) urls.add(mu.group(1).trim());
                        Matcher ma = API_URL_RE.matcher(line);
                        while (ma.find()) urls.add(ma.group(1).trim());
                        // Attribute-style API_URL (ADSProductAPIS.xml)
                        Matcher maa = API_URL_ATTR_RE.matcher(line);
                        while (maa.find()) urls.add(maa.group(1).trim());
                        // Attribute-style SERVLET_URL (SPMPServletActions.xml)
                        Matcher msu = SERVLET_URL_RE.matcher(line);
                        while (msu.find()) urls.add(msu.group(1).trim());
                        Matcher ms = SERVLET_RE.matcher(line);
                        while (ms.find()) servlets.add(ms.group(1).trim());
                        // Attribute-style SERVLET_NAME / SERVLET_CLASS_NAME
                        Matcher msn = SERVLET_NAME_ATTR.matcher(line);
                        while (msn.find()) servlets.add(msn.group(1).trim());
                        Matcher msc = SERVLET_CLASS_RE.matcher(line);
                        while (msc.find()) servlets.add(msc.group(1).trim());
                    }
                }
            } catch (IOException ignored) {
                // Fall back to file-level only
            }
        }

        // Convert the extracted servlet names into FQNs by looking up :RestEndpoint.class_fqn
        // (already populated during ingest from SPMPServletActions.xml + ADSProductAPIS.xml).
        List<String> javaClasses = new ArrayList<>();
        if (!urls.isEmpty()) {
            try (CResult r = writer.session().run(
                "UNWIND $urls AS u " +
                "MATCH (cls:Class)-[:EXPOSES]->(:RestEndpoint {url: u}) " +
                "RETURN collect(DISTINCT cls.fqn) AS classes",
                Map.of("urls", new ArrayList<>(urls)))) {
                if (r.hasNext()) {
                    javaClasses.addAll(r.next().get("classes").asList(CValue::asString));
                    javaClasses.removeIf(s -> s == null || s.isEmpty());
                }
            } catch (Throwable ignored) {}
        }

        // ── PD-2: graph-resolved consequences ────────────────────────
        // For every table named, find Java methods that already WRITE / READ it.
        List<String> writers = new ArrayList<>();
        List<String> readers = new ArrayList<>();
        if (!tables.isEmpty()) {
            try (CResult r = writer.session().run(
                "UNWIND $tables AS t " +
                "MATCH (m:Method)-[:WRITES_TABLE]->(:DbTable {name: t}) " +
                "WITH t, collect(DISTINCT m.fqn) AS ws " +
                "OPTIONAL MATCH (m2:Method)-[:READS_TABLE]->(:DbTable {name: t}) " +
                "RETURN t AS table, ws AS writers, collect(DISTINCT m2.fqn) AS readers",
                Map.of("tables", new ArrayList<>(tables)))) {
                Set<String> wSet = new LinkedHashSet<>();
                Set<String> rSet = new LinkedHashSet<>();
                while (r.hasNext()) {
                    CRecord rec = r.next();
                    String tbl = rec.get("table").asString("");
                    for (String fqn : rec.get("writers").asList(CValue::asString)) {
                        if (fqn != null && !fqn.isEmpty()) wSet.add(prefixTable(tbl, fqn));
                    }
                    for (String fqn : rec.get("readers").asList(CValue::asString)) {
                        if (fqn != null && !fqn.isEmpty()) rSet.add(prefixTable(tbl, fqn));
                    }
                }
                writers.addAll(wSet);
                readers.addAll(rSet);
            } catch (Throwable t) {
                System.err.println("[PolyglotResolver] table writer/reader lookup failed: " + t.getMessage());
            }
        }

        // For every URL declared/named, find JS / C# files that already call it
        // and HtmlPage filenames that reference it. Match both bare URL and
        // L1's "url?MTCALL_VALUE" form (see L4 / HtmlReferences merge logic).
        List<String> jsCallers = new ArrayList<>();
        List<String> csCallers = new ArrayList<>();
        List<String> htmlRefs = new ArrayList<>();
        if (!urls.isEmpty()) {
            try (CResult r = writer.session().run(
                "UNWIND $urls AS u " +
                "MATCH (re:RestEndpoint) " +
                "WHERE re.url = u OR re.url STARTS WITH (u + '?') " +
                "OPTIONAL MATCH (j:JsFile)-[:CALLS_API]->(re) " +
                "OPTIONAL MATCH (cs:CsFile)-[:CALLS_API]->(re) " +
                "OPTIONAL MATCH (h:HtmlPage)-[:REFERENCES]->(re) " +
                "RETURN collect(DISTINCT j.simple_name) AS js, " +
                "       collect(DISTINCT cs.simple_name) AS cs, " +
                "       collect(DISTINCT h.filename) AS html",
                Map.of("urls", new ArrayList<>(urls)))) {
                if (r.hasNext()) {
                    CRecord rec = r.next();
                    rec.get("js").asList(CValue::asString).stream()
                        .filter(s -> s != null && !s.isEmpty()).forEach(jsCallers::add);
                    rec.get("cs").asList(CValue::asString).stream()
                        .filter(s -> s != null && !s.isEmpty()).forEach(csCallers::add);
                    rec.get("html").asList(CValue::asString).stream()
                        .filter(s -> s != null && !s.isEmpty()).forEach(htmlRefs::add);
                }
            } catch (Throwable t) {
                System.err.println("[PolyglotResolver] URL caller lookup failed: " + t.getMessage());
            }
        }

        // Risk escalation:
        //   any table with >0 writers → MEDIUM minimum (existing code already mutates it)
        //   any URL with >0 callers  → MEDIUM minimum (existing UI already hits it)
        String risk = (!tables.isEmpty() || !urls.isEmpty() || !servlets.isEmpty()) ? "MEDIUM" : "LOW";
        if (!writers.isEmpty() || !jsCallers.isEmpty() || !csCallers.isEmpty()) risk = "MEDIUM";

        return new PolyglotChange(path, "XML", role, fc.changeType(), hunkCount,
            List.of(),                       // restUrlsCalled: XML doesn't "call" — it declares
            javaClasses,
            List.of(),
            new ArrayList<>(tables),
            new ArrayList<>(cols),
            new ArrayList<>(urls),
            risk,
            writers, readers, 
            List.of() /* XML: structural extraction is already per-element; no AST */);
    }

    /** Read the patched file from disk and run tree-sitter on its hunks. */
    private List<HunkSymbol> parseHunkSymbols(FileChange fc, boolean isJs) {
        if (fc.hunkRanges() == null || fc.hunkRanges().isEmpty()) return List.of();
        java.nio.file.Path disk = repoRoot.resolve(fc.newPath());
        if (!java.nio.file.Files.isRegularFile(disk)) return List.of();
        byte[] bytes;
        try {
            bytes = java.nio.file.Files.readAllBytes(disk);
        } catch (java.io.IOException e) {
            return List.of();
        }
        try {
            return isJs
                ? TreeSitterHunkResolver.resolveJs(bytes, fc.hunkRanges())
                : TreeSitterHunkResolver.resolveCs(bytes, fc.hunkRanges());
        } catch (Throwable t) {
            System.err.println("[PolyglotResolver] tree-sitter hunk resolve failed for "
                + fc.newPath() + ": " + t.getMessage());
            return List.of();
        }
    }

    /** Format a "table:method" string so the report can group writers/readers by table. */
    private static String prefixTable(String table, String methodFqn) {
        if (table == null || table.isEmpty()) return methodFqn;
        return table + ":" + methodFqn;
    }

    /** Walk backwards from hunkStart looking for the nearest enclosing &lt;table name="…"&gt;. */
    private static String findEnclosingTable(List<String> lines, int hunkStart) {
        for (int i = Math.min(hunkStart - 1, lines.size() - 1); i >= 0; i--) {
            String line = lines.get(i);
            Matcher m = TABLE_RE.matcher(line);
            if (m.find()) return m.group(1);
            // Closing tag means we crossed a sibling — give up
            if (line.contains("</table>")) return null;
        }
        return null;
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private static String languageOf(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".js"))         return "JS";
        if (lower.endsWith(".hbs"))        return "HBS";
        if (lower.endsWith(".cs"))         return "CS";
        if (lower.endsWith(".xml"))        return "XML";
        if (lower.endsWith(".properties")) return "PROPERTIES";
        if (lower.endsWith(".json"))       return "JSON";
        return "OTHER";
    }

    private static String pathTail(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static PolyglotChange base(FileChange fc, String lang, String role, int hunkCount, String risk) {
        return new PolyglotChange(fc.newPath(), lang, role, fc.changeType(), hunkCount,
            List.of(), List.of(), List.of(), List.of(), List.of(),  List.of(), risk,
            List.of(), List.of(),  List.of());
    }
}
