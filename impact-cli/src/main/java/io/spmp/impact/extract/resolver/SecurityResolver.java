package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LongLiteralExpr;
import com.github.javaparser.ast.expr.IntegerLiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.model.GraphEdges.RequiresPermissionEdge;
import io.spmp.impact.model.GraphEdges.ValidatesInputEdge;
import io.spmp.impact.model.GraphNodes.PermissionNode;
import io.spmp.impact.model.GraphNodes.ValidatorNode;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * §4.1 rules S1, S2 — security boundaries. Three detection patterns for S1 — see
 * {@code docs/BOUNDARY_DESTINATIONS.md} §4 for the verified ADMP destination.
 *
 * <h2>S1 REQUIRES_PERMISSION</h2>
 *
 * <p>Pattern A — annotation-driven (Spring / JAX-RS):
 * <ul>
 *   <li>{@code @PreAuthorize("hasAuthority('FOO')")} / {@code @PreAuthorize("hasRole('ADMIN')")}</li>
 *   <li>{@code @Secured("ROLE_ADMIN")} / {@code @RolesAllowed({"ADMIN"})}</li>
 * </ul>
 *
 * <p>Pattern B — keyword-scoped runtime check (legacy / non-ADSM products):
 * <ul>
 *   <li>{@code AdmAccessChecker.check(PERM)} / {@code AccessManager.verify(PERM)} /
 *       {@code PermissionChecker.has(PERM)}</li>
 * </ul>
 *
 * <p>Pattern C — ADMP-specific (D3): chained {@code .getActionList().contains(<const>)}.
 * ADSM stores the technician's permitted actions in a HashSet on
 * {@link com.adventnet.sym.adsm.common.server.helpdesk.ADMPAuthObject}; every action-gated
 * code path does:
 * <pre>{@code
 *   ADMPAuthObject auth = (ADMPAuthObject) session.getAttribute(...);
 *   if (!auth.getActionList().contains(ActionConstants.WORKFLOW_REJECT)) {
 *       // permission denied
 *   }
 * }</pre>
 * Detection: find a {@code MethodCallExpr} with name {@code contains} whose scope is
 * itself a {@code MethodCallExpr} with name {@code getActionList} (any overload — ADSM
 * has 0-arg and 1-arg domain-name overloads). The {@code contains}'s first argument is
 * the permission id — extract the simple name from a {@link FieldAccessExpr}
 * ({@code ActionConstants.WORKFLOW_REJECT} → {@code WORKFLOW_REJECT}), the variable name
 * from a bare {@link NameExpr}, the value from a {@link StringLiteralExpr}, or the
 * literal value from a {@link LongLiteralExpr} / {@link IntegerLiteralExpr}.
 *
 * <p>Pattern C is intentionally LOOSE about the receiver type — we accept any chained
 * {@code .getActionList().contains(...)} call regardless of the upstream variable type.
 * False-positive risk is low because the simple-name + chain pattern is distinctive
 * (verified: ADSM has exactly one {@code getActionList} API on the auth object).
 *
 * <h2>S2 VALIDATES_INPUT</h2>
 *
 * <ul>
 *   <li>{@code @Valid} on a method parameter (Bean Validation) — validator id = parameter type name.</li>
 *   <li>{@code Validator.validate(x)} / {@code InputSanitizer.scan(x)} call sites — validator id = scope text.</li>
 * </ul>
 */
public class SecurityResolver implements BoundaryResolver {

    private static final Set<String> PERMISSION_ANNOTATIONS = Set.of(
        "PreAuthorize", "PostAuthorize", "Secured", "RolesAllowed", "PermissionRequired",
        "RequirePermission"
    );

    private static final Set<String> PERMISSION_SCOPES = Set.of(
        "AccessChecker", "AccessManager", "AdmAccessChecker", "PermissionChecker",
        "AuthorizationManager", "Authz", "Authorization"
    );
    private static final Set<String> PERMISSION_METHODS = Set.of(
        "check", "verify", "has", "hasPermission", "require", "assertPermission",
        "checkPermission", "ensure"
    );

    private static final Set<String> VALIDATOR_SCOPES = Set.of(
        "Validator", "Validation", "InputValidator", "InputSanitizer", "Sanitizer",
        "BeanValidator"
    );
    private static final Set<String> VALIDATOR_METHODS = Set.of(
        "validate", "scan", "sanitize", "verify", "check", "assertValid"
    );

