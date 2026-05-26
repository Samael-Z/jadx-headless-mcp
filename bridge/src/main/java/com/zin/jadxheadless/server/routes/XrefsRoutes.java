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
            List<ClassNode> classRefs = new ArrayList<>(node.getUseIn());
            List<MethodNode> methodRefs = new ArrayList<>(node.getUseInMth());
            for (JavaMethod jm : target.getMethods()) {
                if (jm.isConstructor()) {
                    methodRefs.addAll(jm.getMethodNode().getUseIn());
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
        try {
            JavaClass cls = find(className);
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            List<JavaMethod> matched = new ArrayList<>();
            String simple = cls.getName();
            for (JavaMethod m : cls.getMethods()) {
                if (!m.isConstructor() && m.getName().equals(methodName)) matched.add(m);
                else if (m.isConstructor() && methodName.equals(simple)) matched.add(m);
            }
            if (matched.isEmpty()) {
                Errors.send(ctx, 404, "Method " + methodName + " not in " + cls.getFullName(), logger);
                return;
            }
            List<JavaMethod> related = new ArrayList<>();
            for (JavaMethod m : matched) {
                for (JavaMethod r : methodWithOverrides(m)) {
                    if (!related.contains(r)) related.add(r);
                }
            }
            List<MethodNode> allRefs = new ArrayList<>();
            for (JavaMethod jm : related) {
                allRefs.addAll(jm.getMethodNode().getUseIn());
            }
            ctx.json(Pagination.paginate(ctx, collect(allRefs), "xrefs", "references", r -> r));
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
        for (JavaClass cls : context.getClassesWithInners()) {
            if (cls.getFullName().equals(fullName)) return cls;
        }
        return null;
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
