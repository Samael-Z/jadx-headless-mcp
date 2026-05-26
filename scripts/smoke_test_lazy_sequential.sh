#!/usr/bin/env bash
# Sequential lazy-load test using a delayed-pipe approach (no fifo).
# Each frame is emitted with a sleep between, simulating how an LLM
# pipelines tool calls in practice.
#
# usage:  ./scripts/smoke_test_lazy_sequential.sh /path/to/app.apk

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <apk>" >&2
  exit 2
fi

apk_raw="$1"
apk=$(cygpath -w "$apk_raw" 2>/dev/null || readlink -f "$apk_raw")
root="$(cd "$(dirname "$0")"/.. && pwd)"
exe="$root/target/release/jadx-mcp"
[[ -x "$exe" ]] || exe="$exe.exe"

apk_json=$(printf '%s' "$apk" | sed 's/\\/\\\\/g')

stdout_log="/tmp/lazy_seq_stdout.$$"
stderr_log="/tmp/lazy_seq_stderr.$$"

# Each frame followed by enough sleep for the server to process.
# 35s after load_apk because jadx needs ~25s for this APK + buffer.
{
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"seq","version":"0.0.1"}}}'
  sleep 2
  echo '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
  sleep 1
  echo '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"current_apk","arguments":{}}}'
  sleep 2
  echo "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"load_apk\",\"arguments\":{\"path\":\"${apk_json}\"}}}"
  sleep 45
  echo '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"current_apk","arguments":{}}}'
  sleep 2
  echo '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_package_tree","arguments":{}}}'
  sleep 3
} | "$exe" serve --bridge-startup-secs 600 > "$stdout_log" 2> "$stderr_log" &

pid=$!
trap 'kill -TERM "$pid" 2>/dev/null || true; rm -f "$stdout_log" "$stderr_log"' EXIT

# Wait up to 90s for all 5 responses (id=1, id=2, id=3, id=4, id=5).
deadline=$(( $(date +%s) + 90 ))
while [[ $(date +%s) -lt "$deadline" ]]; do
  lines=$(wc -l < "$stdout_log" 2>/dev/null || echo 0)
  if [[ "$lines" -ge 5 ]]; then break; fi
  sleep 2
done

echo "=== response summary ==="
awk '{
    id = "?"; what = "?"; loaded="?"; sample=""
    if (match($0, /"id":([0-9]+)/, m)) id = m[1]
    if ($0 ~ /"protocolVersion"/) what = "initialize"
    else if ($0 ~ /"loaded":true/) { what = "current_apk"; loaded = "true" }
    else if ($0 ~ /"loaded":false/) { what = "current_apk"; loaded = "false" }
    else if ($0 ~ /"status":"ok".*"bridge_port"/) what = "load_apk-ok"
    else if ($0 ~ /No APK is loaded/) what = "NO-APK error"
    else if ($0 ~ /"total_classes"/) {
        what = "get_package_tree"
        match($0, /"total_classes":([0-9]+)/, mm)
        sample = "(total_classes=" mm[1] ")"
    } else what = "other"

    printf("  id=%-2s %-20s %s %s\n", id, what, (loaded=="?" ? "" : "loaded="loaded), sample)
}' "$stdout_log"

echo
echo "=== stderr signals ==="
grep -E "APK loaded|jadx-bridge ready|MCP server ready|client disconnected|jadx-bridge stopped" "$stderr_log" | head -10
