#!/system/bin/sh
# verify_load.sh - check whether the module is really loaded and reachable.
#
#   sh verify_load.sh [module-name]
#
# Read-only: nothing is opened, nothing is written. Safe to run after a load
# and after an app restart. Prints a PASS/FAIL line per check.
#
# Exit: 0 everything passed, 1 something is missing.

MOD="${1:-suvhwvwrtnb}"
FAIL=0

ok()   { echo "PASS  $1"; }
bad()  { echo "FAIL  $1"; FAIL=$((FAIL + 1)); }

echo "module : $MOD"
echo "kernel : $(uname -r)"
echo "root   : $(id -u)"
echo "---"

# 1. module table
if grep -qw "$MOD" /proc/modules 2>/dev/null; then
    ok "/proc/modules has $MOD"
    grep -w "$MOD" /proc/modules | sed 's/^/      /'
else
    bad "/proc/modules has no $MOD"
fi

# 2. module sysfs
if [ -d "/sys/module/$MOD" ]; then
    ok "/sys/module/$MOD exists"
else
    bad "/sys/module/$MOD missing"
fi

# 3. device name + node
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
    ls -l "/dev/$DEVNAME" | sed 's/^/      /'
    MAJOR=$(awk -v n="$DEVNAME" '$2 == n { print $1; exit }' /proc/devices 2>/dev/null)
    if [ -n "$MAJOR" ]; then
        ok "/proc/devices: major $MAJOR -> $DEVNAME"
    else
        bad "/proc/devices has no entry for $DEVNAME"
    fi
else
    bad "no 6-char device node found under /sys/class + /dev"
fi

# 4. leftovers from the installer
if [ -e /data/tmpf ]; then
    bad "/data/tmpf left behind (installer failed or was killed)"
    ls -l /data/tmpf | sed 's/^/      /'
else
    ok "/data/tmpf cleaned up"
fi

echo "---"
if [ "$FAIL" -eq 0 ]; then
    echo "RESULT: PASS"
    [ -n "$DEVNAME" ] && echo "NODE=/dev/$DEVNAME"
    exit 0
fi

echo "RESULT: FAIL ($FAIL check(s))"
echo "hint: dmesg | tail -30"
exit 1
