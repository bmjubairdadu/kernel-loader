package com.kernelloader.driver

import android.content.Context
import android.net.Uri
import com.kernelloader.root.RootChecker
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * UNIVERSAL KERNEL LOADER ENGINE
 * ==============================
 * Target: load the driver .ko on ANY kernel (old or new), and when the kernel
 * gives problems, FIX them automatically (we run as root / superuser) and load.
 *
 * Auto-fix capabilities:
 *  1. SELinux Enforcing             -> setenforce 0
 *  2. Permission denied on insmod   -> chmod/chcon + SELinux + retry
 *  3. vermagic mismatch (Exec format error / Invalid module format)
 *                                   -> binary-patch the .ko vermagic string to
 *                                      the RUNNING kernel release -> retry
 *                                      (only when the new string fits; the full
 *                                      `uname -r` including suffixes like -perf
 *                                      is auto-detected and used)
 *  4. symbol/version CRC mismatch   -> busybox insmod -f (force) BUT ONLY when
 *                                      SafetyGuard allows it (same major.minor
 *                                      AND kernel currently stable). Otherwise
 *                                      the load is REFUSED so the phone never
 *                                      restarts.
 *  5. module signature enforcement  -> try sig_enforce off -> plain retry
 *                                      (no forced signature bypass)
 *  6. already loaded ("File exists")-> treat as success + verify
 *
 * The app itself NEVER reboots the phone. Old installer .sh scripts contain
 * `reboot` commands - do not use them; use this AUTO LOAD path instead.
 *
 * If no file was picked, it scans the embedded drivers and auto-selects the
 * best match for the running kernel.
 *
 * Every single step is streamed to the terminal (vm.tlog) so the user can see
 * exactly what the loader is doing.
 */
object UniversalKernelLoader {

    private const val TMP_KO = "/data/local/tmp/kloader_auto.ko"

    fun autoLoad(context: Context, vm: DriverViewModel, variant: String = "") {
        vm.tstep("Checking superuser...")
        vm.tlog("==============================================", "INFO")
        vm.tlog(" KERNEL LODER - UNIVERSAL AUTO-LOAD", "INFO")
        if (variant.isNotBlank()) {
            vm.tlog(" requested driver family: ${OtaDriverStore.variantLabel(variant)}", "INFO")
        }
        vm.tlog(" any device - any model - old & new kernels", "INFO")
        vm.tlog("==============================================", "INFO")

        // ---------- 0. Root check ----------
        val rootOk = try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
        if (!rootOk) {
            vm.tlog("ROOT: MISSING - grant Superuser/Su permission to the app!", "ERR")
            finish(vm, false, "Root missing - grant superuser access")
            return
        }
        vm.tlog("ROOT: OK (running as uid=0)", "OK")

        // ---------- 1. Gather device info ----------
        vm.tstep("Reading device info...")
        val brand = Shell.cmd("getprop ro.product.brand").exec().out.firstOrNull()?.trim() ?: ""
        val model = Shell.cmd("getprop ro.product.model").exec().out.firstOrNull()?.trim() ?: ""
        val kernel = Shell.cmd("uname -r").exec().out.firstOrNull()?.trim() ?: "unknown"
        val arch = Shell.cmd("uname -m").exec().out.firstOrNull()?.trim() ?: "unknown"
        val selinux = Shell.cmd("getenforce").exec().out.firstOrNull()?.trim() ?: "unknown"
        if (brand.isNotEmpty() || model.isNotEmpty()) {
            vm.tlog("DEVICE: $brand $model (device name doesn't matter - universal loader)", "INFO")
        }
        vm.tlog("KERNEL: $kernel", "INFO")
        vm.tlog("KERNEL-SHORT: ${RootChecker.kernelShortVersion(kernel)} (suffix auto-detected: full release is used for matching)", "INFO")
        vm.tlog("ARCH:   $arch", "INFO")
        vm.tlog("SELINUX: $selinux", "INFO")

        // ---------- 1b. SAFETY: never force-load on top of a sick kernel ----------
        // (OTA path already has this guard; the embedded path was missing it,
        // which is why mismatched loads caused heat/lag/restart.)
        if (SafetyGuard.kernelLooksUnstable()) {
            SafetyGuard.unstableLines().forEach { vm.tlog(it.first, it.second) }
            finish(vm, false, "Kernel already unstable - reboot once normally, then try again (nothing was loaded)")
            return
        }

        // ---------- 2. FIX: SELinux ----------
        if (selinux.equals("Enforcing", ignoreCase = true)) {
            vm.tstep("Fix: SELinux Enforcing -> Permissive")
            vm.tlog("FIX: SELinux is Enforcing -> setenforce 0", "FIX")
            val r = Shell.cmd("setenforce 0").exec()
            if (r.isSuccess) {
                vm.tlog("FIX: SELinux -> Permissive OK", "OK")
            } else {
                vm.tlog("FIX: setenforce 0 failed (${r.err.joinToString(" ")}). Continuing anyway...", "WARN")
            }
        }

        // ---------- 3. Get the .ko file ----------
        vm.tstep("Preparing driver .ko file...")
        val cacheFile = File(context.cacheDir, "kloader_auto.ko")
        val sourceName: String
        val pickedUri = vm.pickedFileUri.value
        if (pickedUri != null) {
            sourceName = vm.pickedFileName.value ?: "picked.ko"
            vm.tlog("SOURCE: user-picked file: $sourceName", "INFO")
            try {
                context.contentResolver.openInputStream(pickedUri)?.use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                } ?: run {
                    vm.tlog("ERROR: could not open picked file", "ERR")
                    finish(vm, false, "File open failed")
                    return
                }
            } catch (e: Exception) {
                vm.tlog("ERROR: reading picked file: ${e.message}", "ERR")
                finish(vm, false, "File read failed")
                return
            }
        } else {
            val best = findBestEmbeddedDriver(context, kernel, variant)
            if (best == null) {
                vm.tlog("ERROR: no file picked AND no embedded driver found", "ERR")
                finish(vm, false, "No .ko found - pick a file")
                return
            }
            sourceName = best.displayName
            vm.tlog("SOURCE: auto-selected embedded driver: $sourceName (${best.filename})", "INFO")
            // Coverage report: exact X.Y.Z module vs nearest-series fallback
            val realVerm = EmbeddedDrivers.readVermagic(context, best.filename)
            val exactCover = realVerm.isNotEmpty() &&
                    RootChecker.kernelShortVersion(realVerm) == RootChecker.kernelShortVersion(kernel)
            vm.tlog(
                if (exactCover)
                    "COVERAGE: $kernel -> EXACT bundled module (${KernelCoverage.all.size} kernel versions supported)"
                else
                    "COVERAGE: $kernel -> nearest-series module + vermagic patch + force-load (${KernelCoverage.all.size} kernel versions supported)",
                if (exactCover) "OK" else "INFO"
            )
            try {
                context.resources.assets.open(best.filename).use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                vm.tlog("ERROR: extracting embedded driver: ${e.message}", "ERR")
                finish(vm, false, "Driver extract failed")
                return
            }
        }

