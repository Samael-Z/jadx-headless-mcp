package com.zin.jadxheadless.util;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Journals user renames so they survive restarts and APK reloads (mcp-re-toolset: "改名持久化").
 * jadx only writes rename mappings from the GUI; headless must journal + replay itself. Stored in the
 * per-APK cache dir as a simple TSV ({@code type<TAB>cls<TAB>id<TAB>new} per line) — no JSON library,
 * keeping serialization decoupled from the MCP SDK's bundled Jackson.
 */
public final class RenameStore {

	private static final Logger LOG = LoggerFactory.getLogger(RenameStore.class);

	private final List<Map<String, String>> records = new ArrayList<>();
	private Path file;

	public synchronized void setFile(Path file) {
		this.file = file;
	}

	/** Load existing records from {@code file}. Returns how many were loaded. */
	public synchronized int load(Path file) {
		this.file = file;
		records.clear();
		if (!Files.isReadable(file)) {
			return 0;
		}
		try {
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				if (line.isBlank()) {
					continue;
				}
				String[] f = line.split("\t", -1);
				if (f.length < 4) {
					continue;
				}
				Map<String, String> rec = new LinkedHashMap<>();
				rec.put("type", f[0]);
				rec.put("cls", f[1]);
				if (!f[2].isEmpty()) {
					rec.put("id", f[2]);
				}
				rec.put("new", f[3]);
				records.add(rec);
			}
			return records.size();
		} catch (Exception e) {
			LOG.warn("rename journal load failed: {}", e.toString());
			return 0;
		}
	}

	/** Record a rename and persist the whole journal (rename is rare; full rewrite is fine). */
	public synchronized void record(Map<String, String> rec) {
		records.add(rec);
		persist();
	}

	public synchronized List<Map<String, String>> all() {
		return new ArrayList<>(records);
	}

	private void persist() {
		if (file == null) {
			return;
		}
		try {
			StringBuilder sb = new StringBuilder();
			for (Map<String, String> rec : records) {
				sb.append(nz(rec.get("type"))).append('\t')
						.append(nz(rec.get("cls"))).append('\t')
						.append(nz(rec.get("id"))).append('\t')
						.append(nz(rec.get("new"))).append('\n');
			}
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			if (file.getParent() != null) {
				Files.createDirectories(file.getParent());
			}
			Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (Exception e) {
			LOG.warn("rename journal persist failed: {}", e.toString());
		}
	}

	private static String nz(String s) {
		return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
	}
}
