package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.model.GraphEdges.DbTableEdge;
import io.spmp.impact.model.GraphNodes.DbTableNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Detects SQL-table references on the SPMP query construction patterns:
 * <pre>{@code
 *   new SelectQueryImpl(TABLE);   // → READS_TABLE
 *   new UpdateQueryImpl(TABLE);   // → WRITES_TABLE
 *   new InsertQueryImpl(TABLE);   // → WRITES_TABLE
 *   new DeleteQueryImpl(TABLE);   // → WRITES_TABLE
 * }</pre>
 *
 * <p>The {@code TABLE} argument is most commonly a {@code public static final String}
 * field in some utility class (e.g. {@code CopyContentUtils.CSC_PROCESSED_DATA_TABLE}).
 * This resolver runs two passes:
 * <ol>
 *   <li><b>visit()</b> per CU: collect string-constant fields into a global map, and queue
 *       up query constructor calls with their argument expressions.</li>
 *   <li><b>afterAll()</b>: resolve each queued argument against the constant map (or as
 *       a string literal), and emit the corresponding DbTable nodes + edges.</li>
 * </ol>
 *
 * <p>Resolution that falls outside this resolver's reach (computed-at-runtime table names,
 * nested method calls) is skipped silently.
 */
public class DbTableResolver implements BoundaryResolver {

    /** Field FQN ("pkg.Class.FIELD") → string value. */
    private final Map<String, String> stringConstants = new HashMap<>();

    /** Pending query construction sites to resolve once all constants are known. */
    private final List<PendingQuery> pending = new ArrayList<>();

    private record PendingQuery(String methodFqn, Expression arg, boolean isWrite, String enclosingClassFqn) {}

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

