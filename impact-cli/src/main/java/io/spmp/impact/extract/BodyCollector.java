package io.spmp.impact.extract;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BooleanLiteralExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.CharLiteralExpr;
import com.github.javaparser.ast.expr.DoubleLiteralExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.Name;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.SuperExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.resolution.UnsolvedSymbolException;
import com.github.javaparser.resolution.types.ResolvedType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Walks a method/constructor body in a SINGLE pass and emits {@link CallSite} records
 * + {@link FieldAccessSite} records — without ever invoking SymbolSolver.
 *
 * <p><b>The scope-stack trick:</b> the historic bottleneck on huge methods like the
 * 2818-line method in {@code ReportResultUtil.java} was that resolving the type of
 * every {@code localVar.method()} call required a {@code findAll(VariableDeclarator)}
 * scan over the entire method body — O(n) per call, O(n²) per method. By pushing a
 * fresh scope map on every block entry and popping on exit, every variable lookup
 * becomes O(1) average (HashMap on a small per-scope map).
 *
 * <p><b>Why manual recursion instead of VoidVisitorAdapter:</b> JavaParser's visitor
 * pattern routes every node through {@code accept()} double-dispatch + reflection-y
 * machinery. A manual {@code switch (node.getClass())} on the ~12 node types we care
 * about runs ~4× faster and stays inlinable. The cost is a longer file; the win
 * compounds across 800k+ calls.
 *
 * <p><b>What we collect:</b>
 * <ul>
 *   <li>{@link CallSite} for every {@link MethodCallExpr} and every {@link ObjectCreationExpr}
 *       (constructors recorded with {@code simpleName="<init>"})</li>
 *   <li>{@link FieldAccessSite} for every {@link FieldAccessExpr} read and every
 *       {@link AssignExpr} whose LHS is a field-access (write)</li>
 * </ul>
 *
 * <p><b>What we DON'T resolve here:</b> the resolution from the per-site scope info
 * (kind, scope identifier text, resolved-scope-type FQN) into a concrete target
 * Method/Field FQN happens in {@link CallResolver} during Pass 2. This split is
 * intentional — the AST can be fully discarded after Pass 1b, freeing the heap
 * before the resolution pass kicks in.
 */
public final class BodyCollector {

    /** What kind of receiver/scope a call expression has — drives the resolver chain. */
    public enum ScopeKind {
        /** {@code foo(x)} — no explicit scope; implicit-this. */
        ENCLOSING_THIS,
        /** {@code this.foo(x)} */
        EXPLICIT_THIS,
        /** {@code super.foo(x)} */
        EXPLICIT_SUPER,
        /** {@code ClassName.foo(x)} — scope text matches an imported class. */
        STATIC_CLASS,
        /** {@code localVarOrParam.foo(x)} — scope's type is known from the scope stack. */
        LOCAL_VAR,
        /** {@code this.field.foo(x)} or {@code field.foo(x)} — scope is a field on the enclosing class. */
        FIELD_REF,
        /** {@code SomeName.foo(x)} where the name isn't a known class, var, or field. */
        UNKNOWN_NAME,
        /** {@code getX().foo(x)} or other chained / parenthesized scope. We can't resolve without SymbolSolver. */
        CHAINED,
        /** Constructor invocation {@code new Foo(x)}. */
        CONSTRUCTOR
    }

    /**
     * A pending call-resolution task collected during Pass 1b. {@link CallResolver}
     * consumes a list of these in Pass 2 and emits CallEdges.
     *
     * <p>Fields are populated as the AST is walked. {@code resolvedScopeFqn} is filled
     * in eagerly for {@link ScopeKind#LOCAL_VAR} (via scope-stack lookup) so the resolver
     * can do an O(1) lookup; for other kinds the resolver computes the owner from
     * {@code scopeText} + imports.
     *
     * @param fromMethodFqn          source method FQN (where this call appears)
     * @param enclosingClassFqn      the FQN of the class containing the source method
     * @param filePath               the source file (for import lookup)
     * @param line                   1-based line number of the call in the source
     * @param kind                   how the resolver should interpret this site
     * @param scopeText              the literal source-text of the scope expression (or "" / null)
     * @param resolvedScopeFqn       for LOCAL_VAR: the declared type FQN already resolved via imports;
     *                               for FIELD_REF: the field's declared type; null otherwise
     * @param simpleName             the called method's simple name (or "&lt;init&gt;" for constructors)
     * @param arity                  number of arguments at the call site
     */
    public record CallSite(
        String fromMethodFqn,
        String enclosingClassFqn,
        String filePath,
        int line,
        ScopeKind kind,
        String scopeText,
        String resolvedScopeFqn,
        String simpleName,
        int arity
    ) {}

