package com.zin.jadxheadless.util;

import java.io.StringReader;

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
