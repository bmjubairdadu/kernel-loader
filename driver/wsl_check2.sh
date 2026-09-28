#!/bin/bash
OUT=/mnt/c/Users/Administrator/Downloads/DaisyDiverLoder/_chk2.txt
{
  echo "---SCRIPT---"
  ls -la /home/jubair/build_native.sh /tmp/native.log 2>&1
  echo "---LOG(HEAD)---"
  head -12 /tmp/native.log 2>&1
  echo "---LOG(TAIL)---"
  tail -25 /tmp/native.log 2>&1
  echo "---RUNNING---"
  pgrep -af make
  echo "---LAUNCHER---"
  pgrep -af build_native
  echo "---KO---"
  ls -la /mnt/c/Users/Administrator/Downloads/DaisyDiverLoder/driver/out/native_4.9.307.ko /mnt/c/Users/Administrator/Downloads/DaisyDiverLoder/driver/out/native_4.9.337.ko 2>&1
} > "$OUT" 2>&1
echo written
