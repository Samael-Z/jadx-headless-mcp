package com.zin.jadxheadless.index;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves {@code search_in_code} / string-constant tools from SQLite (D6), with the FTS layer split into
 * {@code M} parallel {@link FtsShards} (fast-index-pipeline D2). Decompiled text is indexed into a
 * <b>contentless FTS5 trigram</b> table per shard (rowid = cls_idx; the text itself stays on the disk
 * code cache, not duplicated). Trigram gives the classic "candidate-by-substring then confirm" model;
 * full regex (beyond trigram's reach, or &lt;3-char queries) falls back to <b>ripgrep over the disk
 * code cache</b>.
 *
 * <p><b>Build</b> is decoupled (D1): the parallel decompile threads call {@link #enqueue}; {@code M}
 * shard-writer threads do the tokenization + insert. <b>Queries</b> fan out across all shards and union
 * the candidate {@code cls_idx}; FQNs are resolved from the main DB's {@code classes} table and the
 * analysis-value filter/sort/limit is applied unchanged (so the tool contract is identical to the
 * single-DB version).
 */
public final class CodeSearchIndex {

	private static final Logger LOG = LoggerFactory.getLogger(CodeSearchIndex.class);
	private static final int MAX_STR_PER_CLASS = 4000;
	private static final int MAX_STR_LEN = 512;

	// ---- task 0.2 bench instrumentation: split the index serial cost into FTS-insert (trigram
	// tokenization, accumulated in the shard writers) vs string-extract (accumulated in enqueue), so the
	// spike can attribute the ~14.7ms/class. Off in production; flipped on only by --bench-decompile. ----
	public static volatile boolean BENCH_TIMING = false;
	public static final java.util.concurrent.atomic.AtomicLong BENCH_FTS_NANOS = new java.util.concurrent.atomic.AtomicLong();
	public static final java.util.concurrent.atomic.AtomicLong BENCH_STR_NANOS = new java.util.concurrent.atomic.AtomicLong();

	private final Db db;
	private final Path codeSrcDir; // disk code cache sources dir (for ripgrep fallback)
	private final FtsShards shards;

	/** How many raw candidates to pull before tier-filter/sort/limit so the relevance window is wide enough. */
	private static final int CANDIDATE_CAP = 10000;

	/** Analysis-value scope: filter T4 (stdlib) and rank T1&gt;T2&gt;T3. Set by JadxService after load; never null. */
	private volatile AnalysisScope scope = AnalysisScope.defaults(null);

	/**
	 * Build progress, for the cross-phase decision (D5): while {@code !coverage_complete}, code search
	 * unions FTS with a ripgrep scan of the disk {@code .java} (covering decompiled-but-unindexed classes)
	 * and string search reads the freshly-committed {@code const_strings} rows; once complete it uses FTS
	 * alone (sub-second). Null until {@link #setStatus} (then the safe default — treat as still building).
	 */
	private volatile IndexStatus status;

	public CodeSearchIndex(Db db, Path codeSrcDir, FtsShards shards) {
		this.db = db;
		this.codeSrcDir = codeSrcDir;
		this.shards = shards;
	}

	/** Install the analysis-value scope once the manifest package is known (JadxService.loadApk). */
	public void setScope(AnalysisScope scope) {
		if (scope != null) {
			this.scope = scope;
		}
	}

	/** Install the build status so queries can decide the cross-phase strategy (D5). */
	public void setStatus(IndexStatus status) {
		this.status = status;
	}

	/** True once the whole in-scope set is indexed — then FTS alone is complete and fastest (D5). */
	private boolean coverageComplete() {
		IndexStatus s = status;
		return s != null && s.coverageComplete();
	}

	public int shardCount() {
		return shards.shards();
	}

	// ==================== build (decoupled producer-consumer over M shards) ====================

	/** Open the M shard writers + queues + consumer threads. {@code queueBudgetBytes} = global backpressure cap. */
	public void beginBuild(long queueBudgetBytes) throws SQLException {
		shards.beginBuild(queueBudgetBytes);
	}

	/**
	 * Producer side (called from the parallel decompile threads): hand one class's source to its shard
	 * for tokenization + indexing. Extracts string literals on the calling thread (pure CPU, D3) and
	 * blocks only on the byte budget when the writers fall behind — never on a global write lock.
	 * Returns the number of string literals harvested.
	 */
	public int enqueue(int clsIdx, String code) {
		if (code == null || code.isEmpty()) {
			return 0;
		}
		return shards.enqueue(clsIdx, code);
	}

	/** Chunk-boundary flush: drain queues + commit + TRUNCATE each shard WAL (bounds peak RAM across the pass). */
	public void flush() {
		shards.flush();
	}

	/** End-of-input: drain all shard queues, rebuild each shard's string_fts, commit + close writers. */
	public void finishBuild() {
		shards.finishBuild();
	}

	/** Abort an in-flight build (APK switch / shutdown). */
	public void abortBuild() {
		shards.abortBuild();
	}

	/** cls_idx values already indexed (union across shards) — a resume skips re-decompiling these (5.6). */
	public Set<Integer> indexedRowids() {
		return shards.indexedRowids();
	}

	/**
	 * Total const-string rows across shards. Backfills index_status when a complete index is reused — the
	 * build path counts these incrementally via {@code status.addStrings(...)}, which the reuse path
	 * skips, so without this the count would read 0 despite the rows being present.
	 */
	public long countConstStrings() {
		return shards.countConstStrings();
	}

	public void close() {
		shards.close();
	}

	// ==================== query ====================

	/**
	 * Full-text code search, analysis-value optimized + cross-phase (D5). For ordinary substrings
	 * (≥3 chars) uses the FTS5 trigram shards (one row per class); for a regex request or sub-trigram
	 * query, falls back to ripgrep over the disk code cache. <b>While the index is still building</b>, an
	 * FTS-eligible query additionally unions a ripgrep scan of the decompiled {@code .java}, so classes
	 * already decompiled but not yet in FTS are found too ({@code {disk .java} ⊇ {FTS}}); once
	 * {@code coverage_complete} it uses FTS alone (sub-second). Results default to filtering out standard
	 * library (T4) hits and are ranked T1 app &gt; T2 obfuscated &gt; T3 third-party; {@code limit} is applied
	 * after that ordering. {@code scopePrefix} restricts to a package subtree; {@code includeLibs} brings
	 * T4 hits back. Tool signature/semantics are unchanged — the caller need not know the build phase.
	 */
	public Map<String, Object> searchInCode(String term, boolean regex, int limit, String scopePrefix,
			boolean includeLibs) {
		if (term == null || term.isEmpty()) {
			return result("none", List.of(), "empty query", 0);
		}
		boolean canFts = !regex && term.length() >= 3 && !looksLikeRegex(term);

		// Regex / sub-trigram → ripgrep over the disk code cache (covers all decompiled classes).
		if (!canFts) {
			List<Map<String, Object>> candidates = ripgrep(term, regex, CANDIDATE_CAP);
			if (candidates == null) {
				return result("none", List.of(),
						"query needs regex/ripgrep fallback but `rg` is not on PATH; install ripgrep or use a ≥3-char literal substring",
						0);
			}
			return refine("ripgrep", candidates, scopePrefix, includeLibs, limit, null);
		}

		// FTS-eligible literal substring. Once complete, FTS alone is the full picture (sub-second).
		if (coverageComplete()) {
			return refine("fts5", ftsCode(term), scopePrefix, includeLibs, limit, null);
		}

		// Build in progress (D5): cover ALL decompiled classes, not just the FTS-indexed subset. ripgrep
		// over the sources dir is the full decompiled set ({.java} ⊇ {FTS}); FTS is unioned in to
		// strengthen the already-indexed part. Dedup by class happens in refine().
		List<Map<String, Object>> ftsRows = ftsCode(term);
		List<Map<String, Object>> rgRows = ripgrep(term, false, CANDIDATE_CAP);
		if (rgRows == null) {
			// 3.2: ripgrep not on PATH → degrade to the FTS-indexed subset (sub-second) + a clear note.
			String note = "build in progress and `rg` is not on PATH — searched only the " + ftsRows.size()
					+ " FTS-indexed classes, not the full decompiled set; install ripgrep to also cover "
					+ "decompiled-but-unindexed classes (poll index_status for coverage)";
			return refine("fts5", ftsRows, scopePrefix, includeLibs, limit, note);
		}
		return refine("fts5+ripgrep", unionByClass(rgRows, ftsRows), scopePrefix, includeLibs, limit, null);
	}

	/**
	 * Union two candidate lists by class FQN, keeping the first occurrence (ripgrep rows carry
	 * {@code snippets}; FTS rows carry {@code cls_idx}). This is the D5 cross-phase de-dup: ripgrep over
	 * the {@code .java} dir is the full decompiled set, FTS adds nothing beyond it for coverage but is
	 * unioned per the spec to fold in the indexed subset; either way one row per class.
	 */
	private static List<Map<String, Object>> unionByClass(List<Map<String, Object>> primary,
			List<Map<String, Object>> secondary) {
		LinkedHashMap<String, Map<String, Object>> byClass = new LinkedHashMap<>();
		for (Map<String, Object> m : primary) {
			byClass.putIfAbsent(String.valueOf(m.get("class")), m);
		}
		for (Map<String, Object> m : secondary) {
			byClass.putIfAbsent(String.valueOf(m.get("class")), m);
		}
		return new ArrayList<>(byClass.values());
	}

	/** Cross-shard code_fts MATCH → candidate {class, cls_idx} rows (FQNs resolved from the main DB). */
	private List<Map<String, Object>> ftsCode(String term) {
		Set<Integer> rowids = shards.matchCode(ftsPhrase(term), CANDIDATE_CAP);
		Map<Integer, String> fqns = resolveFqns(rowids);
		List<Map<String, Object>> out = new ArrayList<>(rowids.size());
		for (int clsIdx : rowids) {
			String fqn = fqns.get(clsIdx);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("class", fqn != null ? fqn : ("#" + clsIdx));
			m.put("cls_idx", clsIdx);
			out.add(m);
		}
		return out;
	}

	/**
	 * Apply the analysis-value layer to raw {@code search_in_code} candidates: drop T4 (unless
	 * {@code includeLibs}), restrict to {@code scopePrefix} if given, sort by tier (app &gt; obfuscated &gt;
	 * third-party) then class name, and only then truncate to {@code limit}. Candidates are already one
	 * row per class (FTS by construction; ripgrep folded), so this is also the de-dup step.
	 */
	private Map<String, Object> refine(String engine, List<Map<String, Object>> candidates, String scopePrefix,
			boolean includeLibs, int limit, String extraNote) {
		String prefix = (scopePrefix == null || scopePrefix.isBlank()) ? null : scopePrefix.trim();
		int filteredLibs = 0;
		List<Map<String, Object>> kept = new ArrayList<>();
		for (Map<String, Object> m : candidates) {
			String fqn = String.valueOf(m.get("class"));
			if (prefix != null && !fqn.startsWith(prefix)) {
				continue;
			}
			if (!includeLibs && scope.isLib(fqn)) {
				filteredLibs++;
				continue;
			}
			kept.add(m);
		}
		kept.sort(Comparator
				.comparingInt((Map<String, Object> m) -> scope.rank(String.valueOf(m.get("class"))))
				.thenComparing(m -> String.valueOf(m.get("class"))));
		boolean capped = kept.size() > limit;
		List<Map<String, Object>> rows = capped ? new ArrayList<>(kept.subList(0, limit)) : kept;
		StringBuilder note = new StringBuilder();
		if (extraNote != null && !extraNote.isEmpty()) {
			note.append(extraNote);
		}
		if (capped) {
			if (note.length() > 0) {
				note.append("; ");
			}
			note.append("capped at limit (").append(kept.size()).append(" classes matched after filtering)");
		}
		if (!includeLibs && filteredLibs > 0) {
			if (note.length() > 0) {
				note.append("; ");
			}
			note.append(filteredLibs).append(" stdlib-class hits filtered (set include_libs=true to keep)");
		}
		if (candidates.size() >= CANDIDATE_CAP) {
			if (note.length() > 0) {
				note.append("; ");
			}
			note.append("relevance ranked within first ").append(CANDIDATE_CAP)
					.append(" raw matches — narrow the query or set scope for completeness");
		}
		return result(engine, rows, note.length() == 0 ? null : note.toString(), filteredLibs);
	}

	/**
	 * Substring search over const-string literals (the RE main-line locator), aggregated by class:
	 * each class appears once with the list of its matching strings. Standard-library (T4) classes are
	 * filtered unless {@code includeLibs}; results are ranked T1&gt;T2&gt;T3 and only then truncated to
	 * {@code limit} classes.
	 */
	public List<Map<String, Object>> searchStringConstants(String term, int limit, boolean includeLibs) {
		if (term == null || term.isEmpty()) {
			return new ArrayList<>();
		}
		// Cross-phase (D5): each shard's string_fts is only rebuilt at end-of-build, so a mid-build FTS
		// query would miss already-decompiled classes. The const_strings rows ARE committed per chunk-flush,
		// so until coverage_complete fall back to a LIKE scan (fresh, finds every decompiled class); switch
		// to the faster string_fts once the build is done.
		boolean useFts = term.length() >= 3 && coverageComplete();
		String arg = useFts ? ftsPhrase(term) : "%" + term + "%";
		Map<Integer, Set<String>> byCls = shards.matchStrings(arg, useFts, CANDIDATE_CAP);
		Map<Integer, String> fqns = resolveFqns(byCls.keySet());
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map.Entry<Integer, Set<String>> e : byCls.entrySet()) {
			String fqn = fqns.get(e.getKey());
			if (fqn == null || (!includeLibs && scope.isLib(fqn))) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("class", fqn);
			m.put("strings", new ArrayList<>(e.getValue()));
			m.put("hits", e.getValue().size());
			rows.add(m);
		}
		return rankAndLimit(rows, limit);
	}

	/**
	 * Classes that contain a given <b>exact</b> string literal (one row per class). For substring /
	 * partial matching use {@link #searchStringConstants} (FTS-accelerated). Standard-library (T4)
	 * classes are filtered unless {@code includeLibs}; results are ranked T1&gt;T2&gt;T3.
	 */
	public List<Map<String, Object>> findStringUsages(String value, int limit, boolean includeLibs) {
		if (value == null || value.isEmpty()) {
			return new ArrayList<>();
		}
		Set<Integer> ids = shards.exactString(value, CANDIDATE_CAP);
		Map<Integer, String> fqns = resolveFqns(ids);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int clsIdx : ids) {
			String fqn = fqns.get(clsIdx);
			if (fqn == null || (!includeLibs && scope.isLib(fqn))) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("class", fqn);
			m.put("string", value);
			rows.add(m);
		}
		return rankAndLimit(rows, limit);
	}

	/** Sort rows (each with a {@code "class"} key) by analysis-value rank then FQN, then cap to {@code limit}. */
	private List<Map<String, Object>> rankAndLimit(List<Map<String, Object>> rows, int limit) {
		rows.sort(Comparator
				.comparingInt((Map<String, Object> m) -> scope.rank(String.valueOf(m.get("class"))))
				.thenComparing(m -> String.valueOf(m.get("class"))));
		return rows.size() > limit ? new ArrayList<>(rows.subList(0, limit)) : rows;
	}

	/** Batch-resolve cls_idx → display FQN from the main DB's {@code classes} table (PK lookups, chunked). */
	private Map<Integer, String> resolveFqns(Collection<Integer> ids) {
		Map<Integer, String> out = new HashMap<>(ids.size() * 2);
		if (ids.isEmpty()) {
			return out;
		}
		List<Integer> list = new ArrayList<>(ids);
		synchronized (db.readLock()) {
			for (int from = 0; from < list.size(); from += 500) {
				int to = Math.min(list.size(), from + 500);
				List<Integer> chunk = list.subList(from, to);
				StringBuilder sb = new StringBuilder("SELECT cls_idx,fqn FROM classes WHERE cls_idx IN (");
				for (int k = 0; k < chunk.size(); k++) {
					sb.append(k == 0 ? "?" : ",?");
				}
				sb.append(')');
				try (PreparedStatement ps = db.conn().prepareStatement(sb.toString())) {
					for (int k = 0; k < chunk.size(); k++) {
						ps.setInt(k + 1, chunk.get(k));
					}
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							out.put(rs.getInt(1), rs.getString(2));
						}
					}
				} catch (SQLException e) {
					LOG.warn("resolveFqns chunk failed: {}", e.toString());
				}
			}
		}
		return out;
	}

	// ==================== ripgrep fallback ====================

	private List<Map<String, Object>> ripgrep(String pattern, boolean regex, int cap) {
		try {
			List<String> cmd = new ArrayList<>();
			cmd.add("rg");
			cmd.add("--no-heading");
			cmd.add("--line-number");
			cmd.add("--max-count");
			cmd.add("3"); // ≤3 lines per file → ≤3 snippets per class
			if (!regex) {
				cmd.add("--fixed-strings");
			}
			cmd.add("--");
			cmd.add(pattern);
			cmd.add(codeSrcDir.toAbsolutePath().toString());
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(false);
			Process p = pb.start();
			// Fold to one row per class (1.3): class -> ordered list of {line, text} snippets.
			Map<String, List<Map<String, Object>>> byClass = new LinkedHashMap<>();
			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null && byClass.size() < cap) {
					// format: <path>:<lineno>:<text>; anchor on ".java:" so a Windows drive colon (E:) doesn't mis-split.
					int j = line.indexOf(".java:");
					if (j < 0) {
						continue;
					}
					String path = line.substring(0, j + 5);
					String rest = line.substring(j + 6); // <lineno>:<text>
					int colon = rest.indexOf(':');
					if (colon < 0) {
						continue;
					}
					String lineNo = rest.substring(0, colon);
					String text = rest.substring(colon + 1).trim();
					String fqn = pathToFqn(path);
					List<Map<String, Object>> snippets = byClass.computeIfAbsent(fqn, k -> new ArrayList<>());
					Map<String, Object> snip = new LinkedHashMap<>();
					snip.put("line", lineNo);
					snip.put("text", text.length() > 240 ? text.substring(0, 240) + "…" : text);
					snippets.add(snip);
				}
			}
			p.waitFor();
			List<Map<String, Object>> out = new ArrayList<>();
			for (Map.Entry<String, List<Map<String, Object>>> e : byClass.entrySet()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("class", e.getKey());
				m.put("snippets", e.getValue());
				out.add(m);
			}
			return out;
		} catch (Exception e) {
			LOG.info("ripgrep unavailable / failed: {}", e.toString());
			return null;
		}
	}

	/** Map a disk-cache source path ({@code .../sources/XX/<clsIdxHex>.java}) back to a class FQN. */
	private String pathToFqn(String path) {
		try {
			String name = Path.of(path).getFileName().toString();
			if (name.endsWith(".java")) {
				int clsIdx = Integer.parseInt(name.substring(0, name.length() - 5), 16);
				String fqn = scalarStr("SELECT fqn FROM classes WHERE cls_idx=?", clsIdx);
				if (fqn != null) {
					return fqn;
				}
			}
		} catch (Exception ignored) {
			// fall through
		}
		return path;
	}

	private String scalarStr(String sql, int arg) {
		synchronized (db.readLock()) {
			try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
				ps.setInt(1, arg);
				try (ResultSet rs = ps.executeQuery()) {
					return rs.next() ? rs.getString(1) : null;
				}
			} catch (SQLException e) {
				return null;
			}
		}
	}

	// ==================== helpers ====================

	private static Map<String, Object> result(String engine, List<Map<String, Object>> hits, String note,
			int filteredLibs) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("engine", engine);
		m.put("count", hits.size());
		if (filteredLibs > 0) {
			m.put("filtered_lib_hits", filteredLibs);
		}
		m.put("matches", hits);
		if (note != null) {
			m.put("note", note);
		}
		return m;
	}

	/** Quote a term as an FTS5 phrase so punctuation isn't parsed as query syntax. */
	private static String ftsPhrase(String term) {
		return '"' + term.replace("\"", "\"\"") + '"';
	}

	private static boolean looksLikeRegex(String s) {
		return s.matches(".*[\\\\^$.|?*+()\\[\\]{}].*");
	}

	/** Extract distinct Java string literals from decompiled source (best-effort scanner). */
	static Set<String> extractStringLiterals(String code) {
		LinkedHashSet<String> out = new LinkedHashSet<>();
		int n = code.length();
		int i = 0;
		while (i < n && out.size() < MAX_STR_PER_CLASS) {
			char c = code.charAt(i);
			if (c == '"') {
				StringBuilder sb = new StringBuilder();
				i++;
				boolean closed = false;
				while (i < n) {
					char d = code.charAt(i);
					if (d == '\\' && i + 1 < n) {
						sb.append(d).append(code.charAt(i + 1));
						i += 2;
						continue;
					}
					if (d == '"') {
						closed = true;
						i++;
						break;
					}
					if (d == '\n') {
						break; // unterminated; bail this literal
					}
					sb.append(d);
					i++;
				}
				if (closed && sb.length() > 0 && sb.length() <= MAX_STR_LEN) {
					out.add(sb.toString());
				}
			} else {
				i++;
			}
		}
		return out;
	}
}
