package com.zin.jadxheadless.index;

import java.sql.SQLException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.usage.IUsageInfoVisitor;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.FieldNode;
import jadx.core.dex.nodes.MethodNode;

/**
 * Drains jadx's computed usage graph into the SQLite edge table (D7). Invoked once after load via
 * {@code IUsageInfoData.visitUsageData(this)}; from then on every xref answer is a SQL query, so the
 * in-heap {@code getUseIn()} lists are never consulted by the tool layer.
 *
 * <p>Edge convention is uniform: <b>src uses/calls dst</b>.
 * <ul>
 *   <li>{@code CALLS}      — stored from {@link #visitMethodsUsage} as (caller → method); querying
 *                            {@code dst=M} yields callers, {@code src=M} yields callees (symmetric).</li>
 *   <li>{@code USES_CLASS} — class deps + class users + class-use-in-methods.</li>
 *   <li>{@code USES_FIELD} — (method → field).</li>
 * </ul>
 * EXTENDS/IMPLEMENTS edges are populated separately by the model-only structure pass.
 */
public final class SqliteExportVisitor implements IUsageInfoVisitor {

	private static final Logger LOG = LoggerFactory.getLogger(SqliteExportVisitor.class);

	private final SymbolGraph graph;
	private final IndexStatus status;
	private long edgeCount = 0;
	private long sinceCommit = 0;

	public SqliteExportVisitor(SymbolGraph graph, IndexStatus status) {
		this.graph = graph;
		this.status = status;
	}

	@Override
	public void visitClassDeps(ClassNode cls, List<ClassNode> deps) {
		try {
			int src = graph.classSym(cls);
			for (ClassNode dep : deps) {
				edge(src, graph.classSym(dep), Db.E_USES_CLASS);
			}
		} catch (SQLException e) {
			throw rethrow(e);
		}
	}

	@Override
	public void visitClassUsage(ClassNode cls, List<ClassNode> usage) {
		try {
			int dst = graph.classSym(cls);
			for (ClassNode user : usage) {
				edge(graph.classSym(user), dst, Db.E_USES_CLASS);
			}
		} catch (SQLException e) {
			throw rethrow(e);
		}
	}

	@Override
	public void visitClassUseInMethods(ClassNode cls, List<MethodNode> methods) {
		try {
			int dst = graph.classSym(cls);
			for (MethodNode m : methods) {
				edge(graph.methodSym(m), dst, Db.E_USES_CLASS);
			}
		} catch (SQLException e) {
			throw rethrow(e);
		}
	}

	@Override
	public void visitFieldsUsage(FieldNode fld, List<MethodNode> methods) {
		try {
			int dst = graph.fieldSym(fld);
			for (MethodNode m : methods) {
				edge(graph.methodSym(m), dst, Db.E_USES_FIELD);
			}
		} catch (SQLException e) {
			throw rethrow(e);
		}
	}

	@Override
	public void visitMethodsUsage(MethodNode mth, List<MethodNode> methods) {
		// `methods` are the callers of `mth`; store (caller -> mth) — gives both directions on query.
		// (jadx 1.5.5's IUsageInfoVisitor exposes only this incoming view, not a separate "uses"/callees
		// callback, so the full call graph is captured here.)
		try {
			int dst = graph.methodSym(mth);
			for (MethodNode caller : methods) {
				edge(graph.methodSym(caller), dst, Db.E_CALLS);
			}
		} catch (SQLException e) {
			throw rethrow(e);
		}
	}

	@Override
	public void visitComplete() {
		try {
			graph.commit();
		} catch (SQLException e) {
			LOG.warn("final usage commit failed: {}", e.toString());
		}
		status.addEdges(edgeCount);
		status.addSymbols(graph.symbolCount());
		LOG.info("[usage-export] {} edges, {} symbols", edgeCount, graph.symbolCount());
	}

	private void edge(int src, int dst, int type) throws SQLException {
		graph.addEdge(src, dst, type);
		edgeCount++;
		if (++sinceCommit >= 500_000) {
			graph.commit();
			sinceCommit = 0;
		}
	}

	private static RuntimeException rethrow(SQLException e) {
		return new RuntimeException("usage export SQL error: " + e.getMessage(), e);
	}
}
