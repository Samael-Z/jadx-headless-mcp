"""One-shot helper: add (or update) the `jadx-headless-mcp` entry in ~/.claude.json.

Run once after `npm publish` is live and the package can be `npx`-installed.
Safe to re-run: idempotent overwrite of the named entry only.
"""

from __future__ import annotations

import json
import os
import shutil
import sys
import time
from pathlib import Path

CONFIG = Path.home() / ".claude.json"
SERVER_NAME = "jadx-headless-mcp"

# v0.3.0+ supports runtime `load_apk` tool calls, so no --apk in the launch
# args. One MCP entry serves any APK. To switch files: in conversation, say
# "load <path>" and Claude will call the load_apk tool.
ENTRY = {
    "command": "npx",
    "args": [
        "-y",
        "jadx-headless-mcp",
    ],
}


def main() -> int:
    if not CONFIG.exists():
        print(f"!! {CONFIG} does not exist", file=sys.stderr)
        return 1

    backup = CONFIG.with_suffix(CONFIG.suffix + f".bak.{int(time.time())}")
    shutil.copy2(CONFIG, backup)
    print(f"backup: {backup}")

    with CONFIG.open(encoding="utf-8") as f:
        cfg = json.load(f)

    servers = cfg.setdefault("mcpServers", {})
    existing = servers.get(SERVER_NAME)
    if existing == ENTRY:
        print(f"{SERVER_NAME} already configured identically — nothing to do.")
        return 0

    servers[SERVER_NAME] = ENTRY

    with CONFIG.open("w", encoding="utf-8", newline="\n") as f:
        json.dump(cfg, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print(f"wrote {SERVER_NAME} entry:")
    print(json.dumps(ENTRY, indent=2, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
