package io.spmp.impact.diff;

import io.spmp.impact.model.ImpactReport.HunkSymbol;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSPoint;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterCSharp;
import org.treesitter.TreeSitterJavascript;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tree-sitter backed hunk-to-symbol mapper for JavaScript and C# patch files.
 *
 * <p>Given the raw bytes of a HEAD-revision file plus a list of 1-based inclusive
 * hunk line ranges, returns one {@link HunkSymbol} per affected function / method /
 * class — falling back to the enclosing class if no inner function matches.
 *
 * <p>This is the polyglot equivalent of {@link HunkToSymbolResolver} for Java. It
 * runs on the diff side at analyze-time; it does NOT touch Neo4j.
 *
 * <p>JS function detection covers:
 * <ul>
 *   <li>{@code function_declaration} → top-level named functions</li>
 *   <li>{@code method_definition} → ES6 class methods</li>
 *   <li>{@code variable_declarator} with arrow/function child → named consts and lets</li>
 *   <li>{@code pair} with arrow/function value → object-literal methods (Ember/AMD style)</li>
 *   <li>{@code class_declaration} → fallback for hunks not inside any function</li>
 * </ul>
 *
 * <p>C# detection covers:
 * <ul>
 *   <li>{@code method_declaration}</li>
 *   <li>{@code constructor_declaration}</li>
 *   <li>{@code destructor_declaration}</li>
 *   <li>{@code property_declaration} (when the hunk is inside getter/setter)</li>
 *   <li>{@code operator_declaration}</li>
 *   <li>{@code local_function_statement}</li>
 *   <li>{@code class_declaration} / {@code interface_declaration} / {@code struct_declaration} / {@code record_declaration} → fallback</li>
 * </ul>
 */
public final class TreeSitterHunkResolver {

    /** Function-like node types in JS. */
    private static final Set<String> JS_FN_TYPES = Set.of(
        "function_declaration", "method_definition", "function_expression",
        "arrow_function", "generator_function_declaration"
    );
    /** Class/object-like fallback types in JS. */
    private static final Set<String> JS_CLASS_TYPES = Set.of(
        "class_declaration"
    );

    /** Function-like node types in C#. */
    private static final Set<String> CS_FN_TYPES = Set.of(
        "method_declaration", "constructor_declaration", "destructor_declaration",
        "property_declaration", "operator_declaration", "local_function_statement",
        "conversion_operator_declaration", "indexer_declaration"
    );
    /** Class/struct/interface/record fallback types in C#. */
    private static final Set<String> CS_CLASS_TYPES = Set.of(
        "class_declaration", "interface_declaration", "struct_declaration", "record_declaration"
    );

    private TreeSitterHunkResolver() {}

    public static List<HunkSymbol> resolveJs(byte[] src, List<int[]> hunkRanges) {
        if (src == null || src.length == 0 || hunkRanges == null || hunkRanges.isEmpty()) return List.of();
        return resolve(src, hunkRanges, new TreeSitterJavascript(), JS_FN_TYPES, JS_CLASS_TYPES, true);
    }

    public static List<HunkSymbol> resolveCs(byte[] src, List<int[]> hunkRanges) {
        if (src == null || src.length == 0 || hunkRanges == null || hunkRanges.isEmpty()) return List.of();
        return resolve(src, hunkRanges, new TreeSitterCSharp(), CS_FN_TYPES, CS_CLASS_TYPES, false);
    }