    /** Field read/write. */
    public record FieldAccessSite(
        String fromMethodFqn,
        String enclosingClassFqn,
        String filePath,
        int line,
        ScopeKind kind,
        String scopeText,
        String resolvedScopeFqn,
        String fieldName,
        boolean isWrite
    ) {}

    private final String filePath;
    private final FileImports imports;
    private final String fromMethodFqn;
    private final String enclosingClassFqn;
    private final List<CallSite> calls = new ArrayList<>();
    private final List<FieldAccessSite> fieldAccesses = new ArrayList<>();

    /**
     * Stack of variable-name → declared-type-FQN maps. Top of the stack is the
     * innermost block. Lookup walks bottom-of-stack first (parameters are at the
     * bottom; locals nest above). HashMap-per-scope is cheap enough that the
     * overhead is dwarfed by the savings on look-up cost.
     */
    private final Deque<Map<String, String>> scopeStack = new ArrayDeque<>();

    public BodyCollector(String filePath, FileImports imports,
                         String fromMethodFqn, String enclosingClassFqn) {
        this.filePath = filePath;
        this.imports = imports == null ? FileImports.EMPTY : imports;
        this.fromMethodFqn = fromMethodFqn;
        this.enclosingClassFqn = enclosingClassFqn;
    }

    public List<CallSite> calls()           { return calls; }
    public List<FieldAccessSite> fieldAccesses() { return fieldAccesses; }

    /** Walk a method declaration's body + parameters. */
    public void collectFromMethod(MethodDeclaration md) {
        pushScope();
        try {
            for (Parameter p : md.getParameters()) {
                bind(p.getNameAsString(), resolveTypeText(p.getType()));
            }
            md.getBody().ifPresent(this::walk);
        } finally {
            popScope();
        }
    }

    /** Walk a constructor body + parameters. */
    public void collectFromConstructor(ConstructorDeclaration cd) {
        pushScope();
        try {
            for (Parameter p : cd.getParameters()) {
                bind(p.getNameAsString(), resolveTypeText(p.getType()));
            }
            walk(cd.getBody());
        } finally {
            popScope();
        }
    }

    // ─── Recursive walker ────────────────────────────────────────────────

