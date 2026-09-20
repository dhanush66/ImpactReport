package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.model.GraphEdges.ExposesEdge;
import io.spmp.impact.model.GraphNodes.MethodNode;
import io.spmp.impact.model.GraphNodes.RestEndpointNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Post-processing resolver that expands class-level URL mappings into
 * individual method-level endpoint nodes.
 *
 * <p><b>Problem:</b> Some XML resolvers (primarily {@link WebXmlResolver} and
 * {@link ServletResolver}) emit {@code (Class)-[:EXPOSES]->(:RestEndpoint)} edges
 * when the XML config declares a URL-to-class mapping but contains no method
 * information. For Struts-style dispatcher actions (e.g. {@code /WorkFlow.do →
 * WorkFlowAction}) and plain servlets, this means the impact analyzer can trace
 * backward from a changed method to its class, but cannot continue to the
 * REST endpoint — the EXPOSES edge attaches to the class node, not the method.
 *
 * <p><b>Solution:</b> For every class-level entry in {@code batch.exposes}
 * (i.e. {@code targetMethodSimpleName} is blank), enumerate all parsed methods of
 * that class from {@code batch.methods} and create virtual, per-method URL nodes:
 * <pre>
 *   base URL:           /WorkFlow.do
 *   method simple name: approveRequest
 *   virtual URL:        /WorkFlow.do/approveRequest
 *   new edge:           WorkFlowAction.approveRequest -[:EXPOSES]-> /WorkFlow.do/approveRequest
 * </pre>
 *
 * <p>The original class-level edge ({@code WorkFlowAction -[:EXPOSES]-> /WorkFlow.do})
 * is preserved; this resolver only ADDS new method-level entries — it never removes
 * existing edges.
 *
 * <p><b>Scope:</b>
 * <ul>
 *   <li>Only expands entries where {@code targetMethodSimpleName} is empty —
 *       entries already pointing at a specific method (e.g. from
 *       {@link SecurityXmlResolver}'s {@code apimethod} attribute,
 *       {@link RestApiXmlResolver}'s {@code MTCALL_VALUE}, or
 *       {@link ServletForwardConfigResolver}) are left untouched.</li>
 *   <li>Skips constructors ({@code <init>}, {@code <clinit>}) and overloaded
 *       methods that share the same simple name — one virtual URL per unique
 *       simple name per class is created (the Neo4j writer resolves the specific
 *       overload via {@code OPTIONAL MATCH} when writing the EXPOSES edge).</li>
 * </ul>
 *
 * <p>This resolver has no side-effects outside {@code batch} — it only appends
 * to {@code batch.restEndpoints} and {@code batch.exposes}. It must be registered
 * AFTER all XML resolvers in the resolver list so it can see all class-level entries.
 */
public class ClassMethodExpansionResolver implements BoundaryResolver {

