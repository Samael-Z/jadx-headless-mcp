package com.zin.jadxheadless.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON writer for tool results. Handles the {@link Map}/{@link List}/scalar
 * structures the tool handlers build. We intentionally avoid pulling in a Jackson of our own — the MCP
 * SDK ships Jackson 3 with a matching jackson-annotations, and a second Jackson on the classpath caused
 * an annotations-version clash. This keeps our output serialization fully decoupled from the SDK's.
 */
public final class Json {

	private Json() {
	}

	/** Ordered map literal builder: {@code map("a", 1, "b", 2)}. */
	public static Map<String, Object> map(Object... kv) {
		LinkedHashMap<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i + 1 < kv.length; i += 2) {
			m.put(String.valueOf(kv[i]), kv[i + 1]);
		}
		return m;
	}

	/** Serialize any Map/List/String/Number/Boolean/null graph to a JSON string. */
	public static String write(Object value) {
		StringBuilder sb = new StringBuilder(256);
		write(sb, value);
		return sb.toString();
	}

	@SuppressWarnings("unchecked")
	private static void write(StringBuilder sb, Object v) {
		if (v == null) {
			sb.append("null");
		} else if (v instanceof String) {
			writeString(sb, (String) v);
		} else if (v instanceof Number || v instanceof Boolean) {
			sb.append(v);
		} else if (v instanceof Map) {
			sb.append('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				writeString(sb, String.valueOf(e.getKey()));
				sb.append(':');
				write(sb, e.getValue());
			}
			sb.append('}');
		} else if (v instanceof List) {
			sb.append('[');
			boolean first = true;
			for (Object e : (List<Object>) v) {
				if (!first) {
					sb.append(',');
				}
				first = false;
				write(sb, e);
			}
			sb.append(']');
		} else if (v instanceof Object[]) {
			write(sb, java.util.Arrays.asList((Object[]) v));
		} else {
			writeString(sb, v.toString());
		}
	}

	private static void writeString(StringBuilder sb, String s) {
		sb.append('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
				case '"':
					sb.append("\\\"");
					break;
				case '\\':
					sb.append("\\\\");
					break;
				case '\n':
					sb.append("\\n");
					break;
				case '\r':
					sb.append("\\r");
					break;
				case '\t':
					sb.append("\\t");
					break;
				case '\b':
					sb.append("\\b");
					break;
				case '\f':
					sb.append("\\f");
					break;
				default:
					if (c < 0x20) {
						sb.append(String.format("\\u%04x", (int) c));
					} else {
						sb.append(c);
					}
			}
		}
		sb.append('"');
	}
}
