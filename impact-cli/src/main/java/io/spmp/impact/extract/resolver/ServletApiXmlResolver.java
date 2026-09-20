package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.model.GraphEdges.ExposesEdge;
import io.spmp.impact.model.GraphNodes.RestEndpointNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * Parses ADSM's {@code servlet-api.xml} ({@code <ADSMServletAPIMapping>} entries) and
 * emits method-granularity REST endpoint nodes + EXPOSES edges.
 *
 * <p>Each entry maps a relative URL path to a specific Java class + method:
 * <pre>
 *   &lt;ADSMServletAPIMapping
 *       URL_PATH="customReport/save"
 *       CLASS_NAME="com.adventnet.sym.adsm...CustomReportMgmtAction"
 *       METHOD_NAME="save" ... /&gt;
 * </pre>
 *
 * <p>For each entry emits:
 * <ul>
 *   <li>{@code :RestEndpoint {url: "/URL_PATH"}}</li>
 *   <li>{@code (Method {owner_fqn: CLASS_NAME, simple_name: METHOD_NAME})-[:EXPOSES]->(RestEndpoint)}</li>
 * </ul>
 *
 * <p>The file path is supplied at construction time, read from {@code config.properties}
 * by {@code IngestCmd.servletApiXmlFromConfig()}.
 */
public class ServletApiXmlResolver implements BoundaryResolver {

    private final Path servletApiXml;

    public ServletApiXmlResolver(Path servletApiXml) {
        this.servletApiXml = servletApiXml;
    }

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // no-op: work happens against an XML file, not the Java AST.
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (servletApiXml == null) {
            System.out.println("[ServletApiXmlResolver] skipped — path is null (ServletAPI key missing from config.properties?)");
            return;
        }
        if (!Files.isRegularFile(servletApiXml)) {
            System.out.println("[ServletApiXmlResolver] skipped — file not found: " + servletApiXml);
            return;
        }
        System.out.println("[ServletApiXmlResolver] parsing: " + servletApiXml);

        Set<String> seenUrls = new HashSet<>();
        int endpoints = 0;
        int exposes = 0;

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
            catch (Exception ignore) {}
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(servletApiXml.toFile());

            NodeList mappings = doc.getElementsByTagName("ADSMServletAPIMapping");
            for (int i = 0; i < mappings.getLength(); i++) {
                if (!(mappings.item(i) instanceof Element e)) continue;

                String urlPath   = attr(e, "URL_PATH");
                String className = attr(e, "CLASS_NAME");
                String method    = attr(e, "METHOD_NAME");

                if (urlPath == null || urlPath.isEmpty() || className == null || className.isEmpty() || method == null || method.isEmpty()) continue;


                if (seenUrls.add(urlPath)) {
                    batch.restEndpoints.add(new RestEndpointNode(urlPath,
                        className == null ? "" : className));
                    endpoints++;
                }


                // method-granularity EXPOSES edge: (Class/Method)-[:EXPOSES]->(RestEndpoint)
                batch.exposes.add(new ExposesEdge(
                    className,
                    urlPath,
                    method == null ? "" : method
                ));
                exposes++;
            
            }
        } catch (Throwable t) {
            System.err.println("[ServletApiXmlResolver] error parsing " + servletApiXml + " : " + t.getMessage());
        }

        System.out.printf("[ServletApiXmlResolver] parsed %s → %d endpoints, %d EXPOSES edges%n",
            servletApiXml.getFileName(), endpoints, exposes);
    }

    private static String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }
}
