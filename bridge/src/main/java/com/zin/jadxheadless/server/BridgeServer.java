package com.zin.jadxheadless.server;

import com.zin.jadxheadless.server.routes.ClassRoutes;
import com.zin.jadxheadless.server.routes.MethodRoutes;
import com.zin.jadxheadless.server.routes.RefactoringRoutes;
import com.zin.jadxheadless.server.routes.ResourceRoutes;
import com.zin.jadxheadless.server.routes.XrefsRoutes;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BridgeServer {

    private static final Logger logger = LoggerFactory.getLogger(BridgeServer.class);

    private final BridgeContext context;
    private Javalin app;

    public BridgeServer(BridgeContext context) {
        this.context = context;
    }

    /**
     * Starts the HTTP server.
     * @param host bind address
     * @param port requested port, 0 to let the OS pick
     * @return actual port (resolved if 0 was passed)
     */
    public int start(String host, int port) {
        app = Javalin.create(cfg -> {
            cfg.showJavalinBanner = false;
            cfg.jetty.defaultHost = host;
        });

        ClassRoutes cls = new ClassRoutes(context);
        MethodRoutes mth = new MethodRoutes(context);
        ResourceRoutes res = new ResourceRoutes(context);
        XrefsRoutes xr = new XrefsRoutes(context);
        RefactoringRoutes rf = new RefactoringRoutes(context);

        // Health / general
        app.get("/health", ctx -> ctx.json(java.util.Map.of(
                "status", "ok",
                "apk", context.apkFile().getAbsolutePath(),
                "classes", context.getClassesWithInners().size())));

        // Classes
        app.get("/all-classes", cls::handleAllClasses);
        app.get("/class-source", cls::handleClassSource);
        app.get("/methods-of-class", cls::handleMethodsOfClass);
        app.get("/fields-of-class", cls::handleFieldsOfClass);
        app.get("/smali-of-class", cls::handleSmaliOfClass);
        app.get("/main-activity", cls::handleMainActivity);
        app.get("/main-application-classes-names", cls::handleMainApplicationClassesNames);
        app.get("/main-application-classes-code", cls::handleMainApplicationClassesCode);
        app.get("/search-classes-by-keyword", cls::handleSearchClassesByKeyword);
        app.get("/package-tree", cls::handlePackageTree);
        app.get("/cache-stats", cls::handleCacheStats);
        app.post("/cache-clear", cls::handleCacheClear);

        // Methods
        app.get("/method-by-name", mth::handleMethodByName);
        app.get("/search-method", mth::handleSearchMethod);

        // Resources
        app.get("/manifest", res::handleManifest);
        app.get("/strings", res::handleStrings);
        app.get("/list-all-resource-files-names", res::handleListAllResourceFilesNames);
        app.get("/get-resource-file", res::handleGetResourceFile);

        // Xrefs
        app.get("/xrefs-to-class", xr::handleXrefsToClass);
        app.get("/xrefs-to-method", xr::handleXrefsToMethod);
        app.get("/xrefs-to-field", xr::handleXrefsToField);

        // Refactoring (renames). State is in-memory only — no .jobf persistence yet.
        app.get("/rename-class", rf::handleRenameClass);
        app.get("/rename-method", rf::handleRenameMethod);
        app.get("/rename-field", rf::handleRenameField);
        app.get("/rename-package", rf::handleRenamePackage);

        app.start(host, port);
        int actual = app.port();
        logger.info("Bridge HTTP server listening on {}:{}", host, actual);
        return actual;
    }

    public void stop() {
        if (app != null) {
            try {
                app.stop();
            } catch (Throwable t) {
                logger.warn("Error stopping Javalin", t);
            } finally {
                app = null;
            }
        }
    }
}
