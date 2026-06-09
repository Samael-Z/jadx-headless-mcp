package com.zin.jadxheadless;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

import com.zin.jadxheadless.index.AnalysisScope;
import com.zin.jadxheadless.index.CodeSearchIndex;
import com.zin.jadxheadless.index.Db;
import com.zin.jadxheadless.index.FtsShards;
import com.zin.jadxheadless.jadx.BoundedCodeCache;
import com.zin.jadxheadless.jadx.DiskCodeCache;
import com.zin.jadxheadless.util.CacheLayout;
import com.zin.jadxheadless.util.ManifestUtil;

import jadx.api.IDecompileScheduler;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.args.UseSourceNameAsClassNameAlias;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.impl.NoOpCodeCache;
import jadx.api.plugins.loader.JadxBasePluginLoader;
import jadx.api.plugins.pass.JadxPassInfo;
import jadx.api.plugins.pass.impl.SimpleJadxPassInfo;
import jadx.api.plugins.pass.types.JadxPreparePass;
import jadx.core.Jadx;
import jadx.core.dex.info.ConstStorage;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

/**
 * {@code --bench-decompile} spike (fast-index-pipeline task 0): measure the <b>pure decompile floor</b>
 * at full quality, to judge whether the 8-min full-build target is physically reachable on this machine
 * before investing in the pipeline rewrite. Three passes over the same in-scope class set, each at
 * {@code par=threads}, with the <b>production full-quality {@link JadxArgs}</b> (AUTO/RESTRUCTURE +
 * source-name ALWAYS + kotlin-metadata on classpath — identical to {@link com.zin.jadxheadless.jadx.JadxService};
 * the bench never degrades to simple/fallback):
 *
 * <ol>
 *   <li><b>pass 1 — DECOMPILE-ONLY</b> ({@link NoOpCodeCache}): {@code getCode()} then discard +
 *       {@code unload()}. No disk write, no FTS. → the pure decompile rate (class/s), peak heap, the
 *       floor for every other number.</li>
 *   <li><b>pass 2 — +DISK</b> ({@link DiskCodeCache}): also persists {@code .java}. → the disk-write
 *       delta (expected ≈ 0: SSD, no fsync).</li>
 *   <li><b>pass 3 — +FTS (current)</b> ({@link DiskCodeCache} + the real {@link CodeSearchIndex#indexCode}
 *       serial write): reproduces the current bottleneck, with the task-0.2 nanoTime split of FTS-insert
 *       (trigram tokenization) vs string-extract inside {@code indexCode}.</li>
 * </ol>
 *
 * <p>Judgement (task 0.3): {@code floor = inScope / pass1-rate}. pass3 ≪ pass1 confirms the serial FTS
 * write is the main bottleneck; pass2 ≈ pass1 confirms disk is not. All output goes to stderr.
 */
public final class BenchDecompile {

	private BenchDecompile() {
	}

	/** Decompile→release chunk size (mirrors IndexBuilder's safety model so peak heap stays bounded). */
	private static final int CHUNK = 4000;

