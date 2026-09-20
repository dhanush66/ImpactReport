package io.spmp.impact.diff;

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.declarations.ResolvedConstructorDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration;

import io.spmp.impact.extract.JavaProjectParser;
import io.spmp.impact.model.DiffModels.ChangedSymbol;
import io.spmp.impact.model.DiffModels.ChangedSymbol.ChangeNature;
import io.spmp.impact.model.DiffModels.ChangedSymbol.Kind;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Maps diff hunks (line ranges in HEAD) to their smallest enclosing AST symbol
 * (method / constructor / class). FQNs are produced using the SAME canonicalization
 * the extractor uses, so changed symbols join cleanly with the ingested graph.
 *
 * <p>v1 simplification: change-nature classification is BODY for methods/constructors
 * whose hunk falls inside the body, SIGNATURE if any hunk overlaps the method header
 * line, and ADDED for symbols whose entire range falls within the hunk. DELETED is
 * detected by the caller (when a path has no head blob).
 */
public class HunkToSymbolResolver {

    private final JavaProjectParser parser;

    public HunkToSymbolResolver(JavaProjectParser parser) {
        this.parser = parser;
    }

    /**
     * @param filePath     the file's path (used for ChangedSymbol.filePath)
     * @param sourceBytes  HEAD-revision source bytes
     * @param hunkRanges   list of [startLine, endLine] (1-based, inclusive) in HEAD
     * @return one ChangedSymbol per hunk, deduplicated (multiple hunks in the same method → one entry)
     */
    public List<ChangedSymbol> resolve(String filePath, byte[] sourceBytes, List<int[]> hunkRanges) {
        Optional<CompilationUnit> maybeCu = parser.parseSource(sourceBytes);
        if (maybeCu.isEmpty()) return List.of();
        CompilationUnit cu = maybeCu.get();

        String pkg = cu.getPackageDeclaration()
            .map(p -> p.getNameAsString())
            .orElse("");

        Set<ChangedSymbol> deduped = new LinkedHashSet<>();
        for (int[] range : hunkRanges) {
            // PRIORITY (matches the JS/C# tree-sitter resolver):
            //   (1) Any method/constructor fully INSIDE the hunk → emit every one
            //       (handles ADD-file patches AND wide MODIFY hunks spanning multiple
            //       methods; outer class is suppressed in this branch to avoid noise).
            //   (2) Otherwise, fall back to the SMALLEST symbol that CONTAINS the hunk
            //       (typical MODIFY case — small hunk inside one method body).
            List<Node> insideMembers = findContainedMembers(cu, range[0], range[1]);
            if (!insideMembers.isEmpty()) {
                for (Node n : insideMembers) {
                    ChangedSymbol cs = toChangedSymbol(filePath, pkg, n, range);
                    if (cs != null) deduped.add(cs);
                }
                continue;
            }
            Node smallest = findSmallestEnclosing(cu, range[0], range[1]);
            if (smallest != null) {
                ChangedSymbol cs = toChangedSymbol(filePath, pkg, smallest, range);
                if (cs != null) deduped.add(cs);
            } else {
                // Last resort — emit every method/class/constructor wholly contained.
                // Only triggers when no member matches the inside-check (e.g., the hunk
                // is bigger than the file's outermost class AND contains no inner
                // members; rare).
                for (Node n : findContainedSymbols(cu, range[0], range[1])) {
                    ChangedSymbol cs = toChangedSymbol(filePath, pkg, n, range);
                    if (cs != null) deduped.add(cs);
                }
            }
        }
        return new ArrayList<>(deduped);
    }

    /**
     * Find methods + constructors (NOT outer classes) whose entire range lives inside
     * the hunk. Used by the priority-1 branch: when a hunk wraps multiple members,
     * we want every member as its own ChangedSymbol — the outer class wrapping all of
     * them is noise.
     */
    private List<Node> findContainedMembers(CompilationUnit cu, int hunkStart, int hunkEnd) {
        List<Node> out = new ArrayList<>();
        for (Node n : cu.findAll(Node.class)) {
            if (!(n instanceof MethodDeclaration || n instanceof ConstructorDeclaration)) continue;
            Range r = n.getRange().orElse(null);
            if (r == null) continue;
            if (r.begin.line >= hunkStart && r.end.line <= hunkEnd) out.add(n);
        }
        return out;
    }

