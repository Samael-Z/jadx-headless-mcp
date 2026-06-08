package com.zin.jadxheadless.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;

/**
 * Resolves the per-APK cache directory {@code <cacheRoot>/<apk-hash>/} (D6). Everything derived
 * from one APK — the disk code cache, the SQLite symbol graph + FTS index — lives under here,
 * NOT next to the APK. Keyed by a fast content hash so a changed APK gets a fresh directory and
 * an unchanged one is reused across restarts.
 *
 * <p>Default root is {@code E:\JADX_CACHE_DIR}; override with the {@code JADX_CACHE_DIR} env var
 * or {@code -Djadx.cache.dir=...}.
 */
public final class CacheLayout {

	private static final long PARTIAL_HASH_WINDOW = 1L << 20; // 1 MiB head + 1 MiB tail

	private CacheLayout() {
	}

	/** Configured cache root (env {@code JADX_CACHE_DIR} > sysprop {@code jadx.cache.dir} > {@code E:\JADX_CACHE_DIR}). */
	public static Path cacheRoot() {
		String env = System.getenv("JADX_CACHE_DIR");
		if (env != null && !env.isBlank()) {
			return Paths.get(env.trim());
		}
		String prop = System.getProperty("jadx.cache.dir");
		if (prop != null && !prop.isBlank()) {
			return Paths.get(prop.trim());
		}
		return Paths.get("E:\\JADX_CACHE_DIR");
	}

	/** {@code <cacheRoot>/<apk-hash>/}, created if absent. */
	public static Path forApk(Path apk) throws IOException {
		String hash = contentHash(apk);
		Path dir = cacheRoot().resolve(hash);
		Files.createDirectories(dir);
		return dir;
	}

	/**
	 * Fast, content-sensitive hash: SHA-256 over the file size plus its first and last 1 MiB.
	 * Full-file hashing of a 295 MB APK on every load is wasteful; head+tail+size collides only
	 * on adversarially-crafted inputs, which is irrelevant for a local RE cache key.
	 */
	public static String contentHash(Path apk) throws IOException {
		try {
			long size = Files.size(apk);
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(Long.toString(size).getBytes(java.nio.charset.StandardCharsets.UTF_8));
			if (size <= 2 * PARTIAL_HASH_WINDOW) {
				try (InputStream in = Files.newInputStream(apk)) {
					byte[] buf = new byte[1 << 16];
					int n;
					while ((n = in.read(buf)) != -1) {
						md.update(buf, 0, n);
					}
				}
			} else {
				try (RandomAccessFile raf = new RandomAccessFile(apk.toFile(), "r")) {
					byte[] head = new byte[(int) PARTIAL_HASH_WINDOW];
					raf.readFully(head);
					md.update(head);
					byte[] tail = new byte[(int) PARTIAL_HASH_WINDOW];
					raf.seek(size - PARTIAL_HASH_WINDOW);
					raf.readFully(tail);
					md.update(tail);
				}
			}
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest()) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit(b & 0xF, 16));
			}
			// 16 hex chars (64 bits) is plenty to avoid collisions among a user's APK set.
			return sb.substring(0, 16);
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 unavailable", e);
		}
	}
}
