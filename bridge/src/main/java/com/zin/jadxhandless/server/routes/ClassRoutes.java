package com.zin.jadxhandless.server.routes;

import com.zin.jadxhandless.server.BridgeContext;
import com.zin.jadxhandless.util.Errors;
import com.zin.jadxhandless.util.Pagination;
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
                row.put("access_flags", m.getAccessFlags());
                row.put("is_constructor", m.isConstructor());
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
                row.put("access_flags", f.getAccessFlags());
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
            ctx.json(Map.of(
                    "name", main.getFullName(),
                    "type", "code/java",
                    "content", decompiledCode(main)));
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
            List<Map<String, Object>> packages = counts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .map(e -> {
                        Map<String, Object> row = new HashMap<>();
                        row.put("name", e.getKey());
                        row.put("class_count", e.getValue());
                        row.put("is_likely_library", isLikelyLibrary(e.getKey()));
                        return row;
                    })
                    .collect(Collectors.toList());
            ctx.json(Map.of(
                    "total_classes", all.size(),
                    "total_packages", packages.size(),
                    "packages", packages));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to build package tree: " + e.getMessage(), e, logger);
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
        for (JavaClass cls : context.getClassesWithInners()) {
            if (cls.getFullName().equals(fullName)) return cls;
        }
        return null;
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
}
