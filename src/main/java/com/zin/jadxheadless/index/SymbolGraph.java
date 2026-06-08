package com.zin.jadxheadless.index;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zin.jadxheadless.util.DexId;

import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;

/**
 * Read/write access to the symbol + edge graph (D7). During the build it get-or-creates symbol rows
 * keyed by dex-stable id (D5) and batches edge inserts; at query time it answers xref / call-graph /
 * subclass questions purely from SQLite — never touching jadx's in-heap {@code getUseIn()} lists.
 * That is the whole point of the out-of-heap design: xref survives across restarts and costs no heap.
 */
public final class SymbolGraph {

	private static final Logger LOG = LoggerFactory.getLogger(SymbolGraph.class);
	private static final int BATCH = 20_000;

	private final Db db;

	// ---- build-time state (live only between beginWrite() and endWrite()) ----
	private Connection w; // dedicated writer connection (owned by IndexBuilder)
	private HashMap<String, Integer> symIds; // dex_id -> symbol id
	private int nextSymId = 1;
	private PreparedStatement insSym;
	private PreparedStatement insEdge;
	private PreparedStatement insCls;
	private int symPending;
	private int edgePending;
	private int clsPending;

	public SymbolGraph(Db db) {
		this.db = db;
	}

	// ==================== build (uses the dedicated writer connection) ====================

	public void beginWrite(Connection writer) throws SQLException {
		this.w = writer;
		w.setAutoCommit(false);
		symIds = new HashMap<>(1 << 20);
		insSym = w.prepareStatement(
				"INSERT OR IGNORE INTO symbols(id,kind,dex_id,cls_dex,name,display,cls_idx) VALUES(?,?,?,?,?,?,?)");
		insEdge = w.prepareStatement("INSERT INTO edges(src,dst,type) VALUES(?,?,?)");
		insCls = w.prepareStatement(
				"INSERT OR IGNORE INTO classes(cls_idx,dex_id,fqn,pkg) VALUES(?,?,?,?)");
	}

	/** Register a class row (cls_idx ↔ dex id ↔ display FQN) and its class-kind symbol. */
	public int addClass(int clsIdx, ClassNode cls) throws SQLException {
		String dexId = DexId.forClass(cls);
		String fqn = safeFull(cls);
		String pkg = cls.getPackage();
		insCls.setInt(1, clsIdx);
		insCls.setString(2, dexId);
		insCls.setString(3, fqn);
		insCls.setString(4, pkg);
		insCls.addBatch();
		if (++clsPending >= BATCH) {
			insCls.executeBatch();
			clsPending = 0;
		}
		return symId(Db.K_CLASS, dexId, dexId, simpleName(fqn), fqn, clsIdx);
	}

	public int classSym(ClassNode cls) throws SQLException {
		String dexId = DexId.forClass(cls);
		return symId(Db.K_CLASS, dexId, dexId, simpleName(safeFull(cls)), safeFull(cls), null);
	}

	public int methodSym(MethodNode mth) throws SQLException {
		String dexId = DexId.forMethod(mth);
		String clsDex = DexId.forClass(mth.getParentClass());
		String display = safeFull(mth.getParentClass()) + "." + mth.getName();
		return symId(Db.K_METHOD, dexId, clsDex, mth.getName(), display, null);
	}

	public int fieldSym(FieldNode fld) throws SQLException {
		String dexId = DexId.forField(fld);
		String clsDex = DexId.forClass(fld.getParentClass());
		String display = safeFull(fld.getParentClass()) + "." + fld.getName();
		return symId(Db.K_FIELD, dexId, clsDex, fld.getName(), display, null);
	}