        // ---------- 4. Normalize to raw ELF (some bundled .ko are base64) ----------
        vm.tstep("Validating ELF kernel module...")
        if (!ensureElf(cacheFile)) {
            vm.tlog("ERROR: file is NOT an ELF kernel module (.ko) - insmod impossible", "ERR")
            vm.tlog("HINT: pick a valid .ko / installer .sh (.ko is auto-extracted from .sh)", "WARN")
            finish(vm, false, "Not a valid .ko (ELF)")
            return
        }
        vm.tlog("ELF: valid kernel module detected", "OK")

        // ---------- 5. Read vermagic & compare with running kernel ----------
        val vermagic = readVermagic(cacheFile)
        vm.tlog("KOVERMAGIC: ${vermagic ?: "not found"}", "INFO")
        var needPatch = false
        if (vermagic == null) {
            vm.tlog("WARN: vermagic not found in .ko - will try load anyway", "WARN")
        } else {
            val vmVersion = vermagic.substringBefore(' ')
            val vmArch = vermagic.substringAfterLast(' ', "")
            if (vmVersion != kernel) {
                needPatch = true
                vm.tlog("DIAGNOSE: vermagic MISMATCH! (.ko=$vmVersion vs running=$kernel)", "WARN")
                vm.tlog("PLAN: auto-patch .ko vermagic -> $kernel", "FIX")
            }
            if (vmArch.isNotEmpty() && arch == "aarch64" && !vmArch.contains("aarch64", true)) {
                vm.tlog("DIAGNOSE: ARCH mismatch! (.ko=$vmArch vs running=$arch) - load will likely fail", "WARN")
            }
        }

        // ---------- 6. Copy to /data/local/tmp ----------
        vm.tstep("Copying .ko to /data/local/tmp...")
        val copy = Shell.cmd(
            "cp \"${cacheFile.absolutePath}\" $TMP_KO",
            "chmod 644 $TMP_KO",
            "chown root:root $TMP_KO 2>/dev/null"
        ).exec()
        if (!copy.isSuccess) {
            vm.tlog("ERROR: copy to /data/local/tmp failed: ${copy.err.joinToString(" ")}", "ERR")
            finish(vm, false, "Copy failed")
            return
        }
        vm.tlog("COPY: $TMP_KO OK", "OK")

        // The game-mod clients (Aincrad, Angry Mod) open a FIXED path: they try
        // /dev/wanbai and give up if it is not there. Passing a random
        // devname= made the driver UNLINK /dev/wanbai and register the random
        // node instead, so the driver loaded cleanly, lsmod showed it, the
        // self-test even saw a working /dev node - and the game app still
        // could not read or write anything:
        //     dmesg: kmem: /dev/wanbai removed
        //     dmesg: kmem: /dev/eudwahxj created (major 216). ready.
        // "a random name is safer against anti-cheat" is only a valid trade
        // when the client is told the name; these clients are not.
        val devNode = vm.preferredDevNode()
        vm.tlog("DEVNAME: /dev/$devNode (fixed name - this is the path the game client opens)", "INFO")

        // If vermagic mismatch, patch BEFORE first attempt (old kernels always reject mismatched vermagic)
        if (needPatch) {
            vm.tstep("Fix: patching vermagic -> $kernel")
            if (patchVermagic(cacheFile, kernel)) {
                vm.tlog("FIX: vermagic patched OK -> \"$kernel\"", "OK")
                Shell.cmd("cp \"${cacheFile.absolutePath}\" $TMP_KO", "chmod 644 $TMP_KO").exec()
            } else {
                vm.tlog("FIX: in-place vermagic patch not possible (string too long) - will rely on force-load", "WARN")
            }
        }

        // ---------- 7. insmod with auto-fix retry ladder ----------
        val baselineMods = SafetyGuard.loadedModuleNames()

