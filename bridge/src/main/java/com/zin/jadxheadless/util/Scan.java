package com.zin.jadxheadless.util;

import jadx.api.JavaClass;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Bounded, parallel class-scan helper shared by the route layer.
 *
 * <p>{@link #boundedScan} is the fix for the "search hangs for minutes and monopolizes the single
 * bridge worker" class of problem: callers always get a response bounded by a wall-clock budget, a
 * hit cap, OR a heap-headroom guard. Used for every full-corpus live scan — the fallback path while
 * the pre-decompiled Java index is still warming up / only partially covers a huge APK, plus
 * regex/case-sensitive code searches the token index cannot serve directly.
 */
public final class Scan {

    private Scan() {}

    /** True when free heap headroom drops below ~1/8 of -Xmx — used to stop a live scan best-effort before OOM. */
    public static boolean lowHeap() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long used = rt.totalMemory() - rt.freeMemory();
        return (max - used) < (max / 8);
    }

    /** Outcome of a {@link #boundedScan}: collected hits plus progress/termination metadata. */
    public static final class ScanResult<T> {
        public final List<T> hits;
        public final boolean timedOut;
        public final boolean capped;
        public final boolean memStopped;
        public final int scanned;
        public final int total;

        public ScanResult(List<T> hits, boolean timedOut, boolean capped, boolean memStopped, int scanned, int total) {
            this.hits = hits;
            this.timedOut = timedOut;
            this.capped = capped;
            this.memStopped = memStopped;
            this.scanned = scanned;
            this.total = total;
        }
    }

    /**
     * Run {@code perClass} over every class in parallel, stopping early once any of:
     * (a) the wall-clock budget {@code timeoutMs} is exceeded, (b) {@code cap} non-null hits are
     * collected ({@code cap <= 0} disables the cap), or (c) free heap headroom drops too low
     * ({@link #lowHeap}). The last guard keeps a fallback live-decompile scan from OOM-killing the
     * bridge on a huge, only-partially-indexed APK under tight RAM.
     *
     * <p>When the scan stops early, {@code timedOut}/{@code capped}/{@code memStopped} say why and
     * {@code scanned} reports how many of {@code total} classes were examined.
     *
     * <p>Note: a parallel stream cannot be hard-cancelled, so after the stop flag trips the remaining
     * elements still get scheduled — but each becomes a cheap volatile-read no-op, so wall-time and
     * (because no further decompiles run) memory both stay bounded.
     */
    public static <T> ScanResult<T> boundedScan(
            List<JavaClass> all, long timeoutMs, int cap,
            Function<JavaClass, T> perClass) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        AtomicBoolean capped = new AtomicBoolean(false);
        AtomicBoolean memStopped = new AtomicBoolean(false);
        AtomicInteger scanned = new AtomicInteger();
        ConcurrentLinkedQueue<T> hits = new ConcurrentLinkedQueue<>();
        all.parallelStream().forEach(c -> {
            if (stop.get()) return;
            if (System.nanoTime() > deadline) {
                timedOut.set(true);
                stop.set(true);
                return;
            }
            int n = scanned.incrementAndGet();
            // Every 256 classes, check heap headroom; stop best-effort if low. This is the guard that
            // prevents the fallback live-decompile (for not-yet-indexed classes on a partially-covered
            // huge APK) from OOM-killing the bridge under tight RAM.
            if ((n & 0xFF) == 0 && lowHeap()) {
                memStopped.set(true);
                stop.set(true);
                return;
            }
            T r;
            try {
                r = perClass.apply(c);
            } catch (Throwable t) {
                r = null;
            }
            if (r != null) {
                hits.add(r);
                if (cap > 0 && hits.size() >= cap) {
                    capped.set(true);
                    stop.set(true);
                }
            }
        });
        return new ScanResult<>(new ArrayList<>(hits), timedOut.get(), capped.get(),
                memStopped.get(), scanned.get(), all.size());
    }
}