	public static void run(String apkPath, boolean deobf, int limit, int threads,
			List<String> include, List<String> exclude, boolean indexAll) throws Exception {
		PeakHeapSampler sampler = new PeakHeapSampler();
		sampler.start();
		try {
			Path apk = Path.of(apkPath).toAbsolutePath();
			Path cacheDir = CacheLayout.forApk(apk);
			Path benchDir = cacheDir.resolve("bench");
			Files.createDirectories(benchDir);
			String codeVersion = Jadx.getVersion() + "|src=ALWAYS|kotlin=on|deobf=" + deobf;
			out("=== bench-decompile apk=%s deobf=%s limit=%d threads=%d cores=%d ===",
					apk, deobf, limit, threads, Runtime.getRuntime().availableProcessors());

			// ---- pass 1: DECOMPILE-ONLY (NoOpCodeCache) — the pure floor ----
			Result p1 = pass("1 DECOMPILE-ONLY (NoOp)", apk, deobf, limit, threads,
					include, exclude, indexAll, root -> root.getArgs().setCodeCache(NoOpCodeCache.INSTANCE),
					null);

			// ---- pass 2: +DISK (DiskCodeCache) ----
			Result p2 = pass("2 +DISK (DiskCodeCache)", apk, deobf, limit, threads,
					include, exclude, indexAll, root -> {
						DiskCodeCache d = new DiskCodeCache(root, cacheDir.resolve("bench-p2"), codeVersion);
						root.getArgs().setCodeCache(new BoundedCodeCache(d, 1500));
					}, null);

			// ---- pass 3: +FTS (DiskCodeCache + the real sharded enqueue→shard-writer pipeline) ----
			CodeSearchIndex.BENCH_TIMING = true;
			CodeSearchIndex.BENCH_FTS_NANOS.set(0);
			CodeSearchIndex.BENCH_STR_NANOS.set(0);
			int shardCount = FtsShards.shardCountFromEnv();
			Db benchDb = Db.open(benchDir);
			FtsShards benchShards = new FtsShards(benchDir.resolve("fts"), shardCount);
			CodeSearchIndex csi = new CodeSearchIndex(benchDb,
					cacheDir.resolve("bench-p3").resolve("code").resolve("sources"), benchShards);
			csi.beginBuild(64L * 1024 * 1024);
			Result p3 = pass("3 +FTS (Disk + " + shardCount + "-shard pipeline)", apk, deobf, limit, threads,
					include, exclude, indexAll, root -> {
						DiskCodeCache d = new DiskCodeCache(root, cacheDir.resolve("bench-p3"), codeVersion);
						root.getArgs().setCodeCache(new BoundedCodeCache(d, 1500));
					}, csi);
			csi.finishBuild();
			csi.close();
			benchDb.close();
			CodeSearchIndex.BENCH_TIMING = false;

			long ftsMs = CodeSearchIndex.BENCH_FTS_NANOS.get() / 1_000_000L;
			long strMs = CodeSearchIndex.BENCH_STR_NANOS.get() / 1_000_000L;

			// ---- judgement (task 0.3 raw data; conclusions written to design.md by the operator) ----
			out("\n=== RESULTS (peak heap during whole bench = %d MB) ===", sampler.peakMb());
			report(p1);
			report(p2);
			report(p3);
			out("");
			out("[pass3 indexCode split over %d classes] FTS-insert(trigram)=%dms (%.2fms/cls)  string-extract+insert=%dms (%.2fms/cls)",
					p3.decompiled, ftsMs, perCls(ftsMs, p3.decompiled), strMs, perCls(strMs, p3.decompiled));
			if (p1.rate() > 0) {
				out("[FLOOR] pure-decompile rate = %.1f cls/s → full-scope floor = inScope / rate "
						+ "(e.g. 312498 / %.1f = %.1f s = %.1f min)",
						p1.rate(), p1.rate(), 312498.0 / p1.rate(), 312498.0 / p1.rate() / 60.0);
				out("[CHECK] pass3/pass1 rate ratio = %.2f (≪1 ⇒ serial FTS is the bottleneck); "
						+ "pass2/pass1 = %.2f (≈1 ⇒ disk not the bottleneck)",
						p3.rate() / p1.rate(), p2.rate() / p1.rate());
			}

			// ---- task 6 spike: naive parallelStream vs jadx DecompilerScheduler (pure contention) ----
			// Both passes decompile the IDENTICAL flattened class set (in-scope targets + the deps the
			// scheduler pulls into batches); only the parallelization strategy differs, so the delta is
			// pure dependency-lock contention. Worker thread states are sampled (RUNNABLE ≈ effective
			// cores doing work; BLOCKED ≈ threads parked on a synchronized(ClassInfo) monitor).
			out("\n=== CONTENTION SPIKE (task 6): naive parallelStream vs DecompilerScheduler ===");
			ContentionResult cNaive = passContention("NAIVE parallelStream", apk, deobf, limit, threads,
					include, exclude, indexAll, false);
			ContentionResult cSched = passContention("SCHEDULER batches", apk, deobf, limit, threads,
					include, exclude, indexAll, true);
			report6(cNaive);
			report6(cSched);
			if (cNaive.rate() > 0) {
				out("[SPIKE] scheduler/naive throughput = %.2fx over %d cores; "
						+ "avg RUNNABLE workers naive=%.1f scheduler=%.1f (≈ effective cores); "
						+ "avg BLOCKED workers naive=%.1f scheduler=%.1f (high BLOCKED on naive ⇒ dependency-lock contention)",
						cSched.rate() / cNaive.rate(), threads, cNaive.avgRunnable, cSched.avgRunnable,
						cNaive.avgBlocked, cSched.avgBlocked);
			}
		} finally {
			sampler.stop();
		}
	}

