package com.zin.jadxheadless.mcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Boots the MCP service over the official SDK's <b>stdio</b> transport: JSON-RPC frames on
 * stdin/stdout, all logging on stderr (see {@code simplelogger.properties}). Used when the MCP
 * client (e.g. Claude Code, config {@code "type": "stdio"}) launches and owns this process directly
 * — no Jetty, no port. The client closing stdin terminates the process. Tool handlers run
 * synchronously (jadx access is blocking), exactly as in {@link McpToolServer}.
 */
public final class StdioMcpServer {

	private static final Logger LOG = LoggerFactory.getLogger(StdioMcpServer.class);

	private final List<SyncToolSpecification> tools;
	private final CountDownLatch shutdown = new CountDownLatch(1);

	private McpSyncServer mcp;

	public StdioMcpServer(List<SyncToolSpecification> tools) {
		this.tools = tools;
	}

	public void start() {
		// Same default JSON mapper the SDK's HTTP transport resolves via ServiceLoader (jackson3).
		McpJsonMapper mapper = McpJsonDefaults.getMapper();
		// Wrap stdin so EOF (client closed the pipe) trips the shutdown latch and the JVM exits on
		// its own — important for a multi-GB headless process if the client dies without killing us.
		InputStream in = new FilterInputStream(System.in) {
			@Override
			public int read() throws IOException {
				int b = super.read();
				if (b < 0) {
					shutdown.countDown();
				}
				return b;
			}

			@Override
			public int read(byte[] buf, int off, int len) throws IOException {
				int n = super.read(buf, off, len);
				if (n < 0) {
					shutdown.countDown();
				}
				return n;
			}
		};
		StdioServerTransportProvider transport = new StdioServerTransportProvider(mapper, in, System.out);

		mcp = McpServer.sync(transport)
				.serverInfo("jadx-headless-mcp-v2", "1.1.1")
				.capabilities(McpSchema.ServerCapabilities.builder()
						.tools(true)
						.build())
				.tools(tools)
				.build();

		LOG.info("MCP stdio server ready ({} tools) — JSON-RPC on stdin/stdout, logs on stderr", tools.size());
	}

	/** Block until shutdown is requested (client closes stdin / JVM termination). */
	public void awaitShutdown() throws InterruptedException {
		shutdown.await();
	}

	public void stop() {
		try {
			if (mcp != null) {
				mcp.close();
			}
		} catch (Exception e) {
			LOG.warn("mcp stdio close failed: {}", e.toString());
		}
		shutdown.countDown();
	}
}
