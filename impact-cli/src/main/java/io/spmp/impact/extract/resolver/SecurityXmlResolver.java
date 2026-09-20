package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Parses REST-URL declarations from security-policy XML files —
 * specifically ADManager Plus's {@code web/<app>/WEB-INF/security/security*.xml}
 * style configs.
 *
 * <p>The {@code <url>} element looks like:
 * <pre>{@code
 *   <url path="/api/v2/orchestrations/(\d+)/execute" method="post"
 *        apimethod="com.adventnet.sym.adsm.common.webclient.api.v2.OrchestrationServiceAPI.executeOrchestration">
 *     <param name="from" type="int" allow-empty="false" range=">0"/>
 *   </url>
 * }</pre>
 *
 * <p>This resolver:
 * <ol>
 *   <li>Walks every registered repo root looking for {@code WEB-INF/security/security*.xml}</li>
 *   <li>Extracts every {@code <url path>}, emits a {@code :RestEndpoint} node</li>
 *   <li>For URLs with an explicit {@code apimethod} attribute (format:
 *       {@code com.package.ClassName.methodName}), emits an {@code :EXPOSES} edge</li>
 *   <li>URLs <b>without</b> {@code apimethod} only get a {@code :RestEndpoint} node
 *       (no EXPOSES edge) — these files define URL parameter validation/throttle policy,
 *       NOT routing. The heuristic last-segment matching is excluded to prevent
 *       false-positive EXPOSES edges.</li>
 * </ol>
 *
 * <p>Only {@code security-api-v2.xml} carries the {@code apimethod} attribute in
 * the ADSM codebase. All other security XMLs ({@code security.xml},
 * {@code security-restapi.xml}, etc.) are validation-only.
 */
public class SecurityXmlResolver implements BoundaryResolver {

    /** Repo root to walk for security XMLs. This is the top of a repo (parent of {@code web/}). */
    private final Path securityApiV2Path;

    /** Path segments that should never be traversed — generated/build/dependency output. */
    private static final Set<String> EXCLUDED_DIR_SEGMENTS = Set.of(
        "target", "build", "node_modules", ".git", ".svn", "out", "dist");

    public SecurityXmlResolver(Path securityApiV2Path) {
        this.securityApiV2Path = securityApiV2Path;
    }

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // no-op: XML lives outside the Java AST.
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (securityApiV2Path == null || !Files.isRegularFile(securityApiV2Path)) {
            System.out.println("[SecurityXmlResolver] no security API V2 path configured — skipped");
            return;
        }

        // ── Walk every repo root for security XMLs ──
        Set<String> seenUrls = new HashSet<>();   // dedup across files within this resolver
        int endpointsEmitted = 0, exposesEmitted = 0;

        int[] counts = parseSecurityXml(securityApiV2Path, batch, seenUrls);
        endpointsEmitted += counts[0];
        exposesEmitted   += counts[1];
            

        System.out.printf(
            "[SecurityXmlResolver] scanned %d security XML file(s), emitted %d endpoints, %d EXPOSES edges (apimethod-only)%n",
            1, endpointsEmitted, exposesEmitted);
    }

   

    /**
     * Returns {@code [endpointsEmitted, exposesEdgesEmitted]}.
     */
    private int[] parseSecurityXml(Path xml, ExtractionBatch batch,
                                   Set<String> seenUrls) {
        int endpointsEmitted = 0, exposesEmitted = 0;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            // XXE hardening — security XMLs may carry DOCTYPE refs we don't want to load
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
            catch (Exception ignore) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false); }
            catch (Exception ignore) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
            catch (Exception ignore) {}
            DocumentBuilder db = dbf.newDocumentBuilder();
            // Swallow any stray entity resolution attempts
            db.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new java.io.StringReader("")));
            Document doc = db.parse(xml.toFile());

            NodeList urls = doc.getElementsByTagName("url");
            for (int i = 0; i < urls.getLength(); i++) {
                Node n = urls.item(i);
                if (!(n instanceof Element e)) continue;
                String path = attr(e, "path");
                if (path == null || path.isEmpty()) continue;

                if (!seenUrls.add(path)) continue;   // already emitted this URL
                batch.restEndpoints.add(new RestEndpointNode(path, ""));
                endpointsEmitted++;

                // Only create EXPOSES edges for URLs with explicit apimethod attribute.
                // Format: "com.package.ClassName.methodName"
                String apimethod = attr(e, "apimethod");
                if (apimethod != null && apimethod.contains(".")) {
                    int lastDot = apimethod.lastIndexOf('.');
                    String classFqn = apimethod.substring(0, lastDot);
                    String methodName = apimethod.substring(lastDot + 1);
                    batch.exposes.add(new ExposesEdge(classFqn, path, methodName));
                    exposesEmitted++;
                }
                // URLs without apimethod: RestEndpoint node only (validation/throttle metadata).
                // No heuristic resolution — prevents false-positive EXPOSES edges.
            }
        } catch (Throwable t) {
            System.err.println("[SecurityXmlResolver] error parsing " + xml + " : " + t.getMessage());
        }
        return new int[] { endpointsEmitted, exposesEmitted };
    }

    private static String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }
}
