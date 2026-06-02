package com.zin.jadxheadless.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Append-only log of user renames, persisted next to the APK so that in-memory renames
 * survive a restart (jadx itself only <i>writes</i> user-rename mappings from the GUI's
 * "save project"; headless has no equivalent, so we journal them ourselves).
 *
 * <p><b>Ordered-replay model.</b> Records are kept in the exact order the renames happened
 * and replayed in that same order on the next load. Each record locates its target by the
 * name that was current <i>at the time of the rename</i>; because replay reproduces history
 * step by step, every step sees the same model state it saw originally — so chained renames
 * (rename class A→B, then a method of B) resolve correctly without needing a stable node id.
 *
 * <p>Record fields (all strings): {@code kind} (class|method|field), {@code locator} (the
 * owning class FQN as it was before this rename), {@code member} + {@code descriptor} (for
 * method/field targeting), {@code old} + {@code new} (the NodeRenamedByUser event names).
 */
public final class RenameStore {

    private static final Logger logger = LoggerFactory.getLogger(RenameStore.class);
    private static final ObjectMapper OM = new ObjectMapper();

    private final List<Map<String, String>> records = Collections.synchronizedList(new ArrayList<>());
    private volatile Path file;

    /** Where to persist. Set once at startup; null disables persistence (in-memory only). */
    public void setFile(Path f) {
        this.file = f;
    }

    public int size() {
        return records.size();
    }

    /** Snapshot copy of all records in insertion order. */
    public List<Map<String, String>> all() {
        synchronized (records) {
            return new ArrayList<>(records);
        }
    }

    /** Append a record and persist the whole log. Safe to call concurrently. */
    public synchronized void record(Map<String, String> rec) {
        records.add(rec);
        persist();
    }

    /**
     * Load records from {@code f} into memory (replacing any existing). Returns the number of
     * records loaded, or 0 if the file is absent/unreadable/corrupt (never throws).
     */
    public int load(Path f) {
        if (f == null || !Files.isReadable(f)) {
            return 0;
        }
        try {
            List<Map<String, String>> loaded =
                    OM.readValue(f.toFile(), new TypeReference<List<Map<String, String>>>() {});
            synchronized (records) {
                records.clear();
                records.addAll(loaded);
            }
            return loaded.size();
        } catch (Exception e) {
            logger.warn("[renames] load failed ({}); starting empty", e.toString());
            return 0;
        }
    }

    /** Atomically write the current log to {@link #file} (tmp + move). Best-effort. */
    private void persist() {
        Path f = file;
        if (f == null) {
            return;
        }
        try {
            if (f.getParent() != null) {
                Files.createDirectories(f.getParent());
            }
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            OM.writeValue(tmp.toFile(), all());
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.warn("[renames] persist failed (in-memory rename still active): {}", e.toString());
        }
    }
}
