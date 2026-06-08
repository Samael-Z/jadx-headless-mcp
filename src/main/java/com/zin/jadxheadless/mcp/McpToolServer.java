package com.zin.jadxheadless.mcp;

import java.util.List;

import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Boots the single-process MCP service: an embedded Jetty serving the official SDK's
 * <b>Streamable HTTP</b> transport (NOT the deprecated SSE), bound to localhost with no auth (D1).
 * The transport provider IS the servlet; tool handlers run synchronously (jadx access is blocking).
 */
public final class McpToolServer {

	private static final Logger LOG = LoggerFactory.getLogger(McpToolServer.class);

	private final String host;
	private final int port;
	private final List<SyncToolSpecification> tools;

	private Server jetty;
	private McpSyncServer mcp;

	public McpToolServer(String host, int port, List<SyncToolSpecification> tools) {
		this.host = host;
		this.port = port;
		this.tools = tools;
	}

	public void start() throws Exception {
		HttpServletStreamableServerTransportProvider transport =
				HttpServletStreamableServerTransportProvider.builder()
						.mcpEndpoint("/mcp")
						.build();

		mcp = McpServer.sync(transport)
				.serverInfo("jadx-headless-mcp-v2", "1.0.0")
				.capabilities(McpSchema.ServerCapabilities.builder()
						.tools(true)
						.build())
				.tools(tools)
				.build();

		jetty = new Server();
		ServerConnector connector = new ServerConnector(jetty);
		connector.setHost(host);
		connector.setPort(port);
		jetty.addConnector(connector);

		ServletContextHandler ctx = new ServletContextHandler(ServletContextHandler.SESSIONS);
		ctx.setContextPath("/");
		ctx.addServlet(new ServletHolder(transport), "/mcp/*");
		jetty.setHandler(ctx);

		jetty.start();
		LOG.info("MCP Streamable HTTP server: http://{}:{}/mcp  ({} tools)", host, port, tools.size());
		if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
			LOG.warn("SECURITY: bound to non-localhost {} with NO AUTH — plain HTTP exposes rename_* and "
					+ "decompiled source to anyone on the network. Prefer 127.0.0.1 + an SSH tunnel.", host);
		}
	}

	public void join() throws InterruptedException {
		if (jetty != null) {
			jetty.join();
		}
	}

	public void stop() {
		try {
			if (mcp != null) {
				mcp.close();
			}
		} catch (Exception e) {
			LOG.warn("mcp close failed: {}", e.toString());
		}
		try {
			if (jetty != null) {
				jetty.stop();
			}
		} catch (Exception e) {
			LOG.warn("jetty stop failed: {}", e.toString());
		}
	}
}
