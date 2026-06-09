package com.zin.jadxheadless.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zin.jadxheadless.index.Db;
import com.zin.jadxheadless.jadx.JadxService;
import com.zin.jadxheadless.util.DexId;
import com.zin.jadxheadless.util.ManifestUtil;
import com.zin.jadxheadless.util.Pagination;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.ResourceFile;
import jadx.core.dex.nodes.MethodNode;

/**
 * Declares the refined RE tool set (mcp-re-toolset) over {@link JadxService}. Tiering:
 * <ul>
 *   <li>Tier-1 instant: enumeration, string-pool search, xref (SQLite symbol graph), resources, rename.</li>
 *   <li>Tier-2 fast: single-class decompile (disk-cache hit → instant).</li>
 *   <li>Tier-3 background: {@code search_in_code} via FTS5 / ripgrep, with {@code index_status} progress.</li>
 * </ul>
 * Deliberately excludes GUI-only tools ({@code get_selected_text}, {@code fetch_current_class}) and the
 * smali debugger group ({@code debug_*}) — meaningless headless.
 */
public final class ToolRegistry {

	private static final Logger LOG = LoggerFactory.getLogger(ToolRegistry.class);

	private final JadxService svc;

	public ToolRegistry(JadxService svc) {
		this.svc = svc;
	}