        // 1) Harvest string-constant fields
        for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
            String classFqn = pkg.isEmpty() ? cls.getNameAsString() : pkg + "." + cls.getNameAsString();
            for (FieldDeclaration fd : cls.getFields()) {
                if (!fd.isStatic() || !fd.isFinal()) continue;
                if (!fd.getElementType().asString().equals("String")) continue;
                for (VariableDeclarator v : fd.getVariables()) {
                    Expression init = v.getInitializer().orElse(null);
                    if (init instanceof StringLiteralExpr sle) {
                        stringConstants.put(classFqn + "." + v.getNameAsString(), sle.getValue());
                    }
                }
            }
        }

        // 2) Queue query construction sites — constructor patterns
        for (ObjectCreationExpr oce : cu.findAll(ObjectCreationExpr.class)) {
            String typeName = oce.getType().getNameAsString();
            boolean isRead  = typeName.equals("SelectQueryImpl") || typeName.equals("SelectQuery");
            boolean isWrite = typeName.equals("UpdateQueryImpl") || typeName.equals("UpdateQuery")
                           || typeName.equals("InsertQueryImpl") || typeName.equals("InsertQuery")
                           || typeName.equals("DeleteQueryImpl") || typeName.equals("DeleteQuery");
            // ADMP/AdventNet Persistence pattern: {@code new Column("TABLE", "COL")} — first
            // argument is the table name. Treated as a READ signal (Column construction is
            // overwhelmingly used in SELECT / WHERE / JOIN expressions).
            boolean isColumnCtor = typeName.equals("Column");
            if (!isRead && !isWrite && !isColumnCtor) continue;
            if (oce.getArguments().isEmpty()) continue;

            MethodDeclaration enc = oce.findAncestor(MethodDeclaration.class).orElse(null);
            if (enc == null) continue;
            String classFqn = enclosingClassFqn(enc, pkg);
            String methodFqn = classFqn + "." + enc.getNameAsString() + "(" + canonicalParamTypes(enc) + ")";
            pending.add(new PendingQuery(methodFqn, oce.getArgument(0), isWrite, classFqn));
        }

        // 3) Static factory call patterns common in AdventNet Persistence:
        //    {@code Table.getTable("TBL")}              — table reference, READ
        //    {@code Column.getColumn("TBL", "COL")}     — column on table, READ
        // Most-used ADMP query API; required to close the READS_TABLE gap.
        for (com.github.javaparser.ast.expr.MethodCallExpr mc : cu.findAll(com.github.javaparser.ast.expr.MethodCallExpr.class)) {
            String name = mc.getNameAsString();
            String scope = mc.getScope().map(Object::toString).orElse("");
            boolean isTableGet  = "getTable".equals(name)  && scope.endsWith("Table");
            boolean isColumnGet = "getColumn".equals(name) && scope.endsWith("Column");
            if (!isTableGet && !isColumnGet) continue;
            if (mc.getArguments().isEmpty()) continue;

            MethodDeclaration enc = mc.findAncestor(MethodDeclaration.class).orElse(null);
            if (enc == null) continue;
            String classFqn = enclosingClassFqn(enc, pkg);
            String methodFqn = classFqn + "." + enc.getNameAsString() + "(" + canonicalParamTypes(enc) + ")";
            // First argument is the table name for both patterns.
            pending.add(new PendingQuery(methodFqn, mc.getArgument(0), /* isWrite */ false, classFqn));
        }
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        Set<String> uniqueTables = new HashSet<>();
        // Dedupe (methodFqn, tableName, isWrite) — without this the new
        // {@code Table.getTable} / {@code Column.getColumn} patterns produce thousands of
        // redundant edges (one per column reference in the same method body). Neo4j MERGE
        // would collapse them in storage but the in-memory list still bloats the batch and
        // OOMs the partial-flush sink. A small {@code HashSet} dedupe keeps emission cheap
        // and the Neo4j write payload small.
        Set<String> emittedEdgeKeys = new HashSet<>();
        int resolved = 0, skipped = 0, duplicates = 0;
        for (PendingQuery pq : pending) {
            String tableName = resolveTable(pq.arg, pq.enclosingClassFqn);
            if (tableName == null) { skipped++; continue; }
            uniqueTables.add(tableName);
            String key = pq.methodFqn + "#" + tableName + "#" + (pq.isWrite ? 'W' : 'R');
            if (!emittedEdgeKeys.add(key)) { duplicates++; continue; }
            batch.dbTableEdges.add(new DbTableEdge(pq.methodFqn, tableName, pq.isWrite));
            resolved++;
        }
        for (String t : uniqueTables) batch.dbTables.add(new DbTableNode(t));
        System.out.printf("[DbTableResolver] query-sites=%d  resolved=%d  skipped=%d  duplicates=%d  unique-tables=%d  emitted-edges=%d%n",
            pending.size(), resolved, skipped, duplicates, uniqueTables.size(), batch.dbTableEdges.size());
    }

    private String resolveTable(Expression arg, String enclosingClassFqn) {
        if (arg instanceof StringLiteralExpr s) return s.getValue();
        if (arg instanceof NameExpr ne) {
            // Bare name — try same-class FQN first, then a global suffix search.
            String localFqn = enclosingClassFqn + "." + ne.getNameAsString();
            if (stringConstants.containsKey(localFqn)) return stringConstants.get(localFqn);
            String suffix = "." + ne.getNameAsString();
            for (var e : stringConstants.entrySet()) {
                if (e.getKey().endsWith(suffix)) return e.getValue();
            }
        }
        if (arg instanceof FieldAccessExpr fa) {
            // "ClassName.FIELD" — match by suffix ".ClassName.FIELD"
            String scope = fa.getScope().toString();
            String name = fa.getNameAsString();
            String suffix = "." + scope + "." + name;
            for (var e : stringConstants.entrySet()) {
                if (e.getKey().endsWith(suffix) || e.getKey().equals(scope + "." + name)) return e.getValue();
            }
        }
        return null;
    }

    private static String enclosingClassFqn(MethodDeclaration md, String pkg) {
        ClassOrInterfaceDeclaration cls = md.findAncestor(ClassOrInterfaceDeclaration.class).orElse(null);
        if (cls == null) return pkg;
        return pkg.isEmpty() ? cls.getNameAsString() : pkg + "." + cls.getNameAsString();
    }

    private static String canonicalParamTypes(MethodDeclaration md) {
        try {
            var r = md.resolve();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.getNumberOfParams(); i++) {
                if (i > 0) sb.append(',');
                try { sb.append(r.getParam(i).getType().describe()); }
                catch (Throwable t) { sb.append('?'); }
            }
            return sb.toString();
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < md.getParameters().size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(md.getParameter(i).getType().asString());
            }
            return sb.toString();
        }
    }
}
