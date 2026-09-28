#!/usr/bin/env bash
set -euo pipefail
F=/root/build_all.sh
[ -f "$F" ] || { echo "ERROR: $F missing"; exit 1; }

sed -i 's|cp "$MD/kloader_driver.ko" "$ASSETS/uni_$VER.ko"|# lightweight APK: publish to GitHub instead (publish_drivers.sh)|' "$F"

sed -i 's|\[ -f "$ASSETS/uni_$VER.ko" \]|[ -f "$OUT/uni_$VER.ko" ]|' "$F"

echo "--- patched lines ---"
grep -n 'ASSETS\|uni_$VER.ko' "$F" | head -10