    private void walk(Node node) {
        if (node == null) return;
        Class<?> cls = node.getClass();

        // ── Scope-introducing nodes: push, walk children, pop. ────────────
        if (cls == BlockStmt.class) {
            pushScope();
            try {
                for (Node child : node.getChildNodes()) walk(child);
            } finally {
                popScope();
            }
            return;
        }
        if (cls == ForStmt.class) {
            pushScope();
            try {
                ForStmt fs = (ForStmt) node;
                fs.getInitialization().forEach(this::walk);
                fs.getCompare().ifPresent(this::walk);
                fs.getUpdate().forEach(this::walk);
                walk(fs.getBody());
            } finally {
                popScope();
            }
            return;
        }
        if (cls == ForEachStmt.class) {
            ForEachStmt fe = (ForEachStmt) node;
            pushScope();
            try {
                // The iteration variable
                VariableDeclarationExpr vde = fe.getVariable();
                for (VariableDeclarator v : vde.getVariables()) {
                    bind(v.getNameAsString(), resolveTypeText(v.getType()));
                }
                walk(fe.getIterable());
                walk(fe.getBody());
            } finally {
                popScope();
            }
            return;
        }
        if (cls == CatchClause.class) {
            CatchClause cc = (CatchClause) node;
            pushScope();
            try {
                Parameter cp = cc.getParameter();
                bind(cp.getNameAsString(), resolveTypeText(cp.getType()));
                walk(cc.getBody());
            } finally {
                popScope();
            }
            return;
        }

        // ── Declaration nodes: bind into the CURRENT scope (no push). ─────
        if (cls == VariableDeclarationExpr.class) {
            VariableDeclarationExpr vde = (VariableDeclarationExpr) node;
            for (VariableDeclarator v : vde.getVariables()) {
                String bindType = resolveTypeText(v.getType());
                if (v.getInitializer().isPresent()) {
                    Expression init = v.getInitializer().get();
                    if (init instanceof ObjectCreationExpr oce) {
                        String concreteType = resolveTypeText(oce.getType());
                        if (concreteType != null) bindType = concreteType;
                    }
                    // The initializer may contain calls / field accesses we still need to collect.
                    walk(init);
                }
                bind(v.getNameAsString(), bindType);
            }
            return;
        }

        // ── Call expressions ──────────────────────────────────────────────
        if (cls == MethodCallExpr.class) {
            collectCall((MethodCallExpr) node);
            // Walk the scope and arguments to catch nested calls (e.g. foo(bar()) ).
            MethodCallExpr mc = (MethodCallExpr) node;
            mc.getScope().ifPresent(this::walk);
            mc.getArguments().forEach(this::walk);
            return;
        }
        if (cls == ObjectCreationExpr.class) {
            collectConstructor((ObjectCreationExpr) node);
            ObjectCreationExpr oc = (ObjectCreationExpr) node;
            oc.getArguments().forEach(this::walk);
            oc.getScope().ifPresent(this::walk);
            // Anonymous class body: walk it so we collect any calls inside.
            oc.getAnonymousClassBody().ifPresent(list -> list.forEach(this::walk));
            return;
        }

        // ── Field access (read) ───────────────────────────────────────────
        if (cls == FieldAccessExpr.class) {
            collectFieldAccess((FieldAccessExpr) node, false);
            FieldAccessExpr fa = (FieldAccessExpr) node;
            walk(fa.getScope());
            return;
        }

        // ── Assignment (possible field write) ─────────────────────────────
        if (cls == AssignExpr.class) {
            AssignExpr ax = (AssignExpr) node;
            if (ax.getTarget() instanceof FieldAccessExpr faTarget) {
                collectFieldAccess(faTarget, true);
                walk(faTarget.getScope());
            } else {
                walk(ax.getTarget());
            }
            walk(ax.getValue());
            return;
        }

        // ── Default: recurse into children. ───────────────────────────────
        for (Node child : node.getChildNodes()) walk(child);
    }

    // ─── Site emission helpers ───────────────────────────────────────────