    // ── D3 (Pattern C) — ADMP getActionList().contains(X) ──────────────
    /** Inner-method on the chain — verified against ADMPAuthObject / RestAPIAuthObject. */
    private static final String GET_ACTION_LIST = "getActionList";
    /** Outer-method on the chain — Set.contains / HashSet.contains. */
    private static final String CONTAINS = "contains";

    private int perms = 0, vals = 0, admpPerms = 0;

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
        Set<String> permIds = new HashSet<>();
        Set<String> validatorIds = new HashSet<>();

        // (a) Annotation-driven S1 on method declarations.
        for (MethodDeclaration md : cu.findAll(MethodDeclaration.class)) {
            String methodFqn = null;
            for (AnnotationExpr ann : md.getAnnotations()) {
                if (!PERMISSION_ANNOTATIONS.contains(ann.getNameAsString())) continue;
                String id = extractPermissionFromAnnotation(ann);
                if (id == null) continue;
                if (methodFqn == null) methodFqn = ResolverUtils.methodFqn(md, pkg);
                batch.requiresPermission.add(new RequiresPermissionEdge(methodFqn, id));
                permIds.add(id);
                perms++;
            }
            // S2 — @Valid on a parameter
            for (Parameter p : md.getParameters()) {
                for (AnnotationExpr ann : p.getAnnotations()) {
                    if (!ann.getNameAsString().equals("Valid")) continue;
                    String validatorId = p.getType().asString();
                    if (validatorId.isEmpty()) continue;
                    if (methodFqn == null) methodFqn = ResolverUtils.methodFqn(md, pkg);
                    batch.validatesInput.add(new ValidatesInputEdge(methodFqn, validatorId));
                    validatorIds.add(validatorId);
                    vals++;
                }
            }
        }

        // (b) Runtime call sites — D3 (Pattern C ADMP) / Pattern B keyword-scoped / S2.
        // Dedup by (fromFqn, permId) so a method calling getActionList().contains(X)
        // twice (rare but legal — e.g. on both domain-scoped and global lists) emits
        // one edge per (caller, permId) pair.
        Set<String> permEmitted = new HashSet<>();
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            String scope = call.getScope().map(Object::toString).orElse("");

            // ── S1 Pattern C — ADMP `.getActionList().contains(<const>)` chain ──
            // Match shape: outer call is .contains(...) and its scope is .getActionList().
            if (CONTAINS.equals(name) && isOnGetActionList(call)) {
                String permId = extractPermissionConstant(call.getArguments());
                if (permId != null && !permId.isEmpty()) {
                    String fromFqn = ResolverUtils.enclosingMethodFqn(call, pkg);
                    if (fromFqn != null && permEmitted.add(fromFqn + "|" + permId)) {
                        batch.requiresPermission.add(new RequiresPermissionEdge(fromFqn, permId));
                        permIds.add(permId);
                        admpPerms++;
                        continue;  // avoid double-counting under Pattern B below
                    }
                }
            }

