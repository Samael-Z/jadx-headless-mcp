# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

`jadx-handless-mcp` is a headless MCP server fronting the JADX Android decompiler. It is a single Rust binary that embeds and spawns a Java sidecar — there is no separate plugin to install. End users do `claude mcp add jadx -- jadx-mcp --apk app.apk` and that's it.

Two source trees in one repo, kept in lockstep:

- `bridge/` — Maven project. Builds `jadx-bridge.jar` (shaded, ~55MB). Wraps `jadx.api.JadxDecompiler` and exposes routes over Javalin on `127.0.0.1`. **Does not depend on `jadx-gui`** — every route uses headless `jadx.api.*` types. Adapted from `zinja-coder/jadx-ai-mcp` with `MainWindow`/`JadxWrapper`/`JDebuggerPanel` removed.
- `src/` — Cargo project. The `jadx-mcp` binary. Uses `rmcp` 1.7 over stdio. `build.rs` finds `bridge/target/jadx-bridge.jar` (or `BRIDGE_JAR_PATH` env), and `include_bytes!()` bakes it into the final executable.

Both must build together: `cd bridge && mvn package` first, then `cargo build` picks up the jar.

## Architecture deep cuts

**Subprocess handshake** (`src/bridge.rs`). The Rust parent spawns `java -jar jadx-bridge.jar --apk <path> --port 0`. The bridge prints `PORT=<n>\nREADY\n` to stdout as soon as Javalin is listening; the Rust side reads it line-by-line with `tokio::io::BufReader::lines()` up to `--bridge-startup-secs` (default 600). Everything else from the bridge goes to stderr, which gets forwarded into the Rust `tracing` pipeline. The bridge's `--port 0` asks the OS to pick a free port — the announced PORT line is the only way the parent finds out. If you ever break the handshake (e.g., add output to stdout before READY), the parent times out and reports `did not become READY within Ns`.

**Why `JadxBasePluginLoader` and not `JadxExternalPluginsLoader`** (`bridge/src/main/java/com/zin/jadxhandless/JadxBridge.java`). The latter scans `~/.jadx/plugins/` for user-installed JARs. If the user has the original `jadx-ai-mcp` (or any other GUI plugin) installed, ServiceLoader tries to resolve a class that imports `jadx.gui.ui.MainWindow` — which is not on the classpath — and `JadxDecompiler.load()` blows up with `NoClassDefFoundError`. `JadxBasePluginLoader` reads ONLY from the bundled classpath, where we control what's there. This is the kind of bug that's "fixed locally on the dev machine" forever; don't switch back without rethinking it.

**`#[tool_router]` + `#[tool_handler]`** (`src/server.rs`). rmcp 1.7's macros generate the JSON-RPC dispatch table from the function signatures. The `tool_router: ToolRouter<Self>` field on `JadxMcpServer` is **read by macro-generated code**, hence the `#[allow(dead_code)]` — without it, plain `cargo build` warns and `RUSTFLAGS=-D warnings` in CI would fail.

**Decompilation cache** (`bridge/.../util/DecompilationCache.java`). Per-class source compressed with `Deflate::BEST_SPEED`, stored in a `ConcurrentHashMap`. The cache is shared across `class-source`, `search-classes-by-keyword` with `search_in=code`, and `comment` searches — without it, a code search on a 100k-class APK re-decompiles every class on every search and times out. `/cache-clear` is exposed for memory-pressure scenarios.

## Common commands

```bash
# Build the Java sidecar (must run before cargo build)
cd bridge && mvn -DskipTests package && cd ..

# Build the Rust binary (release; debug is fine for iteration too)
cargo build --release

# End-to-end smoke test against an APK
./scripts/smoke_test.sh ./apks/your.apk      # Bash (Linux/macOS/Git Bash on Windows)
pwsh ./scripts/smoke_test.ps1 ./apks/your.apk # PowerShell on Windows

# Just probe the binary (prints version + bundled jar size, no JVM spawn)
./target/release/jadx-mcp probe

# Extract the bundled bridge jar to disk (for debugging)
./target/release/jadx-mcp extract-bridge --out /tmp/bridge.jar
```

