#!/bin/bash
PROJ=/mnt/c/Users/Administrator/Downloads/DaisyDiverLoder
LOG=$PROJ/_chk3.txt
TC=/home/jubair/daisy-build/toolchain/proton-clang/bin
export PATH=$TC:$PATH
BUILD_ARGS="O=out ARCH=arm64 CLANG_TRIPLE=aarch64-linux-gnu- CROSS_COMPILE=aarch64-linux-gnu- CROSS_COMPILE_ARM32=arm-linux-gnueabi- CC=clang"
M=/home/jubair/ko-build

{
  echo "---DISK---"
  df -h /home /tmp
  echo "---CLANG---"
  which clang; clang --version 2>&1 | head -2
  echo "---MDIR---"
  ls -la $M 2>&1 | head -20
  echo "---COPY SOURCE---"
  cp $PROJ/driver/kloader_driver.c $M/kloader_driver.c && echo copied
  printf 'obj-m += kloader_driver.o\n' > $M/Makefile
  cat $M/Makefile
  echo "---BUILD---"
  cd $M
  make -C /home/jubair/daisy-build/kernel_source $BUILD_ARGS M=$M V=1 modules 2>&1 | tail -40
  echo "---RESULT---"
  ls -la $M/kloader_driver.ko 2>&1
} > "$LOG" 2>&1
echo done
