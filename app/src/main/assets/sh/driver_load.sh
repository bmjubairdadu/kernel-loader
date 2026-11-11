#!/system/bin/sh
# driver_load.sh - root-side installer wrapper.
#
#   sh driver_load.sh <installer.sh> [expected-module-name]
#
# Runs the vendor installer, then verifies the result independently of the
# installer's own exit code, and prints a machine-readable block the app parses.
#
# The installer clears dmesg on success, so the module name is recovered from a
# /proc/modules diff and the device name from a /sys/class diff. Both are
# per-load values, which is why neither is hardcoded.
#
# Output: installer stdout verbatim, then
#
#   FTL_MODULE=suvhwvwrtnb
#   FTL_DEVNAME=k3Pq9Z
#   FTL_MAJOR=240
#   FTL_NODE=/dev/k3Pq9Z
#   FTL_OK=1
#
# Exit: 0 loaded + node found, 1 installer failed, 2 loaded but no node, 3 not root

SCRIPT="$1"
EXPECT="${2:-}"
WORK=/data/local/tmp/ftl

if [ -z "$SCRIPT" ] || [ ! -f "$SCRIPT" ]; then
    echo "FTL_ERROR=missing-script"
    echo "FTL_OK=0"
    exit 1
fi

if [ "$(id -u)" -ne 0 ]; then
    echo "FTL_ERROR=not-root"
    echo "FTL_OK=0"
    exit 3
fi

mkdir -p "$WORK" || exit 1

# ---------------------------------------------------------------- pre-snapshot
awk '{ print $1 }' /proc/modules 2>/dev/null | sort > "$WORK/.mod.before"
ls -1 /dev        2>/dev/null | sort > "$WORK/.dev.before"
ls -1 /sys/class  2>/dev/null | sort > "$WORK/.cls.before"

# ---------------------------------------------------------------- run installer
sh "$SCRIPT"
RC=$?

# ------------------------------------------------------------------- verify 1
# The module name is whatever appeared in /proc/modules, not the file name and
# not necessarily the name the caller expected (6.12 ships "devddgy").
awk '{ print $1 }' /proc/modules 2>/dev/null | sort > "$WORK/.mod.after"
MOD=$(grep -vxF -f "$WORK/.mod.before" "$WORK/.mod.after" 2>/dev/null | head -n 1)

ALREADY=0
if [ -z "$MOD" ] && [ -n "$EXPECT" ] && grep -qw "$EXPECT" /proc/modules 2>/dev/null; then
    MOD="$EXPECT"
    ALREADY=1
fi

if [ -z "$MOD" ]; then
    echo "FTL_MODULE="
    echo "FTL_EXIT=$RC"
    echo "FTL_ERROR=module-not-loaded"
    echo "FTL_OK=0"
    exit 1
fi

# ------------------------------------------------------------------- verify 2
# class_create()/device_create() register the 6-char random name in sysfs.
ls -1 /sys/class 2>/dev/null | sort > "$WORK/.cls.after"
DEVNAME=$(grep -vxF -f "$WORK/.cls.before" "$WORK/.cls.after" 2>/dev/null | head -n 1)

# fallback: a new /dev entry that was not there before the load
if [ -z "$DEVNAME" ]; then
    ls -1 /dev 2>/dev/null | sort > "$WORK/.dev.after"
    DEVNAME=$(grep -vxF -f "$WORK/.dev.before" "$WORK/.dev.after" 2>/dev/null \
              | grep -v '^\.' | head -n 1)
fi

MAJOR=""
NODE=""
if [ -n "$DEVNAME" ]; then
    MAJOR=$(awk -v n="$DEVNAME" '$2 == n { print $1; exit }' /proc/devices 2>/dev/null)
    [ -e "/dev/$DEVNAME" ] && NODE="/dev/$DEVNAME"
fi

echo "FTL_MODULE=$MOD"
echo "FTL_DEVNAME=${DEVNAME:-}"
echo "FTL_MAJOR=${MAJOR:-}"
echo "FTL_NODE=${NODE:-}"
echo "FTL_EXIT=$RC"
echo "FTL_ALREADY=$ALREADY"

if [ -z "$NODE" ]; then
    echo "FTL_ERROR=no-device-node"
    echo "FTL_OK=0"
    exit 2
fi

# default mode is 0600 root:root; let the shell group reach it for testing
chmod 660 "$NODE" 2>/dev/null
chown root:shell "$NODE" 2>/dev/null

echo "FTL_OK=1"
exit 0
