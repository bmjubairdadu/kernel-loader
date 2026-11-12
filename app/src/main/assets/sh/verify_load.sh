#!/system/bin/sh
MOD="${1:-suvhwvwrtnb}"
FAIL=0

ok()   { echo "PASS: $1"; }
bad()  { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "module : $MOD"
echo "kernel : $(uname -r)"
echo "root   : $(id -u)"

DEVNAME=""
NODE=""
for d in /sys/class/*/; do
    cls=$(basename "$d")
    case "$cls" in
        android_usb|avtimer|backlight|battery*|bdi|block|bluetooth|bt*|char|charger*|devfreq|diag|dma|dual_role_usb|dvb|extcon|fastrng|fastrpc|firmware|goodix*|gpio|graphics|hidraw|hwmon|i2c*|icesdcc|ieee*|input|iommu|ipa*|jpeg*|kgsl|lcd*|leds|lirc|mdio*|mem|misc|mmc*|msm*|net|phy|pmsg|power*|ppp|pwm|qg*|qseecom|ramdump|rc|regulator|rfkill|rmnet*|rotator|rtc|scsi*|smb*|smd*|soc*|sound|spmi*|ssc|subsys*|switch|thermal|tty*|uio|vc|video*|virtio*|vtconsole|wakeup|wcnss*|wwan*|BaseRemoteCtl|sensors)
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

if [ -z "$DEVNAME" ]; then
    CDEV=$(awk '/Character devices:/, /Block devices:/ { if ($1 ~ /^[0-9]+$/ && $2 !~ /^(mem|msm|tty|misc|input|sg|fb|video|ch|i2c|ppp|alsa|ptm|pts|spi|usb|DVB|adsprpc|avtimer|dts|sensors|hidraw|bt|jpeg|BaseRemote|goodix|rmnet|icesdcc|qseecom|kgsl|frandom|diag|smd|wcnss|subsys|ramdump|mdss|ipa|iio|media|rtc|pmsg|gpio|uio|wwan)/) print $2 }' /proc/devices 2>/dev/null | tail -n 1)
    if [ -n "$CDEV" ]; then
        DEVNAME="$CDEV"
        [ -e "/dev/$CDEV" ] && NODE="/dev/$CDEV"
    fi
fi

IS_STEALTH=0
if grep -qw "$MOD" /proc/modules 2>/dev/null; then
    ok "/proc/modules has $MOD (Standard Vendor Mode)"
elif [ -n "$NODE" ] || [ -n "$DEVNAME" ]; then
    IS_STEALTH=1
    ok "/proc/modules: module active in stealth mode (hidden)"
else
    bad "/proc/modules has no $MOD"
fi

if [ -d "/sys/module/$MOD" ]; then
    ok "/sys/module/$MOD exists"
elif [ "$IS_STEALTH" -eq 1 ]; then
    ok "/sys/module: kobject hidden (stealth mode)"
else
    bad "/sys/module/$MOD missing"
fi

if [ -n "$NODE" ] && [ -n "$DEVNAME" ]; then
    ok "device node $NODE"
    MAJOR=$(awk -v n="$DEVNAME" '$2 == n { print $1; exit }' /proc/devices 2>/dev/null)
    if [ -n "$MAJOR" ]; then
        ok "/proc/devices: major $MAJOR -> $DEVNAME"
    else
        bad "/proc/devices has no entry for $DEVNAME"
    fi
elif [ -n "$DEVNAME" ]; then
    MAJOR=$(awk -v n="$DEVNAME" '$2 == n { print $1; exit }' /proc/devices 2>/dev/null)
    if [ -n "$MAJOR" ]; then
        ok "/proc/devices: major $MAJOR -> $DEVNAME (driver resident in kernel)"
    else
        bad "/proc/devices has no entry for $DEVNAME"
    fi
else
    bad "no device node found under /sys/class + /dev"
fi

if [ -e /data/tmpf ]; then
    bad "/data/tmpf left behind (installer failed or was killed)"
else
    ok "/data/tmpf cleaned up"
fi

if [ "$FAIL" -eq 0 ]; then
    echo "RESULT: PASS"
    [ -n "$NODE" ] && echo "NODE=$NODE"
    exit 0
fi

echo "RESULT: FAIL ($FAIL check(s))"
exit 1