    /**
     * Method simple names that must never be used as dispatch targets — either
     * they're Java lifecycle methods inherited from HttpServlet/Filter/Action,
     * or they're synthetic names that can't appear in a URL segment.
     */
    private static final Set<String> SKIP_METHODS = Set.of(
        "<init>", "<clinit>",
        "service", "doGet", "doPost", "doPut", "doDelete",
        "doHead", "doOptions", "doTrace", "doFilter",
        "init", "destroy", "getServletConfig", "getServletInfo",
        "getLastModified", "log"
    );

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // no-op: all work happens in afterAll once every resolver has contributed.
    }

    /**
     * Struts dispatch sentinel: ADSProductAPIs.xml entries with {@code MTCALL_VALUE="unspecified"}
     * register the framework dispatcher method, not a real action. The actual business
     * methods (approveRequest, commitRequest, …) are reached by stripping this suffix
     * from the URL and enumerating the class's own methods.
     */
    private static final String STRUTS_DISPATCH_SENTINEL = "unspecified";

    @Override
    public void afterAll(ExtractionBatch batch) {
        // ── Step 1: collect all class-level EXPOSES entries ──────────────────
        // Key: classFqn  →  set of base URLs that have a class-level mapping.
        // Two cases qualify:
        //   (a) targetMethodSimpleName is blank   — WebXmlResolver / ServletResolver
        //   (b) targetMethodSimpleName == "unspecified" — RestApiXmlResolver MTCALL_VALUE
        //       sentinel for Struts dispatcher actions; base URL = url.stripSuffix("?unspecified")
        //
        // We track which (classFqn, baseUrl) pairs we've already processed to avoid
        // emitting duplicates when multiple resolvers emit the same (class, url).
        Map<String, Set<String>> classToBaseUrls = new LinkedHashMap<>();
        for (ExposesEdge edge : batch.exposes) {
            String method = edge.targetMethodSimpleName();
            String baseUrl;
            if (method == null || method.isBlank()) {
                // Case (a): plain class-level mapping
                baseUrl = edge.url();
            } else if (STRUTS_DISPATCH_SENTINEL.equals(method)) {
                // Case (b): Struts dispatch — strip "?unspecified" to get the base URL
                String url = edge.url();
                baseUrl = url.endsWith("?" + STRUTS_DISPATCH_SENTINEL)
                    ? url.substring(0, url.length() - ("?" + STRUTS_DISPATCH_SENTINEL).length())
                    : url;
            } else {
                continue; // already has a real method — skip
            }
            classToBaseUrls
                .computeIfAbsent(edge.classFqn(), k -> new LinkedHashSet<>())
                .add(baseUrl);
        }
        if (classToBaseUrls.isEmpty()) return;

        // ── Step 2: build class → unique method simple names index ───────────
        // batch.methods is never drained by drainLeafCollections(), so it is safe
        // to read here even after partial flushes have been issued.
        Map<String, Set<String>> classToMethods = new LinkedHashMap<>();
        for (MethodNode m : batch.methods) {
            if (m.ownerFqn() == null || m.simpleName() == null) continue;
            if (!classToBaseUrls.containsKey(m.ownerFqn())) continue; // not a class we care about
            if (SKIP_METHODS.contains(m.simpleName())) continue;
            classToMethods
                .computeIfAbsent(m.ownerFqn(), k -> new LinkedHashSet<>())
                .add(m.simpleName());
        }

        // ── Step 3: emit virtual method-level endpoints ───────────────────────
        Set<String> seenEndpoints = new LinkedHashSet<>();
        int endpointsAdded = 0, exposesAdded = 0;
        List<RestEndpointNode> newEndpoints = new ArrayList<>();
        List<ExposesEdge>      newExposes   = new ArrayList<>();

        for (Map.Entry<String, Set<String>> classEntry : classToBaseUrls.entrySet()) {
            String classFqn = classEntry.getKey();
            Set<String> baseUrls = classEntry.getValue();
            Set<String> methodNames = classToMethods.getOrDefault(classFqn, Set.of());
            if (methodNames.isEmpty()) continue;

            for (String baseUrl : baseUrls) {
                for (String methodName : methodNames) {
                    // Virtual URL: base + "/" + methodName
                    // Example: /WorkFlow.do/approveRequest
                    String virtualUrl = baseUrl + "/" + methodName;
                    String edgeKey = classFqn + "\u0000" + virtualUrl + "\u0000" + methodName;
                    if (!seenEndpoints.add(edgeKey)) continue;

                    newEndpoints.add(new RestEndpointNode(virtualUrl, ""));
                    newExposes.add(new ExposesEdge(classFqn, virtualUrl, methodName));
                    endpointsAdded++;
                    exposesAdded++;
                }
            }
        }

        batch.restEndpoints.addAll(newEndpoints);
        batch.exposes.addAll(newExposes);

        if (exposesAdded > 0) {
            System.out.printf(
                "[ClassMethodExpansionResolver] expanded %d class-level URL mapping(s) across %d class(es)" +
                " (blank-method + Struts-dispatch) → added %d virtual endpoints, %d method-level EXPOSES edges%n",
                classToBaseUrls.values().stream().mapToInt(Set::size).sum(),
                classToBaseUrls.size(),
                endpointsAdded,
                exposesAdded);
        }
    }
}
