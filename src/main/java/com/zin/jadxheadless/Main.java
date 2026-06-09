package com.zin.jadxheadless;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zin.jadxheadless.jadx.JadxService;
import com.zin.jadxheadless.mcp.McpToolServer;
import com.zin.jadxheadless.mcp.StdioMcpServer;
import com.zin.jadxheadless.mcp.ToolRegistry;

/**
 * Entry point for the headless jadx + MCP service (single process, D1). Run with a large heap and
 * AWT headless, e.g.:
 *
 * <pre>
 *   java -Xmx20g -Djava.awt.headless=true -jar jadx-headless-mcp-v2.jar \
 *        --host 127.0.0.1 --port 8650 [--apk &lt;path&gt;] [--deobf]
 * </pre>
 *
 * <p>For a client that launches and owns the process (e.g. Claude Code, config
 * {@code "type": "stdio"}), pass {@code --stdio} instead: the MCP service speaks JSON-RPC over
 * stdin/stdout (no port), and the target APK is loaded on demand via the {@code load_apk} tool.
 *
 * Cache root defaults to {@code E:\JADX_CACHE_DIR} (override via {@code JADX_CACHE_DIR}).
 */
public final class Main {

	private static final Logger LOG = LoggerFactory.getLogger(Main.class);

	public static void main(String[] argv) throws Exception {
		System.setProperty("java.awt.headless", "true");

		Args args = Args.parse(argv);
		if (args == null) {
			System.exit(2);
			return;
		}

		if (args.benchDecompile) {
			if (args.apk == null) {
				System.err.println("--bench-decompile requires --apk <path>");
				System.exit(2);
				return;
			}
			BenchDecompile.run(args.apk, args.deobf, args.limit, args.threads,
					args.indexInclude, args.indexExclude, args.indexAll);
			System.exit(0);
			return;
		}

		if (args.selftest) {
			if (args.apk == null) {
				System.err.println("--selftest requires --apk <path>");
				System.exit(2);
				return;
			}
			long wait = 300_000L;
			String waitEnv = System.getenv("JADX_SELFTEST_WAIT_MS");
			if (waitEnv != null) {
				try {
					wait = Long.parseLong(waitEnv.trim());
				} catch (NumberFormatException ignored) {
					// keep default
				}
			}
			SelfTest.run(args.apk, args.deobf, wait, args.indexInclude, args.indexExclude, args.indexAll);
			System.exit(0);
			return;
		}

		JadxService svc = new JadxService();
		svc.setIndexOptions(args.indexInclude, args.indexExclude, args.indexAll);
		svc.setIndexThirdParty(args.indexThirdParty);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOG.info("shutting down");
			svc.close();
		}, "shutdown"));

		ToolRegistry registry = new ToolRegistry(svc);

		if (args.stdio) {
			// stdio transport: the MCP client (e.g. Claude Code) launches and owns this process.
			// Start the MCP server FIRST so the client's initialize handshake is answered
			// immediately — never block startup on a (possibly minutes-long) APK load. The target
			// APK is loaded on demand via the load_apk tool; if --apk was passed, load it in the
			// background so the handshake still returns at once.
			StdioMcpServer server = new StdioMcpServer(registry.build());
			Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "stdio-stop"));
			server.start();
			LOG.info("ready (stdio) — cache root: {}", com.zin.jadxheadless.util.CacheLayout.cacheRoot());
			if (args.apk != null) {
				startBackgroundLoad(svc, args.apk, args.deobf);
			}
			server.awaitShutdown();
			return;
		}

		// HTTP (Streamable HTTP) transport — default. The port is bound after the APK is loaded.
		if (args.apk != null) {
			LOG.info("startup load: {}", args.apk);
			try {
				svc.loadApk(args.apk, args.deobf);
			} catch (Exception e) {
				LOG.error("startup load failed: {}", e.toString(), e);
			}
		}

		McpToolServer server = new McpToolServer(args.host, args.port, registry.build());
		server.start();
		LOG.info("ready — cache root: {}", com.zin.jadxheadless.util.CacheLayout.cacheRoot());
		server.join();
	}

	private static void startBackgroundLoad(JadxService svc, String apk, boolean deobf) {
		Thread t = new Thread(() -> {
			LOG.info("background load: {}", apk);
			try {
				svc.loadApk(apk, deobf);
				LOG.info("background load done: {}", apk);
			} catch (Exception e) {
				LOG.error("background load failed: {}", e.toString(), e);
			}
		}, "startup-load");
		t.setDaemon(true);
		t.start();
	}

	private static final class Args {
		String host = "127.0.0.1";
		int port = 8650;
		String apk = null;
		boolean deobf = false;
		boolean selftest = false;
		boolean stdio = false;
		boolean benchDecompile = false;
		int limit = 20000;
		int threads = Math.max(2, Runtime.getRuntime().availableProcessors());
		final List<String> indexInclude = new ArrayList<>();
		final List<String> indexExclude = new ArrayList<>();
		boolean indexAll = false;
		boolean indexThirdParty = true;

		static Args parse(String[] a) {
			Args r = new Args();
			for (int i = 0; i < a.length; i++) {
				switch (a[i]) {
					case "--host":
						r.host = next(a, ++i);
						break;
					case "--port":
						r.port = Integer.parseInt(next(a, ++i));
						break;
					case "--apk":
						r.apk = next(a, ++i);
						break;
					case "--deobf":
						r.deobf = true;
						break;
					case "--selftest":
						r.selftest = true;
						break;
					case "--bench-decompile":
						r.benchDecompile = true;
						break;
					case "--limit":
						r.limit = Integer.parseInt(next(a, ++i));
						break;
					case "--threads":
						r.threads = Integer.parseInt(next(a, ++i));
						break;
					case "--stdio":
						r.stdio = true;
						break;
					case "--index-include":
						addPrefixes(r.indexInclude, next(a, ++i));
						break;
					case "--index-exclude":
						addPrefixes(r.indexExclude, next(a, ++i));
						break;
					case "--index-all":
						r.indexAll = true;
						break;
					case "--no-index-third-party":
						r.indexThirdParty = false;
						break;
					case "-h":
					case "--help":
						usage();
						return null;
					default:
						System.err.println("Unknown argument: " + a[i]);
						usage();
						return null;
				}
			}
			return r;
		}

		/** Accept comma-separated and/or repeated package prefixes. */
		private static void addPrefixes(List<String> into, String value) {
			for (String p : Arrays.asList(value.split(","))) {
				String t = p.trim();
				if (!t.isEmpty()) {
					into.add(t);
				}
			}
		}

		private static String next(String[] a, int i) {
			if (i >= a.length) {
				throw new IllegalArgumentException("missing value for " + a[i - 1]);
			}
			return a[i];
		}

		private static void usage() {
			System.err.println("Usage: java -Xmx20g -Djava.awt.headless=true -jar jadx-headless-mcp-v2.jar "
					+ "[--host 127.0.0.1] [--port 8650] [--apk <path>] [--deobf] [--stdio]\n"
					+ "       [--index-include <pkg,pkg>] [--index-exclude <pkg,pkg>] [--index-all] [--no-index-third-party]\n"
					+ "       [--bench-decompile [--limit N=20000] [--threads M=cores]]\n"
					+ "  Index scope (analysis-value): default skips stdlib (android/androidx/java/kotlin/google/…);\n"
					+ "  --index-all indexes everything; --no-index-third-party also skips named T3 libs;\n"
					+ "  --index-include/-exclude override by package prefix.\n"
					+ "  --bench-decompile: spike measuring the pure decompile floor at full quality (fast-index-pipeline task 0).");
		}
	}
}
