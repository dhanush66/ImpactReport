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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Resolves ADS Framework {@code Servlet-Forward-Config.xml} request URLs to the
 * real product API class/method declared in {@code ADSProductAPIs*.xml}.
 *
 * <p>Runtime flow in ADSF:
 * <ol>
 *   <li>{@code FWServletAPI} is mapped in web.xml to URLs such as
 *       {@code /RestAPI/ConfigureTrust}.</li>
 *   <li>{@code com.manageengine.ads.fw.common.api.ServletAPI} dispatches by URL using
 *       {@code ADSProductAPIs*.xml}: {@code API_URL -> SERVLET_CLASS_NAME + MTCALL_VALUE}.</li>
 *   <li>The invoked method returns a forward name such as {@code result};
 *       {@code ServletForwardHandler} loads the file named by ADSProductParams
 *       {@code SERVLET_FORWARD_XML} and maps that name to a JSP.</li>
 * </ol>
 *
 * <p>The forward file itself only has URL -> JSP, so this resolver joins its request URLs
 * back to {@code ADSProductAPIs*.xml} and emits URL -> implementation class edges for the
 * bare runtime URL (without appending {@code ?MTCALL_VALUE}).
 */
public class ServletForwardConfigResolver implements BoundaryResolver {

    private final List<Path> repoRoots;
    private final List<Path> xmlConfigRoots;

    private static final Set<String> EXCLUDED_DIR_SEGMENTS = Set.of(
        "target", "build", "node_modules", ".git", ".svn", "out", "dist");

    public ServletForwardConfigResolver(List<Path> repoRoots, List<Path> xmlConfigRoots) {
        this.repoRoots = repoRoots == null ? List.of() : repoRoots;
        this.xmlConfigRoots = xmlConfigRoots == null ? List.of() : xmlConfigRoots;
    }

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // no-op: XML lives outside the Java AST.
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (repoRoots.isEmpty() && xmlConfigRoots.isEmpty()) {
            System.out.println("[ServletForwardConfigResolver] no repo/xml roots configured — skipped");
            return;
        }

        Map<String, List<ApiTarget>> apiTargetsByUrl = loadApiTargets();
        Set<Path> forwardFiles = findForwardConfigFiles();
        if (forwardFiles.isEmpty()) {
            System.out.println("[ServletForwardConfigResolver] Servlet-Forward-Config.xml not found — skipped");
            return;
        }

        Set<String> emitted = new HashSet<>();
        int filesScanned = 0, urlsSeen = 0, endpointsEmitted = 0, exposesEmitted = 0, unresolved = 0;

        for (Path forwardFile : forwardFiles) {
            filesScanned++;
            for (String url : parseForwardUrls(forwardFile)) {
                urlsSeen++;
                List<ApiTarget> targets = apiTargetsByUrl.get(url);
                if (targets == null || targets.isEmpty()) {
                    unresolved++;
                    continue;
                }
                for (ApiTarget target : targets) {
                    if (target.classFqn == null || target.classFqn.isBlank()) continue;
                    String edgeKey = target.classFqn + "\u0000" + url + "\u0000" + target.methodName;
                    if (!emitted.add(edgeKey)) continue;

                    batch.restEndpoints.add(new RestEndpointNode(url, target.classFqn));
                    endpointsEmitted++;
                    batch.exposes.add(new ExposesEdge(target.classFqn, url, target.methodName));
                    exposesEmitted++;
                }
            }
        }

