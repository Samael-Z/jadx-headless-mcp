package com.zin.jadxheadless.index;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serves {@code search_in_code} / string-constant tools from SQLite (D6). Decompiled text is indexed
 * into a <b>contentless FTS5 trigram</b> table (rowid = cls_idx; the text itself stays on the disk
 * code cache, not duplicated). Trigram gives the classic "candidate-by-substring then confirm" model;
 * full regex (beyond trigram's reach, or &lt;3-char queries) falls back to <b>ripgrep over the disk
 * code cache</b>.
 */
public final class CodeSearchIndex {

	private static final Logger LOG = LoggerFactory.getLogger(CodeSearchIndex.class);
	private static final int MAX_STR_PER_CLASS = 4000;
	private static final int MAX_STR_LEN = 512;

	private final Db db;
	private final Path codeSrcDir; // disk code cache sources dir (for ripgrep fallback)

	// build-time state (on the dedicated writer connection)
	private Connection w;
	private PreparedStatement insCode;
	private PreparedStatement insStr;
	private final Object writeLock = new Object();

	public CodeSearchIndex(Db db, Path codeSrcDir) {
		this.db = db;
		this.codeSrcDir = codeSrcDir;
	}

	// ==================== build (uses the dedicated writer connection) ====================

	public void beginWrite(Connection writer) throws SQLException {
		this.w = writer;
		insCode = w.prepareStatement("INSERT INTO code_fts(rowid, body) VALUES(?,?)");
		insStr = w.prepareStatement("INSERT INTO const_strings(str, cls_idx) VALUES(?,?)");
	}

	/**
	 * Index one class's decompiled source: feed the full text to FTS5 (keyed by cls_idx) and harvest
	 * its string literals into {@code const_strings}. Thread-safe — the parallel decompile pass calls
	 * this from many threads, but the single SQLite connection requires serialized writes.
	 */
	public int indexCode(int clsIdx, String code) {
		if (code == null || code.isEmpty()) {
			return 0;
		}
		int strCount = 0;
		synchronized (writeLock) {
			try {
				insCode.setInt(1, clsIdx);
				insCode.setString(2, code);
				insCode.executeUpdate();
				Set<String> literals = extractStringLiterals(code);
				for (String s : literals) {
					insStr.setString(1, s);
					insStr.setInt(2, clsIdx);
					insStr.addBatch();
					strCount++;
				}
				if (strCount > 0) {
					insStr.executeBatch();
				}
			} catch (SQLException e) {
				LOG.warn("indexCode({}) failed: {}", clsIdx, e.toString());
			}
		}
		return strCount;
	}

	/** Commit the FTS/string writes and build the external-content string index. */
	public void endWrite() throws SQLException {
		synchronized (writeLock) {
			w.commit();
			try (Statement st = w.createStatement()) {
				// Populate string_fts (external-content over const_strings) in one shot.
				st.execute("INSERT INTO string_fts(string_fts) VALUES('rebuild')");
			}
			w.commit();
			w.setAutoCommit(true);
			closeQuietly(insCode);
			closeQuietly(insStr);
			insCode = insStr = null;
			w = null;
		}
	}

	public void beginTx() throws SQLException {
		w.setAutoCommit(false);
	}

	/** cls_idx values already present in the FTS index — a resume skips re-decompiling these (5.6). */
	public java.util.Set<Integer> indexedRowids() {
		java.util.Set<Integer> out = new java.util.HashSet<>();
		synchronized (db.readLock()) {
			try (Statement st = db.conn().createStatement();
					ResultSet rs = st.executeQuery("SELECT rowid FROM code_fts")) {
				while (rs.next()) {
					out.add(rs.getInt(1));
				}
			} catch (SQLException e) {
				LOG.warn("indexedRowids query failed: {}", e.toString());
			}
		}
		return out;
	}

	/** Periodic commit during the long code pass so readers see progress and WAL stays bounded. */
	public void commit() throws SQLException {
		synchronized (writeLock) {
			if (w != null) {
				w.commit();
			}
		}
	}

	// ==================== query ====================

	/**
	 * Full-text code search. For ordinary substrings (≥3 chars) uses the FTS5 trigram index; for a
	 * regex request, or sub-trigram queries, falls back to ripgrep over the disk code cache.
	 * Returns rows of {@code {class, cls_idx}}; caller may attach snippets.
	 */
	public Map<String, Object> searchInCode(String term, boolean regex, int limit) {
		if (term == null || term.isEmpty()) {
			return result("none", List.of(), "empty query");
		}
		boolean canFts = !regex && term.length() >= 3 && !looksLikeRegex(term);
		if (canFts) {
			List<Map<String, Object>> hits = ftsCode(term, limit);
			return result("fts5", hits, hits.size() >= limit ? "capped at limit" : null);
		}
		List<Map<String, Object>> hits = ripgrep(term, regex, limit);
		if (hits == null) {
			return result("none", List.of(),
					"query needs regex/ripgrep fallback but `rg` is not on PATH; install ripgrep or use a ≥3-char literal substring");
		}
		return result("ripgrep", hits, hits.size() >= limit ? "capped at limit" : null);
	}

	private List<Map<String, Object>> ftsCode(String term, int limit) {
		List<Map<String, Object>> out = new ArrayList<>();
		String sql = "SELECT f.rowid, c.fqn FROM code_fts f "
				+ "LEFT JOIN classes c ON c.cls_idx=f.rowid "
				+ "WHERE code_fts MATCH ? LIMIT ?";
		synchronized (db.readLock()) {
			try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
				ps.setString(1, ftsPhrase(term));
				ps.setInt(2, limit);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						int clsIdx = rs.getInt(1);
						String fqn = rs.getString(2);
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("class", fqn != null ? fqn : ("#" + clsIdx));
						m.put("cls_idx", clsIdx);
						out.add(m);
					}
				}
			} catch (SQLException e) {
				LOG.warn("ftsCode failed: {}", e.toString());
			}
		}
		return out;
	}

	/** Substring search over const-string literals (the RE main-line locator). */
	public List<Map<String, Object>> searchStringConstants(String term, int limit) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (term == null || term.isEmpty()) {
			return out;
		}
		boolean useFts = term.length() >= 3;
		String sql = useFts
				? "SELECT cs.str, c.fqn FROM string_fts sf "
						+ "JOIN const_strings cs ON cs.id=sf.rowid "
						+ "LEFT JOIN classes c ON c.cls_idx=cs.cls_idx "
						+ "WHERE string_fts MATCH ? LIMIT ?"
				: "SELECT cs.str, c.fqn FROM const_strings cs "
						+ "LEFT JOIN classes c ON c.cls_idx=cs.cls_idx "
						+ "WHERE cs.str LIKE ? LIMIT ?";
		synchronized (db.readLock()) {
			try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
				ps.setString(1, useFts ? ftsPhrase(term) : "%" + term + "%");
				ps.setInt(2, limit);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("string", rs.getString(1));
						m.put("class", rs.getString(2));
						out.add(m);
					}
				}
			} catch (SQLException e) {
				LOG.warn("searchStringConstants failed: {}", e.toString());
			}
		}
		return out;
	}

	/** Classes that contain a given string literal (exact, or substring when {@code contains}). */
	public List<Map<String, Object>> findStringUsages(String value, boolean contains, int limit) {
		List<Map<String, Object>> out = new ArrayList<>();
		if (value == null || value.isEmpty()) {
			return out;
		}
		String sql = "SELECT DISTINCT c.fqn, cs.str FROM const_strings cs "
				+ "LEFT JOIN classes c ON c.cls_idx=cs.cls_idx "
				+ (contains ? "WHERE cs.str LIKE ?" : "WHERE cs.str = ?") + " LIMIT ?";
		synchronized (db.readLock()) {
			try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
				ps.setString(1, contains ? "%" + value + "%" : value);
				ps.setInt(2, limit);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("class", rs.getString(1));
						m.put("string", rs.getString(2));
						out.add(m);
					}
				}
			} catch (SQLException e) {
				LOG.warn("findStringUsages failed: {}", e.toString());
			}
		}
		return out;
	}

	// ==================== ripgrep fallback ====================

	private List<Map<String, Object>> ripgrep(String pattern, boolean regex, int limit) {
		try {
			List<String> cmd = new ArrayList<>();
			cmd.add("rg");
			cmd.add("--no-heading");
			cmd.add("--line-number");
			cmd.add("--max-count");
			cmd.add("3");
			if (!regex) {
				cmd.add("--fixed-strings");
			}
			cmd.add("--");
			cmd.add(pattern);
			cmd.add(codeSrcDir.toAbsolutePath().toString());
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(false);
			Process p = pb.start();
			List<Map<String, Object>> out = new ArrayList<>();
			Set<String> seen = new LinkedHashSet<>();
			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null && out.size() < limit) {
					// format: <path>:<lineno>:<text>
					int c1 = line.indexOf(':');
					int c2 = line.indexOf(':', c1 + 1);
					if (c1 < 0 || c2 < 0) {
						continue;
					}
					String path = line.substring(0, c1);
					String text = line.substring(c2 + 1).trim();
					String fqn = pathToFqn(path);
					String key = fqn + "|" + text;
					if (seen.add(key)) {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("class", fqn);
						m.put("snippet", text.length() > 240 ? text.substring(0, 240) + "…" : text);
						out.add(m);
					}
				}
			}
			p.waitFor();
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

	private static Map<String, Object> result(String engine, List<Map<String, Object>> hits, String note) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("engine", engine);
		m.put("count", hits.size());
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

	private static void closeQuietly(PreparedStatement ps) {
		if (ps != null) {
			try {
				ps.close();
			} catch (SQLException ignored) {
				// ignore
			}
		}
	}
}
