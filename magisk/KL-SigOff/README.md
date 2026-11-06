# KL-SigOff — Magisk Module (Kernel Module Signature Off)

Kernel Loader অ্যাপের unsigned `.ko` ড্রাইভারগুলো `CONFIG_MODULE_SIG_FORCE` কার্নেলে
`insmod: Required key not available / Key was rejected by service` দিয়ে ফেল করে।
এই মডিউল বুট-টাইমে সেই enforcement বন্ধ করে — অ্যাপের ভেতরের sig-unlock ইঞ্জিনের
একই কাজ, কিন্তু Magisk মডিউল হিসেবে (PC লাগে না, OTA-র পরেও নিজে থেকে আবার চলে)।

## কী করে

প্রতি বুটে `service.sh`:

1. স্লট-সচেতনভাবে boot partition খোঁজে (`boot_a`/`boot_b`/`boot`)
2. `dd` দিয়ে dump → bundled `magiskboot unpack`
3. kernel image-এ **module-sig check function-এর শেষ** (`ldr/str … ; ret` টেইল) খুঁজে
   `mov w0,#0; ret` বসায় — মানে যাচাই সবসময় "পাস", unsigned মডিউল লোড হয়
4. না মিললে **dynamic sigpatch engine** (arm64 disassembler) চেষ্টা করে
5. `repack` → partition size যাচাই → `dd` দিয়ে ফিরিয়ে লেখে → state-এ hash রেখে রাখে
6. ২০ সেকেন্ড পর **একবার reboot** (patched kernel লোড হতে) — `AUTO_REBOOT=0` দিয়ে বন্ধ করা যায়

আগেরবার patch করা boot হলে (state hash মিললে) বা অ্যাপ নিজেই unlock করে থাকলে
(`kernel_loader/status.prop`) skip করে — বারবার flash করে না। OTA update-এর পরে
boot বদলালে নিজে থেকেই আবার patch করে।

## কোন build-এর pattern আছে (১০টা + ইঞ্জিন)

| Pattern | Device (kernel) |
|---|---|
| 1 | OnePlus CPH2381 (5.4.254) |
| 2 | Realme RMX3511 (5.4.210) |
| 3 | Infinix X6525 (5.4.210) |
| 4 | OnePlus IN2021 (4.19.157) |
| 5 | Realme RMX3195 (4.19.191) |
| 6 | Samsung SM-A235F (4.19.157) |
| 7 | Xiaomi M2010J19CG (4.19.157) |
| 8 | Realme RMX5070 (4.14.357) |
| 9 | Realme RMX3241 (4.19.191+) |
| 10 | Xiaomi Redmi 7 (4.9.186 — branch inversion) |

অন্য build হলে dynamic engine চেষ্টা করে; সেটাও না পারলে `PATTERN_NOT_FOUND` log করে —
তখন DriverForge-এর sig-off kernel rebuild-ই একমাত্র নিশ্চিত পথ।

## ইনস্টল

Magisk app → Modules → `KL-SigOff-v1.0.zip` থেকে install → reboot।
KSU/APatch-ও চলে (`customize.sh` স্ট্যান্ডার্ড ফলো করে)।

ম্যানুয়াল চালানো/চেক: Magisk app-এ মডিউলের **Action** বাটন, বা রুট শেলে:

```sh
sh /data/adb/modules/kl_sigoff/boot-patch.sh
cat /data/adb/kl_sigoff/log.txt     # কী হলো
cat /data/adb/kl_sigoff/state       # কোন hash কবে patch হয়েছে
```

## Config

`/data/adb/kl_sigoff/config`:

```sh
AUTO_REBOOT=0   # patch করার পর অটো-রিবুট বন্ধ — নিজে করবে
```

## সাবধানতা

- **arm64 only** — bundled binary গুলো arm64।
- **dm-verity/AVB**: verity disabled না থাকলে patched boot বুট করতে রাজি নাও হতে পারে
  (Magisk দিয়ে root করা ফোনে সাধারণত ইতিমধ্যে disabled)।
- ফুল রিভার্ট: মডিউল আনইনস্টল + stock boot.img fastboot-এ ফিরিয়ে ফ্ল্যাশ।
- Battery <15% হলে ওই বুটে skip (রিবুট-লুপ ঠেকাতে), পরের বুটে আবার চেষ্টা।

## ফাইল

```
kl_sigoff/
├── module.prop      # id=kl_sigoff v1.0.0
├── customize.sh     # installer (perm সেট, config তৈরি)
├── service.sh       # প্রতি বুটে চালায় (battery/block wait guard সহ)
├── action.sh        # Magisk Action বাটন — ম্যানুয়াল রান
├── boot-patch.sh    # মূল প্যাচার (dump→unpack→hexpatch→repack→flash→reboot)
├── uninstall.sh     # state/work পরিষ্কার
└── bin/
    ├── magiskboot   # Kernel Loader অ্যাপের বানানো 64-bit native
    └── sigpatch     # dynamic arm64 sig-enforce প্যাচার ইঞ্জিন
```
