package io.spmp.impact.graph.txn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.function.Function;

/**
 * HTTP-backed {@link CypherClient} talking to Neo4j's transactional Cypher endpoint.
 *
 * <p>POSTs to {@code http(s)://host:7474/db/neo4j/tx/commit} with a JSON body of the form:
 * <pre>
 * {
 *   "statements": [
 *     { "statement": "MATCH (n) RETURN count(n)", "parameters": {} }
 *   ]
 * }
 * </pre>
 *
 * <p>Slower than Bolt for high-throughput ingest (each statement is its own HTTP round-trip),
 * but works in any sandbox or restricted-network environment because it uses the
 * synchronous {@link java.net.HttpURLConnection} API — no NIO Selector / Netty
 * event-loop / loopback-socket setup that the sandbox restricts.
 */
public final class HttpCypherClient implements CypherClient {

    private static final ObjectMapper JSON = new ObjectMapper()
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    // Read timeout for the transactional Cypher POST. Default doubled from the previous
    // 5 min to 10 min; override via configuration.properties key `neo4j.http.readTimeoutMs`.
    private static final int DEFAULT_READ_TIMEOUT_MS = 10 * 60 * 1000;
    private static final int READ_TIMEOUT_MS = resolveReadTimeoutMs();
    // Any statement slower than this is logged so slow analyze queries can be pinpointed.
    // Override via configuration.properties key `neo4j.http.slowQueryLogMs`.
    private static final int SLOW_QUERY_LOG_MS = resolveIntProp("neo4j.http.slowQueryLogMs", 5_000);

    private final URL endpoint;
    private final String authHeader;

    public HttpCypherClient(String uri, String user, String pass) {
        // Default to the canonical "neo4j" database. Could be made configurable later.
        String base = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        try {
            this.endpoint = URI.create(base + "/db/neo4j/tx/commit").toURL();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Neo4j HTTP URI: " + uri, e);
        }
        String token = Base64.getEncoder().encodeToString(
            (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        this.authHeader = "Basic " + token;
    }

    // ─── configuration.properties (read-timeout override) ────────────────

    private static Properties configProps;

    /** Load configuration.properties once — CWD-relative first, then classpath. */
    private static synchronized Properties configProps() {
        if (configProps != null) return configProps;
        Properties p = new Properties();
        Path file = Path.of("configuration.properties").toAbsolutePath();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) { p.load(in); }
            catch (IOException e) {
                System.err.println("[HttpCypherClient] failed to read " + file + ": " + e.getMessage());
            }
        } else {
            try (InputStream in = HttpCypherClient.class.getClassLoader()
                    .getResourceAsStream("configuration.properties")) {
                if (in != null) p.load(in);
            } catch (IOException ignore) {}
        }
        configProps = p;
        return configProps;
    }

    /** Read timeout (ms) from {@code neo4j.http.readTimeoutMs}, else the doubled default. */
    private static int resolveReadTimeoutMs() {
        return resolveIntProp("neo4j.http.readTimeoutMs", DEFAULT_READ_TIMEOUT_MS);
    }

    /** Positive int from a configuration.properties key, else {@code fallback}. */
    private static int resolveIntProp(String key, int fallback) {
        String v = configProps().getProperty(key);
        if (v != null && !v.trim().isEmpty()) {
            try {
                int n = Integer.parseInt(v.trim());
                if (n > 0) return n;
            } catch (NumberFormatException ignore) {}
        }
        return fallback;
    }

    /** One-line preview of the batch's statements for slow/failed-query logging. */
    private static String snippet(List<Stmt> stmts) {
        if (stmts == null || stmts.isEmpty()) return "(none)";
        String first = stmts.get(0).cypher().replaceAll("\\s+", " ").trim();
        if (first.length() > 300) first = first.substring(0, 300) + " …";
        return stmts.size() == 1 ? first : "[" + stmts.size() + " stmts] " + first;
    }

    @Override public CResult run(String cypher) {
        return runBatch(List.of(new Stmt(cypher, Map.of()))).get(0);
    }

