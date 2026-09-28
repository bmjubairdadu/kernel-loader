#!/bin/bash
set -u
PROJ=/mnt/c/Users/Administrator/Downloads/DaisyDiverLoder
OUT=$PROJ/driver/out_all
ASSETS=$PROJ/app/src/main/assets/drivers
STATUS=$PROJ/driver/build_all_status.txt
LOGDIR=$PROJ/driver/logs
WORK=/root/kernel-all
TOOL=$WORK/toolchain
VFILE=$PROJ/driver/versions.txt
QUEUE=$WORK/queue.txt
LOCK=$WORK/queue.lock
mkdir -p "$OUT" "$LOGDIR" "$WORK"

log(){ echo "$(date +%m-%d\ %H:%M:%S) $*" >> "$STATUS"; }

export DEBIAN_FRONTEND=noninteractive
have_all=1
for t in bc bison flex curl xz git; do
  command -v "$t" >/dev/null 2>&1 || have_all=0
done
if [ "$have_all" -eq 1 ]; then
  log "SETUP: build deps already present - apt skipped"
else
  log "SETUP: installing build deps..."
  (apt-get update -y && apt-get install -y curl xz-utils bc bison flex libssl-dev libelf-dev git) >> $LOGDIR/deps.log 2>&1 \
    || log "SETUP: apt FAILED (continuing, deps may already exist)"
fi

if [ ! -x "$TOOL/gcc49/bin/aarch64-linux-android-gcc" ]; then
  log "TOOLCHAIN: cloning LineageOS gcc 4.9 (aarch64)..."
  git clone --depth=1 https://github.com/LineageOS/android_prebuilts_gcc_linux-x86_aarch64_aarch64-linux-android-4.9 "$TOOL/gcc49" >> $LOGDIR/toolchain.log 2>&1 \
    || log "TOOLCHAIN: gcc49 clone FAILED"
fi
if [ ! -x "$TOOL/proton/bin/clang" ] && ! command -v clang >/dev/null 2>&1; then
  log "TOOLCHAIN: no proton and no system clang - installing apt clang..."
  apt-get install -y clang lld >> $LOGDIR/deps.log 2>&1 || log "TOOLCHAIN: apt clang FAILED"
fi

tr -d '\r' < "$VFILE" > "$QUEUE"
touch "$LOCK"