	public List<SyncToolSpecification> build() {
		List<SyncToolSpecification> t = new ArrayList<>();

		// ---------- session ----------
		t.add(Tools.tool("load_apk",
				"Load (or switch to) an APK/DEX/AAB/XAPK/APKM/JAR for analysis. Returns as soon as the model is "
						+ "loaded; a background index then builds by analysis-value tier (xref → entry → main → rest) "
						+ "so high-value code is searchable progressively — you need not wait for full coverage. Call "
						+ "this first; poll index_status (xref_ready/main_ready/current_tier) for what is searchable now.",
				Tools.schema(Tools.strProp("path", "Absolute path to the input file")
						+ "," + Tools.boolProp("deobf", "Enable jadx deobfuscation (default false; off is better for "
								+ "heavily-obfuscated apps)"),
						"path"),
				args -> svc.loadApk(reqStr(args, "path"), boolArg(args, "deobf", false))));

		t.add(Tools.tool("current_apk", "Which APK is loaded, with class count and index build status.",
				Tools.schemaObject(null), args -> svc.currentApkInfo()));

		t.add(Tools.tool("index_status",
				"Background index build progress with TIERED availability: the build advances xref → entry → "
						+ "main → rest, and each tier is searchable as it completes (you need not wait for 100%). "
						+ "Key fields: current_tier, xref_ready (get_xrefs_* usable), main_ready (app main package "
						+ "searchable), decompiled_classes vs indexed_classes, percent, coverage_complete. "
						+ "search_in_code already covers every decompiled class while building; coverage_complete=true "
						+ "means the whole in-scope set is in FTS.",
				Tools.schemaObject(null), args -> svc.indexStatus().toMap()));

		t.add(Tools.tool("clear_cache", "Clear in-heap caches (disk index retained for reuse).",
				Tools.schemaObject(null), args -> svc.clearCache()));

		// ---------- enumeration (Tier-1) ----------
		t.add(Tools.tool("get_all_classes",
				"Paginated list of all class FQNs (including inner classes).",
				Tools.schemaObject(Tools.intProp("offset", "start index (default 0)") + ","
						+ Tools.intProp("limit", "page size (default 200, max 2000)")),
				args -> {
					List<String> names = new ArrayList<>();
					for (JavaClass c : svc.getClassesWithInners()) {
						names.add(c.getFullName());
					}
					return Pagination.page(names, args, "classes");
				}));

		t.add(Tools.tool("get_package_tree",
				"Packages with their class counts (sorted), paginated — a cheap structural overview.",
				Tools.schemaObject(Tools.intProp("offset", "start index") + "," + Tools.intProp("limit", "page size")),
				args -> {
					TreeMap<String, Integer> counts = new TreeMap<>();
					for (JavaClass c : svc.getClassesWithInners()) {
						String pkg = c.getPackage();
						counts.merge(pkg == null ? "" : pkg, 1, Integer::sum);
					}
					List<Map<String, Object>> rows = new ArrayList<>();
					counts.forEach((k, v) -> rows.add(Map.of("package", k, "classes", v)));
					return Pagination.page(rows, args, "packages");
				}));

		t.add(Tools.tool("get_methods_of_class",
				"Methods of a class: name, descriptor (for overload disambiguation), return type, args.",
				Tools.schema(Tools.strProp("class_name", "FQN or raw class name"), "class_name"),
				args -> {
					JavaClass c = cls(reqStr(args, "class_name"));
					List<Map<String, Object>> out = new ArrayList<>();
					for (JavaMethod m : c.getMethods()) {
						Map<String, Object> row = new LinkedHashMap<>();
						row.put("name", m.getName());
						row.put("descriptor", JadxService.safeDescriptor(m));
						row.put("return_type", safe(() -> m.getReturnType().toString()));
						row.put("constructor", m.isConstructor());
						out.add(row);
					}
					return Map.of("class", c.getFullName(), "count", out.size(), "methods", out);
				}));

		t.add(Tools.tool("get_fields_of_class", "Fields of a class: name and type.",
				Tools.schema(Tools.strProp("class_name", "FQN or raw class name"), "class_name"),
				args -> {
					JavaClass c = cls(reqStr(args, "class_name"));
					List<Map<String, Object>> out = new ArrayList<>();
					for (JavaField f : c.getFields()) {
						out.add(Map.of("name", f.getName(), "type", safe(() -> f.getType().toString())));
					}
					return Map.of("class", c.getFullName(), "count", out.size(), "fields", out);
				}));

		// ---------- single class (Tier-2) ----------
		t.add(Tools.tool("get_class_source",
				"Decompiled Java source of one class (disk-cache hit → instant; otherwise decompiles on demand).",
				Tools.schema(Tools.strProp("class_name", "FQN or raw class name"), "class_name"),
				args -> {
					JavaClass c = cls(reqStr(args, "class_name"));
					return Map.of("class", c.getFullName(), "source", safe(c::getCode));
				}));

		t.add(Tools.tool("get_smali_of_class", "Smali (bytecode) of one class.",
				Tools.schema(Tools.strProp("class_name", "FQN or raw class name"), "class_name"),
				args -> {
					JavaClass c = cls(reqStr(args, "class_name"));
					return Map.of("class", c.getFullName(), "smali", safe(c::getSmali));
				}));

		t.add(Tools.tool("get_method_by_name",
				"Locate method(s) by name in a class and return signature(s) + a best-effort decompiled snippet "
						+ "of each. Pass descriptor to scope to one overload.",
				Tools.schema(Tools.strProp("class_name", "FQN or raw class name") + ","
						+ Tools.strProp("method_name", "method name") + ","
						+ Tools.strProp("descriptor", "optional smali descriptor to disambiguate overloads"),
						"class_name", "method_name"),
				args -> {
					JavaClass c = cls(reqStr(args, "class_name"));
					String mName = reqStr(args, "method_name");
					String desc = optStr(args, "descriptor");
					String source = safe(c::getCode);
					List<Map<String, Object>> hits = new ArrayList<>();
					for (JavaMethod m : c.getMethods()) {
						if (!m.getName().equals(mName)) {
							continue;
						}
						if (desc != null && !desc.isEmpty() && !JadxService.safeDescriptor(m).equals(desc)) {
							continue;
						}
						Map<String, Object> row = new LinkedHashMap<>();
						row.put("name", m.getName());
						row.put("descriptor", JadxService.safeDescriptor(m));
						row.put("code", extractMethod(source, m.getName()));
						hits.add(row);
					}
					if (hits.isEmpty()) {
						throw new IllegalArgumentException("Method " + mName + " not found in " + c.getFullName());
					}
					return Map.of("class", c.getFullName(), "matches", hits);
				}));

		// ---------- strings (RE main line, Tier-1) ----------
		t.add(Tools.tool("search_string_constants",
				"Substring search over const-string literals, aggregated by class (one row per class + its "
						+ "matching strings). The primary locator on obfuscated apps (names are meaningless; string "
						+ "literals are not). Standard-library classes (android/androidx/java/kotlin/google/…) are "
						+ "filtered by default and results are ranked app > obfuscated > third-party; set "
						+ "include_libs=true to keep stdlib hits.",
				Tools.schema(Tools.strProp("query", "substring to find") + ","
						+ Tools.intProp("limit", "max classes (default 200)") + ","
						+ Tools.boolProp("include_libs", "include standard-library class hits (default false)"),
						"query"),
				args -> {
					int limit = Pagination.intArg(args, "limit", 200, 1, 2000);
					List<Map<String, Object>> hits = svc.codeSearch().searchStringConstants(
							reqStr(args, "query"), limit, boolArg(args, "include_libs", false));
					return withIndexNote(Map.of("count", hits.size(), "matches", hits));
				}));

		t.add(Tools.tool("find_string_usages",
				"Classes that contain an EXACT string literal (whole-literal match), one row per class. For "
						+ "substring / partial matching use search_string_constants (FTS-accelerated). Standard-library "
						+ "classes are filtered by default (set include_libs=true to keep them); ranked app > obfuscated "
						+ "> third-party.",
				Tools.schema(Tools.strProp("value", "the exact string literal to match in full") + ","
						+ Tools.intProp("limit", "max classes (default 200)") + ","
						+ Tools.boolProp("include_libs", "include standard-library class hits (default false)"),
						"value"),
				args -> {
					int limit = Pagination.intArg(args, "limit", 200, 1, 2000);
					List<Map<String, Object>> hits = svc.codeSearch().findStringUsages(
							reqStr(args, "value"), limit, boolArg(args, "include_libs", false));
					return withIndexNote(Map.of("count", hits.size(), "usages", hits));
				}));

		t.add(Tools.tool("get_strings",
				"Android string resources from res/values/strings.xml (name → value), paginated.",
				Tools.schemaObject(Tools.intProp("offset", "start index") + "," + Tools.intProp("limit", "page size")),
				args -> Pagination.page(stringResources(), args, "strings")));

		// ---------- xref (out-of-heap SQLite, Tier-1) ----------
		t.add(Tools.tool("get_xrefs_to_class",
				"Classes that reference a class (out-of-heap SQLite graph). Empty + 'index building' note means retry.",
				Tools.schema(Tools.strProp("class_name", "FQN or raw class name") + ","
						+ Tools.intProp("offset", "") + "," + Tools.intProp("limit", ""), "class_name"),
				args -> {
					Integer sid = classSym(reqStr(args, "class_name"));
					List<Map<String, Object>> refs = sid == null ? List.of() : svc.graph().classUsers(sid);
					return withIndexNote(Pagination.page(refs, args, "references"));
				}));

		t.add(Tools.tool("get_xrefs_to_method",
				"Callers of a method (merged across overloads unless descriptor is given).",
				Tools.schema(Tools.strProp("class_name", "") + "," + Tools.strProp("method_name", "") + ","
						+ Tools.strProp("descriptor", "optional overload descriptor") + ","
						+ Tools.intProp("offset", "") + "," + Tools.intProp("limit", ""),
						"class_name", "method_name"),
				args -> withIndexNote(Pagination.page(
						methodXrefs(reqStr(args, "class_name"), reqStr(args, "method_name"),
								optStr(args, "descriptor"), true),
						args, "references"))));

		t.add(Tools.tool("get_xrefs_from_method", "Callees of a method (what it calls).",
				Tools.schema(Tools.strProp("class_name", "") + "," + Tools.strProp("method_name", "") + ","
						+ Tools.strProp("descriptor", "optional overload descriptor") + ","
						+ Tools.intProp("offset", "") + "," + Tools.intProp("limit", ""),
						"class_name", "method_name"),
				args -> withIndexNote(Pagination.page(
						methodXrefs(reqStr(args, "class_name"), reqStr(args, "method_name"),
								optStr(args, "descriptor"), false),
						args, "callees"))));

		t.add(Tools.tool("get_xrefs_to_field", "Methods that read/write a field.",
				Tools.schema(Tools.strProp("class_name", "") + "," + Tools.strProp("field_name", "") + ","
						+ Tools.intProp("offset", "") + "," + Tools.intProp("limit", ""),
						"class_name", "field_name"),
				args -> {
					JavaClass c = cls(reqStr(args, "class_name"));
					String fName = reqStr(args, "field_name");
					List<Map<String, Object>> refs = List.of();
					for (JavaField f : c.getFields()) {
						if (f.getName().equals(fName) && f.getFieldNode() != null) {
							Integer sid = svc.graph().symbolId(DexId.forField(f.getFieldNode()));
							refs = sid == null ? List.of() : svc.graph().incoming(sid, Db.E_USES_FIELD);
							break;
						}
					}
					return withIndexNote(Pagination.page(refs, args, "references"));
				}));

		t.add(Tools.tool("get_xrefs_from_class",
				"Classes that a class calls into (aggregated from its methods' callees).",
				Tools.schema(Tools.strProp("class_name", "") + "," + Tools.intProp("offset", "") + ","
						+ Tools.intProp("limit", ""), "class_name"),
				args -> {
					JavaClass c = cls(reqStr(args, "class_name"));
					List<Map<String, Object>> refs = svc.graph().callGraphFrom(DexId.forClass(c));
					return withIndexNote(Pagination.page(refs, args, "callees"));
				}));

		t.add(Tools.tool("get_call_graph", "Classes directly called by a class (alias of xrefs_from_class).",
				Tools.schema(Tools.strProp("class_name", "") + "," + Tools.intProp("offset", "") + ","
						+ Tools.intProp("limit", ""), "class_name"),
				args -> {
					JavaClass c = cls(reqStr(args, "class_name"));
					return withIndexNote(Pagination.page(svc.graph().callGraphFrom(DexId.forClass(c)), args, "callees"));
				}));

		t.add(Tools.tool("get_subclasses",
				"Direct subclasses / interface implementors of a class (model-derived; works for framework bases too).",
				Tools.schema(Tools.strProp("class_name", "FQN of the supertype") + "," + Tools.intProp("offset", "")
						+ "," + Tools.intProp("limit", ""), "class_name"),
				args -> {
					String name = reqStr(args, "class_name");
					List<String> subs = svc.subtypeIndex().getOrDefault(name, List.of());
					return Pagination.page(new ArrayList<>(subs), args, "subclasses");
				}));

		// ---------- resources ----------
		t.add(Tools.tool("get_android_manifest", "Raw AndroidManifest.xml.",
				Tools.schemaObject(null),
				args -> Map.of("manifest", orNote(ManifestUtil.manifestXml(svc.jadx()), "manifest not found"))));

		t.add(Tools.tool("get_main_activity", "Fully-qualified launcher activity (action MAIN + category LAUNCHER).",
				Tools.schemaObject(null),
				args -> Map.of("main_activity", orNote(ManifestUtil.mainActivity(svc.jadx()), "no launcher activity found"),
						"package", orNote(ManifestUtil.packageName(svc.jadx()), "?"))));

		t.add(Tools.tool("list_resource_files", "Paginated list of resource file names in the APK.",
				Tools.schemaObject(Tools.intProp("offset", "") + "," + Tools.intProp("limit", "")),
				args -> {
					List<String> names = new ArrayList<>();
					for (ResourceFile r : svc.jadx().getResources()) {
						names.add(r.getOriginalName());
					}
					return Pagination.page(names, args, "resources");
				}));

		t.add(Tools.tool("get_resource_file", "Text content of a resource file by name.",
				Tools.schema(Tools.strProp("name", "resource name as listed by list_resource_files"), "name"),
				args -> {
					String name = reqStr(args, "name");
					for (ResourceFile r : svc.jadx().getResources()) {
						if (r.getOriginalName().equals(name)) {
							return Map.of("name", name, "content", safe(() -> r.loadContent().getText().getCodeStr()));
						}
					}
					throw new IllegalArgumentException("Resource not found: " + name);
				}));

		// ---------- rename (persisted) ----------
		t.add(Tools.tool("rename_class", "Rename a class (immediate + journaled, replayed on reload).",
				Tools.schema(Tools.strProp("class_name", "") + "," + Tools.strProp("new_name", ""),
						"class_name", "new_name"),
				args -> svc.renameClass(reqStr(args, "class_name"), reqStr(args, "new_name"))));

		t.add(Tools.tool("rename_method", "Rename a method (pass descriptor to disambiguate overloads).",
				Tools.schema(Tools.strProp("class_name", "") + "," + Tools.strProp("method_name", "") + ","
						+ Tools.strProp("descriptor", "optional overload descriptor") + ","
						+ Tools.strProp("new_name", ""), "class_name", "method_name", "new_name"),
				args -> svc.renameMethod(reqStr(args, "class_name"), reqStr(args, "method_name"),
						optStr(args, "descriptor"), reqStr(args, "new_name"))));

		t.add(Tools.tool("rename_field", "Rename a field.",
				Tools.schema(Tools.strProp("class_name", "") + "," + Tools.strProp("field_name", "") + ","
						+ Tools.strProp("new_name", ""), "class_name", "field_name", "new_name"),
				args -> svc.renameField(reqStr(args, "class_name"), reqStr(args, "field_name"),
						reqStr(args, "new_name"))));

		t.add(Tools.tool("rename_package", "Rename/remap a package and the classes under it.",
				Tools.schema(Tools.strProp("old_package_name", "") + "," + Tools.strProp("new_package_name", ""),
						"old_package_name", "new_package_name"),
				args -> svc.renamePackage(reqStr(args, "old_package_name"), reqStr(args, "new_package_name"))));

		// ---------- code search (Tier-3) + name search ----------
		t.add(Tools.tool("search_in_code",
				"Full-text code search over decompiled sources via the FTS5 trigram index (≥3-char substrings); "
						+ "set regex=true (or use regex metachars) to fall back to ripgrep. While the index is still "
						+ "building it transparently covers EVERY already-decompiled class (FTS ∪ ripgrep over the "
						+ "decompiled sources), so the main package is searchable long before the build finishes; an "
						+ "index_note reports current coverage. Results are one row per class, default-filter "
						+ "standard-library hits, and are ranked app > obfuscated > third-party (limit applied after "
						+ "ranking). Use scope to restrict to a package subtree, include_libs to keep stdlib hits.",
				Tools.schema(Tools.strProp("query", "substring or regex") + ","
						+ Tools.boolProp("regex", "treat query as a regex (ripgrep)") + ","
						+ Tools.strProp("scope", "restrict to this package prefix subtree (e.g. com.app.feature)") + ","
						+ Tools.boolProp("include_libs", "include standard-library class hits (default false)") + ","
						+ Tools.intProp("limit", "max classes (default 100)"), "query"),
				args -> {
					int limit = Pagination.intArg(args, "limit", 100, 1, 1000);
					Map<String, Object> res = svc.codeSearch().searchInCode(reqStr(args, "query"),
							boolArg(args, "regex", false), limit, optStr(args, "scope"),
							boolArg(args, "include_libs", false));
					return withIndexNote(res);
				}));

		t.add(Tools.tool("search_classes_by_keyword",
				"Class FQNs containing a keyword (instant, model-only; case-insensitive substring).",
				Tools.schema(Tools.strProp("keyword", "") + "," + Tools.intProp("limit", "max (default 200)"),
						"keyword"),
				args -> {
					String kw = reqStr(args, "keyword").toLowerCase();
					int limit = Pagination.intArg(args, "limit", 200, 1, 2000);
					List<String> hits = new ArrayList<>();
					for (JavaClass c : svc.getClassesWithInners()) {
						if (c.getFullName().toLowerCase().contains(kw)) {
							hits.add(c.getFullName());
							if (hits.size() >= limit) {
								break;
							}
						}
					}
					return Map.of("count", hits.size(), "classes", hits);
				}));

		t.add(Tools.tool("search_method_by_name",
				"Methods whose name matches (case-insensitive substring), scanning the model. Capped; use string/xref "
						+ "tools for obfuscated apps where names are meaningless.",
				Tools.schema(Tools.strProp("method_name", "") + "," + Tools.intProp("limit", "max (default 200)"),
						"method_name"),
				args -> {
					String q = reqStr(args, "method_name").toLowerCase();
					int limit = Pagination.intArg(args, "limit", 200, 1, 2000);
					List<Map<String, Object>> hits = new ArrayList<>();
					outer: for (JavaClass c : svc.getClassesWithInners()) {
						for (JavaMethod m : c.getMethods()) {
							if (m.getName().toLowerCase().contains(q)) {
								hits.add(Map.of("class", c.getFullName(), "method", m.getName(),
										"descriptor", JadxService.safeDescriptor(m)));
								if (hits.size() >= limit) {
									break outer;
								}
							}
						}
					}
					return Map.of("count", hits.size(), "methods", hits);
				}));

		LOG.info("registered {} MCP tools", t.size());
		return t;
	}

