package com.zin.jadxheadless.index;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe progress for the background index build, surfaced by the {@code index_status} tool.
 * Until {@code state == READY}, {@code search_in_code} returns partial results + a progress note
 * instead of blocking past the 60s budget (mcp-re-toolset spec).
 */
public final class IndexStatus {

	public enum State {
		ABSENT, BUILDING, READY, FAILED
	}

	private volatile State state = State.ABSENT;
	private volatile String detail = "";
	private volatile int totalClasses = 0;
	private final AtomicInteger indexedClasses = new AtomicInteger();
	private final AtomicInteger symbols = new AtomicInteger();
	private final AtomicLong edges = new AtomicLong();
	private final AtomicInteger strings = new AtomicInteger();
	private volatile long buildStartMs = 0;
	private volatile long buildMs = 0;
	private volatile boolean reusedFromDisk = false;
	private volatile boolean coverageComplete = false;
	private volatile boolean resumed = false;

	public State state() {
		return state;
	}

	public boolean isReady() {
		return state == State.READY;
	}

	public void begin(int total) {
		this.state = State.BUILDING;
		this.totalClasses = total;
		this.indexedClasses.set(0);
		this.symbols.set(0);
		this.edges.set(0);
		this.strings.set(0);
		this.buildStartMs = System.currentTimeMillis();
		this.detail = "building";
	}

	public void markReusedComplete(int total) {
		this.state = State.READY;
		this.totalClasses = total;
		this.indexedClasses.set(total);
		this.reusedFromDisk = true;
		this.coverageComplete = true;
		this.detail = "loaded complete index from disk";
	}

	public void incIndexed() {
		indexedClasses.incrementAndGet();
	}

	/** Seed the indexed count when resuming a partial build (classes already in FTS from a prior run). */
	public void setIndexed(int n) {
		indexedClasses.set(n);
	}

	public void setResumed(boolean resumed) {
		this.resumed = resumed;
	}

	public void addSymbols(int n) {
		symbols.addAndGet(n);
	}

	public void addEdges(long n) {
		edges.addAndGet(n);
	}

	public void addStrings(int n) {
		strings.addAndGet(n);
	}

	public void finish(boolean ok, boolean coverageComplete, String detail) {
		this.buildMs = System.currentTimeMillis() - buildStartMs;
		// A heap-bounded partial build is still READY: the indexed subset is fully searchable; only
		// coverageComplete=false signals there is more to index on a later load.
		this.state = ok ? State.READY : State.FAILED;
		this.coverageComplete = coverageComplete;
		if (detail != null) {
			this.detail = detail;
		}
	}

	public void fail(String detail) {
		this.state = State.FAILED;
		this.detail = detail;
	}

	public int indexed() {
		return indexedClasses.get();
	}

	public int total() {
		return totalClasses;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("state", state.name().toLowerCase());
		m.put("ready", state == State.READY);
		m.put("indexed_classes", indexedClasses.get());
		m.put("total_classes", totalClasses);
		int pct = totalClasses == 0 ? (state == State.READY ? 100 : 0)
				: (int) (100.0 * indexedClasses.get() / totalClasses);
		m.put("percent", Math.min(100, pct));
		m.put("symbols", symbols.get());
		m.put("edges", edges.get());
		m.put("const_strings", strings.get());
		m.put("coverage_complete", coverageComplete);
		m.put("reused_from_disk", reusedFromDisk);
		m.put("resumed", resumed);
		if (buildMs > 0) {
			m.put("build_ms", buildMs);
		}
		if (!detail.isEmpty()) {
			m.put("detail", detail);
		}
		return m;
	}
}
