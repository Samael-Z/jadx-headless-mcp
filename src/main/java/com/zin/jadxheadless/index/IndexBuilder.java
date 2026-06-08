package com.zin.jadxheadless.index;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zin.jadxheadless.jadx.DiskCodeCache;
import com.zin.jadxheadless.jadx.SqliteUsageInfoCache;
import com.zin.jadxheadless.util.ManifestUtil;

import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.usage.IUsageInfoData;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;

/**
 * Builds the SQLite index in the background after load (5.5). Three phases over one dedicated writer
 * connection:
 * <ol>
 *   <li><b>structure</b> — register top-level classes (cls_idx ↔ dex id ↔ FQN); model-only, fast.</li>
 *   <li><b>usage export</b> — drain jadx's usage graph to edges via {@link SqliteExportVisitor} (D7).</li>
 *   <li><b>code + FTS</b> — bounded-parallel decompile of every class (main package first), feeding the
 *       text to the disk code cache (jadx) and the FTS5 trigram index + const-string table. This is the
 *       long pole; it streams (decompile → index → release) under a heap-headroom guard so peak stays
 *       &lt; 20 GB.</li>
 * </ol>
 * A complete build stamps {@code coverage_complete=true}; a later load of the same APK reuses it
 * wholesale (5.6) and skips straight to READY. Runs as a daemon thread; {@link #cancel()} stops it on
 * APK switch / shutdown.
 */
public final class IndexBuilder {

	private static final Logger LOG = LoggerFactory.getLogger(IndexBuilder.class);

	private final JadxDecompiler jadx;
	private final Db db;
	private final SymbolGraph graph;
	private final CodeSearchIndex csi;
	private final IndexStatus status;
	private final DiskCodeCache diskCache;
	private final SqliteUsageInfoCache usageCache;

	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private volatile Thread thread;

	public IndexBuilder(JadxDecompiler jadx, Db db, SymbolGraph graph, CodeSearchIndex csi,
			IndexStatus status, DiskCodeCache diskCache, SqliteUsageInfoCache usageCache) {
		this.jadx = jadx;
		this.db = db;
		this.graph = graph;
		this.csi = csi;
		this.status = status;
		this.diskCache = diskCache;
		this.usageCache = usageCache;
	}

	public void start() {
		Thread t = new Thread(this::run, "index-builder");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		this.thread = t;
		t.start();
	}

	public void cancel() {
		cancelled.set(true);
		Thread t = thread;
		if (t != null) {
			t.interrupt();
		}
	}