    @Override public CResult run(String cypher, Map<String, Object> params) {
        return runBatch(List.of(new Stmt(cypher, params))).get(0);
    }

    @Override public <T> T executeWrite(Function<CTx, T> work) {
        // We don't get real multi-statement transactions without managing the cursor URL
        // across calls. For our usage (single UNWIND-batched statement per executeWrite)
        // a single auto-commit POST is functionally equivalent.
        HttpTx tx = new HttpTx();
        T result = work.apply(tx);
        return result;
    }

    @Override public void close() { /* HttpURLConnection is per-call; nothing to close. */ }

    // ─── core HTTP call ──────────────────────────────────────────

    private record Stmt(String cypher, Map<String, Object> params) {}

    private List<CResult> runBatch(List<Stmt> stmts) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode statementsArr = body.putArray("statements");
        for (Stmt s : stmts) {
            ObjectNode st = statementsArr.addObject();
            st.put("statement", s.cypher());
            st.set("parameters", JSON.valueToTree(s.params() == null ? Map.of() : s.params()));
        }

        byte[] payload;
        try { payload = JSON.writeValueAsBytes(body); }
        catch (Exception e) { throw new RuntimeException("JSON serialize: " + e.getMessage(), e); }

        byte[] respBody;
        int status;
        long startNs = System.nanoTime();
        try {
            HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setRequestProperty("Authorization", authHeader);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            try (OutputStream os = conn.getOutputStream()) { os.write(payload); }
            status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            respBody = is == null ? new byte[0] : readAll(is);
        } catch (IOException e) {
            long ms = (System.nanoTime() - startNs) / 1_000_000;
            System.err.println("[HttpCypherClient] call FAILED after " + ms + "ms"
                + " (connectTimeout=" + CONNECT_TIMEOUT_MS + "ms, readTimeout=" + READ_TIMEOUT_MS + "ms): "
                + e.getClass().getSimpleName() + ": " + e.getMessage()
                + "\n  in-flight statement(s): " + snippet(stmts));
            throw new RuntimeException("Neo4j HTTP call failed: " + e.getMessage(), e);
        }
        long ms = (System.nanoTime() - startNs) / 1_000_000;
        if (ms >= SLOW_QUERY_LOG_MS) {
            System.err.println("[HttpCypherClient] slow query " + ms + "ms: " + snippet(stmts));
        }
        if (status >= 400) {
            throw new RuntimeException("Neo4j HTTP " + status + ": "
                + new String(respBody, StandardCharsets.UTF_8));
        }

        JsonNode root;
        try { root = JSON.readTree(respBody); }
        catch (IOException e) { throw new RuntimeException("JSON parse: " + e.getMessage(), e); }

        // Surface API-level errors (these can come back with a 200 status).
        JsonNode errors = root.path("errors");
        if (errors.isArray() && errors.size() > 0) {
            StringBuilder sb = new StringBuilder("Neo4j errors: ");
            for (JsonNode e : errors) sb.append(e.path("message").asText()).append("; ");
            throw new RuntimeException(sb.toString());
        }