    private void collectCall(MethodCallExpr mc) {
        String simpleName = mc.getNameAsString();
        int arity = mc.getArguments().size();
        int line = mc.getBegin().map(p -> p.line).orElse(0);
        ScopeKind kind;
        String scopeText = "";
        String resolvedScopeFqn = null;
        Boolean resolved = true;

        if (mc.getScope().isEmpty()) {
            kind = ScopeKind.ENCLOSING_THIS;
        } else {
            Expression scope = unwrap(mc.getScope().get());
            try {
                // Calculate the resolved type of the scope expression
                ResolvedType resolvedType = scope.calculateResolvedType();

                if (resolvedType.isReferenceType()) {
                    // Get the fully qualified name (e.g., "com.example.MyService" or "java.lang.String")
                    resolvedScopeFqn = resolvedType.asReferenceType().getQualifiedName();
                }
            } catch (Throwable e) {
                // Broadened from UnsolvedSymbolException-only: calculateResolvedType()'s
                // generic-inference/overload-resolution internals can throw other runtime
                // exceptions (e.g. IndexOutOfBoundsException on raw/varargs/wildcard edge
                // cases). Any failure here must degrade to the import/scope-stack fallback
                // below, not escape — an uncaught throw here aborts extraction for the
                // ENTIRE file (see CoreExtractor's per-file catch).
                //System.err.println("Could not resolve scope: " + e.getClass().getSimpleName() + " " + e.getMessage());
                resolved = false;
            }
            if (scope instanceof ThisExpr te) {
                kind = ScopeKind.EXPLICIT_THIS;
                // calculateResolvedType() above already yields the innermost enclosing type
                // for `this` — but only when a SymbolSolver is attached, and it returns a
                // synthetic "Outer.Anonymous-<uuid>" inside anonymous class bodies, which
                // matches nothing in the index. Fall back to the lexical answer in both cases.
                if (!isUsableOwner(resolvedScopeFqn)) resolvedScopeFqn = explicitThisFqn(te);
            } else if (scope instanceof SuperExpr) {
                kind = ScopeKind.EXPLICIT_SUPER;
                // Same story for `super`: the solver resolves it to the superclass, but files
                // parsed without a SymbolSolver (see JavaProjectParser.buildParserNoSymbols)
                // throw on every resolve(), so read the extends clause off the AST instead.
                if (!isUsableOwner(resolvedScopeFqn)) resolvedScopeFqn = explicitSuperFqn(mc);
            } else if (scope instanceof NameExpr ne) {
                String name = ne.getNameAsString();
                scopeText = name;
                String varType = lookup(name);
                if (varType != null) {
                    kind = ScopeKind.LOCAL_VAR;
                    if (!resolved) resolvedScopeFqn = varType;
                } else if (isProbablyClassName(name)) {
                    kind = ScopeKind.STATIC_CLASS;
                } else {
                    kind = ScopeKind.UNKNOWN_NAME;
                }
            } else if (scope instanceof LiteralExpr literal) {
                // Literal scope: "foo".equals(x), 1.toString(), true.toString(), etc.
                // Resolve to the matching java.lang.* wrapper / String. Collapses
                // thousands of phantom nodes (one per distinct literal text) into one
                // canonical node per JDK method.
                String literalType = literalScopeTypeFqn(literal);
                if (literalType != null) {
                    kind = ScopeKind.LOCAL_VAR;          // treat as if scoped to a var of that type
                    if (!resolved) resolvedScopeFqn = literalType;
                    scopeText = literalType;             // no longer the noisy literal text
                } else {
                    kind = ScopeKind.CHAINED;
                    scopeText = scope.toString();
                }
            } else if (scope instanceof CastExpr cast) {
                // ((Foo) x).bar() — the called method belongs to Foo, not the inner expression's
                // type. Resolve Foo via imports so the call target unifies with our index.
                String castedType = resolveTypeText(cast.getType());
                if (castedType != null) {
                    kind = ScopeKind.LOCAL_VAR;
                    if (!resolved) resolvedScopeFqn = castedType;
                    scopeText = castedType;
                } else {
                    kind = ScopeKind.CHAINED;
                    scopeText = scope.toString();
                }
            } else if (scope instanceof FieldAccessExpr fae) {
                kind = ScopeKind.FIELD_REF;
                scopeText = fae.toString();
            } else {
                // Chained receiver — e.g. MailQueue.getInstance().addMail(x), a.b().c().d(),
                // new Foo().bar(). The cheap import/scope-stack paths can't type it, so ask
                // JavaParser's symbol solver for the receiver's static type; it recursively
                // resolves each hop's return type. On success this becomes a normal resolved
                // owner (LOCAL_VAR path); on failure we keep the legacy CHAINED behavior.
                String owner = resolveScopeType(scope);
                if (owner != null) {
                    kind = ScopeKind.LOCAL_VAR;
                    if (!resolved) resolvedScopeFqn = owner;
                    scopeText = owner;
                } else {
                    kind = ScopeKind.CHAINED;
                    scopeText = scope.toString();
                }
            }
        }

        calls.add(new CallSite(fromMethodFqn, enclosingClassFqn, filePath, line,
            kind, scopeText, resolvedScopeFqn, simpleName, arity));
    }

