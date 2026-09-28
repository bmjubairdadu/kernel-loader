#!/usr/bin/env bash
set -euo pipefail

PROJ=/mnt/c/Users/Administrator/Downloads/DaisyDiverLoder
OUT=$PROJ/driver/out_all
REPO_URL=https://github.com/bmjubairdadu/kernel-loder.git
RAW_BASE=https://raw.githubusercontent.com/bmjubairdadu/kernel-loder/drivers
BRANCH=drivers

[ -d "$OUT" ] || { echo "ERROR: $OUT not found - run a build first"; exit 1; }

WORK=$(mktemp -d)
REPO_DIR=$WORK/repo

GCM="/mnt/c/Program Files/Git/mingw64/bin/git-credential-manager.exe"
if [ -x "$GCM" ]; then
  git config --global credential.helper "$GCM"
  echo "[*] using Windows Git Credential Manager"
fi

if git clone --depth 1 --branch "$BRANCH" "$REPO_URL" "$REPO_DIR" 2>/dev/null; then
  echo "[*] existing '$BRANCH' branch fetched"
else
  mkdir -p "$REPO_DIR"
  git -C "$REPO_DIR" init -q -b "$BRANCH"
  echo "[*] new '$BRANCH' branch initialized"
fi

git -C "$REPO_DIR" config user.name  "${GIT_AUTHOR_NAME:-kernel-loder-bot}"
git -C "$REPO_DIR" config user.email "${GIT_AUTHOR_EMAIL:-bot@kernelloader.local}"
git -C "$REPO_DIR" remote add origin "$REPO_URL" 2>/dev/null || true

rm -f "$REPO_DIR"/*.ko
cp "$OUT"/*.ko "$REPO_DIR"/

python3 - "$RAW_BASE" "$REPO_DIR" <<'PYEOF'
import hashlib, json, os, re, sys, time
base, repo = sys.argv[1], sys.argv[2]
os.chdir(repo)

def parse(v):
    m = re.match(r'(\d+)\.(\d+)\.(\d+)', v)
    return [int(m.group(1)), int(m.group(2)), int(m.group(3))] if m else [99, 99, 99]

drivers = []
for f in sorted(os.listdir('.')):
    if not f.endswith('.ko'):
        continue
    stem = f[:-3]
    version = stem.split('_', 1)[1] if '_' in stem else stem
    h = hashlib.sha256(open(f, 'rb').read()).hexdigest()
    bdate = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(os.path.getmtime(f)))
    drivers.append({"version": version, "file": f, "sha256": h, "size": os.path.getsize(f), "buildDate": bdate})

drivers.sort(key=lambda d: d["buildDate"], reverse=True)
json.dump({"updated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
           "baseUrl": base, "drivers": drivers},
          open("drivers.json", "w"), indent=2)
print(f"[+] drivers.json written: {len(drivers)} drivers")
PYEOF

cd "$REPO_DIR"
git add -A
if git diff --cached --quiet; then
  echo "[=] no changes - '$BRANCH' branch already up to date"
  exit 0
fi
git commit -qm "drivers: publish $(date -u '+%Y-%m-%d %H:%M UTC') [$(ls *.ko 2>/dev/null | wc -l) modules]"

if GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=echo git push -q origin "$BRANCH" 2>/dev/null; then
  echo "PUBLISHED: $RAW_BASE/drivers.json"
  rm -rf "$WORK"
  exit 0
fi

echo "PUSH FAILED - no GitHub credentials available in WSL."
echo "Ready-to-push repo kept at: $REPO_DIR"
echo ""
echo "Push manually with one of:"
echo "  1) git -C \"$REPO_DIR\" push origin $BRANCH   (after gh auth login or credential setup)"
echo "  2) ssh: git -C \"$REPO_DIR\" remote set-url origin git@github.com:bmjubairdadu/kernel-loder.git && git -C \"$REPO_DIR\" push origin $BRANCH"
exit 2