    private List<Node> findContainedSymbols(CompilationUnit cu, int hunkStart, int hunkEnd) {
        List<Node> out = new ArrayList<>();
        for (Node n : cu.findAll(Node.class)) {
            if (!(n instanceof MethodDeclaration
                || n instanceof ConstructorDeclaration
                || n instanceof ClassOrInterfaceDeclaration)) continue;
            Range r = n.getRange().orElse(null);
            if (r == null) continue;
            if (r.begin.line >= hunkStart && r.end.line <= hunkEnd) out.add(n);
        }
        return out;
    }

    private Node findSmallestEnclosing(CompilationUnit cu, int hunkStart, int hunkEnd) {
        Node best = null;
        int bestSize = Integer.MAX_VALUE;
        for (Node n : cu.findAll(Node.class)) {
            if (!(n instanceof MethodDeclaration
                || n instanceof ConstructorDeclaration
                || n instanceof ClassOrInterfaceDeclaration)) continue;
            Range r = n.getRange().orElse(null);
            if (r == null) continue;
            if (r.begin.line > hunkStart || r.end.line < hunkEnd) continue;
            int size = r.end.line - r.begin.line;
            if (size < bestSize) { best = n; bestSize = size; }
        }
        return best;
    }

    private ChangedSymbol toChangedSymbol(String filePath, String pkg, Node n, int[] hunk) {
        Range r = n.getRange().orElse(null);
        if (r == null) return null;
        int start = r.begin.line;
        int end = r.end.line;
        // Clamp the recorded hunk range to the symbol bounds so a hunk that spills past
        // the symbol's end (rare — multi-symbol hunks already get split into separate
        // ChangedSymbols above) doesn't surface confusing line numbers to QA.
        int hStart = Math.max(start, hunk[0]);
        int hEnd   = Math.min(end,   hunk[1]);
        if (hStart > hEnd) { hStart = hunk[0]; hEnd = hunk[1]; } // fallback
        ChangeNature nature = classify(hunk, start, end);

        if (n instanceof MethodDeclaration md) {
            String methodFqn = "";
            String fqn = "";
            try {
                // Resolve the method declaration
                ResolvedMethodDeclaration resolved = md.resolve();

                // 1. Fully Qualified Name (DeclaringClassFQN + MethodName)
                // Result: "com.example.MyService.processData"
                methodFqn = resolved.getQualifiedName();
                methodFqn = resolved.getQualifiedSignature(); // This includes parameter types, so it may be more appropriate depending on your needs

                // 2. Full Qualified Signature (includes parameter types)
                // Result: "com.example.MyService.processData(java.lang.String, int)"
                String fqn1 = resolved.declaringType().getQualifiedName();
                fqn = methodFqn;


            } catch (Exception e) {
                System.err.println("Could not resolve method declaration: " + e.getMessage());
            }
            //String fqn = ownerFqn + "." + md.getNameAsString() + "(" + resolvedParamTypes(md) + ")";
            return new ChangedSymbol(filePath, fqn, Kind.METHOD, start, end, nature, hStart, hEnd);
        }
        if (n instanceof ConstructorDeclaration cd) {
            String fqn = "";
            try {
                // Resolve the constructor declaration
                ResolvedConstructorDeclaration resolved = cd.resolve();

                // 1. Qualified Name (DeclaringClassFQN + ConstructorName)
                // Result: "com.example.MyService.MyService"
                String constructorFqn = resolved.getQualifiedName();

                // 2. Qualified Signature (Includes fully qualified parameter types)
                // Result: "com.example.MyService.MyService(java.lang.String, int)"
                fqn = resolved.getQualifiedSignature();

            } catch (Exception e) {
                System.err.println("Could not resolve constructor: " + e.getMessage());
            }
            
            // String ownerFqn = enclosingTypeFqn(cd, pkg);
            // String fqn = ownerFqn + ".<init>(" + resolvedParamTypes(cd) + ")";
            return new ChangedSymbol(filePath, fqn, Kind.CONSTRUCTOR, start, end, nature, hStart, hEnd);
        }
        if (n instanceof ClassOrInterfaceDeclaration coid) {
            String fqn = "";
            try {
                ResolvedReferenceTypeDeclaration resolved = coid.resolve();
                
                // Returns the fully qualified name
                fqn = resolved.getQualifiedName();

            } catch (Exception e) {
                System.err.println("Could not resolve class declaration: " + e.getMessage());
            }
            //String fqn = typeFqn(coid, pkg);
            Kind kind = coid.isInterface() ? Kind.INTERFACE : Kind.CLASS;
            return new ChangedSymbol(filePath, fqn, kind, start, end, nature, hStart, hEnd);
        }
        return null;
    }

