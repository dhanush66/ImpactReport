package io.spmp.impact.graph.txn;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.TransactionContext;
import org.neo4j.driver.Value;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bolt-backed {@link CypherClient}. Thin adapter — wraps the official Neo4j Java driver.
 * Used in production where Bolt is fast and available. Same client interface as
 * {@link HttpCypherClient} so call sites are transport-agnostic.
 */
public final class BoltCypherClient implements CypherClient {

    private final Driver driver;
    private final Session session;

    public BoltCypherClient(String uri, String user, String pass) {
        this.driver = GraphDatabase.driver(uri, AuthTokens.basic(user, pass));
        this.session = driver.session();
    }

    @Override public CResult run(String cypher) {
        return new BoltResult(session.run(cypher));
    }

    @Override public CResult run(String cypher, Map<String, Object> params) {
        return new BoltResult(session.run(cypher, params));
    }

    @Override public <T> T executeWrite(Function<CTx, T> work) {
        return session.executeWrite(tx -> work.apply(new BoltTx(tx)));
    }

    @Override public void close() {
        try { session.close(); } catch (Exception ignored) {}
        driver.close();
    }

    // ─── adapters ────────────────────────────────────────────────

    private static final class BoltTx implements CTx {
        private final TransactionContext tx;
        BoltTx(TransactionContext tx) { this.tx = tx; }
        @Override public CResult run(String cypher, Map<String, Object> params) {
            return new BoltResult(tx.run(cypher, params));
        }
    }

    private static final class BoltResult implements CResult {
        private final Result r;
        BoltResult(Result r) { this.r = r; }
        @Override public boolean hasNext() { return r.hasNext(); }
        @Override public CRecord next() { return new BoltRecord(r.next()); }
        @Override public void consume() { r.consume(); }
        @Override public Iterator<CRecord> iterator() {
            return new Iterator<>() {
                @Override public boolean hasNext() { return r.hasNext(); }
                @Override public CRecord next() { return new BoltRecord(r.next()); }
            };
        }
        @Override public void close() {}
    }

    private static final class BoltRecord implements CRecord {
        private final Record rec;
        BoltRecord(Record rec) { this.rec = rec; }
        @Override public CValue get(String column) { return new BoltValue(rec.get(column)); }
        @Override public Map<String, Object> asMap() { return rec.asMap(); }
    }

    private static final class BoltValue implements CValue {
        private final Value v;
        BoltValue(Value v) { this.v = v; }
        @Override public boolean isNull() { return v == null || v.isNull(); }
        @Override public String asString() { return v.asString(); }
        @Override public String asString(String defaultValue) { return v.asString(defaultValue); }
        @Override public int asInt() { return v.asInt(); }
        @Override public int asInt(int defaultValue) { return v.asInt(defaultValue); }
        @Override public long asLong() { return v.asLong(); }
        @Override public long asLong(long defaultValue) { return v.asLong(defaultValue); }
        @Override public double asDouble() { return v.asDouble(); }
        @Override public double asDouble(double defaultValue) { return v.asDouble(defaultValue); }
        @Override public boolean asBoolean() { return v.asBoolean(); }
        @Override public boolean asBoolean(boolean defaultValue) { return v.asBoolean(defaultValue); }
        @Override public <T> List<T> asList(Function<CValue, T> mapper) {
            return v.asList(x -> mapper.apply(new BoltValue(x)))
                .stream().collect(Collectors.toList());
        }
        @Override public CValue get(String column) { return new BoltValue(v.get(column)); }
    }
}
