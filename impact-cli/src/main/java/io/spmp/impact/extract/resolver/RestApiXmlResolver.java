package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration;
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFacade;

import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.model.GraphEdges.CallEdge;
import io.spmp.impact.model.GraphEdges.ExposesEdge;
import io.spmp.impact.model.GraphNodes.RestEndpointNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses SPMP's REST-API descriptor XML files to replace the stub
 * {@code "servlet:ClassName"} URLs (emitted by {@link ServletResolver}) with the real
 * {@code /RestAPI/...} URLs the front-end actually hits.
 *
 * <p>Files scanned (auto-detected under {@code <repo>/product_package/conf/}):
 * <ul>
 *   <li>{@code adsf/ADSProductAPIS.xml} — every {@code <ADSProductAPIs>} entry has
 *       {@code API_URL} + {@code SERVLET_CLASS_NAME} attributes. We emit
 *       {@code (Servlet class)-[:EXPOSES]->(:RestEndpoint {url: API_URL})}.</li>
 * </ul>
 *
 * <p>Both the stub and the real URL coexist as separate :RestEndpoint nodes (different
 * keys). Test-case generation and slice queries prefer the real URLs when present.
 *
 * <p>Implementation note: this resolver's {@link #visit} is a no-op because the work
 * happens against XML files outside the Java AST. All emission is done in {@link #afterAll}.
 */
public class RestApiXmlResolver implements BoundaryResolver {

    /** All XML-config roots to walk. Primary + every dep repo's config root. */
    private final java.util.List<Path> xmlConfigRoots;;
    private final Path adsProductApisXml;
    private List<String> admpApiActionURLs = new ArrayList<>();
    private final JavaParserFacade javaParserFacade;


    public RestApiXmlResolver(Path adsProductApisXml, JavaParserFacade javaParserFacade) {
        this.adsProductApisXml = adsProductApisXml;
        this.xmlConfigRoots = null;
        this.javaParserFacade = javaParserFacade;
    }

    /** Multi-root constructor — for multi-repo ingest where every dep contributes a conf dir. */
    public RestApiXmlResolver(java.util.List<Path> xmlConfigRoots) {
        this.xmlConfigRoots = xmlConfigRoots == null ? java.util.List.of() : xmlConfigRoots;
        this.adsProductApisXml = null;
        this.javaParserFacade = null;
    }

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // no-op: this resolver doesn't care about Java AST.
    }

    @Override
    public void afterAll(ExtractionBatch master) {
        int totalEndpoints = parseAdsProductApis(adsProductApisXml, master);
        int admpExposes = emitAdmpApiActionExposes(master);

        // (Future extensions: web.xml, SPMPServletActions.xml, etc.)

        System.out.printf("[RestApiXmlResolver] resolved %d real REST endpoints from XML config, %d ADMPAPIAction branch EXPOSES edges%n",
            totalEndpoints, admpExposes);
    }

    /** Locate a file by name regardless of case. Returns null if missing. */
    private static Path findCaseInsensitive(Path dir, String fileName) {
        if (dir == null || !Files.isDirectory(dir)) return null;
        try (java.util.stream.Stream<Path> children = Files.list(dir)) {
            return children
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().equalsIgnoreCase(fileName))
                .findFirst()
                .orElse(null);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private int parseAdsProductApis(Path xml, ExtractionBatch master) {
        Set<String> seenUrls = new HashSet<>();
        int emitted = 0;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            // Hardening against XXE for untrusted input — config files are trusted but
            // best practice anyway.
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
            catch (Exception ignore) {}
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(xml.toFile());

            NodeList apis = doc.getElementsByTagName("ADSProductAPIs");
            for (int i = 0; i < apis.getLength(); i++) {
                Node n = apis.item(i);
                if (!(n instanceof Element e)) continue;
                String url = attr(e, "API_URL");
                String servlet = attr(e, "SERVLET_CLASS_NAME");
                String apiName = attr(e, "API_NAME");
                String mtCall = attr(e, "MTCALL_VALUE");
                if (url == null || url.isEmpty()) continue;

                // Compose the canonical URL key. If multiple entries reuse the same URL
                // (different MTCALL_VALUE), keep them distinct so tests can reference them
                // separately.
                if(url != null && servlet !=null){
                    if (seenUrls.add(url)) {
                            master.restEndpoints.add(new RestEndpointNode(
                                url,
                                servlet == null ? "" : servlet
                            ));
                            emitted++;
                        }
                    if(!servlet.contains("ADMPAPIAction")){

                        
                        if (servlet != null && !servlet.isEmpty()) {
                            // Stamp the per-URL MTCALL_VALUE as target_method_simple_name so the
                            // analyze filter can drop sibling URLs that share the same Servlet
                            // class (e.g. /WorkFlow.do?unspecified vs /WorkFlow.do?approveRequest).
                            // Empty mtCall preserves class-granularity for URLs that aren't
                            // dispatcher-routed.
                            master.exposes.add(new ExposesEdge(servlet, url, mtCall == null ? "" : mtCall));
                        }
                    }else{
                        admpApiActionURLs.add(url);
                    }
                }
            }
        } catch (Throwable t) {
            System.err.println("[RestApiXmlResolver] error parsing " + xml + " : " + t.getMessage());
        }
        return emitted;
    }

    /**
     * ADMPAPIAction is a dispatcher servlet whose execute() method routes by checks like
     * {@code requestURI.contains("CreateUserOld")}. The XML maps those concrete URLs to
     * ADMPAPIAction, while the Java branch calls the real implementation methods. This pass
     * joins both sides: each call inside the matching if-branch exposes the matched URL.
     */
    private int emitAdmpApiActionExposes(ExtractionBatch master) {
        if (admpApiActionURLs.isEmpty() || master.AdsAPIMethodDeclarion.isEmpty()) return 0;


        Set<String> emittedKeys = new HashSet<>();
        int emitted = 0;
        for (MethodDeclaration executeMethod : master.AdsAPIMethodDeclarion) {

            for (IfStmt ifStmt : executeMethod.findAll(IfStmt.class)) {
                String requestUriToken = requestUriContainsToken(ifStmt.getCondition());
                if (requestUriToken == null || requestUriToken.isEmpty()) continue;
                List<String> matchedUrls = matchedAdmpUrl(requestUriToken);
                if (matchedUrls.isEmpty()) continue;



                Map<String, String> branchCallNames = GetCallsFromThenStatement(ifStmt.getThenStmt());
                if (branchCallNames.isEmpty()) continue;
                for (Map.Entry<String, String> entry : branchCallNames.entrySet()) {
                    String calledSimple = entry.getKey();
                    String owner = entry.getValue();
                    if (calledSimple == null || owner == null || owner.isEmpty()) continue;
                    for (String matchedUrl : matchedUrls) {
                        String key = owner + "|" + calledSimple + "|" + matchedUrl;
                        if (emittedKeys.add(key)) {
                            master.exposes.add(new ExposesEdge(owner, matchedUrl, calledSimple));
                            emitted++;
                        }
                    }
                }
            }
        }
        return emitted;
    }


    private static String requestUriContainsToken(Expression condition) {
        for (MethodCallExpr call : condition.findAll(MethodCallExpr.class)) {
            if (!"contains".equals(call.getNameAsString())) continue;
            if (call.getScope().isEmpty() || !"requestURI".equals(call.getScope().get().toString())) continue;
            if (call.getArguments().size() != 1 || !(call.getArgument(0) instanceof StringLiteralExpr literal)) continue;
            return literal.getValue();
        }
        return null;
    }

    private List<String> matchedAdmpUrl(String requestUriToken) {
        List<String> matchedURLs = new ArrayList<>();
        for (String url : admpApiActionURLs) {
            if (url != null && url.endsWith(requestUriToken)) matchedURLs.add(url);
        }
        return matchedURLs;
    }

    private  Map<String, String> GetCallsFromThenStatement(Statement statement) {
        Map<String, String> names = new HashMap<>();
        String className = null;
        for (MethodCallExpr call : statement.findAll(MethodCallExpr.class)) {
            try{
                ResolvedMethodDeclaration resolved =javaParserFacade.solve(call).getCorrespondingDeclaration();

                className = resolved.declaringType().getQualifiedName();
            }catch (Throwable e) {
            System.err.println("[RestApiXmlResolver] failed to resolve MethodDeclaration expr `"
                + call + "`: " + e.getMessage());
            }

            names.put(call.getNameAsString(), className);
        }
        return names;
    }

    private static String ownerFqn(String methodFqn) {
        String prefix = methodPrefix(methodFqn);
        if (prefix == null) return null;
        int dot = prefix.lastIndexOf('.');
        return dot < 0 ? null : prefix.substring(0, dot);
    }

    private static String simpleMethodName(String methodFqn) {
        String prefix = methodPrefix(methodFqn);
        if (prefix == null) return null;
        int dot = prefix.lastIndexOf('.');
        return dot < 0 ? prefix : prefix.substring(dot + 1);
    }

    private static String methodPrefix(String methodFqn) {
        if (methodFqn == null || methodFqn.isEmpty()) return null;
        int paren = methodFqn.indexOf('(');
        return paren > 0 ? methodFqn.substring(0, paren) : methodFqn;
    }

    private static String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }
}
