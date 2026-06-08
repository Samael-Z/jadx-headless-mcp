# jadx-headless-mcp-v2

A **single-process, headless** jadx decompiler + **MCP server** built for reverse engineering large
Android apps (e.g. Douyin: 295 MB / 55 dex) under a 20 GB heap, with every MCP tool call bounded to
~60 s. Replaces the earlier Rust-bridge `jadx-headless-mcp` and the GUI-bound `jadx-ai-mcp`.

This is the implementation of the OpenSpec change `headless-jadx-mcp`
(`../jadx/openspec/changes/headless-jadx-mcp/`).

## Architecture (one JVM)

```
LLM client ──Streamable HTTP──▶ MCP server (official Java SDK) ──in-process──▶ jadx-core
              (Jetty 12, /mcp)     tools → JadxService / SQLite index           (decompiler)
```

- **No internal HTTP bridge, no Python.** The MCP server and the jadx engine share one process (D1).
- **Resident + lazy:** `load_apk` builds the model once; classes decompile on demand into a
  **bounded in-heap LRU over a disk code cache** (`BoundedCodeCache` → `DiskCodeCache`), so a long
  session never grows the heap without bound (D2). The default unbounded `InMemoryCodeCache` is not used.
- **Out-of-heap xref (D7):** a custom `IUsageInfoCache` captures jadx's computed usage graph; it is
  drained to a **SQLite symbol + edge graph** after load, and all `get_xrefs_*` / `get_call_graph`
  queries hit SQLite — never jadx's in-heap `getUseIn()`.
- **Code search (D6):** decompiled text is indexed into a **contentless FTS5 trigram** table
  (text stays on the disk cache, not duplicated); `search_in_code` uses it, falling back to **ripgrep**
  over the disk cache for full regex.
- **dex-stable keys (D5):** the index is keyed by raw dex descriptors/signatures, so toggling naming
  options or renaming never invalidates the structural index — only the display-name layer.
- **Naming (D4):** `useSourceNameAsClassNameAlias=ALWAYS` + kotlin-metadata (auto); `deobf` OFF by
  default (a `--deobf` knob), since on heavily-obfuscated apps it degrades package names.

Everything derived from one APK lives under `<JADX_CACHE_DIR>/<apk-hash>/` (disk code cache + `index.db`),
reused across restarts (same hash + complete index → skip rebuild).

## Build

```powershell
cd jadx-headless-mcp-v2
mvn -s settings.xml package
# → target/jadx-headless-mcp-v2.jar  (runnable fat jar)
```

`settings.xml` routes Maven through the Aliyun mirror (the local proxy resets TLS to Maven Central).
Requires JDK 17+ (Jetty 12 / MCP SDK). jadx artifacts are pulled from Maven Central, not the sibling
source tree.

## Run

```powershell
java -Xmx20g -Djava.awt.headless=true -jar target/jadx-headless-mcp-v2.jar `
     --host 127.0.0.1 --port 8650 [--apk <path>] [--deobf]
```

- MCP endpoint: `http://127.0.0.1:8650/mcp` (Streamable HTTP, localhost, **no auth** — for remote use
  an SSH tunnel; do not bind a non-localhost host).
- Cache root: `E:\JADX_CACHE_DIR` by default; override with the `JADX_CACHE_DIR` env var.

### End-to-end self-test (no MCP client needed)

```powershell
java -Xmx20g -Djava.awt.headless=true -jar target/jadx-headless-mcp-v2.jar `
     --selftest --apk <path>
```

Loads the APK, waits for the background index, then exercises class-source / string-constant / xref /
code-search tools and prints timings + peak heap.

## Tools

Session: `load_apk` `current_apk` `index_status` `clear_cache` ·
Enumerate: `get_all_classes` `get_package_tree` `get_methods_of_class` `get_fields_of_class` ·
Class: `get_class_source` `get_smali_of_class` `get_method_by_name` ·
Strings: `search_string_constants` `find_string_usages` `get_strings` ·
Xref: `get_xrefs_to_class` `get_xrefs_to_method` `get_xrefs_to_field` `get_xrefs_from_method`
`get_xrefs_from_class` `get_call_graph` `get_subclasses` ·
Resources: `get_android_manifest` `get_main_activity` `list_resource_files` `get_resource_file` ·
Rename (journaled): `rename_class` `rename_method` `rename_field` `rename_package` ·
Search: `search_in_code` `search_classes_by_keyword` `search_method_by_name`

Excluded by design (meaningless headless): `get_selected_text`, `fetch_current_class`, `debug_*`.

## Large-app behavior & memory (validated)

Measured on `-Xmx20g`, AWT-headless, via `--selftest`:

| App | classes (w/ inners) | load | xref graph (SQLite) | peak heap |
|---|---|---|---|---|
| DiDi 132 MB | 94,281 | 25 s | 950,877 sym / 3.7 M edges | **8.7 GB** |
| Douyin 295 MB | 493,376 | 125 s | 4,564,540 sym / **29.5 M edges** | **18.3 GB** |

- **Load + the full out-of-heap xref graph fit in 20 GB** on Douyin, and all tools (class source, string
  constants, xref, manifest, MCP over HTTP) return correctly and fast. This is the RE main line and it
  is complete.
- **`search_in_code` (full-text) builds incrementally.** Decompiling *every* class to feed the FTS index
  is a much heavier workload than loading — jadx accumulates per-class internal state, so on Douyin a
  single 20 GB pass covers ~25k of 319k top-level classes before the low-heap guard stops it (it's
  decompile order = **main package first**, so the app's own code is covered first; the tail is mostly
  third-party libraries). The index is marked `coverage_complete=false` and stays **READY/searchable**
  for the covered subset; **reloading the same APK resumes** — it skips the structure/usage phase and
  the already-indexed classes and extends coverage by another chunk. Repeat to grow toward full
  coverage, or raise `-Xmx` to cover more per pass. `index_status` reports `percent` / `coverage_complete`.
- The disk code cache and SQLite index are reused across restarts (same APK hash). A fully-complete
  index loads back instantly with no rebuild.
