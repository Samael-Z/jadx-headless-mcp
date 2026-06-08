package com.zin.jadxheadless.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Offset/limit pagination for tool results. Large APKs (Douyin: ~319k classes) make unbounded
 * listings useless to an LLM and slow to serialize, so every enumeration tool paginates.
 */
public final class Pagination {

	public static final int DEFAULT_LIMIT = 200;
	public static final int MAX_LIMIT = 2000;

	private Pagination() {
	}

	/** Parse an int argument with a default and clamping to {@code [min, max]}. */
	public static int intArg(Map<String, Object> args, String key, int def, int min, int max) {
		Object v = args == null ? null : args.get(key);
		if (v == null) {
			return def;
		}
		try {
			int n = (v instanceof Number) ? ((Number) v).intValue() : Integer.parseInt(v.toString().trim());
			return Math.max(min, Math.min(max, n));
		} catch (Exception e) {
			return def;
		}
	}

	/**
	 * Slice {@code items[offset, offset+limit)} into a paginated envelope with {@code total},
	 * {@code offset}, {@code limit}, {@code returned}, {@code has_more} and the page under {@code key}.
	 */
	public static <T> Map<String, Object> page(List<T> items, Map<String, Object> args, String key) {
		int total = items.size();
		int offset = intArg(args, "offset", 0, 0, Integer.MAX_VALUE);
		int limit = intArg(args, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
		if (offset > total) {
			offset = total;
		}
		int end = Math.min(total, offset + limit);
		List<T> slice = new ArrayList<>(items.subList(offset, end));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("total", total);
		out.put("offset", offset);
		out.put("limit", limit);
		out.put("returned", slice.size());
		out.put("has_more", end < total);
		out.put(key, slice);
		return out;
	}
}
