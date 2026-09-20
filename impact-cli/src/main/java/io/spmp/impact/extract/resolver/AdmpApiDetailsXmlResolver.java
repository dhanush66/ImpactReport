package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
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
 * Parses ADSM's {@code ADMPAPIDetails.xml} ({@code <ADMPProductAPIs>} entries) and
 * emits REST endpoint nodes.
 *
 * <p>Each entry maps an API name to a URL path:
 * <pre>
 *   &lt;ADMPProductAPIs
 *       API_ID="ADMPProductAPIs:API_ID=1"
 *       API_NAME="MMP_MANAGELICENSE_CSV"
 *       API_URL="/RestAPI/WC/ManageLicense"
 *       API_AUTHORIZATION="com.adventnet.sym.adsm.common.webclient.api.ADMPInternalAPIAuthorization"
 *       API_ERROR_HANDLER="..." /&gt;
 * </pre>
 *
 * <p>For each entry emits:
 * <ul>
 *   <li>{@code :RestEndpoint {url: API_URL}}</li>
 * </ul>
 *
 * <p>No {@code :EXPOSES} edges are emitted — the file declares URL-to-authorization
 * mappings, not URL-to-handler-method mappings.
 *
 * <p>The file path is supplied at construction time, read from {@code config.properties}
 * by {@code IngestCmd.admpApiDetailsXmlFromConfig()}.
 */
public class AdmpApiDetailsXmlResolver implements BoundaryResolver {

    private final Path admpApiDetailsXml;

    public AdmpApiDetailsXmlResolver(Path admpApiDetailsXml) {
        this.admpApiDetailsXml = admpApiDetailsXml;
    }

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // no-op: work happens against an XML file, not the Java AST.
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (admpApiDetailsXml == null) {
            System.out.println("[AdmpApiDetailsXmlResolver] skipped — path is null (ADMPAPIDetails key missing from config.properties?)");
            return;
        }
        if (!Files.isRegularFile(admpApiDetailsXml)) {
            System.out.println("[AdmpApiDetailsXmlResolver] skipped — file not found: " + admpApiDetailsXml);
            return;
        }
        System.out.println("[AdmpApiDetailsXmlResolver] parsing: " + admpApiDetailsXml);

        Set<String> seenUrls = new HashSet<>();
        int endpoints = 0;

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
            catch (Exception ignore) {}
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(admpApiDetailsXml.toFile());

            NodeList entries = doc.getElementsByTagName("ADMPProductAPIs");
            for (int i = 0; i < entries.getLength(); i++) {
                if (!(entries.item(i) instanceof Element e)) continue;

                String apiUrl = attr(e, "API_URL");
                if (apiUrl == null || apiUrl.isEmpty()) continue;

                // Normalise: ensure URL starts with /
                String url = apiUrl.startsWith("/") ? apiUrl : "/" + apiUrl;

                if (seenUrls.add(url)) {
                    batch.restEndpoints.add(new RestEndpointNode(url, ""));
                    endpoints++;
                }
            }
        } catch (Throwable t) {
            System.err.println("[AdmpApiDetailsXmlResolver] error parsing " + admpApiDetailsXml + " : " + t.getMessage());
        }

        System.out.printf("[AdmpApiDetailsXmlResolver] parsed %s → %d endpoints%n",
            admpApiDetailsXml.getFileName(), endpoints);
    }

    private static String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }
}
