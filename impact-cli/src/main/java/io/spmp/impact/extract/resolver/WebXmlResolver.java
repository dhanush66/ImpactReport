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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Parses servlet URL mappings from the Tomcat {@code web.xml} descriptor —
 * specifically from the split {@code resources/tomcat/web_header.xml} +
 * {@code resources/tomcat/web_footer.xml} files that are concatenated at build time
 * to form the deployed {@code web.xml}.
 *
 * <p>The web.xml contains standard {@code <servlet>} + {@code <servlet-mapping>} pairs:
 * <pre>{@code
 *   <servlet>
 *     <servlet-name>com.manageengine.rmp.oumanager.GetOU</servlet-name>
 *     <servlet-class>com.manageengine.rmp.oumanager.GetOU</servlet-class>
 *   </servlet>
 *   <servlet-mapping>
 *     <servlet-name>com.manageengine.rmp.oumanager.GetOU</servlet-name>
 *     <url-pattern>/GetOU</url-pattern>
 *   </servlet-mapping>
 * }</pre>
 *
 * <p>This resolver:
 * <ol>
 *   <li>Walks repo roots looking for {@code resources/tomcat/web_header.xml} and
 *       {@code resources/tomcat/web_footer.xml} (or a single {@code WEB-INF/web.xml})</li>
 *   <li>Builds a servlet-name → servlet-class index from {@code <servlet>} elements</li>
 *   <li>For each {@code <servlet-mapping>}, resolves servlet-name to its class FQN</li>
 *   <li>Emits `:RestEndpoint` + `:EXPOSES` edges for <b>non-wildcard</b> URL patterns
 *       (specific URLs like {@code /GetOU}, {@code /recyclebin})</li>
 *   <li>Skips wildcard patterns ({@code /api/v2/*}, {@code *.do}, {@code /RestAPI/*})
 *       — those are dispatcher-level mappings already handled by
 *       {@link RestApiXmlResolver} and {@link SecurityXmlResolver}</li>
 * </ol>
 */
public class WebXmlResolver implements BoundaryResolver {

    private final List<Path> webXMLRoots;

    private static final Set<String> EXCLUDED_DIR_SEGMENTS = Set.of(
        "target", "build", "node_modules", ".git", ".svn", "out", "dist");

    public WebXmlResolver(List<Path> webXMLRoots) {
        this.webXMLRoots = webXMLRoots == null ? List.of() : webXMLRoots;
    }

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // no-op: XML lives outside the Java AST.
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (webXMLRoots.isEmpty()) {
            System.out.println("[WebXmlResolver] no web XML roots configured — skipped");
            return;
        }

        Set<String> seenUrls = new HashSet<>();
        int filesScanned = 0, endpointsEmitted = 0, exposesEmitted = 0;
        List<Path> parts = new ArrayList<>();
        for (Path webXML : webXMLRoots) {
            if (webXML == null || !Files.isRegularFile(webXML)) continue;
                if (Files.isRegularFile(webXML)) parts.add(webXML);
                         
        }
        if (!parts.isEmpty()) {
            // Parse servlet definitions from both parts
            Map<String, String> servletNameToClass = new HashMap<>();
            for (Path part : parts) {
                filesScanned++;
                parseServletDefinitions(part, servletNameToClass);
            }
            // Parse servlet-mappings from both parts
            for (Path part : parts) {
                int[] counts = parseServletMappings(part, servletNameToClass, batch, seenUrls);
                endpointsEmitted += counts[0];
                exposesEmitted += counts[1];
            }
        }

        if (filesScanned > 0) {
            System.out.printf(
                "[WebXmlResolver] scanned %d web.xml file(s), emitted %d endpoints, %d EXPOSES edges%n",
                filesScanned, endpointsEmitted, exposesEmitted);
        }
    }

    /**
     * Parse {@code <servlet>} elements to build servlet-name → servlet-class mapping.
     */
    private void parseServletDefinitions(Path xml, Map<String, String> servletNameToClass) {
        try {
            Document doc = parseXml(xml);
            if (doc == null) return;

            NodeList servlets = doc.getElementsByTagName("servlet");
            for (int i = 0; i < servlets.getLength(); i++) {
                Node n = servlets.item(i);
                if (!(n instanceof Element e)) continue;

                String servletName = getChildText(e, "servlet-name");
                String servletClass = getChildText(e, "servlet-class");
                if (servletName != null && servletClass != null) {
                    servletNameToClass.put(servletName.trim(), servletClass.trim());
                }
            }
        } catch (Throwable t) {
            System.err.println("[WebXmlResolver] error parsing servlet defs in " + xml + ": " + t.getMessage());
        }
    }

    /**
     * Parse {@code <servlet-mapping>} elements and emit endpoints + EXPOSES edges.
     * Returns {@code [endpointsEmitted, exposesEmitted]}.
     */
    private int[] parseServletMappings(Path xml, Map<String, String> servletNameToClass,
                                       ExtractionBatch batch, Set<String> seenUrls) {
        int endpointsEmitted = 0, exposesEmitted = 0;
        try {
            Document doc = parseXml(xml);
            if (doc == null) return new int[] {0, 0};

            NodeList mappings = doc.getElementsByTagName("servlet-mapping");
            for (int i = 0; i < mappings.getLength(); i++) {
                Node n = mappings.item(i);
                if (!(n instanceof Element e)) continue;

                String servletName = getChildText(e, "servlet-name");
                if (servletName == null) continue;
                servletName = servletName.trim();

                String servletClass = servletNameToClass.get(servletName);
                if (servletClass == null) continue;

                // Get all url-pattern children
                NodeList patterns = e.getElementsByTagName("url-pattern");
                for (int j = 0; j < patterns.getLength(); j++) {
                    String urlPattern = patterns.item(j).getTextContent();
                    if (urlPattern == null || urlPattern.isBlank()) continue;
                    urlPattern = urlPattern.trim();

                    // Skip wildcard/glob dispatcher patterns — handled by other resolvers
                    if (isWildcardPattern(urlPattern)) continue;

                    if (!seenUrls.add(urlPattern)) continue; // dedup

                    batch.restEndpoints.add(new RestEndpointNode(urlPattern, ""));
                    endpointsEmitted++;

                    // Emit EXPOSES edge from servlet class to the URL
                    batch.exposes.add(new ExposesEdge(servletClass, urlPattern, ""));
                    exposesEmitted++;
                }
            }
        } catch (Throwable t) {
            System.err.println("[WebXmlResolver] error parsing servlet mappings in " + xml + ": " + t.getMessage());
        }
        return new int[] {endpointsEmitted, exposesEmitted};
    }

    /**
     * A URL pattern is a "wildcard" dispatcher if it contains {@code *} or is a
     * generic extension mapping ({@code *.do}, {@code *.cc}) or path prefix ({@code /api/*}).
     * These are handled by other resolvers (RestApiXml, SecurityXml, Struts).
     */
    private static boolean isWildcardPattern(String pattern) {
        return pattern.contains("*");
    }

    private Document parseXml(Path xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            // XXE hardening
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
            catch (Exception ignore) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false); }
            catch (Exception ignore) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
            catch (Exception ignore) {}
            DocumentBuilder db = dbf.newDocumentBuilder();
            db.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new java.io.StringReader("")));
            // web_header/web_footer are fragments — strip XML decl, web-app wrapper, and
            // wrap in a synthetic root so the DOM parser can handle them.
            String content = Files.readString(xml);
            // Strip XML declaration
            content = content.replaceFirst("<\\?xml[^?]*\\?>", "");
            // Strip <web-app ...> opening tag (may have xmlns attributes)
            content = content.replaceFirst("<web-app[^>]*>", "");
            // Strip </web-app> closing tag
            content = content.replaceFirst("</web-app>", "");
            // Strip comments that appear before actual content (e.g., <!-- $Id$ -->)
            // and wrap in a synthetic root
            String wrapped = "<?xml version=\"1.0\"?><root>" + content + "</root>";
            return db.parse(new org.xml.sax.InputSource(new java.io.StringReader(wrapped)));
        } catch (Throwable t) {
            System.err.println("[WebXmlResolver] XML parse error for " + xml + ": " + t.getMessage());
            return null;
        }
    }

    /** Get the text content of the first child element with the given tag name. */
    private static String getChildText(Element parent, String tagName) {
        NodeList children = parent.getElementsByTagName(tagName);
        if (children.getLength() == 0) return null;
        String text = children.item(0).getTextContent();
        return (text == null || text.isBlank()) ? null : text;
    }
}
