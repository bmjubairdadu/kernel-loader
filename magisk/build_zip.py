import os
import zipfile

src = "KL-SigOff"
out = "KL-SigOff-v1.0.zip"
if os.path.exists(out):
    os.remove(out)

with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _dirs, files in os.walk(src):
        for f in sorted(files):
            full = os.path.join(root, f)
            rel = os.path.relpath(full, src).replace(os.sep, "/")
            z.write(full, rel)
            print("  added:", rel)

print()
with zipfile.ZipFile(out) as z:
    names = z.namelist()
    bad = [n for n in names if "\\" in n or n.startswith("/")]
    print("entries=%d  bad_paths=%s" % (len(names), bad or "none"))
    print("zip integrity:", z.testzip() or "OK (no corrupt entries)")
    required = ["module.prop", "customize.sh", "service.sh", "boot-patch.sh",
                "uninstall.sh", "action.sh", "bin/magiskboot", "bin/sigpatch"]
    missing = [r for r in required if r not in names]
    print("required files:", "ALL PRESENT" if not missing else "MISSING %s" % missing)
print("zip size: %d bytes" % os.path.getsize(out))