	// ==================== handler helpers ====================

	private JavaClass cls(String name) {
		JavaClass c = svc.findClass(name);
		if (c == null) {
			throw new IllegalArgumentException("Class not found: " + name);
		}
		return c;
	}

	private Integer classSym(String name) {
		JavaClass c = cls(name);
		return svc.graph().classIdByDexId(DexId.forClass(c));
	}

	/** Callers (incoming) or callees (outgoing) of a method, merged across overloads when no descriptor. */
	private List<Map<String, Object>> methodXrefs(String className, String methodName, String descriptor,
			boolean incoming) {
		JavaClass c = cls(className);
		List<Map<String, Object>> out = new ArrayList<>();
		for (JavaMethod m : c.getMethods()) {
			if (!m.getName().equals(methodName)) {
				continue;
			}
			if (descriptor != null && !descriptor.isEmpty() && !JadxService.safeDescriptor(m).equals(descriptor)) {
				continue;
			}
			MethodNode mn = m.getMethodNode();
			if (mn == null) {
				continue;
			}
			Integer sid = svc.graph().symbolId(DexId.forMethod(mn));
			if (sid == null) {
				continue;
			}
			out.addAll(incoming ? svc.graph().incoming(sid, Db.E_CALLS) : svc.graph().outgoing(sid, Db.E_CALLS));
		}
		return out;
	}