	private int symId(int kind, String dexId, String clsDex, String name, String display, Integer clsIdx)
			throws SQLException {
		Integer existing = symIds.get(dexId);
		if (existing != null) {
			return existing;
		}
		int id = nextSymId++;
		symIds.put(dexId, id);
		insSym.setInt(1, id);
		insSym.setInt(2, kind);
		insSym.setString(3, dexId);
		insSym.setString(4, clsDex);
		insSym.setString(5, name);
		insSym.setString(6, display);
		if (clsIdx == null) {
			insSym.setNull(7, java.sql.Types.INTEGER);
		} else {
			insSym.setInt(7, clsIdx);
		}
		insSym.addBatch();
		if (++symPending >= BATCH) {
			insSym.executeBatch();
			symPending = 0;
		}
		return id;
	}

	public void addEdge(int src, int dst, int type) throws SQLException {
		insEdge.setInt(1, src);
		insEdge.setInt(2, dst);
		insEdge.setInt(3, type);
		insEdge.addBatch();
		if (++edgePending >= BATCH) {
			insEdge.executeBatch();
			edgePending = 0;
		}
	}

	/** Flush pending batches and commit (call periodically during a long build). */
	public void commit() throws SQLException {
		if (symPending > 0) {
			insSym.executeBatch();
			symPending = 0;
		}
		if (clsPending > 0) {
			insCls.executeBatch();
			clsPending = 0;
		}
		if (edgePending > 0) {
			insEdge.executeBatch();
			edgePending = 0;
		}
		w.commit();
	}

	public int symbolCount() {
		return nextSymId - 1;
	}

	public void endWrite() throws SQLException {
		commit();
		w.setAutoCommit(true);
		symIds = null; // release the (potentially millions-entry) dex-id → id map
		closeQuietly(insSym);
		closeQuietly(insEdge);
		closeQuietly(insCls);
		insSym = insEdge = insCls = null;
		w = null;
	}

	// ==================== query (out of heap) ====================

	/** Resolve the symbol id for a class dex id (the current-FQN lookups go through JadxService first). */
	public Integer classIdByDexId(String classDexId) {
		return scalarId("SELECT id FROM symbols WHERE dex_id=? AND kind=" + Db.K_CLASS, classDexId);
	}

	public Integer symbolId(String dexId) {
		return scalarId("SELECT id FROM symbols WHERE dex_id=?", dexId);
	}

