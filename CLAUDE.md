# CLAUDE.md

Guidance for Claude Code (and humans) working in this repo. Read this first — it encodes the build/run
commands, the architecture, and the non-obvious gotchas, so you can iterate fast on a fresh clone.

## What this is

`jadx-headless-mcp-v2` — a **single-process, headless [jadx](https://github.com/skylot/jadx) decompiler +
MCP server** for reverse-engineering large Android apps (Douyin-scale: 295 MB / 55 dex) under a **20 GB
heap**, with **every MCP tool call ≤ 60 s**. 32 RE tools over the official MCP Java SDK (Streamable HTTP).
See `README.md` for the user-facing docs and `openspec/changes/headless-jadx-mcp/` for the full spec.

## Commands

```bash
# Build the runnable fat jar (Java 17+, Maven 3.9+):
mvn -s settings.xml package           # → target/jadx-headless-mcp-v2.jar
# On a normal network you can drop "-s settings.xml" (it only mirrors to Aliyun to dodge a LOCAL proxy's
# TLS resets; CI uses Maven Central directly).

# Run (resident Streamable HTTP server; clients connect to http://127.0.0.1:<port>/mcp):
java -Xmx20g -Djava.awt.headless=true -jar target/jadx-headless-mcp-v2.jar --port 8760 [--apk <path>] [--deobf]

# End-to-end self-test (no MCP client; loads APK → index → exercises tools → prints peak heap):
java -Xmx20g -jar target/jadx-headless-mcp-v2.jar --selftest --apk <path>   # JADX_SELFTEST_WAIT_MS tunes wait
```

There is no unit-test suite; `--selftest` against a real APK is the integration test. CI
(`.github/workflows/build.yml`) builds the jar on every push and attaches it to the Release on `v*` tags.

## Architecture (one JVM, four packages under `com.zin.jadxheadless`)

- `Main` / `SelfTest` — entry point + headless self-test.
- `jadx/` — headless jadx core: `JadxService` (load/lifecycle/JadxArgs/rename), `DiskCodeCache` +
  `BoundedCodeCache` (bounded LRU over disk; replaces unbounded `InMemoryCodeCache`),
  `SqliteUsageInfoCache` (capture point for the out-of-heap xref export).
- `index/` — the SQLite brain: `Db` (WAL, schema, reader + dedicated writer connection),
  `SymbolGraph` (symbols+edges DAO, out-of-heap xref queries), `SqliteExportVisitor`
  (usage graph → edges), `CodeSearchIndex` (FTS5 trigram + ripgrep fallback), `IndexBuilder`
  (streaming, heap-bounded, **resumable** background build), `IndexStatus`.
- `mcp/` — `McpToolServer` (Jetty 12 + Streamable HTTP transport), `ToolRegistry` (the 32 tools),
  `Tools` (schema/result helpers).
- `util/` — `CacheLayout` (`<JADX_CACHE_DIR>/<apk-hash>/`), `DexId` (dex-stable ids), `ManifestUtil`,
  `Pagination`, `Json` (hand-rolled), `RenameStore` (TSV journal).

Per-APK state lives in `<JADX_CACHE_DIR>/<apk-hash>/` (disk code cache + `index.db`), reused across runs.

## Hard rules / conventions

- **Target Java 17** (`maven.compiler.release=17`; Jetty 12 + MCP SDK need it). jadx artifacts are Java 11
  bytecode, forward-compatible.
- **Depend on PUBLISHED jadx artifacts** (`io.github.skylot:jadx-*:1.5.5`), never the vendored jadx source
  tree. Upgrading jadx = bump `jadx.version` in `pom.xml`.
- **Keep tool calls ≤ 60 s and stay within `-Xmx20g`.** Tier-1 (structure/strings/xref) answers from
  SQLite instantly; the heavy full decompile is the *background* index build, never a per-call cost.
- xref/call-graph/subclass answers come from **SQLite (out of heap)**, not jadx's in-heap `getUseIn()`.

## Gotchas (hard-won — don't re-discover these)

- **Compile against the artifact, not the vendored source.** jadx **1.5.5**'s `IUsageInfoVisitor` has only
  **6 methods** (`visitClassDeps/ClassUsage/ClassUseInMethods/FieldsUsage/MethodsUsage/Complete`); the
  source tree elsewhere on disk is newer and has 3 more. CALLS edges are captured from `visitMethodsUsage`
  (caller→method) alone — both xref directions derive from that.
- **Do NOT add your own Jackson.** The MCP SDK bundles Jackson 3 (`tools.jackson.*` + jackson-annotations
  2.20). A second `com.fasterxml.jackson:jackson-databind` shadows it → `JsonProperty.isRequired()`
  `NoSuchMethodError`. Tool-result JSON is hand-rolled in `util/Json`; the rename journal uses TSV.
- **Shade must merge `META-INF/services`** (`ServicesResourceTransformer`) or jadx input plugins,
  kotlin-metadata, and the MCP JSON mapper won't be found in the fat jar.
- **Install the code cache via a `JadxPreparePass`** (`jadx.addCustomPass(...)`, set in `init(RootNode)`) —
  the `RootNode` only exists at load time. Cache key = `cls.getRawName()`.
- **Free decompiled IR with `ClassNode.unload()`, NOT `JavaClass.unload()`** — the latter calls
  `unloadFromCache()` which *evicts the disk code cache entry* you just wrote (breaks reuse + ripgrep).
- **Releasing heap for the FTS pass (D7):** after exporting usage to SQLite, clear the per-node `useIn`
  lists (`set(Use)?In(emptyList())`) — that's the spike's ~4.6 GB, not the cache object. Even so, mass
  decompile of a 319k-class app accumulates jadx-internal state, so one 20 GB pass covers tens of
  thousands of classes (main-package first) — hence the **resumable** build: reload the same APK to extend
  FTS coverage; xref is complete from load 1.
- **SQLite concurrency:** one dedicated **writer** connection (`Db.openWriter()`, WAL) for the background
  build; all reads serialize on `db.readLock()`. Don't share one connection across threads.

## Spec-driven workflow (OpenSpec)

The spec lives in `openspec/changes/headless-jadx-mcp/` (proposal/design/specs/tasks). To iterate:
`openspec list` / `openspec status --change headless-jadx-mcp`, or the `/opsx:*` skills. The change is
30/30 complete; archive it (`/opsx:archive`) or open a new change for the next feature.
