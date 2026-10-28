# Changelog — Kernel Loader

All notable changes are documented here (newest first).
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

---

## [2.8] — 2026-10-28 (tag `v2.8` / `versionCode 28`) 1-Click In-App Kernel Signature Auto-Unlock & English-First Dialogs

> **Release v2.8:** Autonomous in-app kernel boot image patching for `CONFIG_MODULE_SIG_FORCE`, embedded `magiskboot` for zero-dependency execution across APatch, Magisk, and KernelSU, English-default error guidance dialogs, and instant 1-click reboot action.

### Added
* **1-Click In-App Auto-Unlock (`autoUnlockKernelSignature`):** Automatically detects `CONFIG_MODULE_SIG_FORCE` signature enforcement upon driver insertion, dumps the active boot partition, hexpatches `is_module_sig_enforced` for OnePlus, Realme, Infinix, Samsung, and Xiaomi kernels, repacks, and writes back to boot partition via root within 2 seconds.
* **Embedded `magiskboot` in APK Assets:** Bundled `magiskboot` directly inside the APK for universal, self-contained kernel patching without relying on external root directories.
* **Instant 1-Click Reboot Action:** Displays a dedicated "🔄 Reboot Now" action button inside the dialog that triggers clean device restart via `svc power reboot`.
* **English-First Default UI:** Error guidance dialogs now open in 100% English by default, while retaining the one-tap Bengali toggle option.
* **Engine Bridge Integration:** Fully connected with `/data/adb/kernel_loader` for enhanced driver staging and insertion.

---

## [2.5] — 2026-10-28 (tag `v2.5` / `versionCode 25`) Bilingual Error Resolution Dialog & Signature Lock Guidance

> **Release v2.5:** Introduces modern rounded bilingual (Bengali/English) error guidance dialogs on driver load failure, exact kernel signature lock detection (`CONFIG_MODULE_SIG_FORCE`), sanitized insmod fallback verification for APatch/KernelPatch/KernelSU, and Linux 6.6 support.

### Added
* **Modern Rounded Bilingual Error Dialog (`DriverErrorDialog`):** Beautiful 26.dp rounded-corner popup dialog with dark glassmorphic styling, warning accent badge, and instant one-tap bilingual switching between 🇧🇩 বাংলা and 🇬🇧 English.
* **Actionable Step-by-Step Fixes:** Intelligently categorizes errors (Signature Locked, Root Denied, Vermagic Mismatch, No DB Match, etc.) and gives users exact numbered steps to resolve them.
* **One-Tap Diagnostic Copy:** Includes a "Copy Log / লগ কপি" button inside the dialog to instantly copy terminal output and dmesg traces to the clipboard.

### Fixed
* **Sanitized Insmod Fallback Execution:** Fixed confusing `/data/adb/magisk/busybox: inaccessible or not found` errors by validating binary executable status (`test -x`) before fallback execution on non-Magisk root setups (KernelPatch / APatch / KernelSU).
* **Accurate Kernel Signature Lock Detection:** Added detection for `Loading of unsigned module is rejected` in dmesg to accurately guide users to enable `BypassSig` KPM or flash `Universal_Kernel_Sig_Bypass.zip`.
* **Cleaned Obsolete Guidance:** Removed obsolete "PATCH BOOT" UI references.
* **Linux 6.1+ & 6.6 Modern Maple Tree VMAs:** Updated driver core with `VMA_ITERATOR` and `for_each_vma` for full compatibility with modern 6.6 kernels.

---

## [2.2] — 2026-10-27 (tag `v2.2` / `versionCode 22`) Fix False Positive Driver Detection & Cold Boot Restart Button

> **Release v2.2:** Fixes premature "RESTART" button display on cold launch before any driver is loaded. Adds strict system character device filtering and verified boot state validation.

### Fixed
* **False Positive Driver Detection Fixed:** Resolved critical issue where standard Android character devices (`/dev/ashmem`, `/dev/binder`, `/dev/random`) matched the generic alphanumeric regex in `probeOurDriverNode`, causing the app to falsely detect a resident driver and display "RESTART" instead of "LOAD" upon startup.
* **System Node Blacklist (`SafetyGuard.SYSTEM_DEV_NODES`):** Hardened device node and sysfs class detection against all core Linux/Android system devices (`ashmem`, `binder`, `random`, `ion`, etc.) so only genuine custom drivers are ever recognized.
* **Cold Boot State Sanitization:** In `DriverViewModel`, `driverLoaded` now stays strictly `false` until background active verification confirms a live custom module in `/proc/modules` or an active custom node. Corrupted or stale session states are automatically cleared on startup.
* **Reboot Timing Invalidation:** Enhanced `BootSessionTracker` with uptime-based boot timestamp comparison (`bootTime > loadTime`) to guarantee cached sessions are immediately invalidated across device reboots even if root access is delayed on launch.

---

## [2.1] — 2026-10-27 (tag `v2.1` / `versionCode 21`) Boot Session Persistence & Background Recents State Fix

> **Release v2.1:** Fixes driver state reset after clearing app from background/recents. Introduces persistent boot session tracking so the "RESTART" button correctly stays active across process restarts while the kernel driver is loaded.

### Fixed
* **Background Recents Clear State Fix:** Resolved issue where swiping the app away from recent apps caused `driverLoaded` to reset to false, incorrectly displaying the "LOAD" button upon reopening instead of "RESTART" even though the driver was resident in kernel memory.
* **Persistent Boot Session Tracking (`BootSessionTracker`):** State is now synchronously restored on launch using `/proc/sys/kernel/random/boot_id` matching, preventing button flicker and correctly preserving loaded driver state across app life cycles until device reboot.
* **Alphanumeric Character Device Node Detection:** Extended stealth character node probe regex to support full 62-character alphanumeric random node names generated by Driver Loader V3 architecture.

---

## [2.0] — 2026-10-27 (tag `v2.0` / `versionCode 20`) Unified Pipeline, Boot Image Diagnostics & Hardened Security

> **Major Release v2.0:** Unified driver architecture, in-app Driver.sh memory verification, boot.img and kallsyms diagnostic pack generator, anti-Frida and anti-debug security shield, and full R8 code obfuscation.

### Added & Improved
* **Unified Driver Loading Pipeline.** Replaced legacy multi-variant switching logic with a unified single-pipeline driver engine.
* **In-App R/W Memory Verification.** Integrated direct execution of the `Driver.sh` memory engine within the terminal screen to verify physical memory read and write operations.
* **Kernel-Level Error Reporting.** Eliminated generic verification error messages; replaced with exact root-cause failure output extracted directly from `Driver.sh` and kernel `dmesg`.
* **Automated Boot Image Extraction.** Automatically discovers active boot partition block devices across Qualcomm, MediaTek, Exynos, and Tensor platforms and dumps `boot.img` for custom driver compilation.
* **Kernel Symbol & Vendor Module Collection.** Captures `/proc/kallsyms`, decompresses `/proc/config.gz`, extracts sample OEM vendor modules (`.ko`) from `/vendor/lib/modules/`, and records vermagic string metadata.
* **Hardened Security & Anti-Tamper Shield.** Added runtime protection against Frida instrumentation, debuggers, and Xposed hooks, combined with full-mode R8 code obfuscation and strict proxy-bypass network security policies.
* **Clean Codebase Architecture.** Completely removed dead engines, legacy assets, and unused APK binaries.
* **Version Bump.** Incremented to `versionCode: 20` and `versionName: "2.0"`.

---

## [1.9] — 2026-10-26 (tag `v1.9` / `versionCode 10`) Pure Stealth Engine & Online OTA Pipeline

> **Seamless In-App Update for all previous versions:** Pure stealth mode enforcement by removing static blacklisted aliases (`entryi`, `wanbai`, `dmsync`, `memacc`), asset size optimization by utilizing online OTA driver store exclusively, and 100% comment-free codebase.

### ✨ Added & Improved
* **Pure Stealth Mode Enforcement.** Automatically removes known blacklisted aliases (`/dev/entryi`, `/dev/wanbai`, `/dev/dmsync`, `/dev/memacc`) and relies purely on dynamic randomized character nodes to evade anti-cheat heuristics.
* **Streamlined Online OTA Pipeline.** Removed redundant bundled driver files from app assets, optimizing APK footprint while ensuring the freshest builds are resolved directly from the live OTA database.
* **Seamless In-App Auto-Update.** Incremented to `versionCode: 10` and `versionName: "1.9"`.
* **Zero Comments.** 100% comment-free across all source files.

---

## [1.8] — 2026-10-26 (tag `v1.8` / `versionCode 9`) 🌐 Live OTA Cache-Control & Binary Asset Attributes

> **Seamless In-App Update for all previous versions:** Disabled HTTP caching in OtaDriverStore to guarantee real-time fresh manifest and driver downloads, fixed asset binary gitattributes, smart boot session persistence, and 100% comment-free codebase.

