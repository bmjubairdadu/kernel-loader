#!/system/bin/sh
KO_ARG="$1"
SCRIPT_DIR="$(dirname "$0")"
KO="${KO_ARG:-$SCRIPT_DIR/kmem_4.9.337-DaisyForGaming.ko}"

if [ "$(id -u)" != "0" ]; then
  echo "ERROR: root proyojon. su diye root hoye chalao." 1>&2
  exit 1
fi

KERNEL="$(uname -r)"
echo "PHONE KERNEL : $KERNEL"
echo "DRIVER FILE  : $KO"

if [ ! -f "$KO" ]; then
  echo "ERROR: .ko file pawa jay ni: $KO" 1>&2
  exit 1
fi

VERMAGIC="$(strings "$KO" | grep -a -m1 '^vermagic=' | sed 's/^vermagic=//')"
echo "KO VERMAGIC  : $VERMAGIC"
KO_REL="${VERMAGIC%% *}"

if [ "$KO_REL" != "$KERNEL" ]; then
  echo "WARN: vermagic milche na (.ko=$KO_REL vs phone=$KERNEL)"
  echo "      mismatch hole plain insmod fail korbe - setai safe."
  echo "      force-load sudhu same series (4.9.x) + stable kernel e hobe."
fi

if dmesg 2>/dev/null | tail -n 150 | grep -i -E -q 'kernel panic|oops:|unable to handle|call trace|softlockup'; then
  echo "SAFETY STOP: kernel already unstable (dmesg te panic/oops)."
  echo "Phone ta ekbar normal reboot kore abar try koro. Kichu load kora hoy ni."
  exit 2
fi

RNAME="$(tr -dc 'a-z' < /dev/urandom | head -c 8)"
echo "DEV NODE     : /dev/$RNAME"

if lsmod | grep -q kmem_337; then
  echo "INFO: kmem_337 age thekei loaded - rmmod kore notun kore tulchi."
  rmmod kmem_337 2>/dev/null
  sleep 1
fi

echo "CMD: insmod $KO devname=$RNAME"
ERR="$(insmod "$KO" "devname=$RNAME" 2>&1)"
RC=$?
echo "$ERR"

if [ $RC -ne 0 ]; then
  echo "$ERR" | grep -i -q -E 'invalid module format|exec format|vermagic|version magic' && \
    echo "DIAGNOSE: vermagic mismatch - ei build ei kernel e cholbe na."

  KMM="${KERNEL#*.}"; KMM="${KERNEL%%.*}.${KMM%%.*}"
  VMM="${KO_REL#*.}"; VMM="${KO_REL%%.*}.${VMM%%.*}"
  if [ "$KMM" = "$VMM" ] && [ -n "$KMM" ]; then
    echo "RETRY: same series ($KMM) - ekbar insmod -f try korchi..."
    ERR="$(insmod -f "$KO" "devname=$RNAME" 2>&1)"
    RC=$?
    echo "$ERR"
  else
    echo "SAFETY: series alada (phone=$KMM vs ko=$VMM) - force korbo na, phone restart hobe na."
    exit 3
  fi
fi

sleep 1
if [ -e "/dev/$RNAME" ]; then
  echo "OK: /dev/$RNAME toiri - driver loaded."
else
  echo "WARN: /dev/$RNAME pawa jay ni. dmesg dekho:"
  dmesg 2>/dev/null | grep -i -E 'kmem|vermagic|insmod' | tail -n 8
  [ $RC -eq 0 ] && exit 4
  exit $RC
fi

if dmesg 2>/dev/null | tail -n 50 | grep -i -E -q 'kernel panic|oops:|unable to handle|call trace'; then
  echo "SAFETY: load er pore kernel unstable - rescue rmmod korchi (restart hobe na)."
  rmmod kmem_337 2>/dev/null
  echo "Driver tule neya hoyeche. Ei kernel er jonno alada build lagbe."
  exit 5
fi

lsmod | grep kmem_337
echo "RESULT: DRIVER LOADED & VERIFIED - kono reboot deya hoy ni."
exit 0
