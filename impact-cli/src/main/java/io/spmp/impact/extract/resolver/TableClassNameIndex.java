package io.spmp.impact.extract.resolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Maps {@code (table, column)} to the Java class names seeded for it in the product's config
 * XML.
 *
 * <p>These XMLs are table seed data: the element name IS the table and its attributes are the
 * columns. From {@code ADSMWorkFlowMenu.xml}:
 * <pre>
 *   &lt;ADSMWorkFlowActions WF_ACTION_ID="24" … CLASS_NAME="…workflow.CommitUserCreation"/&gt;
 * </pre>
 * and from {@code TechnicianCatagory}, which uses a differently-named column:
 * <pre>
 *   &lt;TechnicianCatagory … LISTENER_CLASS_NAME="…delegation.workflow.WFRequester" …/&gt;
 * </pre>
 *
 * <p>Indexing per column rather than hardcoding {@code CLASS_NAME} is what makes the second
 * case work — the Java reads {@code r.get("LISTENER_CLASS_NAME")}, so the resolver asks for
 * exactly that column.
 *
 * <h2>Recognising a class name</h2>
 * A dotted value is NOT enough: these files are full of dotted i18n keys
 * ({@code admp.reports.report_actions.create_request.options.modify_user} — 2,374 of them
 * under {@code ATTRIB_DISP_NAME} alone). A Java FQN is distinguished by its last segment
 * starting with an uppercase letter, which the i18n keys never do.
 *
 * <h2>Why a regex rather than a DOM parse</h2>
 * The configured directory is ~15 MB over ~330 files, including a 23,000-line
 * {@code data-dictionary.xml} with no class names at all. Building a DOM for each to read an
 * attribute is wasted work, and some of this seed data is not strictly well-formed. A text
 * scan finds the attributes anyway, and a malformed file degrades to "no matches" rather than
 * throwing.
 */
final class TableClassNameIndex {

    /** {@code <Element … ATTR="value"…>} — DOTALL because attributes wrap across lines. */
    private static final Pattern ELEMENT = Pattern.compile("<([A-Za-z_][A-Za-z0-9_]*)\\b([^>]*)>",
        Pattern.DOTALL);

    /** A single {@code NAME="value"} attribute. */
    private static final Pattern ATTRIBUTE = Pattern.compile("([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*\"([^\"]*)\"");

    /**
     * A Java class FQN: dotted, and the last segment starts uppercase. Excludes the dotted
     * lowercase i18n keys that dominate these files.
     */
    static final Pattern CLASS_FQN =
        Pattern.compile("^[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*\\.[A-Z][A-Za-z0-9_$]*$");

    /** Skip absurdly large files; seed data is never this big. */
    private static final long MAX_FILE_BYTES = 32L * 1024 * 1024;

    private final Path xmlDir;

    /** "table|COLUMN" (table lowercased, column upper) -> class FQNs, insertion-ordered. */
    private Map<String, List<String>> byTableColumn;
    /** table (lowercased) -> every class FQN across all of its columns. */
    private Map<String, List<String>> byTable;
    private int filesScanned;
    private int valuesFound;

    TableClassNameIndex(Path xmlDir) {
        this.xmlDir = xmlDir;
    }

    boolean isEmpty() {
        load();
        return byTable.isEmpty();
    }

    /** Whether {@code name} names a table that carries at least one class-valued column. */
    boolean isKnownTable(String name) {
        if (name == null || name.isEmpty()) return false;
        load();
        return byTable.containsKey(name.toLowerCase());
    }

    /**
     * Class names in exactly {@code table}.{@code column}, or empty when that pair has none.
     *
     * <p>No fallback to the table-wide union: the caller probes a known list of class-bearing
     * column names in priority order, so an empty result has to mean "not this column" for
     * the probe to move on to the next one.
     */
    List<String> classNamesForExactColumn(String table, String column) {
        if (table == null || table.isEmpty() || column == null || column.isEmpty()) {
            return List.of();
        }
        load();
        return byTableColumn.getOrDefault(key(table, column), List.of());
    }

    /** Every class name across all of {@code table}'s columns. */
    List<String> classNamesFor(String table) {
        if (table == null || table.isEmpty()) return List.of();
        load();
        return byTable.getOrDefault(table.toLowerCase(), List.of());
    }

    String stats() {
        load();
        return "tables=" + byTable.size() + " tableColumns=" + byTableColumn.size()
             + " classNames=" + valuesFound + " xmlFiles=" + filesScanned;
    }

    private static String key(String table, String column) {
        return table.toLowerCase() + "|" + column.toUpperCase();
    }

    private synchronized void load() {
        if (byTableColumn != null) return;
        Map<String, Set<String>> perColumn = new LinkedHashMap<>();
        Map<String, Set<String>> perTable = new LinkedHashMap<>();

        if (xmlDir == null || !Files.isDirectory(xmlDir)) {
            if (xmlDir != null) {
                System.out.println("[TableClassNameIndex] not a directory, skipping: " + xmlDir);
            }
            byTableColumn = Map.of();
            byTable = Map.of();
            return;
        }

        try (Stream<Path> walk = Files.walk(xmlDir)) {
            List<Path> xmls = walk.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xml"))
                .toList();
            for (Path p : xmls) {
                try {
                    if (Files.size(p) > MAX_FILE_BYTES) continue;
                    scan(Files.readString(p, StandardCharsets.UTF_8), perColumn, perTable);
                    filesScanned++;
                } catch (IOException | RuntimeException e) {
                    System.err.println("[TableClassNameIndex] skipped " + p.getFileName()
                        + " : " + e.getClass().getSimpleName());
                }
            }
        } catch (IOException e) {
            System.err.println("[TableClassNameIndex] walk failed for " + xmlDir
                + " : " + e.getMessage());
        }

        byTableColumn = freeze(perColumn);
        byTable = freeze(perTable);
        System.out.println("[TableClassNameIndex] " + stats() + " from " + xmlDir);
    }

    private void scan(String text, Map<String, Set<String>> perColumn,
                      Map<String, Set<String>> perTable) {
        Matcher el = ELEMENT.matcher(text);
        while (el.find()) {
            String table = el.group(1);
            String attrs = el.group(2);
            if (attrs.isEmpty()) continue;
            Matcher at = ATTRIBUTE.matcher(attrs);
            while (at.find()) {
                String column = at.group(1);
                String value = at.group(2).trim();
                if (value.isEmpty() || !CLASS_FQN.matcher(value).matches()) continue;
                if (perColumn.computeIfAbsent(key(table, column), k -> new LinkedHashSet<>())
                             .add(value)) {
                    valuesFound++;
                }
                perTable.computeIfAbsent(table.toLowerCase(), k -> new LinkedHashSet<>())
                        .add(value);
            }
        }
    }

    private static Map<String, List<String>> freeze(Map<String, Set<String>> in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return Collections.unmodifiableMap(out);
    }
}