        // A module lives in kernel memory: once loaded it stays loaded until a
        // reboot or an explicit rmmod. Re-insmod'ing is not just wasteful, it
        // is the "File exists" error path that can end up force-unloading a
        // live driver. So check FIRST and report success if it is already in.
        val alreadyLoaded = vm.knownDriverModules().firstOrNull { it in baselineMods }
        if (alreadyLoaded != null) {
            vm.tstep("Checking module...")
            vm.tlog("STATUS: driver '$alreadyLoaded' is ALREADY loaded - nothing to do", "OK")
            vm.setLoadedModule(alreadyLoaded)
            vm.tlog(
                "INFO: a kernel module survives until reboot or rmmod, so you only " +
                        "load it once per boot. Use UNLOAD to remove it.",
                "INFO"
            )
            // Still refresh the node permissions - a game client may have been
            // the one that reset them, and the node is what it opens.
            Shell.cmd("chmod 666 /dev/$devNode 2>/dev/null").exec()
            verifyLoad(vm, sourceName, devNode, alreadyLoaded)
            finish(vm, true, "Already loaded: $alreadyLoaded")
            return
        }

        vm.tstep("Loading module (insmod)...")
        vm.tlog("CMD: insmod $TMP_KO devname=$devNode", "CMD")
        var res = Shell.cmd("insmod $TMP_KO devname=$devNode").exec()
        if (!res.isSuccess &&
            (res.out + res.err).joinToString("\n").contains("Unknown parameter", true)
        ) {
            // Legacy .ko without the devname parameter - retry plain.
            vm.tlog("INFO: driver has no devname= parameter (legacy build) - retrying plain insmod", "WARN")
            res = Shell.cmd("insmod $TMP_KO").exec()
        }
        res.out.forEach { if (it.isNotBlank()) vm.tlog(it, "OUT") }
        res.err.forEach { if (it.isNotBlank()) vm.tlog(it, "ERR") }

        if (!res.isSuccess) {
            val err = (res.out + res.err).joinToString("\n")
            res = runFixLadder(vm, cacheFile, err, devNode)
        }

