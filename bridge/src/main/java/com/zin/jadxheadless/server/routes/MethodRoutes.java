package com.zin.jadxheadless.server.routes;

import com.zin.jadxheadless.server.BridgeContext;
import com.zin.jadxheadless.util.Errors;
import io.javalin.http.Context;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
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

    /**
     * Returns every method matching the given name. Java has overloading, so this
     * may return multiple entries (same name, different descriptors). Callers that
     * need a specific one can disambiguate with the `descriptor` field.
     *
     * Matching is CASE-SENSITIVE — `getFoo` and `getfoo` are different methods in
     * Java. Old code did equalsIgnoreCase here; that conflated distinct methods.
     */
    public void handleMethodByName(Context ctx) {
        String methodName = ctx.queryParam("method_name");
        if (methodName == null || methodName.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter 'method_name'", logger);
            return;
        }
        String className = ctx.queryParam("class_name");
        String descriptor = ctx.queryParam("descriptor");

        try {
            List<JavaClass> classes = context.getClassesWithInners();
            List<Map<String, Object>> overloads = new ArrayList<>();
            for (JavaClass cls : classes) {
                if (className != null && !className.isEmpty() && !cls.getFullName().equals(className)) {
                    continue;
                }
                for (JavaMethod m : cls.getMethods()) {
                    if (!m.getName().equals(methodName)) continue;
                    String desc = ClassRoutes.safeDescriptor(m);
                    if (descriptor != null && !descriptor.isEmpty() && !desc.equals(descriptor)) {
                        continue;
                    }
                    overloads.add(buildMethodResult(cls, m, desc));
                }
                // When class_name is specified, no need to keep scanning other classes.
                if (className != null && !className.isEmpty() && !overloads.isEmpty()) {
                    break;
                }
            }

            if (overloads.isEmpty()) {
                Errors.send(ctx, 404, "Method not found: " + methodName
                        + (className != null && !className.isEmpty() ? " in " + className : ""), logger);
                return;
            }

            // Backward-compatible response: if exactly one match, return its fields at
            // the top level (the v0.3.0 shape). Otherwise return a `overloads` array.
            if (overloads.size() == 1) {
                ctx.json(overloads.get(0));
            } else {
                Map<String, Object> out = new HashMap<>();
                out.put("query", methodName);
                out.put("class_name", className);
                out.put("overload_count", overloads.size());
                out.put("overloads", overloads);
                out.put("hint", "Multiple overloads found. Pass `descriptor` (e.g. " +
                        overloads.get(0).get("descriptor") + ") to disambiguate.");
                ctx.json(out);
            }
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

    private Map<String, Object> buildMethodResult(JavaClass cls, JavaMethod m, String descriptor) {
        Map<String, Object> out = new HashMap<>();
        out.put("class_name", cls.getFullName());
        out.put("method_name", m.getName());
        out.put("full_name", cls.getFullName() + "." + m.getName());
        out.put("return_type", String.valueOf(m.getReturnType()));
        out.put("is_constructor", m.isConstructor());
        out.put("descriptor", descriptor);
        out.put("declaration", String.valueOf(m.getCodeNodeRef()));
        try {
            out.put("code", m.getCodeStr());
        } catch (Exception e) {
            out.put("code", "// Error retrieving code: " + e.getMessage());
        }
        return out;
    }
}
