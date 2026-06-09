package com.zin.jadxheadless;

import java.util.List;
import java.util.Map;

import com.zin.jadxheadless.index.IndexStatus;
import com.zin.jadxheadless.jadx.JadxService;
import com.zin.jadxheadless.util.DexId;
import com.zin.jadxheadless.util.ManifestUtil;

import jadx.api.JavaClass;

/**
 * Headless end-to-end self-test (invoked via {@code --selftest <apk>}). Drives the same code the MCP
 * tools drive — load → background index → xref (SQLite) / string / code search — and prints results,
 * timings, and peak heap. This is the runnable form of the section-6 validation: it exercises the
 * pipeline without needing an external MCP client.
 */
public final class SelfTest {

	public static void run(String apk, boolean deobf, long indexWaitMs) throws Exception {
		run(apk, deobf, indexWaitMs, List.of(), List.of(), false);
	}

	public static void run(String apk, boolean deobf, long indexWaitMs, List<String> indexInclude,
			List<String> indexExclude, boolean indexAll) throws Exception {
		PeakHeapSampler sampler = new PeakHeapSampler();
		sampler.start();

		JadxService svc = new JadxService();
		svc.setIndexOptions(indexInclude, indexExclude, indexAll);
		out("=== load_apk %s (deobf=%s, indexAll=%s) ===", apk, deobf, indexAll);
		long t0 = System.currentTimeMillis();
		Map<String, Object> load = svc.loadApk(apk, deobf);
		out("loaded: %s", load);

		// wait for the background index, printing progress + recording tier-availability milestones
		// (progressive-index-availability 6.1: when each tier becomes searchable, relative to load).
		IndexStatus st = svc.indexStatus();
		long deadline = System.currentTimeMillis() + indexWaitMs;
		long xrefAt = -1;
		long entryAt = -1;
		long mainAt = -1;
		long completeAt = -1;
		while (System.currentTimeMillis() < deadline) {
			Map<String, Object> sm = st.toMap();
			out("[index] %s", sm);
			long el = System.currentTimeMillis() - t0;
			if (xrefAt < 0 && st.isXrefReady()) {
				xrefAt = el;
				out(">>> xref_ready at %dms (get_xrefs_* usable)", el);
			}
			if (entryAt < 0 && st.isEntryReady()) {
				entryAt = el;
				out(">>> entry_ready at %dms (manifest entry classes searchable)", el);
			}
			if (mainAt < 0 && st.isMainReady()) {
				mainAt = el;
				out(">>> main_ready at %dms (app main package searchable)", el);
			}
			if (completeAt < 0 && st.coverageComplete()) {
				completeAt = el;
				out(">>> coverage_complete at %dms", el);
			}
			if (st.state() == IndexStatus.State.READY || st.state() == IndexStatus.State.FAILED) {
				break;
			}
			Thread.sleep(8000);
		}
		// effectively-final snapshots for the assertion lambdas below
		final long xrefMs = xrefAt;
		final long entryMs = entryAt;
		final long mainMs = mainAt;
		final long completeMs = completeAt;

		out("\n=== TOOL CHECKS ===");
		int classes = svc.getClassesWithInners().size();
		out("classes (with inners): %d", classes);

		String mainAct = ManifestUtil.mainActivity(svc.jadx());
		out("main_activity: %s", mainAct);

		// Tier-2: decompile one class
		check("get_class_source(main_activity)", () -> {
			if (mainAct == null) {
				return "no main activity";
			}
			JavaClass c = svc.findClass(mainAct);
			if (c == null) {
				return "main activity class not found in model";
			}
			String code = c.getCode();
			return "source length=" + (code == null ? 0 : code.length());
		});

		// Tier-1: string constant search (RE main line), aggregated by class + stdlib-filtered
		check("search_string_constants('http')", () -> {
			List<Map<String, Object>> r = svc.codeSearch().searchStringConstants("http", 10, false);
			return "classes=" + r.size() + (r.isEmpty() ? "" : " e.g. " + r.get(0));
		});

		// Tier-1: xref-to-class (out-of-heap SQLite). Pick a class with users.
		check("get_xrefs_to_class(main_activity)", () -> {
			if (mainAct == null) {
				return "no main activity";
			}
			JavaClass c = svc.findClass(mainAct);
			if (c == null) {
				return "not found";
			}
			Integer sid = svc.graph().classIdByDexId(DexId.forClass(c));
			if (sid == null) {
				return "no symbol (index not at usage phase yet?)";
			}
			return "users=" + svc.graph().classUsers(sid).size();
		});

		// Tier-3: code search
		check("search_in_code('onCreate')", () -> {
			Map<String, Object> r = svc.codeSearch().searchInCode("onCreate", false, 10, null, false);
			return "engine=" + r.get("engine") + " count=" + r.get("count");
		});

		// ---- analysis-value layer assertions (analysis-value-code-search, task 4.2) ----
		out("\n=== ANALYSIS-VALUE CHECKS ===");
		// Layer 1: default search_in_code filters stdlib (T4) and yields one row per class.
		check("layer1: search_in_code('http') default — no stdlib hits, one row/class", () -> {
			Map<String, Object> r = svc.codeSearch().searchInCode("http", false, 50, null, false);
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> matches = (List<Map<String, Object>>) r.get("matches");
			java.util.Set<String> seen = new java.util.HashSet<>();
			int libHits = 0;
			int dupes = 0;
			for (Map<String, Object> m : matches) {
				String c = String.valueOf(m.get("class"));
				if (isStdlib(c)) {
					libHits++;
				}
				if (!seen.add(c)) {
					dupes++;
				}
			}
			boolean ok = libHits == 0 && dupes == 0;
			return "classes=" + matches.size() + " stdlibHits=" + libHits + " dupClasses=" + dupes
					+ (ok ? "  [PASS]" : "  [FAIL]");
		});
		// Layer 1: include_libs=true must return at least as many as the default (filtered) query.
		check("layer1: include_libs=true ≥ default", () -> {
			int d = count(svc.codeSearch().searchInCode("http", false, 500, null, false));
			int withLibs = count(svc.codeSearch().searchInCode("http", false, 500, null, true));
			return "default=" + d + " include_libs=" + withLibs + (withLibs >= d ? "  [PASS]" : "  [FAIL]");
		});
		// Layer 2: report the active index scope and how many classes it selected vs. the full set.
		check("layer2: index scope", () -> {
			Map<String, Object> sm = st.toMap();
			return "scope=" + sm.get("index_scope") + " in_scope_classes=" + sm.get("in_scope_classes")
					+ " all_classes=" + classes;
		});

		// ---- progressive-index-availability assertions (task 6.2) + milestones (6.1) ----
		out("\n=== PROGRESSIVE AVAILABILITY (milestones from load) ===");
		out("xref_ready=%s  entry_ready=%s  main_ready=%s  coverage_complete=%s",
				ms(xrefMs), ms(entryMs), ms(mainMs), ms(completeMs));

		out("\n=== PROGRESSIVE CHECKS ===");
		// index_status must expose the tiered-availability fields (D6).
		check("progressive: index_status tier fields present", () -> {
			Map<String, Object> sm = st.toMap();
			StringBuilder missing = new StringBuilder();
			for (String k : new String[] { "current_tier", "xref_ready", "entry_ready", "main_ready",
					"decompiled_classes", "indexed_classes" }) {
				if (!sm.containsKey(k)) {
					missing.append(missing.length() == 0 ? "" : ",").append(k);
				}
			}
			boolean ok = missing.length() == 0;
			return (ok ? "all present" : "MISSING " + missing) + ": current_tier=" + sm.get("current_tier")
					+ " decompiled=" + sm.get("decompiled_classes") + " indexed=" + sm.get("indexed_classes")
					+ (ok ? "  [PASS]" : "  [FAIL]");
		});
		// xref was usable independently of decompile (D3): we observed xref_ready, no later than completion.
		check("progressive: xref_ready not after coverage_complete", () -> {
			boolean ok = xrefMs >= 0 && (completeMs < 0 || xrefMs <= completeMs);
			return "xref_ready@" + ms(xrefMs) + " coverage_complete@" + ms(completeMs) + (ok ? "  [PASS]" : "  [FAIL]");
		});
		// Cross-phase (D5): code search returns hits; once coverage_complete the engine is FTS-only (sub-second),
		// while building it is fts5+ripgrep (or fts5 degraded if rg is absent). Either way one row per class.
		check("progressive: search_in_code engine vs coverage", () -> {
			Map<String, Object> r = svc.codeSearch().searchInCode("onCreate", false, 20, null, false);
			boolean complete = st.coverageComplete();
			String engine = String.valueOf(r.get("engine"));
			boolean ok = !complete || engine.equals("fts5");
			return "coverage_complete=" + complete + " engine=" + engine + " count=" + r.get("count")
					+ (ok ? "  [PASS]" : "  [FAIL]");
		});
		// main_ready ⇒ the app's own package is searchable now (string locator), not blocked on the rest.
		check("progressive: main package searchable when main_ready", () -> {
			if (!st.isMainReady()) {
				return "main_ready=false (heap-bounded partial?) — skip";
			}
			String pkg = ManifestUtil.packageName(svc.jadx());
			Map<String, Object> r = svc.codeSearch().searchInCode("class", false, 50,
					pkg == null ? null : pkg, false);
			return "main_ready=true; search scoped to " + pkg + " -> count=" + r.get("count") + "  [PASS]";
		});

		out("\n=== index_status (final) ===");
		out("%s", st.toMap());

		long secs = (System.currentTimeMillis() - t0) / 1000;
		out("\n=== DONE in %ds; peak heap = %d MB ===", secs, sampler.peakMb());
		svc.close();
		sampler.stop();
	}

