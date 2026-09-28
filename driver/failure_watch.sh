#!/bin/bash
set -u
REPO="${REPO:-bmjubairdadu/kernel-loder}"
PROJ="$(dirname "$(readlink -f "$0")")"
OUT_ALL="$PROJ/out_all"

command -v curl >/dev/null 2>&1 || apt-get install -y curl 2>&1 | tail -n 1

ensure_token() {
  [ -n "${GH_TOKEN:-}" ] && return 0
  local GCM="/mnt/c/Program Files/Git/mingw64/bin/git-credential-manager.exe"
  if [ -x "$GCM" ]; then
    GH_TOKEN=$(printf "protocol=https\nhost=github.com\n" | "$GCM" get 2>/dev/null \
      | sed -n 's/^password=//p' | tr -d '\r\n')
    [ -n "$GH_TOKEN" ] && { export GH_TOKEN; return 0; }
  fi
  return 1
}

api_issues() {
  curl -s --max-time 25 -H "Authorization: Bearer $GH_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$REPO/issues?state=open&per_page=50" -o "$1"
}

api_comment() {
  python3 - "$1" "$2" <<'PY' > /tmp/_cm.json
import json, sys
print(json.dumps({"body": sys.argv[2]}))
PY
  curl -s --max-time 25 -X POST -H "Authorization: Bearer $GH_TOKEN" \
    -H "Accept: application/vnd.github+json" -H "Content-Type: application/json" \
    --data @/tmp/_cm.json \
    "https://api.github.com/repos/$REPO/issues/$1/comments" >/dev/null
}

api_close() {
  curl -s --max-time 25 -X PATCH -H "Authorization: Bearer $GH_TOKEN" \
    -H "Accept: application/vnd.github+json" -H "Content-Type: application/json" \
    --data '{"state":"closed","state_reason":"completed"}' \
    "https://api.github.com/repos/$REPO/issues/$1" >/dev/null
}

api_comments() {
  curl -s --max-time 25 -H "Authorization: Bearer $GH_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$REPO/issues/$1/comments?per_page=30"
}

is_buildable() {
  local short="$1"
  grep -qx "$short" "$PROJ/versions.txt" 2>/dev/null && return 0
  case "$short" in
    4.9.337) [ -d /root/daisy337 ] && return 0 ;;
  esac
  return 1
}

autofix_pass() {
  echo "=== $(date '+%F %T') : autofix pass ==="
  ensure_token || { echo "no GitHub token (Git Bash login missing?)"; return 1; }

  local covered
  covered=$( {
    [ -d "$OUT_ALL" ] && for f in "$OUT_ALL"/*.ko; do
      b=$(basename "$f" .ko); v=${b#*_}; echo "$v" | grep -oE "^[0-9]+\.[0-9]+\.[0-9]+"
    done 2>/dev/null
    curl -s --max-time 20 "https://raw.githubusercontent.com/bmjubairdadu/kernel-loder/drivers/drivers.json" \
      | grep -oE '"version": *"[^"]+"' | grep -oE "[0-9]+\.[0-9]+\.[0-9]+" | sort -u
  } | sort -u )

  api_issues /tmp/_issues.json
  python3 - /tmp/_issues.json <<'PY' | while IFS='|' read -r num kernel short; do
import json, re, sys
for iss in json.load(open(sys.argv[1])):
    if "pull_request" in iss:
        continue
    if "[AUTO-REPORT]" not in (iss.get("title") or ""):
        continue
    body = iss.get("body") or ""
    m = re.search(r"Kernel\s*:\s*([^\n]+)", body)
    kernel = (m.group(1).strip() if m else "?")
    sm = re.search(r"(\d+)\.(\d+)\.(\d+)", kernel)
    short = f"{sm.group(1)}.{sm.group(2)}.{sm.group(3)}" if sm else "?"
    print(f"{iss['number']}|{kernel}|{short}")
PY
    [ -z "$num" ] && continue
    seen=$(api_comments "$num" | grep -c "kl-watch" || true)
    if echo "$covered" | grep -qx "$short"; then
      echo "#$num ($short): loader now exists -> closing"
      api_comment "$num" "✅ Auto-fix: a loader for \`$short\` is now in the database. Update the app, refresh, and tap AUTO LOAD. <!-- kl-watch -->"
      api_close "$num"
    elif [ "$seen" != "0" ]; then
      echo "#$num ($short): already triaged, waiting"
    elif is_buildable "$short"; then
      echo "#$num ($short): buildable -> queued + commented"
      grep -qx "$short" "$PROJ/auto_queue.txt" 2>/dev/null || echo "$short" >> "$PROJ/auto_queue.txt"
      api_comment "$num" "🤖 Auto-triage: kernel \`$kernel\` needs a fresh build - **queued on the build PC**. You will get the loader via app update / OTA. <!-- kl-watch -->"
    else
      echo "#$num ($short): vendor tree needed -> commented"
      api_comment "$num" "🤖 Auto-triage: kernel \`$kernel\` has no public source tree, so it cannot be auto-built yet. The developer was notified - a manual build may follow. <!-- kl-watch -->"
    fi
  done
}

MODE="${1:-once}"
SLEEP_SECS="${2:-300}"
LOG="$PROJ/watch.log"

triage() {
  echo "=== $(date '+%F %T') : open [AUTO-REPORT] issues ==="
  ensure_token || { echo "no GitHub token"; return 1; }
  api_issues /tmp/_issues.json
  python3 - /tmp/_issues.json <<'PY'
import json, sys
found = False
for iss in json.load(open(sys.argv[1])):
    if "pull_request" in iss or "[AUTO-REPORT]" not in (iss.get("title") or ""):
        continue
    found = True
    print(f"{iss['number']} | {(iss.get('created_at') or '')[:10]} | {iss.get('title')} | {iss.get('html_url')}")
if not found:
    print("(none)")
PY
}

if [ "$MODE" = "watch" ]; then
  while true; do triage; echo ""; echo "sleeping ${SLEEP_SECS}s (Ctrl+C to stop)..."; sleep "$SLEEP_SECS"; done
elif [ "$MODE" = "watch-autofix" ]; then
  exec >>"$LOG" 2>&1
  echo "=== watch-autofix started $(date '+%F %T') (every ${SLEEP_SECS}s) ==="
  while true; do autofix_pass; echo "--- sleep ${SLEEP_SECS}s ---"; sleep "$SLEEP_SECS"; done
else
  triage
  echo ""
  echo "tip: $0 watch 300   (auto-check every 5 minutes)"
fi
