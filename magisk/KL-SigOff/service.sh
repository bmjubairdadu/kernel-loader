#!/system/bin/sh
# KL-SigOff — runs at late_start service stage on every boot.
# Patches the boot image once (or again after an OTA changed it), then reboots.
# All logic lives in boot-patch.sh; keep this thin.

MODDIR=${0%/*}

# wait for sys.boot_completed
i=0
while [ $i -lt 30 ]; do
  [ "$(getprop sys.boot_completed)" = "1" ] && break
  sleep 2
  i=$((i+1))
done

# short boot-battery guard: don't reboot-loop on a nearly dead phone
if [ -f /sys/class/power_supply/battery/capacity ]; then
  cap=$(cat /sys/class/power_supply/battery/capacity 2>/dev/null)
  case "$cap" in
    ''|*[!0-9]*) cap=100 ;;
  esac
  if [ "$cap" -lt 15 ]; then
    echo "$(date '+%m-%d %H:%M:%S') SKIP battery low ($cap%) — will retry next boot" \
      >> /data/adb/kl_sigoff/log.txt
    exit 0
  fi
fi

sh "$MODDIR/boot-patch.sh" >/dev/null 2>&1 &
