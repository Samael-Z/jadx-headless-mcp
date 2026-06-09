package com.zin.jadxheadless.index;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

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

	/**
	 * Bump to invalidate an incompatible on-disk schema. v2 (fast-index-pipeline): the FTS layer
	 * ({@code code_fts}/{@code const_strings}/{@code string_fts}) moved out of this DB into the
	 * {@link FtsShards} files under {@code fts/}, and the graph indexes are now built after bulk load.
	 */
	public static final int SCHEMA_VERSION = 2;

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
		// Migration: an on-disk index from an older schema is incompatible (createSchema stamps the
		// current version, so we must wipe stale data BEFORE that). The decompiled code cache is keyed
		// separately (codeVersion) and stays valid — only the SQLite index + FTS shards are rebuilt.
		wipeIfIncompatible(cacheDir, dbFile);
		String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
		Connection c = DriverManager.getConnection(url);
		applyPragmas(c);
		Db db = new Db(dbFile, c);
		db.createSchema();
		return db;
	}

	/** If an existing {@code index.db} has a different {@code schema_version}, delete it + the {@code fts/} shards. */
	private static void wipeIfIncompatible(Path cacheDir, Path dbFile) {
		if (!Files.exists(dbFile)) {
			return;
		}
		Integer ver = null;
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
				Statement st = c.createStatement();
				ResultSet rs = st.executeQuery("SELECT v FROM meta WHERE k='schema_version'")) {
			if (rs.next()) {
				ver = Integer.parseInt(rs.getString(1).trim());
			}
		} catch (Exception ignored) {
			// no meta table / unreadable → treat as incompatible
		}
		if (ver != null && ver == SCHEMA_VERSION) {
			return; // compatible — keep
		}
		LOG.info("[db] on-disk schema {} != {} — wiping incompatible index + fts shards (code cache kept)",
				ver, SCHEMA_VERSION);
		deleteQuietly(dbFile);
		deleteQuietly(Path.of(dbFile + "-wal"));
		deleteQuietly(Path.of(dbFile + "-shm"));
		deleteDirQuietly(cacheDir.resolve("fts"));
	}

	private static void deleteQuietly(Path p) {
		try {
			Files.deleteIfExists(p);
		} catch (Exception e) {
			LOG.warn("[db] could not delete {}: {}", p, e.toString());
		}
	}

	private static void deleteDirQuietly(Path dir) {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (Exception ignored) {
					// best effort
				}
			});
		} catch (Exception e) {
			LOG.warn("[db] could not delete dir {}: {}", dir, e.toString());
		}
	}

	/** A fresh dedicated connection for the background index writer (WAL: concurrent with readers). */
	public Connection openWriter() throws SQLException {
		Connection w = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
		applyPragmas(w);
		try (Statement st = w.createStatement()) {
			// The writer builds the graph indexes by sorting 29.5M edges; spill that sort to disk rather
			// than native RAM (temp_store=MEMORY would balloon system memory and starve the JVM heap).
			st.execute("PRAGMA temp_store=FILE");
		}
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

			// dex_id is NOT marked UNIQUE: the build de-dups in-heap (SymbolGraph.symIds), and a UNIQUE
			// constraint would force per-insert index maintenance during the multi-million-row bulk load.
			st.execute("CREATE TABLE IF NOT EXISTS symbols ("
					+ "id      INTEGER PRIMARY KEY,"
					+ "kind    INTEGER NOT NULL,"
					+ "dex_id  TEXT NOT NULL,"
					+ "cls_dex TEXT NOT NULL,"
					+ "name    TEXT,"
					+ "display TEXT,"
					+ "cls_idx INTEGER)");

			st.execute("CREATE TABLE IF NOT EXISTS edges ("
					+ "src  INTEGER NOT NULL,"
					+ "dst  INTEGER NOT NULL,"
					+ "type INTEGER NOT NULL)");

			// The FTS layer (code_fts / const_strings / string_fts) lives in the per-shard DBs under
			// fts/ (see FtsShards), so M writer threads can tokenize in parallel — not in this DB.
			// Graph indexes (ix_*) are created AFTER the bulk structure+usage load — see createGraphIndexes.

			setMeta("schema_version", Integer.toString(SCHEMA_VERSION));
		}
	}

	/**
	 * Build the graph query indexes once, AFTER the multi-million-row structure+usage bulk insert
	 * (fast-index-pipeline 3.1). Maintaining these per-insert during the load is the classic bulk-load
	 * killer; deferring them to one post-load pass is dramatically faster. Idempotent ({@code IF NOT
	 * EXISTS}) so a resume that already built them is a no-op. Runs on the writer connection.
	 */
	public static void createGraphIndexes(Connection w) throws SQLException {
		try (Statement st = w.createStatement()) {
			st.execute("CREATE INDEX IF NOT EXISTS ix_cls_dex ON classes(dex_id)");
			st.execute("CREATE INDEX IF NOT EXISTS ix_sym_dex ON symbols(dex_id)");
			st.execute("CREATE INDEX IF NOT EXISTS ix_sym_clsdex ON symbols(cls_dex)");
			st.execute("CREATE INDEX IF NOT EXISTS ix_edge_dst ON edges(dst, type)");
			st.execute("CREATE INDEX IF NOT EXISTS ix_edge_src ON edges(src, type)");
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
