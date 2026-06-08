package com.zin.jadxheadless.util;

import jadx.api.JavaClass;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;

/**
 * Builds <b>dex-stable identities</b> (D5) for classes/methods/fields — the keys for the SQLite
 * symbol graph and code-search index. These are derived from the raw DEX descriptors / signatures,
 * which do NOT change when display-name recovery settings change (source-name / kotlin-metadata /
 * deobf / user rename). Display names are stored as a separate metadata layer on top, so toggling
 * naming options never invalidates the structural index — only refreshes the display column.
 *
 * <p>Form:
 * <ul>
 *   <li>class:  {@code <raw-class-name>}            e.g. {@code com.ss.android.X}</li>
 *   <li>method: {@code <raw-class>-><shortId>}      e.g. {@code com.ss.X->a(Ljava/lang/String;)V}</li>
 *   <li>field:  {@code <raw-class>-><shortId>}      e.g. {@code com.ss.X->b:I}</li>
 * </ul>
 * {@code shortId} is the jadx dex signature (raw types), stable across runs.
 */
public final class DexId {

	private DexId() {
	}

	public static String forClass(ClassNode cls) {
		try {
			return cls.getRawName();
		} catch (Throwable t) {
			return cls.getClassInfo().getFullName();
		}
	}

	public static String forClass(JavaClass cls) {
		try {
			return cls.getRawName();
		} catch (Throwable t) {
			return cls.getFullName();
		}
	}

	public static String forMethod(MethodNode mth) {
		String owner = ownerRaw(mth.getParentClass());
		try {
			return owner + "->" + mth.getMethodInfo().getShortId();
		} catch (Throwable t) {
			return owner + "->" + mth.getName();
		}
	}

	public static String forField(FieldNode fld) {
		String owner = ownerRaw(fld.getParentClass());
		try {
			return owner + "->" + fld.getFieldInfo().getShortId();
		} catch (Throwable t) {
			return owner + "->" + fld.getName();
		}
	}

	private static String ownerRaw(ClassNode parent) {
		if (parent == null) {
			return "?";
		}
		return forClass(parent);
	}
}
