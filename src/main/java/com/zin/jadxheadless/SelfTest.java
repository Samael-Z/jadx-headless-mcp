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
		PeakHeapSampler sampler = new PeakHeapSampler();
		sampler.start();

		JadxService svc = new JadxService();
		out("=== load_apk %s (deobf=%s) ===", apk, deobf);
		long t0 = System.currentTimeMillis();
		Map<String, Object> load = svc.loadApk(apk, deobf);
		out("loaded: %s", load);

		// wait for the background index, printing progress
		IndexStatus st = svc.indexStatus();
		long deadline = System.currentTimeMillis() + indexWaitMs;
		while (System.currentTimeMillis() < deadline) {
			Map<String, Object> sm = st.toMap();
			out("[index] %s", sm);
			if (st.state() == IndexStatus.State.READY || st.state() == IndexStatus.State.FAILED) {
				break;
			}
			Thread.sleep(8000);
		}

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

		// Tier-1: string constant search (RE main line)
		check("search_string_constants('http')", () -> {
			List<Map<String, Object>> r = svc.codeSearch().searchStringConstants("http", 10);
			return "hits=" + r.size() + (r.isEmpty() ? "" : " e.g. " + r.get(0));
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
			Map<String, Object> r = svc.codeSearch().searchInCode("onCreate", false, 10);
			return "engine=" + r.get("engine") + " count=" + r.get("count");
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