            // ── S1 Pattern B — AccessChecker.check / AccessManager.verify ──
            if (PERMISSION_METHODS.contains(name) && scopeMatchesAny(scope, PERMISSION_SCOPES)) {
                String id = firstStringArg(call);
                if (id != null) {
                    String fromFqn = ResolverUtils.enclosingMethodFqn(call, pkg);
                    if (fromFqn != null && permEmitted.add(fromFqn + "|" + id)) {
                        batch.requiresPermission.add(new RequiresPermissionEdge(fromFqn, id));
                        permIds.add(id);
                        perms++;
                    }
                }
            }
            // S2 — input validation
            if (VALIDATOR_METHODS.contains(name) && scopeMatchesAny(scope, VALIDATOR_SCOPES)) {
                String id = scope.isEmpty() ? name : scope;
                String fromFqn = ResolverUtils.enclosingMethodFqn(call, pkg);
                if (fromFqn != null) {
                    batch.validatesInput.add(new ValidatesInputEdge(fromFqn, id));
                    validatorIds.add(id);
                    vals++;
                }
            }
        }

        for (String id : permIds) batch.permissions.add(new PermissionNode(id));
        for (String id : validatorIds) batch.validators.add(new ValidatorNode(id));
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        System.out.printf("[SecurityResolver] requires_permission=%d (admp_perms=%d) validates_input=%d%n",
            perms + admpPerms, admpPerms, vals);
    }

    // ─── D3 helpers — getActionList().contains(<const>) ────────────────

    /**
     * Return true if the given {@code contains(...)} call's scope is itself a call to
     * {@link #GET_ACTION_LIST}. Accepts both no-arg ({@code getActionList()}) and
     * 1-arg ({@code getActionList(domainName)}) overloads — both populate the same
     * action-list HashSet in ADSM.
     */
    private static boolean isOnGetActionList(MethodCallExpr containsCall) {
        var scopeOpt = containsCall.getScope();
        if (scopeOpt.isEmpty()) return false;
        if (!(scopeOpt.get() instanceof MethodCallExpr inner)) return false;
        return GET_ACTION_LIST.equals(inner.getNameAsString());
    }

    /**
     * Extract the permission id from the {@code contains(...)} argument list. Order of
     * preference (most → least informative):
     * <ol>
     *   <li>{@link FieldAccessExpr}: {@code ActionConstants.WORKFLOW_REJECT} → simple name
     *       {@code WORKFLOW_REJECT}. This is the dominant pattern in ADSM.</li>
     *   <li>{@link NameExpr}: bare {@code WORKFLOW_REJECT} (rare — only when the constant
     *       is statically imported). Identifier name used as id.</li>
     *   <li>{@link StringLiteralExpr}: string-keyed permission (legacy). The literal value.</li>
     *   <li>{@link LongLiteralExpr} / {@link IntegerLiteralExpr}: numeric action id
     *       (least informative but still better than dropping the edge — the id will
     *       look like {@code "1042"} in the report and QA can map via DB).</li>
     * </ol>
     * Returns {@code null} when the arg is a method call, conditional, or other runtime
     * expression we can't statically pin down — those are skipped (the regular {@code :CALLS}
     * edge still records the {@code contains} call).
     */
    private static String extractPermissionConstant(com.github.javaparser.ast.NodeList<Expression> args) {
        if (args == null || args.isEmpty()) return null;
        Expression first = args.get(0);
        if (first instanceof FieldAccessExpr fae) return fae.getNameAsString();
        if (first instanceof NameExpr ne)         return ne.getNameAsString();
        if (first instanceof StringLiteralExpr sle) return sle.getValue();
        if (first instanceof LongLiteralExpr lle)   return lle.getValue();
        if (first instanceof IntegerLiteralExpr ile) return ile.getValue();
        return null;
    }

    private static boolean scopeMatchesAny(String scope, Set<String> needles) {
        if (scope == null || scope.isEmpty()) return false;
        for (String n : needles) if (scope.contains(n)) return true;
        return false;
    }

    private static String firstStringArg(MethodCallExpr call) {
        if (call.getArguments() == null) return null;
        for (Expression a : call.getArguments()) {
            String s = ResolverUtils.stringArg(a);
            if (s != null && !s.isEmpty()) return s;
        }
        return null;
    }

    /**
     * Extract the permission id from a security annotation. Parses common SpEL forms:
     * {@code hasAuthority('FOO')}, {@code hasRole('ADMIN')}, and bare role names.
     */
    private static String extractPermissionFromAnnotation(AnnotationExpr ann) {
        String raw = null;
        if (ann instanceof SingleMemberAnnotationExpr smae) {
            var v = smae.getMemberValue();
            if (v instanceof StringLiteralExpr sle) raw = sle.getValue();
        } else if (ann instanceof NormalAnnotationExpr nae) {
            for (var pair : nae.getPairs()) {
                if (!pair.getNameAsString().equals("value")) continue;
                if (pair.getValue() instanceof StringLiteralExpr sle) raw = sle.getValue();
            }
        }
        if (raw == null) return null;
        // hasAuthority('FOO') / hasRole('ADMIN') — pull the inner identifier.
        int sq = raw.indexOf('\'');
        if (sq >= 0) {
            int sq2 = raw.indexOf('\'', sq + 1);
            if (sq2 > sq) return raw.substring(sq + 1, sq2);
        }
        int dq = raw.indexOf('"');
        if (dq >= 0) {
            int dq2 = raw.indexOf('"', dq + 1);
            if (dq2 > dq) return raw.substring(dq + 1, dq2);
        }
        return raw.trim();
    }
}
