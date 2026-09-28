#!/usr/bin/env bash
set -euo pipefail
F=/root/build_watchdog.sh
[ -f "$F" ] || { echo "ERROR: $F missing"; exit 1; }

if grep -q 'publish_drivers' "$F"; then
  echo "[=] watchdog already patched"
  exit 0
fi

python3 - "$F" <<'PYEOF'
import sys
p = sys.argv[1]
s = open(p).read()
old = '''    if is_queue_empty; then
      log "WATCHDOG: queue empty - build finished normally, exiting watchdog"
      break
    fi'''
new = '''    if is_queue_empty; then
      log "WATCHDOG: queue empty - build finished normally"
      log "WATCHDOG: publishing driver database to GitHub..."
      bash "$PROJ/driver/publish_drivers.sh" >> "$LOGDIR/publish.log" 2>&1 || log "WATCHDOG: publish failed (see $LOGDIR/publish.log)"
      break
    fi'''
if old not in s:
    print("ANCHOR NOT FOUND - no change")
    sys.exit(1)
open(p, "w").write(s.replace(old, new))
print("[+] watchdog patched - will publish to GitHub when build finishes")
PYEOF
