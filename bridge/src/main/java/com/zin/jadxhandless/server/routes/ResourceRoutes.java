package com.zin.jadxhandless.server.routes;

import com.zin.jadxhandless.server.BridgeContext;
import com.zin.jadxhandless.util.Errors;
import com.zin.jadxhandless.util.Pagination;
import io.javalin.http.Context;
import jadx.api.ResourceFile;
import jadx.core.utils.android.AndroidManifestParser;
import jadx.core.xmlgen.ResContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ResourceRoutes {

    private static final Logger logger = LoggerFactory.getLogger(ResourceRoutes.class);

    private final BridgeContext context;

    public ResourceRoutes(BridgeContext context) {
        this.context = context;
    }

    public void handleManifest(Context ctx) {
        try {
            ResourceFile manifest = AndroidManifestParser.getAndroidManifest(context.jadx().getResources());
            if (manifest == null) {
                Errors.send(ctx, 404, "AndroidManifest.xml not found", logger);
                return;
            }
            String content = manifest.loadContent().getText().getCodeStr();
            ctx.json(Map.of(
                    "name", manifest.getOriginalName(),
                    "type", "manifest/xml",
                    "content", content));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to read manifest: " + e.getMessage(), e, logger);
        }
    }

    public void handleStrings(Context ctx) {
        try {
            List<Map<String, String>> entries = new ArrayList<>();
            for (ResourceFile resFile : context.jadx().getResources()) {
                try {
                    String name = resFile.getDeobfName();
                    if ("resources.arsc".equals(name)) {
                        for (ResContainer sub : resFile.loadContent().getSubFiles()) {
                            if ("res/values/strings.xml".equals(sub.getFileName())) {
                                entries.add(Map.of("file", sub.getFileName(), "content", sub.getText().getCodeStr()));
                            }
                        }
                    } else if ("res/values/strings.xml".equals(name)) {
                        entries.add(Map.of("file", name, "content", resFile.loadContent().getText().getCodeStr()));
                    }
                } catch (Exception inner) {
                    logger.warn("Error processing resource: {}", inner.getMessage());
                }
            }
            if (entries.isEmpty()) {
                Errors.send(ctx, 404, "No strings.xml found", logger);
                return;
            }
            ctx.json(Pagination.paginate(ctx, entries, "resource/strings-xml", "strings", x -> x));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to fetch strings: " + e.getMessage(), e, logger);
        }
    }

    public void handleListAllResourceFilesNames(Context ctx) {
        try {
            List<String> names = new ArrayList<>();
            for (ResourceFile resFile : context.jadx().getResources()) {
                try {
                    String n = resFile.getDeobfName();
                    if ("resources.arsc".equals(n)) {
                        for (ResContainer sub : resFile.loadContent().getSubFiles()) {
                            names.add(sub.getFileName());
                        }
                    }
                    names.add(n);
                } catch (Exception inner) {
                    logger.warn("Error reading resource: {}", inner.getMessage());
                }
            }
            if (names.isEmpty()) {
                Errors.send(ctx, 404, "No resources found", logger);
                return;
            }
            ctx.json(Pagination.paginate(ctx, names, "application-resources", "files", x -> x));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to list resources: " + e.getMessage(), e, logger);
        }
    }

    public void handleGetResourceFile(Context ctx) {
        // Accept either resource_name (Python MCP server) or file_name (legacy jadx-ai-mcp) for compatibility
        String name = ctx.queryParam("resource_name");
        if (name == null || name.isEmpty()) name = ctx.queryParam("file_name");
        if (name == null || name.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter 'resource_name'", logger);
            return;
        }
        try {
            for (ResourceFile resFile : context.jadx().getResources()) {
                if (name.equals(resFile.getDeobfName())) {
                    Map<String, String> file = new HashMap<>();
                    file.put("file_name", resFile.getDeobfName());
                    file.put("content", resFile.loadContent().getText().getCodeStr());
                    ctx.json(Map.of("type", "resource/text", "file", file));
                    return;
                }
                if ("resources.arsc".equals(resFile.getDeobfName())) {
                    for (ResContainer sub : resFile.loadContent().getSubFiles()) {
                        if (name.equals(sub.getFileName())) {
                            Map<String, String> file = new HashMap<>();
                            file.put("file_name", sub.getFileName());
                            file.put("content", sub.getText().getCodeStr());
                            ctx.json(Map.of("type", "resource/text", "file", file));
                            return;
                        }
                    }
                }
            }
            Errors.send(ctx, 404, "Resource not found: " + name, logger);
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to fetch resource: " + e.getMessage(), e, logger);
        }
    }
}