	private void run() {
		List<JavaClass> top = jadx.getClasses();
		if (db.isComplete()) {
			LOG.info("[index] reusing complete index from disk ({} top-level classes)", top.size());
			status.markReusedComplete(top.size());
			return;
		}
		long start = System.currentTimeMillis();
		status.begin(top.size());
		Connection w = null;
		try {
			w = db.openWriter();

			boolean resume = db.graphExported();
			status.setResumed(resume);

			if (!resume) {
				// ---- phase 1: structure (classes table + class symbols) ----
				graph.beginWrite(w);
				for (JavaClass jc : top) {
					if (cancelled.get()) {
						throw new InterruptedException("cancelled");
					}
					int clsIdx = diskCache.clsId(jc.getRawName());
					if (clsIdx < 0) {
						continue;
					}
					try {
						graph.addClass(clsIdx, jc.getClassNode());
					} catch (Throwable t) {
						LOG.debug("addClass {} skipped: {}", safeName(jc), t.toString());
					}
				}
				graph.commit();

				// ---- phase 2: usage export (the out-of-heap xref graph, D7) ----
				IUsageInfoData usageData = usageCache.data();
				if (usageData != null) {
					usageData.visitUsageData(new SqliteExportVisitor(graph, status));
				} else {
					LOG.warn("[index] no usage data captured; xref edges will be empty");
				}
				graph.endWrite();
				setMeta(w, "graph_done", "true");
				LOG.info("[index] structure+usage done in {}ms ({} symbols)",
						System.currentTimeMillis() - start, graph.symbolCount());
			} else {
				LOG.info("[index] RESUME: reusing exported symbol graph from a prior run; extending code coverage");
			}

			// D7: drop the in-heap usage now that the graph is in SQLite — frees several GB. The dominant
			// cost is the per-node use-in lists populated by apply(); clear those on every node, then drop
			// the cache ref. xref is served from SQLite, so this is safe and lowers the resident baseline.
			usageCache.releaseData();
			releaseHeapUseIn();

			// ---- phase 3: decompile + FTS (long pole; heap-bounded, resumable across loads) ----
			csi.beginWrite(w);
			csi.beginTx();
			java.util.Set<Integer> alreadyIndexed = csi.indexedRowids();
			if (!alreadyIndexed.isEmpty()) {
				status.setIndexed(alreadyIndexed.size());
				LOG.info("[index] {} classes already in FTS from a prior run; skipping them", alreadyIndexed.size());
			}
			boolean memStopped = decompilePass(top, alreadyIndexed);
			csi.endWrite();

			boolean complete = !cancelled.get() && !memStopped;
			setMeta(w, "coverage_complete", complete ? "true" : "false");
			setMeta(w, "schema_version", Integer.toString(Db.SCHEMA_VERSION));
			// A heap-bounded partial build is still READY (the indexed subset is fully searchable);
			// reloading the same APK resumes and extends coverage (5.5/5.6).
			status.finish(!cancelled.get(), complete,
					complete ? "index complete in " + (System.currentTimeMillis() - start) + "ms"
							: "PARTIAL " + status.indexed() + "/" + top.size()
									+ " classes (heap-bounded); searchable subset ready — reload the APK to extend coverage");
			LOG.info("[index] {} ({}/{} classes) in {}ms",
					complete ? "COMPLETE" : "PARTIAL", status.indexed(), top.size(),
					System.currentTimeMillis() - start);
		} catch (InterruptedException ie) {
			LOG.info("[index] build cancelled");
			status.fail("cancelled");
		} catch (Throwable t) {
			LOG.error("[index] build failed", t);
			status.fail(t.toString());
		} finally {
			if (w != null) {
				try {
					w.close();
				} catch (Exception ignored) {
					// ignore
				}
			}
		}
	}

	/**
	 * Bounded-parallel decompile of all top-level classes, main package first. Each class's source is
	 * produced by {@code jc.getCode()} (which jadx writes into the disk code cache) and fed to the FTS
	 * index. Parallelism is capped at half the cores — full parallelism OOM-killed Douyin in the spike.
	 */
	private boolean decompilePass(List<JavaClass> top, java.util.Set<Integer> done) throws Exception {
		String pkg = ManifestUtil.packageName(jadx);
		String prefix = (pkg == null || pkg.isEmpty()) ? null : pkg + ".";
		List<JavaClass> main = new ArrayList<>();
		List<JavaClass> rest = new ArrayList<>();
		for (JavaClass jc : top) {
			String fqn = safeName(jc);
			if (prefix != null && (fqn.startsWith(prefix) || fqn.equals(pkg))) {
				main.add(jc);
			} else {
				rest.add(jc);
			}
		}
		int par = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
		ForkJoinPool pool = new ForkJoinPool(par);
		AtomicInteger processed = new AtomicInteger();
		AtomicBoolean memStop = new AtomicBoolean(false);
		try {
			runPass(pool, main, processed, memStop, done);
			if (!cancelled.get() && !memStop.get()) {
				runPass(pool, rest, processed, memStop, done);
			}
		} finally {
			pool.shutdown();
		}
		if (memStop.get()) {
			LOG.warn("[index] decompile pass stopped early on low heap at {} classes", status.indexed());
		}
		return memStop.get();
	}

