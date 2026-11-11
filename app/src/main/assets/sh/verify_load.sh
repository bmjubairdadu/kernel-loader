#!/system/bin/sh
MOD="${1:-suvhwvwrtnb}"
FAIL=0

ok()   { echo "PASS: $1"; }
bad()  { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "module : $MOD"
echo "kernel : $(uname -r)"
echo "root   : $(id -u)"

if grep -qw "$MOD" /proc/modules 2>/dev/null; then
    ok "/proc/modules has $MOD"
else
    bad "/proc/modules has no $MOD"
fi

if [ -d "/sys/module/$MOD" ]; then
    ok "/sys/module/$MOD exists"
else
    bad "/sys/module/$MOD missing"
fi

DEVNAME=""
for d in /sys/class/*/; do
    n=$(basename "$d")
    if [ -e "$d$n" ] && [ "${#n}" -eq 6 ] && [ -e "/dev/$n" ]; then
        DEVNAME="$n"
        break
    fi
done

if [ -n "$DEVNAME" ]; then
    ok "device node /dev/$DEVNAME"
    MAJOR=$(awk -v n="$DEVNAME" '$2 == n { print $1; exit }' /proc/devices 2>/dev/null)
    if [ -n "$MAJOR" ]; then
        ok "/proc/devices: major $MAJOR -> $DEVNAME"
    else
        bad "/proc/devices has no entry for $DEVNAME"
    fi
else
    bad "no 6-char device node found under /sys/class + /dev"
fi

if [ -e /data/tmpf ]; then
    bad "/data/tmpf left behind (installer failed or was killed)"
else
    ok "/data/tmpf cleaned up"
fi

if [ "$FAIL" -eq 0 ]; then
    echo "RESULT: PASS"
    [ -n "$DEVNAME" ] && echo "NODE=/dev/$DEVNAME"
    exit 0
fi

echo "RESULT: FAIL ($FAIL check(s))"
exit 1
