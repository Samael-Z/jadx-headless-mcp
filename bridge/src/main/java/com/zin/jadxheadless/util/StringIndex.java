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
    private static final int FORMAT_VERSION = 1;

    public enum Status { ABSENT, BUILDING, READY, FAILED }

    private volatile Status status = Status.ABSENT;
    private volatile String detail = "";
    private final AtomicInteger builtClasses = new AtomicInteger();
    private volatile int totalClasses = 0;
    private volatile long buildMillis = 0;

    /** value -> sorted class ids (ids index into {@link #idToFqn}). */
    private volatile Map<String, int[]> postings;
    private volatile String[] idToFqn;

    public Status status() { return status; }

    public Map<String, Object> statusMap() {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("status", status.name().toLowerCase());
        m.put("built_classes", builtClasses.get());
        m.put("total_classes", totalClasses);
        m.put("distinct_strings", postings == null ? 0 : postings.size());
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
            final ConcurrentHashMap<String, Set<Integer>> tmp = new ConcurrentHashMap<>(1 << 20);
            IntStream.range(0, classes.size()).parallel().forEach(i -> {
                try {
                    JavaClass c = classes.get(i);
                    fqns[i] = c.getFullName();
                    String smali = smaliFn.apply(c);
                    if (smali != null && !smali.isEmpty()) {
                        final int id = i;
                        extractConstStrings(smali, val ->
                                tmp.computeIfAbsent(val, k -> ConcurrentHashMap.newKeySet()).add(id));
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
            Map<String, int[]> frozen = new ConcurrentHashMap<>(tmp.size() * 2);
            tmp.entrySet().parallelStream().forEach(e -> {
                int[] arr = e.getValue().stream().mapToInt(Integer::intValue).sorted().toArray();
                frozen.put(e.getKey(), arr);
            });
            this.idToFqn = fqns;
            this.postings = frozen;
            this.buildMillis = System.currentTimeMillis() - t0;
            status = Status.READY;
            logger.info("[string-index] READY: {} distinct strings over {} classes in {}ms",
                    frozen.size(), classes.size(), buildMillis);
        } catch (Throwable t) {
            detail = String.valueOf(t);
            status = Status.FAILED;
            logger.warn("[string-index] build FAILED: {}", t.toString());
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
        String[] f = idToFqn;
        if (p == null || f == null) throw new IOException("index not built");
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
            out.writeInt(p.size());
            for (Map.Entry<String, int[]> e : p.entrySet()) {
                writeStr(out, e.getKey());
                int[] ids = e.getValue();
                out.writeInt(ids.length);
                for (int id : ids) out.writeInt(id);
            }
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
            int m = in.readInt();
            if (m < 0 || m > 50_000_000) return false;
            Map<String, int[]> p = new ConcurrentHashMap<>(m * 2);
            for (int i = 0; i < m; i++) {
                String key = readStr(in);
                int len = in.readInt();
                if (len < 0 || len > n) return false;
                int[] ids = new int[len];
                for (int j = 0; j < len; j++) ids[j] = in.readInt();
                p.put(key, ids);
            }
            this.idToFqn = f;
            this.postings = p;
            this.totalClasses = n;
            this.builtClasses.set(n);
            this.status = Status.READY;
            logger.info("[string-index] loaded {} strings / {} classes from {}", m, n, file.getFileName());
            return true;
        } catch (Throwable t) {
            logger.warn("[string-index] load failed ({}), will rebuild", t.toString());
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
