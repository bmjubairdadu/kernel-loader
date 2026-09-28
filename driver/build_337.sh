#!/bin/bash
set -e

KERNEL_DIR=${1:-$HOME/android_kernel_xiaomi_daisy}
TOOLCHAIN=${TOOLCHAIN:-$HOME/toolchain/gcc-4.9/bin/aarch64-linux-android-}
OUT_DIR=$(dirname $(readlink -f $0))/out

echo "=== DaisyForGaming 4.9.337 driver build ==="
echo "KERNEL_DIR=$KERNEL_DIR"

if [ ! -d "$KERNEL_DIR" ]; then
  echo "[!] Kernel source not found. Cloning..."
  mkdir -p $(dirname $KERNEL_DIR)

  git clone --depth=1 https://github.com/Xtended-Devices/kernel_xiaomi_daisy "$KERNEL_DIR"
fi

cd "$KERNEL_DIR"
export ARCH=arm64
export CROSS_COMPILE="$TOOLCHAIN"

if ! command -v ${CROSS_COMPILE}gcc >/dev/null 2>&1; then
  echo "[!] Toolchain not found at $TOOLCHAIN"
  echo "    Download: gcc-4.9 aarch64-linux-android (Google NDK r17c or LOS prebuilts)"
  echo "    Example:"
  echo "      git clone --depth=1 https://github.com/LineageOS/android_prebuilts_gcc_linux-x86_aarch64_aarch64-linux-android-4.9 ~/toolchain/gcc-4.9"
  echo "      export TOOLCHAIN=~/toolchain/gcc-4.9/bin/aarch64-linux-android-"
  exit 1
fi

echo "[*] Kernel version: $(make kernelversion)"
echo "[*] daisy_defconfig..."
make daisy_defconfig
make -j$(nproc) modules_prepare

echo "[*] Building driver module..."
SCRIPT_DIR=$(dirname $(readlink -f $0))
make -C "$KERNEL_DIR" M="$SCRIPT_DIR" ARCH=arm64 CROSS_COMPILE="$TOOLCHAIN" modules

mkdir -p "$OUT_DIR"
cp "$SCRIPT_DIR/kloader_driver.ko" "$OUT_DIR/native_4.9.337.ko"
echo "[*] modinfo:"
modinfo "$OUT_DIR/native_4.9.337.ko" | grep -E "vermagic|version|description" || strings "$OUT_DIR/native_4.9.337.ko" | grep -a -i -m 3 vermagic

echo ""
echo "=== DONE ==="
echo "Output: $OUT_DIR/native_4.9.337.ko"
echo "Copy to phone: adb push $OUT_DIR/native_4.9.337.ko /sdcard/"
echo "Then in Daisy Driver Loader pick + insmod (no -f needed if vermagic=4.9.337)"
