package com.zin.jadxheadless.server.routes;

import com.zin.jadxheadless.server.BridgeContext;
import com.zin.jadxheadless.util.Errors;
import com.zin.jadxheadless.util.Pagination;
import com.zin.jadxheadless.util.TextUtil;
import io.javalin.http.Context;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.ResourceFile;
import jadx.core.utils.android.AndroidManifestParser;
import jadx.core.utils.android.AppAttribute;
import jadx.core.utils.android.ApplicationParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ClassRoutes {

    private static final Logger logger = LoggerFactory.getLogger(ClassRoutes.class);
    private static final Pattern OBFUSCATED_PACKAGE_PATTERN = Pattern.compile("^p\\d+$");

    /**
     * Wall-clock budget for full-corpus scans (find-string-usages and code/comment
     * keyword search). On a 100k+ class APK these scans decompile/generate-smali for
     * every class and can run for minutes. Bounding them keeps the bridge responsive:
     * a scan returns whatever it found within the budget plus {@code timed_out:true},
     * instead of monopolizing the (serial) HTTP worker indefinitely. Override per
     * request with {@code ?timeout_ms=}.
     */
    private static final long DEFAULT_SEARCH_TIMEOUT_MS = 25_000;

    /** Hard cap on classes fetched per {@code /class-sources} batch — keeps the combined response bounded. */
    private static final int MAX_BATCH_CLASSES = 200;

    /** Search modes for the keyword search route. */
    public enum SearchLocation { CLASS_NAME, METHOD_NAME, FIELD_NAME, CODE, COMMENT }

    private static final Map<String, SearchLocation> SEARCH_LOCATIONS = new HashMap<>();
    static {
        SEARCH_LOCATIONS.put("class", SearchLocation.CLASS_NAME);
        SEARCH_LOCATIONS.put("class_name", SearchLocation.CLASS_NAME);
        SEARCH_LOCATIONS.put("method", SearchLocation.METHOD_NAME);
        SEARCH_LOCATIONS.put("method_name", SearchLocation.METHOD_NAME);
        SEARCH_LOCATIONS.put("field", SearchLocation.FIELD_NAME);
        SEARCH_LOCATIONS.put("field_name", SearchLocation.FIELD_NAME);
        SEARCH_LOCATIONS.put("code", SearchLocation.CODE);
        SEARCH_LOCATIONS.put("comment", SearchLocation.COMMENT);
    }

    // Library prefixes for the is_likely_library heuristic in package-tree.
    private static final String[] LIBRARY_PREFIXES = {
            "androidx.", "android.support.", "com.google.", "com.android.",
            "kotlin.", "kotlinx.", "okhttp3.", "okio.", "retrofit2.",
            "com.squareup.", "io.reactivex.", "rx.", "dagger.",
            "com.facebook.", "com.amazonaws.", "org.apache.", "org.json.",
            "com.fasterxml.", "org.slf4j.", "javax.", "junit.",
            "io.netty.", "com.bumptech.glide.", "org.greenrobot.",
            "com.airbnb.", "io.realm.", "bolts.", "butterknife."
    };

    private final BridgeContext context;

    /** Cached package histogram (deterministic per APK) — avoids re-scanning 100k+ classes per call. */
    private volatile List<Map.Entry<String, Integer>> packageTreeCache;
    private volatile int packageTreeTotalClasses;

    public ClassRoutes(BridgeContext context) {
        this.context = context;
    }

    public void handleAllClasses(Context ctx) {
        try {
            List<JavaClass> classes = context.getClassesWithInners();
            ctx.json(Pagination.paginate(ctx, classes, "class-list", "classes", JavaClass::getFullName));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to enumerate classes: " + e.getMessage(), e, logger);
        }
    }

    public void handleClassSource(Context ctx) {
        String className = requireClassName(ctx);
        if (className == null) return;
        try {
            JavaClass cls = findClass(className);
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            ctx.result(TextUtil.cap(decompiledCode(cls), TextUtil.maxChars(ctx)));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to decompile class: " + e.getMessage(), e, logger);
        }
    }

    /**
     * Batch sibling of {@link #handleClassSource}: decompile MANY classes in ONE request.
     * {@code class_names} is a comma-separated FQN list (inner classes use {@code $}). Cuts the
     * per-class round-trip overhead when the caller already knows the set it wants (e.g. after a
     * search or xrefs lookup). Classes are decompiled in parallel and each is capped independently
     * by {@code max_chars}. Unknown names are reported in {@code not_found} rather than failing the
     * whole batch.
     */
    public void handleClassSources(Context ctx) {
        String namesParam = ctx.queryParam("class_names");
        if (namesParam == null || namesParam.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter 'class_names' (comma-separated FQNs)", logger);
            return;
        }
        final int maxChars = TextUtil.maxChars(ctx);
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (String s : namesParam.split(",")) {
            String n = s.trim();
            if (!n.isEmpty()) names.add(n);
        }
        boolean batchCapped = names.size() > MAX_BATCH_CLASSES;
        if (batchCapped) {
            names = names.stream().limit(MAX_BATCH_CLASSES)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        final List<String> ordered = new ArrayList<>(names);
        try {
            // Parallel decompile: each class is ~1s on first touch; the shared cache makes repeats free.
            // toConcurrentMap is safe — `ordered` is deduped (LinkedHashSet) so keys are unique.
            Map<String, Map<String, Object>> byName = ordered.parallelStream()
                    .collect(Collectors.toConcurrentMap(name -> name, name -> {
                        Map<String, Object> row = new HashMap<>();
                        JavaClass cls = findClass(name);
                        if (cls == null) {
                            row.put("not_found", true);
                            return row;
                        }
                        row.put("name", cls.getFullName());
                        try {
                            String code = decompiledCode(cls);
                            int total = code == null ? 0 : code.length();
                            row.put("content", TextUtil.cap(code, maxChars));
                            if (maxChars > 0 && total > maxChars) {
                                row.put("truncated", true);
                                row.put("total_chars", total);
                            }
                        } catch (Exception e) {
                            row.put("content", "// Error decompiling: " + e.getMessage());
                            row.put("error", true);
                        }
                        return row;
                    }));
            List<Map<String, Object>> classes = new ArrayList<>();
            List<String> notFound = new ArrayList<>();
            for (String name : ordered) {
                Map<String, Object> row = byName.get(name);
                if (row == null) continue;
                if (Boolean.TRUE.equals(row.get("not_found"))) {
                    notFound.add(name);
                } else {
                    classes.add(row);
                }
            }
            Map<String, Object> out = new HashMap<>();
            out.put("type", "class-sources");
            out.put("requested", ordered.size());
            out.put("returned", classes.size());
            out.put("classes", classes);
            if (!notFound.isEmpty()) out.put("not_found", notFound);
            if (batchCapped) {
                out.put("batch_capped", true);
                out.put("note", "Batch limited to the first " + MAX_BATCH_CLASSES
                        + " distinct names; request the rest in another call.");
            }
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to batch-fetch class sources: " + e.getMessage(), e, logger);
        }
    }

    public void handleMethodsOfClass(Context ctx) {
        String className = requireClassName(ctx);
        if (className == null) return;
        try {
            JavaClass cls = findClass(className);
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            List<Map<String, Object>> methods = new ArrayList<>();
            for (JavaMethod m : cls.getMethods()) {
                Map<String, Object> row = new HashMap<>();
                row.put("name", m.getName());
                row.put("full_name", cls.getFullName() + "." + m.getName());
                row.put("return_type", String.valueOf(m.getReturnType()));
                // String.valueOf — not the raw AccessInfo object. The AccessInfo has a
                // self-referential `visibility` field that trips Jackson's max nesting
                // depth (1000) and 500s the request on certain classes (e.g. annotations
                // or classes with deep modifier hierarchies). The stringified form
                // ("public static" etc.) is what consumers actually want anyway.
                row.put("access_flags", String.valueOf(m.getAccessFlags()));
                row.put("is_constructor", m.isConstructor());
                // Tee up the method descriptor (parameter types) — without it, overloaded
                // methods are indistinguishable. Callers use this when there are multiple
                // methods with the same name.
                row.put("descriptor", safeDescriptor(m));
                methods.add(row);
            }
            ctx.json(Map.of("class", cls.getFullName(), "methods", methods));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to list methods: " + e.getMessage(), e, logger);
        }
    }

    public void handleFieldsOfClass(Context ctx) {
        String className = requireClassName(ctx);
        if (className == null) return;
        try {
            JavaClass cls = findClass(className);
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            List<Map<String, Object>> fields = new ArrayList<>();
            for (JavaField f : cls.getFields()) {
                Map<String, Object> row = new HashMap<>();
                row.put("name", f.getName());
                row.put("type", String.valueOf(f.getType()));
                // String.valueOf — same AccessInfo cycle gotcha as in handleMethodsOfClass.
                row.put("access_flags", String.valueOf(f.getAccessFlags()));
                fields.add(row);
            }
            ctx.json(Map.of("class", cls.getFullName(), "fields", fields));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to list fields: " + e.getMessage(), e, logger);
        }
    }

    public void handleSmaliOfClass(Context ctx) {
        String className = requireClassName(ctx);
        if (className == null) return;
        try {
            JavaClass cls = findClass(className);
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            ctx.result(TextUtil.cap(cls.getSmali(), TextUtil.maxChars(ctx)));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to get smali: " + e.getMessage(), e, logger);
        }
    }

    public void handleMainActivity(Context ctx) {
        try {
            JadxDecompiler jadx = context.jadx();
            ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(jadx.getResources());
            if (manifestRes == null) {
                Errors.send(ctx, 404, "AndroidManifest.xml not found", logger);
                return;
            }
            AndroidManifestParser parser = new AndroidManifestParser(
                    manifestRes,
                    EnumSet.of(AppAttribute.MAIN_ACTIVITY),
                    jadx.getArgs().getSecurity());
            if (!parser.isManifestFound()) {
                Errors.send(ctx, 404, "AndroidManifest.xml parse failed", logger);
                return;
            }
            ApplicationParams results = parser.parse();
            if (results.getMainActivity() == null) {
                Errors.send(ctx, 404, "Main activity not declared in manifest", logger);
                return;
            }
            JavaClass main = results.getMainActivityJavaClass(jadx);
            if (main == null) {
                Errors.send(ctx, 404, "Main activity class not found: " + results.getMainActivity(), logger);
                return;
            }
            // Decompile inside its own try — some classes (especially obfuscated /
            // synthetic launchers) can throw during decompile; we still want to
            // return the FQN even if the source is unavailable.
            String code;
            try {
                code = decompiledCode(main);
            } catch (Throwable t) {
                code = "// Failed to decompile " + main.getFullName() + ": " + t.getMessage();
            }
            ctx.json(Map.of(
                    "name", main.getFullName(),
                    "type", "code/java",
                    "content", TextUtil.cap(code, TextUtil.maxChars(ctx))));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to resolve main activity: " + e.getMessage(), e, logger);
        }
    }

    public void handleMainApplicationClassesNames(Context ctx) {
        try {
            String pkg = manifestPackageName();
            if (pkg == null) {
                Errors.send(ctx, 404, "Package name missing in AndroidManifest.xml", logger);
                return;
            }
            List<String> names = context.getClassesWithInners().stream()
                    .filter(c -> c.getFullName().startsWith(pkg + ".") || c.getFullName().equals(pkg))
                    .map(JavaClass::getFullName)
                    .collect(Collectors.toList());
            // Paginate with a SANE DEFAULT page (not "all"): a real app has 80k+ such classes;
            // the un-paginated list was a 6MB+ response that overflowed MCP clients. The generic
            // paginate default is count=0=all, so set an explicit default here. ?count=0 for all.
            int offset = parsePositive(ctx, "offset", 0);
            int count = parsePositive(ctx, "count", 500);
            Map<String, Object> out = Pagination.paginate(names, "application-class-names", "classes", offset, count, x -> x);
            out.put("package", pkg);
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to list main app classes: " + e.getMessage(), e, logger);
        }
    }

    public void handleMainApplicationClassesCode(Context ctx) {
        try {
            String pkg = manifestPackageName();
            if (pkg == null) {
                Errors.send(ctx, 404, "Package name missing in AndroidManifest.xml", logger);
                return;
            }
            List<JavaClass> filtered = context.getClassesWithInners().stream()
                    .filter(c -> c.getFullName().startsWith(pkg + ".") || c.getFullName().equals(pkg))
                    .collect(Collectors.toList());

            ctx.json(Pagination.paginate(ctx, filtered, "application-classes", "classes", cls -> {
                Map<String, Object> row = new HashMap<>();
                row.put("name", cls.getFullName());
                row.put("type", "code/java");
                try {
                    row.put("content", decompiledCode(cls));
                } catch (Exception e) {
                    row.put("content", "// Error decompiling: " + e.getMessage());
                }
                return row;
            }));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to fetch main app classes code: " + e.getMessage(), e, logger);
        }
    }

    public void handleSearchClassesByKeyword(Context ctx) {
        String term = ctx.queryParam("search_term");
        if (term == null || term.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter 'search_term'", logger);
            return;
        }
        String packageFilter = ctx.queryParam("package");
        Set<SearchLocation> locations = parseSearchLocations(ctx.queryParam("search_in"));
        long timeoutMs = parsePositiveLong(ctx, "timeout_ms", DEFAULT_SEARCH_TIMEOUT_MS);
        int offset = parsePositive(ctx, "offset", 0);
        int count = parsePositive(ctx, "count", parsePositive(ctx, "limit", 0));
        int cap = count > 0 ? offset + count : 0;
        boolean regex = "true".equalsIgnoreCase(ctx.queryParam("regex"));
        boolean caseSensitive = "true".equalsIgnoreCase(ctx.queryParam("case_sensitive"));

        // Unified text matcher used by every location: a regex .find() when regex=true,
        // otherwise a (case-sensitive or -insensitive) substring test. This is what makes
        // search_in=code support `Cipher\.getInstance\("[^"]+"\)`-style queries without a
        // second code path. Compiled once per request (per-class compilation across 100k+
        // classes would itself dominate runtime).
        final java.util.regex.Pattern pat;
        if (regex) {
            try {
                pat = Pattern.compile(term, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
            } catch (java.util.regex.PatternSyntaxException pe) {
                Errors.send(ctx, 400, "Invalid regex '" + term + "': " + pe.getMessage(), logger);
                return;
            }
        } else {
            pat = null;
        }
        final String tCase = term;
        final String tLower = term.toLowerCase();
        final java.util.function.Predicate<String> textMatch = pat != null
                ? s -> s != null && pat.matcher(s).find()
                : (caseSensitive
                        ? s -> s != null && s.contains(tCase)
                        : s -> s != null && s.toLowerCase().contains(tLower));

        try {
            List<JavaClass> all = context.getClassesWithInners();
            boolean filterPkg = isValidPackageFilter(packageFilter);

            Set<JavaClass> matched = new LinkedHashSet<>();
            boolean timedOut = false;
            int scanned = 0;

            for (SearchLocation loc : locations) {
                // METHOD/FIELD name: use the inverted index when eligible (plain case-insensitive
                // substring, no regex) -- built from the same smali pass, so it skips the per-class
                // getMethods()/getFields() live scan. Falls through to live scan if not READY.
                if (!regex && !caseSensitive
                        && (loc == SearchLocation.METHOD_NAME || loc == SearchLocation.FIELD_NAME)) {
                    List<String> idxHit = (loc == SearchLocation.METHOD_NAME)
                            ? context.stringIndex().lookupMethodContains(term, packageFilter, 0, null)
                            : context.stringIndex().lookupFieldContains(term, packageFilter, 0, null);
                    if (idxHit != null) {
                        for (String fqn : idxHit) {
                            JavaClass c = context.findClassByFqn(fqn);
                            if (c != null) matched.add(c);
                        }
                        continue;
                    }
                }
                // CLASS/METHOD/FIELD name matching is cheap string work (no decompile)
                // -- the existing fast path returns in well under a second. CODE/COMMENT
                // decompile every class, so they go through the time-bounded scan.
                if (loc != SearchLocation.CODE && loc != SearchLocation.COMMENT) {
                    matched.addAll(searchIn(all, textMatch, loc, packageFilter, filterPkg));
                    continue;
                }
                final SearchLocation floc = loc;
                ScanResult<JavaClass> sr = boundedScan(all, timeoutMs, cap, c -> {
                    if (filterPkg && !matchesPackageFilter(c, packageFilter)) return null;
                    String code;
                    try {
                        code = decompiledCode(c);
                    } catch (Exception e) {
                        return null;
                    }
                    if (code == null) return null;
                    if (floc == SearchLocation.CODE) {
                        return textMatch.test(code) ? c : null;
                    }
                    return commentMatches(code, textMatch) ? c : null;
                });
                matched.addAll(sr.hits);
                timedOut |= sr.timedOut;
                scanned += sr.scanned;
            }
            Map<String, Object> out = Pagination.paginate(
                    new ArrayList<>(matched), "class-list", "classes", offset, count, JavaClass::getFullName);
            if (regex) out.put("regex", true);
            if (timedOut) {
                out.put("timed_out", true);
                out.put("scanned", scanned);
                out.put("total_classes", all.size());
                out.put("has_more", true);
                out.put("note", "Code/comment scan hit the " + timeoutMs
                        + "ms budget; results are partial. Prefer search_in=class (fast) or narrow the query / raise ?timeout_ms=.");
            }
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Search failed: " + e.getMessage(), e, logger);
        }
    }

    public void handlePackageTree(Context ctx) {
        try {
            List<Map.Entry<String, Integer>> sorted = packageTree();
            int totalClasses = packageTreeTotalClasses;

            // Paginate to keep responses below MCP client token limits — a real-world
            // 100k-class APK has 20k+ packages and the un-paginated response was 2 MB+.
            // Default page size 50 unless caller overrides via ?count=.
            int defaultCount = 50;
            int offset = parsePositive(ctx, "offset", 0);
            int requestedCount = parsePositive(ctx, "count", defaultCount);

            int total = sorted.size();
            int from = Math.min(offset, total);
            int to = requestedCount <= 0 ? total : Math.min(from + requestedCount, total);
            List<Map<String, Object>> page = new ArrayList<>(Math.max(0, to - from));
            for (int i = from; i < to; i++) {
                Map.Entry<String, Integer> e = sorted.get(i);
                Map<String, Object> row = new HashMap<>();
                row.put("name", e.getKey());
                row.put("class_count", e.getValue());
                row.put("is_likely_library", isLikelyLibrary(e.getKey()));
                page.add(row);
            }
            Map<String, Object> out = new HashMap<>();
            out.put("type", "package-tree");
            out.put("total_classes", totalClasses);
            out.put("total_packages", total);
            out.put("offset", from);
            // Mirror `returned` for consistency with Pagination.paginate — avoids
            // the "count:0 returned:5" confusion when the caller asks for all.
            out.put("count", page.size());
            out.put("page_size", requestedCount);
            out.put("returned", page.size());
            out.put("has_more", to < total);
            out.put("packages", page);
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to build package tree: " + e.getMessage(), e, logger);
        }
    }

    /** Compute (once, cached) the package -> class-count histogram, sorted by count desc. */
    private List<Map.Entry<String, Integer>> packageTree() {
        List<Map.Entry<String, Integer>> cached = packageTreeCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (packageTreeCache != null) return packageTreeCache;
            List<JavaClass> all = context.getClassesWithInners();
            Map<String, Integer> counts = new HashMap<>();
            for (JavaClass cls : all) {
                String full = cls.getFullName();
                int dot = full.lastIndexOf('.');
                String pkg = dot > 0 ? full.substring(0, dot) : "(default)";
                counts.merge(pkg, 1, Integer::sum);
            }
            List<Map.Entry<String, Integer>> sorted = counts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .collect(Collectors.toList());
            packageTreeTotalClasses = all.size();
            packageTreeCache = sorted;
            return sorted;
        }
    }

    private static int parsePositive(Context ctx, String key, int fallback) {
        String raw = ctx.queryParam(key);
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            int v = Integer.parseInt(raw);
            return v < 0 ? fallback : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parsePositiveLong(Context ctx, String key, long fallback) {
        String raw = ctx.queryParam(key);
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            long v = Long.parseLong(raw);
            return v <= 0 ? fallback : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Outcome of a {@link #boundedScan}: collected hits plus progress/termination metadata. */
    private static final class ScanResult<T> {
        final List<T> hits;
        final boolean timedOut;
        final boolean capped;
        final int scanned;
        final int total;
        ScanResult(List<T> hits, boolean timedOut, boolean capped, int scanned, int total) {
            this.hits = hits;
            this.timedOut = timedOut;
            this.capped = capped;
            this.scanned = scanned;
            this.total = total;
        }
    }

    /**
     * Run {@code perClass} over every class in parallel, but stop early once either
     * (a) the wall-clock budget {@code timeoutMs} is exceeded, or (b) {@code cap}
     * non-null hits have been collected ({@code cap <= 0} disables the cap).
     *
     * <p>This is the fix for the "search hangs for minutes and monopolizes the
     * single bridge worker" class of problem: callers always get a bounded
     * response. When the scan stops early, {@code timedOut}/{@code capped} say why
     * and {@code scanned} reports how many of {@code total} classes were examined.
     *
     * <p>Note: a parallel stream cannot be hard-cancelled, so after the stop flag
     * trips the remaining elements still get scheduled — but each becomes a cheap
     * volatile-read no-op, so wall-time stays bounded.
     */
    private <T> ScanResult<T> boundedScan(
            List<JavaClass> all, long timeoutMs, int cap,
            java.util.function.Function<JavaClass, T> perClass) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        AtomicBoolean stop = new AtomicBoolean(false);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        AtomicBoolean capped = new AtomicBoolean(false);
        AtomicInteger scanned = new AtomicInteger();
        ConcurrentLinkedQueue<T> hits = new ConcurrentLinkedQueue<>();
        all.parallelStream().forEach(c -> {
            if (stop.get()) return;
            if (System.nanoTime() > deadline) {
                timedOut.set(true);
                stop.set(true);
                return;
            }
            scanned.incrementAndGet();
            T r;
            try {
                r = perClass.apply(c);
            } catch (Throwable t) {
                r = null;
            }
            if (r != null) {
                hits.add(r);
                if (cap > 0 && hits.size() >= cap) {
                    capped.set(true);
                    stop.set(true);
                }
            }
        });
        return new ScanResult<>(new ArrayList<>(hits), timedOut.get(), capped.get(),
                scanned.get(), all.size());
    }

    /**
     * Attach scan progress/termination metadata to a paginated envelope and fix up
     * {@code has_more}: when a scan stopped early (timeout or hit cap) the true total
     * is unknown and larger than what we paged, so {@code has_more} must be true even
     * though {@link Pagination} computed it from the partial result list.
     */
    private static void decorateScan(Map<String, Object> out, ScanResult<?> sr, long timeoutMs) {
        out.put("timed_out", sr.timedOut);
        out.put("scanned", sr.scanned);
        out.put("total_classes", sr.total);
        if (sr.timedOut || sr.capped) {
            out.put("has_more", true);
        }
        if (sr.timedOut) {
            out.put("note", "Scan hit the " + timeoutMs + "ms budget after examining "
                    + sr.scanned + "/" + sr.total + " classes; results are partial. "
                    + "Narrow the query, raise ?timeout_ms=, or page with ?offset=.");
        }
    }

    /**
     * Find DEX string-pool constants ("foo") used in code. Distinct from
     * {@code /search-classes-by-keyword} (which does case-insensitive substring
     * matching across class/method/field/comment names) and {@code /strings}
     * (which is res/values&#42;/strings.xml -- Android string resources, not
     * DEX string constants).
     *
     * <p>Search source -- selectable via the {@code source} query parameter:
     * <ul>
     *   <li><b>smali</b> (default): scans the Dalvik smali listing for
     *       {@code const-string vN, "<literal>"} opcodes. Authoritative,
     *       independent of whether the class is decompilable. The right
     *       choice for finding native-library loads, hardcoded URLs, or
     *       API keys in R8/anti-tamper hardened classes (e.g. ByteDance,
     *       Tencent) where the Java decompile is empty.</li>
     *   <li><b>code</b>: scans jadx-decompiled Java source. Faster on big
     *       APKs because decompile is cached, but returns 0 hits on classes
     *       that jadx refuses to decompile.</li>
     *   <li><b>both</b>: report a class if either source contains the
     *       literal. The {@code matched_in} field tells you which.</li>
     * </ul>
     */
    public void handleFindStringUsages(Context ctx) {
        String literal = ctx.queryParam("literal");
        if (literal == null || literal.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter 'literal'", logger);
            return;
        }
        // For the code path: by default we look for a Java/Kotlin string
        // constant -- i.e. wrapped in double quotes. quoted=false matches the
        // raw substring (useful for regex fragments). The smali path always
        // searches for the quoted form because smali const-string operands
        // are always quoted.
        String quotedParam = ctx.queryParam("quoted");
        boolean quoted = quotedParam == null || !quotedParam.equalsIgnoreCase("false");
        String caseParam = ctx.queryParam("case_sensitive");
        boolean caseSensitive = caseParam == null || !caseParam.equalsIgnoreCase("false");
        String packageFilter = ctx.queryParam("package");
        boolean filterPkg = isValidPackageFilter(packageFilter);
        String sourceParam = ctx.queryParam("source");
        String source = (sourceParam == null || sourceParam.isEmpty())
                ? "smali" : sourceParam.toLowerCase();
        boolean useCode = source.equals("code") || source.equals("both");
        boolean useSmali = source.equals("smali") || source.equals("both");
        if (!useCode && !useSmali) {
            Errors.send(ctx, 400,
                    "Invalid 'source' parameter: expected 'smali' (default), 'code', or 'both'",
                    logger);
            return;
        }

        final String codeNeedle = quoted ? "\"" + literal + "\"" : literal;
        final String codeHaystack = caseSensitive ? codeNeedle : codeNeedle.toLowerCase();
        final String smaliNeedle = "\"" + literal + "\"";
        final String smaliHaystack = caseSensitive ? smaliNeedle : smaliNeedle.toLowerCase();
        final boolean cs = caseSensitive;
        final boolean fSmali = useSmali;
        final boolean fCode = useCode;

        long timeoutMs = parsePositiveLong(ctx, "timeout_ms", DEFAULT_SEARCH_TIMEOUT_MS);
        int offset = parsePositive(ctx, "offset", 0);
        int count = parsePositive(ctx, "count", parsePositive(ctx, "limit", 0));
        // Early-cap: once we have enough hits to satisfy the requested page we
        // can stop scanning the remaining (potentially 100k+) classes. count<=0
        // means "all" -> no cap, and only the deadline bounds the scan.
        int cap = count > 0 ? offset + count : 0;

        try {
            // ---- Fast path: const-string inverted index (space-for-time) ----
            // Eligible only for the exact smali query (source=smali, case-sensitive),
            // which the index reproduces precisely AND with an accurate total. Any other
            // mode (code/both, substring, case-insensitive) or a not-yet-ready index
            // falls through to the bounded live scan below.
            boolean indexEligible = fSmali && !fCode && cs;
            if (indexEligible) {
                List<String> fqns = context.stringIndex().lookup(literal, packageFilter);
                if (fqns != null) { // index is READY
                    int total = fqns.size();
                    int from = Math.min(Math.max(offset, 0), total);
                    int to = count <= 0 ? total : Math.min(from + count, total);
                    List<Map<String, Object>> usages = new ArrayList<>(Math.max(0, to - from));
                    for (int i = from; i < to; i++) {
                        String fqn = fqns.get(i);
                        Map<String, Object> row = new HashMap<>();
                        row.put("class_name", fqn);
                        row.put("matched_in", "smali");
                        // Snippet/line: only the page's (few) classes need their smali fetched.
                        JavaClass c = context.findClassByFqn(fqn);
                        String smali = c != null ? safeSmali(c) : null;
                        if (smali != null) {
                            Map<String, Object> hit = scanForLiteral(smali, smaliHaystack, cs);
                            if (hit != null) {
                                row.put("line", hit.get("line"));
                                row.put("hits", hit.get("hits"));
                                row.put("snippet", hit.get("snippet"));
                            }
                        }
                        usages.add(row);
                    }
                    Map<String, Object> out = new HashMap<>();
                    out.put("type", "string-usages");
                    out.put("offset", from);
                    out.put("count", usages.size());
                    out.put("page_size", Math.max(count, 0));
                    out.put("total", total);
                    out.put("returned", usages.size());
                    out.put("has_more", to < total);
                    out.put("usages", usages);
                    out.put("index", "ready");
                    ctx.json(out);
                    return;
                }
            }

            // ---- Live bounded scan (index absent/building, or non-exact query) ----
            List<JavaClass> all = context.getClassesWithInners();
            ScanResult<Map<String, Object>> sr = boundedScan(all, timeoutMs, cap, c -> {
                if (filterPkg && !matchesPackageFilter(c, packageFilter)) return null;
                return findStringUsageInClass(
                        c, fSmali, smaliNeedle, smaliHaystack,
                        fCode, codeNeedle, codeHaystack, cs);
            });
            Map<String, Object> out = Pagination.paginate(
                    sr.hits, "string-usages", "usages", offset, count, x -> x);
            decorateScan(out, sr, timeoutMs);
            out.put("index", context.stringIndex().status().name().toLowerCase());
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Find string usages failed: " + e.getMessage(), e, logger);
        }
    }

    private Map<String, Object> findStringUsageInClass(
            JavaClass c,
            boolean useSmali, String smaliNeedle, String smaliHaystack,
            boolean useCode, String codeNeedle, String codeHaystack,
            boolean caseSensitive) {
        try {
            // smali first when requested: it works even for classes jadx
            // can't decompile, and it carries const-string opcodes verbatim.
            if (useSmali) {
                String smali = safeSmali(c);
                if (smali != null && !smali.isEmpty()) {
                    Map<String, Object> hit = scanForLiteral(smali, smaliHaystack, caseSensitive);
                    if (hit != null) {
                        hit.put("class_name", c.getFullName());
                        hit.put("matched_in", "smali");
                        return hit;
                    }
                }
            }
            if (useCode) {
                String code = decompiledCode(c);
                if (code != null && !code.isEmpty()) {
                    Map<String, Object> hit = scanForLiteral(code, codeHaystack, caseSensitive);
                    if (hit != null) {
                        hit.put("class_name", c.getFullName());
                        hit.put("matched_in", "code");
                        return hit;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Scan haystack for haystackNeedle (already cased to match the haystack
     * casing policy). Returns an envelope with line/hits/snippet, or null if
     * not found. The snippet uses the original haystack so casing is preserved.
     */
    private static Map<String, Object> scanForLiteral(
            String haystack, String haystackNeedle, boolean caseSensitive) {
        String hay = caseSensitive ? haystack : haystack.toLowerCase();
        int idx = hay.indexOf(haystackNeedle);
        if (idx < 0) return null;

        int line = 1;
        for (int i = 0; i < idx; i++) {
            if (haystack.charAt(i) == '\n') line++;
        }
        int lineStart = haystack.lastIndexOf('\n', idx) + 1;
        int lineEnd = haystack.indexOf('\n', idx);
        if (lineEnd < 0) lineEnd = haystack.length();
        String snippet = haystack.substring(lineStart, lineEnd).trim();

        int hits = 0;
        int p = 0;
        while ((p = hay.indexOf(haystackNeedle, p)) >= 0) {
            hits++;
            p += haystackNeedle.length();
        }

        Map<String, Object> row = new HashMap<>();
        row.put("line", line);
        row.put("hits", hits);
        row.put("snippet", snippet);
        return row;
    }

    /** Wrap {@code getSmali()} -- some jadx versions throw on synthetic classes. */
    private static String safeSmali(JavaClass c) {
        try {
            return c.getSmali();
        } catch (Throwable t) {
            return null;
        }
    }

    public void handleCacheStats(Context ctx) {
        ctx.json(context.cache().stats());
    }

    /** Const-string inverted index build/load status (absent|building|ready|failed) + progress. */
    public void handleIndexStatus(Context ctx) {
        ctx.json(context.stringIndex().statusMap());
    }

    /**
     * Discovery search over the DEX string-constant pool: list embedded string CONSTANTS whose
     * value matches {@code query} (substring by default, or {@code regex=true}) plus the classes
     * that declare each. Powered by the const-string inverted index, so it is sub-second even on
     * a 100k+ class APK — the opposite end from {@link #handleFindStringUsages}, which takes an
     * EXACT literal and returns its usages. Use this to enumerate URLs, API keys, crypto
     * constants, etc. ("show every string containing 'http' / matching a base64 pattern").
     */
    public void handleSearchStringConstants(Context ctx) {
        String query = ctx.queryParam("query");
        if (query == null || query.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter 'query'", logger);
            return;
        }
        boolean regex = "true".equalsIgnoreCase(ctx.queryParam("regex"));
        String caseParam = ctx.queryParam("case_sensitive");
        boolean caseSensitive = caseParam != null && caseParam.equalsIgnoreCase("true");
        String packageFilter = ctx.queryParam("package");
        int offset = parsePositive(ctx, "offset", 0);
        int count = parsePositive(ctx, "count", 100);
        // Over-fetch by one so pagination can report has_more accurately when the index search caps.
        int searchCap = count > 0 ? offset + count + 1 : 0;
        try {
            List<Map<String, Object>> hits;
            try {
                hits = context.stringIndex().searchKeys(query, caseSensitive, regex,
                        isValidPackageFilter(packageFilter) ? packageFilter : null, searchCap);
            } catch (java.util.regex.PatternSyntaxException pe) {
                Errors.send(ctx, 400, "Invalid regex '" + query + "': " + pe.getMessage(), logger);
                return;
            }
            if (hits == null) {
                // Index not READY: report status rather than silently launching a minutes-long live scan.
                String st = context.stringIndex().status().name().toLowerCase();
                Map<String, Object> out = new HashMap<>();
                out.put("type", "string-constants");
                out.put("index", st);
                out.put("query", query);
                out.put("results", new ArrayList<>());
                out.put("note", "String index not ready (" + st + "). Retry shortly (poll index_status). "
                        + "For an exact known literal, find_string_usages works without the index.");
                ctx.json(out);
                return;
            }
            Map<String, Object> out = Pagination.paginate(hits, "string-constants", "results", offset, count, x -> x);
            out.put("index", "ready");
            out.put("query", query);
            if (regex) out.put("regex", true);
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Search string constants failed: " + e.getMessage(), e, logger);
        }
    }

    /**
     * Direct subclasses / interface implementors of a class, from the type-hierarchy index.
     * {@code class_name} is matched against the smali super/interface ref (the original DEX FQN —
     * for SDK/framework base classes and un-obfuscated classes that equals the decompiled FQN).
     * Index-backed; requires the index to be {@code ready}.
     */
    public void handleSubclasses(Context ctx) {
        String className = requireClassName(ctx);
        if (className == null) return;
        try {
            String packageFilter = ctx.queryParam("package");
            List<String> subs = context.stringIndex().lookupSubtypes(className,
                    isValidPackageFilter(packageFilter) ? packageFilter : null);
            if (subs == null) {
                String st = context.stringIndex().status().name().toLowerCase();
                Map<String, Object> out = new HashMap<>();
                out.put("type", "subclasses");
                out.put("class", className);
                out.put("index", st);
                out.put("subclasses", new ArrayList<>());
                out.put("note", "Type-hierarchy index not ready (" + st + "). Retry shortly (poll index_status).");
                ctx.json(out);
                return;
            }
            Map<String, Object> out = Pagination.paginate(ctx, subs, "subclasses", "subclasses", x -> x);
            out.put("class", className);
            out.put("index", "ready");
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Subclasses lookup failed: " + e.getMessage(), e, logger);
        }
    }

    public void handleCacheClear(Context ctx) {
        context.cache().clear();
        ctx.json(Map.of("status", "cleared", "stats", context.cache().stats()));
    }

    // -------------------- helpers --------------------

    private String requireClassName(Context ctx) {
        String name = ctx.queryParam("class_name");
        if (name == null || name.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter 'class_name'", logger);
            return null;
        }
        return name;
    }

    private JavaClass findClass(String fullName) {
        return context.findClassByFqn(fullName);
    }

    private String decompiledCode(JavaClass cls) {
        String cached = context.cache().get(cls.getFullName());
        if (cached != null) return cached;
        String code = cls.getCode();
        if (code != null) context.cache().put(cls.getFullName(), code);
        return code;
    }

    private String manifestPackageName() throws Exception {
        ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(context.jadx().getResources());
        if (manifestRes == null) return null;
        String xml = manifestRes.loadContent().getText().getCodeStr();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));
        Element root = (Element) doc.getElementsByTagName("manifest").item(0);
        if (root == null) return null;
        String pkg = root.getAttribute("package");
        return pkg == null || pkg.isEmpty() ? null : pkg;
    }

    private Set<SearchLocation> parseSearchLocations(String searchIn) {
        Set<SearchLocation> out = EnumSet.noneOf(SearchLocation.class);
        if (searchIn == null || searchIn.trim().isEmpty()) {
            out.add(SearchLocation.CODE);
            return out;
        }
        for (String p : searchIn.toLowerCase().split(",")) {
            SearchLocation loc = SEARCH_LOCATIONS.get(p.trim());
            if (loc != null) out.add(loc);
        }
        if (out.isEmpty()) out.add(SearchLocation.CODE);
        return out;
    }

    private boolean isValidPackageFilter(String filter) {
        if (filter == null || filter.trim().isEmpty()) return false;
        if (filter.equals("defpackage")) return false;
        String first = filter.split("\\.")[0];
        return !OBFUSCATED_PACKAGE_PATTERN.matcher(first).matches();
    }

    private boolean matchesPackageFilter(JavaClass cls, String filter) {
        if (filter == null || filter.trim().isEmpty()) return true;
        String full = cls.getFullName();
        return full.startsWith(filter + ".") || full.equals(filter);
    }

    /**
     * Single-line ({@code //...}) and block comment extractor. We match each comment span,
     * then apply the caller's text predicate to its body — this routes regex and substring
     * comment search through the same path as code/name search.
     */
    private static final Pattern COMMENT_EXTRACT =
            Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private static boolean commentMatches(String code, java.util.function.Predicate<String> textMatch) {
        java.util.regex.Matcher m = COMMENT_EXTRACT.matcher(code);
        while (m.find()) {
            if (textMatch.test(m.group())) return true;
        }
        return false;
    }

    private Set<JavaClass> searchIn(List<JavaClass> all, java.util.function.Predicate<String> textMatch,
                                    SearchLocation loc, String packageFilter, boolean filterPkg) {
        switch (loc) {
            case CLASS_NAME:
                return all.parallelStream()
                        .filter(c -> (!filterPkg || matchesPackageFilter(c, packageFilter))
                                && textMatch.test(c.getName()))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            case METHOD_NAME:
                return all.parallelStream()
                        .filter(c -> {
                            if (filterPkg && !matchesPackageFilter(c, packageFilter)) return false;
                            for (JavaMethod m : c.getMethods()) {
                                if (textMatch.test(m.getName())) return true;
                                if (m.isConstructor() && textMatch.test(c.getName())) return true;
                            }
                            return false;
                        })
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            case FIELD_NAME:
                return all.parallelStream()
                        .filter(c -> {
                            if (filterPkg && !matchesPackageFilter(c, packageFilter)) return false;
                            for (JavaField f : c.getFields()) {
                                if (textMatch.test(f.getName())) return true;
                            }
                            return false;
                        })
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            case CODE:
                return all.parallelStream()
                        .filter(c -> {
                            try {
                                if (filterPkg && !matchesPackageFilter(c, packageFilter)) return false;
                                String code = decompiledCode(c);
                                return code != null && textMatch.test(code);
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            case COMMENT:
                return all.parallelStream()
                        .filter(c -> {
                            try {
                                if (filterPkg && !matchesPackageFilter(c, packageFilter)) return false;
                                String code = decompiledCode(c);
                                return code != null && commentMatches(code, textMatch);
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            default:
                return new HashSet<>();
        }
    }

    private boolean isLikelyLibrary(String pkg) {
        if (pkg == null) return false;
        for (String prefix : LIBRARY_PREFIXES) {
            if (pkg.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Best-effort method descriptor (parameter type list). Falls back to "" if jadx
     * doesn't expose it cleanly — never throws, since this is metadata enrichment.
     */
    public static String safeDescriptor(JavaMethod m) {
        try {
            jadx.core.dex.nodes.MethodNode mn = m.getMethodNode();
            if (mn == null) return "";
            return String.valueOf(mn.getMethodInfo().getShortId());
        } catch (Throwable t) {
            return "";
        }
    }
}
