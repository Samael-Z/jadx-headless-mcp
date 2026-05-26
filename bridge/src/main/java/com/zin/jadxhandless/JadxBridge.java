/*
 * Copyright (c) 2026 jadx-handless-mcp contributors
 * Apache License 2.0
 */
package com.zin.jadxhandless;

import com.zin.jadxhandless.server.BridgeContext;
import com.zin.jadxhandless.server.BridgeServer;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.impl.NoOpCodeCache;
import jadx.api.plugins.loader.JadxBasePluginLoader;
import jadx.api.usage.impl.EmptyUsageInfoCache;

import java.io.File;
import java.io.PrintStream;
import java.util.concurrent.CountDownLatch;

/**
 * Headless entry point. Spawned as a child process by the Rust MCP server.
 *
 * Communication contract:
 *   1. Parent passes --apk &lt;path&gt; [--host 127.0.0.1] [--port 0]
 *   2. Bridge loads the APK with JadxDecompiler, then starts a Javalin server.
 *   3. As soon as the server is listening, the bridge prints a single line to STDOUT:
 *          PORT=&lt;actual-port&gt;
 *      so the Rust parent (which asked for --port 0) can discover the OS-assigned port.
 *   4. Bridge then prints "READY" to STDOUT and keeps running until SIGTERM.
 *   5. All log output goes to STDERR (slf4j-simple is configured for that).
 *
 * Why stdout for the handshake: it's the only channel guaranteed to be parsable
 * by the Rust child-process plumbing. Logs on stdout would confuse the handshake.
 */
public class JadxBridge {

    public static void main(String[] args) {
        // Force slf4j-simple to stderr so stdout stays clean for the handshake
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.err");
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn");

        BridgeArgs cli = BridgeArgs.parse(args);
        if (cli == null) {
            System.exit(2);
            return;
        }

        File apk = new File(cli.apkPath);
        if (!apk.isFile()) {
            System.err.println("[jadx-bridge] APK not found: " + cli.apkPath);
            System.exit(3);
            return;
        }

        long started = System.currentTimeMillis();
        System.err.println("[jadx-bridge] Loading " + apk.getAbsolutePath());

        JadxArgs jadxArgs = new JadxArgs();
        jadxArgs.getInputFiles().add(apk);
        jadxArgs.setCodeCache(new NoOpCodeCache());
        jadxArgs.setUsageInfoCache(new EmptyUsageInfoCache());
        // Classpath-only plugin loader: deliberately skips the user's ~/.jadx/plugins/
        // installed-plugin directory (which often contains GUI plugins like jadx-ai-mcp
        // that pull in jadx.gui.* classes we don't have on the classpath).
        jadxArgs.setPluginLoader(new JadxBasePluginLoader());
        // Headless: no output dir needed, we never call save()
        jadxArgs.setShowInconsistentCode(true);

        JadxDecompiler jadx = new JadxDecompiler(jadxArgs);
        try {
            jadx.load();
        } catch (Throwable t) {
            System.err.println("[jadx-bridge] Failed to load APK: " + t);
            t.printStackTrace(System.err);
            jadx.close();
            System.exit(4);
            return;
        }

        long loaded = System.currentTimeMillis();
        int classCount = jadx.getClasses().size();
        System.err.println("[jadx-bridge] Loaded " + classCount + " classes in "
                + (loaded - started) + "ms");

        BridgeContext context = new BridgeContext(jadx, apk);
        BridgeServer server = new BridgeServer(context);
        int actualPort;
        try {
            actualPort = server.start(cli.host, cli.port);
        } catch (Throwable t) {
            System.err.println("[jadx-bridge] Failed to start HTTP server: " + t);
            t.printStackTrace(System.err);
            jadx.close();
            System.exit(5);
            return;
        }

        // Handshake: emit port and READY on stdout (single bytes flushed)
        PrintStream out = System.out;
        out.println("PORT=" + actualPort);
        out.println("READY");
        out.flush();
        System.err.println("[jadx-bridge] Listening on http://" + cli.host + ":" + actualPort);

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[jadx-bridge] Shutting down");
            try {
                server.stop();
            } catch (Throwable ignored) {
            }
            try {
                jadx.close();
            } catch (Throwable ignored) {
            }
            latch.countDown();
        }, "bridge-shutdown"));

        // Block forever; SIGTERM from parent triggers the shutdown hook
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static class BridgeArgs {
        String apkPath;
        String host = "127.0.0.1";
        int port = 0;

        static BridgeArgs parse(String[] argv) {
            BridgeArgs a = new BridgeArgs();
            for (int i = 0; i < argv.length; i++) {
                String arg = argv[i];
                switch (arg) {
                    case "--apk":
                    case "-a":
                        if (i + 1 >= argv.length) {
                            return missing(arg);
                        }
                        a.apkPath = argv[++i];
                        break;
                    case "--host":
                        if (i + 1 >= argv.length) {
                            return missing(arg);
                        }
                        a.host = argv[++i];
                        break;
                    case "--port":
                    case "-p":
                        if (i + 1 >= argv.length) {
                            return missing(arg);
                        }
                        try {
                            a.port = Integer.parseInt(argv[++i]);
                        } catch (NumberFormatException e) {
                            System.err.println("[jadx-bridge] Invalid --port: " + argv[i]);
                            return null;
                        }
                        break;
                    case "-h":
                    case "--help":
                        usage();
                        return null;
                    default:
                        System.err.println("[jadx-bridge] Unknown argument: " + arg);
                        usage();
                        return null;
                }
            }
            if (a.apkPath == null || a.apkPath.isEmpty()) {
                System.err.println("[jadx-bridge] Missing required --apk <path>");
                usage();
                return null;
            }
            return a;
        }

        private static BridgeArgs missing(String flag) {
            System.err.println("[jadx-bridge] " + flag + " requires a value");
            return null;
        }

        private static void usage() {
            System.err.println("Usage: java -jar jadx-bridge.jar --apk <path> [--host 127.0.0.1] [--port 0]");
            System.err.println("  --apk PATH    Path to .apk/.dex/.aab/.xapk/.apkm/.jar file");
            System.err.println("  --host HOST   Bind address (default 127.0.0.1)");
            System.err.println("  --port PORT   TCP port (0 = OS-assigned, default 0)");
        }
    }
}