    private void collectConstructor(ObjectCreationExpr oc) {
        String resolvedFqn = "";
        int arity = oc.getArguments().size();
        int line = oc.getBegin().map(p -> p.line).orElse(0);
        String typeName = oc.getType().getNameAsString();
        // For constructors, the "owner" is the class being instantiated.
        // Resolve it the same way we resolve scope names: imports first, then same-package.
        try {
            // Resolve the ObjectCreationExpr's ClassOrInterfaceType
            ResolvedType resolvedType = oc.getType().resolve();

            // Convert to fully qualified name (e.g., "com.example.OuterClass.InnerClass")
            resolvedFqn = resolvedType.asReferenceType().getQualifiedName();


        } catch (Throwable t) {
            // Broadened from UnsolvedSymbolException-only, for the same reason as the scope
            // resolution in collectCall: resolve() throws IllegalStateException — NOT
            // UnsolvedSymbolException — when no SymbolResolver is attached at all, which is
            // exactly the case for every file over LARGE_FILE_THRESHOLD_BYTES (see
            // JavaProjectParser.buildParserNoSymbols). That escaped this catch, and
            // CoreExtractor's per-file guard then discarded the WHOLE file's extraction on
            // the first `new Foo()` it walked. Degrade to the import-based typeName instead.
            //System.err.println("Could not resolve type: " + t.getMessage());
            resolvedFqn = "";
        }
        calls.add(new CallSite(fromMethodFqn, enclosingClassFqn, filePath, line,
            ScopeKind.CONSTRUCTOR, typeName, resolvedFqn, "<init>", arity));
    }

    private void collectFieldAccess(FieldAccessExpr fa, boolean isWrite) {
        String fieldName = fa.getNameAsString();
        int line = fa.getBegin().map(p -> p.line).orElse(0);
        Expression scope = unwrap(fa.getScope());
        ScopeKind kind;
        String scopeText = "";
        String resolvedScopeFqn = null;

        if (scope instanceof ThisExpr) {
            kind = ScopeKind.EXPLICIT_THIS;
        } else if (scope instanceof NameExpr ne) {
            String name = ne.getNameAsString();
            scopeText = name;
            String varType = lookup(name);
            if (varType != null) {
                kind = ScopeKind.LOCAL_VAR;
                resolvedScopeFqn = varType;
            } else if (isProbablyClassName(name)) {
                kind = ScopeKind.STATIC_CLASS;
            } else {
                kind = ScopeKind.UNKNOWN_NAME;
            }
        } else if (scope instanceof LiteralExpr literal) {
            String literalType = literalScopeTypeFqn(literal);
            if (literalType != null) {
                kind = ScopeKind.LOCAL_VAR;
                resolvedScopeFqn = literalType;
                scopeText = literalType;
            } else {
                kind = ScopeKind.CHAINED;
                scopeText = scope.toString();
            }
        } else if (scope instanceof CastExpr cast) {
            String castedType = resolveTypeText(cast.getType());
            if (castedType != null) {
                kind = ScopeKind.LOCAL_VAR;
                resolvedScopeFqn = castedType;
                scopeText = castedType;
            } else {
                kind = ScopeKind.CHAINED;
                scopeText = scope.toString();
            }
        } else {
            kind = ScopeKind.CHAINED;
            scopeText = scope.toString();
        }

        fieldAccesses.add(new FieldAccessSite(fromMethodFqn, enclosingClassFqn,
            filePath, line, kind, scopeText, resolvedScopeFqn, fieldName, isWrite));
    }

    // ─── Scope-stack management ──────────────────────────────────────────

    private void pushScope() { scopeStack.push(new HashMap<>()); }
    private void popScope()  { scopeStack.pop(); }

    private void bind(String name, String typeFqn) {
        if (name == null || name.isEmpty()) return;
        Map<String, String> top = scopeStack.peek();
        if (top != null) top.put(name, typeFqn);
    }

    /** Walk the stack outer-to-inner; innermost binding wins (standard Java shadowing). */
    private String lookup(String name) {
        for (Map<String, String> scope : scopeStack) {
            String v = scope.get(name);
            if (v != null) return v;
        }
        return null;
    }

