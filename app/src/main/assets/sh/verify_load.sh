#!/system/bin/sh
MOD="${1:-suvhwvwrtnb}"
FAIL=0

ok()   { echo "PASS: $1"; }
bad()  { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }

echo "module : $MOD"
echo "kernel : $(uname -r)"
echo "root   : $(id -u)"

CLSNAME=""
DEVNAME=""
NODE=""
SYSDEV=""

for d in /sys/class/*/; do
    [ -d "$d" ] || continue
    cls=$(basename "$d")
    case "$cls" in
        android_usb|avtimer|backlight|battery*|bdi|block|bluetooth|bt*|char|charger*|devfreq|diag|dma|drm|dual_role_usb|dvb|extcon|fastrng|fastrpc|fingerprint|firmware|fuse|goodix*|gpio|graphics|hidraw|hwmon|i2c*|icesdcc|ieee*|input|iommu|ion|ipa*|jpeg*|kgsl|kmsg|lcd*|leds|lirc|mdio*|mem|misc|mmc*|msm*|net|phy|pmsg|power*|ppp|pwm|qg*|qpnp*|qseecom|ramdump|rc|regulator|remoteproc|rfkill|rmnet*|rotator|rpmsg|rtc|scsi*|sensors|smb*|smd*|soc*|sound|spidev|spmi*|ssc|subsys*|switch|tee|thermal|tty*|typec|uhid|uinput|uio|usb*|vc|video*|virtio*|vtconsole|wakeup|watchdog*|wcnss*|wwan*|zram*|BaseRemoteCtl|Vcodec*|vcodec*|accdet*|mrdump*|ndctl*|dimm*|dpa*)
            continue
            ;;
    esac
    case "$cls" in
        [A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9])
            if [ -c "/dev/$cls" ]; then
                CLSNAME="$cls"
                DEVNAME="$cls"
                NODE="/dev/$cls"
                SYSDEV="/sys/class/$cls/$cls/dev"
                break
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
                    SYSDEV="/sys/class/$cls/$c/dev"
                    break 2
                fi
                ;;
        esac
    done
done

if [ -z "$DEVNAME" ]; then
    CDEV=$(awk '/Character devices:/, /Block devices:/ { if ($1 ~ /^[0-9]+$/ && $2 ~ /^[A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9]$/ && $2 !~ /^(mem|msm|tty|misc|input|sg|fb|video|ch|i2c|ppp|alsa|ptm|pts|spi|usb|DVB|adsprpc|avtimer|dts|sensors|hidraw|bt|jpeg|BaseRemote|goodix|rmnet|icesdcc|qseecom|kgsl|frandom|diag|smd|wcnss|subsys|ramdump|mdss|ipa|iio|media|rtc|pmsg|gpio|uio|wwan|rfkill|extcon|switch|spidev|Vcodec|vcodec|accdet|mrdump|ndctl|dimm|dpa)/) print $2 }' /proc/devices 2>/dev/null | tail -n 1)
    if [ -n "$CDEV" ]; then
        DEVNAME="$CDEV"
        [ -c "/dev/$CDEV" ] && NODE="/dev/$CDEV"
    fi
fi

OPEN_FD=""
OPEN_NODE=""
OWNER_PID=""
if [ -z "$NODE" ] && [ -z "$DEVNAME" ]; then
    FD_HIT=$(ls -l /proc/[0-9]*/fd 2>/dev/null | awk '/^\/proc\/[0-9]+\/fd:/ { pid=$1; sub(/^\/proc\//,"",pid); sub(/\/fd:$/,"",pid); next } / -> \/dev\/[A-Za-z0-9]{6} \(deleted\)$/ { fd=$(NF-3); dev=$(NF-1); sub(/^\/dev\//,"",dev); if (dev !~ /^(binder|common|ashmem|random)$/) { print pid "|" fd "|" dev; exit } }')
    if [ -n "$FD_HIT" ]; then
        OLD_IFS="$IFS"
        IFS='|'
        set -- $FD_HIT
        OWNER_PID="${1:-}"
        FD_NUM="${2:-}"
        DEVNAME="${3:-}"
        IFS="$OLD_IFS"
        if [ -n "$DEVNAME" ]; then
            OPEN_FD="/proc/$OWNER_PID/fd/$FD_NUM"
            OPEN_NODE="/dev/$DEVNAME"
            NODE="$OPEN_NODE"
        fi
    fi
fi

IS_STEALTH=0
if grep -qw "$MOD" /proc/modules 2>/dev/null; then
    ok "/proc/modules has $MOD (Standard Vendor Mode)"
elif [ -n "$OPEN_FD" ] || [ -n "$NODE" ] || [ -n "$DEVNAME" ]; then
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

MAJOR=""
if [ -n "$DEVNAME" ]; then
    MAJOR=$(awk -v n="$DEVNAME" '$2 == n { print $1; exit }' /proc/devices 2>/dev/null)
    if [ -z "$MAJOR" ] && [ -n "$SYSDEV" ] && [ -r "$SYSDEV" ]; then
        MAJOR=$(cut -d: -f1 "$SYSDEV" 2>/dev/null)
    fi
    if [ -z "$MAJOR" ] && [ -r "/sys/devices/virtual/$DEVNAME/$DEVNAME/dev" ]; then
        MAJOR=$(cut -d: -f1 "/sys/devices/virtual/$DEVNAME/$DEVNAME/dev" 2>/dev/null)
    fi
    if [ -z "$MAJOR" ] && [ -c "$NODE" ]; then
        HEX_MAJ=$(stat -c %t "$NODE" 2>/dev/null)
        if [ -n "$HEX_MAJ" ]; then
            MAJOR=$(printf "%d" "0x$HEX_MAJ" 2>/dev/null)
        fi
    fi
fi

if [ -n "$OPEN_FD" ]; then
    ok "device node $OPEN_NODE active in use ($OPEN_FD)"
elif [ -n "$NODE" ] && [ -n "$DEVNAME" ]; then
    ok "device node $NODE"
    if [ -n "$MAJOR" ]; then
        if grep -qw "$DEVNAME" /proc/devices 2>/dev/null; then
            ok "/proc/devices: major $MAJOR -> $DEVNAME"
        else
            ok "/sys/class: major $MAJOR -> $DEVNAME (stealth /proc/devices hidden)"
        fi
    else
        bad "no major number resolved for $DEVNAME"
    fi
elif [ -n "$DEVNAME" ]; then
    if [ -n "$MAJOR" ]; then
        ok "/proc/devices: major $MAJOR -> $DEVNAME (driver resident in kernel)"
    else
        bad "/proc/devices has no entry for $DEVNAME"
    fi
else
    bad "no device node found under /sys/class, /dev, or open fds"
fi

KM_LEFT=""
for km in /data/.km_*; do
    [ -e "$km" ] && KM_LEFT="$km" && break
done

if [ -e /data/tmpf ] || [ -n "$KM_LEFT" ] || [ -e /dev/.kloder_session ]; then
    bad "temp loader file left behind"
else
    ok "temp loader files cleaned up"
fi

if [ "$FAIL" -eq 0 ]; then
    echo "RESULT: PASS"
    [ -n "$NODE" ] && echo "NODE=$NODE"
    echo "STEALTH=$IS_STEALTH"
    if [ -n "$OPEN_FD" ]; then
        echo "IN_USE=1"
        [ -n "$OWNER_PID" ] && echo "OWNER_PID=$OWNER_PID"
    else
        echo "IN_USE=0"
    fi
    exit 0
fi

echo "RESULT: FAIL ($FAIL check(s))"
exit 1
