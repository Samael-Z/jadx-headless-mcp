package com.zin.jadxhandless.server;

import com.zin.jadxhandless.util.DecompilationCache;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;

import java.io.File;
import java.util.List;
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
 * For mutating operations (renames) the events() bus serializes internally, but a class
 * unload+reprocess (see RefactoringRoutes.handleRenameVariable) is not thread-safe — guard
 * it with the write side of {@link #lock}.
 */
public final class BridgeContext {

    private final JadxDecompiler jadx;
    private final File apkFile;
    private final DecompilationCache cache = new DecompilationCache();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Cached, lazily computed snapshot of all classes including inner classes. */
    private volatile List<JavaClass> classesWithInners;

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

    /** Invalidate cached class list after a rename/reprocess. */
    public void invalidateClassList() {
        classesWithInners = null;
    }
}
