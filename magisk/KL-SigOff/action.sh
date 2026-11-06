#!/system/bin/sh
# Action button in Magisk app — run/check the patch on demand (needs root, shows result)
MODDIR=${0%/*}

echo "- KL-SigOff manual run"
echo ""
sh "$MODDIR/boot-patch.sh"
rc=$?
echo ""
case $rc in
  0) echo "- OK: patched (or already patched). Check log:" ;;
  1) echo "- No pattern matched this kernel build." ;;
  *) echo "- Infrastructure failure (code $rc). See log:" ;;
esac
echo "  /data/adb/kl_sigoff/log.txt"
exit 0