	private void runPass(ForkJoinPool pool, List<JavaClass> classes, AtomicInteger processed,
			AtomicBoolean memStop, java.util.Set<Integer> done) throws Exception {
		if (classes.isEmpty()) {
			return;
		}
		pool.submit(() -> classes.parallelStream().forEach(jc -> {
			if (cancelled.get() || memStop.get()) {
				return;
			}
			int n = processed.get();
			if ((n & 0x3FF) == 0 && lowHeap()) {
				memStop.set(true);
				return;
			}
			int clsIdx = diskCache.clsId(jc.getRawName());
			if (clsIdx < 0) {
				return;
			}
			if (done.contains(clsIdx)) {
				return; // already indexed in a prior run (resume)
			}
			String code = null;
			try {
				code = jc.getCode();
			} catch (Throwable t) {
				LOG.debug("decompile {} failed: {}", safeName(jc), t.toString());
			}
			if (code != null && !code.isEmpty()) {
				int strs = csi.indexCode(clsIdx, code);
				if (strs > 0) {
					status.addStrings(strs);
				}
			}
			// Free the class's decompiled IR once indexed — keeps the pass's heap bounded over 319k
			// classes (jadx lazy decompile otherwise accumulates per-class state). Use ClassNode.unload()
			// (NOT JavaClass.unload(), which also evicts the disk code cache via unloadFromCache); the
			// text we just wrote must stay on disk for cross-restart reuse + ripgrep. Re-decompiles on demand.
			try {
				ClassNode cn = jc.getClassNode();
				if (cn != null) {
					cn.unload();
				}
			} catch (Throwable ignored) {
				// best effort
			}
			status.incIndexed();
			int pn = processed.incrementAndGet();
			if ((pn % 5000) == 0) {
				try {
					csi.commit();
				} catch (Exception e) {
					LOG.warn("[index] periodic commit failed: {}", e.toString());
				}
				LOG.info("[index] decompiled+indexed {} new classes (total {})", pn, status.indexed());
			}
		})).get();
	}

	/**
	 * Clear the in-heap use-in lists on every node after the usage graph is in SQLite (D7 / task 2.4).
	 * These lists (who-calls-me / who-uses-me), populated by jadx's {@code apply()}, are the spike's
	 * ~4.6 GB on Douyin and the heap's biggest avoidable consumer. xref now answers from SQLite, and
	 * class merging already happened during load, so clearing them is safe and frees the headroom the
	 * decompile/FTS pass needs to cover all classes within 20 GB.
	 */
	private void releaseHeapUseIn() {
		long n = 0;
		List<ClassNode> empty = java.util.Collections.emptyList();
		List<MethodNode> emptyM = java.util.Collections.emptyList();
		for (JavaClass jc : jadx.getClassesWithInners()) {
			try {
				ClassNode c = jc.getClassNode();
				if (c == null) {
					continue;
				}
				c.setUseIn(empty);
				c.setUseInMth(emptyM);
				for (MethodNode m : c.getMethods()) {
					m.setUseIn(emptyM);
				}
				for (FieldNode f : c.getFields()) {
					f.setUseIn(emptyM);
				}
				n++;
			} catch (Throwable ignored) {
				// best effort
			}
		}
		LOG.info("[index] cleared in-heap use-in on {} classes (xref served from SQLite)", n);
	}

	/** Stop the pass best-effort before OOM when free heap headroom drops below ~1/8 of -Xmx. */
	private static boolean lowHeap() {
		Runtime rt = Runtime.getRuntime();
		long max = rt.maxMemory();
		long used = rt.totalMemory() - rt.freeMemory();
		return (max - used) < (max / 8);
	}

	private static void setMeta(Connection w, String k, String v) {
		try (PreparedStatement ps = w.prepareStatement(
				"INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
			ps.setString(1, k);
			ps.setString(2, v);
			ps.executeUpdate();
		} catch (Exception e) {
			LOG.warn("setMeta({}) on writer failed: {}", k, e.toString());
		}
	}

	private static String safeName(JavaClass jc) {
		try {
			return jc.getFullName();
		} catch (Throwable t) {
			return "?";
		}
	}
}
