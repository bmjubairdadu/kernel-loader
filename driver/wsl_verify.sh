#!/bin/bash
OUT=/mnt/c/Users/Administrator/Downloads/DaisyDiverLoder/_chk5.txt
PROJ=/mnt/c/Users/Administrator/Downloads/DaisyDiverLoder
{
  echo "---LOG 307 SECTION---"
  grep -n -A6 '\[2\] 4.9.307' /tmp/native.log | head -30
  echo "---LOG 307 MODINFO---"
  grep -n -A6 'Building modules, stage 2' /tmp/native.log | tail -12
  echo "---LOG TAIL---"
  tail -6 /tmp/native.log
  echo "---out/native_4.9.337.ko---"
  modinfo $PROJ/driver/out/native_4.9.337.ko | grep -E 'filename|version|description|author|vermagic|name'
  echo "---out/native_4.9.307.ko---"
  modinfo $PROJ/driver/out/native_4.9.307.ko | grep -E 'filename|version|description|author|vermagic|name'
  echo "---assets---"
  ls -la $PROJ/app/src/main/assets/drivers/native_4.9.337.ko $PROJ/app/src/main/assets/drivers/native_4.9.307.ko
  echo "---dev node tags in .ko---"
  strings $PROJ/driver/out/native_4.9.337.ko | grep -E 'kloaderctl|KernelLoder|Kernel Loder' | head -5
} > "$OUT" 2>&1
echo verified
