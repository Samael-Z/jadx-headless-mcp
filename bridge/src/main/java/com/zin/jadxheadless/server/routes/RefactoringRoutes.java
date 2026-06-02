package com.zin.jadxheadless.server.routes;

import com.zin.jadxheadless.server.BridgeContext;
import com.zin.jadxheadless.util.Errors;
import io.javalin.http.Context;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.api.data.impl.JadxNodeRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rename operations.
 *
 * <p>Renames go through jadx's <b>headless code-data</b> mechanism
 * ({@code JadxCodeData} + {@code JadxCodeRename} + {@code reloadCodeData()}; see
 * {@link BridgeContext#applyAndRecordRename}). They deliberately do <b>not</b> use the
 * {@code NodeRenamedByUser} event path the original GUI plugin used — that path is handled by
 * jadx-gui's {@code RenameService}, which is absent in a headless decompiler, so firing the
 * event was a silent no-op. Each rename is journaled next to the APK and replayed on the next
 * load, so renames now persist across restarts. Variable renames remain unsupported.
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
            String oldName = cls.getName();
            context.applyAndRecordRename(JadxNodeRef.forCls(cls), newName, true);
            logger.info("Renamed class {} -> {}", oldName, newName);
            ctx.json(Map.of("result", "Renamed class " + oldName + " to " + newName));
        } catch (Exception e) {
            Errors.internal(ctx, "Rename class failed: " + e.getMessage(), e, logger);
        }
    }

    /**
     * Rename a method. Requires either {@code class_name} + {@code method_name}, or a
     * fully-qualified {@code method_name} (e.g. {@code com.example.Foo.bar}). Case-SENSITIVE.
     * If multiple overloads share the name, pass {@code descriptor} (as returned by
     * get_methods_of_class) to disambiguate; otherwise we return 409 with the candidates.
     */
    public void handleRenameMethod(Context ctx) {
        String rawMethodName = ctx.queryParam("method_name");
        String newName = ctx.queryParam("new_name");
        if (missing(ctx, rawMethodName, newName)) return;

        String classFilter = ctx.queryParam("class_name");
        String methodName = rawMethodName;
        // Strip "(args)" parameter list if a lazy caller included it.
        if (methodName.contains("(")) methodName = methodName.substring(0, methodName.indexOf('('));

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
            JavaClass cls = context.findClassByFqn(classFilter);
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
                                + classFilter + "." + methodName + ". Pass `descriptor` to disambiguate.",
                        "descriptors", descs));
                return;
            }

            JavaMethod m = matches.get(0);
            context.applyAndRecordRename(JadxNodeRef.forMth(m), newName, true);
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
                context.applyAndRecordRename(JadxNodeRef.forFld(f), newName, true);
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
            // Package-level rename via a PKG node ref (jadx remaps the package alias and every
            // class under it). new_package_name is the full target package path.
            context.applyAndRecordRename(JadxNodeRef.forPkg(oldPkg), newPkg, true);
            logger.info("Renamed package {} -> {}", oldPkg, newPkg);
            ctx.json(Map.of("result", "Renamed package " + oldPkg + " to " + newPkg));
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