For Rust-only iteration after the bridge JAR exists, you don't need to rerun `mvn package` — `build.rs` will pick up whatever's in `bridge/target/jadx-bridge.jar`.

If you're working ONLY on the bridge (changing route logic), run the bridge directly without going through Rust:

```bash
java -jar bridge/target/jadx-bridge.jar --apk ./apks/your.apk --port 18650
# In another terminal:
curl http://127.0.0.1:18650/health
curl http://127.0.0.1:18650/package-tree | head -c 500
```

## Adding a new MCP tool

Three files always change together:

1. `bridge/src/main/java/com/zin/jadxhandless/server/routes/<Category>Routes.java` — handler method
2. `bridge/src/main/java/com/zin/jadxhandless/server/BridgeServer.java` — register the route
3. `src/server.rs` — add the `#[tool]` async method that proxies via `self.get("/path", &query)` or `self.post(...)`
4. `src/tools.rs` — request struct if any args (derive `Deserialize, JsonSchema`)

The schemars-derived JSON schema is what the LLM sees, so write helpful `#[schemars(description = ...)]` on each arg.

## CI / Release

`.github/workflows/build.yml` has three jobs:

1. **bridge** (Ubuntu, runs once): `mvn package` → uploads `jadx-bridge.jar` as an artifact.
2. **build** (matrix, 5 platforms): downloads the jar artifact into `bridge/target/`, then `cargo build --release --target <triple>`. The `BRIDGE_JAR_PATH` env var points `build.rs` at the downloaded jar. **Runner labels to know about:**
   - `ubuntu-24.04-arm` — ARM64 Linux. **Not** `ubuntu-latest-arm64`, which is invalid and queues forever.
   - `macos-13` — Intel macOS, low availability on free tier; expect slow scheduling.
   - `macos-14` — Apple Silicon.
3. **release** (on `v*` tag): downloads all platform archives, runs `sha256sum`, publishes a GH release. Has `if: always()` so a partial matrix still ships binaries for the platforms that did build — if every job fails, the "bail-out" step exits 1.

To cut a release: merge to `main`, then `git tag -a vX.Y.Z -m "..."` and `git push origin vX.Y.Z`. Don't tag from `dev` — the release notes and the binaries live on `main`.

If you need to retrigger a release after fixing a CI bug (binaries didn't get attached), it's: `git tag -d vX.Y.Z && git push origin :refs/tags/vX.Y.Z && git tag -a vX.Y.Z ... && git push origin vX.Y.Z`.

## Gotchas

- **stdio MCP transport reserves stdout** for JSON-RPC frames. All Rust-side logging goes to stderr (via `tracing_subscriber::fmt().with_writer(std::io::stderr)`). The Java sidecar's logger (`slf4j-simple`) is hard-coded to stderr in `JadxBridge.main()` for the same reason. If you ever see "broken JSON-RPC frame" errors, something is writing to stdout that shouldn't be.
- **The bundled jar invalidates by size**, not by hash. `materialize_bridge_jar()` writes the bundled bytes to `<data-local>/jadx-handless-mcp/jadx-bridge-<size>.jar`. If you somehow ship a different jar with the exact same byte count, the old one won't get overwritten. Use a different version suffix or wipe the cache dir.
- **Renames are in-memory**. The `NodeRenamedByUser` events feed back into the in-process `JadxDecompiler`, but we don't write a `.jobf` mappings file — so a new server invocation starts with the original names. Persisting them is a roadmap item (needs `jadx-rename-mappings` plugin + an output project dir).
- **Java 11+ on PATH** is the only runtime requirement. We pick it up via JAVA_HOME first, then `which java`. If a user has only Java 8, the JVM will fail to load the bridge jar — give them `--java` to override, or document that they need a newer JDK.
