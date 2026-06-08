package com.zin.jadxheadless.jadx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jadx.api.ICodeCache;
import jadx.api.ICodeInfo;
import jadx.api.impl.SimpleCodeInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.RootNode;

/**
 * Disk-backed {@link ICodeCache} for the headless service (D2/D6). Modeled on jadx-gui's
 * {@code DiskCodeCache} but <b>source-text only</b> (no offset→node metadata): the MCP tools serve
 * text, not GUI navigation, so persisting metadata would only cost disk + time.
 *
 * <p>Decompiled source is written under {@code <cacheDir>/code/sources/<XX>/<clsIdHex>.java}, keyed by
 * a {@code code-version} stamp (jadx version + naming config). On reload with a matching stamp the
 * already-decompiled classes are detected and reused — so a class hit in a prior session returns
 * instantly and {@code search_in_code} need not re-decompile (cross-restart reuse, 5.6). A stamp
 * mismatch (e.g. user toggled {@code deobf}) resets the directory.
 *
 * <p>Combine with {@link BoundedCodeCache} as the in-heap front so live heap stays bounded
 * regardless of how many classes a long session touches.
 */
public final class DiskCodeCache implements ICodeCache {

	private static final Logger LOG = LoggerFactory.getLogger(DiskCodeCache.class);

	private final Path srcDir;
	private final Path codeVersionFile;
	private final String codeVersion;
	private final ExecutorService writePool;
	private final Map<String, CacheData> clsDataMap;

	public DiskCodeCache(RootNode root, Path cacheDir, String codeVersion) {
		this.srcDir = cacheDir.resolve("code").resolve("sources");
		this.codeVersionFile = cacheDir.resolve("code").resolve("code-version");
		this.codeVersion = codeVersion;
		int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
		this.writePool = Executors.newFixedThreadPool(threads, r -> {
			Thread t = new Thread(r, "code-cache-writer");
			t.setDaemon(true);
			return t;
		});
		this.clsDataMap = buildClassDataMap(root.getClasses());
		if (checkCodeVersion()) {
			loadCachedSet();
		} else {
			reset();
		}
	}

	private Map<String, CacheData> buildClassDataMap(java.util.List<ClassNode> classes) {
		Map<String, CacheData> map = new HashMap<>(classes.size());
		for (int i = 0; i < classes.size(); i++) {
			map.put(classes.get(i).getRawName(), new CacheData(i));
		}
		return map;
	}

	private boolean checkCodeVersion() {
		try {
			if (!Files.exists(codeVersionFile)) {
				return false;
			}
			return codeVersion.equals(Files.readString(codeVersionFile, StandardCharsets.UTF_8));
		} catch (Exception e) {
			return false;
		}
	}

	private void reset() {
		try {
			deleteDir(srcDir.getParent());
			Files.createDirectories(srcDir);
			Files.writeString(codeVersionFile, codeVersion, StandardCharsets.UTF_8);
			LOG.info("disk code cache reset at {}", srcDir.getParent().toAbsolutePath());
		} catch (Exception e) {
			LOG.warn("disk code cache reset failed: {}", e.toString());
		} finally {
			clsDataMap.values().forEach(d -> d.cached = false);
		}
	}

	private void loadCachedSet() {
		long start = System.currentTimeMillis();
		Map<Integer, Boolean> present = new HashMap<>();
		try (Stream<Path> s = Files.walk(srcDir)) {
			s.forEach(f -> {
				String name = f.getFileName().toString();
				if (name.endsWith(".java")) {
					try {
						present.put(Integer.parseInt(name.substring(0, name.length() - 5), 16), Boolean.TRUE);
					} catch (NumberFormatException ignored) {
						// ignore stray files
					}
				}
			});
		} catch (Exception e) {
			LOG.warn("failed to scan disk code cache: {}", e.toString());
			return;
		}
		int count = 0;
		for (CacheData d : clsDataMap.values()) {
			if (present.containsKey(d.clsId)) {
				d.cached = true;
				count++;
			}
		}
		LOG.info("disk code cache: {} classes reusable from prior run ({}ms)", count, System.currentTimeMillis() - start);
	}

	/**
	 * Stable class id for a raw class name — equal to its index in {@code root.getClasses()} and to
	 * the hex stem of its on-disk {@code .java} file. The index build reuses this as the SQLite
	 * {@code cls_idx} / FTS rowid so the ripgrep fallback can map a matched file back to a class.
	 */
	public int clsId(String rawName) {
		CacheData d = clsDataMap.get(rawName);
		return d == null ? -1 : d.clsId;
	}

	@Override
	public void add(String clsFullName, ICodeInfo codeInfo) {
		CacheData d = clsDataMap.get(clsFullName);
		if (d == null) {
			return; // unknown class (e.g. synthetic) — skip caching
		}
		d.tmp = codeInfo;
		d.cached = true;
		writePool.execute(() -> {
			try {
				ICodeInfo code = d.tmp;
				if (code != null) {
					Path f = javaFile(d.clsId);
					Files.createDirectories(f.getParent());
					Files.writeString(f, code.getCodeStr(), StandardCharsets.UTF_8);
				}
			} catch (Exception e) {
				LOG.warn("write code cache for {} failed: {}", clsFullName, e.toString());
			} finally {
				d.tmp = null;
			}
		});
	}

	@Override
	public @Nullable String getCode(String clsFullName) {
		CacheData d = clsDataMap.get(clsFullName);
		if (d == null || !d.cached) {
			return null;
		}
		ICodeInfo tmp = d.tmp;
		if (tmp != null) {
			return tmp.getCodeStr();
		}
		try {
			Path f = javaFile(d.clsId);
			return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public @NotNull ICodeInfo get(String clsFullName) {
		CacheData d = clsDataMap.get(clsFullName);
		if (d == null || !d.cached) {
			return ICodeInfo.EMPTY;
		}
		ICodeInfo tmp = d.tmp;
		if (tmp != null) {
			return tmp;
		}
		String code = getCode(clsFullName);
		return code == null ? ICodeInfo.EMPTY : new SimpleCodeInfo(code);
	}

	@Override
	public boolean contains(String clsFullName) {
		CacheData d = clsDataMap.get(clsFullName);
		return d != null && d.cached;
	}

	@Override
	public void remove(String clsFullName) {
		CacheData d = clsDataMap.get(clsFullName);
		if (d == null) {
			return;
		}
		d.cached = false;
		ICodeInfo tmp = d.tmp;
		if (tmp == null) {
			try {
				Files.deleteIfExists(javaFile(d.clsId));
			} catch (Exception ignored) {
				// ignore
			}
		} else {
			d.tmp = null;
		}
	}

	private Path javaFile(int clsId) {
		String folder = String.format("%02x", clsId & 0xFF);
		return srcDir.resolve(folder).resolve(Integer.toHexString(clsId) + ".java");
	}

	private static void deleteDir(Path dir) throws IOException {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			s.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// ignore
				}
			});
		}
	}

	@Override
	public void close() {
		writePool.shutdown();
		try {
			if (!writePool.awaitTermination(1, TimeUnit.MINUTES)) {
				LOG.warn("code cache writer pool did not drain within 1 min");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static final class CacheData {
		final int clsId;
		volatile boolean cached;
		volatile @Nullable ICodeInfo tmp;

		CacheData(int clsId) {
			this.clsId = clsId;
		}
	}
}
