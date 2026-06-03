package com.zin.jadxheadless.server.routes;

import com.zin.jadxheadless.server.BridgeContext;
import com.zin.jadxheadless.util.Errors;
import com.zin.jadxheadless.util.Pagination;
import com.zin.jadxheadless.util.Scan;
import com.zin.jadxheadless.util.Scan.ScanResult;
import com.zin.jadxheadless.util.StringIndex;
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
import java.util.function.Predicate;
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
        final Predicate<String> textMatch = pat != null
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
                // CLASS / METHOD / FIELD name matching is cheap string work over the jadx model
                // (no decompile) -- a bounded parallel scan over getMethods()/getFields()/getName()
                // returns in well under a second. CODE/COMMENT need decompiled source.
                if (loc != SearchLocation.CODE && loc != SearchLocation.COMMENT) {
                    final SearchLocation nloc = loc;
                    ScanResult<JavaClass> sr = Scan.boundedScan(all, timeoutMs, cap, c -> {
                        if (filterPkg && !matchesPackageFilter(c, packageFilter)) return null;
                        return nameMatches(c, nloc, textMatch) ? c : null;
                    });
                    matched.addAll(sr.hits);
                    timedOut |= sr.timedOut;
                    scanned += sr.scanned;
                    continue;
                }
                // CODE / COMMENT: route through the unified Java-source search. confirmSearch
                // narrows via the pre-decompiled Java token index when ready (then full-text
                // confirms each candidate), else falls back to a bounded live decompile scan.
                final boolean isComment = loc == SearchLocation.COMMENT;
                Predicate<String> matcher = isComment
                        ? code -> commentMatches(code, textMatch)
                        : textMatch;
                ConfirmResult cr = confirmSearch(term, matcher, packageFilter, regex, caseSensitive, timeoutMs, cap);
                matched.addAll(cr.hits);
                timedOut |= cr.timedOut;
                scanned += cr.scanned;
            }
            Map<String, Object> out = Pagination.paginate(
                    new ArrayList<>(matched), "class-list", "classes", offset, count, JavaClass::getFullName);
            if (regex) out.put("regex", true);
            if (timedOut) {
                out.put("timed_out", true);
                out.put("scanned", scanned);
                out.put("total_classes", all.size());
                out.put("has_more", true);
                out.put("note", "Scan hit the " + timeoutMs
                        + "ms budget; results are partial. Prefer search_in=class (fast), narrow the query, "
                        + "raise ?timeout_ms=, or wait for the pre-decompile index (poll index_status).");
            }
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Search failed: " + e.getMessage(), e, logger);
        }
    }

    /** True if {@code cls}'s name/method-names/field-names match {@code textMatch} for the given location. */
    private static boolean nameMatches(JavaClass cls, SearchLocation loc, Predicate<String> textMatch) {
        switch (loc) {
            case CLASS_NAME:
                return textMatch.test(cls.getName());
            case METHOD_NAME:
                for (JavaMethod m : cls.getMethods()) {
                    if (textMatch.test(m.getName())) return true;
                    if (m.isConstructor() && textMatch.test(cls.getName())) return true;
                }
                return false;
            case FIELD_NAME:
                for (JavaField f : cls.getFields()) {
                    if (textMatch.test(f.getName())) return true;
                }
                return false;
            default:
                return false;
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
     * <p>Always a bounded live scan (deadline + cap) — NO global index. Search source is selectable
     * via the {@code source} query parameter:
     * <ul>
     *   <li><b>code</b>: scans jadx-decompiled Java source (cached, so fast on repeat). Returns 0
     *       hits on classes that jadx refuses to decompile.</li>
     *   <li><b>smali</b>: scans each class's Dalvik smali listing for
     *       {@code const-string vN, "<literal>"} opcodes. Authoritative, independent of whether the
     *       class is decompilable — the right choice for finding native-library loads, hardcoded
     *       URLs, or API keys in R8/anti-tamper hardened classes (e.g. ByteDance, Tencent) where the
     *       Java decompile is empty.</li>
     *   <li><b>both</b> (default): report a class if either source contains the literal. The
     *       {@code matched_in} field tells you which.</li>
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
                ? "both" : sourceParam.toLowerCase();
        boolean useCode = source.equals("code") || source.equals("both");
        boolean useSmali = source.equals("smali") || source.equals("both");
        if (!useCode && !useSmali) {
            Errors.send(ctx, 400,
                    "Invalid 'source' parameter: expected 'code', 'smali', or 'both' (default)",
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
            // Bounded live scan, no global index. Per class: source=code scans decompiled Java
            // (cached), source=smali scans that single class's smali (works even on hardened classes
            // jadx can't decompile), source=both unions them. The deadline + cap keep it responsive
            // on a 100k+ class APK.
            List<JavaClass> all = context.getClassesWithInners();
            ScanResult<Map<String, Object>> sr = Scan.boundedScan(all, timeoutMs, cap, c -> {
                if (filterPkg && !matchesPackageFilter(c, packageFilter)) return null;
                return findStringUsageInClass(
                        c, fSmali, smaliNeedle, smaliHaystack,
                        fCode, codeNeedle, codeHaystack, cs);
            });
            Map<String, Object> out = Pagination.paginate(
                    sr.hits, "string-usages", "usages", offset, count, x -> x);
            decorateScan(out, sr, timeoutMs);
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

    /** Pre-decompile / Java-index progress (status absent|building|ready|failed + decompiled/total, budget, coverage). */
    public void handleIndexStatus(Context ctx) {
        ctx.json(context.stringIndex().statusMap());
    }

    /** Matches a Java string literal {@code "..."} (handles escaped quotes/backslashes inside). */
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    /** Distinct string-literal VALUES the scan can collect before stopping, to bound memory/response. */
    private static final int MAX_STRING_CONSTANT_VALUES = 5000;

    /**
     * Discovery search over string CONSTANTS in decompiled Java source: list distinct string-literal
     * VALUES whose text matches {@code query} (substring by default, or {@code regex=true}), each with
     * the classes that declare it. This is the DISCOVERY counterpart to {@link #handleFindStringUsages}
     * (which takes an EXACT literal and returns its usages). Use it to enumerate URLs, API keys, crypto
     * constants, etc. ("show every string containing 'http' / matching a base64 pattern").
     *
     * <p>Java source-level, NO global index: it narrows via the pre-decompiled Java token index when
     * ready (then extracts + matches literals from the candidates' source), else runs a bounded live
     * decompile scan. Results are bounded by a deadline + a distinct-value cap. Paginated.
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
        boolean filterPkg = isValidPackageFilter(packageFilter);
        int offset = parsePositive(ctx, "offset", 0);
        int count = parsePositive(ctx, "count", 100);
        long timeoutMs = parsePositiveLong(ctx, "timeout_ms", DEFAULT_SEARCH_TIMEOUT_MS);

        // Predicate over an UNQUOTED literal value.
        final Predicate<String> valueMatch;
        if (regex) {
            final Pattern p;
            try {
                p = Pattern.compile(query, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
            } catch (java.util.regex.PatternSyntaxException pe) {
                Errors.send(ctx, 400, "Invalid regex '" + query + "': " + pe.getMessage(), logger);
                return;
            }
            valueMatch = v -> p.matcher(v).find();
        } else if (caseSensitive) {
            valueMatch = v -> v.contains(query);
        } else {
            String ql = query.toLowerCase();
            valueMatch = v -> v.toLowerCase().contains(ql);
        }
        // A class is a candidate iff its source contains the query text at all (literal contents are
        // tokenized into the index, so candidate narrowing still works); the per-literal matcher above
        // does the precise filtering.
        final Predicate<String> sourceMatch = caseSensitive
                ? src -> src.contains(query)
                : (regex
                        ? src -> true /* regex: can't cheaply pre-filter source, confirm via literals */
                        : src -> src.toLowerCase().contains(query.toLowerCase()));

        try {
            // value -> sorted set of declaring class FQNs
            Map<String, Set<String>> byValue = new java.util.concurrent.ConcurrentHashMap<>();
            java.util.concurrent.atomic.AtomicBoolean capped = new java.util.concurrent.atomic.AtomicBoolean(false);
            java.util.function.Function<JavaClass, Boolean> perClass = c -> {
                if (filterPkg && !matchesPackageFilter(c, packageFilter)) return Boolean.FALSE;
                String code;
                try {
                    code = decompiledCode(c);
                } catch (Exception e) {
                    return Boolean.FALSE;
                }
                if (code == null || code.isEmpty() || !sourceMatch.test(code)) return Boolean.FALSE;
                String fqn = c.getFullName();
                boolean any = false;
                java.util.regex.Matcher m = STRING_LITERAL.matcher(code);
                while (m.find()) {
                    String raw = m.group();
                    String val = raw.substring(1, raw.length() - 1); // strip surrounding quotes
                    if (val.isEmpty() || !valueMatch.test(val)) continue;
                    if (!byValue.containsKey(val) && byValue.size() >= MAX_STRING_CONSTANT_VALUES) {
                        capped.set(true);
                        continue;
                    }
                    byValue.computeIfAbsent(val, k -> java.util.Collections.synchronizedSet(new java.util.TreeSet<>()))
                            .add(fqn);
                    any = true;
                }
                return any ? Boolean.TRUE : Boolean.FALSE;
            };

            // Candidate narrowing via the warm Java token index when possible, else full live scan.
            boolean timedOut;
            int scanned;
            String tok = StringIndex.longestToken(query);
            StringIndex idx = context.stringIndex();
            String pkgArg = filterPkg ? packageFilter : null;
            if (tok != null && idx.codeIndexReady()) {
                List<String> candidates = idx.lookupCodeContains(tok, pkgArg, 0, null);
                long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
                boolean to = false;
                int sc = 0;
                if (candidates != null) {
                    for (String fqn : candidates) {
                        if (System.nanoTime() > deadline) { to = true; break; }
                        sc++;
                        JavaClass c = context.findClassByFqn(fqn);
                        if (c != null) perClass.apply(c);
                    }
                }
                // Cover not-yet-indexed classes on a partial index.
                boolean coverageComplete = Boolean.TRUE.equals(idx.statusMap().get("coverage_complete"));
                if (!to && !coverageComplete) {
                    Set<String> indexed = idx.indexedFqns();
                    List<JavaClass> pending = new ArrayList<>();
                    for (JavaClass c : context.getClassesWithInners()) {
                        if (!indexed.contains(safeFullName(c))) pending.add(c);
                    }
                    ScanResult<Boolean> sr = Scan.boundedScan(pending, timeoutMs, 0, perClass::apply);
                    to |= sr.timedOut;
                    sc += sr.scanned;
                }
                timedOut = to;
                scanned = sc;
            } else {
                List<JavaClass> all = context.getClassesWithInners();
                ScanResult<Boolean> sr = Scan.boundedScan(all, timeoutMs, 0, perClass::apply);
                timedOut = sr.timedOut;
                scanned = sr.scanned;
            }

            // Materialize results: one row per distinct value, sorted by value for determinism.
            List<Map<String, Object>> results = new ArrayList<>(byValue.size());
            List<String> values = new ArrayList<>(byValue.keySet());
            java.util.Collections.sort(values);
            for (String val : values) {
                Map<String, Object> row = new HashMap<>();
                row.put("value", val);
                List<String> classes = new ArrayList<>(byValue.get(val));
                row.put("classes", classes);
                row.put("class_count", classes.size());
                results.add(row);
            }
            Map<String, Object> out = Pagination.paginate(results, "string-constants", "results", offset, count, x -> x);
            out.put("query", query);
            if (regex) out.put("regex", true);
            out.put("distinct_values", results.size());
            out.put("scanned", scanned);
            if (timedOut) {
                out.put("timed_out", true);
                out.put("note", "Scan hit the " + timeoutMs + "ms budget; results are partial. "
                        + "Narrow the query, raise ?timeout_ms=, or wait for the pre-decompile index (poll index_status).");
            }
            if (capped.get()) {
                out.put("values_capped", true);
                out.put("note_capped", "Stopped collecting after " + MAX_STRING_CONSTANT_VALUES
                        + " distinct values; narrow the query for a complete set.");
            }
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Search string constants failed: " + e.getMessage(), e, logger);
        }
    }

    /**
     * Direct subclasses / interface implementors of a class, enumerated from the jadx model's
     * type-hierarchy (each class's super/interface refs). {@code class_name} is the FQN as shown in
     * decompiled source (for SDK/framework base classes that's the resolved dotted name, e.g.
     * {@code android.app.Activity}). Model-only (no decompile), so it works as soon as the APK is
     * loaded — no index warm-up needed.
     */
    public void handleSubclasses(Context ctx) {
        String className = requireClassName(ctx);
        if (className == null) return;
        try {
            String packageFilter = ctx.queryParam("package");
            boolean filterPkg = isValidPackageFilter(packageFilter);
            List<String> subs = context.subtypeIndex().get(className);
            List<String> filtered = new ArrayList<>();
            if (subs != null) {
                for (String fqn : subs) {
                    if (!filterPkg || fqn.startsWith(packageFilter + ".") || fqn.equals(packageFilter)) {
                        filtered.add(fqn);
                    }
                }
            }
            Map<String, Object> out = Pagination.paginate(ctx, filtered, "subclasses", "subclasses", x -> x);
            out.put("class", className);
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Subclasses lookup failed: " + e.getMessage(), e, logger);
        }
    }

    /**
     * Multi-hop class-level call-graph traversal from {@code class_name} via a LIVE BFS — no global
     * index. {@code direction}=callees (default) follows what the class transitively calls (parsed
     * from each visited class's smali {@code invoke-*} ops, reusing the xrefs-from extractor, so it
     * works on hardened classes); =callers follows what transitively calls it (jadx
     * {@code ClassNode.getUseIn()}). Each node is {@code {class, depth}}. Bounded by {@code depth}
     * (hops, cap 20) and {@code max_nodes} (default 500) so it stays well under a minute; the start
     * class is excluded; the package filter applies to returned nodes. Paginated.
     */
    public void handleCallGraph(Context ctx) {
        String className = requireClassName(ctx);
        if (className == null) return;
        String dir = ctx.queryParam("direction");
        boolean callees = dir == null || !dir.equalsIgnoreCase("callers");
        int depth = parsePositive(ctx, "depth", 2);
        if (depth < 1) depth = 1;
        if (depth > 20) depth = 20;
        int cap = parsePositive(ctx, "max_nodes", 500);
        if (cap <= 0) cap = 500;
        String packageFilter = ctx.queryParam("package");
        boolean filterPkg = isValidPackageFilter(packageFilter);
        try {
            JavaClass start = findClass(className);
            if (start == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            // BFS over class FQNs. visited bounds total work to <= cap distinct classes regardless of
            // graph fan-out, keeping wall-time well under a minute. Each popped class is expanded once.
            Set<String> visited = new HashSet<>();
            visited.add(start.getFullName());
            java.util.ArrayDeque<String[]> frontier = new java.util.ArrayDeque<>();
            frontier.add(new String[]{ start.getFullName(), "0" });
            Map<String, Integer> resultDepth = new java.util.LinkedHashMap<>();
            boolean truncated = false;

            while (!frontier.isEmpty()) {
                String[] cur = frontier.poll();
                String fqn = cur[0];
                int d = Integer.parseInt(cur[1]);
                if (d >= depth) continue;
                JavaClass c = findClass(fqn);
                if (c == null) continue;
                Set<String> neighbors = callees ? calleesOf(c) : callersOf(c);
                for (String n : neighbors) {
                    if (n == null || n.equals(start.getFullName()) || !visited.add(n)) continue;
                    int nd = d + 1;
                    if (!filterPkg || n.startsWith(packageFilter + ".") || n.equals(packageFilter)) {
                        resultDepth.put(n, nd);
                    }
                    if (visited.size() >= cap + 1) { truncated = true; break; }
                    frontier.add(new String[]{ n, Integer.toString(nd) });
                }
                if (truncated) break;
            }

            List<Map<String, Object>> nodes = new ArrayList<>(resultDepth.size());
            for (Map.Entry<String, Integer> e : resultDepth.entrySet()) {
                Map<String, Object> row = new HashMap<>();
                row.put("class", e.getKey());
                row.put("depth", e.getValue());
                nodes.add(row);
            }
            nodes.sort((a, b) -> {
                int dd = Integer.compare((Integer) a.get("depth"), (Integer) b.get("depth"));
                return dd != 0 ? dd : String.valueOf(a.get("class")).compareTo(String.valueOf(b.get("class")));
            });
            Map<String, Object> out = Pagination.paginate(ctx, nodes, "call-graph", "nodes", x -> x);
            out.put("class", start.getFullName());
            out.put("direction", callees ? "callees" : "callers");
            out.put("max_depth", depth);
            if (truncated) {
                out.put("truncated", true);
                out.put("note", "Traversal hit the max_nodes=" + cap + " budget; graph is partial. "
                        + "Lower depth or raise max_nodes for more.");
            }
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Call-graph traversal failed: " + e.getMessage(), e, logger);
        }
    }

    /** Distinct callee class FQNs of {@code c}, parsed from its smali invoke ops (reuses XrefsRoutes). */
    private Set<String> calleesOf(JavaClass c) {
        try {
            return XrefsRoutes.calleeClassFqns(safeSmali(c));
        } catch (Throwable t) {
            return java.util.Collections.emptySet();
        }
    }

    /** Distinct caller class FQNs of {@code c}, from jadx's class-level use-in edges. */
    private Set<String> callersOf(JavaClass c) {
        Set<String> out = new HashSet<>();
        try {
            jadx.core.dex.nodes.ClassNode node = c.getClassNode();
            if (node == null) return out;
            for (jadx.core.dex.nodes.ClassNode user : node.getUseIn()) {
                if (user != null) out.add(user.getFullName());
            }
        } catch (Throwable t) {
            // best-effort
        }
        return out;
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

    /** Result of {@link #confirmSearch}: matching classes plus bounded-scan progress metadata. */
    private static final class ConfirmResult {
        final Set<JavaClass> hits;
        final boolean timedOut;
        final int scanned;
        ConfirmResult(Set<JavaClass> hits, boolean timedOut, int scanned) {
            this.hits = hits;
            this.timedOut = timedOut;
            this.scanned = scanned;
        }
    }

    /**
     * Unified Java-source search over decompiled code, bounded by {@code timeoutMs} + {@code cap}.
     *
     * <p>{@code sourceMatch} is the confirmation predicate applied to a class's full decompiled
     * source (for code search it is the term matcher; for comment search it scans only comment
     * spans). Strategy:
     * <ol>
     *   <li>If the query yields a usable identifier token AND the pre-decompiled Java index is
     *       READY, narrow to candidate FQNs via {@link StringIndex#lookupCodeContains} and full-text
     *       confirm each (source comes from the decompilation cache, decompiling on demand). This is
     *       sub-second on a warm index even for punctuation queries like {@code getInstance("AES}.</li>
     *   <li>Because the index may be partial (huge APK best-effort), ALSO bounded-scan the classes
     *       not yet indexed ({@code getClassesWithInners()} minus {@code indexedFqns()}), live-
     *       decompiling and confirming, so coverage isn't silently limited to the indexed subset.</li>
     *   <li>If there's no usable token (e.g. punctuation-only query) OR the index isn't ready, run a
     *       bounded full live scan over every class.</li>
     * </ol>
     */
    private ConfirmResult confirmSearch(String query, Predicate<String> sourceMatch, String packageFilter,
                                        boolean regex, boolean caseSensitive, long timeoutMs, int cap) {
        boolean filterPkg = isValidPackageFilter(packageFilter);
        String pkgArg = filterPkg ? packageFilter : null;
        String tok = StringIndex.longestToken(query);
        StringIndex idx = context.stringIndex();
        Set<JavaClass> hits = new LinkedHashSet<>();
        boolean timedOut = false;
        int scanned = 0;

        if (tok != null && idx.codeIndexReady()) {
            // ---- candidate narrowing via the warm Java token index ----
            List<String> candidates = idx.lookupCodeContains(tok, pkgArg, 0, null);
            if (candidates != null) {
                long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
                for (String fqn : candidates) {
                    if (System.nanoTime() > deadline) { timedOut = true; break; }
                    scanned++;
                    String src = context.cache().get(fqn);
                    if (src == null) {
                        JavaClass c = context.findClassByFqn(fqn);
                        if (c == null) continue;
                        try {
                            src = decompiledCode(c);
                        } catch (Exception e) {
                            continue;
                        }
                    }
                    if (src != null && sourceMatch.test(src)) {
                        JavaClass c = context.findClassByFqn(fqn);
                        if (c != null) {
                            hits.add(c);
                            if (cap > 0 && hits.size() >= cap) return new ConfirmResult(hits, timedOut, scanned);
                        }
                    }
                }
            }
            // ---- bounded live scan of classes the (possibly partial) index has not covered ----
            // Skip when the pre-decompile achieved full coverage (every class already indexed +
            // confirmed above); otherwise sweep the not-yet-indexed remainder so a best-effort
            // index on a huge APK doesn't silently cap results to its subset.
            boolean coverageComplete = Boolean.TRUE.equals(idx.statusMap().get("coverage_complete"));
            if (!coverageComplete) {
                Set<String> indexed = idx.indexedFqns();
                List<JavaClass> pending = new ArrayList<>();
                for (JavaClass c : context.getClassesWithInners()) {
                    String fqn = safeFullName(c);
                    if (!indexed.contains(fqn)) pending.add(c);
                }
                if (!pending.isEmpty()) {
                    int remainingCap = cap > 0 ? Math.max(1, cap - hits.size()) : 0;
                    ScanResult<JavaClass> sr = Scan.boundedScan(pending, timeoutMs, remainingCap, c -> {
                        if (filterPkg && !matchesPackageFilter(c, packageFilter)) return null;
                        String code;
                        try {
                            code = decompiledCode(c);
                        } catch (Exception e) {
                            return null;
                        }
                        return code != null && sourceMatch.test(code) ? c : null;
                    });
                    hits.addAll(sr.hits);
                    timedOut |= sr.timedOut;
                    scanned += sr.scanned;
                }
            }
            return new ConfirmResult(hits, timedOut, scanned);
        }

        // ---- no usable token, or index not ready: bounded full live scan ----
        List<JavaClass> all = context.getClassesWithInners();
        ScanResult<JavaClass> sr = Scan.boundedScan(all, timeoutMs, cap, c -> {
            if (filterPkg && !matchesPackageFilter(c, packageFilter)) return null;
            String code;
            try {
                code = decompiledCode(c);
            } catch (Exception e) {
                return null;
            }
            return code != null && sourceMatch.test(code) ? c : null;
        });
        return new ConfirmResult(new LinkedHashSet<>(sr.hits), sr.timedOut, sr.scanned);
    }

    /** Wrap {@code getFullName()} -- some jadx versions throw on unloadable classes. */
    private static String safeFullName(JavaClass c) {
        try {
            return c.getFullName();
        } catch (Throwable t) {
            return "?";
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
