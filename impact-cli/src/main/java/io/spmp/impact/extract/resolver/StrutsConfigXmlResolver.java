package io.spmp.impact.extract.resolver;

import com.github.javaparser.ast.CompilationUnit;
import io.spmp.impact.extract.BoundaryResolver;
import io.spmp.impact.extract.ExtractionBatch;
import io.spmp.impact.model.GraphEdges.ExposesEdge;
import io.spmp.impact.model.GraphNodes.MethodNode;
import io.spmp.impact.model.GraphNodes.RestEndpointNode;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
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
 * Parses Struts 1 {@code struts-config.xml} {@code <action>} mappings and emits
 * {@code :RestEndpoint} nodes + {@code :EXPOSES} edges.
 *
 * <h3>DispatchAction mapping (has {@code parameter} attribute)</h3>
 * <pre>
 *   &lt;action path="/ModifyBulkUser"
 *           type="...ModifyAction"
 *           parameter="methodToCall"&gt;
 * </pre>
 * Generates one URL per non-static, non-constructor method found in {@code batch.methods}
 * for that class:
 * <pre>
 *   /ModifyBulkUser.do?methodToCall=modifyAction
 *     → (ModifyAction.modifyAction)-[:EXPOSES]->(RestEndpoint)
 *   /ModifyBulkUser.do?methodToCall=csvHandle
 *     → (ModifyAction.csvHandle)-[:EXPOSES]->(RestEndpoint)
 *   ...
 * </pre>
 *
 * <h3>Plain Action mapping (no {@code parameter} attribute)</h3>
 * <pre>
 *   &lt;action path="/SetMailboxRights" type="...MailboxRightsAction"&gt;
 * </pre>
 * Generates a single base URL with a class-level EXPOSES edge:
 * <pre>
 *   /SetMailboxRights.do → (MailboxRightsAction)-[:EXPOSES]->(RestEndpoint)
 * </pre>
 *
 * <h3>Fallback</h3>
 * If an action class is not present in {@code batch.methods} (class outside the
 * {@code --src} scope, or methods already flushed in streaming mode), the resolver
 * falls back to a base {@code /{path}.do} endpoint with a class-level EXPOSES edge
 * so the URL node is present in the graph regardless.
 *
 * <p>The file path is supplied at construction time, read from {@code config.properties}
 * by {@code IngestCmd.strutsConfigXmlFromConfig()}.
 */
public class StrutsConfigXmlResolver implements BoundaryResolver {

    private final Path strutsConfigXml;

    public StrutsConfigXmlResolver(Path strutsConfigXml) {
        this.strutsConfigXml = strutsConfigXml;
    }

    @Override
    public void visit(CompilationUnit cu, Path filePath, ExtractionBatch batch) {
        // no-op: work happens against an XML file, not the Java AST.
    }

    @Override
    public void afterAll(ExtractionBatch batch) {
        if (strutsConfigXml == null) {
            System.out.println("[StrutsConfigXmlResolver] skipped — path is null (StrutsConfig key missing from config.properties?)");
            return;
        }
        if (!Files.isRegularFile(strutsConfigXml)) {
            System.out.println("[StrutsConfigXmlResolver] skipped — file not found: " + strutsConfigXml);
            return;
        }
        System.out.println("[StrutsConfigXmlResolver] parsing: " + strutsConfigXml);

        List<ActionMapping> actionMappings = new ArrayList<>();
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(false);
            // struts-config.xml has a <!DOCTYPE> declaration — disallow-doctype-decl would
            // block it entirely. Instead, disable external entity resolution to prevent XXE
            // while still allowing the DOCTYPE declaration itself.
            try { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false); }
            catch (Exception ignore) {}
            try { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false); }
            catch (Exception ignore) {}
            try { dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false); }
            catch (Exception ignore) {}
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            // Suppress DTD-not-found warnings on stderr (Struts DTD is not on the local path)
            db.setErrorHandler(null);
            Document doc = db.parse(strutsConfigXml.toFile());

            NodeList actions = doc.getElementsByTagName("action");
            for (int i = 0; i < actions.getLength(); i++) {
                if (!(actions.item(i) instanceof Element e)) continue;
                String path      = attr(e, "path");
                String type      = attr(e, "type");
                String parameter = attr(e, "parameter"); // null for plain (non-Dispatch) Action
                if (path == null || type == null) continue;
                actionMappings.add(new ActionMapping(path, type, parameter));
            }
        } catch (Throwable t) {
            System.err.println("[StrutsConfigXmlResolver] error parsing " + strutsConfigXml + ": " + t.getMessage());
            return;
        }

        System.out.println("[StrutsConfigXmlResolver] found " + actionMappings.size() + " action mappings");
        if (actionMappings.isEmpty()) return;

        // classFqn → list of actions (a class can theoretically serve multiple paths)
        Map<String, List<ActionMapping>> byClass = new HashMap<>();
        for (ActionMapping am : actionMappings) {
            byClass.computeIfAbsent(am.classFqn, k -> new ArrayList<>()).add(am);
        }

        Set<String> seenUrls          = new HashSet<>();
        Set<String> classesWithMethods = new HashSet<>();
        int endpoints = 0;
        int exposes   = 0;

        // ── Pass 1: method-granularity edges from batch.methods ──────────────────────
        for (MethodNode m : batch.methods) {
            // Skip constructors and static helpers — DispatchAction only dispatches
            // to non-static, non-constructor public methods.
            if (m.isConstructor() || m.isStatic()) continue;

            List<ActionMapping> mappings = byClass.get(m.ownerFqn());
            if (mappings == null) continue;

            classesWithMethods.add(m.ownerFqn());

            for (ActionMapping am : mappings) {
                String url;
                if (am.parameter != null) {
                    // DispatchAction: /path.do?parameter=methodSimpleName
                    url = am.urlPath + ".do?" + am.parameter + "=" + m.simpleName();
                } else {
                    // Plain Action: single base URL, emit a method-level edge per method
                    url = am.urlPath + ".do";
                }
                if (seenUrls.add(url)) {
                    batch.restEndpoints.add(new RestEndpointNode(url, m.ownerFqn()));
                    endpoints++;
                }
                batch.exposes.add(new ExposesEdge(m.ownerFqn(), url, m.simpleName()));
                exposes++;
            }
        }

        // ── Pass 2: fallback for classes NOT present in batch.methods ────────────────
        // Covers: class outside --src scope, or methods already flushed in streaming mode.
        // Emits base /path.do + class-level EXPOSES so the URL node exists in the graph.
        for (ActionMapping am : actionMappings) {
            if (classesWithMethods.contains(am.classFqn)) continue; // already handled above
            String url = am.urlPath + ".do";
            if (seenUrls.add(url)) {
                batch.restEndpoints.add(new RestEndpointNode(url, am.classFqn));
                endpoints++;
            }
            // empty targetMethodSimpleName → class-level EXPOSES in writeExposes()
            batch.exposes.add(new ExposesEdge(am.classFqn, url, ""));
            exposes++;
        }

        System.out.printf("[StrutsConfigXmlResolver] emitted %d endpoints, %d EXPOSES edges%n",
            endpoints, exposes);
    }

    private static String attr(Element e, String name) {
        String v = e.getAttribute(name);
        return v == null || v.isEmpty() ? null : v;
    }

    private record ActionMapping(String urlPath, String classFqn, String parameter) {}
}
