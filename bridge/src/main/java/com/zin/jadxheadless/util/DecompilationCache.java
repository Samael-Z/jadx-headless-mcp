package com.zin.jadxheadless.util;

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
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Caches decompiled source per class in compressed form to keep heap usage down on large APKs.
 * Deflate level BEST_SPEED — we trade ~10-20% ratio for ~3x compression speed,
 * which matters because we may be caching tens of thousands of classes during a code search.
 */
public final class DecompilationCache {

    /** On-disk format for the persisted source cache (.jsrc). "JSRC". */
    private static final int MAGIC = 0x4A535243;
    private static final int VERSION = 1;

    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong compressedBytes = new AtomicLong();
    private final AtomicLong originalBytes = new AtomicLong();

    public String get(String key) {
        byte[] compressed = cache.get(key);
        if (compressed == null) {
            misses.incrementAndGet();
            return null;
        }
        hits.incrementAndGet();
        return decompress(compressed);
    }

    public void put(String key, String value) {
        if (key == null || value == null) {
            return;
        }
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        byte[] compressed = compress(raw);
        if (compressed == null) {
            return;
        }
        byte[] previous = cache.put(key, compressed);
        if (previous == null) {
            compressedBytes.addAndGet(compressed.length);
            originalBytes.addAndGet(raw.length);
        } else {
            compressedBytes.addAndGet(compressed.length - previous.length);
        }
    }

    public void clear() {
        cache.clear();
        hits.set(0);
        misses.set(0);
        compressedBytes.set(0);
        originalBytes.set(0);
    }

    /**
     * Serialize the compressed source cache to {@code file} (key + already-Deflated bytes), so a
     * restart can reload pre-decompiled source instead of re-decompiling on the first search.
     */
    public void save(Path file) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        try (OutputStream fos = Files.newOutputStream(tmp);
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(fos))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(cache.size());
            for (Map.Entry<String, byte[]> e : cache.entrySet()) {
                byte[] kb = e.getKey().getBytes(StandardCharsets.UTF_8);
                out.writeInt(kb.length);
                out.write(kb);
                byte[] v = e.getValue();
                out.writeInt(v.length);
                out.write(v);
            }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Reload a cache written by {@link #save}, populating the in-memory map. Returns false on mismatch. */
    public boolean load(Path file) {
        if (!Files.isReadable(file)) return false;
        try (InputStream fis = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(new BufferedInputStream(fis))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) return false;
            int n = in.readInt();
            if (n < 0 || n > 5_000_000) return false;
            long comp = 0;
            for (int i = 0; i < n; i++) {
                int kl = in.readInt();
                if (kl < 0 || kl > (1 << 24)) return false;
                byte[] kb = new byte[kl];
                in.readFully(kb);
                int vl = in.readInt();
                if (vl < 0 || vl > (1 << 28)) return false;
                byte[] v = new byte[vl];
                in.readFully(v);
                cache.put(new String(kb, StandardCharsets.UTF_8), v);
                comp += vl;
            }
            compressedBytes.addAndGet(comp);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public Map<String, Object> stats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        double hitRate = total == 0 ? 0.0 : (double) h / total;
        long orig = originalBytes.get();
        long comp = compressedBytes.get();
        double ratio = orig == 0 ? 0.0 : (double) comp / orig;
        Map<String, Object> out = new HashMap<>();
        out.put("hits", h);
        out.put("misses", m);
        out.put("hit_rate", hitRate);
        out.put("cached_classes", cache.size());
        out.put("compressed_mb", comp / 1024.0 / 1024.0);
        out.put("original_mb", orig / 1024.0 / 1024.0);
        out.put("compression_ratio", ratio);
        return out;
    }

    private static byte[] compress(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(data);
            deflater.finish();
            byte[] buf = new byte[Math.max(64, data.length / 4)];
            int total = 0;
            while (!deflater.finished()) {
                if (total == buf.length) {
                    byte[] grown = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, grown, 0, total);
                    buf = grown;
                }
                int n = deflater.deflate(buf, total, buf.length - total);
                total += n;
            }
            byte[] result = new byte[total];
            System.arraycopy(buf, 0, result, 0, total);
            return result;
        } finally {
            deflater.end();
        }
    }

    private static String decompress(byte[] data) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            byte[] buf = new byte[Math.max(256, data.length * 4)];
            int total = 0;
            while (!inflater.finished()) {
                if (total == buf.length) {
                    byte[] grown = new byte[buf.length * 2];
                    System.arraycopy(buf, 0, grown, 0, total);
                    buf = grown;
                }
                int n = inflater.inflate(buf, total, buf.length - total);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        return null;
                    }
                    break;
                }
                total += n;
            }
            return new String(buf, 0, total, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
