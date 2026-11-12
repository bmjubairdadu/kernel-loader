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

awk '{ print $1 }' /proc/modules 2>/dev/null | sort > "$WORK/.mod.before"
ls -1 /dev        2>/dev/null | sort > "$WORK/.dev.before"
ls -1 /sys/class  2>/dev/null | sort > "$WORK/.cls.before"
awk '/Character devices:/, /Block devices:/ { if ($1 ~ /^[0-9]+$/) print $2 }' /proc/devices 2>/dev/null | sort > "$WORK/.cdev.before"

sh "$SCRIPT"
RC=$?

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

if [ -n "$NEW_DEV" ] && [ -c "/dev/$NEW_DEV" ]; then
    DEVNAME="$NEW_DEV"
    NODE="/dev/$NEW_DEV"
fi

if [ -z "$NODE" ] && [ -n "$NEW_CDEV" ] && [ -c "/dev/$NEW_CDEV" ]; then
    DEVNAME="$NEW_CDEV"
    NODE="/dev/$NEW_CDEV"
fi

if [ -z "$NODE" ] && [ -n "$NEW_CLS" ]; then
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

if [ -z "$NODE" ]; then
    for d in /sys/class/*/; do
        cls=$(basename "$d")
        case "$cls" in
            android_usb|avtimer|backlight|bdi|block|bluetooth|bt*|char|devfreq|diag|dma|dual_role_usb|dvb|extcon|fastrng|fastrpc|firmware|goodix*|gpio|graphics|hidraw|hwmon|i2c*|icesdcc|ieee*|input|iommu|ipa*|jpeg*|kgsl|lcd*|leds|lirc|mdio*|mem|misc|mmc*|msm*|net|phy|pmsg|power*|ppp|pwm|qseecom|ramdump|rc|regulator|rfkill|rmnet*|rotator|rtc|scsi*|smd*|sound|spmi*|ssc|subsys*|switch|thermal|tty*|uio|vc|video*|virtio*|vtconsole|wakeup|wcnss*|wwan*|BaseRemoteCtl|sensors)
                continue
                ;;
        esac
        if [ -c "/dev/$cls" ]; then
            DEVNAME="$cls"
            NODE="/dev/$cls"
            break
        fi
        for child in "$d"*; do
            c=$(basename "$child")
            if [ -c "/dev/$c" ]; then
                DEVNAME="$c"
                NODE="/dev/$c"
                break 2
            fi
        done
    done
fi

MAJOR=""
if [ -n "$DEVNAME" ]; then
    MAJOR=$(awk -v n="$DEVNAME" '$2 == n { print $1; exit }' /proc/devices 2>/dev/null)
fi

IS_STEALTH=0
if [ -z "$MOD" ]; then
    if [ "$RC" -eq 0 ] && [ -n "$NODE" ]; then
        MOD="${EXPECT:-rt}"
        IS_STEALTH=1
    elif [ -n "$NODE" ] && [ -n "$MAJOR" ]; then
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
    echo "FTL_ERROR=no-device-node"
    echo "FTL_OK=0"
    exit 2
fi

chmod 660 "$NODE" 2>/dev/null
chown root:shell "$NODE" 2>/dev/null

echo "FTL_OK=1"
exit 0