        JsonNode results = root.path("results");
        List<CResult> out = new ArrayList<>(stmts.size());
        for (int i = 0; i < stmts.size(); i++) {
            JsonNode resultNode = (i < results.size()) ? results.get(i) : MissingNode.getInstance();
            out.add(buildResult(resultNode));
        }
        return out;
    }

    private static HttpResult buildResult(JsonNode resultNode) {
        List<String> columns = new ArrayList<>();
        JsonNode cols = resultNode.path("columns");
        if (cols.isArray()) for (JsonNode c : cols) columns.add(c.asText());

        JsonNode data = resultNode.path("data");
        List<JsonNode> rows = new ArrayList<>();
        if (data.isArray()) for (JsonNode r : data) rows.add(r.path("row"));
        return new HttpResult(columns, rows);
    }

    // ─── result/record/value impls ───────────────────────────────

    private final class HttpTx implements CTx {
        @Override public CResult run(String cypher, Map<String, Object> params) {
            return runBatch(List.of(new Stmt(cypher, params))).get(0);
        }
    }

    private static final class HttpResult implements CResult {
        private final List<String> columns;
        private final List<JsonNode> rows;
        private int idx = 0;

        HttpResult(List<String> columns, List<JsonNode> rows) {
            this.columns = columns;
            this.rows = rows;
        }
        @Override public boolean hasNext() { return idx < rows.size(); }
        @Override public CRecord next() {
            if (idx >= rows.size()) throw new NoSuchElementException();
            return new HttpRecord(columns, rows.get(idx++));
        }
        @Override public void consume() { idx = rows.size(); }
        @Override public Iterator<CRecord> iterator() {
            return new Iterator<>() {
                @Override public boolean hasNext() { return HttpResult.this.hasNext(); }
                @Override public CRecord next() { return HttpResult.this.next(); }
            };
        }
        @Override public void close() {}
    }

    private static final class HttpRecord implements CRecord {
        private final List<String> columns;
        private final JsonNode rowArr;
        HttpRecord(List<String> columns, JsonNode rowArr) {
            this.columns = columns;
            this.rowArr = rowArr;
        }
        @Override public CValue get(String column) {
            int i = columns.indexOf(column);
            if (i < 0 || !rowArr.isArray()) return new HttpValue(NullNode.getInstance());
            return new HttpValue(rowArr.get(i));
        }
        @Override public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                JsonNode v = (rowArr.isArray() && i < rowArr.size()) ? rowArr.get(i) : NullNode.getInstance();
                m.put(columns.get(i), unwrap(v));
            }
            return m;
        }
    }

    private static final class HttpValue implements CValue {
        private final JsonNode v;
        HttpValue(JsonNode v) { this.v = v == null ? NullNode.getInstance() : v; }

        @Override public boolean isNull() { return v == null || v.isNull() || v.isMissingNode(); }
        @Override public String asString() { return v.isTextual() ? v.asText() : v.toString(); }
        @Override public String asString(String defaultValue) {
            if (isNull()) return defaultValue;
            return v.isTextual() ? v.asText() : v.toString();
        }
        @Override public int asInt() { return v.asInt(); }
        @Override public int asInt(int defaultValue) { return isNull() ? defaultValue : v.asInt(defaultValue); }
        @Override public long asLong() { return v.asLong(); }
        @Override public long asLong(long defaultValue) { return isNull() ? defaultValue : v.asLong(defaultValue); }
        @Override public double asDouble() { return v.asDouble(); }
        @Override public double asDouble(double defaultValue) { return isNull() ? defaultValue : v.asDouble(defaultValue); }
        @Override public boolean asBoolean() { return v.asBoolean(); }
        @Override public boolean asBoolean(boolean defaultValue) { return isNull() ? defaultValue : v.asBoolean(defaultValue); }
        @Override public <T> List<T> asList(Function<CValue, T> mapper) {
            if (!v.isArray()) return List.of();
            List<T> out = new ArrayList<>(v.size());
            for (JsonNode el : v) out.add(mapper.apply(new HttpValue(el)));
            return out;
        }
        @Override public CValue get(String column) {
            if (v.isObject()) return new HttpValue(v.path(column));
            return new HttpValue(NullNode.getInstance());
        }
    }

    private static byte[] readAll(InputStream is) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
            return baos.toByteArray();
        } finally {
            try { is.close(); } catch (IOException ignored) {}
        }
    }

    private static Object unwrap(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) return null;
        if (n.isTextual())  return n.asText();
        if (n.isInt())      return n.intValue();
        if (n.isLong())     return n.longValue();
        if (n.isDouble())   return n.doubleValue();
        if (n.isBoolean())  return n.booleanValue();
        if (n.isArray()) {
            List<Object> out = new ArrayList<>(n.size());
            for (JsonNode e : n) out.add(unwrap(e));
            return out;
        }
        if (n.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            n.fields().forEachRemaining(e -> out.put(e.getKey(), unwrap(e.getValue())));
            return out;
        }
        return n.toString();
    }
}
