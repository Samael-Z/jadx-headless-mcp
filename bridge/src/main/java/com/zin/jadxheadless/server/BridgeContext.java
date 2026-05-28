package com.zin.jadxheadless.server;

import com.zin.jadxheadless.util.DecompilationCache;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import java.io.File;
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

    private final JadxDecompiler jadx;
    private final File apkFile;
    private final DecompilationCache cache = new DecompilationCache();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

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

    public ReadWriteLock lock() {
        return lock;
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
