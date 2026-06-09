package com.zin.jadxheadless.index;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe progress for the background index build, surfaced by the {@code index_status} tool.
 *
 * <p>Reports the change's <b>tiered progressive availability</b> (progressive-index-availability): the
 * build advances {@code xref → entry → main → rest}, and each tier becomes searchable as it completes
 * rather than only at the end. {@code current_tier} / {@code xref_ready} / {@code entry_ready} /
 * {@code main_ready} tell a caller what is already usable; {@code decompiled_classes} (have {@code .java}
 * on disk) vs {@code indexed_classes} (in FTS) expose the small build-time lag between the two. Until
 * {@code coverage_complete}, {@code search_in_code} still covers every decompiled class (FTS ∪ ripgrep,
 * see {@link CodeSearchIndex}); these fields only describe how far the build has progressed.
 */
public final class IndexStatus {

	public enum State {
		ABSENT, BUILDING, READY, FAILED
	}

	/** Value-prioritized build tiers (D2). {@code current_tier} reports the active one. */
	public enum Tier {
		XREF("xref"), ENTRY("entry"), MAIN("main"), REST("rest"), COMPLETE("complete");

		private final String label;

		Tier(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}
	}

	private volatile State state = State.ABSENT;
	private volatile String detail = "";
	private volatile int totalClasses = 0;
	private final AtomicInteger decompiledClasses = new AtomicInteger();
	private final AtomicInteger indexedClasses = new AtomicInteger();
	private final AtomicInteger symbols = new AtomicInteger();
	private final AtomicLong edges = new AtomicLong();
	private final AtomicInteger strings = new AtomicInteger();
	private volatile long buildStartMs = 0;
	private volatile long buildMs = 0;
	private volatile boolean reusedFromDisk = false;
	private volatile boolean coverageComplete = false;
	private volatile boolean resumed = false;
	private volatile String scope = "";
	private volatile int inScopeClasses = -1;

	// ---- tiered availability (progressive-index-availability D6) ----
	private volatile Tier currentTier = Tier.XREF;
	private volatile boolean xrefReady = false;
	private volatile boolean entryReady = false;
	private volatile boolean mainReady = false;

	public State state() {
		return state;
	}

	public boolean isReady() {
		return state == State.READY;
	}

	public boolean coverageComplete() {
		return coverageComplete;
	}

	public void begin(int total) {
		this.state = State.BUILDING;
		this.totalClasses = total;
		this.decompiledClasses.set(0);
		this.indexedClasses.set(0);
		this.symbols.set(0);
		this.edges.set(0);
		this.strings.set(0);
		this.currentTier = Tier.XREF;
		this.xrefReady = false;
		this.entryReady = false;
		this.mainReady = false;
		this.buildStartMs = System.currentTimeMillis();
		this.detail = "building";
	}

	public void markReusedComplete(int total) {
		this.state = State.READY;
		this.totalClasses = total;
		this.decompiledClasses.set(total);
		this.indexedClasses.set(total);
		this.reusedFromDisk = true;
		this.coverageComplete = true;
		this.xrefReady = true;
		this.entryReady = true;
		this.mainReady = true;
		this.currentTier = Tier.COMPLETE;
		this.detail = "loaded complete index from disk";
	}

	/** One class processed by the decompile pass (its {@code .java} is now on the disk cache). */
	public void incDecompiled() {
		decompiledClasses.incrementAndGet();
	}

	/** Seed the decompiled count when resuming a partial build (classes already on disk / in FTS). */
	public void setDecompiled(int n) {
		decompiledClasses.set(n);
	}

	public int decompiled() {
		return decompiledClasses.get();
	}

	/** Classes committed to FTS (≈ decompiled at each chunk-flush boundary; briefly lags mid-chunk). */
	public void setIndexedClasses(int n) {
		indexedClasses.set(n);
	}

	public int indexedClasses() {
		return indexedClasses.get();
	}

	/** Reset the denominator to the in-scope class count (layer 2) so {@code percent} tracks scope coverage. */
	public void setTotal(int n) {
		this.totalClasses = n;
	}

	public void setResumed(boolean resumed) {
		this.resumed = resumed;
	}

	/** Human-readable description of the active index scope (see {@link AnalysisScope#describe()}). */
	public void setScope(String scope) {
		this.scope = scope == null ? "" : scope;
	}

	/** Number of classes selected for decompile/FTS under the active scope (layer 2); -1 if not yet known. */
	public void setInScopeClasses(int n) {
		this.inScopeClasses = n;
	}

	// ---- tier transitions (set by IndexBuilder as the build advances; seeded from disk meta on resume) ----

	public void setCurrentTier(Tier t) {
		if (t != null) {
			this.currentTier = t;
		}
	}

	public Tier currentTier() {
		return currentTier;
	}

	public void setXrefReady(boolean v) {
		this.xrefReady = v;
	}

	public void setEntryReady(boolean v) {
		this.entryReady = v;
	}

	public void setMainReady(boolean v) {
		this.mainReady = v;
	}

	public boolean isXrefReady() {
		return xrefReady;
	}

	public boolean isEntryReady() {
		return entryReady;
	}

	public boolean isMainReady() {
		return mainReady;
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
		// coverageComplete=false signals there is more to index on a later load. current_tier stays at
		// the tier it stopped on (informative) unless we actually completed.
		this.state = ok ? State.READY : State.FAILED;
		this.coverageComplete = coverageComplete;
		if (coverageComplete) {
			this.currentTier = Tier.COMPLETE;
			this.indexedClasses.set(Math.max(indexedClasses.get(), decompiledClasses.get()));
		}
		if (detail != null) {
			this.detail = detail;
		}
	}

	public void fail(String detail) {
		this.state = State.FAILED;
		this.detail = detail;
	}

	public int total() {
		return totalClasses;
	}

	public Map<String, Object> toMap() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("state", state.name().toLowerCase());
		m.put("ready", state == State.READY);
		// tiered availability — what is already searchable (progressive-index-availability D6)
		m.put("current_tier", currentTier.label());
		m.put("xref_ready", xrefReady);
		m.put("entry_ready", entryReady);
		m.put("main_ready", mainReady);
		m.put("decompiled_classes", decompiledClasses.get());
		m.put("indexed_classes", indexedClasses.get());
		m.put("total_classes", totalClasses);
		int pct = totalClasses == 0 ? (state == State.READY ? 100 : 0)
				: (int) (100.0 * decompiledClasses.get() / totalClasses);
		m.put("percent", Math.min(100, pct));
		m.put("symbols", symbols.get());
		m.put("edges", edges.get());
		m.put("const_strings", strings.get());
		m.put("coverage_complete", coverageComplete);
		m.put("reused_from_disk", reusedFromDisk);
		m.put("resumed", resumed);
		if (!scope.isEmpty()) {
			m.put("index_scope", scope);
		}
		if (inScopeClasses >= 0) {
			m.put("in_scope_classes", inScopeClasses);
		}
		if (buildMs > 0) {
			m.put("build_ms", buildMs);
		}
		if (!detail.isEmpty()) {
			m.put("detail", detail);
		}
		return m;
	}
}
