package com.zin.jadxheadless.util;

import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import jadx.api.JadxDecompiler;
import jadx.api.ResourceFile;
import jadx.core.utils.android.AndroidManifestParser;

/** AndroidManifest helpers (package name, raw XML, launcher activity) shared by tools and the indexer. */
public final class ManifestUtil {

	private static final Logger LOG = LoggerFactory.getLogger(ManifestUtil.class);

	private ManifestUtil() {
	}

	/** Raw AndroidManifest.xml text, or null if absent/unreadable. */
	public static String manifestXml(JadxDecompiler jadx) {
		try {
			ResourceFile mf = AndroidManifestParser.getAndroidManifest(jadx.getResources());
			if (mf == null) {
				return null;
			}
			return mf.loadContent().getText().getCodeStr();
		} catch (Throwable t) {
			LOG.warn("manifest read failed: {}", t.toString());
			return null;
		}
	}

	/** The {@code package} attribute of <manifest>, or null. */
	public static String packageName(JadxDecompiler jadx) {
		String xml = manifestXml(jadx);
		if (xml == null) {
			return null;
		}
		try {
			Element root = parse(xml).getDocumentElement();
			String pkg = root.getAttribute("package");
			return pkg.isEmpty() ? null : pkg;
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Fully-qualified launcher activity (action MAIN + category LAUNCHER), resolving a leading
	 * {@code .} against the manifest package. Returns null if none found.
	 */
	public static String mainActivity(JadxDecompiler jadx) {
		String xml = manifestXml(jadx);
		if (xml == null) {
			return null;
		}
		try {
			Document doc = parse(xml);
			String pkg = doc.getDocumentElement().getAttribute("package");
			NodeList activities = doc.getElementsByTagName("activity");
			String hit = scanForLauncher(activities, pkg);
			if (hit != null) {
				return hit;
			}
			return scanForLauncher(doc.getElementsByTagName("activity-alias"), pkg);
		} catch (Exception e) {
			return null;
		}
	}

	/** Manifest component tags whose {@code android:name} is a real app class (Tier-1 entry points). */
	private static final String[] ENTRY_TAGS = { "activity", "service", "receiver", "provider", "application" };

	/**
	 * Fully-qualified names of the app's manifest entry-point components — every {@code <activity>},
	 * {@code <service>}, {@code <receiver>}, {@code <provider>} plus the {@code <application>} class.
	 * These are Tier-1 of the progressive build (decompiled first, so the most analysis-relevant code
	 * is searchable within seconds; see progressive-index-availability D2). Leading {@code .} / bare
	 * names are resolved against the manifest package. Returns an empty set when there is no manifest or
	 * it fails to parse — the builder then proceeds straight to the main-package tier (D2 fallback).
	 *
	 * <p>{@code <activity-alias>} is intentionally skipped: its {@code android:name} is an alias, not a
	 * class — the real class is its {@code targetActivity}, already covered by the {@code <activity>} scan.
	 */
	public static Set<String> entryClasses(JadxDecompiler jadx) {
		String xml = manifestXml(jadx);
		if (xml == null) {
			return Set.of();
		}
		Set<String> out = new LinkedHashSet<>();
		try {
			Document doc = parse(xml);
			String pkg = doc.getDocumentElement().getAttribute("package");
			for (String tag : ENTRY_TAGS) {
				NodeList nodes = doc.getElementsByTagName(tag);
				for (int i = 0; i < nodes.getLength(); i++) {
					String fqn = resolveName(attrName((Element) nodes.item(i)), pkg);
					if (fqn != null) {
						out.add(fqn);
					}
				}
			}
		} catch (Exception e) {
			LOG.warn("entry-class parse failed: {}", e.toString());
		}
		return out;
	}

	/** Resolve a manifest component name (leading {@code .} or bare) against the package; null if blank. */
	private static String resolveName(String name, String pkg) {
		if (name == null || name.isEmpty()) {
			return null;
		}
		if (name.startsWith(".") && pkg != null && !pkg.isEmpty()) {
			return pkg + name;
		}
		if (!name.contains(".") && pkg != null && !pkg.isEmpty()) {
			return pkg + "." + name;
		}
		return name;
	}

	private static String scanForLauncher(NodeList nodes, String pkg) {
		for (int i = 0; i < nodes.getLength(); i++) {
			Element act = (Element) nodes.item(i);
			if (!hasLauncherFilter(act)) {
				continue;
			}
			String name = act.getAttribute("android:name");
			if (name.isEmpty()) {
				name = act.getAttribute("name");
			}
			if (name.isEmpty()) {
				continue;
			}
			if (name.startsWith(".") && pkg != null && !pkg.isEmpty()) {
				return pkg + name;
			}
			if (!name.contains(".") && pkg != null && !pkg.isEmpty()) {
				return pkg + "." + name;
			}
			return name;
		}
		return null;
	}

	private static boolean hasLauncherFilter(Element activity) {
		NodeList filters = activity.getElementsByTagName("intent-filter");
		for (int i = 0; i < filters.getLength(); i++) {
			Element f = (Element) filters.item(i);
			boolean main = false;
			boolean launcher = false;
			for (Node n = f.getFirstChild(); n != null; n = n.getNextSibling()) {
				if (!(n instanceof Element)) {
					continue;
				}
				Element e = (Element) n;
				String an = attrName(e);
				if ("action".equals(e.getTagName()) && "android.intent.action.MAIN".equals(an)) {
					main = true;
				}
				if ("category".equals(e.getTagName()) && "android.intent.category.LAUNCHER".equals(an)) {
					launcher = true;
				}
			}
			if (main && launcher) {
				return true;
			}
		}
		return false;
	}

	private static String attrName(Element e) {
		String n = e.getAttribute("android:name");
		return n.isEmpty() ? e.getAttribute("name") : n;
	}

	private static Document parse(String xml) throws Exception {
		DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
		f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		f.setNamespaceAware(false);
		DocumentBuilder b = f.newDocumentBuilder();
		return b.parse(new InputSource(new StringReader(xml)));
	}
}
