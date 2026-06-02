package com.zin.jadxheadless.server.routes;

import com.zin.jadxheadless.server.BridgeContext;
import com.zin.jadxheadless.util.Errors;
import com.zin.jadxheadless.util.Pagination;
import com.zin.jadxheadless.util.TextUtil;
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
            int max = TextUtil.maxChars(ctx);
            Map<String, Object> out = new HashMap<>();
            out.put("name", manifest.getOriginalName());
            out.put("type", "manifest/xml");
            out.put("content", TextUtil.cap(content, max));
            if (max > 0 && content != null && content.length() > max) {
                out.put("truncated", true);
                out.put("total_chars", content.length());
            }
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to read manifest: " + e.getMessage(), e, logger);
        }
    }

    /**
     * Match {@code res/values}-prefixed, {@code strings.xml}-suffixed paths —
     * both the default {@code res/values/strings.xml} AND locale variants like
     * {@code res/values-zh-rCN/strings.xml}. Previous code only matched the
     * default, hiding most translations on i18n apps.
     */
    private static boolean isStringsXml(String name) {
        if (name == null) return false;
        return name.startsWith("res/values") && name.endsWith("/strings.xml");
    }

    public void handleStrings(Context ctx) {
        try {
            List<Map<String, String>> entries = new ArrayList<>();
            for (ResourceFile resFile : context.jadx().getResources()) {
                try {
                    String name = resFile.getDeobfName();
                    if ("resources.arsc".equals(name)) {
                        for (ResContainer sub : resFile.loadContent().getSubFiles()) {
                            if (isStringsXml(sub.getFileName())) {
                                entries.add(Map.of("file", sub.getFileName(), "content", sub.getText().getCodeStr()));
                            }
                        }
                    } else if (isStringsXml(name)) {
                        entries.add(Map.of("file", name, "content", resFile.loadContent().getText().getCodeStr()));
                    }
                } catch (Exception inner) {
                    logger.warn("Error processing resource: {}", inner.getMessage());
                }
            }
            if (entries.isEmpty()) {
                Errors.send(ctx, 404, "No strings.xml found (looked for res/values*/strings.xml)", logger);
                return;
            }
            ctx.json(Pagination.paginate(ctx, entries, "resource/strings-xml", "strings", x -> x));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to fetch strings: " + e.getMessage(), e, logger);
        }
    }

    public void handleListAllResourceFilesNames(Context ctx) {
        try {
            // LinkedHashSet — preserves insertion order, drops duplicates. Without
            // the dedup, files that appear both as standalone resources AND inside
            // resources.arsc showed up twice; "resources.arsc" itself was also
            // listed alongside its expanded sub-files (missing `continue` bug).
            java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
            for (ResourceFile resFile : context.jadx().getResources()) {
                try {
                    String n = resFile.getDeobfName();
                    if ("resources.arsc".equals(n)) {
                        for (ResContainer sub : resFile.loadContent().getSubFiles()) {
                            names.add(sub.getFileName());
                        }
                        // Don't list the arsc archive itself — callers want logical
                        // resource paths, not the bundle container.
                        continue;
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
            ctx.json(Pagination.paginate(ctx, new ArrayList<>(names),
                    "application-resources", "files", x -> x));
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
        int max = TextUtil.maxChars(ctx);
        try {
            for (ResourceFile resFile : context.jadx().getResources()) {
                if (name.equals(resFile.getDeobfName())) {
                    respondResource(ctx, name, resFile.loadContent(), max);
                    return;
                }
                if ("resources.arsc".equals(resFile.getDeobfName())) {
                    for (ResContainer sub : resFile.loadContent().getSubFiles()) {
                        if (name.equals(sub.getFileName())) {
                            respondResource(ctx, name, sub, max);
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

    /**
     * Emit a resource as text, capped. Non-text/binary resources (e.g. {@code .properties}
     * loaded as raw data, fonts, images) make jadx's {@code getText()} throw — previously a
     * 500 ClassCastException. We now catch that and return a graceful binary marker instead.
     */
    private void respondResource(Context ctx, String name, ResContainer rc, int max) {
        String text = safeText(rc);
        Map<String, Object> file = new HashMap<>();
        file.put("file_name", name);
        if (text != null) {
            file.put("content", TextUtil.cap(text, max));
            if (max > 0 && text.length() > max) {
                file.put("truncated", true);
                file.put("total_chars", text.length());
            }
            ctx.json(Map.of("type", "resource/text", "file", file));
        } else {
            file.put("content", "");
            file.put("note", "Binary or non-text resource; cannot render as text.");
            ctx.json(Map.of("type", "resource/binary", "file", file));
        }
    }

    /** Extract a resource's text, or null if it is binary/non-text (jadx getText() throws). */
    private static String safeText(ResContainer rc) {
        try {
            if (rc != null && rc.getText() != null) {
                return rc.getText().getCodeStr();
            }
        } catch (Throwable t) {
            // binary / decoded-data / unsupported resource type
        }
        return null;
    }
}
