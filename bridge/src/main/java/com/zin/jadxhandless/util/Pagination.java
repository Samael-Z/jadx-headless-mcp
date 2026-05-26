package com.zin.jadxhandless.util;

import io.javalin.http.Context;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Cursor-free offset/count pagination shared by every list-returning route.
 * Compatible with the Python jadx-mcp-server query shape (offset, count / limit).
 *
 * Returned envelope:
 * <pre>
 * {
 *   "type":     "&lt;label&gt;",         // free-form, helps callers tell collections apart
 *   "offset":   int,
 *   "count":    int,
 *   "total":    int,                  // total before paging
 *   "returned": int,                  // size of items
 *   "has_more": boolean,
 *   "items":    [ T... ]
 * }
 * </pre>
 */
public final class Pagination {

    private Pagination() {
    }

    public static <T, R> Map<String, Object> paginate(
            Context ctx,
            List<T> source,
            String type,
            String itemsKey,
            Function<T, R> mapper) {
        int offset = parseInt(ctx, "offset", 0);
        int count = parseInt(ctx, "count", parseInt(ctx, "limit", 0));
        return paginate(source, type, itemsKey, offset, count, mapper);
    }

    public static <T, R> Map<String, Object> paginate(
            List<T> source,
            String type,
            String itemsKey,
            int offset,
            int count,
            Function<T, R> mapper) {
        if (offset < 0) offset = 0;
        int total = source.size();
        int from = Math.min(offset, total);
        int to = count <= 0 ? total : Math.min(from + count, total);
        List<R> items = new ArrayList<>(Math.max(0, to - from));
        for (int i = from; i < to; i++) {
            items.add(mapper.apply(source.get(i)));
        }
        Map<String, Object> out = new HashMap<>();
        out.put("type", type);
        out.put("offset", from);
        out.put("count", count);
        out.put("total", total);
        out.put("returned", items.size());
        out.put("has_more", to < total);
        out.put(itemsKey, items);
        return out;
    }

    private static int parseInt(Context ctx, String key, int fallback) {
        String raw = ctx.queryParam(key);
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
