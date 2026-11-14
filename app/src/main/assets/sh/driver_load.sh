#!/system/bin/sh
SCRIPT="$1"
EXPECT="${2:-suvhwvwrtnb}"
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
trap 'rm -rf "$WORK" /dev/.kloder_session 2>/dev/null' EXIT

find_stealth_node() {
    DEVNAME=""
    NODE=""
    CLSNAME=""
    OPEN_FD=""
    for d in /sys/class/*/; do
        [ -d "$d" ] || continue
        cls=$(basename "$d")
        case "$cls" in
            android_usb|avtimer|backlight|battery*|bdi|block|bluetooth|bt*|char|charger*|devfreq|diag|dma|drm|dual_role_usb|dvb|extcon|fastrng|fastrpc|fingerprint|firmware|fuse|goodix*|gpio|graphics|hidraw|hwmon|i2c*|icesdcc|ieee*|input|iommu|ion|ipa*|jpeg*|kgsl|kmsg|lcd*|leds|lirc|mdio*|mem|misc|mmc*|msm*|net|phy|pmsg|power*|ppp|pwm|qg*|qpnp*|qseecom|ramdump|rc|regulator|remoteproc|rfkill|rmnet*|rotator|rpmsg|rtc|scsi*|sensors|smb*|smd*|soc*|sound|spidev|spmi*|ssc|subsys*|switch|tee|thermal|tty*|typec|uhid|uinput|uio|usb*|vc|video*|virtio*|vtconsole|wakeup|watchdog*|wcnss*|wwan*|zram*|BaseRemoteCtl|accdet*|mrdump*|Vcodec*|vcodec*|ndctl*|dimm*|dpa*)
                continue
                ;;
        esac
        case "$cls" in
            [0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9])
                if [ -c "/dev/$cls" ]; then
                    CLSNAME="$cls"
                    DEVNAME="$cls"
                    NODE="/dev/$cls"
                    return 0
                fi
                ;;
        esac
        for child in "$d"*; do
            [ -e "$child" ] || continue
            c=$(basename "$child")
            case "$c" in
                [0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9])
                    if [ -c "/dev/$c" ]; then
                        CLSNAME="$cls"
                        DEVNAME="$c"
                        NODE="/dev/$c"
                        return 0
                    fi
                    ;;
            esac
        done
    done
    return 1
}

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

# Clean up any leftover temporary active-node marker before running fresh install
rm -f /dev/.kloder_active_node 2>/dev/null

PRE_MOD=""
PRE_ALREADY=0
PRE_STEALTH=0
PRE_DEVNAME=""
PRE_NODE=""
PRE_CLSNAME=""

if [ -n "$EXPECT" ] && grep -qw "$EXPECT" /proc/modules 2>/dev/null; then
    PRE_MOD="$EXPECT"
    PRE_ALREADY=1
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

ls -1 /dev 2>/dev/null | sort > "$WORK/.dev.after"
NEW_DEV=$(grep -vxF -f "$WORK/.dev.before" "$WORK/.dev.after" 2>/dev/null | grep -v '^\.' | head -n 1)

awk '/Character devices:/, /Block devices:/ { if ($1 ~ /^[0-9]+$/) print $2 }' /proc/devices 2>/dev/null | sort > "$WORK/.cdev.after"
NEW_CDEV=$(grep -vxF -f "$WORK/.cdev.before" "$WORK/.cdev.after" 2>/dev/null | head -n 1)

ls -1 /sys/class 2>/dev/null | sort > "$WORK/.cls.after"
NEW_CLS=$(grep -vxF -f "$WORK/.cls.before" "$WORK/.cls.after" 2>/dev/null | head -n 1)

DEVNAME=""
NODE=""
CLSNAME=""

# Priority 1: Direct node marker created by vendor insmod execution
if [ -s /dev/.kloder_active_node ]; then
    ACT_NODE=$(cat /dev/.kloder_active_node 2>/dev/null)
    if [ -n "$ACT_NODE" ] && [ -c "$ACT_NODE" ]; then
        NODE="$ACT_NODE"
        DEVNAME=$(basename "$ACT_NODE")
        CLSNAME="$DEVNAME"
    fi
fi

# Priority 2: True diff of /dev newly created during installer execution (must start with digit)
if [ -z "$NODE" ]; then
    ls -1 /dev 2>/dev/null | sort > "$WORK/.dev.after"
    for cand in $(grep -vxF -f "$WORK/.dev.before" "$WORK/.dev.after" 2>/dev/null | grep -v '^\.'); do
        if [ -c "/dev/$cand" ]; then
            case "$cand" in
                [0-9]*)
                    DEVNAME="$cand"
                    NODE="/dev/$cand"
                    CLSNAME="$cand"
                    echo "$NODE" > /dev/.kloder_active_node 2>/dev/null
                    chmod 0644 /dev/.kloder_active_node 2>/dev/null
                    break
                    ;;
            esac
        fi
    done
fi

# Priority 3: True diff of /sys/class newly created during installer execution
if [ -z "$NODE" ]; then
    ls -1 /sys/class 2>/dev/null | sort > "$WORK/.cls.after"
    for cand_cls in $(grep -vxF -f "$WORK/.cls.before" "$WORK/.cls.after" 2>/dev/null); do
        case "$cand_cls" in
            [0-9]*)
                if [ -c "/dev/$cand_cls" ]; then
                    DEVNAME="$cand_cls"
                    NODE="/dev/$cand_cls"
                    CLSNAME="$cand_cls"
                    echo "$NODE" > /dev/.kloder_active_node 2>/dev/null
                    chmod 0644 /dev/.kloder_active_node 2>/dev/null
                    break
                fi
                ;;
        esac
    done
fi

resolve_major

if [ -n "$NODE" ] && [ -c "$NODE" ]; then
    chmod 0600 "$NODE" 2>/dev/null
    echo "$NODE" > /dev/.kloder_active_node 2>/dev/null
    chmod 0600 /dev/.kloder_active_node 2>/dev/null
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
