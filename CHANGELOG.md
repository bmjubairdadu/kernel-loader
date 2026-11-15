# Changelog — Kernel Loader

All notable changes are documented here.

---

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