	/**
	 * One contention pass (task 6): load fresh, take the in-scope targets, build jadx's dependency-aware
	 * batches, then decompile EITHER naively ({@code flat.parallelStream}) OR by the scheduler
	 * ({@code batches} one-per-thread, sequential within), sampling worker thread states throughout.
	 */
	private static ContentionResult passContention(String label, Path apk, boolean deobf, int limit, int threads,
			List<String> include, List<String> exclude, boolean indexAll, boolean useScheduler) throws Exception {
		out("\n--- contention: %s ---", label);
		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(apk.toFile());
		args.setUseSourceNameAsClassNameAlias(UseSourceNameAsClassNameAlias.ALWAYS);
		args.setDeobfuscationOn(deobf);
		args.setShowInconsistentCode(true);
		args.setPluginLoader(new JadxBasePluginLoader());
		JadxCodeData codeData = new JadxCodeData();
		codeData.setRenames(new ArrayList<>());
		codeData.setComments(new ArrayList<>());
		args.setCodeData(codeData);
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.addCustomPass(new JadxPreparePass() {
				@Override
				public JadxPassInfo getInfo() {
					return new SimpleJadxPassInfo("BenchCacheInit");
				}

				@Override
				public void init(RootNode root) {
					root.getArgs().setCodeCache(NoOpCodeCache.INSTANCE);
				}
			});
			jadx.load();
			List<JavaClass> targets = selectInScope(jadx, include, exclude, indexAll, limit);
			IDecompileScheduler scheduler = jadx.getDecompileScheduler();
			List<List<JavaClass>> batches = scheduler.buildBatches(targets);
			List<JavaClass> flat = new ArrayList<>();
			for (List<JavaClass> b : batches) {
				flat.addAll(b);
			}
			out("  %d in-scope → %d batches, %d total classes (avg %.1f/batch)", targets.size(),
					batches.size(), flat.size(), batches.isEmpty() ? 0 : (double) flat.size() / batches.size());

			ThreadStateSampler sampler = new ThreadStateSampler();
			AtomicInteger done = new AtomicInteger();
			ForkJoinPool pool = new ForkJoinPool(threads);
			sampler.start();
			long d0 = System.currentTimeMillis();
			try {
				if (useScheduler) {
					pool.submit(() -> batches.parallelStream().forEach(batch -> {
						for (JavaClass jc : batch) {
							decode(jc);
							done.incrementAndGet();
						}
					})).get();
				} else {
					pool.submit(() -> flat.parallelStream().forEach(jc -> {
						decode(jc);
						done.incrementAndGet();
					})).get();
				}
			} finally {
				pool.shutdown();
			}
			long ms = System.currentTimeMillis() - d0;
			sampler.stop();
			return new ContentionResult(label, ms, done.get(), sampler.avgRunnable(), sampler.avgBlocked());
		}
	}

	private static void decode(JavaClass jc) {
		try {
			jc.getCode();
		} catch (Throwable t) {
			// best effort — a failed class still produces fallback text in production
		}
	}

	private static void report6(ContentionResult r) {
		out("[%s] %dms, %d classes, %.1f cls/s; avg workers RUNNABLE=%.1f BLOCKED=%.1f",
				r.label, r.ms, r.classes, r.rate(), r.avgRunnable, r.avgBlocked);
	}

	private static double perCls(long ms, int n) {
		return n == 0 ? 0 : (double) ms / n;
	}

	/** One bench pass: fresh full-quality load with the given code-cache installer, then a timed chunked decompile. */
	private static Result pass(String label, Path apk, boolean deobf, int limit, int threads,
			List<String> include, List<String> exclude, boolean indexAll,
			CacheInstaller cacheInstaller, CodeSearchIndex csi) throws Exception {
		out("\n--- pass %s ---", label);
		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(apk.toFile());
		args.setUseSourceNameAsClassNameAlias(UseSourceNameAsClassNameAlias.ALWAYS);
		args.setDeobfuscationOn(deobf);
		args.setShowInconsistentCode(true);
		args.setPluginLoader(new JadxBasePluginLoader());
		JadxCodeData codeData = new JadxCodeData();
		codeData.setRenames(new ArrayList<>());
		codeData.setComments(new ArrayList<>());
		args.setCodeData(codeData);

		long t0 = System.currentTimeMillis();
		try (JadxDecompiler jadx = new JadxDecompiler(args)) {
			jadx.addCustomPass(new JadxPreparePass() {
				@Override
				public JadxPassInfo getInfo() {
					return new SimpleJadxPassInfo("BenchCacheInit");
				}

				@Override
				public void init(RootNode root) {
					cacheInstaller.install(root);
				}
			});
			jadx.load();
			long loadMs = System.currentTimeMillis() - t0;

			List<JavaClass> targets = selectInScope(jadx, include, exclude, indexAll, limit);
			out("loaded %d classes in %dms; bench target = %d in-scope classes (limit=%d)",
					jadx.getClassesWithInners().size(), loadMs, targets.size(), limit);

			AtomicInteger done = new AtomicInteger();
			AtomicInteger rowidSeq = new AtomicInteger();
			long d0 = System.currentTimeMillis();
			ForkJoinPool pool = new ForkJoinPool(threads);
			try {
				for (int from = 0; from < targets.size(); from += CHUNK) {
					int to = Math.min(targets.size(), from + CHUNK);
					List<JavaClass> chunk = targets.subList(from, to);
					pool.submit(() -> chunk.parallelStream().forEach(jc -> {
						try {
							String code = jc.getCode();
							if (csi != null && code != null && !code.isEmpty()) {
								csi.enqueue(rowidSeq.incrementAndGet(), code); // unique bench rowid (no collisions)
							}
						} catch (Throwable t) {
							// best effort — a failed class still produces fallback text in production
						}
						done.incrementAndGet();
					})).get();
					// serial release (same safety model as IndexBuilder: unload + ConstStorage clear)
					for (JavaClass jc : chunk) {
						releaseClass(jc);
					}
				}
			} finally {
				pool.shutdown();
			}
			long decompileMs = System.currentTimeMillis() - d0;
			return new Result(label, loadMs, decompileMs, done.get());
		}
	}

	/** Mirror IndexBuilder's in-scope selection (scope.shouldIndex, main package first), capped at limit. */
	private static List<JavaClass> selectInScope(JadxDecompiler jadx, List<String> include, List<String> exclude,
			boolean indexAll, int limit) {
		String pkg = ManifestUtil.packageName(jadx);
		AnalysisScope scope = new AnalysisScope(pkg, include, exclude, true, indexAll);
		String prefix = (pkg == null || pkg.isEmpty()) ? null : pkg + ".";
		List<JavaClass> main = new ArrayList<>();
		List<JavaClass> rest = new ArrayList<>();
		for (JavaClass jc : jadx.getClasses()) {
			String fqn;
			try {
				fqn = jc.getFullName();
			} catch (Throwable t) {
				continue;
			}
			if (!scope.shouldIndex(fqn)) {
				continue;
			}
			if (prefix != null && (fqn.startsWith(prefix) || fqn.equals(pkg))) {
				main.add(jc);
			} else {
				rest.add(jc);
			}
		}
		List<JavaClass> all = new ArrayList<>(main);
		all.addAll(rest);
		return limit > 0 && all.size() > limit ? all.subList(0, limit) : all;
	}

	private static void releaseClass(JavaClass jc) {
		try {
			ClassNode cn = jc.getClassNode();
			if (cn == null) {
				return;
			}
			ConstStorage consts = cn.root().getConstValues();
			cn.unload();
			clearConst(cn, consts);
		} catch (Throwable ignored) {
			// best effort
		}
	}

	private static void clearConst(ClassNode cn, ConstStorage consts) {
		consts.removeForClass(cn);
		for (ClassNode inner : cn.getInnerClasses()) {
			clearConst(inner, consts);
		}
	}

	private static void report(Result r) {
		out("[pass %s] load=%dms decompile=%dms classes=%d rate=%.1f cls/s",
				r.label, r.loadMs, r.decompileMs, r.decompiled, r.rate());
	}

	private static void out(String fmt, Object... a) {
		System.err.println(String.format(fmt, a));
	}

	@FunctionalInterface
	private interface CacheInstaller {
		void install(RootNode root);
	}

	private static final class Result {
		final String label;
		final long loadMs;
		final long decompileMs;
		final int decompiled;

		Result(String label, long loadMs, long decompileMs, int decompiled) {
			this.label = label;
			this.loadMs = loadMs;
			this.decompileMs = decompileMs;
			this.decompiled = decompiled;
		}

		double rate() {
			return decompileMs == 0 ? 0 : decompiled * 1000.0 / decompileMs;
		}
	}

	/** Result of one contention pass: throughput + average worker-thread RUNNABLE/BLOCKED counts. */
	private static final class ContentionResult {
		final String label;
		final long ms;
		final int classes;
		final double avgRunnable;
		final double avgBlocked;

		ContentionResult(String label, long ms, int classes, double avgRunnable, double avgBlocked) {
			this.label = label;
			this.ms = ms;
			this.classes = classes;
			this.avgRunnable = avgRunnable;
			this.avgBlocked = avgBlocked;
		}

		double rate() {
			return ms == 0 ? 0 : classes * 1000.0 / ms;
		}
	}

	/**
	 * Samples ForkJoinPool worker thread states every 100ms via {@link ThreadMXBean} (maxDepth 0 — no
	 * stack capture, cheap). RUNNABLE workers ≈ effective cores doing work; BLOCKED workers ≈ threads
	 * parked on a {@code synchronized} monitor (the {@code ProcessClass} dependency lock) — the
	 * contention signal the task-6 spike is after.
	 */
	private static final class ThreadStateSampler {
		private volatile boolean running = true;
		private long runnableSum;
		private long blockedSum;
		private long samples;
		private Thread thread;

		void start() {
			final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
			thread = new Thread(() -> {
				while (running) {
					int r = 0;
					int b = 0;
					ThreadInfo[] infos = bean.getThreadInfo(bean.getAllThreadIds(), 0);
					for (ThreadInfo ti : infos) {
						if (ti == null) {
							continue;
						}
						String n = ti.getThreadName();
						if (n == null || !n.startsWith("ForkJoinPool")) {
							continue;
						}
						Thread.State s = ti.getThreadState();
						if (s == Thread.State.RUNNABLE) {
							r++;
						} else if (s == Thread.State.BLOCKED) {
							b++;
						}
					}
					synchronized (this) {
						runnableSum += r;
						blockedSum += b;
						samples++;
					}
					try {
						Thread.sleep(100);
					} catch (InterruptedException e) {
						return;
					}
				}
			}, "contention-sampler");
			thread.setDaemon(true);
			thread.start();
		}

		synchronized double avgRunnable() {
			return samples == 0 ? 0 : (double) runnableSum / samples;
		}

		synchronized double avgBlocked() {
			return samples == 0 ? 0 : (double) blockedSum / samples;
		}

		void stop() {
			running = false;
			if (thread != null) {
				thread.interrupt();
			}
		}
	}

	/** Samples used heap every 500ms and tracks the peak (mirror of SelfTest's sampler). */
	private static final class PeakHeapSampler {
		private volatile boolean running = true;
		private volatile long peak = 0;
		private Thread thread;

		void start() {
			thread = new Thread(() -> {
				Runtime rt = Runtime.getRuntime();
				while (running) {
					long used = rt.totalMemory() - rt.freeMemory();
					if (used > peak) {
						peak = used;
					}
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						return;
					}
				}
			}, "bench-heap-sampler");
			thread.setDaemon(true);
			thread.start();
		}

		long peakMb() {
			return peak / (1024 * 1024);
		}

		void stop() {
			running = false;
			if (thread != null) {
				thread.interrupt();
			}
		}
	}
}
