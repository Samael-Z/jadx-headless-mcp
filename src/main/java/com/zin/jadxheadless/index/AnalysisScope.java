package com.zin.jadxheadless.index;

import java.util.ArrayList;
import java.util.List;

/**
 * Classifies a class FQN by reverse-engineering <b>analysis value</b> (the analysis-value-code-search
 * change). Four tiers:
 * <ul>
 *   <li>{@code T1_APP} — the app's own code: the AndroidManifest {@code package} and same-origin vendor
 *       systems (e.g. Douyin's {@code com.ss.*} / {@code com.bytedance.*}).</li>
 *   <li>{@code T2_OBFUSCATED} — obfuscated packages (single-letter / very-short top segments like
 *       {@code X}, {@code a.b}, {@code o.X}) — high RE value, must be indexed.</li>
 *   <li>{@code T3_THIRD_PARTY} — named third-party libraries ({@code com.<vendor>.*}); indexed by
 *       default, rankable below app/obfuscated.</li>
 *   <li>{@code T4_STDLIB} — the platform + ubiquitous SDKs ({@code android}/{@code androidx}/{@code java}/
 *       {@code kotlin}/{@code com.google}/...); noise — filtered from results and skipped by the
 *       selective index unless {@code --index-all}.</li>
 * </ul>
 *
 * <p>This drives both layers of the change: the search-result quality layer (filter {@code T4}, sort
 * {@code T1>T2>T3}) and the selective-index layer ({@link #shouldIndex}). Pure string logic with no
 * jadx dependency; built once per load from the manifest package plus the CLI
 * {@code --index-include}/{@code --index-exclude}/{@code --index-all} options.
 */
public final class AnalysisScope {

	/** Analysis-value tier, ordered most-valuable ({@code T1}) to noise ({@code T4}). */
	public enum Tier {
		T1_APP, T2_OBFUSCATED, T3_THIRD_PARTY, T4_STDLIB
	}

	/** Platform + ubiquitous-SDK prefixes treated as standard-library noise (T4). */
	private static final String[] STDLIB_PREFIXES = {
			"android.", "androidx.", "android.support.", "com.android.", "dalvik.",
			"java.", "javax.", "j$.", "sun.", "jdk.",
			"kotlin.", "kotlinx.",
			"com.google.", "com.googlecode.",
			"okhttp3.", "okio.", "retrofit2.", "okhttp.",
			"io.reactivex.", "rx.", "io.grpc.",
			"org.apache.", "org.json.", "org.w3c.", "org.xml.", "org.xmlpull.",
			"org.intellij.", "org.jetbrains.", "org.slf4j.", "org.bouncycastle.",
			"com.squareup.", "com.facebook.react.", "org.chromium.",
	};

	/**
	 * Curated same-origin vendor groups: if the app's manifest package falls under any prefix in a
	 * group, every prefix in that group counts as T1 (the app's own code). Seeds the common case
	 * (notably Douyin/ByteDance, whose code spans {@code com.ss.*} and {@code com.bytedance.*}); users
	 * extend via {@code --index-include}. Intentionally small — T1-vs-T3 only affects ranking (both are
	 * indexed by default), so over-narrow classification is harmless beyond sort order.
	 */
	private static final String[][] SAME_ORIGIN_GROUPS = {
			{ "com.ss.", "com.bytedance.", "com.ixigua.", "com.lemon." }, // ByteDance family
			{ "com.tencent.", "com.tencent.mm." },
			{ "com.alibaba.", "com.taobao.", "com.alipay.", "com.ali." },
			{ "com.baidu." },
	};

	private final List<String> appPrefixes = new ArrayList<>();
	private final List<String> includePrefixes;
	private final List<String> excludePrefixes;
	private final boolean indexThirdParty;
	private final boolean indexAll;

	public AnalysisScope(String manifestPackage, List<String> include, List<String> exclude,
			boolean indexThirdParty, boolean indexAll) {
		this.includePrefixes = normalize(include);
		this.excludePrefixes = normalize(exclude);
		this.indexThirdParty = indexThirdParty;
		this.indexAll = indexAll;
		buildAppPrefixes(manifestPackage);
	}

	/** Default scope: selective (skip T4), third-party indexed, no overrides. */
	public static AnalysisScope defaults(String manifestPackage) {
		return new AnalysisScope(manifestPackage, List.of(), List.of(), true, false);
	}

	private void buildAppPrefixes(String manifestPackage) {
		if (manifestPackage == null || manifestPackage.isEmpty()) {
			return;
		}
		String pkg = manifestPackage + ".";
		appPrefixes.add(pkg);
		String top2 = topSegments(manifestPackage, 2);
		if (top2 != null) {
			appPrefixes.add(top2 + ".");
		}
		for (String[] group : SAME_ORIGIN_GROUPS) {
			boolean inGroup = false;
			for (String p : group) {
				if (pkg.startsWith(p) || (top2 != null && (top2 + ".").startsWith(p))) {
					inGroup = true;
					break;
				}
			}
			if (inGroup) {
				for (String p : group) {
					appPrefixes.add(p);
				}
			}
		}
	}

