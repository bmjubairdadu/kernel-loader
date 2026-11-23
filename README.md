<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="76" alt="Kernel Loader" onerror="this.style.display='none'"/>

# Kernel Loader (v6.7)

### Enterprise-Grade Kernel Driver Engine & Diagnostic Suite for Android
**Adaptive UI · Direct .ko In-Memory Injection · 0600 Root Security · Real-Time Post-Load Cleansing · Panic-Free Stability · OTA Updates**

[![Release](https://img.shields.io/github/v/release/bmjubairdadu/kernel-loader?color=blue&style=flat-square)](https://github.com/bmjubairdadu/kernel-loader/releases/latest)
[![Platform](https://img.shields.io/badge/platform-Android%209%2B%20arm64-green?style=flat-square)](#requirements)
[![Root](https://img.shields.io/badge/root-Magisk%20%7C%20KernelSU%20%7C%20APatch-red?style=flat-square)](#requirements)
[![Driver Format](https://img.shields.io/badge/driver-Direct%20.ko%20ELF-purple?style=flat-square)](#driver-architecture--stealth-pipeline)
[![Node Security](https://img.shields.io/badge/node-0600%20root%3Aroot-success?style=flat-square)](#node-security--anti-cheat-cloaking)
[![Languages](https://img.shields.io/badge/languages-28%20Global%20Locales-orange?style=flat-square)](#internationalization-28-languages)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat-square)](CONTRIBUTING.md)
[![Contributors](https://img.shields.io/github/contributors/bmjubairdadu/kernel-loader?style=flat-square)](https://github.com/bmjubairdadu/kernel-loader/graphs/contributors)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL%20v3-blue?style=flat-square)](LICENSE)

</div>

---

**Kernel Loader** is a powerful root-driven Android application engineered to inspect, verify, and load stealth Linux kernel drivers across Android Linux kernels **4.9 through 6.12+**. Built with modern Jetpack Compose, an adaptive universal layout engine, pure in-memory direct injection (`init_module`), and automated multi-stage post-load memory & log cleansing, Kernel Loader guarantees safe, authenticated, and 100% trace-free driver deployment.

---

## ✨ What's New in v6.5

- **Zero-Panic Stable Kernel Engine:** Fully eliminated unstable `kprobe` hooks on filesystem iterators (`filldir64`), eradicating kernel panics and watchdog bite reboots on Qualcomm and diverse Android kernels.
- **Direct .ko Kernel Module Loading:** Fully deprecated legacy `.bin` containers in favor of direct, raw ARM64 ELF kernel objects (`.ko`) injected straight into kernel memory.
- **Immediate Post-Load Log & Artifact Cleansing:**
  - Automated `dmesg -c` execution flushes the Linux kernel ring buffer instantly after driver activation, preventing game anti-cheat scanners from inspecting kernel module logs.
  - Automatic `logcat -c` flushes Android userspace system logs.
  - Complete disk sanitization wipes temporary injector tools and any `/data/local/tmp/` traces immediately upon insmod.
- **Enforced 0600 (`crw------- root:root`) Device Node Mode:** Device character nodes created by the driver in `/dev` are strictly locked to `0600 root:root`, ensuring only root client daemons can interact with the communication channel.
- **Optimized & Pruned Production Database:** Cleared all legacy multi-kernel entries; remote OTA database serves strictly verified, ready-to-inject production drivers (starting with kernel `4.9.337`).
- **Zero Disk Footprint In-Memory Syscall Engine:** ELF module bytes stream directly into the kernel via `direct_inject_arm64` through standard input pipes, leaving zero `.ko` files saved on public storage or cache.

---

## 🚀 Key Features

### 1. Driver Architecture & Stealth Pipeline
- **Page Table Walk Memory Engine:** In-kernel physical address translation via ARM64 page table walk (`mm->pgd` -> `PUD` -> `PMD` -> `PTE`), eliminating `get_user_pages_remote` artifacts and preserving page reference counts.
- **Kernel Stealth Cloaking:**
  - Taint eradication (`tainted_mask = 0`).
  - Sysfs & module list unlink (`list_del_init(&THIS_MODULE->list)` and `kobject_del`).
  - `vmap_area` concealment blinding `/proc/vmallocinfo`.
  - Directory hiding via kprobe hooks blinding `/dev` directory scans.

### 2. Universal Adaptive UI
- Proportional density scaling guarantees that cards, symmetric status boxes (`ROOT PERMISSION`, `DATABASE`), vermagic badges, and action buttons render with identical aesthetics on every screen size.
- Resilient font-scaling normalization prevents layout deformation from system accessibility font magnification.
- Tablet and foldable-aware containers (`widthIn(max = 580.dp)`) ensure balanced centered proportions.

### 2. Comprehensive Kernel Compatibility Audit
The built-in audit engine validates 10 vital kernel layers before driver insertion:
1. **Root Access:** Validates UID=0 Superuser execution.
2. **Module Subsystem:** Inspects `/proc/sys/kernel/modules_disabled` and `/proc/modules`.
3. **Signature Enforcement:** Checks `sig_enforce` across `/proc/cmdline`, module parameters, and kernel ring buffers.
4. **Architecture & ABI:** Validates 64-bit arm64-v8a compatibility.
5. **Driver Loading Capability:** Evaluates overall kernel acceptance readiness.
6. **SELinux & ueventd Policy:** Diagnoses `/dev` node auto-creation permissions across Android 11 through Android 14.
7. **Mount Namespace:** Validates Global vs Isolated namespace mode.
8. **Database Match:** Scans local and remote databases for exact vermagic matches.
9. **Stealth Device Node:** Checks stealth character device node positions.
10. **Final Verdict:** Clear actionable diagnosis with explicit fixes when needed.

### 3. Security & Driver Database Protection
- **AES-256-GCM Encryption:** Remote driver scripts and manifests are encrypted with a 256-bit symmetric key (`KLENC001` envelope with 12-byte random IVs and 128-bit authentication tags).
- **Anti-Theft Storage:** Downloaded drivers are never stored in unencrypted format on public storage (`/sdcard`).
- **In-Memory Pipe Streaming:** Drivers decrypt on-the-fly and pipe directly to `su`, deleting temporary buffers immediately upon module insertion.
- **R8 Obfuscation & Hardening:** Aggressive class and identifier renaming with runtime anti-debug and anti-frida checks.

### 4. Internationalization (28 Languages)
Supports auto IP-based detection and manual selection across 28 languages:
English, বাংলা (Bengali), हिन्दी (Hindi), اردو (Urdu), Bahasa Indonesia, Bahasa Melayu, العربية (Arabic), فارسی (Persian), Português, Русский (Russian), Українська (Ukrainian), Español, Français, Deutsch, Italiano, Türkçe, Tiếng Việt, ภาษาไทย (Thai), Filipino, 简体中文, 日本語, 한국어, Polski, नेपाली, မြန်မာ, ភាសាខ្មែរ, and සිංහල.

---

## 📱 Requirements

| Component | Requirement |
|---|---|
| Root Provider | Magisk, KernelSU, or APatch (Superuser access required) |
| Architecture | ARM64 (`aarch64` / `arm64-v8a`) |
| Android Version | Android 9.0 (API level 28) through Android 15 |
| Connectivity | Required only for OTA database refresh and in-app update checks |

---

## 🛠️ Building from Source

```bash
git clone https://github.com/bmjubairdadu/kernel-loader.git
cd kernel-loader
./gradlew :app:assembleRelease
```

Built release APK:
`app/build/outputs/apk/release/Kernel-Loader-v6.5.apk`

---

## 🤝 Contributing

Contributions are warmly welcome! Whether you are:
- Reporting an issue or bug on a specific device
- Submitting a new kernel `vermagic` driver compatibility profile
- Adding or improving localizations/translations (28+ languages supported)
- Opening a pull request with bug fixes, optimizations, or new features

Please check out our **[Contributing Guidelines](CONTRIBUTING.md)** and review our **[Code of Conduct](CODE_OF_CONDUCT.md)** before submitting pull requests.

Feel free to open an issue or reach out directly on Telegram if you'd like to collaborate!

---

## 📄 License & Author

- **Author:** [JUBAIR HOSEN](https://github.com/bmjubairdadu)
- **Telegram Support:** [@Jubairdadubm](https://t.me/Jubairdadubm)
- **License:** [GNU General Public License v3.0](LICENSE)
