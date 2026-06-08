package com.zin.jadxheadless.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zin.jadxheadless.util.Json;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Boilerplate for declaring MCP tools against the official Java SDK (v1.1.x). A {@link Handler} takes
 * the parsed argument map and returns any object graph; {@link #tool} serializes it to a JSON
 * {@code TextContent} and turns thrown exceptions into an {@code isError} result, so each handler stays
 * focused on the jadx work.
 */
public final class Tools {

	private static final Logger LOG = LoggerFactory.getLogger(Tools.class);

	private Tools() {
	}

	/** A tool body: argument map in, result object out (serialized to JSON). May throw. */
	@FunctionalInterface
	public interface Handler {
		Object handle(java.util.Map<String, Object> args) throws Exception;
	}

	/**
	 * Build a synchronous tool spec.
	 *
	 * @param name        MCP tool name
	 * @param description LLM-facing description (when to use it, arg semantics)
	 * @param schemaJson  JSON-Schema string for the input object
	 * @param handler     the implementation
	 */
	public static SyncToolSpecification tool(String name, String description, String schemaJson, Handler handler) {
		McpSchema.Tool tool = McpSchema.Tool.builder()
				.name(name)
				.description(description)
				.inputSchema(McpJsonDefaults.getMapper(), schemaJson)
				.build();
		return SyncToolSpecification.builder()
				.tool(tool)
				.callHandler((exchange, request) -> {
					try {
						Object result = handler.handle(request.arguments());
						return McpSchema.CallToolResult.builder()
								.addTextContent(Json.write(result))
								.isError(false)
								.build();
					} catch (Exception e) {
						LOG.warn("tool {} failed: {}", name, e.toString());
						String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
						return McpSchema.CallToolResult.builder()
								.addTextContent(Json.write(Json.map("error", msg, "tool", name)))
								.isError(true)
								.build();
					}
				})
				.build();
	}

	// ---- small JSON-Schema builders (kept as strings; the SDK parses them) ----

	/** An object schema with no required properties (tools that take no/optional args). */
	public static String schemaObject(String propertiesJson) {
		return "{\"type\":\"object\",\"properties\":{" + (propertiesJson == null ? "" : propertiesJson) + "}}";
	}

	/** A string property fragment: {@code "name":{"type":"string","description":"..."}}. */
	public static String strProp(String name, String desc) {
		return "\"" + name + "\":{\"type\":\"string\",\"description\":\"" + esc(desc) + "\"}";
	}

	public static String intProp(String name, String desc) {
		return "\"" + name + "\":{\"type\":\"integer\",\"description\":\"" + esc(desc) + "\"}";
	}

	public static String boolProp(String name, String desc) {
		return "\"" + name + "\":{\"type\":\"boolean\",\"description\":\"" + esc(desc) + "\"}";
	}

	/** Object schema with the given properties and required names. */
	public static String schema(String propertiesJson, String... required) {
		StringBuilder sb = new StringBuilder("{\"type\":\"object\",\"properties\":{");
		sb.append(propertiesJson == null ? "" : propertiesJson).append("}");
		if (required != null && required.length > 0) {
			sb.append(",\"required\":[");
			for (int i = 0; i < required.length; i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append('"').append(required[i]).append('"');
			}
			sb.append("]");
		}
		sb.append("}");
		return sb.toString();
	}

	private static String esc(String s) {
		return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
