package com.zin.jadxheadless.util;

import io.javalin.http.Context;

/**
 * Bounds the size of large text payloads (decompiled source, smali, manifest, resource
 * files) so a single huge class/manifest can't blow past an MCP client's response/token
 * limit. Returns the (possibly truncated) text; when truncated, appends a clear marker so
 * the caller knows there is more and how to get it.
 */
public final class TextUtil {

    private TextUtil() {
    }

    /**
     * Default cap — generous enough for almost every real class, small enough to stay well
     * under typical MCP client limits. Override per request with {@code ?max_chars=}.
     * {@code max_chars=0} disables the cap (caller explicitly wants everything).
     */
    public static final int DEFAULT_MAX_CHARS = 120_000;

    /** Resolve the effective char cap from {@code ?max_chars=} (default {@link #DEFAULT_MAX_CHARS}, 0 = unlimited). */
    public static int maxChars(Context ctx) {
        String raw = ctx.queryParam("max_chars");
        if (raw == null || raw.isEmpty()) return DEFAULT_MAX_CHARS;
        try {
            int v = Integer.parseInt(raw);
            return v < 0 ? DEFAULT_MAX_CHARS : v; // 0 allowed = unlimited
        } catch (NumberFormatException e) {
            return DEFAULT_MAX_CHARS;
        }
    }

    /**
     * Truncate {@code text} to {@code maxChars} (0 = no limit), appending a marker noting the
     * original length and how to fetch the rest. Returns the original when within the cap.
     */
    public static String cap(String text, int maxChars) {
        if (text == null || maxChars <= 0 || text.length() <= maxChars) return text;
        int shown = Math.max(0, maxChars);
        return text.substring(0, shown)
                + "\n\n/* [truncated by jadx-headless-mcp: showing " + shown + " of " + text.length()
                + " chars. Re-request with a larger ?max_chars= (or max_chars=0 for all). */";
    }
}
