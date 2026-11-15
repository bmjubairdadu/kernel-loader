<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="76" alt="Kernel Loader" onerror="this.style.display='none'"/>

# Kernel Loader (v5.9)

### Enterprise-Grade Kernel Driver Engine & Diagnostic Suite for Android
**Adaptive UI · AES-256-GCM Driver Security · Live Verification · SELinux Audit · OTA Updates**

[![Release](https://img.shields.io/github/v/release/bmjubairdadu/kernel-loader?color=blue&style=flat-square)](https://github.com/bmjubairdadu/kernel-loader/releases/latest)
[![Platform](https://img.shields.io/badge/platform-Android%209%2B%20arm64-green?style=flat-square)](#requirements)
[![Root](https://img.shields.io/badge/root-Magisk%20%7C%20KernelSU%20%7C%20APatch-red?style=flat-square)](#requirements)
[![Security](https://img.shields.io/badge/database-AES--256--GCM%20KLENC-purple?style=flat-square)](#security--driver-database-protection)
[![Languages](https://img.shields.io/badge/languages-28%20Global%20Locales-orange?style=flat-square)](https://github.com/bmjubairdadu/kernel-loader#internationalization-28-languages)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg?style=flat-square)](CONTRIBUTING.md)
[![Contributors](https://img.shields.io/github/contributors/bmjubairdadu/kernel-loader?style=flat-square)](https://github.com/bmjubairdadu/kernel-loader/graphs/contributors)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL%20v3-blue?style=flat-square)](LICENSE)

</div>

---

**Kernel Loader** is a powerful root-driven Android application engineered to inspect, verify, and load stealth Linux kernel drivers and polyglot `.sh` installer scripts across Android Linux kernels **4.9 through 6.12**. Built with modern Jetpack Compose, an adaptive universal layout engine, and military-grade AES-256-GCM payload encryption, Kernel Loader guarantees safe, authenticated, and seamless driver operation across all device form factors.

---

## ✨ What's New in v5.9

- **Clean Hyphenated Diagnostics Log:** Purged legacy verbose tags (`FTL_*`), presenting clean `- Step` diagnostic reporting directly in the terminal interface.
- **Robust Driver Parsing Engine:** Added fallback pattern matching for dynamic stealth nodes and kernel memory status, ensuring robust driver detection even with stripped logs.
- **Universal Proportional Layout Engine:** Automatically normalizes screen density to a 392dp baseline across 720p budget devices, 1080p standards, 2K flagships, foldables, and tablets without text truncation.
- **AES-256-GCM Encrypted Driver Security (`KLENC001`):** Complete remote driver database protection preventing unauthorized extraction, tampering, or third-party database theft.
- **Zero-Trace In-Memory Execution:** OTA drivers decrypt directly in memory and stream to the root process pipe; temporary execution buffers are wiped from disk instantly.
- **Always-Accessible Live Driver Verification:** One-click on-demand verification of active character nodes and stealth modules in `/dev`.

---

## 🚀 Key Features

### 1. Universal Adaptive UI
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
`app/build/outputs/apk/release/Kernel-Loader-v5.9.apk`

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
