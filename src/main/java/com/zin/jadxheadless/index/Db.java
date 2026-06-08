package com.zin.jadxheadless.index;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the single SQLite database for one APK (stored at {@code <cacheDir>/index.db}, WAL mode).
 * Holds the unified model (D6), borrowing colbymchenry/codegraph's symbols+edges+FTS shape but
 * fed by {@code jadx-core} instead of tree-sitter:
 *
 * <ul>
 *   <li>{@code classes}      — cls_idx ↔ dex-stable id ↔ display FQN (the code-cache row mapping)</li>
 *   <li>{@code symbols}      — classes/methods/fields keyed by dex-stable id (D5), display as metadata</li>
 *   <li>{@code edges}        — call / class-use / field-use / extends / implements (the xref graph, out of heap, D7)</li>
 *   <li>{@code code_fts}     — FTS5 trigram over decompiled source, <b>contentless</b> (text stays on the disk
 *                              code cache; rowid = cls_idx), serving {@code search_in_code}</li>
 *   <li>{@code const_strings}+{@code string_fts} — const-string literals → class, the RE main-line locator</li>
 *   <li>{@code meta}         — build state / schema version / coverage</li>
 * </ul>
 *
 * Writers serialize (single connection, one writer thread); WAL lets the read path stay concurrent.
 */