### ✨ Added & Improved
* **HTTP No-Cache Bypass (`OtaDriverStore.kt`).** Added explicit `Cache-Control: no-cache`, `Pragma: no-cache`, and disabled internal HTTP caching (`useCaches = false`) to ensure the app never receives stale cached driver manifests or driver binaries from GitHub/CDN proxies, guaranteeing that fresh and updated fixed drivers are always downloaded directly in real-time.
* **Binary Asset Protection (`.gitattributes`).** Explicitly configured Git attributes to treat internal asset binaries (`driver.sh`, `dmtest`, `myprobe`) as binary files (`-text`), preventing any newline conversion (CRLF/LF) corruption during checkout and builds.
* **Smart Boot Session & Active Node Protection.** Preserves active `/dev` nodes across app minimization and resumes, preventing game disconnects while maintaining clean startup on device reboot.
* **Seamless In-App Auto-Update.** Incremented to `versionCode: 9` and `versionName: "1.8"`.
* **Zero Comments.** 100% comment-free across all source files.

---

## [1.7] — 2026-10-26 (tag `v1.7` / `versionCode 8`) 🛡️ Smart Boot Session Tracking & Active Node Guard

> **Seamless In-App Update for all previous versions:** Smart Linux `boot_id` session tracking, active device node protection on app switch/resume, clean fresh boot detection, and 100% comment-free codebase.

### ✨ Added & Improved
* **Active Node Guard (App Switch Safety).** Solved the issue where re-entering the app after loading would mistakenly wipe active device nodes. The app now tracks the active session via Linux `/proc/sys/kernel/random/boot_id` and protects live `/dev` nodes so running games/tools are never disconnected.
* **Smart Boot Session Tracking.** Differentiates between clean phone reboot (starts fresh with LOAD button) and active driver sessions on app resume.
* **Seamless In-App Auto-Update.** Incremented to `versionCode: 8` and `versionName: "1.7"`.

---

## [1.6] — 2026-10-25 (tag `v1.6` / `versionCode 7`) ⚡ Fresh Launch State Fix & Force Reload Control

> **Seamless In-App Update for all previous versions:** Fixed premature "RESTART" button state on app launch, ensured "LOAD" is always ready and available, eliminated false-positive resident locks, added direct "FORCE RELOAD DRIVER" control, and maintained 100% comment-free codebase.

### ✨ Added & Improved
* **App Launch Button State Fix.** Fixed the issue where the action button erroneously showed "RESTART" upon opening the app before the user ever tapped "LOAD". On app launch, `driverLoaded` starts clean and the primary button is always **LOAD**.
* **Elimination of False-Positive Residency Locks.** Fixed `SafetyGuard.hiddenDriverClass()` and startup state checks so unverified sysfs directories or previous dead entries in `/sys/class` do not hijack the app into a locked "RESTART" state.
* **Direct "FORCE RELOAD DRIVER" Action.** Added a dedicated secondary action inside the Unload panel allowing users to instantly re-run the driver loading pipeline without having to reboot their device.
* **Seamless In-App Auto-Update.** Incremented to `versionCode: 7` and `versionName: "1.6"`.

---

## [1.5] — 2026-10-25 (tag `v1.5` / `versionCode 6`) 🛡️ Direct Node Adoption & Probe Unblock

> **Seamless In-App Update for all previous versions:** Eliminated aggressive dmtest/myprobe node deletion, adopted native Kernel_Drivers direct node activation architecture, support for OpenELA EFAULT (errno 14) and stealth classes, and 100% comment-free codebase.

### ✨ Added & Improved
* **Direct Node Adoption (Kernel_Drivers Architecture).** Replaced aggressive diagnostic probe blocking with direct sysfs/devnode adoption. When the kernel module inserts and registers its device node, the node is immediately assigned permissions (`chmod 0666`, `chcon u:object_r:device:s0`) and activated.
* **Elimination of False Node Deletion.** Removed the flawed `rm -f /dev/$node` logic that deleted valid driver nodes when diagnostic tools returned unexpected errnos (e.g. `errno=14 EFAULT` on OpenELA 4.14 kernels with modified `addr_limit` layouts).
* **Stealth Class & Engine Pick Detection.** Automatically detects random 6-8 letter stealth classes (e.g. `NBBGYB`) from `/sys/class` and accepts engine-picked character devices.
* **Seamless In-App Auto-Update.** Incremented to `versionCode: 6` and `versionName: "1.5"`.

---

## [1.4] — 2026-10-25 (tag `v1.4` / `versionCode 5`) 🎯 Exact Vermagic & Commit-Hash Driver Matching

> **Seamless In-App Update for all previous versions:** Full vermagic & git commit-hash matching, hardware vendor `.ko` modinfo extraction, prevention of duplicate "THIS DEVICE" badges on matching major.minor.patch versions, and 100% comment-free codebase.

### ✨ Added & Improved
* **Exact Vermagic & Release String Matching (`OtaDriverStore.isExactMatch`).** Prevents false driver resolution by strictly checking full kernel release strings (`uname -r`), git commit hashes (`-g[0-9a-f]+`), localversion suffixes (`-perf+`), and modinfo vermagic strings rather than solely relying on short 3-part versions (`4.19.157`).
* **Accurate "THIS DEVICE" Badge & List Placement in UI.** Devices running a specific build (e.g. `4.19.157-perf-g1a6087bc0325-dirty`) now exclusively show the "THIS DEVICE" badge on their exact build match. Sibling kernels sharing the same base version (`4.19.157-perf+` and `4.19.157-perf-g9aba441cf6cd-dirty`) no longer falsely display the badge.
* **Direct Vendor Module Vermagic Extractor (`RootChecker.getKernelVermagic`).** Reads `vermagic=` directly from `/vendor/lib/modules/`, `/odm/lib/modules/`, and `/system/lib/modules/` with fallback to toybox stream parsing, verifying complete kernel configuration compatibility prior to download and staging.
* **Precise Revocation & Resolution Ladder.** Revocation manager and OTA resolver now resolve drivers strictly against exact vermagic and git commit hashes before falling back to nearest series.
* **Seamless In-App Auto-Update.** Incremented to `versionCode: 5` and `versionName: "1.4"`.

---

## [1.3] — 2026-10-25 (tag `v1.3` / `versionCode 4`) 🚀 Dynamic ARM64 Machine Code Patcher & Bundled Magiskboot

> **Seamless In-App Update for all previous versions:** Native ARM64 kernel machine code patcher for `module_sig_check`, `check_modinfo` and `check_version` with PAC support, internal bundling of standalone `magiskboot` binary in application assets, automatic extraction and pre-flight deployment, and zero-comment codebase.

### ✨ Added & Improved
* **Dynamic ARM64 Machine Code Patcher (`patchKernelSymbols`).** Directly parses `/proc/kallsyms` to identify symbol offsets and patches kernel instructions in memory/binary:
  - `module_sig_check` patched to `mov w0, #0; ret` (permanently neutralizing signature enforcement).
  - `check_modinfo` patched to `mov w0, #0; ret` (bypassing vermagic rejection).
  - `check_version` patched to `mov w0, #1; ret` (bypassing symbol CRC version mismatches).
  - Full ARM64 PAC (`PACIASP` / `AUTIASP`) awareness to preserve control flow integrity.
* **Bundled Standalone `magiskboot` Tool.** Directly bundled static ARM64 `magiskboot` binary inside application assets (`bin/magiskboot`), automatically extracted to `/data/local/tmp/magiskboot` (`chmod 755`) on boot patch or diagnostic initialization.
* **Seamless In-App Auto-Update.** Incremented to `versionCode: 4` and `versionName: "1.3"`. All existing installations (v1.0, v1.1, v1.2) automatically detect, download, and install this release.
* **Clean Zero-Comment Codebase.** Fully preserved comment-free codebase across all source files.

---

## [1.2] — 2026-10-25 (tag `v1.2` / `versionCode 3`) 🛡️ FolkPatch & Signature Bypass Overhaul

> **Seamless In-App Update for v1.0 & v1.1:** Prioritized kernel signature bypass via Magiskboot hex-patching, elimination of phantom `kptools` framework execution, stealth driver detection via direct IOCTL probes, expanded busybox insmod ladder for FolkPatch/APatch, and clean zero-comment codebase.