	/**
	 * Attach a coverage hint while the index is incomplete (progressive-index-availability 4.2). The note
	 * reports the active tier and what is already searchable — decompiled vs FTS-indexed counts, plus
	 * {@code main_ready}/{@code xref_ready} — so a caller can judge result completeness without parsing
	 * index_status separately. Dropped entirely once {@code coverage_complete} (full FTS, no caveat). The
	 * tool's return structure is otherwise unchanged.
	 */
	private Object withIndexNote(Map<String, Object> base) {
		var st = svc.indexStatus();
		if (st.coverageComplete()) {
			return base; // full coverage — no caveat needed
		}
		var sm = st.toMap();
		Map<String, Object> m = new LinkedHashMap<>(base);
		m.put("index_note", "index building [" + sm.get("current_tier") + " tier, " + sm.get("percent")
				+ "%]: searched " + sm.get("decompiled_classes") + " decompiled classes ("
				+ sm.get("indexed_classes") + " in FTS); main_ready=" + sm.get("main_ready")
				+ ", xref_ready=" + sm.get("xref_ready")
				+ " — results may be partial; poll index_status until coverage_complete");
		return m;
	}

	private List<Map<String, Object>> stringResources() {
		List<Map<String, Object>> out = new ArrayList<>();
		try {
			for (ResourceFile r : svc.jadx().getResources()) {
				if (!r.getOriginalName().endsWith("strings.xml")) {
					continue;
				}
				String xml = r.loadContent().getText().getCodeStr();
				java.util.regex.Matcher mt = java.util.regex.Pattern
						.compile("<string[^>]*name=\"([^\"]+)\"[^>]*>(.*?)</string>", java.util.regex.Pattern.DOTALL)
						.matcher(xml);
				while (mt.find()) {
					out.add(Map.of("name", mt.group(1), "value", mt.group(2).trim()));
				}
			}
		} catch (Throwable t) {
			LOG.warn("string resource parse failed: {}", t.toString());
		}
		return out;
	}

