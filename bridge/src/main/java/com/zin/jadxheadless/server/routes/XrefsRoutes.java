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

    /**
     * Outgoing calls (callees) of a single method — the complement of {@link #handleXrefsToMethod}.
     * jadx exposes incoming refs ({@code getUseIn}) but no ready callee list, so we parse the
     * method's smali {@code invoke-*} opcodes. That works even on R8/anti-tamper hardened classes
     * whose Java decompile is empty (same rationale as the const-string index). With no
     * {@code descriptor}, the callees of every overload of {@code method_name} are merged.
     */
    public void handleXrefsFromMethod(Context ctx) {
        String className = require(ctx, "class_name");
        String methodName = require(ctx, "method_name");
        if (className == null || methodName == null) return;
        String descriptor = ctx.queryParam("descriptor");
        try {
            JavaClass cls = find(className);
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            String smali = safeSmali(cls);
            if (smali == null || smali.isEmpty()) {
                Errors.send(ctx, 404, "No smali available for " + cls.getFullName(), logger);
                return;
            }
            List<Map<String, Object>> callees = calleesOfMethod(smali, methodName, descriptor);
            Map<String, Object> out = Pagination.paginate(ctx, callees, "xrefs-from", "callees", r -> r);
            out.put("class", cls.getFullName());
            out.put("method", methodName);
            if (callees.isEmpty()) {
                out.put("note", "No invoke targets parsed — check the method name (pass ?descriptor= for a "
                        + "specific overload, in smali form e.g. (Ljava/lang/String;)V), or the method makes no calls.");
            }
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to find outgoing calls: " + e.getMessage(), e, logger);
        }
    }

    /** Outgoing calls of an ENTIRE class: every distinct {@code invoke-*} target across all its methods. */
    public void handleXrefsFromClass(Context ctx) {
        String className = require(ctx, "class_name");
        if (className == null) return;
        try {
            JavaClass cls = find(className);
            if (cls == null) {
                Errors.send(ctx, 404, "Class not found: " + className, logger);
                return;
            }
            String smali = safeSmali(cls);
            if (smali == null || smali.isEmpty()) {
                Errors.send(ctx, 404, "No smali available for " + cls.getFullName(), logger);
                return;
            }
            List<Map<String, Object>> callees = calleesOfClass(smali);
            Map<String, Object> out = Pagination.paginate(ctx, callees, "xrefs-from", "callees", r -> r);
            out.put("class", cls.getFullName());
            ctx.json(out);
        } catch (Exception e) {
            Errors.internal(ctx, "Failed to find outgoing calls: " + e.getMessage(), e, logger);
        }
    }

    // ---- smali invoke parsing (outgoing-call extraction) ----

    /** Invoke targets inside the smali block of {@code methodName} (all overloads if descriptor is blank). */
    private static List<Map<String, Object>> calleesOfMethod(String smali, String methodName, String descriptor) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean inTarget = false;
        for (String raw : smali.split("\n")) {
            String line = raw.trim();
            if (!inTarget) {
                if (line.startsWith(".method ")) {
                    String sig = methodSignature(line); // "name(args)ret"
                    if (sig != null && methodName.equals(sigName(sig)) && descMatches(sig, descriptor)) {
                        inTarget = true;
                    }
                }
            } else if (line.startsWith(".end method")) {
                inTarget = false;
            } else if (line.startsWith("invoke")) {
                addInvoke(line, out, seen);
            }
        }
        return out;
    }

    /** Every distinct invoke target in the whole class smali. */
    private static List<Map<String, Object>> calleesOfClass(String smali) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String raw : smali.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("invoke")) addInvoke(line, out, seen);
        }
        return out;
    }

    /**
     * Distinct callee CLASS FQNs (dotted) across all {@code invoke-*} ops in a class's smali — the
     * set of classes this class directly calls into. Shared with the call-graph route so it doesn't
     * duplicate the smali invoke-parsing. Self-references are excluded by the caller.
     */
    public static Set<String> calleeClassFqns(String smali) {
        Set<String> out = new HashSet<>();
        if (smali == null) return out;
        for (String raw : smali.split("\n")) {
            String line = raw.trim();
            if (!line.startsWith("invoke")) continue;
            Map<String, Object> t = parseInvokeTarget(line);
            if (t != null) {
                Object cn = t.get("class");
                if (cn != null) out.add(cn.toString());
            }
        }
        return out;
    }

    private static void addInvoke(String line, List<Map<String, Object>> out, Set<String> seen) {
        Map<String, Object> t = parseInvokeTarget(line);
        if (t == null) return;
        String key = t.get("class") + "->" + t.get("method") + t.get("descriptor");
        if (seen.add(key)) out.add(t);
    }

    /**
     * Parse a smali invoke line into {class (dotted), method, descriptor}. Handles all invoke-*
     * variants (the {@code , L<owner>;-><name>(<args>)<ret>} tail is identical across them).
     * Returns null for invoke-custom / polymorphic forms that lack that tail.
     */
    private static Map<String, Object> parseInvokeTarget(String line) {
        int owner = line.indexOf(", L");
        if (owner < 0) return null;
        int arrow = line.indexOf(";->", owner);
        if (arrow < 0) return null;
        int paren = line.indexOf('(', arrow);
        if (paren < 0) return null;
        String ownerSmali = line.substring(owner + 3, arrow); // between ", L" and ";"
        String method = line.substring(arrow + 3, paren);      // between ";->" and "("
        String descriptor = line.substring(paren);             // "(args)ret"
        Map<String, Object> m = new HashMap<>();
        m.put("class", ownerSmali.replace('/', '.'));
        m.put("method", method);
        m.put("descriptor", descriptor);
        return m;
    }

    /** Extract the "name(args)ret" signature token from a ".method <mods> name(args)ret" line. */
    private static String methodSignature(String methodLine) {
        int paren = methodLine.indexOf('(');
        if (paren < 0) return null;
        int start = methodLine.lastIndexOf(' ', paren);
        if (start < 0) return null;
        return methodLine.substring(start + 1);
    }

    private static String sigName(String sig) {
        int paren = sig.indexOf('(');
        return paren < 0 ? sig : sig.substring(0, paren);
    }

    /** Lenient overload match: blank descriptor matches any; otherwise accept the full sig or its "(args)ret" tail. */
    private static boolean descMatches(String sig, String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) return true;
        if (sig.equals(descriptor)) return true;
        int paren = sig.indexOf('(');
        String tail = paren >= 0 ? sig.substring(paren) : sig;
        return tail.equals(descriptor) || sig.endsWith(descriptor);
    }

    private static String safeSmali(JavaClass c) {
        try {
            return c.getSmali();
        } catch (Throwable t) {
            return null;
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