### ✨ Added & Improved
* **Prioritized Kernel Signature Bypass in Boot Patcher.** `BootPatcherEngine.kt` now prioritizes Unpack -> Kernel Decompress -> HexPatch (`CONFIG_MODULE_SIG_FORCE=y` → `n`, `CONFIG_MODULE_SIG_ALL=y` → `n`, `module_sig_enforce` → `module_sig_disable`, and cmdline `module.sig_enforce=0`) -> Repack. Permanently unlocks unsigned driver insertion on Oppo, Realme, OnePlus, Xiaomi, and other strict vendor kernels while keeping existing FolkPatch / APatch / Magisk root completely intact.
* **Stealth Driver Detection in Framework & Ladder.** In addition to `/proc/modules`, the loading ladder now actively probes the driver node via direct IOCTL (`0x803`/`0x801`). Stealth modules (like RT Stealth Engine) that unlink themselves from `/proc/modules` immediately on load are instantly detected and verified.
* **Expanded Busybox Insmod Ladder.** Added native binary checks for `/data/adb/ap/bin/busybox insmod`, `/data/adb/ksu/bin/busybox insmod`, and `/data/adb/magisk/busybox insmod` across both `UniversalKernelLoader.kt` and `DriverViewModel.kt`.
* **Clear Signature-Lock Guidance.** When encountering `CONFIG_MODULE_SIG_FORCE=y` (`Required key not available`), the UI accurately diagnoses kernel signature enforcement and guides the user directly to tap `PATCH BOOT` or use a BypassSig KPM, eliminating misleading error descriptions.
* **Zero-Comment Clean Codebase.** Completely stripped all inline, block, and docstring comments from project source code files.
* **Seamless In-App Auto-Update.** Bumped to `versionCode: 3` and `versionName: "1.2"` for automatic background discovery and installation on existing installations.

### 🛠️ Fixed
* **Phantom APatch `kptools --load-module` Removed.** Removed the non-existent `kptools --load-module` framework call that printed usage help, exited 0, and caused false-positive success logs followed by a 3-second delay.
* **Recompression Safety Guard.** Added file verification guard so `magiskboot compress=auto` is only executed when `kernel.raw` is decompressed, preventing kernel truncation on uncompressed kernels.

---

## [1.1] — 2026-10-25 (tag `v1.1` / `versionCode 2`) ⚡ Direct IOCTL Probe & 6-8 Char Random Node Support

> **Seamless In-App Update for v1.0:** Total elimination of erroneous node deletion, universal 6-character and 8-character random node detection (`^[A-GNYB]{6,8}$`), direct IOCTL probing (IOCTL `0x803` ModBase & `0x801` MemRead), auto mknod recovery via sysfs, and bundled `driver.sh` standalone diagnostic deployment.

### ✨ Added & Improved
* **Direct IOCTL Verification Engine (`directIoctlProbe`).** Implemented high-reliability direct IOCTL testing (`0x803` ModBase & `0x801` MemRead) replicating `Driver.sh` behaviour. Once a candidate device node answers validly, the app adopts it instantly with zero risk of premature rejection.
* **Universal 6 & 8 Character Node Support (`^[A-GNYB]{6,8}$`).** Replaced legacy strict 8-character limits with universal `{6,8}` regex matching across `SafetyGuard.kt`, `DriverViewModel.kt`, and `UniversalKernelLoader.kt`. Supports both legacy 6-char `entry.c` builds and modern 8-char random-name drivers.
* **Standalone Driver Diagnostic Tool (`driver.sh`).** Bundled `driver.sh` in application assets (`bin/driver.sh`) and automatically extracted to `/data/local/tmp/driver.sh` (`chmod 0755`) on launch for independent terminal diagnostic checks.
* **Sysfs Class mknod Auto-Recovery.** When a module successfully registers with the kernel but devtmpfs fails to instantiate `/dev` nodes, the loader parses `/sys/class` major:minor numbers and executes safe `mknod -m 666` and SELinux label injection (`chcon u:object_r:device:s0`).
* **Interactive VERIFY Button Integration.** Tap-to-verify action in UI now triggers direct IOCTL interrogation to report `"Driver 100% active & operational! (Direct IOCTL Verified)"`.
* **Seamless In-App Auto-Update.** Built with `versionCode: 2` and `versionName: "1.1"`, allowing existing v1.0 installations to automatically detect, download, and install this update via the built-in updater.

### 🛠️ Fixed
* **Permanent Resolution of Node Deletion Bug.** Completely eliminated the bug where unverified random nodes were mistakenly removed with `rm -f /dev/$node` (which caused "Node /dev/ missing"). Nodes are verified non-destructively through direct ABI probes.

---

## [1.0] — 2026-10-25 (tag `v1.0` / `versionCode 1`) 🚀 Official Stability & General Release

> **Official Stable Release:** Full compatibility with `Kernel_Drivers` zero-param insmod ladder, critical revocation watcher reboot-loop fix, safe sysfs and `/dev` device node discovery, hardened anti-tamper with debug support, and foreground service lifecycle reliability.

### ✨ Added & Improved
* **Zero-Param insmod Compatibility.** Preserved native zero-parameter `insmod` ladder compatibility matching `Kernel_Drivers` loader specifications.
* **Hardened Device Node Sanitization.** `/dev` cleanup only prunes recognized driver node names (`^[BDEFGACNY]{8}$`, `dmsync`, `kmem_337`, `memacc`), completely eliminating risk to system device nodes.
* **Android 13+ Notification Permissions.** Declared `POST_NOTIFICATIONS` permission in manifest for reliable foreground service notifications on modern Android versions.
* **Optimized Build Environment.** Increased Gradle Metaspace heap allocation to 768MB in `gradle.properties` to ensure zero compilation memory starvation.

### 🛠️ Fixed
* **Driver Revocation Loop Fixed.** Corrected evaluation guard in `DriverRevocationManager` (`record.loadedFromOta`) so that custom/local `.ko` drivers are never falsely flagged as revoked, permanently preventing unwanted automatic device reboots.
* **Anti-Tamper & LSPosed Compatibility.** Resolved false-positive tamper trigger for devices running Xposed/LSPosed frameworks, and allowed seamless debug testing under `BuildConfig.DEBUG`.
* **Sysfs Class Pattern Matching.** Fixed pattern matching in `mknodPatternSysClasses()` to use the exact character pool regex (`^[BDEFGACNY]{8}$`).
* **Lifecycle Crash Guard.** Removed destructive `killProcess` call from `MainActivity.onDestroy()`, allowing graceful multi-tasking and preventing abrupt crashes during system lifecycle transitions.
* **Repository Cleanup.** Removed outdated binary artifacts, resolved version naming conflicts, and normalized release packaging as `Kernel-Loader-v1.0.apk`.

---

## [1.3] — 2026-10-24 (versionCode 7, tag `v1.3` / `v7`) ⚡ Smart Detection & Signature Security Release

> Intelligent multi-layered QX vs RT variant detection, automatic kernel module signature verification bypass (`CONFIG_MODULE_SIG_FORCE`), sequential pre-load diagnostic checks, remote driver revocation watchdog, single-line console layout, and complete codebase streamlining.

### ✨ Added
* **Smart Multi-Layer Variant Detection.** Automatically evaluates and selects the optimal driver variant (`QX` vs `RT`) using full vermagic matching, kernel release names, and compatibility scoring. Prioritizes QX on gaming kernel profiles (such as `4.9.337-DaisyForGaming`) while selecting native RT on modern GKI kernels (5.x / 6.x) or when only RT is supported.
* **Kernel Signature Verification Bypass (`module_sig_enforce`).** Added dynamic bypass for kernels with `CONFIG_MODULE_SIG_FORCE` enabled via `/proc/sys/kernel/module_sig_enforce`, SELinux policy injection, and verity props to prevent `ENOKEY` / `EKEYREJECTED` load blocks.
* **Module Signature Stripper (`stripModuleSig`).** Automatically strips invalid or untrusted vendor signature metadata appended to `.ko` files before insertion, enabling custom driver loading on restrictive kernels.
* **Live Pre-Load Diagnostics.** Streams paced, real-time diagnostic checks (`Root Permission` -> `Kernel & Arch` -> `Signature Security` -> `Driver Database Scan` -> `Variant Detection`) to the console with realistic intervals before loading modules.
* **Remote Driver Revocation Watchdog.** Background root daemon (`kloader_daemon.sh`) and `DriverWatchdogService` verify driver license validity against remote database; immediately wipes module and reboots device if access is revoked.

### 🛠️ Fixed
* **Console Single-Line Layout & Ellipsis Protection.** Enforced single-line rendering with text ellipsis (`maxLines = 1`, `TextOverflow.Ellipsis`) on both Home and Terminal consoles, preventing text wrapping or card overflow.
* **Console Noise & Tag Stripping.** Removed bracket prefixes (`[DEVNODE]`, `[STAGE]`, etc.) and header banners for clean, uniform `- ` log lines.
* **Console Auto-Scroll Tracking.** Console list locks to the latest incoming line automatically during active load pipelines.
* **Social Asset Sizing.** Fixed WhatsApp and Telegram vector drawable display sizing across devices.
* **Clean Codebase.** Preserved complete code cleanliness with all comments stripped across the project.

