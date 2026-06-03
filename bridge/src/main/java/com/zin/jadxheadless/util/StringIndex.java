package com.zin.jadxheadless.util;

import jadx.api.JavaClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Persistent inverted index: DEX string-constant value -&gt; the classes whose
 * smali contains a {@code const-string} with that exact value.
 *
 * <p><b>Why.</b> {@code find-string-usages} (smali mode) otherwise generates smali
 * for every class on every call — minutes on a 100k+ class APK. The smali-mode
 * needle is the quoted literal {@code "literal"}, and because the quotes bound the
 * match, that is equivalent to an <i>exact</i> match against a {@code const-string}
 * operand value. So an exact-key map (value -&gt; class FQNs) reproduces smali-mode
 * results precisely, in O(1), and additionally yields an accurate total hit count
 * (the live scan can only report a partial count when it times out).
 *
 * <p><b>Space-for-time.</b> The index is built once (parallel smali scan) and
 * persisted next to the APK, so a later run — even after a full restart — loads it
 * in seconds instead of rebuilding. Keyed by APK path + size; a changed APK rebuilds.
 *
 * <p><b>Faithfulness / scope.</b> Keys are the smali operand text (escape sequences
 * as they appear in smali, e.g. {@code \n}, {@code \"}). For the overwhelmingly common
 * escape-free literal this equals the raw string. The fast path is used ONLY for
 * {@code source=smali} + {@code case_sensitive=true}; code/both/substring/case-insensitive
 * queries fall back to the (bounded) live scan, so there is no behavior regression.
 */
public final class StringIndex {

    private static final Logger logger = LoggerFactory.getLogger(StringIndex.class);

    /** Bump when the on-disk format changes so stale files are ignored. */
    private static final int MAGIC = 0x4A584D31; // "JXM1"
    private static final int FORMAT_VERSION = 4; // v4 adds the class-level call graph (forward + reverse)

    public enum Status { ABSENT, BUILDING, READY, FAILED }

    private volatile Status status = Status.ABSENT;
    private volatile String detail = "";
    private final AtomicInteger builtClasses = new AtomicInteger();
    private volatile int totalClasses = 0;
    private volatile long buildMillis = 0;

    /** const-string value -> sorted class ids (ids index into {@link #idToFqn}). */
    private volatile Map<String, int[]> postings;
    /** method name -> sorted class ids (classes declaring a method with that exact name). */
    private volatile Map<String, int[]> methodPostings;
    /** field name -> sorted class ids (classes declaring a field with that exact name). */
    private volatile Map<String, int[]> fieldPostings;
    /** supertype/interface FQN -> sorted class ids of its DIRECT subclasses/implementors. */
    private volatile Map<String, int[]> typeHierarchy;
    /** callee-space FQNs: invoke-target owner classes (may include framework/library classes not in idToFqn). */
    private volatile String[] calleeNames;
    /** caller class id (into idToFqn) -> sorted callee ids (into calleeNames). The forward call graph. */
    private volatile int[][] forwardCalls;
    /** callee id (into calleeNames) -> sorted caller class ids (into idToFqn). The reverse call graph. */
    private volatile int[][] reverseCalls;
    /** code identifier token -> sorted ids into {@link #codeIdToFqn}. Built from decompiled source of a LIMITED
     *  main-package subset (full-corpus decompile is infeasible). Persisted in a separate {@code .codeidx} file. */
    private volatile Map<String, int[]> codeTokens;
    /** FQNs of the classes actually decompiled into {@link #codeTokens} (the main-package subset). */
    private volatile String[] codeIdToFqn;
    private volatile int codeIndexedClasses = 0;
    /** Lazy FQN -> id reverse maps for call-graph queries (built on first use). */
    private volatile Map<String, Integer> fqnToId;
    private volatile Map<String, Integer> calleeFqnToId;
    private volatile String[] idToFqn;

    public Status status() { return status; }

    public Map<String, Object> statusMap() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("status", status.name().toLowerCase());
        m.put("built_classes", builtClasses.get());
        m.put("total_classes", totalClasses);
        m.put("distinct_strings", postings == null ? 0 : postings.size());
        m.put("distinct_methods", methodPostings == null ? 0 : methodPostings.size());
        m.put("distinct_fields", fieldPostings == null ? 0 : fieldPostings.size());
        m.put("distinct_supertypes", typeHierarchy == null ? 0 : typeHierarchy.size());
        m.put("callee_classes", calleeNames == null ? 0 : calleeNames.length);
        m.put("code_indexed_classes", codeIndexedClasses);
        m.put("code_tokens", codeTokens == null ? 0 : codeTokens.size());
        m.put("build_ms", buildMillis);
        if (!detail.isEmpty()) m.put("detail", detail);
        return m;
    }

    /**
     * Look up classes containing a const-string equal to {@code value}.
     * Returns {@code null} when the index is not READY (caller should fall back
     * to a live scan); an empty list means "ready, but no class has it".
     */
    public List<String> lookup(String value, String packageFilter) {
        if (status != Status.READY) return null;
        Map<String, int[]> p = postings;
        String[] f = idToFqn;
        if (p == null || f == null) return null;
        int[] ids = p.get(value);
        if (ids == null) return Collections.emptyList();
        boolean filt = packageFilter != null && !packageFilter.isEmpty();
        List<String> out = new ArrayList<>(ids.length);
        for (int id : ids) {
            if (id < 0 || id >= f.length) continue;
            String fqn = f[id];
            if (!filt || fqn.startsWith(packageFilter + ".") || fqn.equals(packageFilter)) {
                out.add(fqn);
            }
        }
        return out;
    }

    /**
     * Build the index from a parallel smali scan. {@code smaliFn} returns a class's
     * smali (or null). Safe to call once; concurrent/repeat calls are ignored.
     */
    public synchronized void build(List<JavaClass> classes, java.util.function.Function<JavaClass, String> smaliFn) {
        if (status == Status.READY || status == Status.BUILDING) return;
        status = Status.BUILDING;
        totalClasses = classes.size();
        builtClasses.set(0);
        long t0 = System.currentTimeMillis();
        try {
            final String[] fqns = new String[classes.size()];
            // IntBag (primitive int[]) instead of Set<Integer>: ~8 bytes/posting vs ~48 for a
            // boxed-Integer HashSet. With TWO indexes (strings + method names) over ~14M postings
            // that ~6x saving is the difference between fitting in heap and OOM.
            final ConcurrentHashMap<String, IntBag> tmp = new ConcurrentHashMap<>(1 << 20);
            final ConcurrentHashMap<String, IntBag> tmpM = new ConcurrentHashMap<>(1 << 19);
            final ConcurrentHashMap<String, IntBag> tmpF = new ConcurrentHashMap<>(1 << 19);
            final ConcurrentHashMap<String, IntBag> tmpH = new ConcurrentHashMap<>(1 << 16);
            // Forward call graph: each caller task fills its own array slot (no cross-thread
            // contention); callee FQNs are interned into a shared id space (calleeIdMap).
            final IntBag[] forwardBags = new IntBag[classes.size()];
            final ConcurrentHashMap<String, Integer> calleeIdMap = new ConcurrentHashMap<>(1 << 19);
            final AtomicInteger calleeSeq = new AtomicInteger();
            IntStream.range(0, classes.size()).parallel().forEach(i -> {
                try {
                    JavaClass c = classes.get(i);
                    fqns[i] = c.getFullName();
                    String smali = smaliFn.apply(c);
                    if (smali != null && !smali.isEmpty()) {
                        final int id = i;
                        extractConstStrings(smali, val ->
                                tmp.computeIfAbsent(val, k -> new IntBag()).add(id));
                        extractMethodNames(smali, name ->
                                tmpM.computeIfAbsent(name, k -> new IntBag()).add(id));
                        extractFieldNames(smali, name ->
                                tmpF.computeIfAbsent(name, k -> new IntBag()).add(id));
                        extractSuperTypes(smali, sup ->
                                tmpH.computeIfAbsent(sup, k -> new IntBag()).add(id));
                        IntBag fb = new IntBag();
                        extractInvokeOwners(smali, owner ->
                                fb.add(calleeIdMap.computeIfAbsent(owner, k -> calleeSeq.getAndIncrement())));
                        forwardBags[i] = fb;
                    }
                } catch (Throwable t) {
                    // One bad/huge class must not fail the whole build.
                    if (fqns[i] == null) fqns[i] = "?";
                }
                int done = builtClasses.incrementAndGet();
                if ((done & 0x1FFF) == 0) {
                    logger.info("[string-index] built {}/{} classes", done, classes.size());
                }
            });
            Map<String, int[]> frozen = freeze(tmp);
            Map<String, int[]> frozenM = freeze(tmpM);
            Map<String, int[]> frozenF = freeze(tmpF);
            Map<String, int[]> frozenH = freeze(tmpH);
            // Freeze the forward graph + intern callee names, then derive the reverse graph (serial,
            // O(edges)). A single shared EMPTY avoids allocating one empty array per call-less class.
            final int[] EMPTY = new int[0];
            int[][] fwd = new int[classes.size()][];
            for (int i = 0; i < fwd.length; i++) {
                fwd[i] = forwardBags[i] == null ? EMPTY : forwardBags[i].toSortedUnique();
            }
            int calleeCount = calleeSeq.get();
            String[] calleeArr = new String[calleeCount];
            calleeIdMap.forEach((fqn, cid) -> { if (cid != null && cid >= 0 && cid < calleeCount) calleeArr[cid] = fqn; });
            IntBag[] revBags = new IntBag[calleeCount];
            long edgeCount = 0;
            for (int i = 0; i < fwd.length; i++) {
                for (int cid : fwd[i]) {
                    if (cid < 0 || cid >= calleeCount) continue;
                    edgeCount++;
                    IntBag rb = revBags[cid];
                    if (rb == null) { rb = new IntBag(); revBags[cid] = rb; }
                    rb.add(i);
                }
            }
            int[][] rev = new int[calleeCount][];
            for (int cid = 0; cid < calleeCount; cid++) {
                rev[cid] = revBags[cid] == null ? EMPTY : revBags[cid].toSortedUnique();
            }
            this.idToFqn = fqns;
            this.postings = frozen;
            this.methodPostings = frozenM;
            this.fieldPostings = frozenF;
            this.typeHierarchy = frozenH;
            this.calleeNames = calleeArr;
            this.forwardCalls = fwd;
            this.reverseCalls = rev;
            this.fqnToId = null;
            this.calleeFqnToId = null;
            this.buildMillis = System.currentTimeMillis() - t0;
            status = Status.READY;
            logger.info("[string-index] READY: {} strings / {} methods / {} fields / {} supertypes / {} callees / {} call-edges over {} classes in {}ms",
                    frozen.size(), frozenM.size(), frozenF.size(), frozenH.size(), calleeCount, edgeCount, classes.size(), buildMillis);
        } catch (Throwable t) {
            detail = String.valueOf(t);
            status = Status.FAILED;
            logger.warn("[string-index] build FAILED: {}", t.toString());
        }
    }

    /** Freeze a building map (value -> IntBag) into value -> sorted-unique id[] for compact lookup. */
    private static Map<String, int[]> freeze(ConcurrentHashMap<String, IntBag> tmp) {
        Map<String, int[]> frozen = new ConcurrentHashMap<>(Math.max(16, tmp.size() * 2));
        tmp.entrySet().parallelStream().forEach(e -> frozen.put(e.getKey(), e.getValue().toSortedUnique()));
        return frozen;
    }

    /**
     * Append-only, synchronized primitive-int bag used during the build. Far cheaper than
     * {@code Set<Integer>} (no boxing, no per-element node) — the key to fitting both indexes
     * in heap. Duplicates are removed at freeze time via {@link #toSortedUnique()}.
     */
    static final class IntBag {
        private int[] a = new int[4];
        private int n = 0;
        synchronized void add(int v) {
            if (n == a.length) a = java.util.Arrays.copyOf(a, n << 1);
            a[n++] = v;
        }
        synchronized int[] toSortedUnique() {
            if (n == 0) return new int[0];
            int[] s = java.util.Arrays.copyOf(a, n);
            java.util.Arrays.sort(s);
            int w = 1;
            for (int r = 1; r < s.length; r++) {
                if (s[r] != s[w - 1]) s[w++] = s[r];
            }
            return w == s.length ? s : java.util.Arrays.copyOf(s, w);
        }
    }

    /**
     * Classes declaring a method whose name EXACTLY equals {@code name}. {@code null} if
     * index not READY (caller falls back to live scan); empty list = ready but no match.
     */
    public List<String> lookupMethodExact(String name, String packageFilter) {
        Map<String, int[]> mp = methodPostings;
        if (status != Status.READY || mp == null || idToFqn == null) return null;
        return idsToFqns(mp.get(name), packageFilter);
    }

    /**
     * Classes declaring a method whose name CONTAINS {@code term} (case-insensitive), up to
     * {@code cap} distinct classes (cap&lt;=0 = no cap). Iterates the distinct method-name key
     * set (far smaller, and free of the per-class getMethods() cost) instead of scanning every
     * class. Returns null if index not READY. The returned int[] {scanned-keys} is for metadata.
     */
    public List<String> lookupMethodContains(String term, String packageFilter, int cap, int[] outScannedKeys) {
        Map<String, int[]> mp = methodPostings;
        String[] f = idToFqn;
        if (status != Status.READY || mp == null || f == null) return null;
        String t = term.toLowerCase();
        boolean filt = packageFilter != null && !packageFilter.isEmpty();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        int keys = 0;
        for (Map.Entry<String, int[]> e : mp.entrySet()) {
            keys++;
            if (!e.getKey().toLowerCase().contains(t)) continue;
            for (int id : e.getValue()) {
                if (id < 0 || id >= f.length) continue;
                String fqn = f[id];
                if (!filt || fqn.startsWith(packageFilter + ".") || fqn.equals(packageFilter)) {
                    out.add(fqn);
                    if (cap > 0 && out.size() >= cap) { if (outScannedKeys != null) outScannedKeys[0] = keys; return new ArrayList<>(out); }
                }
            }
        }
        if (outScannedKeys != null) outScannedKeys[0] = keys;
        return new ArrayList<>(out);
    }

    /** Per-value cap on how many owning classes are inlined into a {@link #searchKeys} hit. */
    private static final int MAX_CLASSES_PER_VALUE = 100;

    /**
     * Discovery search over the const-string KEY SET: find string CONSTANTS whose value matches
     * {@code query} by substring (default) or regex, and list the classes that declare each.
     *
     * <p>Distinct from {@link #lookup(String, String)} (exact value -&gt; classes). This is
     * "show me every embedded string containing 'http' / matching '[A-Za-z0-9+/]{32,}' and where
     * it lives" — the bread-and-butter of locating URLs, keys, and crypto material. O(distinct
     * strings), sub-second even at ~776k keys, because the heavy smali pass already ran at build
     * time. Returns {@code null} when the index is not READY (caller falls back / reports building).
     *
     * @param cap max distinct matching values to return (&lt;=0 = no cap)
     */
    public List<Map<String, Object>> searchKeys(String query, boolean caseSensitive, boolean regex,
                                                String packageFilter, int cap) {
        Map<String, int[]> p = postings;
        String[] f = idToFqn;
        if (status != Status.READY || p == null || f == null) return null;
        boolean filt = packageFilter != null && !packageFilter.isEmpty();
        java.util.regex.Pattern pat = null;
        String needle = null;
        if (regex) {
            pat = java.util.regex.Pattern.compile(query,
                    caseSensitive ? 0 : java.util.regex.Pattern.CASE_INSENSITIVE);
        } else {
            needle = caseSensitive ? query : query.toLowerCase();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : p.entrySet()) {
            String key = e.getKey();
            boolean match;
            if (pat != null) {
                match = pat.matcher(key).find();
            } else {
                String hay = caseSensitive ? key : key.toLowerCase();
                match = hay.contains(needle);
            }
            if (!match) continue;
            List<String> classes = new ArrayList<>();
            int matchedCount = 0;
            for (int id : e.getValue()) {
                if (id < 0 || id >= f.length) continue;
                String fqn = f[id];
                if (filt && !(fqn.startsWith(packageFilter + ".") || fqn.equals(packageFilter))) continue;
                matchedCount++;
                if (classes.size() < MAX_CLASSES_PER_VALUE) classes.add(fqn);
            }
            if (matchedCount == 0) continue; // package filter removed every owner
            Map<String, Object> row = new HashMap<>();
            row.put("value", key);
            row.put("class_count", matchedCount);
            row.put("classes", classes);
            if (matchedCount > classes.size()) row.put("classes_truncated", true);
            out.add(row);
            if (cap > 0 && out.size() >= cap) break;
        }
        return out;
    }

    private List<String> idsToFqns(int[] ids, String packageFilter) {
        String[] f = idToFqn;
        if (ids == null || f == null) return Collections.emptyList();
        boolean filt = packageFilter != null && !packageFilter.isEmpty();
        List<String> out = new ArrayList<>(ids.length);
        for (int id : ids) {
            if (id < 0 || id >= f.length) continue;
            String fqn = f[id];
            if (!filt || fqn.startsWith(packageFilter + ".") || fqn.equals(packageFilter)) out.add(fqn);
        }
        return out;
    }

    /**
     * Classes declaring a FIELD whose name contains {@code term} (case-insensitive), up to {@code cap}
     * distinct classes. Mirror of {@link #lookupMethodContains}. Returns null if the index is not READY.
     */
    public List<String> lookupFieldContains(String term, String packageFilter, int cap, int[] outScannedKeys) {
        Map<String, int[]> fp = fieldPostings;
        String[] f = idToFqn;
        if (status != Status.READY || fp == null || f == null) return null;
        String t = term.toLowerCase();
        boolean filt = packageFilter != null && !packageFilter.isEmpty();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        int keys = 0;
        for (Map.Entry<String, int[]> e : fp.entrySet()) {
            keys++;
            if (!e.getKey().toLowerCase().contains(t)) continue;
            for (int id : e.getValue()) {
                if (id < 0 || id >= f.length) continue;
                String fqn = f[id];
                if (!filt || fqn.startsWith(packageFilter + ".") || fqn.equals(packageFilter)) {
                    out.add(fqn);
                    if (cap > 0 && out.size() >= cap) { if (outScannedKeys != null) outScannedKeys[0] = keys; return new ArrayList<>(out); }
                }
            }
        }
        if (outScannedKeys != null) outScannedKeys[0] = keys;
        return new ArrayList<>(out);
    }

    /**
     * Direct subclasses / interface implementors of {@code supertypeFqn} (exact FQN). {@code null} if
     * the index is not READY; an empty list means ready but nothing declares it as super/interface.
     */
    public List<String> lookupSubtypes(String supertypeFqn, String packageFilter) {
        Map<String, int[]> th = typeHierarchy;
        if (status != Status.READY || th == null || idToFqn == null) return null;
        return idsToFqns(th.get(supertypeFqn), packageFilter);
    }

    // -------------------- call graph --------------------

    /** Lazily build the FQN -> id reverse maps used by call-graph queries. */
    private void ensureCallMaps() {
        if (fqnToId != null && calleeFqnToId != null) return;
        synchronized (this) {
            if (fqnToId == null) {
                String[] f = idToFqn;
                Map<String, Integer> m = new java.util.HashMap<>(f == null ? 16 : f.length * 2);
                if (f != null) for (int i = 0; i < f.length; i++) if (f[i] != null) m.putIfAbsent(f[i], i);
                fqnToId = m;
            }
            if (calleeFqnToId == null) {
                String[] cn = calleeNames;
                Map<String, Integer> m = new java.util.HashMap<>(cn == null ? 16 : cn.length * 2);
                if (cn != null) for (int i = 0; i < cn.length; i++) if (cn[i] != null) m.putIfAbsent(cn[i], i);
                calleeFqnToId = m;
            }
        }
    }

    /** Direct callees (class level) of {@code classFqn}; null if the call graph isn't READY. */
    public List<String> directCallees(String classFqn, String packageFilter) {
        if (status != Status.READY || forwardCalls == null || calleeNames == null) return null;
        ensureCallMaps();
        Integer id = fqnToId.get(classFqn);
        if (id == null || id < 0 || id >= forwardCalls.length) return Collections.emptyList();
        boolean filt = packageFilter != null && !packageFilter.isEmpty();
        List<String> out = new ArrayList<>();
        for (int cid : forwardCalls[id]) {
            if (cid < 0 || cid >= calleeNames.length) continue;
            String fqn = calleeNames[cid];
            if (fqn != null && (!filt || fqn.startsWith(packageFilter + ".") || fqn.equals(packageFilter))) out.add(fqn);
        }
        return out;
    }

    /** Direct callers (class level) of {@code classFqn}; null if the call graph isn't READY. */
    public List<String> directCallers(String classFqn, String packageFilter) {
        if (status != Status.READY || reverseCalls == null || idToFqn == null) return null;
        ensureCallMaps();
        Integer cid = calleeFqnToId.get(classFqn);
        if (cid == null || cid < 0 || cid >= reverseCalls.length) return Collections.emptyList();
        boolean filt = packageFilter != null && !packageFilter.isEmpty();
        List<String> out = new ArrayList<>();
        for (int id : reverseCalls[cid]) {
            if (id < 0 || id >= idToFqn.length) continue;
            String fqn = idToFqn[id];
            if (fqn != null && (!filt || fqn.startsWith(packageFilter + ".") || fqn.equals(packageFilter))) out.add(fqn);
        }
        return out;
    }

    /**
     * BFS over the call graph from {@code startFqn} up to {@code maxDepth} hops, capping visited
     * nodes at {@code cap}. {@code callees=true} follows forward edges (what this class transitively
     * calls), false follows reverse edges (what transitively calls it). Each reached node is returned
     * with its shortest depth (the start node is excluded). Null if the graph isn't READY.
     */
    public List<Map<String, Object>> traverseCallGraph(String startFqn, boolean callees,
                                                        int maxDepth, int cap, String packageFilter) {
        if (status != Status.READY || forwardCalls == null || reverseCalls == null) return null;
        ensureCallMaps();
        boolean filt = packageFilter != null && !packageFilter.isEmpty();
        java.util.LinkedHashMap<String, Integer> seen = new java.util.LinkedHashMap<>();
        java.util.ArrayDeque<String> frontier = new java.util.ArrayDeque<>();
        seen.put(startFqn, 0);
        frontier.add(startFqn);
        int depth = 0;
        while (!frontier.isEmpty() && depth < maxDepth) {
            depth++;
            int sz = frontier.size();
            for (int s = 0; s < sz && !frontier.isEmpty(); s++) {
                String cur = frontier.poll();
                List<String> next = callees ? directCallees(cur, null) : directCallers(cur, null);
                if (next == null) continue;
                for (String nb : next) {
                    if (seen.containsKey(nb)) continue;
                    seen.put(nb, depth);
                    frontier.add(nb);
                    if (seen.size() >= cap) { frontier.clear(); break; }
                }
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : seen.entrySet()) {
            if (e.getValue() == 0) continue; // exclude the start node
            String fqn = e.getKey();
            if (filt && !(fqn.startsWith(packageFilter + ".") || fqn.equals(packageFilter))) continue;
            Map<String, Object> row = new HashMap<>();
            row.put("class", fqn);
            row.put("depth", e.getValue());
            out.add(row);
        }
        return out;
    }

    // -------------------- code identifier index (limited main-package subset) --------------------

    /**
     * Build the code-identifier index from a LIMITED set of classes (the caller passes a
     * main-package subset — full-corpus decompile is infeasible). Decompiles each via {@code codeFn}
     * and inverts its Java identifiers (length &gt;= 3) -&gt; classes. Self-contained id space
     * ({@link #codeIdToFqn}); persisted separately via {@link #saveCodeIndex}.
     */
    public synchronized void buildCodeIndex(List<JavaClass> classes,
                                            java.util.function.Function<JavaClass, String> codeFn) {
        final String[] fqns = new String[classes.size()];
        final ConcurrentHashMap<String, IntBag> tmp = new ConcurrentHashMap<>(1 << 19);
        IntStream.range(0, classes.size()).parallel().forEach(i -> {
            try {
                JavaClass c = classes.get(i);
                fqns[i] = c.getFullName();
                String code = codeFn.apply(c);
                if (code != null && !code.isEmpty()) {
                    final int id = i;
                    extractIdentifiers(code, tok -> tmp.computeIfAbsent(tok, k -> new IntBag()).add(id));
                }
            } catch (Throwable t) {
                if (fqns[i] == null) fqns[i] = "?";
            }
        });
        this.codeIdToFqn = fqns;
        this.codeTokens = freeze(tmp);
        this.codeIndexedClasses = classes.size();
        logger.info("[code-index] READY: {} tokens over {} main-package classes",
                codeTokens.size(), classes.size());
    }

    public boolean codeIndexReady() {
        return codeTokens != null && codeIdToFqn != null;
    }

    /**
     * Classes (within the code-indexed subset) whose decompiled source contains an identifier token
     * matching {@code term} (case-insensitive substring), up to {@code cap}. Null if the code index
     * isn't built. Identifier-level: won't match across token boundaries / punctuation — callers use
     * the live code scan for those (or a full scan).
     */
    public List<String> lookupCodeContains(String term, String packageFilter, int cap, int[] outScannedKeys) {
        Map<String, int[]> ct = codeTokens;
        String[] f = codeIdToFqn;
        if (ct == null || f == null) return null;
        String t = term.toLowerCase();
        boolean filt = packageFilter != null && !packageFilter.isEmpty();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        int keys = 0;
        for (Map.Entry<String, int[]> e : ct.entrySet()) {
            keys++;
            if (!e.getKey().toLowerCase().contains(t)) continue;
            for (int id : e.getValue()) {
                if (id < 0 || id >= f.length) continue;
                String fqn = f[id];
                if (fqn != null && (!filt || fqn.startsWith(packageFilter + ".") || fqn.equals(packageFilter))) {
                    out.add(fqn);
                    if (cap > 0 && out.size() >= cap) { if (outScannedKeys != null) outScannedKeys[0] = keys; return new ArrayList<>(out); }
                }
            }
        }
        if (outScannedKeys != null) outScannedKeys[0] = keys;
        return new ArrayList<>(out);
    }

    /** Extract Java identifier tokens (length &gt;= 3) from decompiled code; the caller's IntBag de-dups. */
    private static void extractIdentifiers(String code, java.util.function.Consumer<String> sink) {
        int n = code.length();
        int i = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (Character.isJavaIdentifierStart(c)) {
                int s = i;
                i++;
                while (i < n && Character.isJavaIdentifierPart(code.charAt(i))) i++;
                if (i - s >= 3) sink.accept(code.substring(s, i));
            } else {
                i++;
            }
        }
    }

    /**
     * Extract every method name declared in a class's smali ({@code .method <mods> name(...)...}),
     * including {@code <init>}/{@code <clinit>}. Hand-written scan (no regex), one line per method.
     */
    private static void extractMethodNames(String smali, java.util.function.Consumer<String> sink) {
        int from = 0;
        int len = smali.length();
        while (true) {
            int idx = smali.indexOf(".method", from);
            if (idx < 0) break;
            int lineEnd = smali.indexOf('\n', idx);
            if (lineEnd < 0) lineEnd = len;
            int paren = smali.indexOf('(', idx);
            if (paren > 0 && paren < lineEnd) {
                int s = paren - 1;
                while (s > idx && smali.charAt(s) != ' ' && smali.charAt(s) != '\t') s--;
                String name = smali.substring(s + 1, paren);
                if (!name.isEmpty()) sink.accept(name);
            }
            from = lineEnd + 1;
        }
    }

    /**
     * Extract every field name declared in a class's smali ({@code .field <mods> name:type}).
     * Hand-written scan, one line per field.
     */
    private static void extractFieldNames(String smali, java.util.function.Consumer<String> sink) {
        int from = 0;
        int len = smali.length();
        while (true) {
            int idx = smali.indexOf(".field", from);
            if (idx < 0) break;
            int lineEnd = smali.indexOf('\n', idx);
            if (lineEnd < 0) lineEnd = len;
            int colon = smali.indexOf(':', idx);
            if (colon > 0 && colon < lineEnd) {
                int s = colon - 1;
                while (s > idx && smali.charAt(s) != ' ' && smali.charAt(s) != '\t') s--;
                String name = smali.substring(s + 1, colon);
                if (!name.isEmpty()) sink.accept(name);
            }
            from = lineEnd + 1;
        }
    }

    /**
     * Extract a class's direct super class ({@code .super L...;}) and interfaces
     * ({@code .implements L...;}) as dotted FQNs.
     */
    private static void extractSuperTypes(String smali, java.util.function.Consumer<String> sink) {
        int len = smali.length();
        int sup = smali.indexOf(".super ");
        if (sup >= 0) {
            String t = smaliRefType(smali, sup, len);
            if (t != null) sink.accept(t);
        }
        int from = 0;
        while (true) {
            int idx = smali.indexOf(".implements ", from);
            if (idx < 0) break;
            int lineEnd = smali.indexOf('\n', idx);
            if (lineEnd < 0) lineEnd = len;
            String t = smaliRefType(smali, idx, lineEnd);
            if (t != null) sink.accept(t);
            from = lineEnd + 1;
        }
    }

    /** Parse the {@code L<pkg>/<Name>;} token after {@code .super}/{@code .implements} into a dotted FQN. */
    private static String smaliRefType(String smali, int from, int limit) {
        int l = smali.indexOf('L', from);
        if (l < 0 || l >= limit) return null;
        int semi = smali.indexOf(';', l);
        if (semi < 0 || semi >= limit) return null;
        return smali.substring(l + 1, semi).replace('/', '.');
    }

    /**
     * Extract the owner FQN of every {@code invoke-*} target in a class's smali (the
     * {@code , L<owner>;-><name>(...)} tail), as a dotted FQN. Emits one entry per invoke; the
     * caller de-dups via its IntBag. Mirrors XrefsRoutes' on-demand parse, but for the whole class.
     */
    private static void extractInvokeOwners(String smali, java.util.function.Consumer<String> sink) {
        int from = 0;
        int len = smali.length();
        while (true) {
            int idx = smali.indexOf("invoke", from);
            if (idx < 0) break;
            int lineEnd = smali.indexOf('\n', idx);
            if (lineEnd < 0) lineEnd = len;
            int owner = smali.indexOf(", L", idx);
            if (owner > 0 && owner < lineEnd) {
                int arrow = smali.indexOf(";->", owner);
                if (arrow > 0 && arrow < lineEnd) {
                    sink.accept(smali.substring(owner + 3, arrow).replace('/', '.'));
                }
            }
            from = lineEnd + 1;
        }
    }

    /**
     * Extract every {@code const-string}/{@code const-string/jumbo} operand from a class's
     * smali, feeding the escaped operand text (as it appears between the quotes) to {@code sink}.
     *
     * <p>Hand-written char scan, deliberately NOT a regex: Java's {@code Pattern} engine
     * recurses on quantified groups and throws {@link StackOverflowError} when a single
     * {@code const-string} holds a multi-KB embedded blob (base64 keys, dictionaries, etc.),
     * which is exactly what killed the regex-based build on ByteDance APKs.
     */
    private static void extractConstStrings(String smali, java.util.function.Consumer<String> sink) {
        int n = smali.length();
        int from = 0;
        while (true) {
            int idx = smali.indexOf("const-string", from);
            if (idx < 0) break;
            int q = smali.indexOf('"', idx + 12);
            if (q < 0) break;
            StringBuilder sb = new StringBuilder();
            int j = q + 1;
            boolean closed = false;
            while (j < n) {
                char ch = smali.charAt(j);
                if (ch == '\\') {
                    sb.append(ch);
                    if (j + 1 < n) {
                        sb.append(smali.charAt(j + 1));
                        j += 2;
                    } else {
                        j++;
                    }
                    continue;
                }
                if (ch == '"') {
                    closed = true;
                    break;
                }
                if (ch == '\n' || ch == '\r') break; // operands stay on one line
                sb.append(ch);
                j++;
            }
            if (closed) sink.accept(sb.toString());
            from = Math.max(j + 1, idx + 12);
        }
    }

    // -------------------- persistence --------------------

    /** Serialize to a Deflate-compressed binary file. */
    public void save(Path file) throws IOException {
        Map<String, int[]> p = postings;
        Map<String, int[]> pm = methodPostings;
        Map<String, int[]> pf = fieldPostings;
        Map<String, int[]> ph = typeHierarchy;
        String[] cn = calleeNames;
        int[][] fwd = forwardCalls;
        int[][] rev = reverseCalls;
        String[] f = idToFqn;
        if (p == null || pm == null || pf == null || ph == null || f == null
                || cn == null || fwd == null || rev == null) throw new IOException("index not built");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(file.getParent());
        try (OutputStream fos = Files.newOutputStream(tmp);
             DeflaterOutputStream def = new DeflaterOutputStream(new BufferedOutputStream(fos),
                     new Deflater(Deflater.BEST_SPEED));
             DataOutputStream out = new DataOutputStream(def)) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeInt(f.length);
            for (String fqn : f) writeStr(out, fqn == null ? "" : fqn);
            writePostings(out, p);
            writePostings(out, pm);
            writePostings(out, pf);
            writePostings(out, ph);
            out.writeInt(cn.length);
            for (String s : cn) writeStr(out, s == null ? "" : s);
            writeGraph(out, fwd);
            writeGraph(out, rev);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writePostings(DataOutputStream out, Map<String, int[]> p) throws IOException {
        out.writeInt(p.size());
        for (Map.Entry<String, int[]> e : p.entrySet()) {
            writeStr(out, e.getKey());
            int[] ids = e.getValue();
            out.writeInt(ids.length);
            for (int id : ids) out.writeInt(id);
        }
    }

    private static Map<String, int[]> readPostings(DataInputStream in, int classCount) throws IOException {
        int m = in.readInt();
        if (m < 0 || m > 50_000_000) throw new IOException("bad postings count " + m);
        Map<String, int[]> p = new ConcurrentHashMap<>(Math.max(16, m * 2));
        for (int i = 0; i < m; i++) {
            String key = readStr(in);
            int len = in.readInt();
            if (len < 0 || len > classCount) throw new IOException("bad postings len " + len);
            int[] ids = new int[len];
            for (int j = 0; j < len; j++) ids[j] = in.readInt();
            p.put(key, ids);
        }
        return p;
    }

    /** Write an int[][] adjacency graph: row count, then per row (len, ints). */
    private static void writeGraph(DataOutputStream out, int[][] g) throws IOException {
        out.writeInt(g.length);
        for (int[] row : g) {
            out.writeInt(row.length);
            for (int v : row) out.writeInt(v);
        }
    }

    private static int[][] readGraph(DataInputStream in, int maxRow) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 60_000_000) throw new IOException("bad graph size " + n);
        int[][] g = new int[n][];
        for (int i = 0; i < n; i++) {
            int len = in.readInt();
            if (len < 0 || len > maxRow) throw new IOException("bad graph row " + len);
            int[] row = new int[len];
            for (int j = 0; j < len; j++) row[j] = in.readInt();
            g[i] = row;
        }
        return g;
    }

    /** Load from a file written by {@link #save}. Returns false (and leaves status ABSENT) on any mismatch. */
    public boolean load(Path file) {
        if (!Files.isReadable(file)) return false;
        try (InputStream fis = Files.newInputStream(file);
             InflaterInputStream inf = new InflaterInputStream(new BufferedInputStream(fis));
             DataInputStream in = new DataInputStream(inf)) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) return false;
            int n = in.readInt();
            if (n < 0 || n > 5_000_000) return false;
            String[] f = new String[n];
            for (int i = 0; i < n; i++) f[i] = readStr(in);
            Map<String, int[]> p = readPostings(in, n);
            Map<String, int[]> pm = readPostings(in, n);
            Map<String, int[]> pf = readPostings(in, n);
            Map<String, int[]> ph = readPostings(in, n);
            int cnLen = in.readInt();
            if (cnLen < 0 || cnLen > 60_000_000) return false;
            String[] cn = new String[cnLen];
            for (int i = 0; i < cnLen; i++) cn[i] = readStr(in);
            int[][] fwd = readGraph(in, cnLen); // forward rows index calleeNames
            int[][] rev = readGraph(in, n);     // reverse rows index idToFqn
            this.idToFqn = f;
            this.postings = p;
            this.methodPostings = pm;
            this.fieldPostings = pf;
            this.typeHierarchy = ph;
            this.calleeNames = cn;
            this.forwardCalls = fwd;
            this.reverseCalls = rev;
            this.fqnToId = null;
            this.calleeFqnToId = null;
            this.totalClasses = n;
            this.builtClasses.set(n);
            this.status = Status.READY;
            logger.info("[string-index] loaded {} strings / {} methods / {} fields / {} supertypes / {} callees / {} classes from {}",
                    p.size(), pm.size(), pf.size(), ph.size(), cn.length, n, file.getFileName());
            return true;
        } catch (Throwable t) {
            logger.warn("[string-index] load failed ({}), will rebuild", t.toString());
            return false;
        }
    }

    /** Separate on-disk format for the code-identifier index (kept out of the main .stridx). */
    private static final int CODE_MAGIC = 0x4A584D43; // "JXMC"
    private static final int CODE_FORMAT_VERSION = 1;

    /** Serialize the code index to its own Deflate-compressed file. */
    public void saveCodeIndex(Path file) throws IOException {
        Map<String, int[]> ct = codeTokens;
        String[] f = codeIdToFqn;
        if (ct == null || f == null) throw new IOException("code index not built");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (OutputStream fos = Files.newOutputStream(tmp);
             DeflaterOutputStream def = new DeflaterOutputStream(new BufferedOutputStream(fos),
                     new Deflater(Deflater.BEST_SPEED));
             DataOutputStream out = new DataOutputStream(def)) {
            out.writeInt(CODE_MAGIC);
            out.writeInt(CODE_FORMAT_VERSION);
            out.writeInt(f.length);
            for (String fqn : f) writeStr(out, fqn == null ? "" : fqn);
            writePostings(out, ct);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Load a code index written by {@link #saveCodeIndex}. Returns false on absence/mismatch. */
    public boolean loadCodeIndex(Path file) {
        if (!Files.isReadable(file)) return false;
        try (InputStream fis = Files.newInputStream(file);
             InflaterInputStream inf = new InflaterInputStream(new BufferedInputStream(fis));
             DataInputStream in = new DataInputStream(inf)) {
            if (in.readInt() != CODE_MAGIC || in.readInt() != CODE_FORMAT_VERSION) return false;
            int n = in.readInt();
            if (n < 0 || n > 5_000_000) return false;
            String[] f = new String[n];
            for (int i = 0; i < n; i++) f[i] = readStr(in);
            Map<String, int[]> ct = readPostings(in, n);
            this.codeIdToFqn = f;
            this.codeTokens = ct;
            this.codeIndexedClasses = n;
            logger.info("[code-index] loaded {} tokens / {} main-package classes from {}",
                    ct.size(), n, file.getFileName());
            return true;
        } catch (Throwable t) {
            logger.warn("[code-index] load failed ({}), will rebuild", t.toString());
            return false;
        }
    }

    /** Length-prefixed UTF-8 (avoids DataOutput.writeUTF's 64KB ceiling for long const-strings). */
    private static void writeStr(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readStr(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > (1 << 24)) throw new IOException("bad string length " + len);
        byte[] b = new byte[len];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }
}
