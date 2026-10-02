<div align="center">

# ⚡ Kernel Loder

### Universal Android Kernel Module (.ko) Loader
**Any device • Any model • Old & new kernels**

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%209%2B%20(arm64)-green.svg)](#requirements)
[![Version](https://img.shields.io/badge/version-2.4--universal-orange.svg)](CHANGELOG.md)
[![Root](https://img.shields.io/badge/root-Magisk%20%7C%20KernelSU-red.svg)](#requirements)

A root-powered Android app that loads kernel modules (`.ko`) on **any** phone, with a
**live terminal** that shows exactly what the loader is doing — and an **auto-fix engine**
that repairs the usual kernel-side problems (SELinux, permissions, vermagic mismatch,
symbol/CRC mismatch, signature enforcement) and retries, because the app runs with
superuser rights.

</div>

---

## ✨ Features

| Feature | Description |
|---|---|
| 🌍 **Truly universal** | Device brand / model / codename is *never* checked. Only the kernel release matters. |
|  **Vermagic-based driver selection** | The real kernel release is read from each `.ko` **binary** (not from its file name), so `native_*`, `qx_*`, `rt_*` or any custom name works. |
| ⚡ **AUTO LOAD (Universal)** | One tap: detects kernel → picks the best bundled driver → checks vermagic → fixes problems → loads → verifies. |
| ️ **Live terminal** | Colour-coded `CMD / OK / ERR / FIX / WARN / OUT` stream of every step + a **manual root shell** input. |
| 🔧 **Auto-fix engine** | SELinux permissive, chmod/chcon, **binary vermagic patching**, busybox `insmod -f`, `sig_enforce` off — each fix is applied *after* diagnosing the exact error, then retried. |
|  **OTA driver database** | No bundled `.ko` — matching loader is downloaded from the GitHub `drivers` branch at runtime, then verified. |
| ⚙️ **Manual control** | `insmod`, `insmod -f`, `rmmod` (auto-detects the real module name), **Verify Module** (`lsmod` + device node + `dmesg`). |
|  **Portable driver source** | `driver/kloader_driver.c` — a clean, reusable misc-device skeleton for any arm64 kernel. |

---

## 📱 Screens

| Main screen | Live terminal |
|---|---|
| Root status, kernel release, universal status line, driver picker, **⚡ AUTO LOAD**, force load, verification results | Every loader step in real time + manual root command box + Copy / Clear / AUTO LOAD / VERIFY / UNLOAD buttons |

> Screenshots are welcome in `docs/screenshots/`.

---

## ️ Download

* **APK** — grab the latest `app-release.apk` from the [**Releases**](../../releases/latest) page.
* **Kernel drivers (OTA)** — no bundled `.ko`; the app downloads the matching loader
  from the GitHub `drivers` branch database at runtime.
* **Full source** — Kotlin + Jetpack Compose app, C kernel module and build tooling.

---

## ✅ Requirements

| Item | Requirement |
|---|---|
| Root | **Magisk** or **KernelSU** (the app requests Superuser on first use) |
| Architecture | **arm64 / aarch64** |
| Android | 9.0 (API 28) or newer |
| Module format | Standard Linux loadable kernel module (`.ko`) for the *running* kernel release |

---

## 🚀 Quick start

1. Install the APK from **Releases**.
2. Open the app → grant **Superuser** permission → accept the safety warning.
3. *(Optional)* tap **Pick .ko file** to load your own module — if you skip this, AUTO LOAD
   picks the best bundled driver automatically.
4. Tap **⚡ AUTO LOAD (Universal)** — the app switches to the **Terminal** screen and streams
   every step live.
5. Watch the terminal (or the result card) — the last lines show the verification result:

```text
VERIFY: lsmod -> LOADED (kloader_driver)          OK
VERIFY: /dev node -> FOUND (/dev/kloaderctl)      OK
```

6. Verify manually any time with **Verify Module**, or type your own commands in the terminal:

```bash
lsmod | head
ls -l /dev/kloaderctl
cat /dev/kloaderctl            # -> Kernel Loder 4.9.337 OK
dmesg | grep KernelLoder | tail -n 20
```

---

## 🧭 How it works

```text
──────────────┐   ┌───────────────┐   ┌────────────────┐   ┌──────────────┐
│ Root check   │ → │ Device/kernel │ → │ Pick or auto-  │ → │ ELF +        │
│ (libsu shell)│   │ uname, geten- │   │ select driver  │   │ vermagic     │
│              │   │ force, arch   │   │ (best match)   │   │ inspection   │
└──────────────┘   ───────────────   └────────────────   └──────┬───────
                                                                    ▼
┌──────────────┐   ┌───────────────┐   ┌────────────────┐   ┌──────────────┐
│ Verify:      │ ← │ insmod → auto-│ ← │ Copy to        │ ← │ Fix if       │
│ lsmod / dev  │   │ fix ladder →  │   │ /data/local/tmp│   │ needed:      │
│ node / dmesg │   │ retry         │   │ (chmod 644)    │   │ vermagic     │
└──────────────┘   └───────────────┘   └────────────────┘   │ patch / -f   │
                                                             └──────────────┘
```

### Auto-fix ladder (applied only when the diagnosis matches)

| Kernel error / state | Automatic fix |
|---|---|
| `SELinux` = Enforcing | `setenforce 0` → Permissive |
| `Permission denied` on insmod | `chmod` + `chcon` + SELinux fix → retry |
| `Invalid module format` / `Exec format error` / `vermagic` / `version magic` | **Binary-patch the `vermagic=` string in the `.ko`** to the running kernel release → retry |
| `Unknown symbol`, `disagrees about version`, `module_layout` | busybox **`insmod -f`** (force) — busybox auto-detected (Magisk path included) |
| `Required key not available` / `Key was rejected` | `sig_enforce` off → retry |
| `File exists` (already loaded) | treated as success and verified |

### Driver selection score (highest wins)

| Condition | Score |
|---|---|
| `.ko` vermagic short version == running kernel short version | **1200** |
| asset version == full `uname -r` | 1000 |
| `uname -r` starts with asset version | 900 |
| asset version starts with `uname -r` | 800 |
| same `major.minor` | 500 |
| native/KERNEL build bonus, QX over RT tie-break | +200 / +5 |

---

## 🗂️ Project structure

```text
KernelLoder/
├─ app/                                   # Android app (Kotlin + Jetpack Compose)
│  └─ src/main/
│     ├─ java/com/kernelloader/
│     │  ├─ MainActivity.kt               # UI: root/kernel status, driver picker, AUTO LOAD, results
│     │  ├─ driver/
│     │  │  ├─ DriverViewModel.kt         # embedded driver catalog, insmod/rmmod, verify
│     │  │  └─ UniversalKernelLoader.kt   # AUTO LOAD engine + auto-fix ladder + vermagic patcher
│     │  ├─ root/RootChecker.kt           # root detection, kernel parsing, universal status line
│     │  └─ ui/                           # TerminalScreen, CreditsScreen, SafetyWarningDialog, theme
│     ├─ assets/drivers/*.ko              # bundled kernel modules (QX / RT / native)
│     └─ res/values/strings.xml
├─ driver/                                # kernel module + build tooling
│  ├─ kloader_driver.c                    # universal misc-device driver source
│  ├─ Makefile
│  ├─ build_native_wsl.sh                 # builds native_4.9.337.ko + native_4.9.307.ko (WSL)
│  ├─ wsl_launch_native.sh, wsl_verify.sh # LF-safe WSL helpers
│  └─ out/                                # prebuilt .ko + README_BUILD_BANGLA.md
├─ docs/                                  # Bangla guides (install / build / FAQ)
├─ .github/workflows/                     # CI: debug APK + kernel module build
└─ gradle/, build.gradle.kts, settings.gradle.kts
```

---

## 🛠️ Building the app

```bash
# JDK 17+ required (Android Studio's bundled JBR works)
export JAVA_HOME="/path/to/jdk17"          # Windows: set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr

./gradlew :app:assembleDebug               # Windows: gradlew.bat :app:assembleDebug

# output
app/build/outputs/apk/debug/app-debug.apk
```

* `namespace` / `applicationId`: `com.kernelloader`
* `minSdk 28`, `targetSdk 34`, Compose UI, `libsu` for the root shell

---

## 🔨 Building the kernel module (.ko)

```bash
# 1. kernel source (target tree) + an aarch64 toolchain, e.g. proton-clang
# 2. one command builds both shipped builds and copies them into the app assets
wsl -d Ubuntu-22.04 -- bash /mnt/c/.../KernelLoder/driver/wsl_launch_native.sh
#    progress: /tmp/native.log      verification: driver/wsl_verify.sh
```

`driver/build_native_wsl.sh` performs, for every kernel release:

1. `make O=out ARCH=arm64 ... CC=clang modules` against the target kernel tree,
2. `modinfo` the result (vermagic / version / author check),
3. copies the module to `driver/out/native_<release>.ko` **and** `app/src/main/assets/drivers/`.

> **Note** — the `vermagic` string (`4.9.337-DaisyForGaming SMP preempt mod_unload modversions aarch64`)
> comes from the kernel tree's `CONFIG_LOCALVERSION`, not from the driver source.
> A module only loads *cleanly* on the exact kernel release it was built against.

### Adding support for another kernel

```bash
# just drop it in - no code change needed
cp my_kernel_5.4.210.ko app/src/main/assets/drivers/native_5.4.210.ko
./gradlew :app:assembleDebug
```

The catalog scans `assets/drivers/` at runtime and the loader reads each file's real
`vermagic`, so new drivers are picked up automatically (any file name / any prefix).

---

## 🧰 Troubleshooting

| Symptom | Reason / fix |
|---|---|
| `ROOT: MISSING` | Grant Superuser to the app (Magisk / KernelSU) and reopen it. |
| `Invalid module format` on your own `.ko` | Built for a different kernel release. Use AUTO LOAD (vermagic auto-patch) or rebuild against your `uname -r`. |
| `Unknown symbol` / `disagrees about version` | Kernel config/CRC mismatch → AUTO LOAD falls back to busybox `insmod -f`. |
| Module loads but no `/dev` node | Some drivers expose their interface only after an ioctl; check `dmesg` in the terminal. |
| Reboot after a force load | `insmod -f` bypasses safety checks — a wrong module can crash the kernel. Prefer a matching build. |

---

## ⚠️ Safety / Disclaimer

Loading kernel modules as **root** can crash, reboot or brick a device and can void
warranties. The loader tries to fix problems automatically, but **no guarantee is given**.
You are solely responsible for what you load. Always keep a backup of your boot image and
know how to recover (fastboot / recovery).

---

## 🤝 Contributing

1. Fork → feature branch → commit → open a Pull Request.
2. Keep the *universal* philosophy: **never** branch on device brand/model — only on kernel release and capabilities.
3. Run `./gradlew :app:assembleDebug` before pushing.
4. New driver builds are welcome: attach the `.ko` + `modinfo` output in the PR.

---

## 📜 Credits & License

* **Author / maintainer:** [JUBAIR HOSEN](https://github.com/bmjubairdadu)
* Root shell: [**libsu**](https://github.com/topjohnwu/libsu) by topjohnwu (Apache-2.0)
* Legacy driver family (QX / RT builds) collected from the community
* Licensed under the **GNU General Public License v3.0** — see [LICENSE](LICENSE).
  The kernel module (`driver/kloader_driver.c`) is `MODULE_LICENSE("GPL")`.

<div align="center">

**Kernel Loder v2.4-universal** — one loader for every kernel.
⭐ Star the repo if it helped you!

</div>