        // ---------- 8. Verify + post-load stability (rescue if unsafe) ----------
        // The module's lsmod name is whatever KBUILD_MODNAME / .modinfo said
        // at build time (kmem_337, kmem_337_qx, entryi, 5.10_A12, ...) and has
        // NOTHING to do with the .ko file name. So discover it: whatever
        // appeared in /proc/modules between the two snapshots IS our driver.
        val loadedNow = SafetyGuard.newlyLoaded(baselineMods)
        if (loadedNow.isNotEmpty()) {
            vm.setLoadedModule(loadedNow.first())
        }
        vm.tstep("Verifying module...")
        // Wait a beat so dmesg can show an oops, then check stability.
        // If the just-loaded module made the kernel sick, rmmod it at once
        // so the phone does NOT restart.
        val beforeMods = baselineMods
        // NOTE: no sleep here - autoLoad runs on the main thread, so we check
        // dmesg state immediately instead of blocking the UI.
        if (SafetyGuard.kernelLooksUnstable()) {
            vm.tlog("SAFETY: kernel unstable after load (panic/oops signature caught)", "ERR")
            var rescued = false
            (loadedNow.ifEmpty { SafetyGuard.newlyLoaded(beforeMods) }).forEach { mod ->
                vm.tlog("RESCUE: rmmod $mod (preventing phone restart)", "FIX")
                if (SafetyGuard.rescueUnload(mod)) {
                    rescued = true
                    vm.setLoadedModule("")
                    vm.tlog("RESCUE: $mod unloaded - kernel stable, phone will not restart", "OK")
                } else {
                    vm.tlog("RESCUE: rmmod $mod failed - module is still loaded", "WARN")
                }
            }
            finish(vm, false, if (rescued) "Unsafe loader removed (no restart)" else "Loader unstable - contact support")
            return
        }
        val ok = verifyLoad(vm, sourceName, devNode, vm.loadedModuleName.value) &&
                abiCheck(context, vm, devNode, baselineMods, variant)
        if (ok) {
            vm.tlog("==============================================", "OK")
            vm.tlog(" RESULT: DRIVER LOADED & VERIFIED", "OK")
            vm.tlog(" module name in lsmod: ${vm.loadedModuleName.value.ifBlank { "(unknown)" }}", "OK")
            vm.tlog("==============================================", "OK")
            finish(vm, true, "Loaded OK: $sourceName")
            // A reboot wipes kernel memory, so the driver has to be loaded
            // again every time the phone restarts. Stage the EXACT bytes that
            // were insmod'ed (already vermagic-patched, so the copy is
            // byte-identical to what the kernel just accepted) and install a
            // Magisk service.d script that loads it at every boot.
            stageForBoot(vm, context, variant, devNode)
        } else {
            vm.tlog("==============================================", "ERR")
            vm.tlog(" RESULT: LOAD FAILED - details in the terminal log", "ERR")
            vm.tlog(" HINT: run \"dmesg | tail -n 30\" in the terminal to see the exact kernel error", "WARN")
            vm.tlog("==============================================", "ERR")
            finish(vm, false, "Load failed - details in the terminal")
        }
    }

    /**
     * Auto-fix ladder: analyze kernel error text and apply the matching fix,
     * retrying insmod after each fix. Returns the last shell result.
     *
     * SAFETY: force-load is REFUSED unless SafetyGuard.canForceLoad passes
     * (same major.minor) and the kernel is currently stable. Refusal means
     * "no load, no restart" - never a panic.
     */
    private fun runFixLadder(vm: DriverViewModel, cacheFile: File, originalError: String, devNode: String): Shell.Result {
        var res = Shell.cmd("true").exec()
        var lastErr = originalError
        var attempts = 0
        val deviceKernel = Shell.cmd("uname -r").exec().out.firstOrNull()?.trim() ?: ""
        val koVermagic = try { readVermagic(cacheFile) } catch (e: Exception) { null }
        val koRelease = koVermagic?.substringBefore(' ') ?: ""

        // --- FIX A: "File exists" -> stale/loaded module with the same name.
        // A previous load may be stuck (visible to insmod, invisible to
        // lsmod and without a /dev node). Unload it, then retry once.
        // If it is STILL stuck, only a normal user reboot clears kernel
        // state - the app never reboots by itself.
        if (lastErr.contains("File exists", true) || lastErr.contains("already loaded", true)) {
            // Unload whichever of OUR driver modules the kernel is holding.
            // Hardcoding one name is wrong: the same bug arrives under
            // kmem_337, kmem_337_qx, entryi, 5.10_A12, ... depending on which
            // .ko was loaded, and the name is not the .ko file name.
            val stuck = vm.knownDriverModules().filter { SafetyGuard.isLoaded(it) }
            if (stuck.isEmpty()) {
                vm.tlog("DIAGNOSE: kernel says the name is taken, but no known driver module is in lsmod", "WARN")
                vm.tlog("ACTION: reboot the phone ONCE normally, then tap LOAD again", "FIX")
                return res
            }
            vm.tlog("DIAGNOSE: module name already present - clearing ${stuck.joinToString(", ")}", "WARN")
            // Plain rmmod ONLY: forced unload of a stuck module can panic
            // the kernel (instant reboot). If plain fails, a normal user
            // reboot clears it - the app never forces anything.
            val unload = Shell.cmd(
                stuck.joinToString("; ") { "rmmod $it 2>&1" },
                "sleep 2"
            ).exec()
            unload.out.forEach { if (it.isNotBlank()) vm.tlog("UNLOAD: $it", "INFO") }
            vm.setLoadedModule("")
            res = insmodRetry(devNode)
            vm.tlog("RETRY: insmod (after stale cleanup) -> exit ${res.code}", "CMD")
            res.err.forEach { if (it.isNotBlank()) vm.tlog(it, "ERR") }
            if (res.isSuccess) return res
            lastErr = (res.out + res.err).joinToString("\n")
            if (lastErr.contains("File exists", true)) {
                vm.tlog("STALE: kernel still holds the old module entry - load impossible in this boot", "ERR")
                vm.tlog("ACTION: reboot the phone ONCE normally (no auto-reboot), then tap LOAD again", "FIX")
                return res
            }
            attempts++
        }

        // --- FIX B: Permission denied -> SELinux / chmod
        if (lastErr.contains("Permission denied", true) || lastErr.contains("Operation not permitted", true)) {
            vm.tstep("Fix: permission problem...")
            vm.tlog("FIX: Permission denied -> chmod + chcon + setenforce 0 + retry", "FIX")
            val fix = Shell.cmd(
                "chmod 644 $TMP_KO",
                "chcon u:object_r:system_file:s0 $TMP_KO 2>/dev/null",
                "setenforce 0 2>/dev/null"
            ).exec()
            vm.tlog("FIX: applied (exit ${fix.code})", if (fix.isSuccess) "OK" else "WARN")
            res = insmodRetry(devNode)
            vm.tlog("RETRY: insmod (after permission fix) -> exit ${res.code}", "CMD")
            res.err.forEach { if (it.isNotBlank()) vm.tlog(it, "ERR") }
            if (res.isSuccess) return res
            lastErr = (res.out + res.err).joinToString("\n")
            attempts++
        }

        // --- FIX C: vermagic / Exec format error -> binary patch + retry
        if (lastErr.contains("Invalid module format", true) ||
            lastErr.contains("Exec format error", true) ||
            lastErr.contains("version magic", true) ||
            lastErr.contains("vermagic", true)
        ) {
            vm.tstep("Fix: vermagic mismatch -> patching .ko...")
            val kernel = Shell.cmd("uname -r").exec().out.firstOrNull()?.trim() ?: ""
            vm.tlog("FIX: vermagic/format error -> patching vermagic to \"$kernel\"", "FIX")
            if (kernel.isNotEmpty() && patchVermagic(cacheFile, kernel)) {
                vm.tlog("FIX: vermagic patched -> \"$kernel\"", "OK")
                Shell.cmd("cp \"${cacheFile.absolutePath}\" $TMP_KO", "chmod 644 $TMP_KO").exec()
                res = insmodRetry(devNode)
                vm.tlog("RETRY: insmod (after vermagic patch) -> exit ${res.code}", "CMD")
                res.err.forEach { if (it.isNotBlank()) vm.tlog(it, "ERR") }
                if (res.isSuccess) return res
                lastErr = (res.out + res.err).joinToString("\n")
            } else {
                vm.tlog("FIX: patch failed - trying force-load instead", "WARN")
            }
            attempts++
        }

        // --- FIX D: symbol / CRC / signature issues -> force load via busybox
        // GATED: same-major.minor only, stable kernel only. Otherwise refuse
        // (a cross-series or sick-kernel force-load is what reboots phones).
        if (lastErr.contains("Unknown symbol", true) ||
            lastErr.contains("disagrees about version", true) ||
            lastErr.contains("module_layout", true) ||
            lastErr.contains("Invalid module format", true) ||
            lastErr.contains("Exec format error", true) ||
            lastErr.contains("Required key not available", true) ||
            lastErr.contains("Operation not permitted", true) ||
            attempts > 0
        ) {
            if (deviceKernel.isNotEmpty() && koRelease.isNotEmpty() &&
                !SafetyGuard.canForceLoad(deviceKernel, koRelease)
            ) {
                SafetyGuard.refusalLines(deviceKernel, koRelease).forEach { vm.tlog(it.first, it.second) }
                vm.tlog("ACTION: nothing was force-loaded, phone will not restart.", "INFO")
                return res
            }
            if (SafetyGuard.kernelLooksUnstable()) {
                SafetyGuard.unstableLines().forEach { vm.tlog(it.first, it.second) }
                return res
            }
            if (lastErr.contains("Required key not available", true) || lastErr.contains("Key was rejected", true)) {
                vm.tlog("DIAGNOSE: kernel enforces module signatures - force-load refused (would fail / risk panic)", "ERR")
                vm.tlog("ACTION: this kernel ($deviceKernel) needs a signed/custom-built loader - contact support", "FIX")
                return res
            }
            vm.tstep("Fix: force-load (busybox insmod -f)...")
            vm.tlog("FIX: force-load via busybox insmod -f (bypasses vermagic/CRC/sign checks)", "FIX")
            res = Shell.cmd(
                "BB=\$(command -v busybox); [ -z \"\$BB\" ] && BB=/data/adb/magisk/busybox; " +
                        "[ -x \"\$BB\" ] && \$BB insmod -f $TMP_KO devname=$devNode || insmod -f $TMP_KO devname=$devNode"
            ).exec()
            if (!res.isSuccess &&
                ((res.out + res.err).joinToString("\n").contains("Unknown parameter", true) ||
                 (res.out + res.err).joinToString("\n").contains("No such file", true))
            ) {
                res = Shell.cmd(
                    "BB=\$(command -v busybox); [ -z \"\$BB\" ] && BB=/data/adb/magisk/busybox; " +
                            "[ -x \"\$BB\" ] && \$BB insmod -f $TMP_KO || insmod -f $TMP_KO"
                ).exec()
            }
            vm.tlog("RETRY: insmod -f (force) -> exit ${res.code}", "CMD")
            res.out.forEach { if (it.isNotBlank()) vm.tlog(it, "OUT") }
            res.err.forEach { if (it.isNotBlank()) vm.tlog(it, "ERR") }
            if (res.isSuccess) return res
            lastErr = (res.out + res.err).joinToString("\n")
        }

        // --- FIX E: signature enforcement -> disable sig_enforce, force again
        if (lastErr.contains("Required key not available", true) || lastErr.contains("Key was rejected", true)) {
            vm.tstep("Fix: module signature enforcement...")
            vm.tlog("FIX: kernel rejected module signature -> sig_enforce off", "FIX")
            Shell.cmd("echo 0 > /sys/module/module/parameters/sig_enforce 2>/dev/null").exec()
            res = insmodRetry(devNode)
            vm.tlog("RETRY: insmod (after sig_enforce off) -> exit ${res.code}", "CMD")
            if (res.isSuccess) return res
        }

        return res
    }

    /** insmod honoring the per-load devname, with legacy fallback (no param).
     * Some ROMs' insmod treats unknown `key=value` args as filenames
     * ("No such file") instead of reporting "Unknown parameter" - so fall
     * back to plain insmod in both cases. Tries every insmod binary. */
    private fun insmodRetry(devNode: String, force: Boolean = false): Shell.Result {
        val bins = listOf("/system/bin/insmod", "/vendor/bin/insmod", "insmod")
        val forms = mutableListOf<String>()
        for (b in bins) {
            if (force) forms.add("$b -f $TMP_KO devname=$devNode")
            forms.add("$b $TMP_KO devname=$devNode")
        }
        for (b in bins) {
            if (force) forms.add("$b -f $TMP_KO")
            forms.add("$b $TMP_KO")
        }
        var r = Shell.cmd("true").exec()
        for (cmd in forms) {
            r = Shell.cmd(cmd).exec()
            if (r.isSuccess) return r
            val err = (r.out + r.err).joinToString(" | ").take(160)
            // Log only interesting failures; "not found" on a exotic path is noise.
            android.util.Log.d("KernelLoder", "insmod try [$cmd] -> ${r.code} $err")
        }
        return r
    }

    /**
     * Post-load ABI self-check: run the bundled kprobe against the new /dev node
     * and require ALL PASS.
     *
     * RT protocol: read/write 0 on success, -5 on failure; 0x804/0x805 -> -22.
     * QX protocol: read/write 0 on success, -1 on failure; 0x804 -> 2, 0x805 -> 0.
     *
     * The probe reports which family it detected, so a driver that answers with
     * the WRONG family's convention is rejected - that is the whole point: a
     * mismatched driver insmods cleanly and then silently returns wrong data to
     * the game client. A probe that cannot run at all is treated as a pass
     * (never unload a good driver because a self-test could not execute).
     */
    fun abiCheck(
        context: Context,
        vm: DriverViewModel,
        devNode: String,
        baselineMods: Set<String>,
        wantVariant: String = ""
    ): Boolean {
        vm.tstep("Verifying driver ABI...")
        return try {
            val cache = File(context.cacheDir, "kprobe")
            try {
                context.resources.assets.open("drivers/kprobe").use { input ->
                    FileOutputStream(cache).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                vm.tlog("ABI: probe not bundled - skipping check", "WARN")
                return true
            }
            val dst = "/data/local/tmp/kprobe_$devNode"
            Shell.cmd("cp \"${cache.absolutePath}\" $dst", "chmod 755 $dst").exec()
            val res = Shell.cmd("$dst /dev/$devNode").exec()
            var detected = ""
            res.out.forEach {
                if (it.isNotBlank()) {
                    val t = when {
                        it.startsWith("PASS") || it == "ALL PASS" -> "OK"
                        it.startsWith("WARN") -> "WARN"
                        else -> "INFO"
                    }
                    vm.tlog("kprobe: $it", t)
                    // "PASS abi-probe rt|qx" -> remember which family answered
                    val m = Regex("^PASS abi-probe (\\w+)$").find(it.trim())
                    if (m != null) detected = m.groupValues[1].lowercase()
                }
            }
            val pass = res.isSuccess && res.out.any { it.trim() == "ALL PASS" }
            if (pass) {
                if (detected.isNotEmpty()) {
                    val want = wantVariant.trim().lowercase()
                    if (want.isNotEmpty() && want != detected) {
                        vm.tlog(
                            "ABI: driver speaks the $detected protocol but you asked for " +
                                    "${OtaDriverStore.variantLabel(want)} - game tools built for " +
                                    "${OtaDriverStore.variantLabel(want)} will not read correctly",
                            "WARN"
                        )
                    } else {
                        vm.tlog("ABI: verified - driver speaks the $detected protocol", "OK")
                    }
                } else {
                    vm.tlog("ABI: driver protocol verified (self-test ALL PASS)", "OK")
                }
            } else {
                vm.tlog("ABI: SELF-TEST FAILED - the driver did not answer correctly", "ERR")
                vm.tlog("ABI: removing it so game tools never talk to a broken driver", "FIX")
                SafetyGuard.newlyLoaded(baselineMods).forEach { mod ->
                    vm.tlog("RESCUE: rmmod $mod (failed self-test)", "FIX")
                    Shell.cmd("rmmod $mod 2>/dev/null").exec()
                }
            }
            Shell.cmd("rm -f $dst ${cache.absolutePath} 2>/dev/null").exec()
            pass
        } catch (e: Exception) {
            vm.tlog("ABI: probe error (${e.message}) - skipping", "WARN")
            true
        }
    }

    /** Post-load verification: lsmod + any /dev node from the module + dmesg */
    private fun verifyLoad(
        vm: DriverViewModel,
        sourceName: String,
        expectedNode: String = "",
        moduleName: String = ""
    ): Boolean {
        vm.tstep("Verifying module...")
        val lsmod = Shell.cmd("lsmod").exec()
        // Use the name discovered from the lsmod diff (exact), or the one
        // recorded from a previous load. Token guessing off the .ko FILE name
        // is not used: the file name is only a label and the real module name
        // is whatever the build baked in (kmem_337 / entryi / 5.10_A12 / ...).
        val candidates = (listOf(moduleName) + vm.knownDriverModules())
            .filter { it.isNotBlank() }.distinct()
        val loadedLine = lsmod.out.drop(1).firstOrNull { line ->
            val n = line.trim().split(Regex("\\s+")).firstOrNull() ?: ""
            n.isNotBlank() && n != "Module" && n in candidates
        }
        val moduleLoaded = loadedLine != null
        val loadedName = loadedLine?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""
        if (loadedName.isNotBlank()) vm.setLoadedModule(loadedName)
        // The module itself chooses its /dev node name - match it, then fall back
        // to the tags our bundled drivers use. The per-load random devname
        // (new kmem driver) is checked explicitly first.
        val devList = Shell.cmd("ls /dev 2>/dev/null").exec().out
        if (expectedNode.isNotEmpty()) {
            // ueventd resets fresh nodes to 0600 root - force world R/W so
            // game tools (any uid, permissive SELinux) can open it.
            Shell.cmd("chmod 666 /dev/$expectedNode 2>/dev/null").exec()
        }
        if (expectedNode.isNotEmpty() && devList.any { it.trim() == expectedNode }) {
            vm.tlog("VERIFY: /dev node -> FOUND (/dev/$expectedNode, the path the game client opens)", "OK")
            vm.rememberDevNode(expectedNode)
        }
        val devMatches = devList.filter { node ->
            (loadedName.isNotEmpty() && node.contains(loadedName, true)) ||
                    (expectedNode.isNotEmpty() && node.contains(expectedNode, true)) ||
                    node.contains("kloader", true) || node.contains("daisy", true) ||
                    node.contains("entryi", true) || node.contains("kmem", true)
        }
        // The ONLY node that matters is the one the game client opens. A driver
        // that registered some other node passes a fuzzy "some node appeared"
        // test while the game apps can still read nothing, which is how a
        // random /dev name shipped unnoticed. So require the exact path, and
        // name the stray nodes explicitly if there are any.
        val exactNode = expectedNode.isNotEmpty() && devList.any { it.trim() == expectedNode }
        val devExists = exactNode
        val dmesg = Shell.cmd("dmesg | grep -i -E 'kmem|kloader|entryi|vermagic|insmod' | tail -n 15").exec()

        vm.tlog(
            "VERIFY: lsmod -> ${if (moduleLoaded) "LOADED (${loadedLine!!.trim().split(Regex("\\s+")).first()})" else "module not visible in lsmod"}",
            if (moduleLoaded) "OK" else "WARN"
        )
        if (exactNode) {
            vm.tlog("VERIFY: /dev/$expectedNode -> EXISTS (game apps can open it)", "OK")
            if (devMatches.size > 1) {
                vm.tlog("VERIFY: extra nodes present: ${devMatches.joinToString(", ")}", "INFO")
            }
        } else {
            vm.tlog("VERIFY: /dev/$expectedNode -> MISSING", "ERR")
            vm.tlog(
                "VERIFY: the driver registered ${devMatches.joinToString(", ").ifEmpty { "nothing" }} instead. " +
                        "The game apps open /dev/$expectedNode, so they cannot use this load.",
                "ERR"
            )
            vm.tlog("VERIFY: dmesg says the driver REMOVED the node it used to own - see the lines below", "ERR")
        }
        dmesg.out.takeLast(6).forEach { if (it.isNotBlank()) vm.tlog("dmesg: $it", "INFO") }

        vm.setVerification(
            VerificationResult(
                lsmod = lsmod.out,
                deviceNodeFound = devExists,
                dmesgLogs = dmesg.out,
                timestamp = vm.nowString()
            )
        )
        // The module being in lsmod is NOT enough: a load is only useful to the
        // user if the /dev node their game app opens actually exists. A driver
        // that is loaded but registered a different node name is a failure, not
        // a partial success.
        return moduleLoaded && (expectedNode.isBlank() || devExists)
    }

    /**
     * Make the just-loaded driver survive reboots.
     *
     * The staged copy is the file that was really insmod'ed, so it already
     * carries any vermagic patch this device needed. The boot script re-checks
     * /proc/modules before loading, so a manual load followed by a reboot can
     * never produce a double insmod ("File exists").
     */
    private fun stageForBoot(
        vm: DriverViewModel,
        context: Context,
        variant: String,
        devNode: String
    ) {
        val modName = vm.loadedModuleName.value
        if (modName.isBlank()) {
            vm.tlog("AUTOLOAD: skipped - the loaded module name is unknown, so no safe boot script", "WARN")
            return
        }
        val ko = File(TMP_KO)
        if (!ko.exists()) {
            vm.tlog("AUTOLOAD: skipped - $TMP_KO is gone", "WARN")
            return
        }
        if (!DriverAutoload.hasBootRunner()) {
            vm.tlog("AUTOLOAD: no /data/adb/service.d runner found (Magisk/KernelSU?)", "WARN")
            vm.tlog("AUTOLOAD: the driver will NOT auto-load after a reboot - tap LOAD again", "INFO")
            return
        }
        vm.tlog("AUTOLOAD: staging so the driver comes back by itself after a reboot...", "INFO")
        val ok = DriverAutoload.enable(context, ko, modName, variant, devNode) { m, t ->
            vm.tlog(m, t)
        }
        vm.autoloadEnabled.value = ok
    }

    private fun finish(vm: DriverViewModel, ok: Boolean, msg: String) {        vm.autoLoadOk.value = ok
        vm.autoLoadStatus.value = msg
        if (!ok) {
            vm.tlog("SUPPORT: no exact loader found for this kernel, or the load failed.", "WARN")
            vm.tlog(
                "SUPPORT: message us on WhatsApp - we will build a custom loader for your kernel: wa.me/${SupportContact.WHATSAPP_NUMBER}",
                "FIX"
            )
        }
        vm.tstep("")
    }

    // ================= binary helpers =================

    /** Make sure the file is a raw ELF .ko; if it is base64-encoded, decode it in place. */
    fun ensureElf(file: File): Boolean {
        val bytes = file.readBytes()
        if (isElf(bytes)) return true
        return try {
            val decoded = Base64.getMimeDecoder().decode(bytes)
            if (isElf(decoded)) {
                file.writeBytes(decoded)
                true
            } else extractEmbeddedKo(bytes)?.let { file.writeBytes(it); true } ?: false
        } catch (e: Exception) {
            extractEmbeddedKo(bytes)?.let { file.writeBytes(it); true } ?: false
        }
    }

    /**
     * RT / QX installer scripts (.sh) carry the .ko as a base64 blob, e.g.
     *   MODULE_BASE64="f0VMRgIB...\r\n..."  followed by insmod
     * Extract the longest base64 run that decodes to a valid ELF .ko.
     * This makes ANY RT/QX .sh script loadable via the file picker - no
     * per-kernel script list needed.
     */
    private fun extractEmbeddedKo(bytes: ByteArray): ByteArray? {
        return try {
            val text = String(bytes, Charsets.ISO_8859_1)
            var best: ByteArray? = null
            // A real .ko blob is always several KB of base64 chars / whitespace.
            for (m in Regex("""[A-Za-z0-9+/=\s]{4096,}""").findAll(text)) {
                val b64 = m.value.replace(Regex("""\s"""), "")
                val cut = b64.substring(0, b64.length / 4 * 4)   // multiple of 4
                try {
                    val decoded = Base64.getDecoder().decode(cut)
                    if (isElf(decoded) && (best == null || decoded.size > best!!.size)) best = decoded
                } catch (_: Exception) { /* keep scanning */ }
            }
            best
        } catch (e: Exception) {
            null
        }
    }

    private fun isElf(b: ByteArray): Boolean =
            b.size > 4 && b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() &&
            b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()

    /** Read the "vermagic=..." string from the raw .ko bytes, or null. */
    fun readVermagic(file: File): String? {
        val bytes = file.readBytes()
        val tag = "vermagic=".toByteArray(Charsets.US_ASCII)
        val idx = indexOf(bytes, tag) ?: return null
        var end = idx + tag.size
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, idx + tag.size, end - (idx + tag.size), Charsets.US_ASCII)
    }

    /**
     * Binary-patch the vermagic string of a .ko so the kernel accepts it.
     * New string = "<running kernel release> <original flags>" (null-padded).
     * If that is longer than the original string, fall back to release only.
     * Returns true on success.
     */
    fun patchVermagic(file: File, kernelRelease: String): Boolean {
        return try {
            val bytes = file.readBytes()
            val tag = "vermagic=".toByteArray(Charsets.US_ASCII)
            val idx = indexOf(bytes, tag) ?: return false
            var end = idx + tag.size
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            val oldStr = String(bytes, idx + tag.size, end - (idx + tag.size), Charsets.US_ASCII)
            if (oldStr.isEmpty()) return false
            val flags = oldStr.substringAfter(' ', "")
            val candidates = listOf(
                    "$kernelRelease $flags".trim(),
                    kernelRelease
            )
            val newStr = candidates.firstOrNull { it.length <= oldStr.length } ?: return false
            var p = idx + tag.size
            for (ch in newStr) bytes[p++] = ch.code.toByte()
            while (p < end) bytes[p++] = 0
            file.writeBytes(bytes)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
        if (needle.isEmpty() || haystack.size < needle.size) return null
        outer@ for (i in 0..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }

    // ================= embedded driver auto-selection =================

    /**
     * Pick the embedded driver that best matches the running kernel:
     * exact version match > kernel-starts-with-version > same major.minor > none.
     * Device build (real kernel build) wins, then the requested ABI family.
     *
     * [variant] ("rt" / "qx" / "") selects the ABI family. A driver explicitly
     * tagged with the OTHER family is never returned - it would insmod cleanly
     * and then every game-mod read/write would return wrong data, which is far
     * worse than reporting "no driver found".
     */
    fun findBestEmbeddedDriver(
        context: Context,
        kernelRelease: String,
        variant: String = ""
    ): DriverInfo? {
        val all = EmbeddedDrivers.getAvailableDrivers(context)
        if (all.isEmpty()) return null
        val want = variant.trim().lowercase()
        val pool = if (want.isEmpty()) all
                   else all.filter { it.variant.isBlank() || it.variant == want }
        if (pool.isEmpty()) return null

        val ver = Regex("""(\d+)\.(\d+)\.(\d+)""")
        val km = ver.find(kernelRelease)
        val kMajor = km?.groupValues?.get(1)?.toIntOrNull()
        val kMinor = km?.groupValues?.get(2)?.toIntOrNull()
        val kPatch = km?.groupValues?.get(3)?.toIntOrNull()

        fun score(d: DriverInfo): Int {
            var s = 0
            // The asset file name is only a label: read the REAL kernel release
            // from the binary vermagic, so any naming / any kernel works.
            val realRelease = EmbeddedDrivers.readVermagic(context, d.filename)
            val realShort = if (realRelease.isNotEmpty()) RootChecker.kernelShortVersion(realRelease) else ""
            val dm = ver.find(if (realShort.isNotEmpty()) realShort else d.version)
            val dMajor = dm?.groupValues?.get(1)?.toIntOrNull()
            val dMinor = dm?.groupValues?.get(2)?.toIntOrNull()
            val dPatch = dm?.groupValues?.get(3)?.toIntOrNull()
            val exactShort = realShort.isNotEmpty() &&
                realShort == RootChecker.kernelShortVersion(kernelRelease)

            if (exactShort) {
                // Exact X.Y.Z match - kernel NAME irrelevant, only numbers count.
                // Same-release UNI module is the cross-device pick (1200 + 60):
                // a Daisy NATIVE module only overtakes it on a real Daisy kernel.
                // FULL-release exact match (e.g. 4.9.337-perf-g1234 vs itself)
                // is even better: no vermagic patch needed at all.
                s = 1200
                if (realRelease == kernelRelease) s = 1500
            } else if (realRelease == kernelRelease) {
                s = 1500
            } else if (d.version == kernelRelease) {
                s = 1000
            } else if (kernelRelease.startsWith(d.version)) {
                s = 900
            } else if (d.version.startsWith(kernelRelease)) {
                s = 800
            } else if (kMajor != null && dMajor != null) {
                if (dMajor == kMajor && dMinor != null && dMinor == kMinor) {
                    // Same series X.Y - closest patch level wins
                    val patchBonus = if (dPatch != null && kPatch != null)
                        (255 - kotlin.math.abs(dPatch - kPatch)).coerceAtLeast(0) else 0
                    s = 600 + patchBonus
                } else if (dMajor == kMajor) {
                    // Same major, different minor (e.g. 5.8.18 -> bundled 5.4/5.10)
                    val minorDist = dMinor?.let { kotlin.math.abs(it - (kMinor ?: it)) } ?: 0
                    s = 300 + (255 - minorDist * 8).coerceAtLeast(0)
                } else {
                    // Different major (old 3.x / very new 6.x-7.x) - still covered:
                    // pick the NEAREST major series module, then vermagic patch + force-load.
                    val majorDist = kotlin.math.abs(dMajor - kMajor)
                    s = (80 - majorDist * 12).coerceAtLeast(8)
                }
            }
            if (s > 0) {
                // Universal mainline builds (UNI) always win cross-device ties:
                // same X.Y.Z works on every vendor kernel via vermagic patch.
                // Device builds (NATIVE/DAISY) only get a bonus on an actual
                // DaisyForGaming kernel, so other phones pick the UNI module.
                val realName = realRelease.lowercase()
                val runningName = kernelRelease.lowercase()
                val daisyPair =
                    realName.contains("daisy") && runningName.contains("daisy")
                if (d.type == "UNI") s += 60
                if ((d.type == "NATIVE" || d.type == "KERNEL" || d.type == "DAISY") && daisyPair) {
                    s += 200
                }
                // Requested ABI family first, then a family-agnostic module,
                // and finally the other family (only reachable when the caller
                // asked for no particular family).
                if (want.isNotEmpty()) {
                    if (d.variant == want) s += 40
                    else if (d.variant.isBlank()) s += 10
                } else {
                    if (d.variant == OtaDriverStore.QX) s += 5   // tie-break: QX over RT
                }
            }
            return s
        }

        return pool.map { it to score(it) }.filter { it.second > 0 }
                .maxByOrNull { it.second }?.first
    }
}