"""Dedup D:\kernel-loader-drivers\drivers.json: one driver per (full vermagic, variant).

Keeper policy (documented in DELETION_REPORT.md):
1. newest buildDate wins
2. tie -> smaller file wins (clean build, no debug bloat)
3. tie -> first index wins

Deleted .ko files are MOVED (not destroyed) to RE\removed_dup_drivers\ outside the repo.
"""
import datetime
import hashlib
import json
import os
import shutil

DB = r"D:\kernel-loader-drivers"
BACKUP = r"D:\kernel-loader\RE\removed_dup_drivers"
os.makedirs(BACKUP, exist_ok=True)

db = json.load(open(os.path.join(DB, "drivers.json"), encoding="utf-8"))
drivers = db["drivers"]

groups = {}
for i, d in enumerate(drivers):
    groups.setdefault((d["version"], d.get("variant", "")), []).append(i)

def size_of(d):
    f = os.path.join(DB, d["file"])
    return os.path.getsize(f) if os.path.exists(f) else 1 << 62

keep, drop = [], []
for key, idxs in sorted(groups.items()):
    if len(idxs) == 1:
        continue
    # stable multi-pass sort: least significant key first
    ranked = sorted(idxs)
    ranked.sort(key=lambda i: size_of(drivers[i]))                                # smaller wins
    ranked.sort(key=lambda i: drivers[i].get("buildDate", ""), reverse=True)      # newest wins
    keep.append(ranked[0])
    drop.extend(ranked[1:])

print("=== KEEP / DROP ===")
for i in sorted(keep):
    print("KEEP [%2d] %s" % (i, drivers[i]["file"]))
for i in sorted(drop):
    d = drivers[i]
    twin = ""
    print("DROP [%2d] %-48s (%s, %s)" % (i, d["file"], d.get("buildDate", "?"), d.get("sizeFormatted", "?")))

# move dropped .ko out of the repo
moved = []
for i in drop:
    src = os.path.join(DB, drivers[i]["file"])
    dst = os.path.join(BACKUP, drivers[i]["file"])
    if os.path.exists(src):
        shutil.move(src, dst)
        moved.append(drivers[i]["file"])

db["drivers"] = [d for i, d in enumerate(drivers) if i not in set(drop)]
db["count"] = len(db["drivers"])
db["updated"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

with open(os.path.join(DB, "drivers.json"), "w", encoding="utf-8", newline="\n") as fh:
    json.dump(db, fh, indent=2, ensure_ascii=False)
    fh.write("\n")

# validate: every remaining file exists, no duplicate vermagic left
remaining = db["drivers"]
keys = [(d["version"], d.get("variant", "")) for d in remaining]
assert len(keys) == len(set(keys)), "duplicates remain!"
missing = [d["file"] for d in remaining if not os.path.exists(os.path.join(DB, d["file"]))]
assert not missing, "missing files: %s" % missing

print()
print("moved to backup:", moved)
print("entries: %d -> %d" % (len(drivers), db["count"]))
print("validation: no duplicate vermagic, all %d files exist" % db["count"])
