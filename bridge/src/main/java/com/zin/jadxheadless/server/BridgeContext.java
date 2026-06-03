package com.zin.jadxheadless.server;

import com.zin.jadxheadless.util.DecompilationCache;
import com.zin.jadxheadless.util.RenameStore;
import com.zin.jadxheadless.util.StringIndex;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.data.ICodeRename;
import jadx.api.data.IJavaNodeRef;
import jadx.api.data.impl.JadxCodeData;
import jadx.api.data.impl.JadxCodeRename;
import jadx.api.data.impl.JadxNodeRef;
import jadx.api.ResourceFile;
import jadx.core.utils.android.AndroidManifestParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Shared state held by all route handlers.
 *
 * The {@link JadxDecompiler} is the headless analog of MainWindow.getWrapper().getDecompiler()
 * in the original jadx-ai-mcp plugin. Most routes only need to call read-only methods on it
 * ({@code getClasses}, {@code getResources}, etc.) — those are safe to invoke concurrently
 * because Javalin dispatches requests on a thread pool.
 *
 * Class lookups are O(1) via {@link #findClassByFqn(String)} after the first call (lazy
 * FQN index built from {@code getClassesWithInners()}). Routes should prefer that over
 * scanning the full class list themselves.
 */
public final class BridgeContext {

    private static final Logger logger = LoggerFactory.getLogger(BridgeContext.class);

    private final JadxDecompiler jadx;
    private final File apkFile;
    private final DecompilationCache cache = new DecompilationCache();
    private final StringIndex stringIndex = new StringIndex();
    private final RenameStore renameStore = new RenameStore();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Cap on classes decompiled into the code index (full-corpus decompile is infeasible). */
    private static final int MAX_CODE_INDEX_CLASSES = 25_000;

    /** Cached, lazily computed snapshot of all classes including inner classes. */
    private volatile List<JavaClass> classesWithInners;
    /** Lazy FQN -> JavaClass index for O(1) lookups by name. */
    private volatile Map<String, JavaClass> classByFqn;
    /**
     * Lazy raw-name -> JavaClass index. The raw name is the original DEX class
     * name (e.g. {@code X.1C8X}) BEFORE jadx's built-in deobfuscator mangles
     * digit-leading or otherwise illegal-Java identifiers. Without this index
     * a caller who locates a class via external DEX parsing (where digit-leading
     * names like 1C8X are legal) cannot retrieve its source through jadx,
     * because jadx renames it to something like AnonymousClass1C8X internally.
     */
    private volatile Map<String, JavaClass> classByRawName;

    public BridgeContext(JadxDecompiler jadx, File apkFile) {
        this.jadx = jadx;
        this.apkFile = apkFile;
    }

    public JadxDecompiler jadx() {
        return jadx;
    }

    public File apkFile() {
        return apkFile;
    }

    public DecompilationCache cache() {
        return cache;
    }

    public StringIndex stringIndex() {
        return stringIndex;
    }

    public RenameStore renameStore() {
        return renameStore;
    }

    public ReadWriteLock lock() {
        return lock;
    }

    /**
     * Kick off the const-string inverted index build in the background (space-for-time).
     * Tries to load a persisted index next to the APK first; only rebuilds (and re-persists)
     * when absent/stale. Non-blocking: until the index is READY, find-string-usages falls
     * back to the bounded live scan. Safe to call once after the server is listening.
     */
    public void startStringIndexBuild() {
        Thread t = new Thread(() -> {
            try {
                Path idxFile = stringIndexFile();
                if (idxFile != null && stringIndex.load(idxFile)) {
                    // smali/method/field/type/call indexes reused from disk; still ensure the code index below
                } else {
                    logger.info("[string-index] building (no valid cache at {})", idxFile);
                    stringIndex.build(getClassesWithInners(), BridgeContext::safeSmali);
                    if (idxFile != null && stringIndex.status() == StringIndex.Status.READY) {
                        try {
                            stringIndex.save(idxFile);
                            logger.info("[string-index] persisted to {}", idxFile);
                        } catch (Exception e) {
                            logger.warn("[string-index] persist failed (in-memory index still active): {}", e.toString());
                        }
                    }
                }
                // Code-identifier index (decompile-based, main-package only). Separate file, best-effort.
                if (stringIndex.status() == StringIndex.Status.READY) {
                    buildOrLoadCodeIndex();
                }
            } catch (Throwable t2) {
                logger.warn("[string-index] background build error: {}", t2.toString());
            }
        }, "string-index-builder");
        t.setDaemon(true);
        // Slightly below normal so live request handling stays responsive during the build.
        try { t.setPriority(Thread.NORM_PRIORITY - 1); } catch (Throwable ignored) {}
        t.start();
    }

    /** Persisted string-index location ({@code <apk-dir>/.jadx-mcp-cache/<name>.<size>.stridx}). */
    private Path stringIndexFile() {
        return cacheFile(".stridx");
    }

    /** Persisted user-rename journal location ({@code <apk-dir>/.jadx-mcp-cache/<name>.<size>.renames.json}). */
    private Path renameStoreFile() {
        return cacheFile(".renames.json");
    }

