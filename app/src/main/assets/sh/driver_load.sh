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
            android_usb|avtimer|backlight|battery*|bdi|block|bluetooth|bt*|char|charger*|devfreq|diag|dma|drm|dual_role_usb|dvb|extcon|fastrng|fastrpc|fingerprint|firmware|fuse|goodix*|gpio|graphics|hidraw|hwmon|i2c*|icesdcc|ieee*|input|iommu|ion|ipa*|jpeg*|kgsl|kmsg|lcd*|leds|lirc|mdio*|mem|misc|mmc*|msm*|net|phy|pmsg|power*|ppp|pwm|qg*|qpnp*|qseecom|ramdump|rc|regulator|remoteproc|rfkill|rmnet*|rotator|rpmsg|rtc|scsi*|sensors|smb*|smd*|soc*|sound|spidev|spmi*|ssc|subsys*|switch|tee|thermal|tty*|typec|uhid|uinput|uio|usb*|vc|video*|virtio*|vtconsole|wakeup|watchdog*|wcnss*|wwan*|zram*|BaseRemoteCtl|accdet*|mrdump*|Vcodec*|vcodec*)
                continue
                ;;
        esac
        case "$cls" in
            [A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9])
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
                [A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9])
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

PRE_MOD=""
PRE_ALREADY=0
PRE_STEALTH=0
PRE_DEVNAME=""
PRE_NODE=""
PRE_CLSNAME=""

if [ -n "$EXPECT" ] && grep -qw "$EXPECT" /proc/modules 2>/dev/null; then
    PRE_MOD="$EXPECT"
    PRE_ALREADY=1
else
    if find_stealth_node; then
        case "$DEVNAME" in
            [A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9])
                if ! grep -qw "$DEVNAME" /proc/devices 2>/dev/null; then
                    PRE_DEVNAME="$DEVNAME"
                    PRE_NODE="$NODE"
                    PRE_CLSNAME="$CLSNAME"
                    PRE_MOD="$EXPECT"
                    PRE_ALREADY=1
                    PRE_STEALTH=1
                fi
                ;;
        esac
    fi
    if [ "$PRE_ALREADY" -eq 0 ]; then
        FD_HIT=$(ls -l /proc/[0-9]*/fd 2>/dev/null | awk '/ -> \/dev\/[A-Za-z0-9]{6} \(deleted\)$/ { dev=$(NF-1); sub(/^\/dev\//,"",dev); if (dev !~ /^(binder|common|ashmem|random)$/) { print dev; exit } }')
        if [ -n "$FD_HIT" ]; then
            PRE_DEVNAME="$FD_HIT"
            PRE_NODE="/dev/$FD_HIT"
            PRE_MOD="$EXPECT"
            PRE_ALREADY=1
            PRE_STEALTH=1
        fi
    fi
fi

if [ "$PRE_ALREADY" -eq 1 ]; then
    MOD="$PRE_MOD"
    DEVNAME="$PRE_DEVNAME"
    NODE="$PRE_NODE"
    CLSNAME="$PRE_CLSNAME"
    ALREADY=1
    IS_STEALTH="$PRE_STEALTH"
    RC=0
    resolve_major
    echo "FTL_MODULE=$MOD"
    echo "FTL_DEVNAME=${DEVNAME:-}"
    echo "FTL_MAJOR=${MAJOR:-}"
    echo "FTL_NODE=${NODE:-}"
    echo "FTL_EXIT=0"
    echo "FTL_ALREADY=1"
    echo "FTL_STEALTH=$IS_STEALTH"
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

if [ -n "$NEW_DEV" ] && [ -c "/dev/$NEW_DEV" ]; then
    DEVNAME="$NEW_DEV"
    NODE="/dev/$NEW_DEV"
    CLSNAME="$NEW_DEV"
fi

if [ -z "$NODE" ] && [ -n "$NEW_CDEV" ] && [ -c "/dev/$NEW_CDEV" ]; then
    DEVNAME="$NEW_CDEV"
    NODE="/dev/$NEW_CDEV"
    CLSNAME="$NEW_CDEV"
fi

if [ -z "$NODE" ] && [ -n "$NEW_CLS" ]; then
    CLSNAME="$NEW_CLS"
    if [ -c "/dev/$NEW_CLS" ]; then
        DEVNAME="$NEW_CLS"
        NODE="/dev/$NEW_CLS"
    else
        for child in "/sys/class/$NEW_CLS"/*; do
            c=$(basename "$child")
            if [ -c "/dev/$c" ]; then
                DEVNAME="$c"
                NODE="/dev/$c"
                break
            fi
        done
    fi
fi

# Fallback scan for active stealth device (only when vendor script succeeded)
if [ -z "$NODE" ] && [ "$RC" -eq 0 ]; then
    find_stealth_node || true
fi

# If node was still not found but vendor script succeeded (RC=0), give kernel a moment to register node
if [ -z "$NODE" ] && [ "$RC" -eq 0 ]; then
    sleep 0.3
    ls -1 /dev 2>/dev/null | sort > "$WORK/.dev.after"
    NEW_DEV=$(grep -vxF -f "$WORK/.dev.before" "$WORK/.dev.after" 2>/dev/null | grep -v '^\.' | head -n 1)
    if [ -n "$NEW_DEV" ] && [ -c "/dev/$NEW_DEV" ]; then
        DEVNAME="$NEW_DEV"
        NODE="/dev/$NEW_DEV"
        CLSNAME="$NEW_DEV"
    fi
    if [ -z "$NODE" ]; then
        find_stealth_node || true
    fi
fi

# Check proc/devices fallback if node opened and class deleted
if [ -z "$DEVNAME" ]; then
    CDEV=$(awk '/Character devices:/, /Block devices:/ { if ($1 ~ /^[0-9]+$/ && $2 ~ /^[A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9]$/ && $2 !~ /^(mem|msm|tty|misc|input|sg|fb|video|ch|i2c|ppp|alsa|ptm|pts|spi|usb|DVB|adsprpc|avtimer|dts|sensors|hidraw|bt|jpeg|BaseRemote|goodix|rmnet|icesdcc|qseecom|kgsl|frandom|diag|smd|wcnss|subsys|ramdump|mdss|ipa|iio|media|rtc|pmsg|gpio|uio|wwan|rfkill|extcon|switch|spidev|Vcodec|vcodec|accdet|mrdump)/) print $2 }' /proc/devices 2>/dev/null | tail -n 1)
    if [ -n "$CDEV" ]; then
        DEVNAME="$CDEV"
        [ -c "/dev/$CDEV" ] && NODE="/dev/$CDEV"
    fi
fi

resolve_major

IS_STEALTH=0
if [ -z "$MOD" ]; then
    if [ "$RC" -eq 0 ] && [ -n "$NODE" ]; then
        MOD="${EXPECT:-rt}"
        IS_STEALTH=1
    elif [ "$RC" -eq 0 ] && [ -n "$NODE" ] && [ -n "$MAJOR" ]; then
        MOD="${EXPECT:-rt}"
        IS_STEALTH=1
        ALREADY=1
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
