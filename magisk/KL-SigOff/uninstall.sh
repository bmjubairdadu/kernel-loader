#!/system/bin/sh
# KL-SigOff uninstall — remove state/work dir.
# NOTE: the flashed boot image stays patched. To fully revert, reflash the
# stock boot.img of your current build via fastboot.
rm -rf /data/adb/kl_sigoff
exit 0
