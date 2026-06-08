package com.zin.jadxheadless;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zin.jadxheadless.jadx.JadxService;
import com.zin.jadxheadless.mcp.McpToolServer;
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
			SelfTest.run(args.apk, args.deobf, wait);
			System.exit(0);
			return;
		}

		JadxService svc = new JadxService();
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			LOG.info("shutting down");
			svc.close();
		}, "shutdown"));

		if (args.apk != null) {
			LOG.info("startup load: {}", args.apk);
			try {
				svc.loadApk(args.apk, args.deobf);
			} catch (Exception e) {
				LOG.error("startup load failed: {}", e.toString(), e);
			}
		}

		ToolRegistry registry = new ToolRegistry(svc);
		McpToolServer server = new McpToolServer(args.host, args.port, registry.build());
		server.start();
		LOG.info("ready — cache root: {}", com.zin.jadxheadless.util.CacheLayout.cacheRoot());
		server.join();
	}

	private static final class Args {
		String host = "127.0.0.1";
		int port = 8650;
		String apk = null;
		boolean deobf = false;
		boolean selftest = false;

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

		private static String next(String[] a, int i) {
			if (i >= a.length) {
				throw new IllegalArgumentException("missing value for " + a[i - 1]);
			}
			return a[i];
		}

		private static void usage() {
			System.err.println("Usage: java -Xmx20g -Djava.awt.headless=true -jar jadx-headless-mcp-v2.jar "
					+ "[--host 127.0.0.1] [--port 8650] [--apk <path>] [--deobf]");
		}
	}
}
