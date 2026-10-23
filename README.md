<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="72" alt="Kernel Loader" onerror="this.style.display='none'"/>

# ⚡ Kernel Loader

### OTA Kernel Module Loader for Android
**One tap · Detects your kernel · Downloads the right driver · Loads it**

[![Release](https://img.shields.io/github/v/release/bmjubairdadu/kernel-loader?style=flat-square)](../../releases/latest)
[![Platform](https://img.shields.io/badge/platform-Android%209%2B%20arm64-green?style=flat-square)](#-requirements)
[![Root](https://img.shields.io/badge/root-Magisk%20%7C%20KernelSU%20%7C%20APatch-red?style=flat-square)](#-requirements)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL%20v3-blue?style=flat-square)](LICENSE)

</div>

---

**Kernel Loader** is a root-powered Android app that loads kernel modules (`.ko`) on any
arm64 device. It reads your **exact running kernel**, finds a matching driver in the
**OTA driver database** (40+ prebuilt kernels), loads it through a safe pipeline and
verifies the result — all from one clean, dark interface.

No device hunting. No adb. No manual `insmod`.

## 📱 Screenshots

| Home | Pipeline & DB |
|---|---|
| ![Home](docs/screenshots/main.png) | ![Console & DB](docs/screenshots/console.png) |

---

## ✨ Features

- ⚡ **One-tap LOAD** — smart adaptive pipeline: evaluates **Primary (QX or RT) → Fallback Secondary → Built-in Universal**.
- 🧠 **Smart Dual-Variant Detection** — inspects your kernel's full vermagic, configuration flags, and gaming profile (e.g., Daisy, CAF) to dynamically designate either **QX** or **RT** as the best-suited primary driver.
- 🔐 **Kernel Signature Verification Bypass** — bypasses `CONFIG_MODULE_SIG_FORCE` and `module_sig_enforce` on restrictive kernels by disabling enforcement at runtime and stripping incompatible vendor signatures (`stripModuleSig`) before insertion.
- 🎯 **Exact kernel matching** — reads `uname -r` and resolves an **exact** prebuilt driver from the OTA database (42+ kernels, 4.9 → 6.6, Driver Engine 3.1).
- 🔍 **Live Pre-Load Diagnostics** — runs sequential, paced pre-flight system inspections (Root privileges, Kernel & Arch, Signature enforcement, Driver DB availability, Variant evaluation) with clean, concise status feedback.
- 🧨 **One-tap UNLOAD** — cleanly removes the driver, including **stealth-loaded** builds via unhide ioctl (`0x806`), `rmmod`, and post-unload cleanup verification.
- 🛡️ **Revocation Watchdog Daemon** — detached background supervisor (`kloader_daemon.sh` & service) actively guards driver health and root access; cleanly purges and reboots if root or permissions are revoked.
- 🔗 **Universal Device Node Symlinks** — automatically exposes and links all standard memory access aliases (`/dev/wanbai`, `/dev/dmsync`, `/dev/memacc`, `/dev/vmemacc`) with verified `0666` permissions and SELinux `chr_file` policy injection.
- 🛑 **STOP button** — cancels load operations safely mid-pipeline with one tap.
- 🗒️ **Status console** — clean, color-coded, single-line live feedback (`Database Connected`, `Driver loaded`, `Load OK`...).
- 🔀 **RT / QX separation** — driver catalogs split per ABI (`RT KERNELS` / `QX KERNELS`) with `THIS DEVICE` / `NEWEST` indicators.
- 🔁 **Boot auto-load — opt-in** — toggle **OFF by default**. When enabled, a Magisk/APatch boot service restores the module on every startup.
- 🧾 **Auto build-request reports** — easily report unsupported kernels with pre-filled WhatsApp/GitHub reports containing compiler strings, vermagic, and `/proc/config.gz` flags.
- 📲 **In-app background updates** — checks GitHub releases automatically, downloads updates in the background, and triggers one-tap seamless APK installation.
- 🛡️ **Anti-tamper & hardening** — release signature validation, debugger/Xposed refusal, R8 code flattening, and sha256 OTA integrity verification (see [Security](#-security)).

## 📥 Download

Grab the latest APK from the [**Releases**](../../releases/latest) page and install it.
The app checks for updates by itself — when a new version is out it offers a
one-tap in-app install.

## ✅ Requirements

| Item | Requirement |
|---|---|
| Root | **Magisk**, **KernelSU** or **APatch** (grant Superuser on first use) |
| Architecture | arm64 / aarch64 |
| Android | 9.0 (API 28) or newer |
| Internet | Needed to fetch the driver database and updates |

## 🚀 Quick start

1. Install the APK and open it — grant **Superuser** when asked.
2. Wait for `Database Connected` in the status console.
3. Tap **LOAD** — the pipeline detects your kernel, downloads the matching driver,
   loads it and verifies the `/dev` node.
4. Wrong driver family? Force **RT** or **QX** with the chips under LOAD.
5. Done with it? Tap **UNLOAD** — the driver is removed and the kernel state is
   verified clean.
6. Want the driver to survive reboots? Flip **Load at every boot** ON — it stays
   opt-in; the loader never enables it by itself.

## 🧭 How the load pipeline works

```
tap LOAD
   │
   ▼
[Pre-Load Diagnostics] ── Root privileges · Kernel & Vermagic · Signature bypass · Variant scoring
   │
   ▼
Stage 1/3 · Primary Variant (Smart QX / RT) ── local staged .ko → OTA DB match → download → insmod ladder → verify /dev
   │  fail?
   ▼
Stage 2/3 · Fallback Variant (Alternate ABI) ─ same flow with alternate driver ABI
   │  fail?
   ▼
Stage 3/3 · Built-in Universal Loader ──────── embedded universal fallback loader
   │
   ▼
Post-Load Verification & Arming:
   ├─ verify: lsmod + character devnode live
   ├─ symlinks: /dev/wanbai, /dev/dmsync, /dev/memacc, /dev/vmemacc (perms 0666)
   ├─ security: SELinux chr_file rules injected
   └─ watchdog: supervisor daemon armed (auto-cleanup & reboot guard)
```

Every stage is cancellable with the **STOP** button, and each ABI's logs stay tagged so
they can be filtered later in the full console (`ALL · STATUS · RT · QX`).

## 🧾 Reporting an unsupported kernel

If your kernel has no match, the app builds a **complete build request** for you:

- exact `uname -r` + architecture + page size
- `/proc/version` (compiler string)
- **vermagic** extracted from an on-device module
- kernel **config flags** from `/proc/config.gz` (`SMP`, `PREEMPT`,
  `MODVERSIONS`, `MODULE_UNLOAD`, `LOCALVERSION`…)
- device identity (model, board, SoC, fingerprint)
- `dmesg` tail + full load log

Tap **WhatsApp** to send it as a chat message, **Report** to open a pre-filled GitHub
issue, or **Copy full report** to paste it anywhere. A maintainer builds the matching
`.ko`, pushes it to the driver database, and your app finds it on the next refresh.

## 🔨 Building the app

```bash
git clone https://github.com/bmjubairdadu/kernel-loader.git
cd kernel-loader
./gradlew :app:assembleDebug          # or assembleRelease
# output: app/build/outputs/apk/<variant>/app-*.apk
```

- Kotlin + Jetpack Compose, `libsu` for the root shell
- `minSdk 28` · `targetSdk 34` · JDK 17

## 🛡️ Security

| Layer | What it does |
|---|---|
| R8 full shrink + obfuscation | Classes renamed, code paths flattened, `-repackageclasses` |
| Log stripping | `Log.v/d/i` removed at build time — nothing leaks via logcat |
| Signature check | The app verifies its signing certificate at every launch; re-signed (cracked) builds refuse to start |
| Debugger / Xposed refusal | A debugger attached or an Xposed hook in the process → app exits |
| Backup disabled | `allowBackup=false` — no adb-backup extraction of app data |

> Building your own copy? Update `TRUSTED_SIG_SHA256` in `AppGuard.kt` with your
> keystore's certificate SHA-256 (`keytool -list -v`), otherwise your build will
> exit on launch.

## 🗂️ Project structure

```text
kernel-loader/
├─ app/src/main/java/com/kernelloader/
│  ├─ MainActivity.kt
│  ├─ driver/          # load pipeline, OTA database, boot auto-load, reports
│  ├─ mem/             # driverless process-memory test
│  ├─ root/            # root & kernel detection
│  ├─ security/        # AppGuard anti-tamper
│  ├─ ui/              # Compose screens & theme
│  └─ update/          # GitHub release update checker
├─ docs/screenshots/
└─ .github/workflows/  # CI (APK build)
```

> The driver-engine sources live in `driver/` (`kmem_337_core.h`, `memacc.c`,
> `memacc_qx.c`) alongside the WSL build scripts; the compiled `.ko` artifacts ship
> in the APK and the OTA database.

## ⚠️ Safety / Disclaimer

Loading kernel modules as **root** can crash, reboot or brick a device and can void
warranties. The loader verifies every driver against your kernel, but **no guarantee
is given**. Keep a backup of your boot image and know how to recover.

## 📜 Credits & License

* **Author / maintainer:** [JUBAIR HOSEN](https://github.com/bmjubairdadu)
* Root shell: [libsu](https://github.com/topjohnwu/libsu) by topjohnwu (Apache-2.0)
* Licensed under **GPL-3.0** — see [LICENSE](LICENSE)

<div align="center">

**Kernel Loader v1.3** — one tap, the right driver, every kernel.
⭐ Star the repo if it helped you!

</div>
