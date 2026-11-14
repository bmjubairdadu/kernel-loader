#!/system/bin/sh
# Advanced Driver Execution Engine for Kernel Loader
# Diff-driven stealth device registration without static blacklist guessing

SCRIPT="$1"
EXPECT="$2"
WORK="/data/local/tmp/ftl_work_$$"

if [ -z "$SCRIPT" ] || [ ! -f "$SCRIPT" ]; then
    echo "FTL_ERROR=script_missing"
    echo "FTL_OK=0"
    exit 3
fi

mkdir -p "$WORK" || exit 1
trap 'rm -rf "$WORK" /dev/.kloder_session 2>/dev/null' EXIT

resolve_major() {
    MAJOR=""
    [ -z "$DEVNAME" ] && return
    MAJOR=$(awk -v n="$DEVNAME" '$2 == n { print $1; exit }' /proc/devices 2>/dev/null)
    if [ -z "$MAJOR" ] && [ -n "$CLSNAME" ] && [ -r "/sys/class/$CLSNAME/$DEVNAME/dev" ]; then
        MAJOR=$(cut -d: -f1 "/sys/class/$CLSNAME/$DEVNAME/dev" 2>/dev/null)
    fi
    if [ -z "$MAJOR" ] && [ -r "/sys/devices/virtual/$DEVNAME/$DEVNAME/dev" ]; then
        MAJOR=$(cut -d: -f1 "/sys/devices/virtual/$DEVNAME/$DEVNAME/dev" 2>/dev/null)
    fi
    if [ -z "$MAJOR" ] && [ -c "$NODE" ]; then
        HEX_MAJ=$(stat -c %t "$NODE" 2>/dev/null)
        [ -n "$HEX_MAJ" ] && MAJOR=$(printf "%d" "0x$HEX_MAJ" 2>/dev/null)
    fi
}

PRE_MOD=""
if [ -n "$EXPECT" ] && grep -qw "$EXPECT" /proc/modules 2>/dev/null; then
    PRE_MOD="$EXPECT"
    echo "FTL_MODULE=$PRE_MOD"
    echo "FTL_EXIT=0"
    echo "FTL_ALREADY=1"
    echo "FTL_STEALTH=0"
    echo "FTL_OK=1"
    exit 0
fi

# [Stage 1] Pre-execution Snapshots
awk '{ print $1 }' /proc/modules 2>/dev/null | sort > "$WORK/.mod.before"
ls -1 /dev        2>/dev/null | sort > "$WORK/.dev.before"
ls -1 /sys/class  2>/dev/null | sort > "$WORK/.cls.before"
awk '/Character devices:/, /Block devices:/ { if ($1 ~ /^[0-9]+$/) print $2 }' /proc/devices 2>/dev/null | sort > "$WORK/.cdev.before"

# [Stage 1] Run Vendor Installer
sh "$SCRIPT"
RC=$?

# [Stage 2] Post-execution Diff & Advanced Verification
awk '{ print $1 }' /proc/modules 2>/dev/null | sort > "$WORK/.mod.after"
MOD=$(grep -vxF -f "$WORK/.mod.before" "$WORK/.mod.after" 2>/dev/null | head -n 1)

ALREADY=0
if [ -z "$MOD" ] && [ -n "$EXPECT" ] && grep -qw "$EXPECT" /proc/modules 2>/dev/null; then
    MOD="$EXPECT"
    ALREADY=1
fi

DEVNAME=""
NODE=""
CLSNAME=""

# True diff of /dev newly created during installer execution (must start with digit)
ls -1 /dev 2>/dev/null | sort > "$WORK/.dev.after"
for cand in $(grep -vxF -f "$WORK/.dev.before" "$WORK/.dev.after" 2>/dev/null | grep -v '^\.'); do
    if [ -c "/dev/$cand" ]; then
        case "$cand" in
            [0-9]*)
                DEVNAME="$cand"
                NODE="/dev/$cand"
                CLSNAME="$cand"
                break
                ;;
        esac
    fi
done

# True diff of /sys/class newly created during installer execution
if [ -z "$NODE" ]; then
    ls -1 /sys/class 2>/dev/null | sort > "$WORK/.cls.after"
    for cand_cls in $(grep -vxF -f "$WORK/.cls.before" "$WORK/.cls.after" 2>/dev/null); do
        case "$cand_cls" in
            [0-9]*)
                if [ -c "/dev/$cand_cls" ]; then
                    DEVNAME="$cand_cls"
                    NODE="/dev/$cand_cls"
                    CLSNAME="$cand_cls"
                    break
                fi
                ;;
        esac
    done
fi

resolve_major

if [ -n "$NODE" ] && [ -c "$NODE" ]; then
    chmod 0600 "$NODE" 2>/dev/null
fi

IS_STEALTH=0
if [ -z "$MOD" ]; then
    if [ "$RC" -eq 0 ] && [ -n "$NODE" ]; then
        MOD="${EXPECT:-rt}"
        IS_STEALTH=1
    fi
fi

echo "FTL_MODULE=$MOD"
echo "FTL_DEVNAME=${DEVNAME:-}"
echo "FTL_MAJOR=${MAJOR:-}"
echo "FTL_NODE=${NODE:-}"
echo "FTL_EXIT=$RC"
echo "FTL_ALREADY=$ALREADY"
echo "FTL_STEALTH=$IS_STEALTH"

if [ -z "$MOD" ]; then
    echo "FTL_ERROR=module-not-loaded"
    echo "FTL_OK=0"
    exit 1
fi

if [ -z "$NODE" ]; then
    if [ "$RC" -eq 0 ]; then
        echo "FTL_OK=1"
        exit 0
    fi
    echo "FTL_ERROR=no-device-node"
    echo "FTL_OK=0"
    exit 2
fi

echo "FTL_OK=1"
exit 0
