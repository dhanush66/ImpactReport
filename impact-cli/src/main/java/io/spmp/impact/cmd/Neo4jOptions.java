package io.spmp.impact.cmd;

import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Neo4j connection options.
 *
 * <p>Resolution precedence for each setting:
 * <ol>
 *   <li>Explicit CLI flag ({@code --neo4j} / {@code --user} / {@code --pass})
 *       or a programmatically-set field.</li>
 *   <li>{@code configuration.properties} — keys {@code neo4j.hostname} + {@code neo4j.port}
 *       (composed into {@code bolt://host:port}), {@code neo4j.username}, {@code neo4j.password}.</li>
 *   <li>Hardcoded fallback ({@code bolt://localhost:7687} / {@code neo4j} /
 *       env {@code NEO4J_PASS} / {@code neo4j}).</li>
 * </ol>
 *
 * <p>{@code configuration.properties} is looked up CWD-relative first (same convention as
 * {@code config.properties}), then on the classpath. A missing file or key falls through
 * to the next source, so nothing breaks if the file is absent.
 */
public class Neo4jOptions {
    @Option(names = "--neo4j", description = "Bolt URI (default: configuration.properties, else bolt://localhost:7687)")
    public String uri;

    @Option(names = "--user", description = "Neo4j user (default: configuration.properties, else neo4j)")
    public String user;

    @Option(names = "--pass", description = "Neo4j password (default: configuration.properties, else env NEO4J_PASS, else 'neo4j')")
    public String pass;

    // ── configuration.properties (loaded once, cached) ───────────────────
    private static final String CONFIG_FILE = "configuration.properties";
    private static Properties cachedProps;

    private static synchronized Properties props() {
        if (cachedProps != null) return cachedProps;
        Properties p = new Properties();
        Path file = Path.of(CONFIG_FILE).toAbsolutePath();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                p.load(in);
            } catch (IOException e) {
                System.err.println("[neo4j] failed to read " + file + ": " + e.getMessage());
            }
        } else {
            // Classpath fallback (e.g. bundled under src/main/resources).
            try (InputStream in = Neo4jOptions.class.getClassLoader().getResourceAsStream(CONFIG_FILE)) {
                if (in != null) p.load(in);
            } catch (IOException e) {
                System.err.println("[neo4j] failed to read classpath " + CONFIG_FILE + ": " + e.getMessage());
            }
        }
        cachedProps = p;
        return cachedProps;
    }

    /** A trimmed, non-empty property value, or {@code null} if absent/blank. */
    private static String prop(String key) {
        String v = props().getProperty(key);
        return (v != null && !v.trim().isEmpty()) ? v.trim() : null;
    }

    /** Bolt URI: explicit flag, else bolt://{neo4j.hostname}:{neo4j.port}, else bolt://localhost:7687. */
    public String resolveUri() {
        if (uri != null && !uri.isEmpty()) return uri;
        String host = prop("neo4j.hostname");
        String port = prop("neo4j.port");
        if (host != null || port != null) {
            return "bolt://" + (host != null ? host : "localhost") + ":" + (port != null ? port : "7687");
        }
        return "bolt://localhost:7687";
    }

    /** Neo4j username: explicit flag, else neo4j.username, else 'neo4j'. */
    public String resolveUser() {
        if (user != null && !user.isEmpty()) return user;
        String u = prop("neo4j.username");
        return u != null ? u : "neo4j";
    }

    /** Neo4j password: explicit flag, else neo4j.password, else env NEO4J_PASS, else 'neo4j'. */
    public String resolvePass() {
        if (pass != null && !pass.isEmpty()) return pass;
        String p = prop("neo4j.password");
        if (p != null) return p;
        String env = System.getenv("NEO4J_PASS");
        return env != null ? env : "neo4j";
    }
}
