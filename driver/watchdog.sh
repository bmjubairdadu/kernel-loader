#!/bin/bash
set -u

PROJ=/mnt/c/Users/Administrator/Downloads/DaisyDiverLoder
OUT=$PROJ/driver/out_all
ASSETS=$PROJ/app/src/main/assets/drivers
STATUS=$PROJ/driver/build_all_status.txt
LOGDIR=$PROJ/driver/logs
WORK=/root/kernel-all
TOOL=$WORK/toolchain
VFILE=$PROJ/driver/versions.txt
QUEUE=$WORK/queue.txt
LOCK=$WORK/queue.lock
WATCHDOG_LOG="$LOGDIR/watchdog.log"
mkdir -p "$LOGDIR"

log() { echo "$(date +%m-%d %H:%M:%S) $1" >> "$WATCHDOG_LOG"; }
log "WATCHDOG: watchdog started (PID $$)"

is_build_alive() {
  pgrep -f "bash /root/build_all.sh" >/dev/null 2>&1
}

is_queue_empty() {
  [ -f "$QUEUE" ] && [ ! -s "$QUEUE" ]
}

check_internet() {
  curl -s --connect-timeout 5 --max-time 10 -o /dev/null -w "%{http_code}" https://kernel.org 2>/dev/null | grep -q "200\|30[12]"
}

retry_build() {
  log "WATCHDOG: build process died - attempting relaunch..."

  if [ -f "$VFILE" ] && [ ! -s "$QUEUE" ]; then
    tr -d '\r' < "$VFILE" > "$QUEUE"
    log "WATCHDOG: re-initialized queue from versions.txt"
  fi
  touch "$LOCK"
  bash /root/build_all.sh &
  local pid=$!
  log "WATCHDOG: relaunched build_all.sh (PID $pid)"
  sleep 3
  if is_build_alive; then
    log "WATCHDOG: relaunch SUCCESS"
  else
    log "WATCHDOG: relaunch FAILED - will retry in 30s"
    sleep 30
    retry_build
  fi
}

INTERNET_DOWN_SECS=0
while true; do

  if ! is_build_alive; then
    log "WATCHDOG: build_all.sh NOT running"
    if is_queue_empty; then
      log "WATCHDOG: queue empty - build finished normally"
      log "WATCHDOG: publishing driver database to GitHub..."
      tr -d "\r" < "$PROJ/driver/publish_drivers.sh" > /tmp/pub_lf.sh
      bash /tmp/pub_lf.sh >> "$LOGDIR/publish.log" 2>&1 || log "WATCHDOG: publish failed (see $LOGDIR/publish.log)"
      break
    fi
    retry_build
    continue
  fi

  if ! check_internet; then
    INTERNET_DOWN_SECS=$((INTERNET_DOWN_SECS + 30))
    if [ "$INTERNET_DOWN_SECS" -gt 300 ]; then
      log "WATCHDOG: internet down for >5min ($INTERNET_DOWN_SECS s) - curl may be stuck"
    fi
  else
    if [ "$INTERNET_DOWN_SECS" -gt 0 ]; then
      log "WATCHDOG: internet recovered after $INTERNET_DOWN_SECS s - resuming downloads"

      touch "$QUEUE"
    fi
    INTERNET_DOWN_SECS=0
  fi

  if [ -f "$STATUS" ]; then
    local lines=$(wc -l < "$STATUS" 2>/dev/null || echo 0)
    local remaining=$(wc -l < "$QUEUE" 2>/dev/null || echo 0)
    log "WATCHDOG: heartbeat - status_lines=$lines remaining=$remaining"
  fi

  sleep 30
done
log "WATCHDOG: watchdog exiting (build complete or fatal error)"
