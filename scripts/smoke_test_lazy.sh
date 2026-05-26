#!/usr/bin/env bash
# Smoke test for the v0.3.0 lazy-load behavior:
#   1. Server starts WITHOUT --apk.
#   2. Calling get_package_tree before load_apk returns a clean error.
#   3. load_apk loads the file.
#   4. get_package_tree after load_apk returns real data.
#
# usage:  ./scripts/smoke_test_lazy.sh /path/to/app.apk

set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: $0 <apk>" >&2
  exit 2
fi

apk_raw="$1"
# Convert to absolute path that Rust/Java will accept on Windows too.
apk=$(cygpath -w "$apk_raw" 2>/dev/null || readlink -f "$apk_raw")
root="$(cd "$(dirname "$0")"/.. && pwd)"
exe="$root/target/release/jadx-mcp"
[[ -x "$exe" ]] || exe="$exe.exe"
[[ -x "$exe" ]] || { echo "binary not built: $exe" >&2; exit 1; }

# JSON-escape backslashes for embedding in the request stream.
apk_json=$(printf '%s' "$apk" | sed 's/\\/\\\\/g')

req=$(cat <<EOF
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"lazy-smoke","version":"0.0.1"}}}
{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_package_tree","arguments":{}}}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"current_apk","arguments":{}}}
{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"load_apk","arguments":{"path":"${apk_json}"}}}
{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"current_apk","arguments":{}}}
{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"get_package_tree","arguments":{}}}
EOF
)

stdout_log="$(mktemp -t lazy_smoke_stdout.XXXX)"
stderr_log="$(mktemp -t lazy_smoke_stderr.XXXX)"
echo "stdout: $stdout_log"
echo "stderr: $stderr_log"

# Note: NO --apk arg. Server should start cleanly.
( echo "$req"; sleep 90 ) | "$exe" serve --bridge-startup-secs 600 \
    > "$stdout_log" 2> "$stderr_log" &
pid=$!
trap 'kill -TERM "$pid" 2>/dev/null || true' EXIT

deadline=$(( $(date +%s) + 240 ))
while [[ $(date +%s) -lt "$deadline" ]]; do
  lines=$(wc -l < "$stdout_log" 2>/dev/null || echo 0)
  if [[ "$lines" -ge 6 ]]; then break; fi
  sleep 3
done

echo
echo "=== response summary (parsed) ==="
python -c "
import sys, json
expected = {
    1: ('initialize',          lambda r: 'protocolVersion' in r),
    2: ('get_package_tree #1', lambda r: any('No APK is loaded' in c.get('text','') for c in r.get('content',[]))),
    3: ('current_apk #1',      lambda r: '\"loaded\": false' in (r.get('content',[{}])[0].get('text','') or '')),
    4: ('load_apk',            lambda r: '\"status\": \"ok\"' in (r.get('content',[{}])[0].get('text','') or '')),
    5: ('current_apk #2',      lambda r: '\"loaded\": true' in (r.get('content',[{}])[0].get('text','') or '')),
    6: ('get_package_tree #2', lambda r: 'total_classes' in (r.get('content',[{}])[0].get('text','') or '')),
}
seen = set()
with open('$stdout_log', encoding='utf-8') as f:
    for line in f:
        line = line.strip()
        if not line: continue
        try:
            obj = json.loads(line)
        except Exception as e:
            print(f'  ??? non-json: {line[:100]} ({e})')
            continue
        id_ = obj.get('id')
        seen.add(id_)
        label, predicate = expected.get(id_, (f'id={id_}', lambda r: True))
        r = obj.get('result') or obj.get('error') or {}
        ok = '✓' if predicate(r) else '✗'
        body_preview = (json.dumps(r)[:140] if r else '(empty)')
        print(f'  {ok} #{id_} {label}: {body_preview}')

for id_ in (1,2,3,4,5,6):
    if id_ not in seen:
        print(f'  MISSING response for #{id_}')
"

echo
echo "=== stderr tail (last 15 lines) ==="
tail -n 15 "$stderr_log"