    /** Persisted code-identifier index location ({@code <apk-dir>/.jadx-mcp-cache/<name>.<size>.codeidx}). */
    private Path codeIndexFile() {
        return cacheFile(".codeidx");
    }

    /**
     * Resolve a per-APK cache file under {@code <apk-dir>/.jadx-mcp-cache/} (or the temp dir if the
     * APK's directory is not writable). Keyed by APK name + byte size so a changed APK gets fresh
     * files; {@code suffix} distinguishes the artifact (.stridx, .renames.json).
     */
    private Path cacheFile(String suffix) {
        try {
            File parent = apkFile.getAbsoluteFile().getParentFile();
            File dir;
            if (parent != null && parent.canWrite()) {
                dir = new File(parent, ".jadx-mcp-cache");
            } else {
                dir = new File(System.getProperty("java.io.tmpdir"), "jadx-mcp-cache");
            }
            return new File(dir, apkFile.getName() + "." + apkFile.length() + suffix).toPath();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Load or build the code-identifier index for the main-package subset (best-effort, separate
     * .codeidx file). Decompile is expensive, so this is capped at {@link #MAX_CODE_INDEX_CLASSES}
     * and runs on the same low-priority background thread as the smali index.
     */
    private void buildOrLoadCodeIndex() {
        Path codeFile = codeIndexFile();
        try {
            if (codeFile != null && stringIndex.loadCodeIndex(codeFile)) return;
            String pkg = manifestPackageName();
            if (pkg == null || pkg.isEmpty()) {
                logger.info("[code-index] skipped (no manifest package)");
                return;
            }
            List<JavaClass> mainClasses = new ArrayList<>();
            for (JavaClass c : getClassesWithInners()) {
                String fqn = c.getFullName();
                if (fqn.startsWith(pkg + ".") || fqn.equals(pkg)) {
                    mainClasses.add(c);
                    if (mainClasses.size() >= MAX_CODE_INDEX_CLASSES) break;
                }
            }
            if (mainClasses.isEmpty()) {
                logger.info("[code-index] skipped (no classes under package {})", pkg);
                return;
            }
            logger.info("[code-index] building over {} main-package classes (pkg={}, cap={})",
                    mainClasses.size(), pkg, MAX_CODE_INDEX_CLASSES);
            stringIndex.buildCodeIndex(mainClasses, c -> {
                try {
                    String cached = cache.get(c.getFullName());
                    if (cached != null) return cached;
                    String code = c.getCode();
                    if (code != null) cache.put(c.getFullName(), code);
                    return code;
                } catch (Throwable t) {
                    return null;
                }
            });
            if (codeFile != null && stringIndex.codeIndexReady()) {
                try {
                    stringIndex.saveCodeIndex(codeFile);
                    logger.info("[code-index] persisted to {}", codeFile);
                } catch (Exception e) {
                    logger.warn("[code-index] persist failed (in-memory code index still active): {}", e.toString());
                }
            }
        } catch (Throwable t) {
            logger.warn("[code-index] build error: {}", t.toString());
        }
    }

    /** Read the {@code package} attribute from AndroidManifest.xml, or null. */
    private String manifestPackageName() {
        try {
            ResourceFile manifestRes = AndroidManifestParser.getAndroidManifest(jadx.getResources());
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
        } catch (Throwable t) {
            logger.warn("[code-index] manifest package parse failed: {}", t.toString());
            return null;
        }
    }

    /**
     * Lazily ensure a {@link JadxCodeData} is attached to the decompiler args. This is jadx's
     * headless store for user renames; the GUI's NodeRenamedByUser path needs jadx-gui's
     * RenameService (absent here), so renames must go through code-data + {@code reloadCodeData()}.
     */
    private JadxCodeData ensureCodeData() {
        JadxCodeData cd = (JadxCodeData) jadx.getArgs().getCodeData();
        if (cd == null) {
            cd = new JadxCodeData();
            cd.setRenames(new ArrayList<>());
            cd.setComments(new ArrayList<>());
            jadx.getArgs().setCodeData(cd);
        }
        return cd;
    }

    /**
     * Apply a rename the headless way and (optionally) journal it for cross-restart persistence:
     * add a {@link JadxCodeRename} to the code-data, fire {@code reloadCodeData()} so jadx's
     * RenameVisitor re-applies it on next decompile, then clear the now-stale decompilation cache
     * and FQN index. Re-renaming the same node replaces its prior entry.
     */
    public synchronized void applyAndRecordRename(JadxNodeRef ref, String newName, boolean persist) {
        JadxCodeData cd = ensureCodeData();
        List<ICodeRename> list = new ArrayList<>(cd.getRenames());
        list.removeIf(r -> ref.equals(r.getNodeRef()));
        list.add(new JadxCodeRename(ref, newName));
        cd.setRenames(list);
        jadx.reloadCodeData();
        cache.clear();
        invalidateClassIndex();
        if (persist) {
            Map<String, String> rec = new HashMap<>();
            rec.put("type", ref.getType().name());
            rec.put("cls", ref.getDeclaringClass());
            if (ref.getShortId() != null) rec.put("id", ref.getShortId());
            rec.put("new", newName == null ? "" : newName);
            renameStore.record(rec);
        }
    }

    /**
     * Load the persisted rename journal and re-apply every rename in one batch (see
     * {@link RenameStore}). Call once at startup AFTER the model is loaded and BEFORE serving, so a
     * reconnecting client sees prior renames already applied. Also arms the store to persist new
     * renames. Records that no longer resolve are carried in code-data and harmlessly ignored by jadx.
     */
    public void loadAndReplayRenames() {
        Path f = renameStoreFile();
        if (f == null) return;
        int loaded = renameStore.load(f);
        renameStore.setFile(f); // persist new renames regardless of whether anything was loaded
        if (loaded == 0) return;
        JadxCodeData cd = ensureCodeData();
        List<ICodeRename> list = new ArrayList<>(cd.getRenames());
        int applied = 0;
        for (Map<String, String> rec : renameStore.all()) {
            JadxNodeRef ref = refFromRecord(rec);
            String newName = rec.get("new");
            if (ref == null || newName == null) continue;
            list.removeIf(r -> ref.equals(r.getNodeRef()));
            list.add(new JadxCodeRename(ref, newName));
            applied++;
        }
        cd.setRenames(list);
        jadx.reloadCodeData();
        cache.clear();
        invalidateClassIndex();
        logger.info("[renames] replayed {}/{} persisted renames", applied, loaded);
    }

    /** Rebuild a {@link JadxNodeRef} from a journal record ({@code type}, {@code cls}, {@code id}). */
    private static JadxNodeRef refFromRecord(Map<String, String> rec) {
        String type = rec.get("type");
        String cls = rec.get("cls");
        if (type == null || cls == null) return null;
        try {
            return new JadxNodeRef(IJavaNodeRef.RefType.valueOf(type), cls, rec.get("id"));
        } catch (Exception e) {
            return null;
        }
    }

    /** Wrap {@code getSmali()} -- never throw during the index build. */
    static String safeSmali(JavaClass c) {
        try {
            return c.getSmali();
        } catch (Throwable t) {
            return null;
        }
    }

    /** All classes including inner classes. Cached after first call. */
    public List<JavaClass> getClassesWithInners() {
        List<JavaClass> local = classesWithInners;
        if (local == null) {
            synchronized (this) {
                local = classesWithInners;
                if (local == null) {
                    local = jadx.getClassesWithInners();
                    classesWithInners = local;
                }
            }
        }
        return local;
    }

    /**
     * O(1) lookup of a class by its current fully-qualified name. Builds an index
     * lazily on first call from the cached class list. Used by xref/rename routes
     * that previously scanned the whole 90k-class list per request.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>Exact match on jadx's current FQN ({@code getFullName()}).</li>
     *   <li>Exact match on the original DEX raw name ({@code getRawName()}).
     *       This lets callers who located a class via external DEX parsing
     *       (e.g. {@code LX/1C8X;} -&gt; {@code X.1C8X}) retrieve it even after
     *       jadx's deobfuscator has renamed digit-leading classes to
     *       {@code AnonymousClass1C8X} or similar.</li>
     *   <li>Linear scan -- final fallback for in-session renames that
     *       happened after the indexes were built.</li>
     * </ol>
     */
    public JavaClass findClassByFqn(String fqn) {
        if (fqn == null) return null;
        ensureIndexes();
        JavaClass hit = classByFqn.get(fqn);
        if (hit != null) return hit;
        // Raw-name fallback for digit-leading or otherwise jadx-renamed classes.
        Map<String, JavaClass> raw = classByRawName;
        if (raw != null) {
            hit = raw.get(fqn);
            if (hit != null) return hit;
        }
        // Last-resort linear scan -- covers in-session renames after indexing.
        for (JavaClass c : getClassesWithInners()) {
            if (fqn.equals(c.getFullName())) return c;
            if (fqn.equals(safeRawName(c))) return c;
        }
        return null;
    }

    private void ensureIndexes() {
        if (classByFqn != null && classByRawName != null) return;
        synchronized (this) {
            if (classByFqn != null && classByRawName != null) return;
            List<JavaClass> all = getClassesWithInners();
            Map<String, JavaClass> byFqn = new HashMap<>(all.size() * 2);
            Map<String, JavaClass> byRaw = new HashMap<>(all.size() * 2);
            for (JavaClass c : all) {
                byFqn.put(c.getFullName(), c);
                String raw = safeRawName(c);
                // Only index raw when it differs from the current FQN -- avoids
                // bloating the map with duplicate entries on un-renamed classes.
                if (raw != null && !raw.equals(c.getFullName())) {
                    byRaw.put(raw, c);
                }
            }
            classByFqn = byFqn;
            classByRawName = byRaw;
        }
    }

    /** Wrap {@code getRawName()} -- some jadx versions throw on unloadable classes. */
    private static String safeRawName(JavaClass c) {
        try {
            return c.getRawName();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Drop the FQN/raw-name indexes (e.g. after a bulk rename). Class list itself is unaffected. */
    public void invalidateClassIndex() {
        classByFqn = null;
        classByRawName = null;
    }
}
