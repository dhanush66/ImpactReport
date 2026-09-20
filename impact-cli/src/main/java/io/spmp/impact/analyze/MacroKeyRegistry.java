package io.spmp.impact.analyze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.*;

/**
 * §8c2 — Macro-key registry: loads {@code macro-keys.json} from the classpath and provides
 * lookup methods used by {@link SliceExecutor#runAffectedMacros} to determine which
 * specific notification-template placeholders are affected by a patch.
 *
 * <p>Two lookup strategies:
 * <ol>
 *   <li><b>Binary gating (HIGH risk):</b> when Layer C detects a dispatch-block overlap,
 *       ALL keys for that macro class are affected (the entire notification was suppressed
 *       pre-fix). Returns the full key list.</li>
 *   <li><b>Data-flow (MEDIUM risk):</b> when Layer A/B reaches a macro init, only keys
 *       whose backing DB table is also written by the patched method are affected.
 *       Cross-references the macro's {@code dbTables} against the slice's
 *       {@code writesTables}.</li>
 * </ol>
 *
 * <p>Also provides the status-writer → macro-key lookup used for sibling-statement
 * attribution in Layer C.
 */
public final class MacroKeyRegistry {

    /** Per-macro-class key definition. */
    public record MacroKeyEntry(String macroKey, String placeholder, String dbColumn) {}

    /** Per-macro-class metadata. */
    public record MacroClassInfo(List<String> dbTables, List<MacroKeyEntry> keys) {}

    /** Status-writer sibling-call → affected keys. */
    public record StatusWriterEntry(String calledMethod, List<String> affectsKeys) {}

    /** Table-write → affected keys (§8c2 Condition 4 table-level mapping). */
    public record TableWriteEntry(String tableName, List<String> affectsKeys) {}

    private final Map<String, MacroClassInfo> bySimpleName;
    private final List<StatusWriterEntry> statusWriters;
    private final Map<String, List<String>> tableWriteMap;  // tableName → affectedKeys

    private static volatile MacroKeyRegistry INSTANCE;

    private MacroKeyRegistry(Map<String, MacroClassInfo> bySimpleName,
                             List<StatusWriterEntry> statusWriters,
                             Map<String, List<String>> tableWriteMap) {
        this.bySimpleName = bySimpleName;
        this.statusWriters = statusWriters;
        this.tableWriteMap = tableWriteMap;
    }

    public static MacroKeyRegistry instance() {
        if (INSTANCE == null) {
            synchronized (MacroKeyRegistry.class) {
                if (INSTANCE == null) {
                    INSTANCE = load();
                }
            }
        }
        return INSTANCE;
    }

