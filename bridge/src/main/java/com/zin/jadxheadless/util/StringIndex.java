package com.zin.jadxheadless.util;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Persistent inverted index over <b>decompiled Java source</b>, built incrementally during the
 * background pre-decompile pass kicked off by {@code load_apk}.
 *
 * <p>Maps each Java identifier token (length &gt;= 3) found in a class's decompiled source — which
 * also captures identifier-ish fragments INSIDE string literals (URLs, keys, dictionary words) — to
 * the ids of classes whose source contains it. Callers use {@link #lookupCodeContains} to NARROW to
 * candidate classes, then full-text-confirm against the cached source, so punctuation queries like
 * {@code Cipher.getInstance("AES")} work (the confirm step does the exact/regex match).
 *
 * <p><b>Space-for-time.</b> Built once under a wall-clock budget and persisted to a {@code .jidx}
 * file next to the APK, reloaded in seconds on a later run. Keyed by APK path + size (handled by the
 * caller's cache-file naming); a changed APK rebuilds.
 *
 * <p><b>Best-effort coverage.</b> On a huge APK the pre-decompile may stop early (time budget or
 * memory guardrail); {@link #statusMap} reports {@code coverage_complete=false}. Classes not yet
 * indexed are served by a bounded live scan in the route layer.
 */
public final class StringIndex {

    private static final Logger logger = LoggerFactory.getLogger(StringIndex.class);

    /** Bump when the on-disk format changes so stale files are ignored. "JXM5" = Java-source index. */
    private static final int MAGIC = 0x4A584D35;
    private static final int FORMAT_VERSION = 1;

    public enum Status { ABSENT, BUILDING, READY, FAILED }

    private volatile Status status = Status.ABSENT;
    private volatile String detail = "";
    private final AtomicInteger indexedClasses = new AtomicInteger();
    private volatile int totalClasses = 0;
    private volatile long buildMillis = 0;
    private volatile long budgetMs = 0;
    private volatile boolean budgetExhausted = false;
    private volatile boolean memStopped = false;
    private volatile boolean coverageComplete = false;
    private volatile long buildStartMs = 0;

    /** identifier token -> sorted class ids (ids index into {@link #idToFqn}). */
    private volatile Map<String, int[]> codeTokens;
    /** id -> FQN of the classes actually decompiled + indexed. */
    private volatile String[] idToFqn;

    // ---- incremental build scratch state (live only while BUILDING) ----
    private volatile ConcurrentHashMap<String, IntBag> buildTmp;
    private volatile ConcurrentHashMap<Integer, String> buildFqns;
    private volatile AtomicInteger buildSeq;

    public Status status() { return status; }

    public Map<String, Object> statusMap() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("status", status.name().toLowerCase());
        m.put("decompiled_classes", indexedClasses.get());
        m.put("total_classes", totalClasses);
        m.put("distinct_tokens", codeTokens == null ? 0 : codeTokens.size());
        m.put("build_ms", buildMillis);
        m.put("budget_ms", budgetMs);
        m.put("budget_exhausted", budgetExhausted);
        m.put("mem_stopped", memStopped);
        m.put("coverage_complete", coverageComplete);
        m.put("ready", status == Status.READY);
        if (!detail.isEmpty()) m.put("detail", detail);
        return m;
    }

    // -------------------- incremental build --------------------

    /**
     * Start an incremental build. {@code total} is the number of classes the pre-decompile intends to
     * cover (for progress reporting); {@code budgetMs} is the wall-clock budget (for status only).
     */
    public synchronized void beginBuild(int total, long budgetMs) {
        this.status = Status.BUILDING;
        this.totalClasses = total;
        this.budgetMs = budgetMs;
        this.budgetExhausted = false;
        this.memStopped = false;
        this.coverageComplete = false;
        this.indexedClasses.set(0);
        this.buildTmp = new ConcurrentHashMap<>(1 << 19);
        this.buildFqns = new ConcurrentHashMap<>(1 << 17);
        this.buildSeq = new AtomicInteger();
        this.buildStartMs = System.currentTimeMillis();
    }

    /**
     * Index one class's decompiled source. Thread-safe — called from the parallel pre-decompile pass.
     * {@code code} may be null/empty (a hardened class that didn't decompile); the class still gets an
     * id so its FQN is recorded, just with no tokens.
     */
    public void indexClass(String fqn, String code) {
        ConcurrentHashMap<String, IntBag> tmp = buildTmp;
        ConcurrentHashMap<Integer, String> fq = buildFqns;
        AtomicInteger seq = buildSeq;
        if (tmp == null || fq == null || seq == null) return; // not building
        int id = seq.getAndIncrement();
        fq.put(id, fqn == null ? "?" : fqn);
        if (code != null && !code.isEmpty()) {
            extractTokens(code, tok -> tmp.computeIfAbsent(tok, k -> new IntBag()).add(id));
        }
        indexedClasses.incrementAndGet();
    }

    /** Finish the incremental build: freeze postings, publish them, record why it stopped. */
    public synchronized void finishBuild(boolean budgetExhausted, boolean memStopped, boolean coverageComplete) {
        ConcurrentHashMap<String, IntBag> tmp = buildTmp;
        ConcurrentHashMap<Integer, String> fq = buildFqns;
        AtomicInteger seq = buildSeq;
        if (tmp == null || fq == null || seq == null) { status = Status.FAILED; return; }
        try {
            int n = seq.get();
            String[] fqns = new String[n];
            for (Map.Entry<Integer, String> e : fq.entrySet()) {
                int id = e.getKey();
                if (id >= 0 && id < n) fqns[id] = e.getValue();
            }
            this.idToFqn = fqns;
            this.codeTokens = freeze(tmp);
            this.budgetExhausted = budgetExhausted;
            this.memStopped = memStopped;
            this.coverageComplete = coverageComplete;
            this.buildMillis = System.currentTimeMillis() - buildStartMs;
            this.status = Status.READY;
            logger.info("[java-index] READY: {} tokens over {} classes in {}ms (budgetExhausted={}, memStopped={}, complete={})",
                    codeTokens.size(), n, buildMillis, budgetExhausted, memStopped, coverageComplete);
        } catch (Throwable t) {
            detail = String.valueOf(t);
            status = Status.FAILED;
            logger.warn("[java-index] finishBuild FAILED: {}", t.toString());
        } finally {
            this.buildTmp = null;
            this.buildFqns = null;
            this.buildSeq = null;
        }
    }

    public boolean codeIndexReady() {
        return codeTokens != null && idToFqn != null;
    }

    /** FQNs of all indexed classes (for "already decompiled?" checks and bounded-fallback set math). */
    public java.util.Set<String> indexedFqns() {
        String[] f = idToFqn;
        if (f == null) return java.util.Collections.emptySet();
        java.util.HashSet<String> s = new java.util.HashSet<>(Math.max(16, f.length * 2));
        for (String x : f) if (x != null && !x.equals("?")) s.add(x);
        return s;
    }

    // -------------------- lookup --------------------

    /**
     * Candidate classes whose indexed tokens contain {@code term} (case-insensitive substring), up to
     * {@code cap} (&lt;=0 = no cap). Returns {@code null} if the index isn't built (caller falls back to
     * a live scan). Token-level only: callers full-text-confirm the returned candidates.
     */
    public List<String> lookupCodeContains(String term, String packageFilter, int cap, int[] outScannedKeys) {
        Map<String, int[]> ct = codeTokens;
        String[] f = idToFqn;
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

    /** Longest Java identifier token (length &gt;= 3) in {@code query}, or null if none — used to pick a narrowing key. */
    public static String longestToken(String query) {
        if (query == null) return null;
        int n = query.length();
        int i = 0;
        String best = null;
        while (i < n) {
            char c = query.charAt(i);
            if (Character.isJavaIdentifierStart(c)) {
                int s = i; i++;
                while (i < n && Character.isJavaIdentifierPart(query.charAt(i))) i++;
                if (i - s >= 3 && (best == null || (i - s) > best.length())) best = query.substring(s, i);
            } else {
                i++;
            }
        }
        return best;
    }

    /** Extract Java identifier tokens (length &gt;= 3) from decompiled code; the caller's IntBag de-dups.
     *  Scans the whole text including string-literal contents, so identifiers embedded in literals
     *  (e.g. {@code https}, {@code api} inside {@code "https://api..."}) are indexed for narrowing. */
    private static void extractTokens(String code, java.util.function.Consumer<String> sink) {
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

    // -------------------- shared build helpers --------------------

    /** Freeze a building map (value -> IntBag) into value -> sorted-unique id[] for compact lookup. */
    private static Map<String, int[]> freeze(ConcurrentHashMap<String, IntBag> tmp) {
        Map<String, int[]> frozen = new ConcurrentHashMap<>(Math.max(16, tmp.size() * 2));
        tmp.entrySet().parallelStream().forEach(e -> frozen.put(e.getKey(), e.getValue().toSortedUnique()));
        return frozen;
    }

    /**
     * Append-only, synchronized primitive-int bag used during the build. Far cheaper than
     * {@code Set<Integer>} (no boxing, no per-element node). Duplicates removed at freeze time.
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

    // -------------------- persistence (.jidx) --------------------

    /** Serialize the Java-source index to a Deflate-compressed {@code .jidx} file. */
    public void save(Path file) throws IOException {
        Map<String, int[]> ct = codeTokens;
        String[] f = idToFqn;
        if (ct == null || f == null) throw new IOException("index not built");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (OutputStream fos = Files.newOutputStream(tmp);
             DeflaterOutputStream def = new DeflaterOutputStream(new BufferedOutputStream(fos),
                     new Deflater(Deflater.BEST_SPEED));
             DataOutputStream out = new DataOutputStream(def)) {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeBoolean(coverageComplete);
            out.writeInt(f.length);
            for (String fqn : f) writeStr(out, fqn == null ? "" : fqn);
            writePostings(out, ct);
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Load from a file written by {@link #save}. Returns false (status stays ABSENT) on any mismatch. */
    public boolean load(Path file) {
        if (!Files.isReadable(file)) return false;
        try (InputStream fis = Files.newInputStream(file);
             InflaterInputStream inf = new InflaterInputStream(new BufferedInputStream(fis));
             DataInputStream in = new DataInputStream(inf)) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) return false;
            boolean complete = in.readBoolean();
            int n = in.readInt();
            if (n < 0 || n > 5_000_000) return false;
            String[] f = new String[n];
            for (int i = 0; i < n; i++) f[i] = readStr(in);
            Map<String, int[]> ct = readPostings(in, n);
            this.idToFqn = f;
            this.codeTokens = ct;
            this.indexedClasses.set(n);
            this.totalClasses = n;
            this.coverageComplete = complete;
            this.buildMillis = 0;
            this.status = Status.READY;
            logger.info("[java-index] loaded {} tokens / {} classes from {} (complete={})",
                    ct.size(), n, file.getFileName(), complete);
            return true;
        } catch (Throwable t) {
            logger.warn("[java-index] load failed ({}), will rebuild", t.toString());
            return false;
        }
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

    /** Length-prefixed UTF-8 (avoids DataOutput.writeUTF's 64KB ceiling for long tokens). */
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
