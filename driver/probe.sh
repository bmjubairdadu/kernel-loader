#!/bin/bash
{
  echo "=== DISK ==="; df -h /
  echo "=== CPU/RAM ==="; nproc; free -g | head -2
  echo "=== TOOLS ==="
  for t in curl xz tar make gcc bc flex bison python3 perl git; do
    printf "%-8s: %s\n" "$t" "$(command -v $t || echo MISSING)"
  done
  echo "=== CLANG SEARCH ==="
  for p in /opt/proton-clang/bin/clang ~/proton-clang/bin/clang /usr/lib/llvm-*/bin/clang /usr/bin/clang; do
    [ -x $p ] && echo "FOUND: $p"
  done
  find /home /opt /usr/local -maxdepth 3 -name "aarch64-linux-android-gcc" -o -maxdepth 3 -name "clang" -type f 2>/dev/null | head -5
  echo "=== /mnt/c SPACE ==="; df -h /mnt/c | tail -1
  echo "=== PROBE DONE ==="
} > /mnt/c/Users/Administrator/Downloads/DaisyDiverLoder/driver/probe_out.txt 2>&1
