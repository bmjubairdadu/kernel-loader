"""Scan every .ko in the driver DB for ELF integrity.

Bricked = bad ELF magic, wrong e_machine (must be 183/EM_AARCH64 for arm64),
wrong e_type (must be 1/ET_REL for a .ko), or contains U+FFFD replacement chars
(the UTF-8 corruption marker from V3's packed scripts).
"""
import glob
import json
import os

DB = r"D:\kernel-loader-drivers"

def elf_check(path):
    data = open(path, "rb").read()
    out = {
        "size": len(data),
        "fffd": data.count(b"\xef\xbf\xbd"),
        "magic_ok": data[:4] == b"\x7fELF",
        "class": data[4] if len(data) > 4 else -1,
        "type": int.from_bytes(data[16:18], "little") if len(data) > 18 else -1,
        "machine": int.from_bytes(data[18:20], "little") if len(data) > 20 else -1,
    }
    out["ok"] = (out["magic_ok"] and out["class"] == 2 and out["type"] == 1
                 and out["machine"] == 183 and out["fffd"] == 0)
    return out, data

db = json.load(open(os.path.join(DB, "drivers.json"), encoding="utf-8"))
bricked = []
print("%-48s %9s %6s %7s %6s %6s  %s" % ("file", "size", "magic", "type", "mach", "FFFD", "verdict"))
print("-" * 100)
for d in db["drivers"]:
    path = os.path.join(DB, d["file"])
    if not os.path.exists(path):
        print("%-48s MISSING FILE" % d["file"])
        bricked.append(d)
        continue
    info, data = elf_check(path)
    verdict = "OK" if info["ok"] else "BRICKED"
    if not info["ok"]:
        bricked.append(d)
    print("%-48s %9d %6s %7d %6d %6d  %s" % (
        d["file"], info["size"], "yes" if info["magic_ok"] else "NO",
        info["type"], info["machine"], info["fffd"], verdict))

print("-" * 100)
print("total=%d  ok=%d  bricked=%d" % (len(db["drivers"]), len(db["drivers"]) - len(bricked), len(bricked)))
print()
if bricked:
    print("BRICKED ENTRIES:")
    for d in bricked:
        print("  -", d["file"], "| vermagic:", d["version"][:58], "| source:", d.get("source", "?"))
