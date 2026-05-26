#!/usr/bin/env bash
# Smoke test the npm wrapper end-to-end: pipe MCP frames into `node cli.js`,
# verify it downloads the binary and proxies tool calls correctly.
#
#   ./scripts/smoke_test_npm.sh /path/to/app.apk

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <apk>" >&2
  exit 2
fi

apk="$1"
root="$(cd "$(dirname "$0")"/.. && pwd)"
cli="$root/npm-wrapper/cli.js"
[[ -f "$cli" ]] || { echo "wrapper not found: $cli" >&2; exit 1; }

req=$(cat <<'EOF'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"npm-smoke","version":"0.0.1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_android_manifest","arguments":{}}}
EOF
)

stdout_log="$(mktemp -t npm_smoke_stdout.XXXX)"
stderr_log="$(mktemp -t npm_smoke_stderr.XXXX)"
echo "stdout: $stdout_log"
echo "stderr: $stderr_log"

( echo "$req"; sleep 60 ) | node "$cli" serve --apk "$apk" --bridge-startup-secs 600 \
    > "$stdout_log" 2> "$stderr_log" &
pid=$!
trap 'kill -TERM "$pid" 2>/dev/null || true' EXIT

# Wait up to 5 min for 3 response frames (binary download + bridge load + 3 RPC).
deadline=$(( $(date +%s) + 300 ))
while [[ $(date +%s) -lt "$deadline" ]]; do
  lines=$(wc -l < "$stdout_log" 2>/dev/null || echo 0)
  if [[ "$lines" -ge 3 ]]; then break; fi
  sleep 5
done

echo "--- stderr (last 30) ---"
tail -n 30 "$stderr_log"
echo "--- stdout response frame ids ---"
head -n 4 "$stdout_log" | python -c "
import sys, json
for line in sys.stdin:
    line = line.strip()
    if not line: continue
    try:
        obj = json.loads(line)
        id_ = obj.get('id', '?')
        has_result = 'result' in obj
        has_error = 'error' in obj
        if has_result:
            content = obj['result'].get('content') or obj['result'].get('tools') or list(obj['result'].keys())
            if isinstance(content, list) and content and isinstance(content[0], dict):
                summary = f\"{len(content)} item(s), keys=[{','.join(content[0].keys())}]\"
            else:
                summary = str(content)[:120]
            print(f'  id={id_}  RESULT: {summary}')
        elif has_error:
            print(f'  id={id_}  ERROR: {obj[\"error\"]}')
        else:
            print(f'  id={id_}  ???')
    except Exception as e:
        print(f'  non-json line: {line[:80]} ({e})')
"
echo "--- stdout total lines: $(wc -l < "$stdout_log") ---"
