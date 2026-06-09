package com.zin.jadxheadless.index;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
 * Builds the SQLite index in the background after load (5.5). Three phases:
 * <ol>
 *   <li><b>structure</b> — register top-level classes (cls_idx ↔ dex id ↔ FQN); model-only, fast.</li>
 *   <li><b>usage export</b> — drain jadx's usage graph to edges via {@link SqliteExportVisitor} (D7),
 *       then build the graph indexes once over the bulk-loaded rows ({@link Db#createGraphIndexes}, 3.1).</li>
 *   <li><b>code + FTS</b> — bounded-parallel decompile of every in-scope class, ordered by
 *       <b>analysis-value tier</b> (entry classes → main package → rest) so the highest-value code is
 *       searchable first (progressive-index-availability D2). The decompile threads only {@code getCode()}
 *       + harvest string literals and hand the text to a bounded queue (D1/D3); {@code M} shard-writer
 *       threads do the FTS5 trigram tokenization + insert in parallel ({@link FtsShards}, D2). Releases per
 *       chunk under a heap-headroom guard so peak stays &lt; 20 GB.</li>
 * </ol>
 *
 * <p>Each tier flips a readiness flag ({@code xref_ready} after the usage graph; {@code entry_ready} /
 * {@code main_ready} as those tiers decompile) surfaced by {@code index_status}, and a reload resumes a
 * heap-bounded partial build — extending coverage and restoring tier flags from on-disk meta (2.3/6.3).
 * A complete build stamps {@code coverage_complete=true}; a later load of the same APK reuses it
 * wholesale (5.6) and skips straight to READY. The structure+usage phase writes the MAIN DB while the
 * code phase writes the SHARD DBs (different files/connections) — so with {@code JADX_INDEX_OVERLAP=1}
 * they can run concurrently (3.2). Runs as a daemon thread; {@link #cancel()} stops it on APK switch /
 * shutdown.
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
	private final AnalysisScope scope;

	private final AtomicBoolean cancelled = new AtomicBoolean(false);
	private final AtomicBoolean memStop = new AtomicBoolean(false);
	private volatile Thread thread;

	public IndexBuilder(JadxDecompiler jadx, Db db, SymbolGraph graph, CodeSearchIndex csi,
			IndexStatus status, DiskCodeCache diskCache, SqliteUsageInfoCache usageCache, AnalysisScope scope) {
		this.jadx = jadx;
		this.db = db;
		this.graph = graph;
		this.csi = csi;
		this.status = status;
		this.diskCache = diskCache;
		this.usageCache = usageCache;
		this.scope = scope;
	}

	// ---- phase-3 tunables (env-overridable so the section-5 sweep can tune without recompiling) ----

	/** Decompile parallelism. Default = all cores (the per-class const-storage write is serialized between
	 *  chunks, so the old cores/2 heap throttle is no longer needed). */
	private static int indexThreads() {
		return envInt("JADX_INDEX_THREADS", Math.max(2, Runtime.getRuntime().availableProcessors()));
	}

	/** Classes per decompile→release chunk. Parallel-decompile a chunk, then release it serially. */
	private static int chunkSize() {
		return envInt("JADX_INDEX_CHUNK", 4000);
	}

	/** Global backpressure cap on queued decompiled source bytes (D1). Default 64 MiB. */
	private static long queueBudgetBytes() {
		return (long) envInt("JADX_INDEX_QUEUE_MB", 64) * 1024L * 1024L;
	}

	private static int envInt(String key, int def) {
		String v = System.getenv(key);
		if (v != null) {
			try {
				int n = Integer.parseInt(v.trim());
				if (n > 0) {
					return n;
				}
			} catch (NumberFormatException ignored) {
				// keep default
			}
		}
		return def;
	}

	private static boolean envBool(String key) {
		String v = System.getenv(key);
		return v != null && (v.equals("1") || v.equalsIgnoreCase("true") || v.equalsIgnoreCase("yes"));
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
		try {
			csi.abortBuild(); // unblock any decompile thread parked on the queue byte budget
		} catch (Throwable ignored) {
			// ignore
		}
		Thread t = thread;
		if (t != null) {
			t.interrupt();
		}
	}

	private void run() {
		List<JavaClass> top = jadx.getClasses();
		// Reuse a complete on-disk index only when it was built for the SAME scope; a broader request
		// (e.g. now --index-all over a prior selective build) must fall through and extend coverage.
		boolean scopeMatches = scope.describe().equals(db.getMeta("index_scope"));
		if (db.isComplete() && scopeMatches) {
			LOG.info("[index] reusing complete index from disk ({} top-level classes, scope={}, shards={})",
					top.size(), scope.describe(), csi.shardCount());
			status.markReusedComplete(top.size());
			// Backfill counts from the on-disk index (the build path accumulates these incrementally).
			status.addSymbols((int) graph.countSymbols());
			status.addEdges(graph.countEdges());
			status.addStrings((int) csi.countConstStrings());
			return;
		}
		long start = System.currentTimeMillis();
		status.begin(top.size());
		Connection w = null;
		try {
			w = db.openWriter();
			boolean resume = db.graphExported();
			status.setResumed(resume);
			// Seed tier-readiness from on-disk meta so a reload reflects what a prior session already
			// completed BEFORE the builder re-derives it (2.3/6.3): graph_done→xref, entry_done, main_done.
			seedTierFlagsFromMeta();

			boolean overlap = !resume && envBool("JADX_INDEX_OVERLAP");

			boolean memStopped;
			if (overlap) {
				memStopped = runOverlapped(w, top);
			} else {
				if (!resume) {
					runStructureUsage(w, top, start);
				} else {
					LOG.info("[index] RESUME: reusing exported symbol graph from a prior run; extending code coverage");
				}
				// D7: drop the in-heap usage now that the graph is in SQLite — frees ~4.6 GB BEFORE the
				// deferred index build sorts 29.5M edges, so the two big memory consumers don't collide.
				usageCache.releaseData();
				releaseHeapUseIn();
				Db.createGraphIndexes(w); // 3.1: build graph indexes once, after use-in is freed (idempotent on resume)
				// Tier-0 done: the symbol graph is queryable (D2/D3) — get_xrefs_*/call-graph/subclasses
				// are now available, before any class is decompiled.
				status.setXrefReady(true);
				memStopped = runCodePhase(w, top);
			}

			// Persist tier-completion flags (entry_done/main_done) so a reload restores readiness from
			// disk. Runs on the builder thread after the code phase (overlap has joined its code thread),
			// so writing the MAIN-DB writer connection here is single-threaded and safe (2.3/6.3).
			persistTierMeta(w);

			boolean complete = !cancelled.get() && !memStopped;
			setMeta(w, "coverage_complete", complete ? "true" : "false");
			setMeta(w, "schema_version", Integer.toString(Db.SCHEMA_VERSION));
			setMeta(w, "fts_shards", Integer.toString(csi.shardCount()));
			setMeta(w, "index_scope", scope.describe());
			// A heap-bounded partial build is still READY (the indexed subset is fully searchable);
			// reloading the same APK resumes and extends coverage (5.5/5.6).
			status.finish(!cancelled.get(), complete,
					complete ? "index complete in " + (System.currentTimeMillis() - start) + "ms"
							: "PARTIAL " + status.decompiled() + "/" + top.size()
									+ " classes (heap-bounded); searchable subset ready — reload the APK to extend coverage");
			LOG.info("[index] {} ({}/{} classes) in {}ms",
					complete ? "COMPLETE" : "PARTIAL", status.decompiled(), top.size(),
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

	// ---- phase 1+2: structure + usage export (writes the MAIN DB) ----

	private void runStructureUsage(Connection w, List<JavaClass> top, long start) throws Exception {
		graph.beginWrite(w);
		registerClasses(top);
		graph.commit();
		exportUsage();
		graph.endWrite();
		setMeta(w, "graph_done", "true");
		LOG.info("[index] structure+usage done in {}ms ({} symbols)",
				System.currentTimeMillis() - start, graph.symbolCount());
	}

	private void registerClasses(List<JavaClass> top) throws Exception {
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
	}

	private void exportUsage() {
		IUsageInfoData usageData = usageCache.data();
		if (usageData != null) {
			usageData.visitUsageData(new SqliteExportVisitor(graph, status));
		} else {
			LOG.warn("[index] no usage data captured; xref edges will be empty");
		}
	}

	// ---- phase 3: decompile + sharded FTS (writes the SHARD DBs) ----

	private boolean runCodePhase(Connection w, List<JavaClass> top) throws Exception {
		csi.beginBuild(queueBudgetBytes());
		try {
			java.util.Set<Integer> alreadyIndexed = csi.indexedRowids();
			if (!alreadyIndexed.isEmpty()) {
				// Already in FTS from a prior run ⇒ both decompiled (.java on disk) and indexed.
				status.setDecompiled(alreadyIndexed.size());
				status.setIndexedClasses(alreadyIndexed.size());
				LOG.info("[index] {} classes already in FTS from a prior run; skipping them", alreadyIndexed.size());
			}
			return decompilePass(top, alreadyIndexed);
		} finally {
			csi.finishBuild(); // drain queues, rebuild each shard's string_fts, commit + close writers
		}
	}

	// ---- 3.2 overlap: phase-2 (usage → MAIN DB) concurrent with phase-3 (decompile+FTS → SHARD DBs) ----

	/**
	 * Overlapped build (env {@code JADX_INDEX_OVERLAP=1}). Class registration runs first (the only true
	 * ordering dependency — query-time FQN resolution needs the {@code classes} table). Then usage export
	 * (MAIN DB) and the decompile/FTS code phase (SHARD DBs) run concurrently: they touch different
	 * connections/files, so there is no writer conflict. The in-heap use-in lists must survive until usage
	 * export reads them, so they are cleared only after BOTH finish — which is why this trades higher peak
	 * heap for wall-clock and is opt-in (the default linear path frees them before the code phase).
	 */
	private boolean runOverlapped(Connection w, List<JavaClass> top) throws Exception {
		long start = System.currentTimeMillis();
		graph.beginWrite(w);
		registerClasses(top);
		graph.commit();

		AtomicReference<Throwable> codeErr = new AtomicReference<>();
		AtomicBoolean codeMemStop = new AtomicBoolean(false);
		Thread codeThread = new Thread(() -> {
			try {
				codeMemStop.set(runCodePhase(w, top));
			} catch (Throwable t) {
				codeErr.set(t);
			}
		}, "index-code-phase");
		codeThread.start();

		// main thread continues with usage export (writes MAIN DB) in parallel
		exportUsage();
		graph.endWrite();
		setMeta(w, "graph_done", "true");
		LOG.info("[index] (overlap) structure+usage done in {}ms ({} symbols); awaiting code phase",
				System.currentTimeMillis() - start, graph.symbolCount());

		codeThread.join();
		Throwable err = codeErr.get();
		if (err != null) {
			throw (err instanceof Exception) ? (Exception) err : new RuntimeException(err);
		}
		usageCache.releaseData();
		releaseHeapUseIn();
		Db.createGraphIndexes(w); // after use-in is freed, so the index-build sort has headroom
		status.setXrefReady(true); // overlap: graph queryable only now (it ran concurrent with the code phase)
		return codeMemStop.get();
	}

	/**
	 * Decompile + index the in-scope classes (layer 2: skip T4 stdlib unless {@code --index-all}) by
	 * <b>analysis-value tier</b> (progressive-index-availability D2): Tier-1 <b>entry</b> classes
	 * (manifest components) → Tier-2 <b>main</b> package (T1 app + same-origin) → Tier-3 <b>rest</b>
	 * (T2 obfuscated + T3 third-party). Each tier flips its {@code *_ready} flag the moment it is fully
	 * decompiled + flushed, so the highest-value code is searchable long before the build completes; a
	 * heap stop mid-tier leaves later flags false and a reload resumes them.
	 *
	 * <p>Within a tier, classes run in heap-bounded chunks: each chunk is decompiled in parallel
	 * (read-only on jadx's global state) and the text handed to the FTS shards via
	 * {@link CodeSearchIndex#enqueue} (shard-writer threads tokenize + insert). After the chunk's
	 * decompile barrier, classes are released SERIALLY ({@code unload()} + clear the global
	 * {@code ConstStorage}): {@code ConstStorage.classes} is a plain {@code HashMap} the parallel
	 * decompile reads via {@code getConstField}, so mutating it inside the stream would race. Release
	 * only needs the decompile barrier (not the queue drain): the queued units hold copies of the source
	 * text + literals, not the {@code ClassNode}.
	 */
	private boolean decompilePass(List<JavaClass> top, java.util.Set<Integer> done) throws Exception {
		memStop.set(false);
		java.util.Set<String> entryFqns = ManifestUtil.entryClasses(jadx);
		String pkg = ManifestUtil.packageName(jadx);
		String prefix = (pkg == null || pkg.isEmpty()) ? null : pkg + ".";
		List<JavaClass> entry = new ArrayList<>();
		List<JavaClass> main = new ArrayList<>();
		List<JavaClass> rest = new ArrayList<>();
		int inScope = 0;
		int skipped = 0;
		for (JavaClass jc : top) {
			String fqn = safeName(jc);
			if (!scope.shouldIndex(fqn)) {
				skipped++;
				continue; // out of analysis-value scope (e.g. T4 stdlib) — not decompiled/indexed
			}
			inScope++;
			int clsIdx = diskCache.clsId(jc.getRawName());
			if (clsIdx < 0 || done.contains(clsIdx)) {
				continue; // unmappable, or already in FTS from a prior run (resume)
			}
			if (!entryFqns.isEmpty() && entryFqns.contains(fqn)) {
				entry.add(jc); // Tier-1: manifest entry points
			} else if (prefix != null && (fqn.startsWith(prefix) || fqn.equals(pkg))) {
				main.add(jc); // Tier-2: app main package
			} else {
				rest.add(jc); // Tier-3: everything else in scope (obfuscated + third-party)
			}
		}
		status.setInScopeClasses(inScope);
		status.setTotal(inScope);
		int par = indexThreads();
		int chunk = chunkSize();
		LOG.info("[index] phase-3 scope='{}': {} in-scope, {} skipped; {} pending this pass "
				+ "(entry {} + main {} + rest {}); par={} chunk={} shards={}", scope.describe(), inScope, skipped,
				entry.size() + main.size() + rest.size(), entry.size(), main.size(), rest.size(), par, chunk,
				csi.shardCount());

		ForkJoinPool pool = new ForkJoinPool(par);
		try {
			// Tier-1 entry → Tier-2 main → Tier-3 rest. A tier's *_ready flag flips only once its pending
			// classes are all decompiled+flushed (an empty list on resume completes instantly → flag set).
			status.setCurrentTier(IndexStatus.Tier.ENTRY);
			runChunks(pool, entry, chunk);
			if (!stopped()) {
				status.setEntryReady(true);
			}
			if (!stopped()) {
				status.setCurrentTier(IndexStatus.Tier.MAIN);
				runChunks(pool, main, chunk);
				if (!stopped()) {
					status.setMainReady(true);
				}
			}
			if (!stopped()) {
				status.setCurrentTier(IndexStatus.Tier.REST);
				runChunks(pool, rest, chunk);
			}
		} finally {
			pool.shutdown();
		}
		if (memStop.get()) {
			LOG.warn("[index] decompile pass stopped early on low heap at {} classes — reload to resume", status.decompiled());
		}
		return memStop.get();
	}

	/** Cancelled by an APK switch/shutdown, or stopped to resume on low heap — either ends the current tier. */
	private boolean stopped() {
		return cancelled.get() || memStop.get();
	}

	/**
	 * Process a class list in chunks: parallel decompile + enqueue a chunk, barrier, then serial
	 * {@link #releaseClass} of the chunk. Peak heap ≈ baseline + one chunk's IR + the bounded queue, so a
	 * single pass covers the whole scope. The shard writers commit periodically themselves; a low-heap
	 * backstop (after a GC) still resumes-not-OOMs if some other global creeps up.
	 */
	private void runChunks(ForkJoinPool pool, List<JavaClass> classes, int chunkSize) throws Exception {
		for (int from = 0; from < classes.size(); from += chunkSize) {
			if (cancelled.get() || memStop.get()) {
				return;
			}
			int to = Math.min(classes.size(), from + chunkSize);
			List<JavaClass> chunk = classes.subList(from, to);
			// 1) parallel decompile + enqueue (jadx reads global const storage here → must stay read-only)
			pool.submit(() -> chunk.parallelStream().forEach(this::decompileAndIndex)).get();
			// 2) serial release: clear each class's global accumulation now that no decompile is running
			for (JavaClass jc : chunk) {
				releaseClass(jc);
			}
			// 2b) flush the shard writers: drain the queue + commit + TRUNCATE each WAL. Keeps the queue
			// empty and WAL files small at the heap check below, so peak RAM stays bounded across the pass.
			csi.flush();
			// After flush, every enqueued class (= all decompiled so far) is committed to FTS, so the
			// indexed count catches up to the decompiled count here (it only lags transiently mid-chunk).
			status.setIndexedClasses(status.decompiled());
			LOG.info("[index] indexed {} classes so far (chunk of {})", status.decompiled(), chunk.size());
			// 3) heap backstop: per-chunk release + flush should keep us bounded. Proactively GC when free
			// headroom dips below 1/8 of -Xmx, but only STOP-to-resume when it's still critically low (<1/16)
			// after the GC — the resident jadx model sits near the threshold on huge apps, so stopping at the
			// first 1/8 dip ends the pass prematurely (it did on Douyin); 1/16 gives the GC room to recover.
			if (lowHeap()) {
				System.gc();
				if (criticalHeap()) {
					memStop.set(true);
					return;
				}
			}
		}
	}

	/** Decompile one class (jadx caches the text to disk) and hand it to the FTS shards for indexing. */
	private void decompileAndIndex(JavaClass jc) {
		if (cancelled.get() || memStop.get()) {
			return;
		}
		int clsIdx = diskCache.clsId(jc.getRawName());
		if (clsIdx < 0) {
			return;
		}
		String code = null;
		try {
			code = jc.getCode();
		} catch (Throwable t) {
			LOG.debug("decompile {} failed: {}", safeName(jc), t.toString());
		}
		if (code != null && !code.isEmpty()) {
			int strs = csi.enqueue(clsIdx, code); // extracts literals (parallel, D3) + hands to shard writer
			if (strs > 0) {
				status.addStrings(strs);
			}
		}
		status.incDecompiled();
	}

	/**
	 * Seed the tier-readiness flags from on-disk meta at the start of a build/resume (2.3): {@code graph_done}
	 * ⇒ xref, {@code entry_done}/{@code main_done} ⇒ those tiers. Keeps {@code index_status} correct after a
	 * restart before the builder re-derives readiness by re-running the (now-empty) tiers.
	 */
	private void seedTierFlagsFromMeta() {
		if ("true".equals(db.getMeta("graph_done"))) {
			status.setXrefReady(true);
		}
		if ("true".equals(db.getMeta("entry_done"))) {
			status.setEntryReady(true);
		}
		if ("true".equals(db.getMeta("main_done"))) {
			status.setMainReady(true);
		}
	}

	/**
	 * Persist the tier-completion flags so a later reload restores readiness from disk (2.3/6.3). Called on
	 * the builder thread after the code phase finishes (no concurrent writer), so writing the MAIN-DB writer
	 * connection is safe. A hard cancel may skip this, but a resume re-derives the flags as each tier's
	 * (already-complete) pending list empties.
	 */
	private void persistTierMeta(Connection w) {
		if (status.isEntryReady()) {
			setMeta(w, "entry_done", "true");
		}
		if (status.isMainReady()) {
			setMeta(w, "main_done", "true");
		}
	}

	/**
	 * Release one indexed class's heap — two distinct accumulations, both required:
	 *
	 * <ol>
	 *   <li><b>Decompiled IR</b> via {@link ClassNode#unload()}. The lazy {@code getCode()} API keeps a
	 *       class LOADED after codegen for cheap re-access, so without this ~100 KB/class of IR accumulates
	 *       and caps a 20 GB pass. {@code unload()} recurses inner classes and does NOT touch the disk code
	 *       cache, so the text we just wrote stays for reuse/ripgrep/{@code get_class_source}.</li>
	 *   <li><b>Global const storage</b> via {@code removeForClass} (recursing inners): the per-class entries
	 *       in the root's {@link jadx.core.dex.info.ConstStorage} that {@code unload()} leaves behind.</li>
	 * </ol>
	 *
	 * <p>MUST run serially (between chunks, no concurrent decompile): both {@code unload()} and
	 * {@code ConstStorage.classes} (a plain {@code HashMap} the parallel decompile reads via
	 * {@code getConstField}) are unsafe to mutate while classes are decompiling.
	 */
	private void releaseClass(JavaClass jc) {
		try {
			ClassNode cn = jc.getClassNode();
			if (cn == null) {
				return;
			}
			cn.unload(); // free decompiled IR (the dominant per-class accumulation; recurses inners)
			clearConstStorage(cn, cn.root().getConstValues()); // free the const entries unload() leaves
		} catch (Throwable ignored) {
			// best effort
		}
	}

	private static void clearConstStorage(ClassNode cn, jadx.core.dex.info.ConstStorage consts) {
		consts.removeForClass(cn);
		for (ClassNode inner : cn.getInnerClasses()) {
			clearConstStorage(inner, consts);
		}
	}

	/**
	 * Clear the in-heap use-in lists on every node after the usage graph is in SQLite (D7). These lists
	 * (who-calls-me / who-uses-me) are the spike's ~4.6 GB on Douyin and the heap's biggest avoidable
	 * consumer. xref now answers from SQLite, so clearing them is safe and frees the headroom the
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

	/** Proactive-GC threshold: free heap headroom below ~1/8 of -Xmx. */
	private static boolean lowHeap() {
		Runtime rt = Runtime.getRuntime();
		long max = rt.maxMemory();
		long used = rt.totalMemory() - rt.freeMemory();
		return (max - used) < (max / 8);
	}

	/** Stop-to-resume threshold: free heap headroom still below ~1/16 of -Xmx after a GC (near-OOM). */
	private static boolean criticalHeap() {
		Runtime rt = Runtime.getRuntime();
		long max = rt.maxMemory();
		long used = rt.totalMemory() - rt.freeMemory();
		return (max - used) < (max / 16);
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
