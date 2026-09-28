import sys

NEW = '''      tr -d "\\r" < "$PROJ/driver/publish_drivers.sh" > /tmp/pub_lf.sh
      bash /tmp/pub_lf.sh >> "$LOGDIR/publish.log" 2>&1 || log "WATCHDOG: publish failed (see $LOGDIR/publish.log)"'''

OLD = '''      bash "$PROJ/driver/publish_drivers.sh" >> "$LOGDIR/publish.log" 2>&1 || log "WATCHDOG: publish failed (see $LOGDIR/publish.log)"'''

for p in sys.argv[1:]:
    try:
        s = open(p).read()
    except OSError as e:
        print("SKIP", p, e)
        continue
    if OLD in s:
        open(p, "w").write(s.replace(OLD, NEW))
        print("patched", p)
    elif "tr -d" in s:
        print("already ok", p)
    else:
        print("ANCHOR NOT FOUND", p)
