package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.model.GraphEdges.HasColumnEdge;
import io.spmp.impact.model.GraphNodes.DbColumnNode;
import io.spmp.impact.model.GraphNodes.DbTableNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Parses the AdventNet Persistence schema definition files
 * (matched by name: {@code data-dictionary.xml} or {@code *-dd.xml}),
 * which are the authoritative source for SPMP's database tables, columns, data types,
 * size, nullability, and PK-like uniquevalue generators.
 *
 * <p>The existing {@link DbTableResolver} mines table NAMES from {@code SelectQueryImpl(TABLE)}
 * calls in Java. This resolver complements that by extracting the FULL SCHEMA — every
 * table, every column — even tables that no Java code currently reads.
 *
 * <p>Files scanned (under {@code <repo>/product_package/conf/}):
 * <ul>
 *   <li>{@code spmp/data-dictionary.xml} — main SPMP schema</li>
 *   <li>{@code Authentication/data-dictionary.xml} — auth-module schema</li>
 *   <li>any other {@code data-dictionary.xml} the walker finds</li>
 * </ul>
 *
 * <p>For each {@code <table name="X">/<columns>/<column name="Y">}, emits:
 * <ul>
 *   <li>{@code :DbTable {name: X}} (idempotent with what DbTableResolver emits)</li>
 *   <li>{@code :DbColumn {table: X, name: Y, data_type, max_size, nullable, pk_like}}</li>
 *   <li>{@code (DbTable)-[:HAS_COLUMN]->(DbColumn)}</li>
 * </ul>
 */
public class DbSchemaXmlResolver implements BoundaryResolver {

    /** All XML-config roots to walk. Primary + every dep repo's config root. */
    private final java.util.List<Path> xmlConfigRoots;

    /** Single-root constructor (back-compat). */
    public DbSchemaXmlResolver(Path xmlConfigRoot) {
        this.xmlConfigRoots = xmlConfigRoot == null
            ? java.util.List.of()
            : java.util.List.of(xmlConfigRoot);
    }

    /** Multi-root constructor — for multi-repo ingest where every dep contributes a conf dir. */
    public DbSchemaXmlResolver(java.util.List<Path> xmlConfigRoots) {
        this.xmlConfigRoots = xmlConfigRoots == null ? java.util.List.of() : xmlConfigRoots;
    }

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // no-op: schema files are outside the Java tree
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (xmlConfigRoots.isEmpty()) {
            System.out.println("[DbSchemaXmlResolver] xml-config-root not set or missing — skipped");
            return;
        }
        Set<String> seenTables = new HashSet<>();
        int totalTables = 0, totalColumns = 0;
        int ddFileCount = 0;

