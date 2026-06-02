#!/usr/bin/env bash
# Bash smoke test: pipe MCP frames into `jadx-mcp serve`, capture responses.
#
#   ./scripts/smoke_test.sh /path/to/app.apk

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <apk>" >&2
  exit 2
fi

apk="$1"
root="$(cd "$(dirname "$0")"/.. && pwd)"
exe="$root/target/release/jadx-mcp"
[[ -x "$exe" ]] || exe="$exe.exe"
[[ -x "$exe" ]] || { echo "binary not built: $exe" >&2; exit 1; }

# Compose request stream
req=$(cat <<'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"smoke-test","version":"0.0.1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_android_manifest","arguments":{}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"index_status","arguments":{}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"search_string_constants","arguments":{"query":"http","count":3}}}
EOF
)

stdout_log="$(mktemp -t smoke_stdout.XXXX)"
stderr_log="$(mktemp -t smoke_stderr.XXXX)"
echo "stdout: $stdout_log"
echo "stderr: $stderr_log"

# Open stdin as a coproc so we can keep it open for an extra second after the last frame.
( echo "$req"; sleep 60 ) | "$exe" serve --apk "$apk" --bridge-startup-secs 600 \
    > "$stdout_log" 2> "$stderr_log" &
pid=$!

trap 'kill -TERM "$pid" 2>/dev/null || true' EXIT

# Wait up to 5 minutes for all response lines (init reply + tools/list + 3 tool calls).
deadline=$(( $(date +%s) + 300 ))
while [[ $(date +%s) -lt "$deadline" ]]; do
  lines=$(wc -l < "$stdout_log" 2>/dev/null || echo 0)
  if [[ "$lines" -ge 5 ]]; then break; fi
  sleep 2
done

echo "--- stdout (first 6 lines) ---"
head -n 6 "$stdout_log"
echo "--- stderr tail ---"
tail -n 30 "$stderr_log"
echo "---"
echo "lines on stdout: $(wc -l < "$stdout_log")"