        System.out.printf(
            "[ServletForwardConfigResolver] scanned %d forward file(s), saw %d URL(s), emitted %d endpoints, %d EXPOSES edges, unresolved %d%n",
            filesScanned, urlsSeen, endpointsEmitted, exposesEmitted, unresolved);
    }

    private Map<String, List<ApiTarget>> loadApiTargets() {
        Map<String, List<ApiTarget>> targets = new HashMap<>();
        for (Path xmlConfigRoot : xmlConfigRoots) {
            if (xmlConfigRoot == null || !Files.isDirectory(xmlConfigRoot)) continue;
            Path adsfDir = xmlConfigRoot.resolve("adsf");
            if (!Files.isDirectory(adsfDir)) continue;
            try (Stream<Path> children = Files.list(adsfDir)) {
                children
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().startsWith("adsproductapis"))
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xml"))
                    .forEach(p -> parseAdsProductApis(p, targets));
            } catch (Throwable t) {
                System.err.println("[ServletForwardConfigResolver] error scanning " + adsfDir + " : " + t.getMessage());
            }
        }
        return targets;
    }

    private void parseAdsProductApis(Path xml, Map<String, List<ApiTarget>> targets) {
        try {
            Document doc = parseXml(xml);
            if (doc == null) return;
            NodeList apis = doc.getElementsByTagName("ADSProductAPIs");
            for (int i = 0; i < apis.getLength(); i++) {
                Node n = apis.item(i);
                if (!(n instanceof Element e)) continue;
                String url = attr(e, "API_URL");
                String servletClass = attr(e, "SERVLET_CLASS_NAME");
                String methodName = attr(e, "MTCALL_VALUE");
                if (url == null || url.isBlank() || servletClass == null || servletClass.isBlank()) continue;
                targets.computeIfAbsent(url.trim(), ignored -> new ArrayList<>())
                    .add(new ApiTarget(servletClass.trim(), methodName == null ? "" : methodName.trim()));
            }
        } catch (Throwable t) {
            System.err.println("[ServletForwardConfigResolver] error parsing " + xml + " : " + t.getMessage());
        }
    }

    private Set<Path> findForwardConfigFiles() {
        Set<Path> files = new LinkedHashSet<>();
        List<String> configuredValues = loadConfiguredForwardPaths();

        for (Path repoRoot : repoRoots) {
            if (repoRoot == null || !Files.isDirectory(repoRoot)) continue;
            for (String configured : configuredValues) {
                addIfRegular(files, repoRoot.resolve(configured));
                // Runtime config uses deployed webapps/adsm; the source tree stores the
                // same file under web/adsm.
                if (configured.startsWith("webapps/")) {
                    addIfRegular(files, repoRoot.resolve("web/" + configured.substring("webapps/".length())));
                } else if (configured.startsWith("webapps\\")) {
                    addIfRegular(files, repoRoot.resolve("web\\" + configured.substring("webapps\\".length())));
                }
            }
            Path conventional = repoRoot.resolve("web").resolve("adsm").resolve("WEB-INF").resolve("Servlet-Forward-Config.xml");
            addIfRegular(files, conventional);

            // Fallback for product variants with a different webapp name.
            try (Stream<Path> paths = Files.walk(repoRoot)) {
                paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("Servlet-Forward-Config.xml"))
                    .filter(p -> !isExcluded(p))
                    .forEach(files::add);
            } catch (Throwable t) {
                System.err.println("[ServletForwardConfigResolver] error walking " + repoRoot + " : " + t.getMessage());
            }
        }
        return files;
    }

    private List<String> loadConfiguredForwardPaths() {
        List<String> values = new ArrayList<>();
        for (Path xmlConfigRoot : xmlConfigRoots) {
            if (xmlConfigRoot == null || !Files.isDirectory(xmlConfigRoot)) continue;
            Path paramsFile = xmlConfigRoot.resolve("adsf").resolve("ADSProductParams.xml");
            if (!Files.isRegularFile(paramsFile)) continue;
            try {
                Document doc = parseXml(paramsFile);
                if (doc == null) continue;
                NodeList params = doc.getElementsByTagName("ADSProductParams");
                for (int i = 0; i < params.getLength(); i++) {
                    Node n = params.item(i);
                    if (!(n instanceof Element e)) continue;
                    if (!"SERVLET_FORWARD_XML".equals(attr(e, "PARAM_NAME"))) continue;
                    String value = attr(e, "PARAM_VALUE");
                    if (value != null && !value.isBlank()) {
                        values.add(value.trim().replace('\\', '/'));
                    }
                }
            } catch (Throwable t) {
                System.err.println("[ServletForwardConfigResolver] error parsing " + paramsFile + " : " + t.getMessage());
            }
        }
        return values;
    }

    private List<String> parseForwardUrls(Path xml) {
        List<String> urls = new ArrayList<>();
        try {
            Document doc = parseXml(xml);
            if (doc == null) return urls;
            NodeList requests = doc.getElementsByTagName("request");
            for (int i = 0; i < requests.getLength(); i++) {
                Node n = requests.item(i);
                if (!(n instanceof Element e)) continue;
                String url = attr(e, "url");
                if (url != null && !url.isBlank()) urls.add(url.trim());
            }
        } catch (Throwable t) {
            System.err.println("[ServletForwardConfigResolver] error parsing " + xml + " : " + t.getMessage());
        }
        return urls;
    }

    private static Document parseXml(Path xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); }
            catch (Exception ignore) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false); }
            catch (Exception ignore) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
            catch (Exception ignore) {}
            DocumentBuilder db = dbf.newDocumentBuilder();
            db.setEntityResolver((publicId, systemId) ->
                new org.xml.sax.InputSource(new java.io.StringReader("")));
            return db.parse(xml.toFile());
        } catch (Throwable t) {
            System.err.println("[ServletForwardConfigResolver] XML parse error for " + xml + " : " + t.getMessage());
            return null;
        }
    }

    private static void addIfRegular(Set<Path> files, Path path) {
        if (path != null && Files.isRegularFile(path)) files.add(path.toAbsolutePath().normalize());
    }

    private static boolean isExcluded(Path path) {
        for (Path part : path) {
            if (EXCLUDED_DIR_SEGMENTS.contains(part.toString())) return true;
        }
        return false;
    }

    private static String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private record ApiTarget(String classFqn, String methodName) {}
}
