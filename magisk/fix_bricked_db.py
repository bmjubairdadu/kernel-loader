"""Fix bricked .ko entries in the driver DB.

1. Extract every ELF from V3's Kernel_Drivers.zip scripts (already unzipped at
   RE\\v3apk\\scripts), keep only structurally valid arm64 REL modules, index by vermagic.
2. For each bricked DB entry: exact-vermagic match -> replace the .ko bytes and
   refresh sha256/size/buildDate/source. No match -> freeze the entry
   (frozenDrivers, same shape as DriverForge.FreezeDriverAsync) so phones stop
   downloading a module insmod will always reject.
"""
import base64
import datetime
import glob
import hashlib
import json
import os
import re

DB = r"D:\kernel-loader-drivers"
SCRIPTS = r"D:\kernel-loader\RE\v3apk\scripts"
NOW = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def norm(v):
    return " ".join(v.split()).strip()


def extract_elf(script_path):
    data = open(script_path, "rb").read()
    m = re.search(rb'MODULE_BASE64="([A-Za-z0-9+/=\r\n]+)"', data)
    if m:
        try:
            return base64.b64decode(re.sub(rb"\s", b"", m.group(1)))
        except Exception:
            return None
    i = data.find(b"\x7fELF")
    return data[i:] if i >= 0 else None


def vermagic_of(ko):
    m = re.search(rb"vermagic=([^\x00\n]{4,160})", ko)
    return norm(m.group(1).decode("utf-8", "replace")) if m else None


def elf_ok(ko):
    return (len(ko) > 20 and ko[:4] == b"\x7fELF" and ko[4] == 2
            and int.from_bytes(ko[16:18], "little") == 1
            and int.from_bytes(ko[18:20], "little") == 183
            and ko.count(b"\xef\xbf\xbd") == 0)


# ---------- 1. good-ELF vermagic index from V3 APK ----------
good = {}
for f in sorted(glob.glob(os.path.join(SCRIPTS, "RT", "*.sh")) + glob.glob(os.path.join(SCRIPTS, "QX", "*.sh"))):
    ko = extract_elf(f)
    if ko and elf_ok(ko):
        v = vermagic_of(ko)
        if v:
            good[v] = (os.path.relpath(f, SCRIPTS).replace(os.sep, "/"), ko)
print("V3 APK good ELFs indexed: %d" % len(good))

# ---------- 2. load DB, process bricked ----------
db = json.load(open(os.path.join(DB, "drivers.json"), encoding="utf-8"))
replaced, frozen = [], []

for d in db["drivers"]:
    path = os.path.join(DB, d["file"])
    if not os.path.exists(path):
        frozen.append((d, "missing .ko file"))
        continue
    ko = open(path, "rb").read()
    if elf_ok(ko):
        continue
    hit = good.get(norm(d["version"]))
    if hit:
        src_name, blob = hit
        with open(path, "wb") as fh:
            fh.write(blob)
        d["sha256"] = hashlib.sha256(blob).hexdigest()
        d["size"] = len(blob)
        d["sizeFormatted"] = ("%.1f KB" % (len(blob) / 1024)) if len(blob) < 1048576 else ("%.1f MB" % (len(blob) / 1048576))
        d["buildDate"] = NOW
        d["source"] = "v3apk:%s (replaced corrupt copy)" % src_name
        d["verified"] = True
        replaced.append((d["file"], src_name, len(blob)))
        print("REPLACED %-44s <- V3 %s (%d bytes)" % (d["file"], src_name, len(blob)))
    else:
        frozen.append((d, "corrupt .ko (V3 UTF-8 packaging bug, e_machine=49135) - no intact copy exists in Driver Loader V3 APK; rebuild via DriverForge"))

# ---------- 3. apply freezes ----------
if frozen:
    db.setdefault("frozenDrivers", [])
    for d, reason in frozen:
        d["frozen"] = True
        d["frozenAt"] = NOW
        d["freezeReason"] = reason
        db["drivers"].remove(d)
        db["frozenDrivers"].append(d)
        print("FROZEN   %-44s (%s)" % (d["file"], reason[:60] + "..."))

db["count"] = len(db["drivers"])
db["updated"] = NOW

with open(os.path.join(DB, "drivers.json"), "w", encoding="utf-8", newline="\n") as fh:
    json.dump(db, fh, indent=2, ensure_ascii=False)
    fh.write("\n")

# ---------- 4. validate ----------
finals = db["drivers"]
bad = [d["file"] for d in finals if not elf_ok(open(os.path.join(DB, d["file"]), "rb").read())]
keys = [(d["version"], d.get("variant", "")) for d in finals]
missing = [d["file"] for d in finals if not os.path.exists(os.path.join(DB, d["file"]))]
print()
print("entries: %d (frozen parked: %d)" % (db["count"], len(db["frozenDrivers"])))
print("rescan: bricked remaining =", bad or "NONE")
print("duplicate vermagic =", [k for k in set(keys) if keys.count(k) > 1] or "NONE")
print("missing files =", missing or "NONE")
assert not bad and not missing
