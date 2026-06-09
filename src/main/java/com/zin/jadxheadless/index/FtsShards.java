package com.zin.jadxheadless.index;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The FTS layer, split into {@code M} parallel shard databases (fast-index-pipeline D2). A single
 * SQLite database admits only one writer, so trigram tokenization of decompiled source serializes —
 * the dominant build bottleneck. Sharding {@code code_fts} (+ its {@code const_strings}/{@code string_fts}
 * siblings) into {@code M} self-contained DBs at {@code <cacheDir>/fts/shard-<i>.db}, routed by
 * {@code clsIdx % M}, lets {@code M} writer threads tokenize in parallel.
 *
 * <p><b>Build</b> (producer-consumer, D1): the decompile threads call {@link #enqueue} — which extracts
 * string literals on the calling thread (pure CPU, D3) and hands a unit to a bounded (by bytes)
 * per-shard queue; {@code M} writer threads drain the queues and do the SQLite writes. A byte
 * {@link Semaphore} provides backpressure so peak heap stays bounded regardless of decompile speed.
 *
 * <p><b>Query</b> (D2): {@code search_in_code}/string search fan out across all shards and union the
 * candidate {@code cls_idx} (then {@link CodeSearchIndex} resolves FQNs from the main DB and applies the
 * unchanged analysis-value filter/sort/limit). Per-shard reads use independent WAL read connections.
 *
 * <p>Shard count is fixed for an index (routing must be stable); it is stored in the main DB
 * {@code meta.fts_shards} and a {@code schema_version} bump invalidates pre-sharding indexes.
 */
public final class FtsShards implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(FtsShards.class);

	/** Default shard count. 8 keeps within SQLite's default ATTACH limit (main + ≤9) and leaves cores for decompile. */
	public static final int DEFAULT_SHARDS = 8;
	private static final int MAX_PERMIT = 1 << 26; // cap one class's queue reservation at 64 MiB
	private static final int COMMIT_EVERY = 2000; // per-shard rows between commits (readers see progress, WAL bounded)

	private final Path ftsDir;
	private final int m;
	private final Object readLock = new Object(); // guards all shard read connections (queries are sub-second)
	private final Connection[] readConn;

	// ---- build state (live only between beginBuild() and finishBuild()) ----
	private Connection[] writeConn;
	private PreparedStatement[] insCode;
	private PreparedStatement[] insStr;
	private List<BlockingQueue<Unit>> queues;
	private Thread[] writers;
	private Semaphore byteBudget;
	private volatile boolean buildAborted;

	private static final Unit POISON = new Unit(-1, null, null, 0);

	public FtsShards(Path ftsDir, int shards) throws SQLException {
		this.ftsDir = ftsDir;
		this.m = Math.max(1, shards);
		try {
			Files.createDirectories(ftsDir);
		} catch (Exception e) {
			throw new SQLException("cannot create fts dir " + ftsDir, e);
		}
		this.readConn = new Connection[m];
		for (int i = 0; i < m; i++) {
			Connection c = open(i);
			ensureSchema(c);
			readConn[i] = c;
		}
	}

	/** Shard count from {@code JADX_INDEX_SHARDS} (default {@value #DEFAULT_SHARDS}), clamped to [1,64]. */
	public static int shardCountFromEnv() {
		String v = System.getenv("JADX_INDEX_SHARDS");
		if (v != null) {
			try {
				int n = Integer.parseInt(v.trim());
				if (n >= 1 && n <= 64) {
					return n;
				}
			} catch (NumberFormatException ignored) {
				// keep default
			}
		}
		return DEFAULT_SHARDS;
	}

	public int shards() {
		return m;
	}

	private int route(int clsIdx) {
		return Math.floorMod(clsIdx, m);
	}

	private Connection open(int shard) throws SQLException {
		Path f = ftsDir.resolve("shard-" + shard + ".db");
		Connection c = DriverManager.getConnection("jdbc:sqlite:" + f.toAbsolutePath());
		try (Statement st = c.createStatement()) {
			st.execute("PRAGMA journal_mode=WAL");
			st.execute("PRAGMA synchronous=NORMAL");
			// Spill the string_fts 'rebuild' sort to disk, not native RAM: M shards rebuild concurrently
			// at end-of-build, and a MEMORY sort ×M would spike system memory and starve the JVM heap.
			st.execute("PRAGMA temp_store=FILE");
			st.execute("PRAGMA cache_size=-8192"); // ~8 MiB page cache per shard (×M; keep native RAM modest)
			st.execute("PRAGMA busy_timeout=30000");
		}
		return c;
	}

	private static void ensureSchema(Connection c) throws SQLException {
		try (Statement st = c.createStatement()) {
			st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS code_fts USING fts5("
					+ "body, content='', tokenize='trigram')");
			st.execute("CREATE TABLE IF NOT EXISTS const_strings ("
					+ "id INTEGER PRIMARY KEY, str TEXT NOT NULL, cls_idx INTEGER NOT NULL)");
			st.execute("CREATE INDEX IF NOT EXISTS ix_cstr_cls ON const_strings(cls_idx)");
			st.execute("CREATE VIRTUAL TABLE IF NOT EXISTS string_fts USING fts5("
					+ "str, content='const_strings', content_rowid='id', tokenize='trigram')");
		}
	}

	// ==================== build (producer-consumer) ====================

	/**
	 * Open {@code M} writer connections + start {@code M} consumer threads. {@code queueBudgetBytes}
	 * is the global backpressure cap (sum across shards) for queued source text.
	 */
	public void beginBuild(long queueBudgetBytes) throws SQLException {
		buildAborted = false;
		int budget = (int) Math.min(Integer.MAX_VALUE - 1, Math.max(MAX_PERMIT, queueBudgetBytes));
		byteBudget = new Semaphore(budget);
		writeConn = new Connection[m];
		insCode = new PreparedStatement[m];
		insStr = new PreparedStatement[m];
		queues = new ArrayList<>(m);
		writers = new Thread[m];
		for (int i = 0; i < m; i++) {
			Connection w = open(i);
			w.setAutoCommit(false);
			writeConn[i] = w;
			insCode[i] = w.prepareStatement("INSERT INTO code_fts(rowid, body) VALUES(?,?)");
			insStr[i] = w.prepareStatement("INSERT INTO const_strings(str, cls_idx) VALUES(?,?)");
			queues.add(new LinkedBlockingQueue<>());
		}
		for (int i = 0; i < m; i++) {
			final int shard = i;
			Thread t = new Thread(() -> writerLoop(shard), "fts-writer-" + i);
			t.setDaemon(true);
			writers[i] = t;
			t.start();
		}
	}

	/**
	 * Producer side (called from the parallel decompile threads): extract string literals (pure CPU,
	 * D3 — out of any write lock), then hand the unit to its shard's queue under byte backpressure.
	 * Returns the number of string literals harvested (for status accounting). Never blocks on a lock —
	 * only on the byte budget when consumers fall behind.
	 */
	public int enqueue(int clsIdx, String code) {
		boolean timing = CodeSearchIndex.BENCH_TIMING;
		long t0 = timing ? System.nanoTime() : 0L;
		List<String> strings = new ArrayList<>(CodeSearchIndex.extractStringLiterals(code));
		if (timing) {
			CodeSearchIndex.BENCH_STR_NANOS.addAndGet(System.nanoTime() - t0);
		}
		int permit = Math.min(Math.max(1, code.length()), MAX_PERMIT);
		try {
			byteBudget.acquire(permit);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return strings.size();
		}
		if (buildAborted) {
			byteBudget.release(permit);
			return strings.size();
		}
		queues.get(route(clsIdx)).add(new Unit(clsIdx, code, strings, permit));
		return strings.size();
	}

	private void writerLoop(int shard) {
		boolean timing = CodeSearchIndex.BENCH_TIMING;
		int pending = 0;
		BlockingQueue<Unit> q = queues.get(shard);
		try {
			while (true) {
				Unit u = q.take();
				if (u == POISON) {
					break;
				}
				if (u.flushLatch != null) {
					// Chunk-boundary flush: commit pending rows and TRUNCATE the WAL so it doesn't grow
					// unbounded across the long pass (native/OS RAM pressure that degrades JVM GC).
					try {
						if (pending > 0) {
							writeConn[shard].commit();
							pending = 0;
						}
						try (Statement st = writeConn[shard].createStatement()) {
							st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
						}
					} catch (SQLException e) {
						LOG.warn("[fts-writer-{}] flush failed: {}", shard, e.toString());
					} finally {
						u.flushLatch.countDown();
					}
					continue;
				}
				try {
					long t0 = timing ? System.nanoTime() : 0L;
					insCode[shard].setInt(1, u.clsIdx);
					insCode[shard].setString(2, u.code);
					insCode[shard].executeUpdate();
					if (timing) {
						CodeSearchIndex.BENCH_FTS_NANOS.addAndGet(System.nanoTime() - t0);
					}
					if (!u.strings.isEmpty()) {
						for (String s : u.strings) {
							insStr[shard].setString(1, s);
							insStr[shard].setInt(2, u.clsIdx);
							insStr[shard].addBatch();
						}
						insStr[shard].executeBatch();
					}
					if (++pending >= COMMIT_EVERY) {
						writeConn[shard].commit();
						pending = 0;
					}
				} catch (SQLException e) {
					LOG.warn("[fts-writer-{}] insert cls {} failed: {}", shard, u.clsIdx, e.toString());
				} finally {
					byteBudget.release(u.permit);
				}
			}
			// drain done for this shard: commit, then rebuild the external-content string index.
			writeConn[shard].commit();
			try (Statement st = writeConn[shard].createStatement()) {
				st.execute("INSERT INTO string_fts(string_fts) VALUES('rebuild')");
			}
			writeConn[shard].commit();
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		} catch (SQLException e) {
			LOG.warn("[fts-writer-{}] finalize failed: {}", shard, e.toString());
		}
	}

	/**
	 * Chunk-boundary flush: enqueue a flush marker on every shard and block until each writer has
	 * committed its pending rows and TRUNCATEd its WAL. Called between decompile chunks so the queue is
	 * empty and WAL files stay small at the heap-headroom check (keeps peak RAM bounded across the pass).
	 */
	public void flush() {
		if (writers == null) {
			return;
		}
		CountDownLatch latch = new CountDownLatch(m);
		for (int i = 0; i < m; i++) {
			queues.get(i).add(new Unit(latch));
		}
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Signal end-of-input, wait for all shard writers to drain + rebuild + commit, then close write conns. */
	public void finishBuild() {
		if (writers == null) {
			return;
		}
		for (int i = 0; i < m; i++) {
			queues.get(i).add(POISON);
		}
		for (Thread t : writers) {
			try {
				t.join();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
		for (int i = 0; i < m; i++) {
			closeQuietly(insCode[i]);
			closeQuietly(insStr[i]);
			try {
				writeConn[i].close();
			} catch (SQLException ignored) {
				// ignore
			}
		}
		writeConn = null;
		insCode = insStr = null;
		writers = null;
		queues = null;
	}

	/** Abort an in-flight build (APK switch / shutdown): unblock producers and stop writers fast. */
	public void abortBuild() {
		buildAborted = true;
		Thread[] ws = writers;
		if (byteBudget != null) {
			byteBudget.release(Integer.MAX_VALUE / 2); // wake any producer blocked on acquire
		}
		if (ws != null) {
			for (Thread t : ws) {
				t.interrupt();
			}
		}
	}

	// ==================== query (fan-out across shards) ====================

	/** Candidate cls_idx for a code_fts MATCH, unioned across shards (cap total). */
	public Set<Integer> matchCode(String phrase, int cap) {
		Set<Integer> out = new HashSet<>();
		synchronized (readLock) {
			for (int i = 0; i < m && out.size() < cap; i++) {
				try (PreparedStatement ps = readConn[i].prepareStatement(
						"SELECT rowid FROM code_fts WHERE code_fts MATCH ? LIMIT ?")) {
					ps.setString(1, phrase);
					ps.setInt(2, cap);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							out.add(rs.getInt(1));
						}
					}
				} catch (SQLException e) {
					LOG.warn("matchCode shard {} failed: {}", i, e.toString());
				}
			}
		}
		return out;
	}

	/**
	 * String-literal matches across shards, returned as (cls_idx → distinct matching strings). When
	 * {@code useFts} the trigram {@code string_fts} drives it; otherwise a {@code LIKE} scan of
	 * {@code const_strings}. Capped at {@code cap} total rows.
	 */
	public Map<Integer, Set<String>> matchStrings(String arg, boolean useFts, int cap) {
		String sql = useFts
				? "SELECT cs.cls_idx, cs.str FROM string_fts sf JOIN const_strings cs ON cs.id=sf.rowid "
						+ "WHERE string_fts MATCH ? LIMIT ?"
				: "SELECT cs.cls_idx, cs.str FROM const_strings cs WHERE cs.str LIKE ? LIMIT ?";
		Map<Integer, Set<String>> byCls = new LinkedHashMap<>();
		synchronized (readLock) {
			int seen = 0;
			for (int i = 0; i < m && seen < cap; i++) {
				try (PreparedStatement ps = readConn[i].prepareStatement(sql)) {
					ps.setString(1, arg);
					ps.setInt(2, cap);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							byCls.computeIfAbsent(rs.getInt(1), k -> new java.util.LinkedHashSet<>()).add(rs.getString(2));
							seen++;
						}
					}
				} catch (SQLException e) {
					LOG.warn("matchStrings shard {} failed: {}", i, e.toString());
				}
			}
		}
		return byCls;
	}

	/** cls_idx of classes containing an EXACT string literal, unioned across shards. */
	public Set<Integer> exactString(String value, int cap) {
		Set<Integer> out = new HashSet<>();
		synchronized (readLock) {
			for (int i = 0; i < m && out.size() < cap; i++) {
				try (PreparedStatement ps = readConn[i].prepareStatement(
						"SELECT DISTINCT cls_idx FROM const_strings WHERE str = ? LIMIT ?")) {
					ps.setString(1, value);
					ps.setInt(2, cap);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							out.add(rs.getInt(1));
						}
					}
				} catch (SQLException e) {
					LOG.warn("exactString shard {} failed: {}", i, e.toString());
				}
			}
		}
		return out;
	}

	/** cls_idx already present in code_fts across all shards (resume skips these). */
	public Set<Integer> indexedRowids() {
		Set<Integer> out = new HashSet<>();
		synchronized (readLock) {
			for (int i = 0; i < m; i++) {
				try (Statement st = readConn[i].createStatement();
						ResultSet rs = st.executeQuery("SELECT rowid FROM code_fts")) {
					while (rs.next()) {
						out.add(rs.getInt(1));
					}
				} catch (SQLException e) {
					LOG.warn("indexedRowids shard {} failed: {}", i, e.toString());
				}
			}
		}
		return out;
	}

	/** Total const-string rows across all shards (backfills index_status on reuse). */
	public long countConstStrings() {
		long total = 0;
		synchronized (readLock) {
			for (int i = 0; i < m; i++) {
				try (Statement st = readConn[i].createStatement();
						ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM const_strings")) {
					if (rs.next()) {
						total += rs.getLong(1);
					}
				} catch (SQLException e) {
					LOG.warn("countConstStrings shard {} failed: {}", i, e.toString());
				}
			}
		}
		return total;
	}

	@Override
	public void close() {
		synchronized (readLock) {
			for (int i = 0; i < m; i++) {
				try {
					if (readConn[i] != null) {
						try (Statement st = readConn[i].createStatement()) {
							st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
						} catch (SQLException ignored) {
							// best effort
						}
						readConn[i].close();
					}
				} catch (SQLException e) {
					LOG.warn("close shard {} failed: {}", i, e.toString());
				}
			}
		}
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

	/** One queued class: decompiled source + harvested literals + its byte reservation (or a flush marker). */
	private static final class Unit {
		final int clsIdx;
		final String code;
		final List<String> strings;
		final int permit;
		final CountDownLatch flushLatch; // non-null ⇒ a flush marker (not a class)

		Unit(int clsIdx, String code, List<String> strings, int permit) {
			this.clsIdx = clsIdx;
			this.code = code;
			this.strings = strings;
			this.permit = permit;
			this.flushLatch = null;
		}

		/** Flush marker: the writer commits pending rows + TRUNCATEs the WAL, then counts the latch down. */
		Unit(CountDownLatch flushLatch) {
			this.clsIdx = -2;
			this.code = null;
			this.strings = null;
			this.permit = 0;
			this.flushLatch = flushLatch;
		}
	}
}
