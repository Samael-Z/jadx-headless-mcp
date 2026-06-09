package com.zin.jadxheadless.jadx;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zin.jadxheadless.index.AnalysisScope;
import com.zin.jadxheadless.index.CodeSearchIndex;
import com.zin.jadxheadless.index.Db;
import com.zin.jadxheadless.index.IndexBuilder;
import com.zin.jadxheadless.index.IndexStatus;
import com.zin.jadxheadless.util.CacheLayout;
import com.zin.jadxheadless.util.DexId;
import com.zin.jadxheadless.util.ManifestUtil;

import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.args.UseSourceNameAsClassNameAlias;
import jadx.api.data.ICodeRename;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.plugins.loader.JadxBasePluginLoader;
import jadx.api.plugins.pass.JadxPassInfo;
import jadx.api.plugins.pass.impl.SimpleJadxPassInfo;
import jadx.api.plugins.pass.types.JadxPreparePass;
import jadx.core.Jadx;
import jadx.core.dex.instructions.args.ArgType;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

import com.zin.jadxheadless.util.RenameStore;

/**
 * The headless jadx core (headless-jadx-server). Owns the resident {@link JadxDecompiler} for the
 * current APK, installs the bounded/disk code cache (D2) and the SQLite usage cache (D7), kicks off
 * the background index build, and serves the model lookups + renames the MCP tools need. One APK is
 * resident at a time; {@link #loadApk} swaps in a new one and releases the old.
 *
 * <p>Naming (D4): {@code useSourceNameAsClassNameAlias=ALWAYS} + kotlin-metadata (auto, on classpath);
 * {@code deobf} default OFF (an optional knob) because on heavily-obfuscated apps it degrades package
 * names without recovering semantics.
 */
