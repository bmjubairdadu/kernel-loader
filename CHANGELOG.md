# Changelog — Kernel Loader

All notable changes are documented here.

---

## [6.3] — 2026-11-19 (tag `v6.3` / `versionCode 63`)
- **Pure In-Memory Direct Injection Architecture:** Fully transitioned from legacy runner scripts to native in-memory direct injection (`direct_inject_arm64`), streaming decrypted `.bin` containers straight into kernel memory through memory pipes with zero disk footprint.
- **Stealth Alphabetic Kernel Module Display:** Real-time genuine module name extraction directly from driver ELF headers, enforcing clean alphabetic stealth identification.
- **Streamlined Database:** Database purged and optimized for active production targets, featuring verified pure in-memory `4.9.337.bin`.
- **Zero Driver Binaries in APK:** Complete remote on-demand driver isolation ensuring absolute driver protection.

## [6.2] — 2026-11-17 (tag `v6.2` / `versionCode 62`)
- **Bulletproof On-Demand Driver Download Engine:** Upgraded OTA driver download pipeline with cryptographic AES-256-GCM authentication verification and dual-hash validation. Eliminates download failures and hash mismatches across all kernel variants (including 4.14.186 and 4.19.191).
- **HTTP Redirect & Multi-Mirror Failover:** Enhanced cloud mirrors with automatic 301/302/307 redirect resolution and multi-CDN failover (Fastly, GitHub Raw, jsDelivr, GCore).
- **Zero Driver Binaries in APK (100% Remote Security):** Strictly 0 driver binaries bundled inside APK assets. Drivers are fetched securely from remote database, injected directly into kernel memory, and unlinked instantly from disk with zero persistence.
- **Military-Grade Anti-Crack Shield:** Certificate SHA-256 validation, anti-debug watcher, anti-Frida/Xposed dynamic hook scanner, and anti-virtual sandbox containment.

## [6.1] — 2026-11-17 (tag `v6.1` / `versionCode 61`)
- **SecurityShield Runtime Hardening:** Comprehensive anti-tamper and anti-crack protections.
- **Remote On-Demand Driver Storage:** Transitioned to pure encrypted remote driver downloads with instant disk erasure.

## [6.0] — 2026-11-15 (tag `v6.0` / `versionCode 60`)
- **Deep Kernel & Node Auto-Creation Audit:** Audits whether `CONFIG_DEVTMPFS=y` is active for synchronous kernel-level device node creation or if asynchronous `ueventd` daemon handling is required.
- **ARM64 Module PLT Kernel Audit:** Inspects `CONFIG_ARM64_MODULE_PLTS=y` to verify kernel relocations and module PLT memory layout compatibility.
- **Adaptive Dynamic Sysfs & Node Polling Engine:** Automatically polls `/dev` and `/sys/class` with zero unnecessary waiting, finishing immediately the moment the node appears, with gentle sysfs uevent pulses when needed.
- **Hardened Node Permissions (0600 root:root):** Driver character device nodes in `/dev` are strictly locked to `0600` (`root:root`) permissions, preventing any unprivileged user application or anti-cheat scanner from accessing or probing the node.
- **Filtered Internal Diagnostic Markers:** Silently filters internal `FTL_*` metadata (`FTL_OK`, `FTL_NODE`, `FTL_DEVNAME`) from user-visible terminal logs while preserving real-time in-memory tracking.
- **Automated GitHub Tag Release Pipeline:** Attached release APK asset generation directly to GitHub Actions tag builds.

## [5.9] — 2026-11-15 (tag `v5.9` / `versionCode 59`)
- **Clean Hyphenated Diagnostics Log:** Purged legacy verbose metadata tags (`FTL_*`), presenting clean `- Step` diagnostic reporting directly in the terminal interface.
- **Robust Driver Parsing Engine:** Added fallback pattern matching for dynamic stealth nodes and kernel memory status, ensuring robust driver detection even with stripped logs.
- **Universal Proportional Layout Engine:** Automatically normalizes screen density to a 392dp baseline across all display sizes and densities without text truncation.
- **AES-256-GCM Encrypted Driver Security (KLENC001):** Military-grade payload encryption protects the remote driver database.
- **Zero-Trace In-Memory Pipe Execution:** Decryption happens in memory and streams directly to root execution (`su`).

## [5.8] — 2026-11-15 (tag `v5.8` / `versionCode 58`)
- **Universal Adaptive UI Engine:** Proportional display scaling based on a 392dp baseline. Cards, headers, badges, and symmetric status boxes render cleanly across all screen sizes, densities (720p, 1080p, 2K, 4K), foldables, and tablets without text truncation.
- **AES-256-GCM Driver Security (KLENC001):** Military-grade payload encryption protects the remote driver database. Raw files downloaded from GitHub remain indecipherable ciphertext to unauthorized third parties.
- **Zero-Trace In-Memory Pipe Execution:** Decryption happens in memory and streams directly to root execution (`su`); temporary execution buffers are unlinked from disk immediately upon insmod.
- **SELinux & ueventd Node Audit:** 10-point kernel diagnostic scan explicitly displaying SELinux mode, ueventd device node auto-creation permissions, and actionable resolution steps.
- **Always-Accessible Live Verification:** One-click on-demand verification of active stealth character device nodes in `/dev`.
- **Cleaned Localization (28 Languages):** Redesigned and streamlined text across 28 global languages with auto IP country detection and globe picker dialog.
- **Clean Production Codebase:** Removed obsolete scripts, unused legacy documentation, and bundled binaries from git tracking.