### 🔄 Changed
* Built with `versionCode 7` and `versionName 1.3`.

---

## [1.2] — 2026-10-21 (versionCode 6, tag `v6`) 🚀 Auto-Update & Persistence Release

> Seamless background auto-updates, persistent terminal logs across app clears, direct Telegram issue reports with device diagnostics, universal device node symlinks, and enhanced resident stealth driver detection.

### ✨ Added
* **Automatic Background Update Download.** When a new release is detected on GitHub, the APK is automatically downloaded in the background. The update card shows download progress and switches to "UPDATE READY TO INSTALL", allowing instant installation with a single tap without download delays.
* **Periodic Background Update Checks.** Checks for new updates automatically every 15 minutes while the app is active, as well as on manual database refresh.
* **Direct Telegram Reporting (@Jubairdadubm).** Replaced GitHub issue reporting with one-tap Telegram report submission. Packs `report.txt`, `Module.symvers.txt` (CRC table), `config.txt`, `dmesg.txt`, and `applog.txt` into a ZIP and attaches it directly to Telegram for `@Jubairdadubm`.
* **Universal Device Node Symlinks.** When a driver loads, all standard candidate nodes (`/dev/wanbai`, `/dev/dmsync`, `/dev/dmsync_rt`, `/dev/dmsync_qx`, `/dev/memacc`, `/dev/entryi`, `/dev/kmem_337`, `/dev/CheckMe`, `/dev/lanran`, `/dev/video188`) are automatically symlinked and given `0666` permissions so any third-party tool finds its expected node immediately.
* **Detailed Module Operation Logging.** Added granular, real-time stage logging for every step of the kernel module insertion and validation pipeline.

### 🛠️ Fixed
* **Terminal Log Persistence Across App Clear/Kill.** Converted `DriverViewModel` to an `AndroidViewModel` and implemented synchronous disk log persistence (`files/console_history.log`) so terminal logs survive background app termination and app restarts.
* **Resident Hidden Driver Detection.** `SafetyGuard.hiddenDriverClass()` now recognizes all prefix patterns (`dmsync*`, `kmem*`, `memacc*`) so resident drivers in memory are gracefully reused without triggering `File exists` errors on reload.
* **Flexible Version Code Extraction.** `AppUpdateChecker` now parses `versionCode` from release body, titles, and tags (`extractVersionCode`), preventing version detection failures on non-integer tags.
* **Chooser Flag Crash Guard.** Added `FLAG_ACTIVITY_NEW_TASK` to `Intent.createChooser` in `SupportContact` to avoid potential runtime exceptions on non-Activity contexts.

### 🔄 Changed
* Built with `versionCode 6` and `versionName 1.2`.

---

## [Driver 3.2.2] — 2026-10-20 (OTA, no APK change needed) 🛠️ RT ABI hotfix

> **"Loads but does nothing" root cause fixed.** The 3.1/3.2.1 RT builds
> returned `0` for a successful read/write and `-EIO` for a failure — the
> exact inverse of the legacy RT protocol used by the reference
> `kmem_337.c`, the embedded 3.0-337 driver, and the real client. Every
> successful access was therefore reported to the client as a failure.
> 3.2.2 restores the legacy convention.

### 🛠️ Fixed
* **RT read/write return convention restored (live-proven).** `0x801`
  read and `0x802` write now return `-EIO` (userspace `-1`, `errno=5`) on
  success and `0` on failure, exactly like the legacy RT drivers. QX is
  unchanged (`0` success / `-1` failure). The two conventions are
  mutually exclusive, so a single dual-ABI binary is not possible; the RT
  and QX variants stay separate.
* **Modbase path buffer 256 → 1024 bytes.** `d_path()` can now resolve
  deeply nested Android executable paths instead of failing with
  `-ENAMETOOLONG` and silently returning `0`.
* **Exact legacy RT ioctl surface.** RT implements only `0x801` read,
  `0x802` write and `0x803` modbase; any other command returns `-EINVAL`,
  matching the reference RT `dispatch_ioctl`. `0x803` still returns `0`
  and fills `base`.

### ✅ Verified
* **Live daisy phone** (`4.9.337-DaisyForGaming #12`, Magisk root):
  insmod OK, `/dev/wanbai` 0666, `dmtest` v3 **15/15 PASS**; the RT report
  exercises only `0x801`/`0x802`/`0x803`. Raw proof: `0x801` success
  `rc=-1 errno=5 (EIO)`, invalid address `rc=0`; modbase
  init/libc/linker64/libm/libdl/libbase/libcutils + self/child PASS; own/
  cross-process R/W and cross-page PASS.
* **QEMU 4.14.264 ABI harness:** 14/14 + compat32 5/5 + 2500× stress clean
  with the legacy `-EIO` expectations.
* Static: exact vermagic and 48/48 imported CRCs against the daisy
  `Module.symvers`.
* OTA DB entry replaced with the fixed binary and marked
  `verified:true`, `testStatus:"live-pass"`. QX remains the live-verified
  3.2.1 build.

---

## [1.1] — 2026-10-15 (versionCode 5, tag `v5`) 🥷 Stealth release

> The driver now hides one-way on load — exactly like the reference RT
> drivers. No way back, so UNLOAD becomes RESTART. Reads and writes leave
> no fault/COW/pagemap trace behind.

### ✨ Added
* **LOADED BUT HIDDEN status.** When the module is live but invisible in
  `lsmod`, the home screen says so explicitly (new `hiddenLoaded` state,
  also picked up by refresh) instead of a bare `LOADED ·`.
* **Resident-driver reuse.** LOAD checks `/sys/class/dmsync*` first and
  reuses the already-loaded hidden driver (node verified) instead of
  running `insmod` into `File exists`; the built-in loader treats
  `File exists` as already-loaded too.
* **Animated home rings.** The LOAD ring (driver not loaded) and the
  RESTART ring (driver resident) breathe and sweep slowly (5.2 s/turn)
  while idle and spin fast while working. The sweep is a layer-only
  transform fed by a throttled frame loop (~20 updates/s): no
  recomposition, no idle repaint, and it stops by itself when the ring is
  hidden or the screen is off (measured 2.9% janky frames vs ~100% for
  the naive 60 fps version).
* **RESTART feedback.** Tapping RESTART flips the panel straight to a
  spinner + "REBOOTING", so the tap is visibly acknowledged while the
  reboot lands.

### 🔄 Changed
* **UNLOAD → RESTART** (home + terminal screens). Only a reboot unloads
  the driver now — the button runs `reboot` via the root shell. The old
  rmmod/unhide ladder and the `0x806` helper path are removed.
* **Driver 3.2.x core (via OTA, no APK change needed).**
  Physical-alias read/write (page-table walk + `ioremap_cache` — no GUP,
  no `FOLL_FORCE`, no PTE dirty, no cache flush, no `mmap` locking, no
  rate limiter) · minimal ioctl surface (RT `0x801/0x802/0x803`,
  QX `0x801–0x805`) · neutral names (`dmsync_rt` / `dmsync_qx`, single
  `/dev` node, zero `memacc`/`ksync` strings left).
* **3.2.1:** the 32-bit compat modbase path now checks its second copy
  (gcc-11 build fix, 64-bit path untouched).
* **Idle repaint killed.** The home screen no longer repaints while
  nothing moves, and the load pipeline's shell work runs off the main
  thread — long root calls can't freeze the UI anymore.

### 🛠️ Fixed
* **Rebuilt `rt/qx_4.14.113-25741605.ko` from the Samsung M31 source.**
  The phone's kernel has no CRC table, so the old entry — no CRCs but a
  `modversions` vermagic flag — could never load. The new build carries
  the exact vermagic `4.14.113-25741605 SMP preempt mod_unload aarch64`.
* **`rt/qx_4.9.337-DaisyForGaming.ko`** updated to the same 3.2.0 core
  (exact CRCs, phone-tested).
* **Release-tag rule enforced.** The first `v1.1` tag parsed as `1`, so
  installed v1.0 apps reported "up to date". Retagged as `v5` and
  documented in `scripts/publish_apk.ps1`.

## [1.0] - 2026-10-11

> **Fresh start.** Versioning restarts at 1.0 and this is the shipped
> baseline: tag, release asset and the app itself all read v1.0. It carries
> the full on-device hardening pass (unload, autoload switch, root state)
> on top of the Driver Engine 3.1 rollout.

