#!/bin/bash
set -e
KSRC=/home/jubair/daisy-build/kernel_source
KOUT=/home/jubair/daisy-build/kernel_source/out
M=/home/jubair/ko-build
export ARCH=arm64
export PATH=/home/jubair/daisy-build/toolchain/proton-clang/bin:$PATH
echo "kernel.release=$(cat $KOUT/include/config/kernel.release)"
cp /mnt/c/Users/Administrator/Downloads/DaisyDiverLoder/driver/kloader_driver.c $M/
printf 'obj-m += kloader_driver.o\n' > $M/Makefile
make -C $KSRC O=$KOUT M=$M ARCH=arm64 CLANG_TRIPLE=aarch64-linux-gnu- CROSS_COMPILE=aarch64-linux-gnu- CROSS_COMPILE_ARM32=arm-linux-gnueabi- CC=clang modules
echo "---- modinfo ----"
modinfo $M/kloader_driver.ko | grep -E "vermagic|version|description|author" || true
cp $M/kloader_driver.ko /mnt/c/Users/Administrator/Downloads/DaisyDiverLoder/driver/out/native_4.9.337.ko
echo "COPIED to driver/out/native_4.9.337.ko"
