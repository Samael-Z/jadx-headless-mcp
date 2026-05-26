# jadx-headless-mcp

Headless **JADX** Android decompiler exposed as a **Model Context Protocol** (MCP) server.

This npm package is a thin Node.js shim around the [main project](https://github.com/Samael-Z/jadx-headless-mcp). On first run it downloads the platform-specific native binary (~52 MB) from the matching GitHub Release into your user cache, then exec()s it.

## Quick start

```bash
npx -y jadx-headless-mcp@latest
```

Or wire it into Claude Code / Claude Desktop / any stdio MCP client. **One config serves any APK** — switch files by calling the `load_apk` MCP tool in conversation, no restart required:

```jsonc
{
  "mcpServers": {
    "jadx": {
      "command": "npx",
      "args": ["-y", "jadx-headless-mcp@latest"]
    }
  }
}
```

Claude Code one-liner:

```bash
claude mcp add jadx -- npx -y jadx-headless-mcp@latest
```

Then in conversation: *"Load `/path/to/app.apk` and show me the package tree."* Claude calls `load_apk` → `get_package_tree`. To switch APKs later just say so — `load_apk` accepts a different path at any time.

> ⚠️ **Pin `@latest` (or an explicit version) — bare `npx jadx-headless-mcp` reuses npx's cached version forever.** npx does not re-check the registry on subsequent runs when no version tag is specified, so without `@latest` you'd be stuck on whichever version was first downloaded. If you've already installed the bare form and want to upgrade, clear the wrapper cache (`%LOCALAPPDATA%\jadx-headless-mcp-npm` on Windows, `~/.cache/jadx-headless-mcp-npm` on Linux/macOS) and restart your MCP client.

## Requirements

- **Node.js 18+**
- **Java 11+** on PATH (or set `JAVA_HOME`) — the underlying binary spawns a JVM sidecar to drive JADX.
- **`tar` on PATH** — Windows 10 1803+, macOS, Linux all have it built in.

## Supported platforms

| OS | Arch | Release asset |
|---|---|---|
| Linux | x86_64 | `jadx-headless-mcp-linux-x86_64.tar.gz` |
| Linux | arm64 | `jadx-headless-mcp-linux-arm64.tar.gz` |
| macOS | Intel | `jadx-headless-mcp-macos-x86_64.tar.gz` |
| macOS | Apple Silicon | `jadx-headless-mcp-macos-arm64.tar.gz` |
| Windows | x86_64 | `jadx-headless-mcp-windows-x86_64.zip` |

## What the binary actually does

It speaks MCP over stdio, exposing 24 tools across 5 categories (classes / methods / resources / xrefs / renames). See the [main README](https://github.com/Samael-Z/jadx-headless-mcp#readme) for the tool catalog, architecture diagram, and CLI flags.

## Cache location

The downloaded binary lives at:

- Linux/macOS: `~/.cache/jadx-headless-mcp-npm/v<version>/`
- Windows: `%LOCALAPPDATA%\jadx-headless-mcp-npm\v<version>\`

Delete the version directory to force a re-download.

## License

Apache-2.0 (inherited from [`skylot/jadx`](https://github.com/skylot/jadx)).