### Added
* **Kernel Driver 3.1 architecture rollout across all kernels (4.9 … 6.6, RT & QX).**
  The driver engine has been upgraded to version 3.1 across all 42 supported kernel
  configurations in the OTA database and embedded APK assets:
  - **Shared core engine (`kmem_337_core.h`)**: Single-bounce kernel I/O, stack fast
    path (zero slab/kmalloc allocations for reads $\le$ 256 bytes), and page walk executed
    with `mmap_read_lock` / `mmap_sem` read lock held across the translation.
  - **Additive Batch u32 Ioctl (`0x807`)**: Added `CMD_BATCH_U32` allowing clients to
    read up to 32 non-contiguous 32-bit addresses in a single syscall, drastically
    reducing userland-to-kernel context switch overhead.
  - **Full Backward Compatibility**: All legacy ioctls (`0x801` read, `0x802` write,
    `0x803` modbase, `0x804/0x805` QX handshake, and `0x806` unhide) remain 100%
    binary- and ABI-compatible. No loader code change is required.
  - **Node and Parameter Aliasing**: Both RT (`memacc.c`) and QX (`memacc_qx.c`) wrappers
    expose both `devname` and `devicename` module parameters, creating `/dev/wanbai`
    with `0666` permissions and automated asynchronous deferred permission recovery.
  - **Embedded Fallback Assets Synced**: APK assets now bundle 42 prebuilt 3.1 fallback
    drivers covering kernel versions 4.9 through 6.6.

### Changed
* **Boot auto-load is opt-in.** A successful load only STAGES the .ko for
  later; the boot script is installed solely while the "Load at every
  boot" switch is ON, and the switch stays OFF until the user flips it.
  Switch it OFF and unload — the next restart loads nothing. A load never
  turns the switch on by itself anymore.
* **Faster UNLOAD.** The unhide helper (ioctl `0x806`) runs only when the
  module is actually hidden (node live, nothing in `lsmod`); a visible
  module is rmmod'ed directly. rmmod runs in the already-root shell (no
  `su -c` context re-negotiation per attempt) and known visible names are
  tried before the full candidate ladder. The SELinux permissive fallback
  (300 ms) is only paid when the first unhide attempt fails.
* **Faster LOAD.** The pre-insmod cleanup removes only modules that are
  actually live and skips its wait entirely on a fresh load (0.4 s only
  when a stale driver really was removed). Load verification now waits
  for the device node to appear (ueventd race) and applies chmod/chcon on
  the first hit, so the node is client-ready the moment load reports
  success.
* **Every device / kernel hardening.** Boot staging falls back to the
  .ko's own modinfo `name=` (pure Kotlin) when the module is invisible in
  lsmod, so stealth builds stage and auto-load like everything else;
  module names are validated, not mangled (`5.10_A12` keeps its dot);
  the staged-name reader falls back to `grep -a` when a ROM ships without
  `strings`; wrong-arch devices fail fast with "arm64 CPU required"
  instead of burning the pipeline on `Exec format error`.

### Fixed
* **Unload works for drivers registered under any name.** The rmmod ladder
  reads the real `name=` (modinfo) out of the staged `driver.ko` on the
  device and tries that first — a driver the app installed always comes
  down, even when its lsmod name is outside the known list. When the node
  answers but no known module is loaded, the failure message says so
  honestly (unknown name or stale node) instead of a bare "all candidates
  rejected".
* **The boot script is never named after the node.** With no remembered
  module the script used to read `will insmod 'wanbai'` — the node name,
  not a module name — breaking its already-loaded check. It now uses the
  staged .ko's real modinfo name and refuses (with a clear message) when
  that cannot be determined.
* **The "Load at every boot" switch rules the boot script on disk.** The
  switch's OFF state used to live only in app prefs while a script left
  behind by an older version (or a data-wiping reinstall) kept reloading
  the driver at every boot and again after every unload. The app now
  reconciles the disk with the switch in both directions: prefs ON + no
  script → back to OFF; prefs OFF + script present → the leftover script
  is removed (both known script paths) and the terminal says so.
  Toggling OFF also cleans the boot logs now (the `rm -f` prefix was
  missing, so successful removals could log as failures).
* **Root state no longer blocks or goes stale.** Home and Console computed
  root availability (and the kernel release, a root-shell call) on the
  main thread during first composition — a fresh install could freeze on
  the su prompt, and granting root afterwards left "ROOT MISSING" +
  disabled buttons until the screen was left and re-entered. Both screens
  resolve root/kernel info off the main thread and re-check on every
  ON_RESUME, so the UI lights up the moment root is granted.
* Full codebase audit (OTA store, updater, anti-tamper, report builder,
  memory test, load/unload/autoload pipelines): no further defects found.

## [2.3] - 2026-10-10