    // ─── Symbol-solver fallback for chained receivers ───────────────────

    /**
     * Resolve the static type of a chained receiver expression via JavaParser's symbol
     * solver, returning its raw FQN (no generics) for use as a call owner — or {@code null}
     * if it can't be typed (primitive/void/array-of-primitive, unresolved library type, or
     * no solver configured on this CU). {@code calculateResolvedType()} resolves the whole
     * chain recursively, so any depth of {@code a.b().c().d()} is handled in one call.
     */
    private static String resolveScopeType(Expression scope) {
        try {
            ResolvedType rt = scope.calculateResolvedType();
            if (rt.isReferenceType()) {
                // getQualifiedName() drops type arguments → matches how owners are keyed.
                return rt.asReferenceType().getQualifiedName();
            }
            if (rt.isArray()) {
                // Array receivers only expose java.lang.Object methods.
                return "java.lang.Object";
            }
            return null;
        } catch (Throwable t) {
            // UnsolvedSymbolException / UnsupportedOperationException / StackOverflowError on
            // pathological generics / "symbol resolution not configured" — degrade to CHAINED.
            return null;
        }
    }

    // ─── Type-text → FQN via imports ─────────────────────────────────────

    /**
     * Resolve a JavaParser {@link Type} to its FQN using imports + same-package.
     * Generic parameters are dropped. Returns the FQN string, or just the simple
     * name when no rule matches (we never return null — callers treat unresolved
     * types as themselves so look-ups can still partially succeed via name).
     */
    /**
     * Whether an FQN produced by the SymbolSolver is worth keeping as a call owner.
     *
     * <p>Rejects JavaParser's synthetic names for anonymous class bodies — it reports the
     * type of {@code this} inside {@code new Runnable(){...}} as
     * {@code pkg.A.Anonymous-2a38404c-c632-486c-9b3c-ad1b51e09523}, a per-parse UUID that
     * corresponds to no :Class node and would never unify across runs.
     */
    private static boolean isUsableOwner(String fqn) {
        return fqn != null && !fqn.isEmpty() && !fqn.contains(".Anonymous-");
    }

    /**
     * Owner type of an explicit {@code this} receiver, derived lexically.
     *
     * <p>Plain {@code this.foo()} binds to the innermost enclosing type, which is exactly
     * {@link #enclosingClassFqn} — the FQN CoreExtractor is already using for the method
     * being walked, so it unifies with the index by construction.
     *
     * <p>Qualified {@code Outer.this.foo()} binds to the NAMED enclosing type instead, so
     * peel {@code enclosingClassFqn} back to the segment that matches the qualifier:
     * {@code pkg.Outer.Inner} + {@code Outer} → {@code pkg.Outer}. Imports are only consulted
     * if that fails, since a qualified-this target is by definition an enclosing type of the
     * current one and therefore already present in the FQN.
     */
    private String explicitThisFqn(ThisExpr te) {
        String qualifier = te.getTypeName().map(Name::asString).orElse(null);
        if (qualifier == null) return enclosingClassFqn;

        String simple = qualifier.contains(".")
            ? qualifier.substring(qualifier.lastIndexOf('.') + 1) : qualifier;
        for (String cur = enclosingClassFqn; cur != null && !cur.isEmpty(); ) {
            if (cur.equals(simple) || cur.endsWith("." + simple)) return cur;
            int dot = cur.lastIndexOf('.');
            if (dot < 0) break;
            cur = cur.substring(0, dot);
        }
        String viaImports = imports.resolveSimpleName(simple);
        return viaImports != null ? viaImports : enclosingClassFqn;
    }