	/** The analysis-value tier of a class FQN. */
	public Tier tierOf(String fqn) {
		if (fqn == null || fqn.isEmpty()) {
			return Tier.T3_THIRD_PARTY;
		}
		// User include wins → treat as app-origin (highest value, always indexed).
		if (startsWithAny(fqn, includePrefixes)) {
			return Tier.T1_APP;
		}
		if (startsWithAny(fqn, appPrefixes)) {
			return Tier.T1_APP;
		}
		if (startsWithAny(fqn, STDLIB_PREFIXES)) {
			return Tier.T4_STDLIB;
		}
		if (isObfuscatedPackage(fqn)) {
			return Tier.T2_OBFUSCATED;
		}
		return Tier.T3_THIRD_PARTY;
	}

	/** Relevance rank for sorting (0 = most valuable). */
	public int rank(String fqn) {
		return tierOf(fqn).ordinal();
	}

	/** True for standard-library / common-SDK classes (T4) — filtered from results by default. */
	public boolean isLib(String fqn) {
		return tierOf(fqn) == Tier.T4_STDLIB;
	}

	/**
	 * Whether a class should be decompiled + indexed by the selective index (layer two). Defaults:
	 * T1/T2 always; T3 when {@code indexThirdParty}; T4 only under {@code --index-all}. An explicit
	 * {@code --index-exclude} prefix always skips; an {@code --index-include} prefix always indexes.
	 */
	public boolean shouldIndex(String fqn) {
		if (startsWithAny(fqn, excludePrefixes)) {
			return false;
		}
		if (startsWithAny(fqn, includePrefixes)) {
			return true;
		}
		if (indexAll) {
			return true;
		}
		switch (tierOf(fqn)) {
			case T1_APP:
			case T2_OBFUSCATED:
				return true;
			case T3_THIRD_PARTY:
				return indexThirdParty;
			case T4_STDLIB:
			default:
				return false;
		}
	}

	public boolean indexAll() {
		return indexAll;
	}

	/** Short human-readable description of the active scope, for {@code index_status}/{@code current_apk}. */
	public String describe() {
		if (indexAll) {
			return "all (incl. stdlib)";
		}
		StringBuilder sb = new StringBuilder("analysis-value (T1 app + T2 obfuscated");
		if (indexThirdParty) {
			sb.append(" + T3 third-party");
		}
		sb.append("; T4 stdlib skipped)");
		if (!includePrefixes.isEmpty()) {
			sb.append(" +include").append(includePrefixes);
		}
		if (!excludePrefixes.isEmpty()) {
			sb.append(" -exclude").append(excludePrefixes);
		}
		return sb.toString();
	}

	// ==================== helpers ====================

	/**
	 * Obfuscation heuristic on the <b>package</b> portion of an FQN: a single short segment
	 * ({@code X}, {@code abc}) or a first segment ≤2 chars followed by another ≤2-char segment
	 * ({@code a.b}, {@code o.X}). Deliberately conservative so real short prefixes like
	 * {@code io.flutter} (first seg short, rest long) stay T3, not misread as obfuscated.
	 */
	static boolean isObfuscatedPackage(String fqn) {
		int lastDot = fqn.lastIndexOf('.');
		if (lastDot < 0) {
			return false; // default package — leave as third-party, still indexed
		}
		String pkg = fqn.substring(0, lastDot);
		int firstDot = pkg.indexOf('.');
		if (firstDot < 0) {
			// single-segment package: obfuscated if short
			return pkg.length() <= 3;
		}
		String first = pkg.substring(0, firstDot);
		if (first.length() > 2) {
			return false;
		}
		int secondDot = pkg.indexOf('.', firstDot + 1);
		String second = secondDot < 0 ? pkg.substring(firstDot + 1) : pkg.substring(firstDot + 1, secondDot);
		return second.length() <= 2;
	}

	private static boolean startsWithAny(String fqn, List<String> prefixes) {
		for (String p : prefixes) {
			if (fqn.startsWith(p)) {
				return true;
			}
		}
		return false;
	}

	private static boolean startsWithAny(String fqn, String[] prefixes) {
		for (String p : prefixes) {
			if (fqn.startsWith(p)) {
				return true;
			}
		}
		return false;
	}

	private static String topSegments(String pkg, int n) {
		int idx = 0;
		int count = 0;
		for (int i = 0; i < pkg.length(); i++) {
			if (pkg.charAt(i) == '.') {
				count++;
				if (count == n) {
					idx = i;
					break;
				}
			}
		}
		if (count < n) {
			return null; // fewer than n segments — top-n is the whole package (already added)
		}
		return pkg.substring(0, idx);
	}

	/** Normalize a prefix list: trim, drop blanks, ensure a trailing dot for clean prefix matching. */
	private static List<String> normalize(List<String> in) {
		List<String> out = new ArrayList<>();
		if (in == null) {
			return out;
		}
		for (String s : in) {
			if (s == null) {
				continue;
			}
			String t = s.trim();
			if (t.isEmpty()) {
				continue;
			}
			out.add(t.endsWith(".") ? t : t + ".");
		}
		return out;
	}
}