public final class Db implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(Db.class);

	/** Bump to invalidate an incompatible on-disk schema. */
	public static final int SCHEMA_VERSION = 1;

	/** Edge types. */
	public static final int E_CALLS = 0; // method -> method (callee)
	public static final int E_USES_CLASS = 1; // node(class/method owner) -> class
	public static final int E_USES_FIELD = 2; // method -> field
	public static final int E_EXTENDS = 3; // class -> superclass
	public static final int E_IMPLEMENTS = 4; // class -> interface

	/** Symbol kinds. */
	public static final int K_CLASS = 0;
	public static final int K_METHOD = 1;
	public static final int K_FIELD = 2;

	private final Path dbFile;
	private final Connection conn; // read/query connection (guarded by readLock)
	private final Object readLock = new Object();

	private Db(Path dbFile, Connection conn) {
		this.dbFile = dbFile;
		this.conn = conn;
	}

	/** The read/query connection. All query callers MUST synchronize on {@link #readLock()}. */
	public Connection conn() {
		return conn;
	}

	/**
	 * Guards the single read connection. SQLite {@link Connection}s are not thread-safe, and MCP tool
	 * calls arrive concurrently (6.6), so every read serializes on this monitor. Writes go through a
	 * SEPARATE {@link #openWriter()} connection — WAL lets that writer run concurrently with readers,
	 * so the long background build never blocks tool queries.
	 */
	public Object readLock() {
		return readLock;
	}

	public Path file() {
		return dbFile;
	}

	private static void applyPragmas(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			// Durability traded for speed — this is a rebuildable cache, not a system of record.
			st.execute("PRAGMA journal_mode=WAL");
			st.execute("PRAGMA synchronous=NORMAL");
			st.execute("PRAGMA temp_store=MEMORY");
			st.execute("PRAGMA cache_size=-65536"); // ~64 MiB page cache
			st.execute("PRAGMA busy_timeout=30000");
		}
	}

	/** Open (creating if needed) the SQLite DB at {@code cacheDir/index.db} and ensure the schema exists. */
	public static Db open(Path cacheDir) throws SQLException {
		Path dbFile = cacheDir.resolve("index.db");
		String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
		Connection c = DriverManager.getConnection(url);
		applyPragmas(c);
		Db db = new Db(dbFile, c);
		db.createSchema();
		return db;
	}

	/** A fresh dedicated connection for the background index writer (WAL: concurrent with readers). */
	public Connection openWriter() throws SQLException {
		Connection w = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
		applyPragmas(w);
		return w;
	}

	private void createSchema() throws SQLException {
		try (Statement st = conn.createStatement()) {
			st.execute("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT)");

			st.execute("CREATE TABLE IF NOT EXISTS classes ("
					+ "cls_idx INTEGER PRIMARY KEY,"
					+ "dex_id  TEXT NOT NULL,"
					+ "fqn     TEXT NOT NULL,"
					+ "pkg     TEXT)");
			st.execute("CREATE INDEX IF NOT EXISTS ix_cls_dex ON classes(dex_id)");

			st.execute("CREATE TABLE IF NOT EXISTS symbols ("
					+ "id      INTEGER PRIMARY KEY,"
					+ "kind    INTEGER NOT NULL,"
					+ "dex_id  TEXT NOT NULL UNIQUE,"
					+ "cls_dex TEXT NOT NULL,"
					+ "name    TEXT,"
					+ "display TEXT,"
					+ "cls_idx INTEGER)");
			st.execute("CREATE INDEX IF NOT EXISTS ix_sym_clsdex ON symbols(cls_dex)");

			st.execute("CREATE TABLE IF NOT EXISTS edges ("
					+ "src  INTEGER NOT NULL,"
					+ "dst  INTEGER NOT NULL,"
					+ "type INTEGER NOT NULL)");
			st.execute("CREATE INDEX IF NOT EXISTS ix_edge_dst ON edges(dst, type)");
			st.execute("CREATE INDEX IF NOT EXISTS ix_edge_src ON edges(src, type)");

			// Contentless FTS5 (text lives on the disk code cache; rowid = cls_idx). Trigram tokenizer
			// gives substring/regex-candidate matching (the Google Code Search / Zoekt model).
			st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS code_fts USING fts5("
					+ "body, content='', tokenize='trigram')");

			st.execute("CREATE TABLE IF NOT EXISTS const_strings ("
					+ "id      INTEGER PRIMARY KEY,"
					+ "str     TEXT NOT NULL,"
					+ "cls_idx INTEGER NOT NULL)");
			st.execute("CREATE INDEX IF NOT EXISTS ix_cstr_cls ON const_strings(cls_idx)");
			st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS string_fts USING fts5("
					+ "str, content='const_strings', content_rowid='id', tokenize='trigram')");

			setMeta("schema_version", Integer.toString(SCHEMA_VERSION));
		}
	}

	public void setMeta(String k, String v) {
		try (var ps = conn.prepareStatement("INSERT INTO meta(k,v) VALUES(?,?) "
				+ "ON CONFLICT(k) DO UPDATE SET v=excluded.v")) {
			ps.setString(1, k);
			ps.setString(2, v);
			ps.executeUpdate();
		} catch (SQLException e) {
			LOG.warn("setMeta({}) failed: {}", k, e.toString());
		}
	}

	public String getMeta(String k) {
		try (var ps = conn.prepareStatement("SELECT v FROM meta WHERE k=?")) {
			ps.setString(1, k);
			try (var rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		} catch (SQLException e) {
			return null;
		}
	}

	/** True when a prior run already built a complete index for this APK (cross-restart reuse, 5.6). */
	public boolean isComplete() {
		return "true".equals(getMeta("coverage_complete"))
				&& Integer.toString(SCHEMA_VERSION).equals(getMeta("schema_version"));
	}

	/**
	 * True when the symbol/edge graph was already exported in a prior run (so a resume can skip the
	 * expensive structure+usage phase and just extend the FTS code coverage). Detected by a non-empty
	 * symbols table so an older partial index (built before the graph_done marker existed) still resumes.
	 */
	public boolean graphExported() {
		if ("true".equals(getMeta("graph_done"))) {
			return true;
		}
		synchronized (readLock) {
			try (Statement st = conn.createStatement();
					java.sql.ResultSet rs = st.executeQuery("SELECT EXISTS(SELECT 1 FROM symbols)")) {
				return rs.next() && rs.getInt(1) == 1;
			} catch (SQLException e) {
				return false;
			}
		}
	}

	@Override
	public void close() {
		try {
			conn.close();
		} catch (SQLException e) {
			LOG.warn("Db close failed: {}", e.toString());
		}
	}
}