public final class JadxService implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(JadxService.class);
	private static final int CODE_CACHE_LRU = 1500;

	private volatile Session session;

	// Index-scope options (CLI: --index-include / --index-exclude / --index-all); applied on every load.
	private volatile List<String> indexInclude = List.of();
	private volatile List<String> indexExclude = List.of();
	private volatile boolean indexAll = false;
	private volatile boolean indexThirdParty = true;

	/** Set the selective-index scope options from CLI args (see {@link AnalysisScope}). */
	public void setIndexOptions(List<String> include, List<String> exclude, boolean all) {
		this.indexInclude = include == null ? List.of() : include;
		this.indexExclude = exclude == null ? List.of() : exclude;
		this.indexAll = all;
	}

	/** Whether named T3 third-party libraries are indexed (default true; {@code --no-index-third-party} off). */
	public void setIndexThirdParty(boolean indexThirdParty) {
		this.indexThirdParty = indexThirdParty;
	}

	/** Per-APK resident state. Swapped atomically on {@link JadxService#loadApk}. */
	public static final class Session {
		final Path apk;
		final boolean deobf;
		final Path cacheDir;
		final JadxDecompiler jadx;
		final DiskCodeCache diskCache;
		final SqliteUsageInfoCache usageCache;
		final Db db;
		final com.zin.jadxheadless.index.SymbolGraph graph;
		final CodeSearchIndex codeSearch;
		final IndexStatus indexStatus;
		final IndexBuilder builder;
		final AnalysisScope scope;
		final RenameStore renameStore = new RenameStore();
		final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

		// lazy class indexes (model-only)
		volatile List<JavaClass> classesWithInners;
		volatile Map<String, JavaClass> byFqn;
		volatile Map<String, JavaClass> byRaw;
		volatile Map<String, List<String>> subtypes;

		Session(Path apk, boolean deobf, Path cacheDir, JadxDecompiler jadx, DiskCodeCache diskCache,
				SqliteUsageInfoCache usageCache, Db db, com.zin.jadxheadless.index.SymbolGraph graph,
				CodeSearchIndex codeSearch, IndexStatus indexStatus, IndexBuilder builder, AnalysisScope scope) {
			this.apk = apk;
			this.deobf = deobf;
			this.cacheDir = cacheDir;
			this.jadx = jadx;
			this.diskCache = diskCache;
			this.usageCache = usageCache;
			this.db = db;
			this.graph = graph;
			this.codeSearch = codeSearch;
			this.indexStatus = indexStatus;
			this.builder = builder;
			this.scope = scope;
		}
	}

	// ==================== lifecycle ====================

	/** Load (or switch to) an APK and start the background index build. Blocks until jadx finishes loading. */
	public synchronized Map<String, Object> loadApk(String path, boolean deobf) throws Exception {
		File apkFile = new File(path);
		if (!apkFile.isFile()) {
			throw new IllegalArgumentException("APK/input not found: " + path);
		}
		closeSession(); // release any previous APK first

		long t0 = System.currentTimeMillis();
		Path apk = apkFile.toPath().toAbsolutePath();
		Path cacheDir = CacheLayout.forApk(apk);
		String codeVersion = Jadx.getVersion() + "|src=ALWAYS|kotlin=on|deobf=" + deobf;
		LOG.info("[load] {} -> cacheDir {}", apk, cacheDir);

		JadxArgs args = new JadxArgs();
		args.getInputFiles().add(apkFile);
		args.setUseSourceNameAsClassNameAlias(UseSourceNameAsClassNameAlias.ALWAYS);
		args.setDeobfuscationOn(deobf);
		args.setShowInconsistentCode(true);
		// Classpath-only loader: skip ~/.jadx/plugins (avoids GUI plugins that need jadx.gui.*).
		args.setPluginLoader(new JadxBasePluginLoader());
		SqliteUsageInfoCache usageCache = new SqliteUsageInfoCache();
		args.setUsageInfoCache(usageCache);
		// empty code-data so renames can be replayed after load
		JadxCodeData codeData = new JadxCodeData();
		codeData.setRenames(new ArrayList<>());
		codeData.setComments(new ArrayList<>());
		args.setCodeData(codeData);

		// Install the bounded/disk code cache once the RootNode exists (D2) — see jadx-gui's pattern.
		final DiskCodeCache[] diskHolder = new DiskCodeCache[1];
		JadxDecompiler jadx = new JadxDecompiler(args);
		jadx.addCustomPass(new JadxPreparePass() {
			@Override
			public JadxPassInfo getInfo() {
				return new SimpleJadxPassInfo("HeadlessCacheInit");
			}

			@Override
			public void init(RootNode root) {
				DiskCodeCache disk = new DiskCodeCache(root, cacheDir, codeVersion);
				diskHolder[0] = disk;
				root.getArgs().setCodeCache(new BoundedCodeCache(disk, CODE_CACHE_LRU));
			}
		});

		jadx.load();
		int classCount = jadx.getClassesWithInners().size();
		LOG.info("[load] {} classes in {}ms", classCount, System.currentTimeMillis() - t0);

		Db db = Db.open(cacheDir);
		Path codeSrcDir = cacheDir.resolve("code").resolve("sources");
		com.zin.jadxheadless.index.SymbolGraph graph = new com.zin.jadxheadless.index.SymbolGraph(db);
		// FTS is split into M shards (fast-index-pipeline D2). The shard count is fixed for an index
		// (routing must be stable): reuse the stored count if present, else the env/default.
		int shardCount = com.zin.jadxheadless.index.FtsShards.shardCountFromEnv();
		String storedShards = db.getMeta("fts_shards");
		if (storedShards != null) {
			try {
				shardCount = Integer.parseInt(storedShards.trim());
			} catch (NumberFormatException ignored) {
				// keep env/default
			}
		}
		com.zin.jadxheadless.index.FtsShards shards =
				new com.zin.jadxheadless.index.FtsShards(cacheDir.resolve("fts"), shardCount);
		CodeSearchIndex codeSearch = new CodeSearchIndex(db, codeSrcDir, shards);
		IndexStatus indexStatus = new IndexStatus();

		// Analysis-value scope: drives result filtering/ranking (layer 1) and selective indexing (layer 2).
		String manifestPkg = ManifestUtil.packageName(jadx);
		AnalysisScope scope = new AnalysisScope(manifestPkg, indexInclude, indexExclude, indexThirdParty, indexAll);
		codeSearch.setScope(scope);
		codeSearch.setStatus(indexStatus); // cross-phase search needs the build phase (D5)
		indexStatus.setScope(scope.describe());
		LOG.info("[scope] manifest package={}; index scope={}", manifestPkg, scope.describe());

		IndexBuilder builder = new IndexBuilder(jadx, db, graph, codeSearch, indexStatus,
				diskHolder[0], usageCache, scope);

		Session s = new Session(apk, deobf, cacheDir, jadx, diskHolder[0], usageCache, db, graph,
				codeSearch, indexStatus, builder, scope);
		this.session = s;

		// replay persisted renames before serving, then start the background index build
		replayRenames(s);
		builder.start();

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("loaded", apk.toString());
		out.put("classes", classCount);
		out.put("cache_dir", cacheDir.toString());
		out.put("deobf", deobf);
		out.put("load_ms", System.currentTimeMillis() - t0);
		out.put("index", s.indexStatus.toMap());
		return out;
	}

	public synchronized void closeSession() {
		Session s = session;
		if (s == null) {
			return;
		}
		session = null;
		try {
			s.builder.cancel();
		} catch (Throwable ignored) {
			// ignore
		}
		try {
			s.jadx.close();
		} catch (Throwable t) {
			LOG.warn("jadx close failed: {}", t.toString());
		}
		try {
			s.codeSearch.close(); // close the FTS shard connections (checkpoint WAL)
		} catch (Throwable ignored) {
			// ignore
		}
		try {
			s.db.close();
		} catch (Throwable ignored) {
			// ignore
		}
		LOG.info("[session] released {}", s.apk);
	}

	@Override
	public void close() {
		closeSession();
	}

	/** Current session or throw a clear "load_apk first" error. */
	public Session require() {
		Session s = session;
		if (s == null) {
			throw new IllegalStateException("No APK loaded. Call load_apk(path) first.");
		}
		return s;
	}

	public @Nullable Session current() {
		return session;
	}

	public com.zin.jadxheadless.index.SymbolGraph graph() {
		return require().graph;
	}

	public com.zin.jadxheadless.index.CodeSearchIndex codeSearch() {
		return require().codeSearch;
	}

	public com.zin.jadxheadless.index.IndexStatus indexStatus() {
		return require().indexStatus;
	}

	public JadxDecompiler jadx() {
		return require().jadx;
	}

	public Map<String, Object> currentApkInfo() {
		Session s = session;
		if (s == null) {
			return Map.of("loaded", false);
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("loaded", true);
		m.put("apk", s.apk.toString());
		m.put("cache_dir", s.cacheDir.toString());
		m.put("deobf", s.deobf);
		m.put("classes", getClassesWithInners().size());
		m.put("index", s.indexStatus.toMap());
		return m;
	}

	// ==================== class lookup (model) ====================

	public List<JavaClass> getClassesWithInners() {
		Session s = require();
		List<JavaClass> local = s.classesWithInners;
		if (local == null) {
			synchronized (s) {
				local = s.classesWithInners;
				if (local == null) {
					local = s.jadx.getClassesWithInners();
					s.classesWithInners = local;
				}
			}
		}
		return local;
	}

	public List<JavaClass> getTopClasses() {
		return require().jadx.getClasses();
	}

	/** O(1) class lookup by current FQN, with raw-name and linear-scan fallbacks (ports v1 behavior). */
	public @Nullable JavaClass findClass(String name) {
		if (name == null) {
			return null;
		}
		Session s = require();
		ensureIndexes(s);
		JavaClass hit = s.byFqn.get(name);
		if (hit != null) {
			return hit;
		}
		hit = s.byRaw.get(name);
		if (hit != null) {
			return hit;
		}
		for (JavaClass c : getClassesWithInners()) {
			if (name.equals(c.getFullName()) || name.equals(safeRaw(c))) {
				return c;
			}
		}
		return null;
	}

	private void ensureIndexes(Session s) {
		if (s.byFqn != null && s.byRaw != null) {
			return;
		}
		synchronized (s) {
			if (s.byFqn != null && s.byRaw != null) {
				return;
			}
			List<JavaClass> all = getClassesWithInners();
			Map<String, JavaClass> fqn = new HashMap<>(all.size() * 2);
			Map<String, JavaClass> raw = new HashMap<>(all.size() * 2);
			for (JavaClass c : all) {
				fqn.put(c.getFullName(), c);
				String r = safeRaw(c);
				if (r != null && !r.equals(c.getFullName())) {
					raw.put(r, c);
				}
			}
			s.byFqn = fqn;
			s.byRaw = raw;
		}
	}

	/** Model-derived supertype→direct-subtypes index for {@code get_subclasses} (cheap, no decompile). */
	public Map<String, List<String>> subtypeIndex() {
		Session s = require();
		Map<String, List<String>> local = s.subtypes;
		if (local != null) {
			return local;
		}
		synchronized (s) {
			if (s.subtypes != null) {
				return s.subtypes;
			}
			Map<String, List<String>> map = new HashMap<>();
			for (JavaClass c : getClassesWithInners()) {
				ClassNode node;
				String sub;
				try {
					sub = c.getFullName();
					node = c.getClassNode();
				} catch (Throwable t) {
					continue;
				}
				if (node == null) {
					continue;
				}
				try {
					String sup = argTypeFqn(node.getSuperClass());
					if (sup != null && !sup.equals("java.lang.Object")) {
						map.computeIfAbsent(sup, k -> new ArrayList<>()).add(sub);
					}
					List<ArgType> ifaces = node.getInterfaces();
					if (ifaces != null) {
						for (ArgType it : ifaces) {
							String f = argTypeFqn(it);
							if (f != null) {
								map.computeIfAbsent(f, k -> new ArrayList<>()).add(sub);
							}
						}
					}
				} catch (Throwable ignored) {
					// skip unreadable hierarchy
				}
			}
			s.subtypes = map;
			return map;
		}
	}

	private void invalidateClassIndex(Session s) {
		s.byFqn = null;
		s.byRaw = null;
		s.subtypes = null;
	}

	// ==================== renames (headless code-data + journal; ports v1) ====================

	public Map<String, Object> renameClass(String fqn, String newName) {
		JavaClass cls = found(fqn);
		String old = cls.getName();
		applyAndRecordRename(JadxNodeRef.forCls(cls), newName, true);
		return Map.of("result", "Renamed class " + old + " -> " + newName, "class", fqn);
	}

	public Map<String, Object> renameMethod(String clsFqn, String methodName, String descriptor, String newName) {
		JavaClass cls = found(clsFqn);
		String mName = methodName.contains("(") ? methodName.substring(0, methodName.indexOf('(')) : methodName;
		List<JavaMethod> matches = new ArrayList<>();
		for (JavaMethod m : cls.getMethods()) {
			if (!m.getName().equals(mName)) {
				continue;
			}
			if (descriptor != null && !descriptor.isEmpty() && !safeDescriptor(m).equals(descriptor)) {
				continue;
			}
			matches.add(m);
		}
		if (matches.isEmpty()) {
			throw new IllegalArgumentException("Method " + mName + " not in " + clsFqn);
		}
		if (matches.size() > 1) {
			List<String> descs = new ArrayList<>();
			for (JavaMethod m : matches) {
				descs.add(safeDescriptor(m));
			}
			Map<String, Object> err = new LinkedHashMap<>();
			err.put("error", "Ambiguous: " + matches.size() + " overloads of " + clsFqn + "." + mName
					+ ". Pass `descriptor` to disambiguate.");
			err.put("descriptors", descs);
			return err;
		}
		applyAndRecordRename(JadxNodeRef.forMth(matches.get(0)), newName, true);
		return Map.of("result", "Renamed method " + clsFqn + "." + mName + " -> " + newName);
	}

	public Map<String, Object> renameField(String clsFqn, String fieldName, String newName) {
		JavaClass cls = found(clsFqn);
		for (JavaField f : cls.getFields()) {
			if (f.getName().equals(fieldName)) {
				applyAndRecordRename(JadxNodeRef.forFld(f), newName, true);
				return Map.of("result", "Renamed field " + clsFqn + "." + fieldName + " -> " + newName);
			}
		}
		throw new IllegalArgumentException("Field " + fieldName + " not in " + clsFqn);
	}

	public Map<String, Object> renamePackage(String oldPkg, String newPkg) {
		applyAndRecordRename(JadxNodeRef.forPkg(oldPkg), newPkg, true);
		return Map.of("result", "Renamed package " + oldPkg + " -> " + newPkg);
	}

	private JavaClass found(String fqn) {
		JavaClass c = findClass(fqn);
		if (c == null) {
			throw new IllegalArgumentException("Class not found: " + fqn);
		}
		return c;
	}

	private void applyAndRecordRename(JadxNodeRef ref, String newName, boolean persist) {
		Session s = require();
		s.lock.writeLock().lock();
		try {
			JadxCodeData cd = (JadxCodeData) s.jadx.getArgs().getCodeData();
			if (cd == null) {
				cd = new JadxCodeData();
				cd.setRenames(new ArrayList<>());
				cd.setComments(new ArrayList<>());
				s.jadx.getArgs().setCodeData(cd);
			}
			List<ICodeRename> list = new ArrayList<>(cd.getRenames());
			list.removeIf(r -> ref.equals(r.getNodeRef()));
			list.add(new JadxCodeRename(ref, newName));
			cd.setRenames(list);
			s.jadx.reloadCodeData();
			invalidateClassIndex(s);
			if (persist) {
				Map<String, String> rec = new HashMap<>();
				rec.put("type", ref.getType().name());
				rec.put("cls", ref.getDeclaringClass());
				if (ref.getShortId() != null) {
					rec.put("id", ref.getShortId());
				}
				rec.put("new", newName == null ? "" : newName);
				s.renameStore.record(rec);
			}
		} finally {
			s.lock.writeLock().unlock();
		}
	}

	private void replayRenames(Session s) {
		Path f = s.cacheDir.resolve("renames.json");
		int loaded = s.renameStore.load(f);
		s.renameStore.setFile(f);
		if (loaded == 0) {
			return;
		}
		JadxCodeData cd = (JadxCodeData) s.jadx.getArgs().getCodeData();
		List<ICodeRename> list = new ArrayList<>(cd.getRenames());
		int applied = 0;
		for (Map<String, String> rec : s.renameStore.all()) {
			JadxNodeRef ref = refFromRecord(rec);
			String newName = rec.get("new");
			if (ref == null || newName == null) {
				continue;
			}
			list.removeIf(r -> ref.equals(r.getNodeRef()));
			list.add(new JadxCodeRename(ref, newName));
			applied++;
		}
		cd.setRenames(list);
		s.jadx.reloadCodeData();
		LOG.info("[renames] replayed {}/{} persisted renames", applied, loaded);
	}

	private static JadxNodeRef refFromRecord(Map<String, String> rec) {
		String type = rec.get("type");
		String cls = rec.get("cls");
		if (type == null || cls == null) {
			return null;
		}
		try {
			return new JadxNodeRef(IJavaNodeRef.RefType.valueOf(type), cls, rec.get("id"));
		} catch (Exception e) {
			return null;
		}
	}

	// ==================== misc ====================

	public Map<String, Object> clearCache() {
		Session s = require();
		s.diskCache.close(); // drains + keeps disk; in-heap LRU cleared on next session
		invalidateClassIndex(s);
		return Map.of("result", "in-heap caches cleared (disk index retained for reuse)");
	}

	public static String dexIdFor(JavaClass c) {
		return DexId.forClass(c);
	}

	private static @Nullable String safeRaw(JavaClass c) {
		try {
			return c.getRawName();
		} catch (Throwable t) {
			return null;
		}
	}

	private static @Nullable String argTypeFqn(ArgType t) {
		if (t == null) {
			return null;
		}
		try {
			return t.isObject() ? t.getObject() : null;
		} catch (Throwable e) {
			return null;
		}
	}

	/** Smali-style descriptor of a method, for overload disambiguation. */
	public static String safeDescriptor(JavaMethod m) {
		try {
			return m.getMethodNode().getMethodInfo().getShortId();
		} catch (Throwable t) {
			return m.getName();
		}
	}
}
