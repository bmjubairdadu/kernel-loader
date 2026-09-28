#!/bin/bash
exec > /mnt/c/Users/Administrator/Downloads/DaisyDiverLoder/driver/b307.log 2>&1

set -e
SRC=~/daisy-build/kernel_source
T307=~/daisy-build/kernel_307
TC=~/daisy-build/toolchain/proton-clang/bin
OUT=/mnt/c/Users/Administrator/Downloads/DaisyDiverLoder/driver/out
export PATH=$TC:$PATH
BUILD_ARGS="O=out ARCH=arm64 CLANG_TRIPLE=aarch64-linux-gnu- CROSS_COMPILE=aarch64-linux-gnu- CROSS_COMPILE_ARM32=arm-linux-gnueabi- CC=clang"

echo "===== [1] Preparing 4.9.307 source tree ====="
REL307="$T307/out/include/config/kernel.release"
if [ -d "$T307" ] && [ -f "$REL307" ] && grep -q '^4\.9\.307' "$REL307"; then
  echo "Reusing existing prepared 307 tree"
else
  rm -rf "$T307"
  mkdir -p "$T307"
  tar -C "$SRC" --exclude=./out --exclude=./.git -cf - . | tar -C "$T307" -xf -
  sed -i 's/^SUBLEVEL = .*/SUBLEVEL = 307/' "$T307/Makefile"
  grep -E '^(VERSION|PATCHLEVEL|SUBLEVEL)' "$T307/Makefile" | head -3
fi

mkdir -p "$T307/out"
if [ -f "$SRC/out/.config" ]; then
  cp "$SRC/out/.config" "$T307/out/.config"
  make -C "$T307" $BUILD_ARGS olddefconfig
else
  make -C "$T307" $BUILD_ARGS daisy_defconfig
fi

echo "===== [2] modules_prepare for 307 ====="
make -C "$T307" $BUILD_ARGS -j$(nproc) modules_prepare

echo "===== [3] Building kloader_driver.ko for 4.9.307 ====="
rm -rf ~/ko-build-307 && mkdir -p ~/ko-build-307
sed 's/4\.9\.337/4.9.307/g; s/1\.1-337/1.1-307/g' \
  /mnt/c/Users/Administrator/Downloads/DaisyDiverLoder/driver/kloader_driver.c \
  > ~/ko-build-307/kloader_driver.c
printf 'obj-m += kloader_driver.o\n' > ~/ko-build-307/Makefile
make -C "$T307" $BUILD_ARGS M=/home/jubair/ko-build-307 modules

echo "kernel.release = $(cat $T307/out/include/config/kernel.release)"
modinfo ~/ko-build-307/kloader_driver.ko | grep -E 'vermagic|version' || \
  strings ~/ko-build-307/kloader_driver.ko | grep -a -m 1 vermagic
mkdir -p "$OUT"
cp ~/ko-build-307/kloader_driver.ko "$OUT/native_4.9.307.ko"
echo "COPIED: $OUT/native_4.9.307.ko"

echo "===== [4] Refresh 4.9.337 build ====="
make -C "$SRC" $BUILD_ARGS M=/home/jubair/ko-build modules
cp /home/jubair/ko-build/kloader_driver.ko "$OUT/native_4.9.337.ko"
modinfo "$OUT/native_4.9.337.ko" | grep -E 'vermagic|version' || true
echo "COPIED: $OUT/native_4.9.337.ko"

echo "===== ALL DONE ====="
ls -la "$OUT"
