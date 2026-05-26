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
    /** Lazy FQN → JavaClass index for O(1) lookups by name. */
    private volatile Map<String, JavaClass> classByFqn;

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
     * Falls back to a linear scan if the FQN isn't in the index (e.g. when the
     * caller passes a name that the underlying JavaClass.getFullName() now returns
     * differently after an in-session rename).
     */
    public JavaClass findClassByFqn(String fqn) {
        if (fqn == null) return null;
        Map<String, JavaClass> idx = classByFqn;
        if (idx == null) {
            synchronized (this) {
                idx = classByFqn;
                if (idx == null) {
                    List<JavaClass> all = getClassesWithInners();
                    Map<String, JavaClass> built = new HashMap<>(all.size() * 2);
                    for (JavaClass c : all) {
                        built.put(c.getFullName(), c);
                    }
                    idx = built;
                    classByFqn = idx;
                }
            }
        }
        JavaClass hit = idx.get(fqn);
        if (hit != null) return hit;
        // Fallback linear scan — covers renames that happened after the index was built.
        for (JavaClass c : getClassesWithInners()) {
            if (c.getFullName().equals(fqn)) return c;
        }
        return null;
    }

    /** Drop the FQN index (e.g. after a bulk rename). Class list itself is unaffected. */
    public void invalidateClassIndex() {
        classByFqn = null;
    }
}
