package io.spmp.impact.graph.txn;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Minimal client surface used by every part of impact-cli that talks to Neo4j.
 *
 * <p>Two implementations: {@link BoltCypherClient} (wraps the official Neo4j Java driver
 * over the Bolt protocol — fast, what production uses) and {@link HttpCypherClient}
 * (uses Neo4j's transactional Cypher HTTP API — slower, but works in environments
 * where Netty's NIO loopback selector is blocked, e.g. our sandboxed dev runner).
 *
 * <p>{@link #open(String, String, String)} auto-selects by URI scheme.
 */
public interface CypherClient extends AutoCloseable {

    /**
     * Open a client. URI scheme decides the transport:
     * <ul>
     *   <li>{@code bolt://} or {@code neo4j://}: Bolt driver</li>
     *   <li>{@code http://} or {@code https://}: HTTP transactional API</li>
     * </ul>
     */
    static CypherClient open(String uri, String user, String pass) {
        if (uri == null) throw new IllegalArgumentException("uri must not be null");
        String lower = uri.toLowerCase();
        if (lower.startsWith("bolt://") || lower.startsWith("neo4j://")
            || lower.startsWith("bolt+s://") || lower.startsWith("neo4j+s://")) {
            return new BoltCypherClient(uri, user, pass);
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return new HttpCypherClient(uri, user, pass);
        }
        throw new IllegalArgumentException("Unsupported Neo4j URI scheme: " + uri);
    }

    /** Run a parameterless cypher and stream rows. */
    CResult run(String cypher);

    /** Run a parameterized cypher and stream rows. */
    CResult run(String cypher, Map<String, Object> params);

    /**
     * Run a write transaction. The work function receives a context that can submit
     * additional statements in the same transaction. The function's return value is
     * passed back to the caller.
     */
    <T> T executeWrite(Function<CTx, T> work);

    @Override
    void close();

    // ─────────────────────────────────────────────────────────────────
    // Row / record / value abstractions
    // ─────────────────────────────────────────────────────────────────

    interface CResult extends Iterable<CRecord>, AutoCloseable {
        boolean hasNext();
        CRecord next();
        /** Drain and discard remaining rows. */
        void consume();
        @Override
        void close();
    }

    interface CRecord {
        CValue get(String column);
        Map<String, Object> asMap();
    }

    interface CValue {
        boolean isNull();
        String asString();
        String asString(String defaultValue);
        int asInt();
        int asInt(int defaultValue);
        long asLong();
        long asLong(long defaultValue);
        double asDouble();
        double asDouble(double defaultValue);
        boolean asBoolean();
        boolean asBoolean(boolean defaultValue);
        <T> List<T> asList(Function<CValue, T> mapper);

        /** For nested map-shaped values (like the entry-point objects in slice results). */
        CValue get(String column);
    }

    interface CTx {
        CResult run(String cypher, Map<String, Object> params);
    }
}
