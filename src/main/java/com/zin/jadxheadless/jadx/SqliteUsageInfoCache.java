package com.zin.jadxheadless.jadx;

import org.jetbrains.annotations.Nullable;

import jadx.api.usage.IUsageInfoCache;
import jadx.api.usage.IUsageInfoData;
import jadx.core.dex.nodes.RootNode;

/**
 * Custom {@link IUsageInfoCache} that is the capture point for the out-of-heap xref design (D7).
 *
 * <p>jadx's {@code UsageInfoVisitor} calls {@link #set} with the freshly computed usage graph; we
 * keep the reference so {@link #get} can hand it back for the model's lazy per-class
 * {@code applyForClass} (and so usage isn't recomputed within a session). The service then drains
 * that same graph to SQLite once after load via {@code data.visitUsageData(...)}, after which every
 * xref tool answers from SQLite — the heap {@code getUseIn()} lists are never read by the tool layer.
 *
 * <p>We deliberately do NOT disable usage computation: the spike showed that breaks anonymous/
 * synthetic class merging (class count 319k→456k, wrong output). Usage is computed and applied as
 * normal; the win is that queries leave the heap.
 */
public final class SqliteUsageInfoCache implements IUsageInfoCache {

	private volatile IUsageInfoData data;

	@Override
	public @Nullable IUsageInfoData get(RootNode root) {
		return data;
	}

	@Override
	public void set(RootNode root, IUsageInfoData data) {
		this.data = data;
	}

	/** The captured usage graph, for the post-load SQLite export. Null until jadx has computed it. */
	public @Nullable IUsageInfoData data() {
		return data;
	}

	/**
	 * Drop the in-heap usage graph after it has been exported to SQLite (D7). Frees several GB on a
	 * large app — measured necessary on Douyin, where retaining it starved the FTS decompile pass and
	 * tripped the low-heap guard. Safe: usage was already computed AND applied to the model during load
	 * (so class-merging and node use-in are intact); xref now comes from SQLite, and lazy per-class
	 * decompile no longer needs {@code applyForClass} (it re-reads code from the disk cache).
	 */
	public void releaseData() {
		data = null;
	}

	@Override
	public void close() {
		data = null;
	}
}
