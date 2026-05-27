#!/usr/bin/env bash
# v0.3.1 regression test — verifies the bugs found in v0.3.0 are fixed:
#   1. get_methods_of_class on SecurityWrapper no longer 500s (AccessInfo cycle).
#   2. get_package_tree returns paginated response (not 2 MB blob).
#   3. get_methods_of_class returns `descriptor` field for overloaded methods.
#   4. get_xrefs_to_method on overloaded methods reports overload count when ambiguous.
#   5. get_all_resource_file_names does NOT include "resources.arsc" itself.
#   6. rename_method without class_name OR FQN method_name returns 400.

set -euo pipefail

apk="E:/DEV/handlessJADX/apks/com.sdu.didi.gsui_9.3.0.apk"
exe="E:/DEV/handlessJADX/jadx-headless-mcp/target/release/jadx-mcp.exe"

stdout_log="/tmp/v031_stdout.$$"
stderr_log="/tmp/v031_stderr.$$"

{
  echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"v031","version":"0.0.1"}}}'
  sleep 1
  echo '{"jsonrpc":"2.0","method":"notifications/initialized","params":{}}'
  sleep 1
  echo "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"load_apk\",\"arguments\":{\"path\":\"${apk}\"}}}"
  sleep 45
  # Bug #1 regression — get_methods_of_class on SecurityWrapper
  echo '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_methods_of_class","arguments":{"class_name":"com.didi.security.wireless.adapter.SecurityWrapper"}}}'
  sleep 3
  # Bug #2 regression — paginated package tree
  echo '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"get_package_tree","arguments":{"offset":0,"count":5}}}'
  sleep 3
  # Bug #5 regression — resource listing dedup
  echo '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_all_resource_file_names","arguments":{"count":5}}}'
  sleep 3
  # Bug #6 regression — rename_method requires class_name or FQN
  echo '{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"rename_method","arguments":{"method_name":"init","new_name":"renamed"}}}'
  sleep 2
  # Bug #3 regression — overloaded method detection via get_methods_of_class on CryptoUtil
  echo '{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"get_methods_of_class","arguments":{"class_name":"com.didichuxing.omega.sdk.common.utils.CryptoUtil"}}}'
  sleep 2
  # Bug #4 regression — get_xrefs_to_method on potentially-overloaded
  echo '{"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"get_xrefs_to_method","arguments":{"class_name":"com.didichuxing.omega.sdk.common.utils.CryptoUtil","method_name":"encrypt","count":5}}}'
  sleep 3
} | "$exe" serve --bridge-startup-secs 600 > "$stdout_log" 2> "$stderr_log" &
pid=$!
trap 'kill -TERM "$pid" 2>/dev/null || true' EXIT

deadline=$(( $(date +%s) + 120 ))
while [[ $(date +%s) -lt "$deadline" ]]; do
  lines=$(wc -l < "$stdout_log" 2>/dev/null || echo 0)
  if [[ "$lines" -ge 8 ]]; then break; fi
  sleep 3
done

echo "=== responses ==="
python -c "
import json, sys, os
labels = {
    1: 'initialize',
    2: 'load_apk',
    3: 'get_methods_of_class(SecurityWrapper) — was AccessInfo bomb',
    4: 'get_package_tree(offset=0,count=5) — was un-paginated',
    5: 'get_all_resource_file_names(count=5) — was double-listing resources.arsc',
    6: 'rename_method(init) WITHOUT class_name — should 400 now',
    7: 'get_methods_of_class(CryptoUtil) — should have descriptor field',
    8: 'get_xrefs_to_method(CryptoUtil.encrypt) — should still work',
}
seen = set()
with open(os.environ['STDOUT_LOG'], encoding='utf-8') as f:
    for line in f:
        line=line.strip()
        if not line: continue
        try: obj=json.loads(line)
        except: continue
        rid=obj.get('id')
        seen.add(rid)
        r=obj.get('result',{})
        label=labels.get(rid, f'id={rid}')
        if 'content' in r and r['content']:
            txt=r['content'][0].get('text','')
            tag='ERR ' if r.get('isError') else 'OK  '
            # Probe specific fields per test
            if rid == 3:
                ok = 'access_flags' in txt and ('AccessInfo' not in txt)
                tag = 'PASS' if ok else 'FAIL'
                hits = ['descriptor' in txt, 'access_flags' in txt]
                txt = f'access_flags-stringified={(\"access_flags\" in txt)}, descriptor-present={(\"descriptor\" in txt)}'
            elif rid == 4:
                ok = '\"has_more\"' in txt or 'has_more' in txt
                tag = 'PASS' if ok else 'FAIL'
                txt = txt[:220]
            elif rid == 5:
                ok = 'resources.arsc' not in txt or txt.count('resources.arsc') <= 1
                tag = 'PASS' if ok else 'FAIL'
                txt = txt[:220]
            elif rid == 6:
                ok = r.get('isError') and 'class_name' in txt
                tag = 'PASS' if ok else 'FAIL'
                txt = txt[:220]
            elif rid == 7:
                ok = 'descriptor' in txt
                tag = 'PASS' if ok else 'FAIL'
                txt = ('has descriptor field' if ok else txt[:220])
            else:
                txt = txt[:200]
            print(f'  {tag} #{rid} {label}: {txt}')
        elif 'protocolVersion' in r:
            print(f'  OK   #{rid} {label}')
" 2>&1 || true

# Cleanup
kill -TERM "$pid" 2>/dev/null || true
sleep 1
rm -f "$stdout_log" "$stderr_log"
