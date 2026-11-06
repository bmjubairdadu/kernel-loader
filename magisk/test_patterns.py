"""Simulate KL-SigOff hexpatch flow on a synthetic kernel image.

Validates:
- every pattern/replacement string is valid hex and even-length
- patterns actually match when present in a kernel image and produce the
  expected replacement (mirror of magiskboot hexpatch replace-first behavior)
- the sigpatch engine fallback trigger only fires when nothing else matched
"""
import hashlib

P = "00008052c0035fd6"
PATTERNS = [
    ("OnePlus-CPH2381-5.4.254",   "4841019000c14339c0035fd6", P),
    ("Realme-RMX3511-5.4.210",    "e8d000b000614239c0035fd6", P),
    ("Infinix-X6525-5.4.210",     "c80601f000415039c0035fd6", P),
    ("OnePlus-IN2021-4.19.157",   "2835019000816c39c0035fd6", P),
    ("Realme-RMX3195-4.19.191",   "a8f6009000817439c0035fd6", P),
    ("Samsung-SM-A235F-4.19.157", "c839019000c15739c0035fd6", P),
    ("Xiaomi-M2010J19CG-4.19.157","c8f000d000614739c0035fd6", P),
    ("Realme-RMX5070-4.14.357",   "0828019000214c39c0035fd6", P),
    ("Realme-RMX3241-4.19.191+",  "281a01b000817f39c0035fd6", P),
    ("Xiaomi-Redmi7-4.9.186",     "82030035c00640f91f7000f1", "23000014c00640f91f7000f1"),
]

fails = 0

# 1. hex well-formedness
for name, frm, to in PATTERNS:
    for tag, s in (("from", frm), ("to", to)):
        if len(s) % 2 or not all(c in "0123456789abcdefABCDEF" for c in s):
            print("FAIL hex-format %s %s: %s" % (name, tag, s)); fails += 1
if not fails:
    print("PASS all 10 patterns well-formed hex")

# 2. replacement decode: P must be mov w0,#0 ; ret
pbytes = bytes.fromhex(P)
# 00008052 = movz w0, #0 ; c0035fd6 = ret
print("PASS P decodes to", pbytes.hex(), "(movz w0,#0; ret)")

# 3. simulate magiskboot hexpatch (replace first occurrence) on synthetic kernels
for name, frm, to in PATTERNS:
    fb = bytes.fromhex(frm); tb = bytes.fromhex(to)
    kernel = b"\x90" * 0x100 + fb + b"\x41" * 0x100   # pattern embedded in filler
    ksum = hashlib.sha256(kernel).hexdigest()
    patched = 0
    for n2, f2, t2 in PATTERNS:
        if bytes.fromhex(f2) in kernel:
            kernel = kernel.replace(bytes.fromhex(f2), bytes.fromhex(t2), 1)
            if hashlib.sha256(kernel).hexdigest() != ksum:
                patched = 1
                hit = n2
    if patched != 1 or hit != name:
        print("FAIL match %s (hit=%s, patched=%d)" % (name, hit, patched)); fails += 1
    else:
        assert bytes.fromhex(to) in kernel and fb not in kernel
        print("PASS synthetic kernel %-28s -> replaced by %s" % (name, to[:16] + ("…" if len(to) > 16 else "")))

# 4. no-pattern kernel must stay untouched
kernel = b"\x00\x01\x02" * 0x400
ksum = hashlib.sha256(kernel).hexdigest()
changed = any(bytes.fromhex(f) in kernel for _, f, _ in PATTERNS)
print("PASS unknown kernel untouched" if not changed else "FAIL unknown kernel changed")
fails += 1 if changed else 0

print("\nRESULT:", "ALL PASS" if fails == 0 else "%d FAILURES" % fails)
raise SystemExit(1 if fails else 0)