    /**
     * Walk the tree once, collect every function-like and class-like declaration with its
     * line range, then for each hunk pick the smallest declaration that contains it.
     */
    private static List<HunkSymbol> resolve(byte[] src,
                                            List<int[]> hunkRanges,
                                            TSLanguage lang,
                                            Set<String> fnTypes,
                                            Set<String> classTypes,
                                            boolean isJs) {
        TSParser parser = new TSParser();
        parser.setLanguage(lang);
        TSTree tree;
        try {
            tree = parser.parseString(null, new String(src, StandardCharsets.UTF_8));
        } catch (Throwable t) {
            // Parser blew up — let the caller fall back to file-level
            return List.of();
        }
        TSNode root = tree.getRootNode();

        // Collect declarations in document order: list<Decl{name, kind, parent, start, end}>
        List<Decl> decls = new ArrayList<>();
        walk(root, src, "", decls, fnTypes, classTypes, isJs);

        // For each hunk we want every function/method whose line range OVERLAPS the
        // hunk's line range — not just the smallest containing one. Two cases:
        //   (a) hunk inside function (typical MODIFY hunk): only the smallest
        //       containing decl is interesting; outer classes are noise.
        //   (b) hunk wraps function(s) (typical ADD-mode where hunk = entire file):
        //       every function inside is "changed".
        // Strategy:
        //   - if any decl is fully INSIDE the hunk, return ALL such decls
        //     (skip the trivially-containing "whole file" class fallback);
        //   - otherwise fall back to smallest-containing.
        Map<String, HunkSymbol> uniq = new LinkedHashMap<>();
        for (int[] h : hunkRanges) {
            int hs = h[0], he = h[1];
            List<Decl> inside = new ArrayList<>();
            for (Decl d : decls) {
                if (d.start >= hs && d.end <= he
                    // Skip purely-containing "class wrapping the whole file" decls when we
                    // have inner functions to report — keep classes only when they are
                    // strictly inside the hunk (which is the same condition; this branch
                    // already implies that). The filter below applies the actual heuristic.
                    && !d.kind.equals("class")) {
                    inside.add(d);
                }
            }
            if (!inside.isEmpty()) {
                for (Decl d : inside) {
                    String key = d.kind + "|" + d.parent + "|" + d.name + "|" + d.start;
                    uniq.putIfAbsent(key, new HunkSymbol(
                        d.name, d.kind, d.parent, d.start, d.end, hs, he
                    ));
                }
                continue;
            }
            Decl best = null;
            int bestSize = Integer.MAX_VALUE;
            for (Decl d : decls) {
                if (d.start <= hs && d.end >= he) {
                    int size = d.end - d.start;
                    if (size < bestSize) { best = d; bestSize = size; }
                }
            }
            if (best == null) continue;
            String key = best.kind + "|" + best.parent + "|" + best.name + "|" + best.start;
            uniq.putIfAbsent(key, new HunkSymbol(
                best.name, best.kind, best.parent, best.start, best.end, hs, he
            ));
        }
        return new ArrayList<>(uniq.values());
    }