	private Integer scalarId(String sql, String arg) {
		synchronized (db.readLock()) {
			try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
				ps.setString(1, arg);
				try (ResultSet rs = ps.executeQuery()) {
					return rs.next() ? rs.getInt(1) : null;
				}
			} catch (SQLException e) {
				LOG.warn("scalarId failed: {}", e.toString());
				return null;
			}
		}
	}

	/** Incoming references to a symbol of the given edge type → the source symbols (callers/users). */
	public List<Map<String, Object>> incoming(int dstId, int type) {
		return neighbours(dstId, type, true);
	}

	/** Outgoing references from a symbol of the given edge type → the destination symbols (callees). */
	public List<Map<String, Object>> outgoing(int srcId, int type) {
		return neighbours(srcId, type, false);
	}

	private List<Map<String, Object>> neighbours(int pivot, int type, boolean incoming) {
		String join = incoming
				? "SELECT s.kind,s.dex_id,s.cls_dex,s.name,s.display FROM edges e "
						+ "JOIN symbols s ON s.id=e.src WHERE e.dst=? AND e.type=?"
				: "SELECT s.kind,s.dex_id,s.cls_dex,s.name,s.display FROM edges e "
						+ "JOIN symbols s ON s.id=e.dst WHERE e.src=? AND e.type=?";
		List<Map<String, Object>> out = new ArrayList<>();
		synchronized (db.readLock()) {
			try (PreparedStatement ps = db.conn().prepareStatement(join)) {
				ps.setInt(1, pivot);
				ps.setInt(2, type);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						out.add(rowMap(rs));
					}
				}
			} catch (SQLException e) {
				LOG.warn("neighbours query failed: {}", e.toString());
			}
		}
		return out;
	}

	/**
	 * Class-level xref-to: every node that uses class C (E_USES_CLASS), de-duplicated to the set of
	 * referencing classes (a method user is reported via its owner class). This is the out-of-heap
	 * analog of jadx {@code ClassNode.getUseIn() + getUseInMth()}.
	 */
	public List<Map<String, Object>> classUsers(int classSymId) {
		String sql = "SELECT DISTINCT s.cls_dex, c.fqn FROM edges e "
				+ "JOIN symbols s ON s.id=e.src "
				+ "LEFT JOIN classes c ON c.dex_id=s.cls_dex "
				+ "WHERE e.dst=? AND e.type=" + Db.E_USES_CLASS;
		List<Map<String, Object>> out = new ArrayList<>();
		synchronized (db.readLock()) {
			try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
				ps.setInt(1, classSymId);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						String clsDex = rs.getString(1);
						String fqn = rs.getString(2);
						out.add(Map.of("class", fqn != null ? fqn : clsDex));
					}
				}
			} catch (SQLException e) {
				LOG.warn("classUsers query failed: {}", e.toString());
			}
		}
		return out;
	}

	/** Classes directly called/used by class C → the set of distinct owner classes of its callees. */
	public List<Map<String, Object>> callGraphFrom(String classDex) {
		String sql = "SELECT DISTINCT dst.cls_dex, c.fqn FROM symbols src "
				+ "JOIN edges e ON e.src=src.id AND e.type=" + Db.E_CALLS + " "
				+ "JOIN symbols dst ON dst.id=e.dst "
				+ "LEFT JOIN classes c ON c.dex_id=dst.cls_dex "
				+ "WHERE src.cls_dex=? AND dst.cls_dex<>?";
		List<Map<String, Object>> out = new ArrayList<>();
		synchronized (db.readLock()) {
			try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
				ps.setString(1, classDex);
				ps.setString(2, classDex);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						String clsDex = rs.getString(1);
						String fqn = rs.getString(2);
						out.add(Map.of("class", fqn != null ? fqn : clsDex));
					}
				}
			} catch (SQLException e) {
				LOG.warn("callGraphFrom query failed: {}", e.toString());
			}
		}
		return out;
	}

	/** Direct subtypes (subclasses + interface implementors) of a class, from stored extends/implements edges. */
	public List<Map<String, Object>> subclasses(int classSymId) {
		String sql = "SELECT s.display, s.cls_dex, e.type FROM edges e "
				+ "JOIN symbols s ON s.id=e.src "
				+ "WHERE e.dst=? AND (e.type=" + Db.E_EXTENDS + " OR e.type=" + Db.E_IMPLEMENTS + ")";
		List<Map<String, Object>> out = new ArrayList<>();
		synchronized (db.readLock()) {
			try (PreparedStatement ps = db.conn().prepareStatement(sql)) {
				ps.setInt(1, classSymId);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("class", rs.getString(1) != null ? rs.getString(1) : rs.getString(2));
						m.put("relation", rs.getInt(3) == Db.E_EXTENDS ? "extends" : "implements");
						out.add(m);
					}
				}
			} catch (SQLException e) {
				LOG.warn("subclasses query failed: {}", e.toString());
			}
		}
		return out;
	}

	private static Map<String, Object> rowMap(ResultSet rs) throws SQLException {
		Map<String, Object> m = new LinkedHashMap<>();
		int kind = rs.getInt(1);
		m.put("kind", kind == Db.K_CLASS ? "class" : kind == Db.K_METHOD ? "method" : "field");
		m.put("name", rs.getString(4));
		m.put("display", rs.getString(5));
		m.put("class", rs.getString(3));
		return m;
	}

	private static String safeFull(ClassNode c) {
		try {
			return c.getFullName();
		} catch (Throwable t) {
			return DexId.forClass(c);
		}
	}

	private static String simpleName(String fqn) {
		if (fqn == null) {
			return null;
		}
		int dot = fqn.lastIndexOf('.');
		return dot >= 0 ? fqn.substring(dot + 1) : fqn;
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
