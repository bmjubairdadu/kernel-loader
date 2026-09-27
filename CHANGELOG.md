# Changelog

All notable changes to **Kernel Loder** are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
semantic-ish version tags (`<major>.<minor>-<flavour>`).

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
* **Project renamed to “Kernel Loder”** (`settings.gradle.kts` rootProject name, app label,
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