build_one(){
  local VER=$1
  local MAJOR=${VER%%.*}
  local TARBALL=$WORK/linux-$VER.tar.xz
  local TREE=$WORK/linux-$VER

  [ -f "$OUT/uni_$VER.ko" ] && { log "SKIP-DONE  $VER"; return 0; }
  [ -f "$ASSETS/uni_$VER.ko" ] && { log "SKIP-DONE  $VER"; return 0; }

  if [ "$MAJOR" -eq 3 ]; then
    local MINOR3=${VER#3.}; MINOR3=${MINOR3%%.*}
    [ "$MINOR3" -lt 7 ] && { log "SKIP-NOARM64 $VER (mainline arm64 starts at 3.7)"; return 2; }
  fi

  local URL="https://cdn.kernel.org/pub/linux/kernel/v${MAJOR}.x/linux-$VER.tar.xz"

  if [ -f "$TARBALL" ] && ! xz -t "$TARBALL" >/dev/null 2>&1; then
    log "DISCARD    $VER (cached tarball corrupt/truncated - re-downloading)"
    rm -f "$TARBALL"
  fi
  if [ ! -f "$TARBALL" ]; then
    local try=0 CODE=000 CRES=""
    while [ "$try" -lt 4 ]; do
      try=$((try+1))
      CRES=""
      [ -s "$TARBALL.part" ] && CRES="--continue-at -"
      log "DOWNLOAD   $VER (attempt $try) ..."
      CODE=$(curl -L --fail --retry 3 --retry-delay 5 --connect-timeout 30 \
                  $CRES --max-time 5400 --speed-limit 1000 --speed-time 120 \
                  -o "$TARBALL.part" -w '%{http_code}' "$URL" 2>"$LOGDIR/dl-$VER.err")
      [ -z "$CODE" ] && CODE=000
      if [ -s "$TARBALL.part" ] && xz -t "$TARBALL.part" >/dev/null 2>&1; then
        mv -f "$TARBALL.part" "$TARBALL"
        log "GOT        $VER ($(stat -c%s "$TARBALL") bytes)"
        break
      fi
      case "$CODE" in
        404|403|410)
          log "SKIP-NOSRC $VER (kernel.org answers HTTP $CODE - release not on cdn)"
          rm -f "$TARBALL.part"
          return 2 ;;
      esac
      log "RETRY-DL   $VER (http=$CODE, kept $(stat -c%s "$TARBALL.part" 2>/dev/null || echo 0) bytes, resuming)"
      sleep 10
    done
    if [ ! -f "$TARBALL" ]; then
      log "FAIL-DL    $VER (incomplete after 4 attempts - partial kept so the next run resumes)"
      return 1
    fi
  fi

  rm -rf "$TREE"; mkdir -p "$TREE"
  tar -xf "$TARBALL" -C "$TREE" --strip-components=1 \
    || { log "FAIL       $VER (extract)"; rm -rf "$TREE"; return 1; }

  local MD=$WORK/mod-$VER
  rm -rf "$MD"; mkdir -p "$MD"
  sed "s/4\.9\.337/$VER/g; s/2\.0-337/2.0-${VER}/g" "$PROJ/driver/kloader_driver.c" > "$MD/kloader_driver.c"
  printf 'obj-m += kloader_driver.o\n' > "$MD/Makefile"

  local GCC49=$TOOL/gcc49/bin
  local PROTON=$TOOL/proton/bin

  [ -x "$PROTON/clang" ] || PROTON=/usr/bin

  try_toolchain(){
    local NAME=$1; shift
    local TLOG=$LOGDIR/$VER.$NAME.log
    local PATHPRE=""
    local -a VARS=()
    for a in "$@"; do
      case "$a" in
        PATHPRE=*) PATHPRE=${a#PATHPRE=} ;;
        *) VARS+=("$a") ;;
      esac
    done
    : > "$TLOG"
    ( export PATH="$PATHPRE"
      for v in "${VARS[@]}"; do export "$v"; done
      cd "$TREE"
      echo "--- defconfig ---"
      make ARCH=arm64 defconfig                      || exit 1

      ./scripts/config -d MODVERSIONS -d MODULE_SIG -d DEBUG_INFO_BTF -e MODULES 2>/dev/null || true
      make ARCH=arm64 olddefconfig                   || exit 1
      echo "--- modules_prepare ---"

      make ARCH=arm64 -j"$(nproc)" modules_prepare HOSTCFLAGS="-O2 -fcommon" || exit 1
      echo "--- module build ---"
      if ! make ARCH=arm64 M="$MD" modules HOSTCFLAGS="-O2 -fcommon"; then

        if grep -q "code model 'large' with -fpic" "$TLOG" 2>/dev/null; then
          echo "--- PIC RETRY: dropping ARM64 erratum + forcing -fno-pic ---"
          ./scripts/config -d ARM64_ERRATUM_843419 -d ARM64_ERRATUM_845719 2>/dev/null || true
          make ARCH=arm64 olddefconfig >/dev/null 2>&1 || true
          make ARCH=arm64 M="$MD" modules HOSTCFLAGS="-O2 -fcommon" KCFLAGS="-fno-pic -fno-pie" || exit 1
        else
          exit 1
        fi
      fi
      local MAGIC
      MAGIC=$(strings "$MD/kloader_driver.ko" | grep -m1 '^vermagic=')
      echo "VERMAGIC: $MAGIC"
      case "$MAGIC" in
        "vermagic=$VER "*)
          cp "$MD/kloader_driver.ko" "$OUT/uni_$VER.ko"
          cp "$MD/kloader_driver.ko" "$ASSETS/uni_$VER.ko"
          echo "BUILD-OK" ;;
        *) echo "VERMAGIC-MISMATCH (got '$MAGIC', want '$VER ...')"; exit 1 ;;
      esac
    ) >> "$TLOG" 2>&1
    grep -q '^BUILD-OK$' "$TLOG"
  }

  local built=0
  if [ "$MAJOR" -le 4 ]; then
    [ -x "$GCC49/aarch64-linux-android-gcc" ] && \
      try_toolchain gcc49 "PATHPRE=$GCC49:$PATH" CROSS_COMPILE=aarch64-linux-android- && built=1
    if [ $built -eq 0 ] && [ -x "$PROTON/clang" ]; then

      try_toolchain clang "PATHPRE=$PROTON:$GCC49:$PATH" CROSS_COMPILE=aarch64-linux-android- CLANG_TRIPLE=aarch64-linux-android- CC=clang && built=1
    fi
  else
    [ -x "$PROTON/clang" ] && \
      try_toolchain clang "PATHPRE=$PROTON:$GCC49:$PATH" CROSS_COMPILE=aarch64-linux-android- CC=clang && built=1
    if [ $built -eq 0 ] && [ -x "$GCC49/aarch64-linux-android-gcc" ]; then
      try_toolchain gcc49 "PATHPRE=$GCC49:$PATH" CROSS_COMPILE=aarch64-linux-android- && built=1
    fi
  fi

  rm -rf "$TREE"
  if [ $built -eq 1 ]; then
    log "OK-BUILD   $VER"
    rm -f "$TARBALL"
    return 0
  else
    log "FAIL-BUILD $VER (see logs)"
    return 1
  fi
}

worker(){
  while :; do
    local VER=""
    { flock -x 9
      VER=$(head -n 1 "$QUEUE" 2>/dev/null)
      [ -n "$VER" ] && sed -i 1d "$QUEUE"
    } 9> "$LOCK"
    [ -z "$VER" ] && break
    build_one "$VER"
  done
}

log "PIPELINE: starting 3 workers for $(wc -l < "$VFILE") versions"
for i in 1 2 3; do worker & done
wait
log "PIPELINE: DONE -> ko=$(ls "$OUT" 2>/dev/null | wc -l) built"
