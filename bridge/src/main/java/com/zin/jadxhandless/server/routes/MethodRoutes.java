package com.zin.jadxhandless.server.routes;

import com.zin.jadxhandless.server.BridgeContext;
import com.zin.jadxhandless.util.Errors;
import io.javalin.http.Context;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class MethodRoutes {

    private static final Logger logger = LoggerFactory.getLogger(MethodRoutes.class);

    private final BridgeContext context;

    public MethodRoutes(BridgeContext context) {
        this.context = context;
    }

    public void handleMethodByName(Context ctx) {
        String methodName = ctx.queryParam("method_name");
        if (methodName == null || methodName.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter 'method_name'", logger);
            return;
        }
        String className = ctx.queryParam("class_name");

        try {
            List<JavaClass> classes = context.getClassesWithInners();
            if (className == null || className.isEmpty()) {
                for (JavaClass cls : classes) {
                    for (JavaMethod m : cls.getMethods()) {
                        if (m.getName().equalsIgnoreCase(methodName)) {
                            ctx.json(buildMethodResult(cls, m));
                            return;
                        }
                    }
                }
            } else {
                for (JavaClass cls : classes) {
                    if (!cls.getFullName().equals(className)) continue;
                    for (JavaMethod m : cls.getMethods()) {
                        if (m.getName().equalsIgnoreCase(methodName)) {
                            ctx.json(buildMethodResult(cls, m));
                            return;
                        }
                    }
                }
            }
            Errors.send(ctx, 404, "Method not found: " + methodName, logger);
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to fetch method: " + e.getMessage(), e, logger);
        }
    }

    public void handleSearchMethod(Context ctx) {
        String methodName = ctx.queryParam("method_name");
        if (methodName == null || methodName.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter 'method_name'", logger);
            return;
        }
        try {
            String term = methodName.toLowerCase();
            List<String> hits = context.getClassesWithInners().parallelStream()
                    .filter(cls -> {
                        for (JavaMethod m : cls.getMethods()) {
                            if (m.getName().toLowerCase().contains(term)) return true;
                            if (m.isConstructor() && cls.getName().toLowerCase().contains(term)) return true;
                        }
                        return false;
                    })
                    .map(JavaClass::getFullName)
                    .collect(Collectors.toList());
            ctx.json(Map.of("query", methodName, "matches", hits, "count", hits.size()));
        } catch (Exception e) {
            Errors.internal(ctx, "Search failed: " + e.getMessage(), e, logger);
        }
    }

    private Map<String, Object> buildMethodResult(JavaClass cls, JavaMethod m) {
        Map<String, Object> out = new HashMap<>();
        out.put("class_name", cls.getFullName());
        out.put("method_name", m.getName());
        out.put("full_name", cls.getFullName() + "." + m.getName());
        out.put("return_type", String.valueOf(m.getReturnType()));
        out.put("is_constructor", m.isConstructor());
        out.put("declaration", String.valueOf(m.getCodeNodeRef()));
        try {
            out.put("code", m.getCodeStr());
        } catch (Exception e) {
            out.put("code", "// Error retrieving code: " + e.getMessage());
        }
        return out;
    }
}
