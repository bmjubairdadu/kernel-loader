#!/bin/bash
set -e
SRC=~/daisy-build/kernel_source
T307=~/daisy-build/kernel_307
TC=~/daisy-build/toolchain/proton-clang/bin
PROJ=/mnt/c/Users/Administrator/Downloads/DaisyDiverLoder
OUT=$PROJ/driver/out
ASSETS=$PROJ/app/src/main/assets/drivers
SRC_C=$PROJ/driver/kloader_driver.c
export PATH=$TC:$PATH
BUILD_ARGS="O=out ARCH=arm64 CLANG_TRIPLE=aarch64-linux-gnu- CROSS_COMPILE=aarch64-linux-gnu- CROSS_COMPILE_ARM32=arm-linux-gnueabi- CC=clang"

echo "===== [1] 4.9.337 build ====="
REL=$(cat "$SRC/out/include/config/kernel.release")
echo "kernel.release(337) = $REL"
M337=/home/jubair/ko-build
rm -rf $M337 && mkdir -p $M337
cp "$SRC_C" $M337/kloader_driver.c
printf 'obj-m += kloader_driver.o\n' > $M337/Makefile
make -C "$SRC" $BUILD_ARGS M=$M337 modules
mkdir -p "$OUT" "$ASSETS"
cp $M337/kloader_driver.ko "$OUT/native_4.9.337.ko"
cp $M337/kloader_driver.ko "$ASSETS/native_4.9.337.ko"
modinfo "$OUT/native_4.9.337.ko" | grep -E 'filename|version|description|author|vermagic' || true

echo "===== [2] 4.9.307 source tree ====="
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
make -C "$T307" $BUILD_ARGS -j$(nproc) modules_prepare

echo "===== [3] 4.9.307 build ====="
echo "kernel.release(307) = $(cat $REL307)"
M307=/home/jubair/ko-build-307
rm -rf $M307 && mkdir -p $M307
sed 's/4\.9\.337/4.9.307/g; s/2\.0-337/2.0-307/g' "$SRC_C" > $M307/kloader_driver.c
printf 'obj-m += kloader_driver.o\n' > $M307/Makefile
make -C "$T307" $BUILD_ARGS M=$M307 modules
cp $M307/kloader_driver.ko "$OUT/native_4.9.307.ko"
cp $M307/kloader_driver.ko "$ASSETS/native_4.9.307.ko"
modinfo "$OUT/native_4.9.307.ko" | grep -E 'filename|version|description|author|vermagic' || true

echo "===== ALL DONE ====="
ls -la "$OUT"
ls -la "$ASSETS"
