package com.zin.jadxheadless.server.routes;

import com.zin.jadxheadless.server.BridgeContext;
import com.zin.jadxheadless.util.Errors;
import io.javalin.http.Context;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.plugins.events.types.NodeRenamedByUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rename operations. State is in-memory only — renames are visible to subsequent
 * tool calls in the same session, but are not persisted to a .jobf mappings file
 * (that requires the jadx-rename-mappings plugin and an output project dir).
 *
 * Variable rename was dropped in v0.1.0 because it required forcing a class reload
 * (mainWindow.getWrapper() coupling) — will revisit when we wire up jadx-rename-mappings.
 */
public final class RefactoringRoutes {

    private static final Logger logger = LoggerFactory.getLogger(RefactoringRoutes.class);

    private final BridgeContext context;

    public RefactoringRoutes(BridgeContext context) {
        this.context = context;
    }

    public void handleRenameClass(Context ctx) {
        String className = ctx.queryParam("class_name");
        String newName = ctx.queryParam("new_name");
        if (missing(ctx, className, newName)) return;
        try {
            JadxDecompiler jadx = context.jadx();
            for (JavaClass cls : context.getClassesWithInners()) {
                if (cls.getFullName().equals(className)) {
                    NodeRenamedByUser event = new NodeRenamedByUser(
                            cls.getCodeNodeRef(), cls.getName(), newName);
                    event.setRenameNode(cls.getClassNode());
                    event.setResetName(newName.isEmpty());
                    jadx.events().send(event);
                    context.invalidateClassList();
                    logger.info("Renamed class {} -> {}", cls.getName(), newName);
                    ctx.json(Map.of("result", "Renamed class " + cls.getName() + " to " + newName));
                    return;
                }
            }
            Errors.send(ctx, 404, "Class not found: " + className, logger);
        } catch (Exception e) {
            Errors.internal(ctx, "Rename class failed: " + e.getMessage(), e, logger);
        }
    }

    public void handleRenameMethod(Context ctx) {
        String rawMethodName = ctx.queryParam("method_name");
        String newName = ctx.queryParam("new_name");
        if (missing(ctx, rawMethodName, newName)) return;

        // Accept either "ClassName.methodName" or just "methodName" (when class_name is given).
        String classFilter = ctx.queryParam("class_name");
        String methodName = rawMethodName;
        if (methodName.contains("(")) methodName = methodName.substring(0, methodName.indexOf('('));

        try {
            JadxDecompiler jadx = context.jadx();
            for (JavaClass cls : context.getClassesWithInners()) {
                if (classFilter != null && !classFilter.isEmpty() && !cls.getFullName().equals(classFilter)) continue;
                for (JavaMethod m : cls.getMethods()) {
                    String fqn = cls.getFullName() + "." + m.getName();
                    if (fqn.equalsIgnoreCase(methodName) || m.getName().equalsIgnoreCase(methodName)) {
                        NodeRenamedByUser event = new NodeRenamedByUser(
                                m.getCodeNodeRef(), m.getName(), newName);
                        event.setRenameNode(m.getMethodNode());
                        event.setResetName(newName.isEmpty());
                        jadx.events().send(event);
                        logger.info("Renamed method {} -> {}", fqn, newName);
                        ctx.json(Map.of("result", "Renamed method " + fqn + " to " + newName));
                        return;
                    }
                }
            }
            Errors.send(ctx, 404, "Method not found: " + rawMethodName, logger);
        } catch (Exception e) {
            Errors.internal(ctx, "Rename method failed: " + e.getMessage(), e, logger);
        }
    }

    public void handleRenameField(Context ctx) {
        String className = ctx.queryParam("class_name");
        String fieldName = ctx.queryParam("field_name");
        String newName = ctx.queryParam("new_name");
        if (newName == null || newName.isEmpty()) newName = ctx.queryParam("new_field_name");
        if (missing(ctx, className, fieldName) || newName == null) {
            if (newName == null) Errors.send(ctx, 400, "Missing required parameter 'new_name'", logger);
            return;
        }
        try {
            JadxDecompiler jadx = context.jadx();
            for (JavaClass cls : context.getClassesWithInners()) {
                if (!cls.getFullName().equals(className)) continue;
                for (JavaField f : cls.getFields()) {
                    if (f.getName().equals(fieldName)) {
                        NodeRenamedByUser event = new NodeRenamedByUser(
                                f.getCodeNodeRef(), f.getName(), newName);
                        event.setRenameNode(f.getFieldNode());
                        event.setResetName(newName.isEmpty());
                        jadx.events().send(event);
                        logger.info("Renamed field {}.{} -> {}", className, fieldName, newName);
                        ctx.json(Map.of("result", "Renamed field " + fieldName + " to " + newName));
                        return;
                    }
                }
            }
            Errors.send(ctx, 404, "Class or field not found: " + className + "#" + fieldName, logger);
        } catch (Exception e) {
            Errors.internal(ctx, "Rename field failed: " + e.getMessage(), e, logger);
        }
    }

    public void handleRenamePackage(Context ctx) {
        String oldPkg = ctx.queryParam("old_package_name");
        String newPkg = ctx.queryParam("new_package_name");
        if (missing(ctx, oldPkg, newPkg)) return;
        try {
            JadxDecompiler jadx = context.jadx();
            List<String> errors = new ArrayList<>();
            int total = 0;
            int renamed = 0;
            for (JavaClass cls : context.getClassesWithInners()) {
                String fqn = cls.getFullName();
                if (!(fqn.startsWith(oldPkg + ".") || fqn.equals(oldPkg))) continue;
                total++;
                try {
                    String suffix = fqn.substring(oldPkg.length());
                    String newFqn = newPkg + suffix;
                    NodeRenamedByUser event = new NodeRenamedByUser(
                            cls.getCodeNodeRef(), cls.getName(), newFqn);
                    event.setRenameNode(cls.getClassNode());
                    event.setResetName(false);
                    jadx.events().send(event);
                    renamed++;
                } catch (Exception inner) {
                    errors.add("Failed " + fqn + ": " + inner.getMessage());
                }
            }
            context.invalidateClassList();
            Map<String, Object> out = new HashMap<>();
            out.put("renamed", renamed);
            out.put("total", total);
            out.put("errors", errors);
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Rename package failed: " + e.getMessage(), e, logger);
        }
    }

    private boolean missing(Context ctx, String... args) {
        for (String a : args) {
            if (a == null || a.isEmpty()) {
                Errors.send(ctx, 400, "Missing required parameter", logger);
                return true;
            }
        }
        return false;
    }
}