### Added
* **Real UNLOAD for the stealth driver.** The new `unloadDriver` flow opens
  the `/dev/wanbai` node and sends the unhide ioctl (`0x806`) through a
  tiny freestanding helper binary (`bin/kloder_unhide`, built from
  `driver/kloder_unhide.c` — the app cannot issue ioctls from Java since
  `android.system.Os` has no `ioctl` and `Libcore.os` is blocked at
  targetSdk 34), sets SELinux permissive when needed (300 ms settle, one
  unhide retry — a hidden module cannot be rmmod'ed), then walks the
  known-module ladder (`memacc → memacc_qx → kmem_337 → kmem_337_qx →
  entryi → kmem → kloader → 5.10_A12 → wanbai → daisy`), stopping at the
  first success. Verification requires the node gone, `lsmod` clean of
  every known driver name and `/proc/sys/kernel/tainted` back at its
  pre-unload value; failures name the exact step and stderr.
  Boot-autoload staging is never touched.
* UNLOAD is now offered whenever the driver is actually loaded — node
  present or a known module in `lsmod` — so a stealth-loaded driver
  (module hidden, node live) shows UNLOAD instead of "not loaded", and
  pressing it with nothing loaded reports "Nothing loaded" without
  crashing.
* The load path tolerates stealth too: verification passes on the live
  node when the module has already unlinked itself from `/proc/modules`,
  and a load attempt while the driver is already active is reported as
  "Driver already loaded" instead of an insmod `File exists` failure.

### Fixed
* **The unload and reload ladders now use the real module names.** The
  embedded/OTA `qx_*.ko` builds register as `entryi` in `lsmod` and the
  `rt_*.ko` builds as `5.10_A12` (verified from each `.ko`'s modinfo), but
  the rmmod ladder and the pre-insmod cleanup only tried
  `memacc`/`kmem_337` — unloading an RT/QX driver failed at rmmod and
  switching variants died on insmod `File exists`. Both paths now walk
  the full known-module list, and the post-unload `lsmod` check is
  anchored to exact names so short patterns cannot match unrelated
  modules.
* The insmod ladder's fatal-error check now runs **before** the legacy
  unknown-parameter retry: toybox reports "unknown symbol in module, or
  unknown parameter" as one combined line, which used to be misread as a
  legacy retry and burned 4 attempts on a deterministic kernel rejection.
* **Report residue is cleared automatically.** The loose report files are
  deleted as soon as the ZIP is packed; the ZIP is wiped the moment the
  user returns from WhatsApp (sent or cancelled — by then WhatsApp has
  either read the stream or never opened it, so there is no read race);
  and an app start wipes the report dir as a backstop. Nothing survives
  a restart.

### Security
* **OTA drivers are hash-verified now.** `drivers.json` carries a
  `sha256` per entry; the app verifies the cached copy before reuse and
  the download before staging, and deletes + rejects on mismatch. A
  corrupted or tampered `.ko` can no longer reach `insmod`.
* The `/dev` node name is sanitized to letters/digits/`_`/`-` (max 32)
  everywhere it reaches a shell command or the boot script.
* The embedded-base64 scan in `ensureElf` is skipped for files over
  16 MB, so a large non-ELF pick can no longer spin the regex scanner.
* Temp residue removed: the staged `/data/local/tmp/kloader_auto.ko` is
  deleted after every built-in load attempt (success or fail), the boot
  script's cache copy is deleted right after install, and old update
  APKs are cleared before each new update download.

---

## [2.2] — 2026-10-05 (versionCode 4, tag v4)

### Fixed
* **The CRC extractor step broke with the rebuilt `kcrc_dump`.** The tools on
  the `drivers` branch are now tiny (~10 KB) dynamic-PIE builds (the old
  3.5 MB static build segfaulted at startup on Android 11), but the app's
  download check required ≥ 1 MB — every report would have failed the
  Module.symvers step. The accepted size window is now 4 KB – 64 MB, and the
  ELF machine type is verified on download so an arm build can never run on
  an arm64 shell (and vice versa).
* **32-bit phones are supported now.** `uname -m` `armv7*`/`armv8*` shells
  download `kcrc_dump-arm` (9.8 KB, ELF `0x28`) instead of the aarch64 build;
  both arches cache under their own name and the other arch's stale copy is
  removed after a successful download.
* **A cached extractor can no longer go stale.** Before running, the app
  compares the cached file size against a HEAD request to the primary mirror
  and re-downloads when the published tool changed — the extractor can be
  rebuilt on the `drivers` branch without shipping a new APK. (Users who
  already cached the old 3.5 MB static build get the new one automatically;
  on Android ≤ 10 the old build would still have run, on Android 11+ it
  segfaulted.)
* **"Update Check Offline" on every launch.** The updater polled
  `api.github.com` unauthenticated, which rate-limits by IP (60 requests per
  hour) — every user behind the same carrier NAT shares one budget, so the
  check failed for large groups of users. The updater now reads the public
  release pages instead, which are not rate-limited: `/releases/latest`
  redirects to the current tag, and `/releases/expanded_assets/<tag>` lists
  the APK asset (size read via a HEAD request). The old API JSON flow is kept
  only as a fallback in case the page layout ever changes.

### Changed
* The extractor now self-unhides `kptr_restrict`, so no extra root-side
  preparation is needed before the CRC dump (drivers-branch tools commit
  `5b108ce`).

---

## [2.1] — 2026-10-05 (versionCode 3, tag v3)

### Added
* **One-tap auto build report as a ZIP on WhatsApp.** The WhatsApp button now
  collects everything on its own and opens WhatsApp with
  `report_<kernel-version>.zip` attached plus a one-line caption
  (`Samsung SM-S921B, 4.14.284-LONGSU, NO MATCH`) — the user only taps send.
  Inside the ZIP:
  * `report.txt` — the full 14-field build template (`KERNEL / VERSION / ARCH /
    PAGE SIZE / DEVICE / ANDROID / FP / ROOT / BUNDLE / DB MATCH / VERMAGIC /
    RESULT / DMESG / APP LOG`) plus a `SOURCE` line (kernel source URL if
    `/proc/version` carries one, otherwise `unknown`)
  * `Module.symvers.txt` — the kernel CRC table, extracted on the phone
  * `config.txt` — the complete kernel config from `/proc/config.gz`
  * `dmesg.txt` — the insmod-related dmesg tail with the exact last error
  * `applog.txt` — the app's own log
* **The app runs `kcrc_dump` itself.** The CRC extractor is downloaded from
  the `drivers` branch (raw.githubusercontent → jsDelivr → github.com
  fallbacks, ELF + size verified, cached in the app's private dir), staged to
  `/data/local/tmp` and executed through the root shell. Users no longer need
  MT Manager or a manual download; a MODVERSIONS-less kernel is detected and
  reported as "no CRC file needed". If the extractor fails, its exact output
  ships as `kcrc_status.txt`.
* **ROOT type detection** in reports: Magisk / KernelSU / APatch / other `su`
  (via `su -v` + `/data/adb` markers).
* **BUNDLE field** — records which driver families the last pipeline actually
  tried (`RT → QX → built-in`, or the forced variant).
* **RESULT now carries the exact insmod error text**, captured at the moment
  the load failed, instead of a summarised status.
* WhatsApp package visibility (`<queries>`) and a `jid` extra so the report
  opens the dev chat directly on most WhatsApp builds; WhatsApp's own
  send-to screen (everything pre-filled) is the fallback, and a plain text
  report link is the last resort when no WhatsApp install exists.

### Fixed
* **In-app auto-update never fired.** `scripts/publish_apk.ps1` published
  releases to the pre-rename repo (`bmjubairdadu/kernel-loder`) while the
  updater watches `bmjubairdadu/kernel-loader`; every update check came back
  "up to date". The publish scripts now target the renamed repo. Release tags
  are single increasing integers (`v3`, `v4`, …) matching `versionCode` —
  the updater's tag parser reads the first number, so dotted tags like
  `v2.1` could never trigger an update.

### Changed
* Report template depth: 20 insmod-related `dmesg` lines (was 5), the last 50
  app-log lines (was 12), full `/proc/version`, plus the key kernel config
  flags. GitHub-issue and clipboard reports use the same template.
* The report ZIP lives in the FileProvider cache (`report/`), wiped on every
  run.

---

## [2.0] — 2026-10-05

### Added
* One-tap LOAD pipeline: **RT → QX → built-in**, each stage reported and
  cancellable with a **STOP** button.
* OTA driver database with exact kernel matching — RT and QX supported-kernel
  lists shown separately with `THIS DEVICE` / `NEWEST` badges.
* Boot auto-load toggle: the staged driver reloads itself after every reboot.
* **Auto build-request reports** — for unsupported kernels the app collects
  vermagic (from an on-device `.ko`), kernel config flags from
  `/proc/config.gz`, `/proc/version`, device identity, `dmesg` tail and the
  full log, then offers a ready WhatsApp message, a pre-filled GitHub issue or
  clipboard text.
* Anti-tamper: signing-certificate check at every launch, debugger/Xposed
  refusal, backup disabled, verbose log stripping in release builds.
* Redesigned gradient UI with animated logo, single clean **status console**
  with short statuses, full console with `ALL · STATUS · RT · QX` filters.
* In-app self-update from GitHub releases.

### Fixed
* Crash on reopening the app after it was backgrounded (libsu main shell was
  already created).

---

## [5.4-universal] — 2026-09-30 (versionCode 36)

### Fixed
* **Boot auto-load install no longer fails.** The script was staged through
  `/data/local/tmp/kloder-boot.sh`, which the app process cannot create on
  this ROM (`EACCES`), so every switch-ON ended in `could not write ...`.
  The temp file now lives in the app-private cache dir and only the final
  copy runs as root.

---

## [5.3-universal] — 2026-09-30 (versionCode 35)

### Fixed
* **OTA loads now stage for boot, like embedded loads.** The boot auto-load
  switch only worked after an embedded-path load, because only that path
  staged the exact bytes and installed the service.d script. The default OTA
  path never staged, so after a reboot nothing came back even with the switch
  on. Both paths now share one staging step.

---

## [5.2-universal] — 2026-09-29 (versionCode 34)

### Changed
* **Minimal loader: download from server, load at the exact position, done.**
  The self-test probe, vermagic patching, force-load ladder, dmesg panic
  scan and rescue-rmmod paths are removed from both load paths (OTA and
  embedded). A load is now: stage the file, one `insmod … devname=wanbai`
  (plain retry for legacy builds without the parameter), `chmod 666` the
  node, confirm via `lsmod` + exact `/dev` path. If the kernel refuses, its
  own error is shown in one line and nothing else is attempted. No
  force-load means no panic risk by construction.
* What stays: exact-position load (`devname=wanbai`), world-readable node,
  `setenforce 0`, already-loaded detection, boot auto-load staging, and the
  UNLOAD button flow. The `drivers/kprobe` test binary is no longer bundled.

---

## [5.1-universal] — 2026-09-28 (versionCode 33)

### Fixed (driver database, live immediately)
* Rebuilt `rt_4.9.337-DaisyForGaming.ko` and `qx_4.9.337-DaisyForGaming.ko`
  for the rebuilt kernel. The new kernel changed `struct module`, so the
  old drivers failed every load with `disagrees about version of symbol
  module_layout` (old CRC `0x78d772a5`, new kernel wants `0x2415a1ea`). Both
  drivers now carry `0x2415a1ea` with unchanged sources. The existing v5.0
  app picks them up over OTA, no new APK needed for this fix.

### Changed (app code, ships with the next APK build)
* Shorter console. Download progress lines are gone (one
  `OTA: downloaded` line per file). Failed loads show at most 2 `TRY:`
  lines plus a count, and `DIAG:` output is deduplicated to the last 4
  unique lines. `verifyModule()` prints one `VERIFY:` line instead of three
  command echoes. The embedded-path dmesg dump is deduplicated to 3 lines.
* Removed the database link block (`DB updated` line and the
  `open driver database` row) from the home screen. The driver list itself
  is unchanged.

---

## [4.6-universal] — 2026-09-28 (versionCode 31)

### Changed
* **The cross-process test now runs against a normal app process, not just
  pid 1.** The device log from 4.5 was the first real evidence about the
  driver's cross-process read:

  ```
  PASS /proc parse matches the driver's own base   <- the parse is correct
  maps base 0x0000005578e20000                     <- a valid address
  xread ret 0xfffffffffffffffb                     <- -5, the read failed
  ```

  So the base is known-good and the read still failed — but `/init` is an
  unusual subject (its lowest mapping is not representative of an app), so a
  failure there says nothing conclusive about a game process.

  The probe now locates `surfaceflinger`, `system_server` or `zygote` by
  scanning `/proc/<pid>/maps` for its name, and reads that process's ELF
  header — an ordinary PIE app mapping, which is exactly what a game mod
  reads. pid 1 is still probed, but only as a labelled reference.

  The three possible outcomes are now distinguishable:
  * `PASS cross-process read (ELF magic)` — reading another process works.
  * `READABLE from pid 1` but no ELF header — the base guess was wrong.
  * `not readable` on a normal process — the page-table walk really is the
    problem, and the driver is what needs fixing.

### Verified
The pid-discovery loop and the maps parse were compiled and run against a
real Linux `/proc` first: `systemd` was found at pid 1 and `bash` at pid 310,
each with a base matching `head -1 /proc/N/maps` exactly and the ELF magic
present at that address, in 1 ms and 4 ms respectively.

---

## [4.5-universal] — 2026-09-28 (versionCode 30)

### Fixed
* **The `/proc` scanner added in 4.4 never worked** — on the device it
  reported `found pid 0xffffffffffffffff` for every candidate. It walked
  `/proc` with `getdents64` and matched `/proc/<pid>/comm`, and the dirent
  parsing found no usable pid. Replaced with something far simpler and
  provable: the first line of `/proc/<pid>/maps` is the lowest mapping, i.e.
  the load address, so it is just the hex number before the first `-`. No
  dirent parsing at all.

  The new version validates itself inside the same run: it parses
  `/proc/self/maps` and requires the result to equal the base that `0x803`
  already returned for the probe's own process. If that agrees, the parse is
  trustworthy and any later failure belongs to the driver, not the test.

  This is the part that actually matters for a game mod: reading *another*
  process. Everything before only read the probe's own address space, so it
  would still have passed if the page-table walk never left the calling
  process.

### Verified
The maps parser was compiled and run on a real Linux `/proc` before shipping,
and it matches `head -1 /proc/N/maps` exactly for both the current process
and pid 1, with the ELF magic (`7f 45 4c 46`) confirmed at the parsed address
in each case.

---

## [4.4-universal] — 2026-09-27 (versionCode 29)

### Fixed
* **The OTA path never made `/dev/wanbai` world-readable.** The embedded path
  did `chmod 666` inside `verifyLoad()`, but the GitHub/OTA path skipped
  `verifyLoad()` entirely, so a driver downloaded from the database left the
  node at devtmpfs's default `0600 root`. The self-test still passed because
  it runs as root, while a game app — a different uid — would be refused
  access. The OTA path now checks the exact node, `chmod 666`s it, logs the
  resulting mode, and fails the load if `/dev/wanbai` is absent.
* **The build reported a stale version.** `versionCode` was bumped *after*
  `assembleRelease`, so the APK shipped with the previous `BuildConfig` and
  the console said "current v27 4.2-universal" while actually running the 4.3
  code. The version is now bumped before building, and the generated
  `BuildConfig.java` is verified to match.

### Changed
* The cross-process probe now resolves **real pids** by walking `/proc` and
  matching `/proc/<pid>/comm` (raw `getdents64`, no libc). The driver's
  `get_module_base()` calls `pid_task(find_vpid(pid), …)`, so asking for
  "any process called init" does not work — `find_vpid(0)` is NULL and the
  lookup returns 0. The previous probe hardcoded pid 1, read the wrong base,
  and produced a misleading warning.
* The cross-process probe now tries `init`, `surfaceflinger`, `system_server`
  and `zygote`, printing the pid, base, raw return and the 4 bytes read for
  each, plus a control read of a known-good address. That separates "the base
  lookup returned something odd" from "the page-table walk does not work for
  another process", which are very different bugs.

### Verified
`getdents64` (0x3d), `read` (0x3f), `close` (0x39) and `openat` (0x38) are
all present in the rebuilt probe's disassembly, `/proc` and the `comm` path
are built at runtime, and the stale `-5` comparison remains absent.

---

## [4.3-universal] — 2026-09-27 (versionCode 28)

### Fixed
* **The app asked the driver for a random `/dev` name, which deleted
  `/dev/wanbai` — the one path the game-mod clients open.** This is why
  "load করলেও কাজ হয় না" even though the driver was loading perfectly.

  ```
  VERIFY: /dev node -> FOUND (/dev/eudwahxj, per-load name)   <- "success"
  dmesg: kmem: /dev/wanbai removed
  dmesg: kmem: /dev/eudwahxj created (major 216). ready.
  ```

  Aincrad 3.7 and Angry Mod V1 build the path `/dev/wanbai` from the strings
  `/dev` + `wanbai` and open it with no discovery step. Passing
  `devname=<random>` makes the driver unlink its own `/dev/wanbai` and
  register the random node instead, so the game apps had nothing to open. The
  rationale in the old comment — "static node names are fingerprinted by
  anti-cheat, random is safer" — is only a valid trade when the client is
  told the name, and these clients are not.

  Both load paths (OTA and embedded) now always request `devname=wanbai`.

* **A fuzzy node check hid the failure.** `verifyLoad` accepted *any* new `/dev`
  entry containing "kmem"/"kloader"/"entryi"/the module name, so a load that
  registered the wrong node was reported as `FOUND`. It now requires the exact
  path, names any stray nodes, and a load counts as successful only when the
  module is in `lsmod` **and** `/dev/wanbai` exists.

### Added
* **Cross-process read test.** Every previous check read the probe's own
  address space, so it would still have passed if the page-table walk only ever
  looked at the calling process — i.e. it never proved the one thing a game mod
  needs. The probe now reads the ELF magic (`7f 45 4c 46`) out of `/init`
  (pid 1) using the same `0x803` module-base lookup the game clients use.
  It is reported as `PASS xread-init` / `WARN xread-init` but deliberately does
  **not** gate the result: `/init`'s exact base varies by ROM, and an
  ambiguous self-test must never be able to `rmmod` a working driver.

### Answer to "did you verify read/write?"
No — not through this app. Read/write was verified on the device in an earlier
round using standalone testers against a driver loaded from a shell script,
which is why the defect below went unnoticed: the app's own load path had never
been exercised end to end, and its self-test was asserting the inverted return
convention (fixed in 4.2).

Also updated: CHANGELOG, versionCode 28 / versionName 4.3-universal.

---

## [4.2-universal] — 2026-09-27 (versionCode 27)

### Fixed
* **The ABI self-test asserted the inverted return convention and was removing
  a perfectly good driver.** `driver/t_rw.c` treated `ret == -5` as success,
  but the RT driver returns `0` on success and `-5` on failure — the client
  tests the return with `cmp w0, #0`, which is exactly why it must not be
  `-5`. Every load therefore ended in `ABI: MISMATCH` and the app `rmmod`'ed
  the driver it had just installed:

  ```
  kprobe: FAIL read  got=0x00000000123445678   <- the read was CORRECT
  kprobe: FAIL write marker=0x00000000aabbccdd <- the write was CORRECT
  ABI: MISMATCH - loaded driver is stale/wrong, removing it
  ```

  The values came back right; only the expected return code was wrong.
* **The self-test only knew the RT ABI, so a QX driver always failed it.**
  RT answers `0x805 -> -22` and `0x804 -> -22`; QX answers `0x805 -> 0` and
  `0x804 -> 2`. Pressing QX therefore reported a false mismatch and removed
  the QX driver too. The probe now *identifies* the family from those two
  commands and reports `PASS abi-probe rt|qx`; the app compares that with the
  family the user asked for and warns when they differ instead of silently
  loading the wrong one.
* Self-test output now prints the raw ioctl return value for every check (as
  one write, so a line can no longer be lost from the log), and the failure
  text says "self-test failed" rather than blaming a "stale driver".
* Renamed "ABI MISMATCH" handling: a driver that answers with the other
  family's convention is now the only case that is called a mismatch.

### Removed
* `app/src/main/java/com/kernelloader/kernel/KernelProfiler.kt` — untracked WIP
  that never compiled (it called `RootChecker.getArch()`, which does not exist,
  and `Flushable.flush()` on a non-flushable chain) and that nothing in the app
  referenced. It broke every `assembleRelease`, so the build could not be
  verified at all until it was deleted.

### Verified
The rebuilt probe is a static aarch64 ELF; disassembly shows the success test
is `cbnz` against the ioctl return (i.e. `r == 0` required) and there is no
`-5` comparison anywhere in the binary.

---

## [4.1-universal] — 2026-09-27 (versionCode 26)

### Added
* **Load at every boot** (Magisk / KernelSU / APatch `service.d`). The kernel
  module lives in kernel memory, so a reboot always wiped it and the user had
  to tap LOAD again. A successful load now stages the *exact* bytes that were
  `insmod`'ed (already vermagic-patched) and installs
  `/data/adb/service.d/90-kloder.sh`, so the driver comes back by itself.
  A switch on the home screen turns it off and removes the script.
* **UNLOAD button on the home screen.** It only unloads — it never installs,
  never patches a vermagic and never reboots.

### Fixed
* **"Already loaded" is now detected correctly.** The module's name in `lsmod`
  is baked in at build time (`KBUILD_MODNAME` / `.modinfo name=`) and has
  nothing to do with the `.ko` file name:

  | asset | real module name |
  |---|---|
  | `rt_4.9.337-DaisyForGaming.ko` | `kmem_337` |
  | `qx_4.9.337-DaisyForGaming.ko` | `kmem_337_qx` |
  | `rt_4.14.117.ko` | `5.10_A12` |
  | `qx_4.14.117.ko` | `entryi` |

  The old code guessed the name from the file name, which produced no usable
  token for most drivers, and then hardcoded `kmem_337`. The name is now
  discovered by diffing `/proc/modules` around the `insmod`, which is exact for
  every driver. Tapping a load button with the driver already present is now a
  no-op that reports success instead of falling into the force-unload path.
* **UNLOAD can no longer remove an unrelated module.** It used to fall back to
  `entries.lastOrNull()` — the *last* entry in `lsmod` — which would `rmmod` an
  unrelated driver (camera, touch, wifi, …) if the guessed name did not match.
  It now only ever unloads a name this app actually loaded.
* The "File exists" recovery step unloads whichever known driver module the
  kernel is really holding, instead of only `kmem_337`.
* The generated boot script refuses to install if any placeholder is left
  unsubstituted, instead of writing a silently broken script.
* The boot script does not `insmod` twice: it checks `/proc/modules` first, so
  a manual load followed by a reboot cannot produce a double load.

---

## [4.0-universal] — 2026-09-27 (versionCode 25)

### Added
* **Two load buttons: RT and QX.** The game-mod clients (Aincrad, Angry Mod)
  speak two *different* driver ABIs — RT uses ioctl `0x801/0x802/0x803` and
  returns `0` on success, QX uses `0x801/0x802` plus a `0x804/0x805` handshake
  and returns `-1` on failure. Loading the wrong family still `insmod`s cleanly
  and then silently returns wrong data, so the family is now an explicit choice.
* **ABI family (`variant`) throughout the OTA pipeline.** `drivers.json` entries
  are tagged `"rt"` or `"qx"`, and the RT button will only ever be served an RT
  driver (family-agnostic entries remain acceptable to both).
* **17 RT + 23 QX drivers** bundled in `assets/drivers/` and published to the
  `drivers` branch, covering kernel 4.9.186 … 6.6.57.
* Per-button driver list and a family-aware "Ready:" status line.

### Changed
* **Driver database wiped.** The 110 legacy `uni_*` universal builds were
  removed; the database is now exactly the 40 RT/QX drivers.
* Embedded driver naming is `<family>_<version>.ko` (`rt_…`, `qx_…`) so the
  scanner derives the family from the file name. The binary's own `vermagic`
  still decides kernel compatibility — the name is only a label.
* The stale `kmem_4.9.337-DaisyForGaming.ko` build was replaced by
  `rt_4.9.337-DaisyForGaming.ko`, compiled with `CONFIG_MODVERSIONS` CRCs taken
  from this kernel's `Module.symvers`.

### Fixed
* The RT button can no longer be handed a QX driver (or the reverse) by the
  OTA resolver, the embedded scanner, or the supported-kernels list.

### Note
* `app/src/main/java/com/kernelloader/kernel/KernelProfiler.kt` is untracked WIP
  that does **not** compile (it calls `RootChecker.getArch()`, which does not
  exist, and nothing references the class). It is excluded from the build; fix
  or delete it before it breaks a release.

---

## [2.4-universal] — 2026-09-22 (versionCode 9)

### Fixed
* Splash logo now fixed at 192dp (was drawn at 512px intrinsic size).
* Fixed branded dark theme everywhere (was following wallpaper/system theme).
* Console readability: fixed dark terminal card, brighter log colors, 12sp font.
* Database connection: real error shown in console (DNS/timeout/TLS/HTTP),
  jsDelivr CDN mirror added as fallback, refresh flow improved.

### Changed
* All UI and console messages in English (Banglish removed).

---

## [2.3-universal] — 2026-09-22 (versionCode 8)

### Added
* **OTA kernel loader** (`OtaDriverStore`): manifest `drivers.json` fetched from the
  `drivers` branch at launch; matching loader downloaded, `insmod`-loaded and verified.
* **SafetyGuard**: refuses wrong major/minor loads, blocks loading after kernel
  panic/oops in dmesg, `rmmod` rescue on failure.
* **WhatsApp support** (+8801785917145) with kernel version prefilled + custom text.
* **In-app auto-update** (`AppUpdateChecker`) from GitHub Releases `latest` API,
  FileProvider APK install flow.
* `scripts/publish_apk.ps1` — one-command release APK upload.

### Changed
* **Zero bundled `.ko`**: `app/src/main/assets/drivers/` ships empty; release APK ~2.5 MB.
* Supported list newest-first with `buildDate`, `LATEST` / `THIS DEVICE` badges.
* R8 shrink + obfuscation, release signed with debug keystore for in-place updates.

---

## [2.2-universal] — 2026-09-21 (versionCode 7)

OTA groundwork release (superseded by 2.3).

---

## [2.1-universal] — 2026-09-19

### Added
* **Universal kernel module driver** — new portable source `driver/kloader_driver.c`
  (module `kloader_driver`, misc device `/dev/kloaderctl`, dmesg tag `KernelLoder:`),
  replacing the device-specific driver source.
* **Native builds for two kernel releases**, produced with proton-clang from real kernel trees:
  * `native_4.9.337.ko` — vermagic `4.9.337-DaisyForGaming SMP preempt mod_unload modversions aarch64`
  * `native_4.9.307.ko` — vermagic `4.9.307-DaisyForGaming SMP preempt mod_unload modversions aarch64`
* One-command WSL build pipeline: `driver/build_native_wsl.sh` (+ `wsl_launch_native.sh`, `wsl_verify.sh`)
  which builds both releases and copies them into `driver/out/` **and** `app/src/main/assets/drivers/`.
* `docs/` — Bangla install / build / FAQ guides.

### Changed
* **Project renamed to “Kernel Loader”** (`settings.gradle.kts` rootProject name, app label,
  credits screen) — the app no longer carries a device-specific identity.
* **Device-agnostic everything:** the app never checks brand/model/codename, only the kernel release.
* Driver selection now reads the **real kernel release from each `.ko` binary (vermagic)**
  (`EmbeddedDrivers.readVermagic`) and scores vermagic-exact matches highest (1200).
* Verification is dynamic: `lsmod` module name → matching `/dev` node, with `kloaderctl` / legacy
  `daisyctl` fallbacks (`VerificationResult.deviceNodeFound`, dump-styled key `daisyctlExists`).
* `rmmod` resolves the real module name from tokens of the picked file name (`nameTokens()`).
* Status line is universal: `UNIVERSAL: <kernel> - exact driver available / no exact driver…`
  (`RootChecker.getCompatibilityMessage`, new `RootChecker.hasExactDriver()`).
* Internal temp paths renamed: `/data/local/tmp/kloader_auto.ko`, cache `kloader_auto.ko`.
* Repo hygiene: strict `.gitignore`, `.gitattributes` (LF for shell/scripts, binary for `.ko`/`.apk`),
  Gradle `versionCode 6`, `versionName 2.1-universal`.

### Removed
* `driver/daisy_driver.c` and the `daisy_*.ko` assets (superseded by `native_*.ko`).
* Device-specific checks (`is337Kernel()`, `is307Kernel()`, `is186Kernel()`, `is337` UI flag).

---

## [2.0-universal] — 2026-09-19

### Added
* **⚡ AUTO LOAD (Universal)** engine (`UniversalKernelLoader`) with an auto-fix ladder:
  SELinux → permissive, chmod/chcon, **binary vermagic patching**, busybox `insmod -f`,
  `sig_enforce` off — each applied after diagnosing the real error, then retried.
* **Live terminal screen** (`TerminalScreen`) with colour-coded `CMD / OK / ERR / FIX / WARN / OUT`
  lines, busy-progress step display, manual root-shell command box, Copy / Clear / quick actions.
* Dynamic embedded-driver catalog that **scans `assets/drivers/`** at runtime (40 modules bundled).
* Auto-verification after loading: `lsmod`, device node existence, `dmesg` tail.

### Fixed
* Duplicate `LogEntry` / `VerificationResult` declarations (Kotlin redeclaration error).
* Missing strings and unterminated `<resources>` block in `strings.xml`.
* `rmmod` failing when the module name differed from the file name; busybox fallback added.

---

## [1.1-337fix] — 2026-09-19

### Added
* First 4.9.337-specific release: `4.9.337` `.ko` built against the device kernel tree,
  `insmod -f` force load, kernel-vs-vermagic diagnostics, `Verify Module` action.

---

## [1.0] — 2026-09-18

### Added
* Initial Android app: root check, kernel version display, `.ko` picker, `insmod` / `rmmod`,
  embedded QX / RT driver catalog, diagnostic log console.