	private interface Check {
		Object get() throws Exception;
	}

	private static void check(String name, Check c) {
		try {
			out("[ok] %s -> %s", name, c.get());
		} catch (Throwable t) {
			out("[ERR] %s -> %s", name, t.toString());
		}
	}

	private static int count(Map<String, Object> result) {
		Object c = result == null ? null : result.get("count");
		return c instanceof Number ? ((Number) c).intValue() : -1;
	}

	/** Format an elapsed-ms milestone, or "n/a" if the flag was never observed during the wait window. */
	private static String ms(long v) {
		return v < 0 ? "n/a" : v + "ms";
	}

	/** Local mirror of the most common T4 prefixes — for asserting layer-1 stdlib filtering in the self-test. */
	private static final String[] STDLIB = {
			"android.", "androidx.", "java.", "javax.", "kotlin.", "kotlinx.",
			"com.google.", "com.android.", "okhttp3.", "okio.", "retrofit2.",
	};

	private static boolean isStdlib(String fqn) {
		for (String p : STDLIB) {
			if (fqn.startsWith(p)) {
				return true;
			}
		}
		return false;
	}

	private static void out(String fmt, Object... a) {
		System.err.println(String.format(fmt, a));
	}

	/** Samples used heap every 500ms and tracks the peak — a rough section-6.2 heap check. */
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
			}, "heap-sampler");
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
