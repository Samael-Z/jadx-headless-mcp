# jadx-headless-mcp

[![build](https://github.com/Samael-Z/jadx-headless-mcp/actions/workflows/build.yml/badge.svg)](https://github.com/Samael-Z/jadx-headless-mcp/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/Samael-Z/jadx-headless-mcp?sort=semver)](https://github.com/Samael-Z/jadx-headless-mcp/releases)
[![Java](https://img.shields.io/badge/Java-17%2B-blue)](#requirements)
[![license](https://img.shields.io/badge/license-Apache--2.0-green)](#license)

**English** · [简体中文](README.zh-CN.md)

A **single-process, headless [jadx](https://github.com/skylot/jadx) decompiler + MCP server** built for
**reverse-engineering large Android apps** (e.g. Douyin: 295 MB / 55 dex) under a **20 GB heap**, with
**every MCP tool call bounded to ~60 s**. It exposes 32 RE tools to LLM clients over the official
**Model Context Protocol** — over **stdio** (client-launched) or **Streamable HTTP**.

> This is the **v1.0 Java rewrite**. It supersedes the earlier Rust-bridge implementation (the `v0.x`
> tags / `dev` branch), removing the cross-language bridge and the GUI dependency entirely.

---

## Table of contents

- [Why](#why)
- [Highlights](#highlights)
- [Architecture](#architecture)
- [Project structure](#project-structure)
- [Requirements](#requirements)
- [Install / Build](#install--build)
- [Usage](#usage)
- [Connecting an MCP client](#connecting-an-mcp-client)
- [MCP tools](#mcp-tools)
- [Large-app behavior & memory](#large-app-behavior--memory)
- [Changelog](#changelog)
- [License & credits](#license--credits)

---

## Why

Existing options didn't fit reverse-engineering (RE) needs:

- **GUI-embedded plugins** (e.g. jadx-ai-mcp) bail out when there is no GUI context — they **cannot run
  headless**.
- The **earlier `jadx-headless-mcp`** located information by *grep-ing smali text*, which forced
  decompiling every class (**extremely slow**), and on huge APKs like Douyin it **failed to load fully**,
  making the MCP effectively useless.

This project is **rebuilt for RE**: load any APK (including Douyin), keep all tools fast and stable,
**≤ 60 s per tool call** (loading may take longer, once), within **20 GB** of heap.

## Highlights

- 🧩 **Single JVM, no bridge, no Python.** jadx-core and the MCP server run in one process; MCP speaks
  **stdio** (client-launched, `--stdio`) or **Streamable HTTP** (embedded Jetty on `127.0.0.1`; not the
  deprecated SSE).
- 🧠 **Out-of-heap xref.** A custom `IUsageInfoCache` exports jadx's usage graph to **SQLite**
  (symbols + edges); `get_xrefs_*` / `get_call_graph` answer from SQL and never touch jadx's in-heap
  `getUseIn()`. On Douyin that's **4.5 M symbols / 29.5 M edges** off the heap.
- 🔎 **Analysis-value code search.** `search_in_code` uses an **SQLite FTS5 trigram** index over
  decompiled text (with a **ripgrep** fallback for full regex). Results are one-row-per-class,
  **default-filter the standard library**, and rank **app > obfuscated > third-party**; the index itself
  is selective (skip stdlib by default, `--index-all` for everything).
- 💾 **Bounded + disk code cache.** A bounded in-heap LRU over a disk cache replaces jadx's unbounded
  `InMemoryCodeCache`, so a long session never grows the heap without bound. Reused across restarts.
- 🏷️ **dex-stable keys.** The index is keyed by raw DEX descriptors, so toggling naming options or
  renaming never invalidates the structural index — only the display-name layer.
- 🪜 **Tiered, progressive availability.** `load_apk` returns as soon as the model loads; the index then
  builds by **analysis value** — xref graph → manifest **entry** classes → app **main** package → the rest —
  so the most relevant code is searchable within seconds, long before full coverage. `index_status` reports
  `current_tier` / `xref_ready` / `main_ready` and `decompiled_classes` vs `indexed_classes`. While building,
  `search_in_code` transparently covers **every already-decompiled class** (FTS ∪ ripgrep over the decompiled
  sources), so partial results are never missing a decompiled class.
- 🔁 **Resumable, streaming build.** The index builds in the background with progress
  (`index_status`); a partial build stays searchable and **resumes/extends on the next load** (tier-readiness
  flags are restored from on-disk products).
- 📛 **Readable names by default.** `useSourceNameAsClassNameAlias=ALWAYS` + kotlin-metadata; `deobf`
  **off** by default (it degrades package names on heavily-obfuscated apps).

## Architecture

One JVM, three layers:

```
 LLM client ──Streamable HTTP──▶  MCP server (official Java SDK)  ──in-process──▶  jadx-core
                (Jetty 12, /mcp)      32 tools → JadxService                         (decompiler)
                                              │
                                              ├─▶ SQLite symbol+edge graph   (out-of-heap xref, D7)
                                              ├─▶ SQLite FTS5 trigram        (search_in_code, D6)
                                              └─▶ bounded LRU → disk code cache (lazy decompile, D2)
```

Everything derived from one APK lives under `<JADX_CACHE_DIR>/<apk-hash>/` (disk code cache +
`index.db`), reused across restarts when the APK hash matches.

**Key design decisions**

| # | Decision |
|---|---|
| D1 | Single-process Java; official MCP Java SDK + **Streamable HTTP**; localhost, no auth |
| D2 | Resident + lazy decompile; **bounded LRU over a disk code cache** (no unbounded `InMemoryCodeCache`) |
| D4 | Naming = source-name `ALWAYS` + kotlin-metadata; **`deobf` off** by default |
| D5 | Cache/index keyed by **dex-stable identity**; display names are a metadata layer |
| D6 | One local **SQLite**: symbol/edge graph + FTS5 `trigram`, fed by jadx; **ripgrep** regex fallback |
| D7 | **xref/usage exported to SQLite (out of heap)** via `IUsageInfoCache` + `visitUsageData` |

## Project structure

```
jadx-headless-mcp/                    (main branch = the Java rewrite)
├── pom.xml                           Maven build; shaded fat jar, Java 17 target
├── settings.xml                      Maven mirror (local-proxy workaround; NOT used by CI)
├── .github/workflows/build.yml       CI: build the jar, upload artifact, release on v* tags
└── src/main/
    ├── resources/simplelogger.properties   logs → stderr (stdout stays clean)
    └── java/com/zin/jadxheadless/
        ├── Main.java                 entry point + CLI args (--apk/--port/--deobf/--selftest)
        ├── SelfTest.java             headless end-to-end self-test (--selftest)
        ├── jadx/                     ── headless jadx core (headless-jadx-server) ──
        │   ├── JadxService.java          load/lifecycle, JadxArgs, class lookup, rename journal
        │   ├── DiskCodeCache.java        disk-backed ICodeCache (source-only, cross-restart reuse)
        │   ├── BoundedCodeCache.java     bounded in-heap LRU in front of the disk cache
        │   └── SqliteUsageInfoCache.java custom IUsageInfoCache — capture point for the xref export
        ├── index/                    ── unified SQLite index (code-search-index) ──
        │   ├── Db.java                   SQLite connection, WAL, schema, reader/writer split
        │   ├── SymbolGraph.java          symbols + edges DAO; out-of-heap xref/call-graph queries
        │   ├── SqliteExportVisitor.java  IUsageInfoVisitor → edge table (usage → SQLite)
        │   ├── CodeSearchIndex.java      FTS5 trigram + const-string search + ripgrep fallback
        │   ├── IndexBuilder.java         streaming, heap-bounded, resumable background build
        │   └── IndexStatus.java          progress/coverage state (index_status)
        ├── mcp/                       ── MCP tool layer (mcp-re-toolset) ──
        │   ├── McpToolServer.java        Jetty + Streamable HTTP transport + MCP sync server
        │   ├── ToolRegistry.java         declares the 32 tools over JadxService
        │   └── Tools.java                tool-spec helper (schema + result/error wrapping)
        └── util/                      ── shared utilities ──
            ├── CacheLayout.java          per-APK cache dir (<JADX_CACHE_DIR>/<apk-hash>/)
            ├── DexId.java                dex-stable identity for class/method/field (D5)
            ├── ManifestUtil.java         AndroidManifest: package / launcher activity / raw XML
            ├── Pagination.java           offset/limit paging for enumerations
            ├── Json.java                 dependency-free JSON writer for tool results
            └── RenameStore.java          rename journal (TSV; replayed on reload)
```

## Requirements

- **JDK 17+** (Jetty 12 and the MCP SDK require 17; jadx artifacts are Java 11 bytecode, forward-compatible).
- **Maven 3.9+** to build from source.
- Heap: run with `-Xmx20g` for large apps (smaller apps need less). `ripgrep` (`rg`) on `PATH` is
  **strongly recommended** (it powers the regex fallback **and**, while the index is still building, the
  cross-phase scan that lets `search_in_code` cover decompiled-but-not-yet-indexed classes; without it,
  in-progress code search is limited to the FTS-indexed subset until `coverage_complete`).

## Install / Build

### Option A — download a release

Grab `jadx-headless-mcp-v2.jar` from the [Releases](https://github.com/Samael-Z/jadx-headless-mcp/releases)
page (attached to each `v*` tag by CI).

### Option B — build from source

```bash
git clone https://github.com/Samael-Z/jadx-headless-mcp.git
cd jadx-headless-mcp
mvn -s settings.xml package          # → target/jadx-headless-mcp-v2.jar  (~68 MB, self-contained)
```

`settings.xml` routes Maven through a mirror to work around a **local** proxy's TLS resets; on a normal
network (and in CI) you can drop `-s settings.xml` and use Maven Central directly. The build shades all
dependencies (jadx-all, Jetty, MCP SDK, sqlite-jdbc) and merges `META-INF/services` so every ServiceLoader
(jadx input plugins, kotlin-metadata, the MCP JSON mapper) resolves.

## Usage

Two transports. **stdio** is simplest when the client launches the process (e.g. Claude Code); **HTTP**
(the default) is a resident server you start yourself and point clients at.

```bash
# HTTP (Streamable HTTP) — a resident server you start; clients connect to the URL
java -Xmx20g -Djava.awt.headless=true -jar jadx-headless-mcp-v2.jar \
     --host 127.0.0.1 --port 8650 [--apk <path-to-apk>] [--deobf]

# stdio — the client launches & owns the process; load a target APK at runtime via load_apk
java -Xmx20g -Djava.awt.headless=true -jar jadx-headless-mcp-v2.jar --stdio
```

| Flag | Default | Meaning |
|---|---|---|
| `--host` | `127.0.0.1` | Bind address (keep on localhost — **no auth**; use an SSH tunnel for remote) |
| `--port` | `8650` | HTTP port; MCP endpoint is `http://<host>:<port>/mcp` |
| `--apk` | — | Optional APK/DEX/AAB/XAPK/APKM/JAR to load on startup (or call `load_apk` later) |
| `--deobf` | off | Enable jadx deobfuscation (off is better for heavily-obfuscated apps) |
| `--stdio` | — | Speak MCP over stdin/stdout instead of HTTP — for clients that launch the process (e.g. Claude Code `"type": "stdio"`). No port; logs stay on stderr. Load a target APK at runtime via `load_apk`. |
| `--selftest` | — | Run the headless end-to-end self-test against `--apk` and exit |
| `--index-include <pkg,…>` | — | Force these package prefixes into the code-search index (even if they'd be classed as stdlib) and rank them as app code |
| `--index-exclude <pkg,…>` | — | Skip these package prefixes from the code-search index |
| `--index-all` | off | Index **every** class incl. standard library — overrides the default analysis-value scope (see below) |
| `--no-index-third-party` | off | Also skip **T3** named third-party libraries (index only T1 app + T2 obfuscated) — a general index-reduction lever for third-party-heavy apps; T4 stdlib is skipped regardless |
| `--bench-decompile` | — | Spike mode: measure the pure full-quality decompile floor (3 passes: decompile-only / +disk / +sharded-FTS) and exit. `--limit N` (default 20000) / `--threads M` (default cores). |

- Cache root defaults to `E:\JADX_CACHE_DIR`; override with the `JADX_CACHE_DIR` env var or
  `-Djadx.cache.dir=...`.
- Self-test (no MCP client needed):
  `java -Xmx20g -jar jadx-headless-mcp-v2.jar --selftest --apk app.apk`
  (`JADX_SELFTEST_WAIT_MS` controls how long it waits for the background index).

## Connecting an MCP client

**stdio** — the client launches the process (recommended for Claude Code; load the target APK at runtime
via `load_apk`):

```jsonc
{
  "mcpServers": {
    "jadx-headless-mcp-v2": {
      "type": "stdio",
      "command": "java",
      "args": ["-Xmx20g", "-Djava.awt.headless=true", "-jar",
               "/abs/path/to/jadx-headless-mcp-v2.jar", "--stdio"]
    }
  }
}
```

**Streamable HTTP** — start the server yourself (see Usage), then point the client at the URL (it does
not spawn the process):

```jsonc
{
  "mcpServers": {
    "jadx-headless": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:8650/mcp"
    }
  }
}
```

A typical session: `load_apk` → `get_package_tree` → drill in with `search_string_constants` /
`get_class_source` / `get_xrefs_to_*`. Poll `index_status` for `search_in_code` readiness.

## MCP tools

32 tools, tiered by cost. **Tier-1** is instant (structure / string pool / SQLite xref); **Tier-2** is a
single lazy decompile; **Tier-3** is index-backed/background. GUI-only tools (`get_selected_text`,
`fetch_current_class`) and the smali `debug_*` group are **intentionally excluded** (meaningless headless).

### Session
| Tool | Description |
|---|---|
| `load_apk` | Load/switch APK; starts the background index build |
| `current_apk` | Loaded APK + class count + index status |
| `index_status` | Tiered build progress: `current_tier` / `xref_ready` / `main_ready` / `decompiled_classes` vs `indexed_classes` / `percent` / `coverage_complete` / symbols / edges |
| `clear_cache` | Clear in-heap caches (disk index retained) |

### Enumeration (Tier-1)
| Tool | Description |
|---|---|
| `get_all_classes` | All class FQNs (paginated) |
| `get_package_tree` | Packages with class counts |
| `get_methods_of_class` | Methods (name + descriptor + return type) |
| `get_fields_of_class` | Fields (name + type) |

### Single class (Tier-2)
| Tool | Description |
|---|---|
| `get_class_source` | Decompiled Java (disk-cache hit → instant) |
| `get_smali_of_class` | Smali bytecode |
| `get_method_by_name` | Locate method(s) + best-effort code slice |

### Strings — RE main line (Tier-1)
| Tool | Description |
|---|---|
| `search_string_constants` | Substring search over const-string literals, **aggregated by class**; stdlib-filtered + ranked (`include_libs` to keep stdlib) |
| `find_string_usages` | Classes containing an **exact** string literal (whole-literal; for substring use `search_string_constants`); stdlib-filtered (`include_libs`) |
| `get_strings` | Android `strings.xml` resources |

### Xref — out-of-heap SQLite (Tier-1)
| Tool | Description |
|---|---|
| `get_xrefs_to_class` | Classes referencing a class |
| `get_xrefs_to_method` | Callers of a method |
| `get_xrefs_from_method` | Callees of a method |
| `get_xrefs_to_field` | Methods reading/writing a field |
| `get_xrefs_from_class` | Classes a class calls into |
| `get_call_graph` | Direct callees of a class |
| `get_subclasses` | Direct subclasses / interface implementors |

### Resources
| Tool | Description |
|---|---|
| `get_android_manifest` | Raw AndroidManifest.xml |
| `get_main_activity` | Launcher activity FQN |
| `list_resource_files` | Resource file names (paginated) |
| `get_resource_file` | Text content of a resource |

### Rename (journaled, replayed on reload)
| Tool | Description |
|---|---|
| `rename_class` / `rename_method` / `rename_field` / `rename_package` | Persisted renames |

### Code & name search
| Tool | Description |
|---|---|
| `search_in_code` | Full-text (FTS5 trigram; regex via ripgrep) — Tier-3, index-backed. One row per class, **stdlib-filtered + ranked app > obfuscated > third-party**; `scope` (package subtree) + `include_libs` params |
| `search_classes_by_keyword` | Class FQNs containing a keyword (instant) |
| `search_method_by_name` | Methods by name substring (model scan, capped) |

## Large-app behavior & memory

Measured with `-Xmx20g`, AWT-headless, via `--selftest`:

| App | classes (w/ inners) | load | xref graph (SQLite) | peak heap |
|---|---|---|---|---|
| DiDi 132 MB | 94,281 | 25 s | 950,877 sym / 3.7 M edges | **8.7 GB** |
| Douyin 295 MB | 493,376 | 125 s | 4,564,540 sym / **29.5 M edges** | **18.3–19.1 GB** |

- **Load + the full out-of-heap xref graph fit in 20 GB on Douyin**, and class source / strings / xref /
  manifest / MCP-over-HTTP all return correctly and fast. This RE main line is complete from the first load.
- **`search_in_code` is analysis-value-driven.** By default it decompiles + indexes only code with RE
  value — the app's own code and obfuscated packages (T1/T2) — and **skips the standard library**
  (`android`/`androidx`/`java`/`kotlin`/`com.google`/…; T4). Results are **one row per class**,
  **default-filter stdlib hits**, and are **ranked app > obfuscated > third-party** (`limit` applied after
  ranking). Use `scope` to restrict to a package subtree, `include_libs=true` to keep stdlib, or
  `--index-all` to index everything.
- **Memory-bounded streaming build.** Each class is decompiled then released in chunks (parallel
  decompile → barrier → serial release): `unload()` frees the decompiled IR (the dominant per-class
  accumulation) and `ConstStorage.removeForClass` frees the const-storage entries `unload()` leaves
  behind, so peak heap stays bounded. A **single 20 GB pass covers the full in-scope set** (Douyin:
  312,498 classes, peak 19.3 GB); a low-heap backstop still resumes-not-OOMs for `--index-all` / larger
  apps (`coverage_complete=false` + reload to extend). The disk code cache + SQLite index are **reused
  across restarts** (same APK hash), so a complete index loads back instantly with no rebuild.
- **Multi-core build pipeline (v1.3.0).** The decompile threads only decompile + harvest string literals
  and hand the text to a bounded queue; **M FTS shard-writer threads** (`fts/shard-<i>.db`, routed by
  `clsIdx % M`) tokenize + insert in parallel — removing the single-writer FTS bottleneck. Graph indexes
  are built **after** the bulk structure+usage load (not per-row). Tunables: `JADX_INDEX_SHARDS` (M,
  default 8), `JADX_INDEX_THREADS` (decompile parallelism, default cores), `JADX_INDEX_CHUNK` (default
  4000), `JADX_INDEX_QUEUE_MB` (queue byte cap, default 64), `JADX_INDEX_OVERLAP=1` (run usage-export
  concurrently with the code phase — off by default; raises peak heap toward the 20 GB ceiling).
  Decompilation quality is **unchanged** (full AUTO/RESTRUCTURE + source-name + kotlin-metadata): the
  speedup is purely parallelism/decoupling, never a quality downgrade, so `get_class_source` output is
  byte-identical. **Honest ceiling:** the build can only approach the pure-decompile CPU floor — measured
  at **~228 classes/s on 22 cores** at full quality (`--bench-decompile`), i.e. ~23 min for Douyin's 312k
  classes. Going faster needs more cores (linear) or fewer classes (`--no-index-third-party`), not a
  software trick.
- **Tiered progressive availability (v1.4.0).** Since the full build is an irreducible ~CPU-bound task,
  the build is ordered by **analysis value** instead of just "finish faster": xref graph (independent of
  decompile, ready first) → manifest **entry** classes → app **main** package → the rest. Each tier flips
  a readiness flag (`xref_ready` / `entry_ready` / `main_ready`, with `current_tier`) so the most relevant
  code is searchable within seconds. While building, `search_in_code` unions FTS with a ripgrep scan of the
  decompiled `.java` so it covers **every already-decompiled class** (not just the FTS-indexed subset);
  once `coverage_complete` it uses FTS alone (sub-second). Tool signatures/semantics are unchanged — a
  caller need not know the build phase; an `index_note` reports current coverage. This does **not** shorten
  total build time — it changes the *availability curve*.

## Changelog

| Version | Date | Notes |
|---|---|---|
| **v1.4.0** | 2026-06-09 | **Tiered progressive availability.** `load_apk` returns at model-load; the background index now builds by **analysis-value tier** — xref → manifest **entry** classes → app **main** package → rest — so high-value code is searchable within seconds instead of only at 100%. `index_status` gains `current_tier` / `xref_ready` / `entry_ready` / `main_ready` and `decompiled_classes` vs `indexed_classes`. While building, `search_in_code` transparently covers **every already-decompiled class** (FTS ∪ ripgrep over the decompiled sources) and falls back to the FTS-indexed subset (with a note) if `rg` is absent; after `coverage_complete` it uses FTS alone. Tier-readiness is restored from on-disk products on reload. **Total build time is unchanged** (still ≤20 GB, full-quality RESTRUCTURE, resume across sessions) — only the *availability curve* improves; decompile parallelism is deliberately untouched. |
| **v1.3.0** | 2026-06-09 | **Fast multi-core index build.** The build is now a decoupled pipeline: decompile threads only decompile + harvest literals → bounded queue → **M parallel FTS shard-writers** (`fts/shard-<i>.db`, routed by `clsIdx % M`), removing the single-SQLite-writer trigram bottleneck. `search_in_code` / string search fan out across shards + merge (result semantics unchanged). Graph indexes are built **after** the bulk load (not per-row). New: `--no-index-third-party` (skip T3); env knobs `JADX_INDEX_SHARDS`/`THREADS`/`QUEUE_MB`/`OVERLAP`. Schema bumped to **v2** (old indexes auto-rebuild; decompiled code cache kept). **Decompilation quality is unchanged** — pure parallelism, no `simple`/`fallback` downgrade. A `--bench-decompile` spike fixes the honest ceiling: full-quality decompile floors at **~228 cls/s @ 22 cores** (~23 min for Douyin's 312k), so the build approaches that floor rather than an arbitrary target. |
| **v1.2.0** | 2026-06-08 | **Analysis-value code search.** `search_in_code` / `search_string_constants` / `find_string_usages` now **aggregate one row per class**, **filter standard-library hits by default** (android/androidx/java/kotlin/google/…), and **rank app > obfuscated > third-party** (`limit` after ranking); `search_in_code` gains `scope` + `include_libs`. The background index is now **selective** (decompile only T1 app + T2 obfuscated, skip T4 stdlib; `--index-include`/`--index-exclude`/`--index-all` to adjust). Root-cause fix for the partial-coverage limit: phase-3 releases each class with **`unload()` (frees the decompiled IR) + `ConstStorage.removeForClass` (frees the const accumulation)** in parallel-decompile→barrier→serial-release chunks — so a single 20 GB pass now covers the **full in-scope set** (Douyin: 312,498 classes, 100%, peak 19.3 GB), up from the previous ~14%. |
| **v1.1.0** | 2026-06-08 | **stdio transport** (`--stdio`): the MCP client (e.g. Claude Code) launches & owns the process; the target APK is loaded at runtime via `load_apk` — HTTP retained as the default. **Fix:** `index_status` now backfills `symbols`/`edges`/`const_strings` when a complete index is reused from disk (it previously reported 0, though the data was always present). |
| **v1.1.1** | 2026-06-08 | **String-tool cleanup:** `find_string_usages` is now exact-match only (whole-literal); substring search belongs to `search_string_constants` (FTS-accelerated). Drops the overlapping `contains` option and its slow `LIKE` full scan — the two string tools are now orthogonal. |
| **v1.0.1** | 2026-06-08 | Version alignment; **CI/CD** (GitHub Actions: build the fat jar, upload artifact, attach jar to the Release on `v*` tags). |
| **v1.0.0** | 2026-06-08 | **Initial release of the single-process Java rewrite.** Out-of-heap SQLite xref, FTS5 trigram code search, bounded/disk code cache, resumable index, official MCP SDK over Streamable HTTP. Validated on DiDi + Douyin within `-Xmx20g`. |
| `v0.x` | — | Legacy Rust-bridge implementation (recoverable via the `dev` branch and `v0.x` tags). Superseded by v1.0. |

## License & credits

- Licensed under the **Apache License 2.0**, consistent with upstream jadx.
- Built on [skylot/jadx](https://github.com/skylot/jadx) (the decompiler engine, compiled against the
  published `io.github.skylot:jadx-*` artifacts) and the official
  [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk).
- A from-scratch Java reimagining of the earlier Rust-bridge `jadx-headless-mcp`.
