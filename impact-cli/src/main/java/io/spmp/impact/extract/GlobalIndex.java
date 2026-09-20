package io.spmp.impact.extract;

import io.spmp.impact.model.GraphEdges;
import io.spmp.impact.model.GraphNodes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Frozen-after-build indices used by {@link CallResolver} during Pass 2.
 *
 * <p><b>Lifecycle:</b>
 * <ol>
 *   <li>During Pass 1a (declaration extraction), workers call the {@code record*} methods
 *       on a single shared instance — backed by {@code ConcurrentHashMap} for safe concurrent writes.</li>
 *   <li>After Pass 1a completes, {@link #freeze()} swaps the live maps to immutable copies
 *       via {@code Map.copyOf}. The JIT can inline reads from immutable maps; we also drop
 *       the synchronization overhead for the read-heavy Pass 2.</li>
 *   <li>Pass 2 only ever reads. Threads share one instance with no lock contention.</li>
 * </ol>
 *
 * <p><b>Index shapes:</b>
 * <ul>
 *   <li>{@code classByFqn}: {@code Class FQN → ClassInfo} (simple name, package, interface?, abstract?, file path)</li>
 *   <li>{@code methodsByOwnerAndSimpleName}: {@code (ownerFqn + "#" + simpleName) → List<MethodSig>}.
 *       The composite key is intentionally a string to avoid allocating a record per lookup;
 *       the small extra character cost is paid once but the saved object headers add up over 800k+ calls.</li>
 *   <li>{@code fieldByOwnerAndName}: {@code (ownerFqn + "#" + fieldName) → FieldInfo}</li>
 *   <li>{@code extendsByChild} / {@code implementsByChild}: child class FQN → list of parents.
 *       Used by {@link #transitiveAncestors} to walk inheritance chains.</li>
 *   <li>{@code importsByFile}: file path → {@link FileImports} record</li>
 * </ul>
 *
 * <p><b>Why not Symbol Solver:</b> SymbolSolver re-resolves on every call expression, hitting
 * O(method-body) tree walks for local-variable lookups. This index makes every call resolution
 * O(1) avg via direct map lookup, with an O(depth) ancestor walk only when inheritance matters.
 */
public final class GlobalIndex {

    public record ClassInfo(
        String fqn, String simpleName, String pkg, String filePath,
        boolean isInterface, boolean isAbstract
    ) {}

    public record MethodSig(
        String fqn, String simpleName, String ownerFqn, int paramCount,
        boolean isStatic, boolean isConstructor
    ) {}

    public record FieldInfo(String fqn, String simpleName, String ownerFqn, String typeText) {}

    /** Mutable build-phase storage. Replaced with immutable copies in {@link #freeze()}. */
    private Map<String, ClassInfo> classByFqn = new ConcurrentHashMap<>();
    private Map<String, List<MethodSig>> methodsByOwnerAndSimpleName = new ConcurrentHashMap<>();
    private Map<String, FieldInfo> fieldByOwnerAndName = new ConcurrentHashMap<>();
    private Map<String, FileImports> importsByFile = new ConcurrentHashMap<>();
    private Map<String, List<String>> extendsByChild = new ConcurrentHashMap<>();
    private Map<String, List<String>> implementsByChild = new ConcurrentHashMap<>();
    private boolean frozen = false;

    // Memoized ancestor walks. Built lazily on first lookup in Pass 2.
    private final Map<String, Set<String>> ancestorCache = new ConcurrentHashMap<>();

    // ─── Build phase (called from Pass 1a workers) ───────────────────────

    public void recordClass(GraphNodes.ClassNode cn) {
        if (frozen) throw new IllegalStateException("GlobalIndex frozen");
        classByFqn.putIfAbsent(cn.fqn(),
            new ClassInfo(cn.fqn(), cn.simpleName(), cn.pkg(), cn.filePath(),
                cn.isInterface(), cn.isAbstract()));
    }

    public void recordMethod(GraphNodes.MethodNode mn, int paramCount) {
        if (frozen) throw new IllegalStateException("GlobalIndex frozen");
        String key = mn.ownerFqn() + "#" + mn.simpleName();
        // Compute & merge in a single atomic step; new ArrayList per slot is fine
        // because the per-slot list is small (overloads count, typically 1-10).
        methodsByOwnerAndSimpleName.compute(key, (k, existing) -> {
            List<MethodSig> list = existing == null ? new ArrayList<>(2) : existing;
            list.add(new MethodSig(mn.fqn(), mn.simpleName(), mn.ownerFqn(),
                paramCount, mn.isStatic(), mn.isConstructor()));
            return list;
        });
    }

    public void recordField(GraphNodes.FieldNode fn) {
        if (frozen) throw new IllegalStateException("GlobalIndex frozen");
        String key = fn.ownerFqn() + "#" + fn.simpleName();
        fieldByOwnerAndName.putIfAbsent(key,
            new FieldInfo(fn.fqn(), fn.simpleName(), fn.ownerFqn(), fn.type()));
    }

    public void recordImports(String filePath, FileImports imports) {
        if (frozen) throw new IllegalStateException("GlobalIndex frozen");
        importsByFile.put(filePath, imports);
    }

    public void recordExtends(GraphEdges.ExtendsEdge e) {
        if (frozen) throw new IllegalStateException("GlobalIndex frozen");
        extendsByChild.compute(e.fromClassFqn(), (k, list) -> {
            List<String> l = list == null ? new ArrayList<>(2) : list;
            l.add(e.toClassFqn());
            return l;
        });
    }

    public void recordImplements(GraphEdges.ImplementsEdge e) {
        if (frozen) throw new IllegalStateException("GlobalIndex frozen");
        implementsByChild.compute(e.fromClassFqn(), (k, list) -> {
            List<String> l = list == null ? new ArrayList<>(2) : list;
            l.add(e.toInterfaceFqn());
            return l;
        });
    }

    /**
     * Freeze the indices to immutable maps. After this, build methods throw and read
     * methods are lock-free + inline-friendly. Call once between Pass 1a and Pass 2.
     */
    public synchronized void freeze() {
        if (frozen) return;
        classByFqn = Map.copyOf(classByFqn);
        // Each slot's list also gets frozen to List.copyOf (immutable, cache-friendly).
        Map<String, List<MethodSig>> mFrozen = new HashMap<>(methodsByOwnerAndSimpleName.size());
        methodsByOwnerAndSimpleName.forEach((k, v) -> mFrozen.put(k, List.copyOf(v)));
        methodsByOwnerAndSimpleName = Map.copyOf(mFrozen);
        fieldByOwnerAndName = Map.copyOf(fieldByOwnerAndName);
        importsByFile = Map.copyOf(importsByFile);
        Map<String, List<String>> eFrozen = new HashMap<>(extendsByChild.size());
        extendsByChild.forEach((k, v) -> eFrozen.put(k, List.copyOf(v)));
        extendsByChild = Map.copyOf(eFrozen);
        Map<String, List<String>> iFrozen = new HashMap<>(implementsByChild.size());
        implementsByChild.forEach((k, v) -> iFrozen.put(k, List.copyOf(v)));
        implementsByChild = Map.copyOf(iFrozen);
        frozen = true;
    }

    public boolean isFrozen() { return frozen; }

    // ─── Read phase (called from Pass 2 / Pass 3) ────────────────────────

    public ClassInfo classOf(String fqn) {
        return classByFqn.get(fqn);
    }

    /** All methods declared on {@code ownerFqn} with this simple name. Empty list if none. */
    public List<MethodSig> methodsOn(String ownerFqn, String simpleName) {
        if (ownerFqn == null || simpleName == null) return List.of();
        List<MethodSig> list = methodsByOwnerAndSimpleName.get(ownerFqn + "#" + simpleName);
        return list == null ? List.of() : list;
    }

    public FieldInfo fieldOn(String ownerFqn, String fieldName) {
        if (ownerFqn == null || fieldName == null) return null;
        return fieldByOwnerAndName.get(ownerFqn + "#" + fieldName);
    }

    public FileImports importsOf(String filePath) {
        if (filePath == null) return FileImports.EMPTY;
        return importsByFile.getOrDefault(filePath, FileImports.EMPTY);
    }

    public List<String> directParents(String childFqn) {
        List<String> ext = extendsByChild.getOrDefault(childFqn, List.of());
        List<String> impl = implementsByChild.getOrDefault(childFqn, List.of());
        if (ext.isEmpty()) return impl;
        if (impl.isEmpty()) return ext;
        List<String> combined = new ArrayList<>(ext.size() + impl.size());
        combined.addAll(ext);
        combined.addAll(impl);
        return combined;
    }

    /**
     * All transitive ancestors of {@code childFqn} (extends + implements, recursively).
     * Memoized — first call walks the tree, subsequent calls are O(1).
     */
    public Set<String> transitiveAncestors(String childFqn) {
        if (childFqn == null) return Set.of();
        Set<String> cached = ancestorCache.get(childFqn);
        if (cached != null) return cached;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(directParents(childFqn));
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String p = queue.poll();
            if (!visited.add(p)) continue;
            out.add(p);
            for (String pp : directParents(p)) {
                if (!visited.contains(pp)) queue.add(pp);
            }
        }
        Set<String> result = Set.copyOf(out);
        ancestorCache.put(childFqn, result);
        return result;
    }

    // ─── Stats / introspection ───────────────────────────────────────────

    public int classCount() { return classByFqn.size(); }
    public int methodSlotCount() { return methodsByOwnerAndSimpleName.size(); }
    public int fieldCount() { return fieldByOwnerAndName.size(); }
    public int fileCount() { return importsByFile.size(); }

    public int methodTotalCount() {
        int n = 0;
        for (List<MethodSig> v : methodsByOwnerAndSimpleName.values()) n += v.size();
        return n;
    }
}
