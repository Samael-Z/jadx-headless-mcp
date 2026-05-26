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
            JavaClass cls = context.findClassByFqn(className);
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            JadxDecompiler jadx = context.jadx();
            NodeRenamedByUser event = new NodeRenamedByUser(
                    cls.getCodeNodeRef(), cls.getName(), newName);
            event.setRenameNode(cls.getClassNode());
            event.setResetName(newName.isEmpty());
            jadx.events().send(event);
            // Class list itself doesn't change shape on rename — same instances, only
            // getFullName() returns the new name dynamically. But the FQN index keyed
            // off the OLD name, so refresh it so subsequent lookups by NEW name work.
            context.invalidateClassIndex();
            logger.info("Renamed class {} -> {}", cls.getName(), newName);
            ctx.json(Map.of("result", "Renamed class " + cls.getName() + " to " + newName));
        } catch (Exception e) {
            Errors.internal(ctx, "Rename class failed: " + e.getMessage(), e, logger);
        }
    }

    /**
     * Rename a method. Requires either:
     *   (a) `class_name` + `method_name`, OR
     *   (b) `method_name` = fully-qualified `class.method` form.
     *
     * Case-SENSITIVE matching — `getFoo` won't match `getfoo`. The previous
     * equalsIgnoreCase logic was dangerous: it could rename unrelated SDK methods
     * that happened to share a name modulo case.
     *
     * If multiple overloads of the same name exist on the class, the optional
     * `descriptor` query param disambiguates (matches `descriptor` returned by
     * get_methods_of_class). Without a descriptor we refuse to rename ambiguously
     * and return 409 with the list of candidates.
     */
    public void handleRenameMethod(Context ctx) {
        String rawMethodName = ctx.queryParam("method_name");
        String newName = ctx.queryParam("new_name");
        if (missing(ctx, rawMethodName, newName)) return;

        String classFilter = ctx.queryParam("class_name");
        String methodName = rawMethodName;
        // Strip "(args)" parameter list if present (lazy callers may include it).
        if (methodName.contains("(")) methodName = methodName.substring(0, methodName.indexOf('('));

        // Resolve class from either class_name param or FQN-style method_name.
        if (classFilter == null || classFilter.isEmpty()) {
            int dot = methodName.lastIndexOf('.');
            if (dot <= 0) {
                Errors.send(ctx, 400,
                        "rename_method requires either `class_name` or a fully-qualified " +
                        "`method_name` like `com.example.Foo.bar`. Refusing to rename by " +
                        "bare method name to avoid touching unrelated classes.", logger);
                return;
            }
            classFilter = methodName.substring(0, dot);
            methodName = methodName.substring(dot + 1);
        }

        String descriptor = ctx.queryParam("descriptor");

        try {
            JavaClass cls = null;
            for (JavaClass c : context.getClassesWithInners()) {
                if (c.getFullName().equals(classFilter)) { cls = c; break; }
            }
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + classFilter, logger);
                return;
            }

            List<JavaMethod> matches = new ArrayList<>();
            for (JavaMethod m : cls.getMethods()) {
                if (!m.getName().equals(methodName)) continue;
                if (descriptor != null && !descriptor.isEmpty()
                        && !ClassRoutes.safeDescriptor(m).equals(descriptor)) continue;
                matches.add(m);
            }
            if (matches.isEmpty()) {
                Errors.send(ctx, 404,
                        "Method " + methodName + " not in " + classFilter
                        + (descriptor != null && !descriptor.isEmpty()
                                ? " (with descriptor " + descriptor + ")"
                                : ""), logger);
                return;
            }
            if (matches.size() > 1) {
                List<String> descs = new ArrayList<>();
                for (JavaMethod m : matches) descs.add(ClassRoutes.safeDescriptor(m));
                ctx.status(409).json(Map.of(
                        "error", "Ambiguous: " + matches.size() + " overloads of "
                                + classFilter + "." + methodName + ". " +
                                "Pass `descriptor` to disambiguate.",
                        "descriptors", descs));
                return;
            }

            JavaMethod m = matches.get(0);
            JadxDecompiler jadx = context.jadx();
            NodeRenamedByUser event = new NodeRenamedByUser(
                    m.getCodeNodeRef(), m.getName(), newName);
            event.setRenameNode(m.getMethodNode());
            event.setResetName(newName.isEmpty());
            jadx.events().send(event);
            String fqn = classFilter + "." + methodName;
            logger.info("Renamed method {} -> {}", fqn, newName);
            ctx.json(Map.of("result", "Renamed method " + fqn + " to " + newName));
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
            JavaClass cls = context.findClassByFqn(className);
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            for (JavaField f : cls.getFields()) {
                if (!f.getName().equals(fieldName)) continue;
                JadxDecompiler jadx = context.jadx();
                NodeRenamedByUser event = new NodeRenamedByUser(
                        f.getCodeNodeRef(), f.getName(), newName);
                event.setRenameNode(f.getFieldNode());
                event.setResetName(newName.isEmpty());
                jadx.events().send(event);
                logger.info("Renamed field {}.{} -> {}", className, fieldName, newName);
                ctx.json(Map.of("result", "Renamed field " + fieldName + " to " + newName));
                return;
            }
            Errors.send(ctx, 404, "Field " + fieldName + " not in class " + className, logger);
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
            // Bulk rename — FQN index now contains stale old names; rebuild on next lookup.
            context.invalidateClassIndex();
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