	/** Best-effort single-method code slice from a class's decompiled source via brace matching. */
	private static String extractMethod(String source, String methodName) {
		if (source == null) {
			return null;
		}
		int idx = source.indexOf(" " + methodName + "(");
		if (idx < 0) {
			return null;
		}
		int lineStart = source.lastIndexOf('\n', idx) + 1;
		int brace = source.indexOf('{', idx);
		if (brace < 0) {
			return source.substring(lineStart, Math.min(source.length(), idx + 200));
		}
		int depth = 0;
		for (int i = brace; i < source.length(); i++) {
			char ch = source.charAt(i);
			if (ch == '{') {
				depth++;
			} else if (ch == '}') {
				depth--;
				if (depth == 0) {
					return source.substring(lineStart, i + 1);
				}
			}
		}
		return source.substring(lineStart, Math.min(source.length(), brace + 400));
	}

	// ==================== arg helpers ====================

	private static String reqStr(Map<String, Object> args, String key) {
		Object v = args == null ? null : args.get(key);
		if (v == null || v.toString().isEmpty()) {
			throw new IllegalArgumentException("Missing required argument: " + key);
		}
		return v.toString();
	}

	private static String optStr(Map<String, Object> args, String key) {
		Object v = args == null ? null : args.get(key);
		return v == null ? null : v.toString();
	}

	private static boolean boolArg(Map<String, Object> args, String key, boolean def) {
		Object v = args == null ? null : args.get(key);
		if (v == null) {
			return def;
		}
		if (v instanceof Boolean) {
			return (Boolean) v;
		}
		return Boolean.parseBoolean(v.toString());
	}

	private static Object orNote(String v, String note) {
		return v != null ? v : note;
	}

	@FunctionalInterface
	private interface ThrowingSupplier {
		String get() throws Exception;
	}

	private static String safe(ThrowingSupplier s) {
		try {
			return s.get();
		} catch (Throwable t) {
			return null;
		}
	}
}