    /**
     * Owner type of an explicit {@code super} receiver, derived lexically: the declared
     * superclass of the type enclosing {@code node}.
     *
     * <p>Walks outward to the nearest enclosing type BODY rather than using
     * {@code findAncestor(ClassOrInterfaceDeclaration.class)}, because an anonymous class is
     * an {@link ObjectCreationExpr} and not a declaration — inside
     * {@code new Foo(){ ... super.bar() ... }} the target is {@code Foo}, whereas
     * findAncestor would skip past it and wrongly report the outer class's superclass.
     *
     * <p>A class with no {@code extends} clause reports {@code java.lang.Object}, which is
     * the truthful answer; it simply won't match a project :Class node downstream.
     */
    private String explicitSuperFqn(Node node) {
        for (Node cur = node.getParentNode().orElse(null); cur != null;
             cur = cur.getParentNode().orElse(null)) {
            if (cur instanceof ObjectCreationExpr oce && oce.getAnonymousClassBody().isPresent()) {
                return resolveTypeText(oce.getType());
            }
            if (cur instanceof ClassOrInterfaceDeclaration cd) {
                return cd.getExtendedTypes().getFirst()
                    .map(this::resolveTypeText)
                    .orElse("java.lang.Object");
            }
        }
        return null;
    }

    private String resolveTypeText(Type type) {
        if (type == null) return null;
        String text = type.asString();
        int lt = text.indexOf('<');
        if (lt > 0) text = text.substring(0, lt);
        text = text.trim();
        // Strip array brackets — array methods are inherited from java.lang.Object.
        while (text.endsWith("[]")) text = text.substring(0, text.length() - 2).trim();
        if (text.isEmpty()) return null;
        // Already qualified
        if (text.contains(".")) return text;
        String fqn = imports.resolveSimpleName(text);
        if (fqn != null) return fqn;
        // Java implicit imports
        if (isJavaLangSimpleName(text)) return "java.lang." + text;
        // Unknown — keep the simple name; downstream lookups using just the simple name
        // can still hit when the index has a unique match.
        return text;
    }

    private static boolean isProbablyClassName(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    /**
     * Map a Java literal to its java.lang.* wrapper FQN for scope-resolution purposes.
     * E.g. {@code "foo"} → {@code java.lang.String}, {@code 1L} → {@code java.lang.Long}.
     * Returns {@code null} for {@code null} literal (no methods callable).
     */
    /**
     * Strip away noise nodes that wrap the real scope expression.
     * Handles {@code (expr)} (parenthesized) and {@code (Type) expr} (cast).
     * Repeats until a non-noise node is found, so {@code ((String) foo).bar()} unwraps
     * to {@code foo} and {@code ((("x"))).method()} unwraps to {@code "x"}.
     */
    private static Expression unwrap(Expression e) {
        while (true) {
            if (e instanceof EnclosedExpr enc) {
                e = enc.getInner();
            } else if (e instanceof CastExpr ce) {
                // Cast scope: the called method belongs to the CAST type, not the inner expression.
                // For now treat as chained — preserves existing behavior. Future work: emit the
                // cast type as resolvedScopeFqn so the resolver can look up methods on it.
                return ce;
            } else {
                return e;
            }
        }
    }

    private static String literalScopeTypeFqn(LiteralExpr literal) {
        if (literal instanceof StringLiteralExpr)  return "java.lang.String";
        if (literal instanceof IntegerLiteralExpr) return "java.lang.Integer";
        if (literal instanceof LongLiteralExpr)    return "java.lang.Long";
        if (literal instanceof BooleanLiteralExpr) return "java.lang.Boolean";
        if (literal instanceof CharLiteralExpr)    return "java.lang.Character";
        if (literal instanceof DoubleLiteralExpr)  return "java.lang.Double";
        // NullLiteralExpr → no callable methods; let it fall through to CHAINED
        return null;
    }

    /** Names that resolve to {@code java.lang.X} via implicit imports. */
    private static boolean isJavaLangSimpleName(String name) {
        // Hot path — keep the list small + ordered by frequency in real Java code.
        switch (name) {
            case "String": case "Object": case "Integer": case "Long": case "Boolean":
            case "Double": case "Float": case "Byte": case "Character": case "Short":
            case "Number": case "Math": case "System": case "Thread": case "Throwable":
            case "Exception": case "RuntimeException": case "Error": case "Class":
            case "Void": case "Iterable": case "Comparable": case "CharSequence":
            case "StringBuilder": case "StringBuffer":
                return true;
            default:
                return false;
        }
    }
}
