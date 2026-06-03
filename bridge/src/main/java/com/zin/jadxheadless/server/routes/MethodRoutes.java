package com.zin.jadxheadless.server.routes;

import com.zin.jadxheadless.server.BridgeContext;
import com.zin.jadxheadless.util.Errors;
import com.zin.jadxheadless.util.Scan;
import com.zin.jadxheadless.util.Scan.ScanResult;
import io.javalin.http.Context;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class MethodRoutes {

    private static final Logger logger = LoggerFactory.getLogger(MethodRoutes.class);

    /**
     * Wall-clock budget for the full-corpus method-name scans. Without a bound these
     * routes scanned every one of 100k+ classes' method lists; a no-class lookup ran
     * single-threaded for minutes and — because an aborted HTTP client does not cancel
     * the in-flight work — wedged the (CPU-saturating) bridge so EVERY later request
     * (even /health) timed out behind it. Now bounded: see handleSearchMethod / handleMethodByName.
     */
    private static final long DEFAULT_SEARCH_TIMEOUT_MS = 25_000;
    /** Default cap on collected matches for the no-class method-by-name path (decompiles each match). */
    private static final int DEFAULT_METHOD_MATCH_CAP = 50;

    private final BridgeContext context;

    public MethodRoutes(BridgeContext context) {
        this.context = context;
    }

    /**
     * Returns every method matching the given name. Java has overloading, so this
     * may return multiple entries (same name, different descriptors). Callers that
     * need a specific one can disambiguate with the `descriptor` field.
     *
     * Matching is CASE-SENSITIVE — `getFoo` and `getfoo` are different methods.
     *
     * <p>With {@code class_name} this is an O(1) index lookup (the fast, common path).
     * Without it, a bounded parallel scan over all classes (deadline + match cap),
     * so it can never wedge the bridge.
     */
    public void handleMethodByName(Context ctx) {
        String methodName = ctx.queryParam("method_name");
        if (methodName == null || methodName.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter 'method_name'", logger);
            return;
        }
        String className = ctx.queryParam("class_name");
        String descriptor = ctx.queryParam("descriptor");
        boolean timedOut = false;

        try {
            List<Map<String, Object>> overloads = new ArrayList<>();

            if (className != null && !className.isEmpty()) {
                // Fast path: resolve the class in O(1). Single class -> include decompiled code.
                JavaClass cls = context.findClassByFqn(className);
                if (cls != null) {
                    collectOverloads(cls, methodName, descriptor, overloads, true);
                }
            } else {
                // No class_name => DISCOVERY over the jadx model: a bounded parallel scan for classes
                // declaring a method named EXACTLY methodName (no decompile -> stays fast even for a
                // common name). Returns just the LIST OF CLASSES; caller re-queries with class_name=
                // <one of these> for full overloads + code (O(1)). Bounded by deadline + cap.
                String packageFilter = ctx.queryParam("package");
                boolean filterPkg = isValidPackageFilter(packageFilter);
                long timeoutMs = parseLong(ctx, "timeout_ms", DEFAULT_SEARCH_TIMEOUT_MS);
                int offset = parseInt(ctx, "offset", 0);
                int count = parseInt(ctx, "count", 0);
                int cap = count > 0 ? offset + count : DEFAULT_METHOD_MATCH_CAP;
                final String fDesc = descriptor;
                ScanResult<String> sr = Scan.boundedScan(context.getClassesWithInners(), timeoutMs, cap, cls -> {
                    if (filterPkg && !matchesPackageFilter(cls, packageFilter)) return null;
                    for (JavaMethod m : cls.getMethods()) {
                        if (!m.getName().equals(methodName)) continue;
                        if (fDesc != null && !fDesc.isEmpty()
                                && !ClassRoutes.safeDescriptor(m).equals(fDesc)) continue;
                        return cls.getFullName();
                    }
                    return null;
                });
                List<String> classes = dedup(sr.hits);
                int total = classes.size();
                int from = Math.min(Math.max(offset, 0), total);
                int to = count <= 0 ? total : Math.min(from + count, total);
                Map<String, Object> out = new HashMap<>();
                out.put("query", methodName);
                out.put("classes", new ArrayList<>(classes.subList(from, to)));
                out.put("count", to - from);
                out.put("total", total);
                out.put("offset", from);
                out.put("has_more", to < total || sr.timedOut || sr.capped);
                out.put("scanned", sr.scanned);
                out.put("total_classes", sr.total);
                if (sr.timedOut) {
                    out.put("timed_out", true);
                    out.put("note", "Scan hit the " + timeoutMs + "ms budget; results partial. "
                            + "Pass class_name for an exact O(1) lookup, or raise ?timeout_ms=.");
                }
                out.put("hint", "Classes declaring a method named '" + methodName
                        + "'. Re-query with class_name=<one of these> for full overloads + code.");
                ctx.json(out);
                return;
            }

            if (overloads.isEmpty()) {
                Errors.send(ctx, 404, "Method not found: " + methodName
                        + (className != null && !className.isEmpty() ? " in " + className : ""), logger);
                return;
            }

            // Backward-compatible: single match -> top-level fields (v0.3.0 shape); else `overloads`.
            if (overloads.size() == 1 && !timedOut) {
                ctx.json(overloads.get(0));
            } else {
                Map<String, Object> out = new HashMap<>();
                out.put("query", methodName);
                out.put("class_name", className);
                out.put("overload_count", overloads.size());
                out.put("overloads", overloads);
                if (timedOut) {
                    out.put("timed_out", true);
                    out.put("note", "Scan hit the time budget; results partial. Pass class_name for an exact O(1) lookup, or raise ?timeout_ms=.");
                } else {
                    out.put("hint", "Multiple overloads found. Pass `descriptor` (e.g. "
                            + overloads.get(0).get("descriptor") + ") to disambiguate.");
                }
                ctx.json(out);
            }
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to fetch method: " + e.getMessage(), e, logger);
        }
    }

    private void collectOverloads(JavaClass cls, String methodName, String descriptor,
                                  List<Map<String, Object>> out, boolean includeCode) {
        for (JavaMethod m : cls.getMethods()) {
            if (!m.getName().equals(methodName)) continue;
            String desc = ClassRoutes.safeDescriptor(m);
            if (descriptor != null && !descriptor.isEmpty() && !desc.equals(descriptor)) continue;
            out.add(buildMethodResult(cls, m, desc, includeCode));
        }
    }

    /**
     * Find classes that declare a method whose name contains the query (case-insensitive), via a
     * bounded parallel scan over the jadx model (getMethods() — no decompile). Bounded: deadline +
     * early-cap + pagination, with timed_out/scanned metadata — so it returns in O(timeout) instead
     * of running unbounded and wedging the serial bridge.
     */
    public void handleSearchMethod(Context ctx) {
        String methodName = ctx.queryParam("method_name");
        if (methodName == null || methodName.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter 'method_name'", logger);
            return;
        }
        String packageFilter = ctx.queryParam("package");
        boolean filterPkg = isValidPackageFilter(packageFilter);
        long timeoutMs = parseLong(ctx, "timeout_ms", DEFAULT_SEARCH_TIMEOUT_MS);
        int offset = parseInt(ctx, "offset", 0);
        int count = parseInt(ctx, "count", parseInt(ctx, "limit", 0));
        int cap = count > 0 ? offset + count : 0;
        try {
            final String term = methodName.toLowerCase();
            List<JavaClass> all = context.getClassesWithInners();
            ScanResult<String> sr = Scan.boundedScan(all, timeoutMs, cap, cls -> {
                if (filterPkg && !matchesPackageFilter(cls, packageFilter)) return null;
                for (JavaMethod m : cls.getMethods()) {
                    if (m.getName().toLowerCase().contains(term)
                            || (m.isConstructor() && cls.getName().toLowerCase().contains(term))) {
                        return cls.getFullName();
                    }
                }
                return null;
            });
            List<String> matches = dedup(sr.hits);
            int total = matches.size();
            int from = Math.min(Math.max(offset, 0), total);
            int to = count <= 0 ? total : Math.min(from + count, total);
            List<String> page = new ArrayList<>(matches.subList(from, to));

            Map<String, Object> out = new HashMap<>();
            out.put("query", methodName);
            out.put("matches", page);
            out.put("count", page.size());
            out.put("offset", from);
            out.put("total", total);
            out.put("returned", page.size());
            out.put("has_more", to < total || sr.timedOut || sr.capped);
            out.put("scanned", sr.scanned);
            out.put("total_classes", sr.total);
            out.put("timed_out", sr.timedOut);
            if (sr.timedOut) {
                out.put("note", "Scan hit the " + timeoutMs + "ms budget after "
                        + sr.scanned + "/" + sr.total + " classes; results partial. Raise ?timeout_ms= or page with ?offset=.");
            }
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Search failed: " + e.getMessage(), e, logger);
        }
    }

    private Map<String, Object> buildMethodResult(JavaClass cls, JavaMethod m, String descriptor, boolean includeCode) {
        Map<String, Object> out = new HashMap<>();
        out.put("class_name", cls.getFullName());
        out.put("method_name", m.getName());
        out.put("full_name", cls.getFullName() + "." + m.getName());
        out.put("return_type", String.valueOf(m.getReturnType()));
        out.put("is_constructor", m.isConstructor());
        out.put("descriptor", descriptor);
        out.put("declaration", String.valueOf(m.getCodeNodeRef()));
        // Decompiling (getCodeStr) is ~1s/method — skip it for multi-match DISCOVERY (no class_name)
        // so a common name over hundreds of classes stays fast. Caller re-queries with class_name for code.
        if (includeCode) {
            try {
                out.put("code", m.getCodeStr());
            } catch (Exception e) {
                out.put("code", "// Error retrieving code: " + e.getMessage());
            }
        }
        return out;
    }

    /** Order-preserving de-dup of scan hits (a class can match on multiple methods). */
    private static List<String> dedup(List<String> hits) {
        return new ArrayList<>(new LinkedHashSet<>(hits));
    }

    private static final Pattern OBFUSCATED_PACKAGE_PATTERN = Pattern.compile("^p\\d+$");

    /** Mirror of ClassRoutes' package-filter validation: reject defpackage / obfuscated p\d+ roots. */
    private static boolean isValidPackageFilter(String filter) {
        if (filter == null || filter.trim().isEmpty()) return false;
        if (filter.equals("defpackage")) return false;
        String first = filter.split("\\.")[0];
        return !OBFUSCATED_PACKAGE_PATTERN.matcher(first).matches();
    }

    private static boolean matchesPackageFilter(JavaClass cls, String filter) {
        if (filter == null || filter.trim().isEmpty()) return true;
        String full = cls.getFullName();
        return full.startsWith(filter + ".") || full.equals(filter);
    }

    private static long parseLong(Context ctx, String key, long fallback) {
        String raw = ctx.queryParam(key);
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            long v = Long.parseLong(raw);
            return v <= 0 ? fallback : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(Context ctx, String key, int fallback) {
        String raw = ctx.queryParam(key);
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            int v = Integer.parseInt(raw);
            return v < 0 ? fallback : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