    /** Recursively scan AST; record any function-like or class-like node with a name. */
    private static void walk(TSNode node, byte[] src, String parent,
                             List<Decl> sink, Set<String> fnTypes, Set<String> classTypes, boolean isJs) {
        String type = node.getType();
        String namedAs = parent;   // children inherit by default

        if (fnTypes.contains(type)) {
            String name = nameOfFunctionLike(node, src, type, isJs);
            String kind = mapKind(type, isJs);
            int startLine = node.getStartPoint().getRow() + 1;
            int endLine = node.getEndPoint().getRow() + 1;
            // Only record named functions; anonymous arrow/function_expression nodes
            // are usually inline callbacks (Ember .extend({ observer: function() {} }) etc.)
            // — they'll be reached transitively via their named binding wrapper.
            if (name != null && !name.isEmpty()) {
                sink.add(new Decl(name, kind, parent, startLine, endLine));
                namedAs = (parent == null || parent.isEmpty() ? "" : parent + ".") + name;
            }
        } else if (classTypes.contains(type)) {
            String name = nameOfClass(node, src);
            String kind = "class";
            if (isJs && "class_declaration".equals(type)) kind = "class";
            int startLine = node.getStartPoint().getRow() + 1;
            int endLine = node.getEndPoint().getRow() + 1;
            sink.add(new Decl(name == null ? "<anon>" : name, kind, parent, startLine, endLine));
            namedAs = name == null ? parent : name;
        }
        // Detect JS named-arrow / named-function-expression patterns
        if (isJs && ("variable_declarator".equals(type) || "pair".equals(type) || "assignment_expression".equals(type))) {
            // Check the right-hand side
            TSNode rhs = jsRhsForBinding(node, type);
            if (rhs != null && (JS_FN_TYPES.contains(rhs.getType()))) {
                String lhsName = jsLhsName(node, src, type);
                if (lhsName != null && !lhsName.isEmpty()) {
                    String kind = "function";
                    if ("pair".equals(type)) kind = "method"; // object-literal method
                    int startLine = node.getStartPoint().getRow() + 1;
                    int endLine = node.getEndPoint().getRow() + 1;
                    sink.add(new Decl(lhsName, kind, parent, startLine, endLine));
                    namedAs = (parent == null || parent.isEmpty() ? "" : parent + ".") + lhsName;
                }
            }
        }
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            walk(node.getChild(i), src, namedAs == null ? "" : namedAs, sink, fnTypes, classTypes, isJs);
        }
    }

    /**
     * Extract the name of a function-like node by scanning its immediate children for a
     * representative identifier child ("identifier", "property_identifier", etc.).
     */
    private static String nameOfFunctionLike(TSNode node, byte[] src, String type, boolean isJs) {
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            TSNode c = node.getChild(i);
            String ct = c.getType();
            if (isJs) {
                if ("identifier".equals(ct) || "property_identifier".equals(ct)) return slice(src, c);
            } else {
                if ("identifier".equals(ct)) return slice(src, c);
            }
        }
        return null;
    }

    private static String nameOfClass(TSNode node, byte[] src) {
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            TSNode c = node.getChild(i);
            String ct = c.getType();
            if ("identifier".equals(ct) || "type_identifier".equals(ct)) return slice(src, c);
        }
        return null;
    }

    /** For JS binding nodes, return the function/arrow node on the RHS (or null). */
    private static TSNode jsRhsForBinding(TSNode bindingNode, String bindingType) {
        int n = bindingNode.getChildCount();
        for (int i = 0; i < n; i++) {
            TSNode c = bindingNode.getChild(i);
            String ct = c.getType();
            if (JS_FN_TYPES.contains(ct)) return c;
        }
        return null;
    }

    /** For JS binding nodes, return the LHS name. */
    private static String jsLhsName(TSNode bindingNode, byte[] src, String bindingType) {
        int n = bindingNode.getChildCount();
        for (int i = 0; i < n; i++) {
            TSNode c = bindingNode.getChild(i);
            String ct = c.getType();
            if ("identifier".equals(ct) || "property_identifier".equals(ct)
                || "shorthand_property_identifier".equals(ct)
                || "private_property_identifier".equals(ct)) {
                return slice(src, c);
            }
            if ("string".equals(ct) || "computed_property_name".equals(ct)) {
                // "foo": function() {} — strip quotes
                String s = slice(src, c);
                if (s != null && s.length() >= 2) {
                    char first = s.charAt(0), last = s.charAt(s.length() - 1);
                    if ((first == '"' || first == '\'') && first == last) {
                        return s.substring(1, s.length() - 1);
                    }
                }
                return s;
            }
        }
        return null;
    }

    private static String slice(byte[] src, TSNode n) {
        int s = n.getStartByte(), e = n.getEndByte();
        if (s < 0 || e > src.length || s > e) return "";
        return new String(src, s, e - s, StandardCharsets.UTF_8);
    }

    private static String mapKind(String tsType, boolean isJs) {
        if (isJs) {
            return switch (tsType) {
                case "function_declaration", "generator_function_declaration" -> "function";
                case "method_definition" -> "method";
                case "function_expression", "arrow_function" -> "function";
                default -> "function";
            };
        }
        return switch (tsType) {
            case "constructor_declaration" -> "constructor";
            case "destructor_declaration"  -> "destructor";
            case "property_declaration"    -> "property";
            case "operator_declaration", "conversion_operator_declaration" -> "operator";
            case "indexer_declaration"     -> "indexer";
            case "local_function_statement" -> "function";
            default -> "method";
        };
    }

    private record Decl(String name, String kind, String parent, int start, int end) {}
}
