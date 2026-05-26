# jadx-headless-mcp

**English** | [简体中文](README.zh-CN.md)

Headless **JADX** Android decompiler exposed as a **Model Context Protocol** (MCP) server, written in Rust for fast cold start and easy single-binary distribution. Lets Claude (or any MCP client) reverse-engineer `.apk`, `.dex`, `.aab`, `.xapk`, `.apkm` files — list classes, fetch decompiled Java source, cross-reference, rename symbols, search by keyword — without launching JADX-GUI.

> Inspired by [`blacktop/ida-mcp-rs`](https://github.com/blacktop/ida-mcp-rs); this is the JADX flavor.

## Architecture

```
┌──────────────┐  MCP stdio  ┌────────────────┐  loopback HTTP  ┌────────────────────┐
│  MCP client  │ ──────────▶ │   jadx-mcp     │ ──────────────▶ │  jadx-bridge.jar   │
│  (Claude...) │             │  (Rust binary) │   (port = OS)   │  (JVM + JADX core) │
└──────────────┘             └────────────────┘                 └────────────────────┘
                                     │                                    │
                                     │ bundles jadx-bridge.jar            │ JadxDecompiler
                                     ▼                                    ▼
                              one binary per OS                  reads .apk / .dex / .aab
```

- **Rust binary** (`jadx-mcp`): the MCP server. Embeds the jadx-bridge JAR via `include_bytes!`, extracts to a per-user cache on first run, spawns it as a child JVM, and proxies every MCP tool call to it over `127.0.0.1`.
- **Java sidecar** (`jadx-bridge.jar`): a thin Javalin HTTP server wrapping `jadx.api.JadxDecompiler`. Loaded once per APK; survives for the lifetime of the MCP server. Adapted from [`zinja-coder/jadx-ai-mcp`](https://github.com/zinja-coder/jadx-ai-mcp) with the GUI dependencies (`MainWindow`, `JadxWrapper`, debugger panels) stripped out.

A bundled JAR is large (~55 MB) but it makes the binary self-contained: `claude mcp add` works out of the box once `java` is on PATH.

## Requirements

- **Java 11 or newer** on `PATH` (or set `JAVA_HOME`). Check with `java -version`.
- Any OS: macOS (Intel / Apple Silicon), Linux (x86_64 / arm64), Windows x86_64.

That's it — no Python, no Maven, no JADX installation needed by end users.

## Install

### Option 1 — `npx` (zero install, recommended)

If you have Node.js 18+:

```bash
claude mcp add jadx -- npx -y jadx-headless-mcp@latest
```

That's it. One MCP entry serves any APK — call the `load_apk` tool in conversation to point at whatever file you want to analyze, switch by calling it again. No need to edit config or restart Claude when switching files.

The npm package is a thin shim that downloads the platform-specific binary (~52 MB) from the matching GitHub Release on first run and caches it locally. Subsequent runs skip the download.

> **Why `@latest`?** A bare `npx -y jadx-headless-mcp` reuses whatever version npx happened to download the first time and **does not check the registry for newer releases**. That's an npx semantics, not a bug in this package. Two ways to stay current:
>
> - `npx -y jadx-headless-mcp@latest` — npx re-checks the registry on every invocation (slight startup overhead, but always picks up new releases). **This is the recommended config.**
> - `npx -y jadx-headless-mcp@0.3.1` — pin to an exact version. More predictable; you control upgrades by editing the config.
>
> If you already registered the bare form and want to upgrade now, also clear the cache: `rm -rf "$LOCALAPPDATA/jadx-headless-mcp-npm"` (Windows) or `rm -rf ~/.cache/jadx-headless-mcp-npm` (Linux/macOS), then restart Claude Code.

### Option 2 — pre-built binary

Download the archive for your OS from the [latest release](https://github.com/Samael-Z/jadx-headless-mcp/releases/latest):

| OS / arch | archive |
|---|---|
| Linux x86_64 | `jadx-headless-mcp-linux-x86_64.tar.gz` |
| Linux arm64 | `jadx-headless-mcp-linux-arm64.tar.gz` |
| macOS Intel | `jadx-headless-mcp-macos-x86_64.tar.gz` |
| macOS Apple Silicon | `jadx-headless-mcp-macos-arm64.tar.gz` |
| Windows x86_64 | `jadx-headless-mcp-windows-x86_64.zip` |

Unpack and put the `jadx-mcp` binary somewhere on `PATH`.

### Option 3 — `cargo install`

```bash
# Requires Rust 1.80+, JDK 11+, and Maven on PATH (builds the sidecar JAR locally).
git clone https://github.com/Samael-Z/jadx-headless-mcp
cd jadx-headless-mcp
cd bridge && mvn -DskipTests package && cd ..
cargo install --path . --locked
```

## Register with an MCP client

### Claude Code CLI

```bash
claude mcp add jadx -- jadx-mcp
```

One config, any APK. Call the `load_apk` tool in conversation to point at a file; call it again with a different path to switch.

### Claude Desktop / Codex / generic stdio config

```json
{
  "mcpServers": {
    "jadx": {
      "command": "jadx-mcp"
    }
  }
}
```

If `java` isn't on PATH for the Desktop app's environment, set `JADX_MCP_JAVA` or pass `--java /full/path/to/java`.

### Working with the loaded APK

After registering, just say:

> Load `E:\\path\\to\\app.apk` and show me the package tree.

Claude calls `load_apk` (~30 seconds for JADX to decompile, larger APKs longer), then `get_package_tree`. Switch APKs at any time:

> Now switch to `D:\\samples\\other.apk` and find any class containing "encrypt".

### Useful flags / env

| Flag | Env | Purpose |
|---|---|---|
| `--apk PATH` | `JADX_MCP_APK` | **Optional.** APK / DEX / AAB / XAPK / APKM / JAR to autoload on startup. Equivalent to calling `load_apk` immediately. Mostly useful for single-APK workflows |
| `--java PATH` | `JADX_MCP_JAVA` | Override the Java executable. Default: `$JAVA_HOME/bin/java` then `PATH` |
| `--bridge-jar PATH` | `JADX_MCP_BRIDGE_JAR` | Use a different bridge JAR (e.g. a local build). Default: bundled |
| `--jvm-arg ARG` | `JADX_MCP_JVM_ARGS` | Extra JVM flags. Default: `-Xmx2g`. Repeat for multiple |
| `--bridge-startup-secs N` | — | How long to wait for the bridge to finish loading the APK. Default: 600 |
| `--bridge-host`, `--bridge-port` | — | Override the bridge's loopback bind. Default: `127.0.0.1:0` (OS-assigned) |
| — | `JADX_MCP_LOG` | `tracing` filter, e.g. `debug,reqwest=warn`. Default: `info` |

## Tools exposed

Twenty-seven tools across six categories. Run `tools/list` from your client for the full schema — descriptions below are abbreviated.

| Category | Tool |
|---|---|
| **Session** | `load_apk`, `current_apk` |
| **Classes** | `get_all_classes`, `get_class_source`, `get_methods_of_class`, `get_fields_of_class`, `get_smali_of_class`, `get_main_activity_class`, `get_main_application_classes_names`, `get_main_application_classes_code`, `get_package_tree`, `search_classes_by_keyword`, `get_cache_stats`, `clear_cache` |
| **Methods** | `get_method_by_name`, `search_method_by_name` |
| **Resources** | `get_android_manifest`, `get_strings`, `get_all_resource_file_names`, `get_resource_file` |
| **Xrefs** | `get_xrefs_to_class`, `get_xrefs_to_method`, `get_xrefs_to_field` |
| **Renames** | `rename_class`, `rename_method`, `rename_field`, `rename_package` |

A typical analysis flow:

```
load_apk { path: "/abs/path/to/app.apk" }         → point the server at an APK (~30s for jadx to load)
get_package_tree                                  → see APK structure, find app package
get_main_application_classes_names                → enumerate first-party classes
get_class_source { class_name: "..." }            → read decompiled source
get_xrefs_to_method { class_name, method_name }   → trace callers
search_classes_by_keyword { search_term, ... }    → free-text search across packages
load_apk { path: "/path/to/other.apk" }           → switch to a different APK in-session
```

Renames are in-memory only — visible to subsequent tool calls in the same session, not persisted to disk.

## Building from source

```bash
git clone https://github.com/Samael-Z/jadx-headless-mcp
cd jadx-headless-mcp

# 1. Java sidecar (needs JDK 11+ and Maven)
cd bridge && mvn -DskipTests package && cd ..

# 2. Rust binary (needs Rust 1.80+)
cargo build --release

# Binary is at target/release/jadx-mcp (.exe on Windows).
```

Smoke test against an APK in `apks/`:

```bash
./scripts/smoke_test.sh ./apks/your.apk
```

The build script (`build.rs`) finds the JAR in `bridge/target/jadx-bridge.jar` by default. To use a pre-built jar from elsewhere, set `BRIDGE_JAR_PATH` before building.

## What this is *not*

- It does **not** include the JADX-GUI debugger features (`debug_get_stack_frames`, etc.) — those require a live GUI and were dropped.
- It does **not** call `fetch_current_class` or `get_selected_text` — those make no sense without a UI; supply the FQN to `get_class_source` instead.
- Renames don't persist to a `.jobf` mappings file. Wiring up the `jadx-rename-mappings` plugin is on the roadmap.

## Acknowledgements

- [`skylot/jadx`](https://github.com/skylot/jadx) — the decompiler doing the actual work.
- [`zinja-coder/jadx-ai-mcp`](https://github.com/zinja-coder/jadx-ai-mcp) — the original GUI plugin whose routes this project ports to a headless context.
- [`blacktop/ida-mcp-rs`](https://github.com/blacktop/ida-mcp-rs) — the Rust-MCP-server-fronting-a-foreign-analyzer pattern this project borrows.

## License

Apache-2.0 (inherited from `skylot/jadx`).
