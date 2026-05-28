package com.zin.jadxheadless.server.routes;

import com.zin.jadxheadless.server.BridgeContext;
import com.zin.jadxheadless.util.Errors;
import com.zin.jadxheadless.util.Pagination;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class ClassRoutes {

    private static final Logger logger = LoggerFactory.getLogger(ClassRoutes.class);
    private static final Pattern OBFUSCATED_PACKAGE_PATTERN = Pattern.compile("^p\\d+$");

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
            ctx.result(decompiledCode(cls));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to decompile class: " + e.getMessage(), e, logger);
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
            ctx.result(cls.getSmali());
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
                    "content", code));
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
            ctx.json(Map.of("package", pkg, "count", names.size(), "classes", names));
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

        try {
            List<JavaClass> all = context.getClassesWithInners();
            String t = term.toLowerCase();
            boolean filterPkg = isValidPackageFilter(packageFilter);

            Set<JavaClass> matched = new LinkedHashSet<>();
            for (SearchLocation loc : locations) {
                matched.addAll(searchIn(all, t, loc, packageFilter, filterPkg));
            }
            ctx.json(Pagination.paginate(ctx, new ArrayList<>(matched), "class-list", "classes", JavaClass::getFullName));
        } catch (Exception e) {
            Errors.internal(ctx, "Search failed: " + e.getMessage(), e, logger);
        }
    }

    public void handlePackageTree(Context ctx) {
        try {
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
            out.put("total_classes", all.size());
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

        try {
            List<JavaClass> all = context.getClassesWithInners();
            List<Map<String, Object>> matches = all.parallelStream()
                    .filter(c -> !filterPkg || matchesPackageFilter(c, packageFilter))
                    .map(c -> findStringUsageInClass(
                            c, fSmali, smaliNeedle, smaliHaystack,
                            fCode, codeNeedle, codeHaystack, cs))
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toList());

            ctx.json(Pagination.paginate(ctx, matches, "string-usages", "usages", x -> x));
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

    private Set<JavaClass> searchIn(List<JavaClass> all, String term, SearchLocation loc,
                                    String packageFilter, boolean filterPkg) {
        switch (loc) {
            case CLASS_NAME:
                return all.parallelStream()
                        .filter(c -> (!filterPkg || matchesPackageFilter(c, packageFilter))
                                && c.getName().toLowerCase().contains(term))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            case METHOD_NAME:
                return all.parallelStream()
                        .filter(c -> {
                            if (filterPkg && !matchesPackageFilter(c, packageFilter)) return false;
                            for (JavaMethod m : c.getMethods()) {
                                if (m.getName().toLowerCase().contains(term)) return true;
                                if (m.isConstructor() && c.getName().toLowerCase().contains(term)) return true;
                            }
                            return false;
                        })
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            case FIELD_NAME:
                return all.parallelStream()
                        .filter(c -> {
                            if (filterPkg && !matchesPackageFilter(c, packageFilter)) return false;
                            for (JavaField f : c.getFields()) {
                                if (f.getName().toLowerCase().contains(term)) return true;
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
                                return code != null && code.toLowerCase().contains(term);
                            } catch (Exception e) {
                                return false;
                            }
                        })
                        .collect(Collectors.toCollection(LinkedHashSet::new));
            case COMMENT:
                Pattern singleLine = Pattern.compile("//.*?" + Pattern.quote(term) + ".*", Pattern.CASE_INSENSITIVE);
                Pattern multiLine = Pattern.compile("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/", Pattern.DOTALL);
                return all.parallelStream()
                        .filter(c -> {
                            try {
                                if (filterPkg && !matchesPackageFilter(c, packageFilter)) return false;
                                String code = decompiledCode(c);
                                if (code == null) return false;
                                if (singleLine.matcher(code).find()) return true;
                                java.util.regex.Matcher mm = multiLine.matcher(code);
                                while (mm.find()) {
                                    if (mm.group().toLowerCase().contains(term)) return true;
                                }
                                return false;
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
    static String safeDescriptor(JavaMethod m) {
        try {
            jadx.core.dex.nodes.MethodNode mn = m.getMethodNode();
            if (mn == null) return "";
            return String.valueOf(mn.getMethodInfo().getShortId());
        } catch (Throwable t) {
            return "";
        }
    }
}
