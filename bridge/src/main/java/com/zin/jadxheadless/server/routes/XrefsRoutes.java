package com.zin.jadxheadless.server.routes;

import com.zin.jadxheadless.server.BridgeContext;
import com.zin.jadxheadless.util.Errors;
import com.zin.jadxheadless.util.Pagination;
import io.javalin.http.Context;
import jadx.api.JavaClass;
import jadx.api.JavaField;
import jadx.api.JavaMethod;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class XrefsRoutes {

    private static final Logger logger = LoggerFactory.getLogger(XrefsRoutes.class);

    private final BridgeContext context;

    public XrefsRoutes(BridgeContext context) {
        this.context = context;
    }

    public void handleXrefsToClass(Context ctx) {
        String className = require(ctx, "class_name");
        if (className == null) return;
        try {
            JavaClass target = find(className);
            if (target == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            ClassNode node = target.getClassNode();
            if (node == null) {
                Errors.send(ctx, 500, "Class " + className + " has no backing ClassNode (synthetic?)", logger);
                return;
            }
            List<ClassNode> classRefs = new ArrayList<>(node.getUseIn());
            List<MethodNode> methodRefs = new ArrayList<>(node.getUseInMth());
            for (JavaMethod jm : target.getMethods()) {
                if (jm.isConstructor()) {
                    // getMethodNode() can return null for synthetic/bridge methods —
                    // skip those instead of NPE.
                    MethodNode mn = jm.getMethodNode();
                    if (mn != null) methodRefs.addAll(mn.getUseIn());
                }
            }

            Map<String, Set<String>> classToMethods = new HashMap<>();
            for (MethodNode m : methodRefs) {
                ClassNode parent = m.getParentClass();
                if (parent != null) {
                    classToMethods.computeIfAbsent(parent.getFullName(), k -> new HashSet<>()).add(m.getName());
                }
            }

            Set<String> seenClassNames = new HashSet<>();
            for (ClassNode c : classRefs) seenClassNames.add(c.getFullName());
            for (MethodNode m : methodRefs) {
                ClassNode parent = m.getParentClass();
                if (parent != null && !seenClassNames.contains(parent.getFullName())) {
                    classRefs.add(parent);
                    seenClassNames.add(parent.getFullName());
                }
            }

            List<Map<String, String>> refs = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ClassNode c : classRefs) {
                String cn = c.getFullName();
                if (classToMethods.containsKey(cn)) {
                    for (MethodNode m : methodRefs) {
                        if (m.getParentClass() != null && m.getParentClass().getFullName().equals(cn)) {
                            addUnique(refs, seen, methodRefInfo(m));
                        }
                    }
                } else {
                    Map<String, String> info = new HashMap<>();
                    info.put("class", cn);
                    info.put("method", "");
                    addUnique(refs, seen, info);
                }
            }
            ctx.json(Pagination.paginate(ctx, refs, "xrefs", "references", r -> r));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to find class references: " + e.getMessage(), e, logger);
        }
    }

    public void handleXrefsToMethod(Context ctx) {
        String className = require(ctx, "class_name");
        String methodName = require(ctx, "method_name");
        if (className == null || methodName == null) return;
        // Optional descriptor to disambiguate overloads. If absent, references from
        // all overloads are merged. Old code merged ALL by accident (it picked the
        // first match) which silently lost callers of other overloads.
        String descriptor = ctx.queryParam("descriptor");
        try {
            JavaClass cls = find(className);
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            List<JavaMethod> matched = new ArrayList<>();
            String simple = cls.getName();
            for (JavaMethod m : cls.getMethods()) {
                boolean nameMatch = (!m.isConstructor() && m.getName().equals(methodName))
                        || (m.isConstructor() && methodName.equals(simple));
                if (!nameMatch) continue;
                if (descriptor != null && !descriptor.isEmpty()
                        && !ClassRoutes.safeDescriptor(m).equals(descriptor)) {
                    continue;
                }
                matched.add(m);
            }
            if (matched.isEmpty()) {
                Errors.send(ctx, 404, "Method " + methodName + " not in " + cls.getFullName()
                        + (descriptor != null && !descriptor.isEmpty()
                                ? " (with descriptor " + descriptor + ")"
                                : ""), logger);
                return;
            }
            List<JavaMethod> related = new ArrayList<>();
            for (JavaMethod m : matched) {
                for (JavaMethod r : methodWithOverrides(m)) {
                    if (!related.contains(r)) related.add(r);
                }
            }
            List<MethodNode> allRefs = new ArrayList<>();
            int overloadsMatched = matched.size();
            for (JavaMethod jm : related) {
                MethodNode mn = jm.getMethodNode();
                if (mn != null) allRefs.addAll(mn.getUseIn());
            }
            Map<String, Object> paginated = Pagination.paginate(
                    ctx, collect(allRefs), "xrefs", "references", r -> r);
            if (overloadsMatched > 1) {
                paginated.put("matched_overloads", overloadsMatched);
                paginated.put("hint",
                        "Multiple overloads of `" + methodName + "` matched. " +
                        "Pass `descriptor` to scope to one — see get_methods_of_class for descriptors.");
            }
            ctx.json(paginated);
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to find method references: " + e.getMessage(), e, logger);
        }
    }

    public void handleXrefsToField(Context ctx) {
        String className = require(ctx, "class_name");
        String fieldName = require(ctx, "field_name");
        if (className == null || fieldName == null) return;
        try {
            JavaClass cls = find(className);
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            JavaField field = null;
            for (JavaField f : cls.getFields()) {
                if (f.getName().equals(fieldName)) {
                    field = f;
                    break;
                }
            }
            if (field == null) {
                Errors.send(ctx, 404, "Field " + fieldName + " not in " + cls.getFullName(), logger);
                return;
            }
            FieldNode fn = field.getFieldNode();
            if (fn == null) {
                Errors.send(ctx, 500, "Field " + fieldName + " has no backing FieldNode", logger);
                return;
            }
            List<MethodNode> refs = fn.getUseIn();
            ctx.json(Pagination.paginate(ctx, collect(refs), "xrefs", "references", r -> r));
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to find field references: " + e.getMessage(), e, logger);
        }
    }

    // helpers

    private String require(Context ctx, String key) {
        String v = ctx.queryParam(key);
        if (v == null || v.isEmpty()) {
            Errors.send(ctx, 400, "Missing required parameter '" + key + "'", logger);
            return null;
        }
        return v;
    }

    private JavaClass find(String fullName) {
        return context.findClassByFqn(fullName);
    }

    private List<JavaMethod> methodWithOverrides(JavaMethod m) {
        List<JavaMethod> rel = m.getOverrideRelatedMethods();
        return (rel != null && !rel.isEmpty()) ? rel : Collections.singletonList(m);
    }

    private List<Map<String, String>> collect(List<MethodNode> nodes) {
        Set<String> seen = new HashSet<>();
        List<Map<String, String>> out = new ArrayList<>();
        for (MethodNode m : nodes) addUnique(out, seen, methodRefInfo(m));
        return out;
    }

    private void addUnique(List<Map<String, String>> list, Set<String> seen, Map<String, String> info) {
        if (info == null) return;
        String key = info.get("class") + "#" + info.get("method");
        if (seen.add(key)) list.add(info);
    }

    private Map<String, String> methodRefInfo(MethodNode m) {
        if (m == null) return null;
        try {
            Map<String, String> info = new HashMap<>();
            ClassNode parent = m.getParentClass();
            if (parent != null) {
                info.put("class", parent.getFullName());
                ensureDecompiled(parent);
            } else {
                info.put("class", "");
            }
            JavaMethod jm = m.getJavaNode();
            String name = jm != null ? jm.getName() : m.getName();
            if ("<clinit>".equals(name)) name = "";
            info.put("method", name);
            return info;
        } catch (Exception e) {
            logger.warn("methodRefInfo failed: {}", e.getMessage());
            return null;
        }
    }

    private void ensureDecompiled(ClassNode classNode) {
        if (classNode != null && !classNode.getState().isProcessComplete()) {
            try {
                if (classNode.getJavaNode() != null) classNode.getJavaNode().decompile();
            } catch (Exception e) {
                logger.warn("decompile {} failed: {}", classNode.getFullName(), e.getMessage());
            }
        }
    }
}