    private static MacroKeyRegistry load() {
        Map<String, MacroClassInfo> map = new LinkedHashMap<>();
        List<StatusWriterEntry> writers = new ArrayList<>();
        Map<String, List<String>> tableWrites = new LinkedHashMap<>();
        try (InputStream is = MacroKeyRegistry.class.getResourceAsStream("/macro-keys.json")) {
            if (is == null) {
                System.err.println("[MacroKeyRegistry] macro-keys.json not found on classpath");
                return new MacroKeyRegistry(Map.of(), List.of(), Map.of());
            }
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(is);
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String name = entry.getKey();
                JsonNode node = entry.getValue();
                if ("_statusWriterTable".equals(name)) {
                    JsonNode entries = node.get("entries");
                    if (entries != null && entries.isArray()) {
                        for (JsonNode e : entries) {
                            String method = e.get("calledMethod").asText();
                            List<String> keys = new ArrayList<>();
                            for (JsonNode k : e.get("affectsKeys")) keys.add(k.asText());
                            writers.add(new StatusWriterEntry(method, keys));
                        }
                    }
                    JsonNode tableEntries = node.get("tableWriteEntries");
                    if (tableEntries != null && tableEntries.isArray()) {
                        for (JsonNode e : tableEntries) {
                            String tbl = e.get("tableName").asText();
                            List<String> keys = new ArrayList<>();
                            for (JsonNode k : e.get("affectsKeys")) keys.add(k.asText());
                            tableWrites.put(tbl, keys);
                        }
                    }
                    continue;
                }
                JsonNode dbTablesNode = node.get("dbTables");
                List<String> dbTables = new ArrayList<>();
                if (dbTablesNode != null && dbTablesNode.isArray()) {
                    for (JsonNode t : dbTablesNode) dbTables.add(t.asText());
                }
                JsonNode keysNode = node.get("keys");
                List<MacroKeyEntry> keys = new ArrayList<>();
                if (keysNode != null && keysNode.isArray()) {
                    for (JsonNode k : keysNode) {
                        keys.add(new MacroKeyEntry(
                            k.get("macroKey").asText(),
                            k.get("placeholder").asText(),
                            k.has("dbColumn") && !k.get("dbColumn").isNull() ? k.get("dbColumn").asText() : null
                        ));
                    }
                }
                map.put(name, new MacroClassInfo(dbTables, keys));
            }
        } catch (Exception e) {
            System.err.println("[MacroKeyRegistry] Failed to load macro-keys.json: " + e.getMessage());
        }
        return new MacroKeyRegistry(Collections.unmodifiableMap(map),
                                    Collections.unmodifiableList(writers),
                                    Collections.unmodifiableMap(tableWrites));
    }

    /** Get all macro classes in the registry. */
    public Set<String> macroClassNames() {
        return bySimpleName.keySet();
    }

    /** Get macro-class info by simple name. */
    public MacroClassInfo get(String simpleName) {
        return bySimpleName.get(simpleName);
    }

    /**
     * Binary-gating: return ALL placeholder keys for this macro class.
     * Used when Layer C (HIGH risk) determines the entire notification was gated.
     */
    public List<String> allKeysForClass(String simpleName) {
        MacroClassInfo info = bySimpleName.get(simpleName);
        if (info == null) return List.of();
        return info.keys().stream().map(MacroKeyEntry::placeholder).toList();
    }

    /**
     * Attribute-level narrowing: given LDAP attribute names from :HANDLES_ATTRIBUTE
     * edges (e.g. "daysToExpireAccount"), return only the matching placeholder keys.
     * Used by Layer D when hunk-block overlap identifies specific attributes.
     */
    public List<String> keysForAttributes(String simpleName, List<String> attributeNames) {
        MacroClassInfo info = bySimpleName.get(simpleName);
        if (info == null) return List.of();
        if (attributeNames == null || attributeNames.isEmpty()) return List.of();
        Set<String> attrSet = new java.util.HashSet<>(attributeNames);
        List<String> result = new ArrayList<>();
        for (MacroKeyEntry e : info.keys()) {
            if (attrSet.contains(e.macroKey())) {
                result.add(e.placeholder());
            }
        }
        return result;
    }

    /**
     * Data-flow: return only the placeholder keys whose backing DB table is among
     * the provided set of tables that the patched method writes.
     * Used for Layer A/B (MEDIUM risk) where specific DB tables are touched.
     */
    public List<String> keysAffectedByTables(String simpleName, Set<String> writtenTables) {
        MacroClassInfo info = bySimpleName.get(simpleName);
        if (info == null) return List.of();
        if (writtenTables == null || writtenTables.isEmpty()) return List.of();
        List<String> result = new ArrayList<>();
        for (MacroKeyEntry e : info.keys()) {
            if (e.dbColumn() == null) continue;
            // dbColumn format is "TABLE.COLUMN" — extract table name
            String tablePart = e.dbColumn().contains(".")
                ? e.dbColumn().substring(0, e.dbColumn().indexOf('.'))
                : e.dbColumn();
            if (writtenTables.contains(tablePart)) {
                result.add(e.placeholder());
            }
        }
        return result;
    }

    /**
     * Status-writer lookup: given sibling method calls in the dispatch block, return
     * the PLACEHOLDER names (e.g. {@code %WorkflowStatus%}) of macro keys that are
     * indirectly affected by the sibling DB writes.
     */
    public List<String> keysFromSiblingCalls(String macroSimpleName, Collection<String> siblingMethodNames) {
        if (siblingMethodNames == null || siblingMethodNames.isEmpty()) return List.of();
        // Collect affected macro key strings from the status-writer table
        Set<String> affectedMacroKeys = new LinkedHashSet<>();
        for (StatusWriterEntry entry : statusWriters) {
            if (siblingMethodNames.contains(entry.calledMethod())) {
                affectedMacroKeys.addAll(entry.affectsKeys());
            }
        }
        if (affectedMacroKeys.isEmpty()) return List.of();
        // Map macro keys → placeholder names for the given class
        MacroClassInfo info = bySimpleName.get(macroSimpleName);
        if (info == null) {
            // Fallback: return the raw keys if class not found
            return new ArrayList<>(affectedMacroKeys);
        }
        List<String> result = new ArrayList<>();
        for (MacroKeyEntry e : info.keys()) {
            if (affectedMacroKeys.contains(e.macroKey())) {
                result.add(e.placeholder());
            }
        }
        // If no placeholders matched (key not in this class), return raw keys
        return result.isEmpty() ? new ArrayList<>(affectedMacroKeys) : result;
    }

    /** Get the status-writer entries for external use. */
    public List<StatusWriterEntry> statusWriters() {
        return statusWriters;
    }

    /**
     * Table-write lookup: given a set of DB tables the patch writes (from :WRITES_TABLE),
     * return macro keys affected per the §8c2 table-write mapping.
     * This is a SECOND cross-reference layer on top of the per-class dbColumn matching.
     */
    public List<String> keysFromWrittenTables(Set<String> writtenTables) {
        if (writtenTables == null || writtenTables.isEmpty()) return List.of();
        Set<String> result = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : tableWriteMap.entrySet()) {
            if (writtenTables.contains(entry.getKey())) {
                result.addAll(entry.getValue());
            }
        }
        return new ArrayList<>(result);
    }
}