    /**
     * Decide ADDED / SIGNATURE / BODY based on how the hunk overlaps the symbol's range.
     *  - Hunk fully contains the symbol → ADDED (typical for new files / new methods).
     *  - Hunk starts at or before the symbol's first line → SIGNATURE (signature line touched).
     *  - Otherwise → BODY.
     */
    private static ChangeNature classify(int[] hunk, int nodeStart, int nodeEnd) {
        if (hunk[0] <= nodeStart && hunk[1] >= nodeEnd) return ChangeNature.ADDED;
        if (hunk[0] <= nodeStart) return ChangeNature.SIGNATURE;
        return ChangeNature.BODY;
    }

    // ─── helpers (FQN production matches CoreExtractor) ────────────────────

    private static String enclosingTypeFqn(Node child, String pkg) {
        Node cur = child;
        // Walk up until we find a TypeDeclaration
        while (cur != null && !(cur instanceof TypeDeclaration)) {
            cur = cur.getParentNode().orElse(null);
        }
        if (cur instanceof ClassOrInterfaceDeclaration coid) return typeFqn(coid, pkg);
        if (cur instanceof TypeDeclaration<?> td) {
            // enum/record/annotation — just qualify by package
            return pkg.isEmpty() ? td.getNameAsString() : pkg + "." + td.getNameAsString();
        }
        return pkg;
    }

    private static String typeFqn(ClassOrInterfaceDeclaration coid, String pkg) {
        // Walk up through any outer TypeDeclarations and assemble Outer.Inner.Innermost
        StringBuilder names = new StringBuilder(coid.getNameAsString());
        Node cur = coid.getParentNode().orElse(null);
        while (cur != null) {
            if (cur instanceof ClassOrInterfaceDeclaration outer) {
                names.insert(0, outer.getNameAsString() + ".");
            }
            cur = cur.getParentNode().orElse(null);
        }
        return pkg.isEmpty() ? names.toString() : pkg + "." + names;
    }

    private static String resolvedParamTypes(MethodDeclaration md) {
        try {
            ResolvedMethodDeclaration r = md.resolve();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.getNumberOfParams(); i++) {
                if (i > 0) sb.append(',');
                try { sb.append(r.getParam(i).getType().describe()); }
                catch (Throwable t) { sb.append('?'); }
            }
            return sb.toString();
        } catch (Throwable t) {
            return rawParamTypes(md);
        }
    }

    private static String resolvedParamTypes(ConstructorDeclaration cd) {
        try {
            ResolvedConstructorDeclaration r = cd.resolve();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < r.getNumberOfParams(); i++) {
                if (i > 0) sb.append(',');
                try { sb.append(r.getParam(i).getType().describe()); }
                catch (Throwable t) { sb.append('?'); }
            }
            return sb.toString();
        } catch (Throwable t) {
            return rawParamTypes(cd);
        }
    }

    private static String rawParamTypes(MethodDeclaration md) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < md.getParameters().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(md.getParameter(i).getType().asString());
        }
        return sb.toString();
    }

    private static String rawParamTypes(ConstructorDeclaration cd) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cd.getParameters().size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(cd.getParameter(i).getType().asString());
        }
        return sb.toString();
    }
}
