#!/system/bin/sh
# KL-SigOff installer — Magisk / KernelSU / APatch

SKIPUNZIP=0

ui_print "**********************************************"
ui_print "   KL-SigOff: Universal Kernel Sig Bypass     "
ui_print "**********************************************"
ui_print ""

if [ "$ARCH" != "arm64" ]; then
  abort "ERROR: arm64 only (bundled binaries are arm64). Your arch: $ARCH"
fi

if [ "$API" -lt 26 ]; then
  ui_print "! WARNING: Android < 8.0 untested on this flow"
fi

# Ensure executable permissions on bundled binaries and scripts
chmod 0755 "$MODPATH/bin/magiskboot" 2>/dev/null
chmod 0755 "$MODPATH/bin/sigpatch" 2>/dev/null
chmod 0755 "$MODPATH/boot-patch.sh" 2>/dev/null
chmod 0755 "$MODPATH/service.sh" 2>/dev/null
chmod 0755 "$MODPATH/action.sh" 2>/dev/null

mkdir -p /data/adb/kl_sigoff
[ -f /data/adb/kl_sigoff/config ] || \
  printf 'AUTO_REBOOT=0\n' > /data/adb/kl_sigoff/config

ui_print "- Analyzing and patching boot image directly..."
ui_print ""

AUTO_REBOOT=0 MODDIR="$MODPATH" sh "$MODPATH/boot-patch.sh"
PATCH_RES=$?

ui_print ""
case $PATCH_RES in
  0)
    ui_print "[✓] SUCCESS: Module signature enforcement disabled!"
    ui_print "[✓] All unsigned .ko kernel drivers can now load."
    ui_print "[✓] Please REBOOT your device now to activate."
    ;;
  1)
    ui_print "[!] Pattern could not be determined automatically."
    ui_print "[!] service.sh will attempt background fallback on boot."
    ;;
  *)
    ui_print "[!] Patch deferred (exit code: $PATCH_RES)."
    ui_print "[!] Check /data/adb/kl_sigoff/log.txt for details."
    ;;
esac

ui_print ""
ui_print "- Installation complete."