        for (Path xmlConfigRoot : xmlConfigRoots) {
            if (xmlConfigRoot == null || !Files.isDirectory(xmlConfigRoot)) continue;
            try (Stream<Path> walk = Files.walk(xmlConfigRoot)) {
                var ddFiles = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> isDataDictionaryFile(p.getFileName().toString()))
                    .toList();
                ddFileCount += ddFiles.size();
                for (Path f : ddFiles) {
                    int[] counts = parseDataDictionary(f, batch, seenTables);
                    totalTables += counts[0];
                    totalColumns += counts[1];
                }
            } catch (Throwable t) {
                System.err.println("[DbSchemaXmlResolver] walk error in " + xmlConfigRoot + ": " + t.getMessage());
            }
        }

        System.out.printf("[DbSchemaXmlResolver] resolved %d tables, %d columns from %d schema XML file(s)%n",
            totalTables, totalColumns, ddFileCount);
    }

    /**
     * Recognise an AdventNet-Persistence schema XML file. Both naming conventions exist
     * across our products:
     *   - {@code data-dictionary.xml} (SPMP, Audit, Authentication, …)
     *   - {@code <product>-dd.xml}  (ADSM o365 sub-modules, e.g. {@code adsm-dd.xml})
     */
    private static boolean isDataDictionaryFile(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.equals("data-dictionary.xml") || lower.endsWith("-dd.xml");
    }

    /** @return [tableCount, columnCount] parsed from this file */
    private int[] parseDataDictionary(Path file, ExtractionBatch batch, Set<String> seenTables) {
        int tableCount = 0, columnCount = 0;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            // Schema files include a DOCTYPE that points to a remote/local DTD; disable
            // both external-DTD loading and DTD validation so we don't hit network or
            // missing-file failures.
            try { dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); }
            catch (Exception ignore) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false); }
            catch (Exception ignore) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
            catch (Exception ignore) {}
            try { dbf.setValidating(false); }
            catch (Exception ignore) {}

            DocumentBuilder db = dbf.newDocumentBuilder();
            // Swallow DTD-related warnings/errors silently.
            db.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new java.io.StringReader("")));
            Document doc = db.parse(file.toFile());

            NodeList tables = doc.getElementsByTagName("table");
            for (int i = 0; i < tables.getLength(); i++) {
                Node n = tables.item(i);
                if (!(n instanceof Element te)) continue;
                String tableName = te.getAttribute("name");
                if (tableName == null || tableName.isEmpty()) continue;

                if (seenTables.add(tableName)) {
                    batch.dbTables.add(new DbTableNode(tableName));
                    tableCount++;
                }

                // Collect uniquevalue-generation column names — used to mark pk_like.
                // Generator names in data-dictionary.xml typically use lowercase column names
                // (e.g. "FarmProfiles.id") while <column name="..."> attributes are uppercase
                // (e.g. "ID"), so we normalise both sides to lowercase for matching.
                Set<String> pkLikeCols = new HashSet<>();
                NodeList uvgs = te.getElementsByTagName("uniquevalue-generation");
                for (int u = 0; u < uvgs.getLength(); u++) {
                    Node g = uvgs.item(u);
                    if (!(g instanceof Element ge)) continue;
                    NodeList gnames = ge.getElementsByTagName("generator-name");
                    for (int k = 0; k < gnames.getLength(); k++) {
                        String genName = gnames.item(k).getTextContent();
                        // pattern: "<table>.<col>" or "<table>.<col>.<...>"
                        if (genName != null) {
                            String[] parts = genName.trim().split("\\.");
                            if (parts.length >= 2 && parts[0].equalsIgnoreCase(tableName)) {
                                pkLikeCols.add(parts[1].toLowerCase());
                            }
                        }
                    }
                }

                NodeList cols = te.getElementsByTagName("column");
                for (int c = 0; c < cols.getLength(); c++) {
                    Node cn = cols.item(c);
                    if (!(cn instanceof Element ce)) continue;
                    // Filter to direct <column> children of <columns>, not nested types.
                    Node parent = ce.getParentNode();
                    if (!(parent instanceof Element pe) || !"columns".equals(pe.getTagName())) continue;
                    // Skip if not under our current table (defensive — getElementsByTagName is recursive).
                    Node tableAncestor = pe.getParentNode();
                    if (tableAncestor != te) continue;

                    String colName = ce.getAttribute("name");
                    if (colName == null || colName.isEmpty()) continue;

                    String dataType = textOf(ce.getElementsByTagName("data-type"));
                    Integer maxSize = parseIntOrNull(textOf(ce.getElementsByTagName("max-size")));
                    boolean nullable = !"false".equalsIgnoreCase(textOf(ce.getElementsByTagName("nullable")));

                    batch.dbColumns.add(new DbColumnNode(
                        tableName, colName, dataType, maxSize, nullable,
                        pkLikeCols.contains(colName.toLowerCase())
                    ));
                    batch.hasColumn.add(new HasColumnEdge(tableName, colName));
                    columnCount++;
                }
            }
        } catch (Throwable t) {
            System.err.println("[DbSchemaXmlResolver] error parsing " + file + " : " + t.getMessage());
        }
        return new int[]{tableCount, columnCount};
    }

    private static String textOf(NodeList nl) {
        if (nl == null || nl.getLength() == 0) return "";
        Node first = nl.item(0);
        return first == null ? "" : (first.getTextContent() == null ? "" : first.getTextContent().trim());
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try { return Integer.valueOf(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}
