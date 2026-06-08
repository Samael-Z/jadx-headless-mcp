package com.zin.jadxheadless.jadx;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import jadx.api.ICodeCache;
import jadx.api.ICodeInfo;

/**
 * Bounded in-heap LRU in front of a disk {@link ICodeCache} (D2). Replaces jadx's default unbounded
 * {@code InMemoryCodeCache}: a long session that touches thousands of classes keeps only the most
 * recently used {@code maxEntries} in heap; everything else is on disk. This is what keeps live heap
 * from growing without bound as the LLM browses code.
 */
public final class BoundedCodeCache implements ICodeCache {

	private final ICodeCache back;
	private final int maxEntries;
	private final LinkedHashMap<String, ICodeInfo> lru;

	public BoundedCodeCache(ICodeCache back, int maxEntries) {
		this.back = back;
		this.maxEntries = maxEntries;
		this.lru = new LinkedHashMap<>(Math.min(1024, maxEntries), 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, ICodeInfo> eldest) {
				return size() > BoundedCodeCache.this.maxEntries;
			}
		};
	}

	@Override
	public synchronized void add(String clsFullName, ICodeInfo codeInfo) {
		lru.put(clsFullName, codeInfo);
		back.add(clsFullName, codeInfo);
	}

	@Override
	public @NotNull ICodeInfo get(String clsFullName) {
		synchronized (this) {
			ICodeInfo hit = lru.get(clsFullName);
			if (hit != null) {
				return hit;
			}
		}
		ICodeInfo fromBack = back.get(clsFullName);
		if (fromBack != ICodeInfo.EMPTY) {
			synchronized (this) {
				lru.put(clsFullName, fromBack);
			}
		}
		return fromBack;
	}

	@Override
	public @Nullable String getCode(String clsFullName) {
		synchronized (this) {
			ICodeInfo hit = lru.get(clsFullName);
			if (hit != null) {
				return hit.getCodeStr();
			}
		}
		return back.getCode(clsFullName);
	}

	@Override
	public boolean contains(String clsFullName) {
		synchronized (this) {
			if (lru.containsKey(clsFullName)) {
				return true;
			}
		}
		return back.contains(clsFullName);
	}

	@Override
	public synchronized void remove(String clsFullName) {
		lru.remove(clsFullName);
		back.remove(clsFullName);
	}

	@Override
	public void close() throws IOException {
		synchronized (this) {
			lru.clear();
		}
		back.close();
	}
}
