#!/system/bin/sh
# KL-SigOff core patcher — dump boot, hexpatch kernel sig-enforce, repack, flash back.
# Callable standalone (root shell) or from customize.sh / service.sh / action.sh.
# Exit codes: 0=patched or already-done, 1=no pattern matched, 2+ = infrastructure failure

MODDIR="${MODDIR:-/data/adb/modules/kl_sigoff}"
BASE=/data/adb/kl_sigoff
WORK="$BASE/work"
STATE="$BASE/state"
LOG="$BASE/log.txt"

log() { echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"; }

mkdir -p "$BASE" "$WORK"

# busybox (sha256sum etc.) from whichever root manager is installed
for bb in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
  if [ -x "$bb" ]; then PATH="$bb:$PATH"; export PATH; break; fi
done

# ---------- config (override in /data/adb/kl_sigoff/config) ----------
AUTO_REBOOT=1
[ -f "$BASE/config" ] && . "$BASE/config"

# ---------- locate boot partition (slot-aware, universal) ----------
slot=$(getprop ro.boot.slot_suffix 2>/dev/null)
[ -z "$slot" ] && slot=$(grep -o 'androidboot.slot_suffix=[^ ]*' /proc/cmdline 2>/dev/null | cut -d= -f2)
if [ -z "$slot" ]; then
  s=$(getprop ro.boot.slot 2>/dev/null)
  [ -n "$s" ] && slot="_$s"
fi

find_boot() {
  for name in "boot$slot" "boot" "boot_a" "boot_b"; do
    for dir in /dev/block/by-name \
               /dev/block/bootdevice/by-name \
               /dev/block/platform/*/by-name \
               /dev/block/platform/*/*/by-name \
               /dev/block/mapper; do
      if [ -b "$dir/$name" ]; then
        readlink -f "$dir/$name" 2>/dev/null || echo "$dir/$name"
        return 0
      fi
    done
  done

  for b in /sys/class/block/*; do
    uevent="$b/uevent"
    if [ -f "$uevent" ]; then
      if grep -q -E "PARTNAME=boot$slot$|PARTNAME=boot$" "$uevent" 2>/dev/null; then
        devname=$(basename "$b")
        if [ -b "/dev/block/$devname" ]; then
          echo "/dev/block/$devname"
          return 0
        fi
      fi
    fi
  done

  dev=$(find /dev/block -name "boot$slot" -o -name "boot" 2>/dev/null | head -n 1)
  if [ -n "$dev" ] && [ -b "$dev" ]; then
    readlink -f "$dev" 2>/dev/null || echo "$dev"
    return 0
  fi

  return 1
}

BOOT_DEV=$(find_boot)
if [ -z "$BOOT_DEV" ] || [ ! -b "$BOOT_DEV" ]; then
  log "ERR BOOT_DEV_NOT_FOUND"
  echo "BOOT_DEV_NOT_FOUND"
  exit 2
fi

echo "- Target boot partition: $BOOT_DEV"

# ---------- locate magiskboot (module copy first, then root-manager copies) ----------
MB=""
for b in "$MODDIR/bin/magiskboot" \
         /data/adb/magisk/magiskboot \
         /data/adb/ksu/bin/magiskboot \
         /data/adb/ap/bin/magiskboot; do
  if [ -x "$b" ]; then MB="$b"; break; fi
done
if [ -z "$MB" ] && [ -f "$MODDIR/bin/magiskboot" ]; then
  chmod 0755 "$MODDIR/bin/magiskboot" 2>/dev/null
  [ -x "$MODDIR/bin/magiskboot" ] && MB="$MODDIR/bin/magiskboot"
fi
if [ -z "$MB" ]; then log "ERR MAGISKBOOT_NOT_FOUND"; echo "MAGISKBOOT_NOT_FOUND"; exit 3; fi

SIGPATCH="$MODDIR/bin/sigpatch"
[ -x "$SIGPATCH" ] || chmod 0755 "$SIGPATCH" 2>/dev/null

# ---------- dump current boot ----------
cd "$WORK" || exit 4
rm -f boot.img kernel kernel_dtb ramdisk.cpio new-boot.img
echo "- Dumping boot partition..."
dd if="$BOOT_DEV" of="$WORK/boot.img" bs=4096 2>/dev/null
if [ ! -s boot.img ]; then log "ERR DUMP_FAILED ($BOOT_DEV)"; echo "DUMP_FAILED"; exit 4; fi

CUR_HASH=$(sha256sum boot.img | cut -d' ' -f1)

# already-patched guards: same image we flashed before
if grep -q "patched_boot_sha256=$CUR_HASH" "$STATE" 2>/dev/null; then
  log "SKIP already patched (state hash match)"
  echo "- Kernel already patched (hash match)."
  echo "ALREADY_PATCHED"
  exit 0
fi

# ---------- unpack ----------
echo "- Unpacking kernel image..."
"$MB" unpack boot.img >/dev/null 2>&1
if [ ! -f kernel ]; then log "ERR UNPACK_FAILED"; echo "UNPACK_FAILED"; exit 5; fi

# ---------- patch kernel signature enforcement ----------
KSUM=$(sha256sum kernel | cut -d' ' -f1)
PATCHED=0

# Try 3-tier native dynamic engine first (Tier 1 table + Tier 2 leaf + Tier 3 entry)
if [ -x "$SIGPATCH" ]; then
  echo "- Running 3-Tier Dynamic ARM64 Patcher..."
  out=$("$SIGPATCH" kernel 2>&1)
  eng=$?
  now=$(sha256sum kernel | cut -d' ' -f1)
  if [ "$now" != "$KSUM" ]; then
    PATCHED=1
    log "PATCHED via sigpatch engine: $out"
    echo "$out"
    KSUM=$now
  elif [ "$eng" = "0" ]; then
    log "ENGINE report: $out"
    echo "$out"
    echo "ALREADY_PATCHED"
    exit 0
  fi
fi

# Fallback: known per-build patterns via magiskboot hexpatch
if [ "$PATCHED" = "0" ]; then
  P="00008052c0035fd61f2003d5"

  try_pattern() {
    "$MB" hexpatch kernel "$2" "$3" >/dev/null 2>&1
    now=$(sha256sum kernel | cut -d' ' -f1)
    if [ "$now" != "$KSUM" ]; then
      PATCHED=1
      log "PATCHED $1 ($2)"
      echo "- Matched known pattern: $1"
      KSUM=$now
    fi
  }

  try_pattern "OnePlus-CPH2381-5.4.254"     4841019000c14339c0035fd6 "$P"
  try_pattern "Realme-RMX3511-5.4.210"      e8d000b000614239c0035fd6 "$P"
  try_pattern "Infinix-X6525-5.4.210"       c80601f000415039c0035fd6 "$P"
  try_pattern "OnePlus-IN2021-4.19.157"     2835019000816c39c0035fd6 "$P"
  try_pattern "Realme-RMX3195-4.19.191"     a8f6009000817439c0035fd6 "$P"
  try_pattern "Samsung-SM-A235F-4.19.157"   c839019000c15739c0035fd6 "$P"
  try_pattern "Xiaomi-M2010J19CG-4.19.157"  c8f000d000614739c0035fd6 "$P"
  try_pattern "Realme-RMX5070-4.14.357"     0828019000214c39c0035fd6 "$P"
  try_pattern "Realme-RMX3241-4.19.191+"    281a01b000817f39c0035fd6 "$P"
  try_pattern "Xiaomi-Redmi7-4.9.186-branch" 82030035c00640f91f7000f1 23000014c00640f91f7000f1
fi

if [ "$PATCHED" = "0" ]; then
  log "ERR PATTERN_NOT_FOUND kernel=${KSUM:0:12}"
  echo "PATTERN_NOT_FOUND"
  exit 1
fi

# ---------- repack + flash ----------
echo "- Repacking boot image..."
"$MB" repack boot.img >/dev/null 2>&1
if [ ! -s new-boot.img ]; then log "ERR REPACK_FAILED"; echo "REPACK_FAILED"; exit 6; fi

# size sanity: new image must fit the boot partition
part_sectors=$(cat "/sys/class/block/$(basename "$BOOT_DEV")/size" 2>/dev/null)
if [ -n "$part_sectors" ] && [ "$part_sectors" -gt 0 ] 2>/dev/null; then
  part_bytes=$((part_sectors * 512))
  img_bytes=$(wc -c < new-boot.img)
  if [ "$img_bytes" -gt "$part_bytes" ]; then
    log "ERR IMAGE_TOO_BIG img=$img_bytes part=$part_bytes — not flashing"
    echo "IMAGE_TOO_BIG"
    exit 7
  fi
fi

echo "- Flashing patched boot image to $BOOT_DEV..."
blockdev --setrw "$BOOT_DEV" 2>/dev/null
chmod 660 "$BOOT_DEV" 2>/dev/null
dd if=new-boot.img of="$BOOT_DEV" bs=4096 2>"$WORK/flash_err.txt"
FLASH_RC=$?
if [ $FLASH_RC -ne 0 ]; then
  ERR_TXT=$(cat "$WORK/flash_err.txt" 2>/dev/null | tr '\n' ' ')
  log "ERR FLASH_WRITE_FAILED: $ERR_TXT"
  echo "FLASH_WRITE_FAILED: $ERR_TXT"
  cp -f new-boot.img /sdcard/patched_boot.img 2>/dev/null
  echo "- Saved fallback to /sdcard/patched_boot.img"
  exit 8
fi
sync

# Strict readback verification
NEW_SIZE=$(wc -c < new-boot.img)
NEW_HASH=$(sha256sum new-boot.img | cut -d' ' -f1)

COUNT=$(( (NEW_SIZE + 4095) / 4096 ))
dd if="$BOOT_DEV" of="$WORK/readback.img" bs=4096 count="$COUNT" 2>/dev/null
head -c "$NEW_SIZE" "$WORK/readback.img" > "$WORK/readback_exact.img" 2>/dev/null || dd if="$WORK/readback.img" of="$WORK/readback_exact.img" bs=1 count="$NEW_SIZE" 2>/dev/null

READ_HASH=$(sha256sum "$WORK/readback_exact.img" | cut -d' ' -f1)

if [ "$NEW_HASH" != "$READ_HASH" ]; then
  log "ERR FLASH_VERIFY_FAILED: new=$NEW_HASH read=$READ_HASH (OEM block write protection)"
  echo "FLASH_VERIFY_FAILED: boot block device write-protected"
  cp -f new-boot.img /sdcard/patched_boot.img 2>/dev/null
  echo "- Saved fallback to /sdcard/patched_boot.img"
  exit 9
fi

{
  echo "patched_boot_sha256=$NEW_HASH"
  echo "boot_dev=$BOOT_DEV"
  echo "patched_at=$(date '+%Y-%m-%d %H:%M:%S')"
} >> "$STATE"
mkdir -p /data/adb/kernel_loader 2>/dev/null
echo "sig_unlocked=true" > /data/adb/kernel_loader/status.prop 2>/dev/null
echo "patched_boot_sha256=$NEW_HASH" >> /data/adb/kernel_loader/status.prop 2>/dev/null
log "OK flashed patched boot to $BOOT_DEV sha=$NEW_HASH"
echo "PATCH_SUCCESS"

# reboot once so the patched kernel actually loads (disable: AUTO_REBOOT=0 in config)
if [ "$AUTO_REBOOT" = "1" ]; then
  log "reboot scheduled in 20s (AUTO_REBOOT=1)"
  (sleep 20; reboot) &
fi
exit 0
