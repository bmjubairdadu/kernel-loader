package com.kernelloader.driver

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernelloader.BuildConfig
import com.kernelloader.driver.OtaDriverStore
import com.kernelloader.mem.MemDirect
import com.kernelloader.root.RootChecker
import com.kernelloader.update.AppUpdateChecker
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UNIVERSAL KERNEL LOADER
 * ========================
 * This app loads a kernel module (.ko) on ANY rooted device.
 *
 * How it works:
 * 1. User presses the LOAD button
 * 2. App detects running kernel version (uname -r)
 * 3. OTA: exact-match .ko is downloaded from the GitHub driver database
 *    (fallback: best embedded match in assets/drivers/ + vermagic auto-patch)
 * 4. Module is loaded via insmod through the root shell (+ auto-fix ladder)
 * 5. Every step is printed in the Kernel Loder console (copyable)
 */
object EmbeddedDrivers {
    // Asset path helpers

    /**
     * Returns list of available embedded drivers with their versions.
     * Scans assets/drivers dynamically. Any *.ko dropped into assets/drivers is
     * picked up automatically - prefix (native_ / qx_ / rt_ / anything) is only
     * a label. Files without an underscore are used as-is.
     */
    fun getAvailableDrivers(context: Context): List<DriverInfo> {
        val drivers = mutableListOf<DriverInfo>()
        val names = try {
            context.resources.assets.list("drivers")?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        for (name in names) {
            if (!name.endsWith(".ko")) continue
            val base = name.removeSuffix(".ko")
            val idx = base.indexOf('_')
            val prefix = if (idx == -1) "" else base.substring(0, idx).uppercase(Locale.US)
            val type = if (idx == -1) "KERNEL" else prefix
            val version = if (idx == -1) base else base.substring(idx + 1)
            // RT_ / rt- / QX_ / qx- all map onto the two driver families the
            // game-mod clients actually speak.
            val variant = when {
                prefix.startsWith("RT") -> OtaDriverStore.RT
                prefix.startsWith("QX") -> OtaDriverStore.QX
                base.startsWith("rt-", true) -> OtaDriverStore.RT
                base.startsWith("qx-", true) -> OtaDriverStore.QX
                else -> ""
            }
            val description = when (type) {
                "UNI" -> "Universal build for kernel $version (any device)"
                "NATIVE", "DAISY" -> "Daisy device build for kernel $version (DaisyForGaming tree)"
                "QX" -> "QX driver for $version (QX ABI - ioctl 0x801/0x802/0x804)"
                "RT" -> "RT driver for $version (RT ABI - ioctl 0x801/0x802/0x803)"
                else -> "Kernel module build for $version"
            }
            drivers.add(DriverInfo(
                type = type,
                version = version,
                filename = "drivers/$name",
                displayName = "$type $version",
                description = description,
                variant = variant
            ))
        }
        return drivers.sortedWith(compareBy(
            { typeOrder(it.type) },
            { it.version }
        ))
    }

    /**
     * Read the REAL kernel release out of a .ko's vermagic string.
     * Prefix of the asset name is irrelevant - this is what the kernel checks.
     */
    fun readVermagic(context: Context, assetPath: String): String {
        return try {
            val bytes = context.resources.assets.open(assetPath).use { input ->
                val buf = ByteArray(1 shl 20)          // up to 1 MB is enough
                val n = input.read(buf)
                if (n <= 0) ByteArray(0) else buf.copyOf(n)
            }
            val tag = "vermagic=".toByteArray(Charsets.US_ASCII)
            outer@ for (i in 0..(bytes.size - tag.size)) {
                for (j in tag.indices) if (bytes[i + j] != tag[j]) continue@outer
                var end = i + tag.size
                while (end < bytes.size && bytes[end] != 0.toByte()) end++
                return String(bytes, i + tag.size, end - (i + tag.size), Charsets.US_ASCII)
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun typeOrder(t: String): Int = when (t) {
        "UNI", "KERNEL" -> 0
        "NATIVE", "DAISY" -> 1
        "QX" -> 2
        "RT" -> 3
        else -> 4
    }
}

data class DriverInfo(
    val type: String,
    val version: String,
    val filename: String,
    val displayName: String,
    val description: String,
    /** "" (any), "rt" or "qx" - which game-mod client ABI this driver speaks. */
    val variant: String = ""
)

data class LogEntry(
    val timestamp: String,
    val command: String,
    val stdout: List<String>,
    val stderr: List<String>,
    val exitCode: Int
)

data class VerificationResult(
    val lsmod: List<String> = emptyList(),
    val deviceNodeFound: Boolean = false,     // true when ANY /dev node from the module showed up
    val dmesgLogs: List<String> = emptyList(),
    val timestamp: String = "",
    val kernelRelease: String = "",
    val loadedModules: Int = 0
)

data class TerminalLine(
    val time: String,
    val text: String,
    val type: String = "INFO"  // INFO, CMD, OUT, OK, ERR, FIX, WARN
)

class DriverViewModel : ViewModel() {
    /** /dev node the game-mod clients hardcode. See [preferredDevNode]. */
    val DEFAULT_DEV_NODE = "wanbai"
    var devNodeOverride = mutableStateOf("")

    var pickedFileUri = mutableStateOf<Uri?>(null)
    var pickedFileName = mutableStateOf<String?>(null)
    val logs = mutableStateListOf<LogEntry>()
    var verificationResult = mutableStateOf<VerificationResult?>(null)

    // ---- Universal Terminal state ----
    val terminalLines = mutableStateListOf<TerminalLine>()
    var isBusy = mutableStateOf(false)
    var busyStep = mutableStateOf("")
    var autoLoadStatus = mutableStateOf("")   // final one-line result
    var autoLoadOk = mutableStateOf<Boolean?>(null)

    // ---- OTA (GitHub driver database) state ----
    var remoteManifest = mutableStateOf<OtaDriverStore.Manifest?>(null)
    var manifestStatus = mutableStateOf("IDLE")   // IDLE / LOADING / OK / EMPTY / OFFLINE

    // ---- Driver runtime state ----
    /** The real lsmod name of the driver we loaded ("", "kmem_337", "entryi", ...). */
    var loadedModuleName = mutableStateOf("")
    /** True when a memory driver is present in /proc/modules. */
    var driverLoaded = mutableStateOf(false)
    /** Human-readable module list, for the home screen. */
    var driverModule = mutableStateOf("")
    /** Boot auto-load (Magisk service.d) is installed. */
    var autoloadEnabled = mutableStateOf(false)

    /** Remember the module name discovered after a load (and after an unload). */
    fun setLoadedModule(name: String) {
        if (loadedModuleName.value != name) loadedModuleName.value = name
        driverLoaded.value = name.isNotBlank()
    }

    // ---- In-app auto-update (GitHub Releases database) state ----
    var appUpdate = mutableStateOf<AppUpdateChecker.UpdateInfo?>(null)
    var updateStatus = mutableStateOf("IDLE")     // IDLE / CHECKING / NONE / AVAILABLE / DOWNLOADING / DONE / ERROR
    var updateProgress = mutableStateOf(-1)       // 0..100 while DOWNLOADING, -1 otherwise
    var updateMsg = mutableStateOf("")

    /**
     * Auto-update check: compare our versionCode with the newest GitHub
     * Release. Runs automatically on app open; console-logged like everything
     * else so the user always sees what is happening.
     */
    fun checkForAppUpdate() {
        if (updateStatus.value == "CHECKING" || updateStatus.value == "DOWNLOADING") return
        updateStatus.value = "CHECKING"
        tlog("UPDATE: checking GitHub Releases (current v${BuildConfig.VERSION_CODE} ${BuildConfig.VERSION_NAME})...", "INFO")
        viewModelScope.launch(Dispatchers.IO) {
            val result = AppUpdateChecker.check(BuildConfig.UPDATE_API_URL, BuildConfig.VERSION_CODE)
            withContext(Dispatchers.Main) {
                when (result) {
                    is AppUpdateChecker.UpdateCheck.Available -> {
                        appUpdate.value = result.info
                        updateStatus.value = "AVAILABLE"
                        tlog(
                            "UPDATE: new version found - v${result.info.versionCode} ${result.info.versionName} " +
                                    "(${result.info.apkSize / 1024} KB, ${result.info.publishedAt.take(10)})",
                            "OK"
                        )
                        tlog("UPDATE: tap the update banner to install", "INFO")
                    }
                    AppUpdateChecker.UpdateCheck.UpToDate -> {
                        appUpdate.value = null
                        updateStatus.value = "NONE"
                        tlog("UPDATE: app is up-to-date (v${BuildConfig.VERSION_CODE})", "OK")
                    }
                    AppUpdateChecker.UpdateCheck.Offline -> {
                        if (updateStatus.value != "AVAILABLE") updateStatus.value = "ERROR"
                        tlog("UPDATE: GitHub unreachable (offline?) - skipped", "WARN")
                    }
                }
            }
        }
    }

    /** Download the newer APK from GitHub and open the system installer. */
    fun installAppUpdate(context: Context) {
        val info = appUpdate.value ?: return
        updateStatus.value = "DOWNLOADING"
        updateProgress.value = 0
        viewModelScope.launch(Dispatchers.IO) {
            if (!AppUpdateChecker.canInstall(context)) {
                withContext(Dispatchers.Main) {
                    tlog("UPDATE: \"install unknown apps\" permission needed - opening settings", "WARN")
                    updateStatus.value = "AVAILABLE"
                    updateProgress.value = -1
                    AppUpdateChecker.requestInstallPermission(context)
                }
                return@launch
            }
            val msg = AppUpdateChecker.downloadAndInstall(context, info) { pct ->
                viewModelScope.launch(Dispatchers.Main) { updateProgress.value = pct }
            }
            withContext(Dispatchers.Main) {
                updateMsg.value = msg
                updateStatus.value = if (msg.contains("installer opened")) "DONE" else "ERROR"
                updateProgress.value = -1
                tlog(msg, if (updateStatus.value == "DONE") "OK" else "ERR")
            }
        }
    }

    /**
     * Fetch drivers.json from the GitHub database so the UI can show every
     * kernel version that has a loader available. Runs automatically when the
     * app opens and can be pulled to refresh manually.
     */
    fun refreshManifest() {
        manifestStatus.value = "LOADING"
        tlog("DB: connecting to driver database...", "INFO")
        viewModelScope.launch(Dispatchers.IO) {
            val (m, error) = try {
                OtaDriverStore.fetchDetailed()
            } catch (e: Exception) {
                null to (e.message ?: "unexpected error")
            }
            withContext(Dispatchers.Main) {
                remoteManifest.value = m
                manifestStatus.value = when {
                    m == null -> "OFFLINE"
                    m.drivers.isEmpty() -> "EMPTY"
                    else -> "OK"
                }
                when {
                    m != null && m.drivers.isNotEmpty() ->
                        tlog("DB: connected - ${m.drivers.size} loaders available", "OK")
                    m != null ->
                        tlog("DB: connected but database is empty", "WARN")
                    else ->
                        tlog("DB: connection failed - $error", "ERR")
                }
            }
        }
    }

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    companion object {
        private const val MAX_TERMINAL_LINES = 1200
    }

    fun tlog(text: String, type: String = "INFO") {
        viewModelScope.launch(Dispatchers.Main) {
            terminalLines.add(TerminalLine(timeFormat.format(Date()), text, type))
            trimTerminal()
        }
    }

    fun tstep(step: String) {
        viewModelScope.launch(Dispatchers.Main) { busyStep.value = step }
    }

    private fun trimTerminal() {
        if (terminalLines.size > MAX_TERMINAL_LINES) {
            repeat(terminalLines.size - MAX_TERMINAL_LINES) { terminalLines.removeAt(0) }
        }
    }

    fun clearTerminal() { terminalLines.clear() }

    fun nowString(): String = dateFormat.format(Date())

    fun setVerification(result: VerificationResult) {
        viewModelScope.launch(Dispatchers.Main) { verificationResult.value = result }
    }

    fun getTerminalText(): String {
        return terminalLines.joinToString("\n") { "[${it.time}] ${it.text}" }
    }

    /** Driverless memory engine self-test (root + /proc/pid/mem, no .ko). */
    fun memTest() {
        viewModelScope.launch {
            tlog("# memtest (driverless, no driver needed)", "CMD")
            withContext(Dispatchers.IO) {
                try {
                    Shell.cmd("setenforce 0 2>/dev/null").exec()
                    val lines = MemDirect.selfTest()
                    withContext(Dispatchers.Main) {
                        lines.forEach { (text, type) -> tlog(text, type) }
                    }
                } catch (e: Exception) {
                    tlog("MEM ERROR: ${e.message}", "ERR")
                }
            }
        }
    }

    /** Run a user-typed command in the root shell and stream result into the terminal */
    fun runUserCommand(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty()) return
        viewModelScope.launch {
            tlog("# $c", "CMD")
            withContext(Dispatchers.IO) {
                try {
                    val res = Shell.cmd(c).exec()
                    withContext(Dispatchers.Main) {
                        res.out.forEach { if (it.isNotBlank()) terminalLines.add(TerminalLine(timeFormat.format(Date()), it, "OUT")) }
                        res.err.forEach { terminalLines.add(TerminalLine(timeFormat.format(Date()), it, "ERR")) }
                        terminalLines.add(TerminalLine(timeFormat.format(Date()), "exit ${res.code}", if (res.isSuccess) "OK" else "ERR"))
                        trimTerminal()
                    }
                    addLog(c, res.out, res.err, res.code)
                } catch (e: Exception) {
                    tlog("ERROR: ${e.message}", "ERR")
                }
            }
        }
    }

    /**
     * OTA auto-load: try to fetch an exact-match .ko from the OTA manifest first.
     * [variant] selects the ABI family ("rt" / "qx") so the RT button never
     * installs a QX driver (they would load but every read would fail).
     * Returns true if a driver was downloaded and loaded.
     */
    private suspend fun runOtaLoad(context: Context, variant: String = ""): Boolean {
        tlog("OTA: checking manifest...", "INFO")
        if (variant.isNotBlank()) {
            tlog("OTA: looking for a ${OtaDriverStore.variantLabel(variant)} driver", "INFO")
        }
        val manifest = OtaDriverStore.fetchManifest() ?: run {
            tlog("OTA: manifest unavailable - see DB error above, then tap refresh", "WARN")
            return false
        }
        if (manifest.drivers.isEmpty()) {
            tlog("OTA: manifest has no drivers", "WARN")
            return false
        }
        val pool = OtaDriverStore.variantsFor(manifest, variant)
        if (pool.isEmpty()) {
            tlog(
                "OTA: database has no ${OtaDriverStore.variantLabel(variant)} driver " +
                        "(${manifest.drivers.size} other family entries available)",
                "WARN"
            )
            return false
        }
        val kernel = RootChecker.getKernelRelease() ?: return false
        val resolved = OtaDriverStore.resolve(manifest, kernel, variant)
        when (resolved) {
            is OtaDriverStore.ResolveResult.Exact -> {
                tlog("OTA: exact match found for $kernel", "OK")
                return downloadAndLoadOta(context, manifest, resolved.entry)
            }
            is OtaDriverStore.ResolveResult.Near -> {
                val target = resolved.entry.version
                tlog(
                    "OTA: no exact match; nearest loader = $target (distance ${resolved.distance})",
                    "WARN"
                )
                // ---------- REBOOT GUARD 1: force-load only on same major.minor ----------
                if (!SafetyGuard.canForceLoad(kernel, target)) {
                    SafetyGuard.refusalLines(kernel, target).forEach { tlog(it.first, it.second) }
                    return false
                }
                // ---------- REBOOT GUARD 2: load nothing when the kernel is already sick ----------
                if (SafetyGuard.kernelLooksUnstable()) {
                    SafetyGuard.unstableLines().forEach { tlog(it.first, it.second) }
                    return false
                }
                tlog(
                    "SAFETY: same kernel series (${SafetyGuard.majorMinor(kernel)}) - " +
                            "trying nearest-loader force-load",
                    "FIX"
                )
                return downloadAndLoadOta(context, manifest, resolved.entry, force = true)
            }
            is OtaDriverStore.ResolveResult.None -> {
                tlog("OTA: no driver available for this kernel series", "INFO")
                return false
            }
        }
    }

    private suspend fun downloadAndLoadOta(
        context: Context,
        manifest: OtaDriverStore.Manifest,
        entry: OtaDriverStore.DriverEntry,
        force: Boolean = false
    ): Boolean {
        val downloaded = OtaDriverStore.downloadDriver(
            context = context,
            baseUrl = manifest.baseUrl,
            entry = entry,
            onLog = { msg, kind -> tlog(msg, kind) }
        ) ?: return false
        try {
            if (!UniversalKernelLoader.ensureElf(downloaded)) {
                tlog("OTA: downloaded file is not a valid ELF .ko", "ERR")
                return false
            }

            val kernel = RootChecker.getKernelRelease() ?: return false
            tlog("OTA: loading ${downloaded.name} ($kernel)...", "INFO")

            // ---------- STAGING: app-private files/ is not openable by insmod
            // on many ROMs ("No such file or directory" even though the file
            // exists). Stage to /data/local/tmp like the embedded path. ------
            val staged = File("/data/local/tmp/kloader_ota.ko")
            // MUST be the fixed /dev/wanbai, never a random name: the game-mod
            // clients hardcode that path, and a devname= parameter makes the
            // driver remove /dev/wanbai. See preferredDevNode().
            val devNode = preferredDevNode()
            Shell.cmd(
                "cp \"${downloaded.absolutePath}\" ${staged.absolutePath}",
                "chmod 644 ${staged.absolutePath}",
                "chown root:root ${staged.absolutePath} 2>/dev/null",
                "chcon u:object_r:system_file:s0 ${staged.absolutePath} 2>/dev/null",
                "setenforce 0 2>/dev/null"
            ).exec()
            if (!Shell.cmd("test -f ${staged.absolutePath}").exec().isSuccess) {
                tlog("OTA: staging to /data/local/tmp failed - cannot load", "ERR")
                return false
            }
            tlog("OTA: staged ${staged.absolutePath} (/dev/$devNode)", "OK")
            // Stale cleanup first: a leftover module from an earlier boot
            // session answers "File exists" to every insmod.
            Shell.cmd("rmmod kmem_337 2>/dev/null", "sleep 1").exec()
            try {

            // ---------- REBOOT GUARD 3: baseline of loaded modules ----------
            val before = SafetyGuard.loadedModuleNames()
            if (before.isNotEmpty()) {
                tlog("SAFETY: baseline modules = ${before.size} (rescue rmmod ready)", "INFO")
            }

            // If the .ko was built for another release, patch vermagic first
            // (mismatched vermagic => kernel rejects, or panic when forced).
            val vermagic = try { UniversalKernelLoader.readVermagic(downloaded) } catch (e: Exception) { null }
            if (vermagic != null &&
                RootChecker.kernelShortVersion(vermagic) != RootChecker.kernelShortVersion(kernel)
            ) {
                tlog("FIX: vermagic \"$vermagic\" != device \"$kernel\" - patching", "FIX")
                if (UniversalKernelLoader.patchVermagic(downloaded, kernel)) {
                    tlog("FIX: vermagic patched OK -> \"$kernel\"", "OK")
                    // Re-stage: insmod runs from the staged copy, not the download.
                    Shell.cmd(
                        "cp \"${downloaded.absolutePath}\" ${staged.absolutePath}",
                        "chmod 644 ${staged.absolutePath}"
                    ).exec()
                } else {
                    tlog("FIX: vermagic patch failed (string too long) - will try force-load", "WARN")
                }
            }

            // ---------- insmod ladder: every binary x every arg form ----------
            // This device's default insmod says "No such file" for a file
            // that provably exists, so try explicit binaries (/system,
            // /vendor, busybox) with and without devname= / -f, and log each.
            val bins = listOf(
                "/system/bin/insmod", "/vendor/bin/insmod", "insmod",
                "/data/adb/magisk/busybox insmod", "busybox insmod"
            )
            val forms = mutableListOf<String>()
            for (b in bins) {
                forms.add("$b ${staged.absolutePath} devname=$devNode")
                forms.add("$b ${staged.absolutePath}")
                if (force) {
                    forms.add("$b -f ${staged.absolutePath} devname=$devNode")
                    forms.add("$b -f ${staged.absolutePath}")
                }
            }
            var res = Shell.cmd("true").exec()
            for (cmd in forms) {
                res = Shell.cmd(cmd).exec()
                val oneLine = (res.out + res.err).firstOrNull { it.isNotBlank() } ?: ""
                tlog("TRY: $cmd -> exit ${res.code} $oneLine", if (res.isSuccess) "OK" else "INFO")
                if (res.isSuccess) break
            }

            if (!res.isSuccess) {
                tlog("OTA: insmod failed (exit ${res.code})", "ERR")
                val errText = (res.out + res.err).joinToString("\n")
                // One-shot environment dump so the exact cause is visible.
                Shell.cmd(
                    "ls -l /system/bin/insmod /vendor/bin/insmod 2>&1",
                    "for b in /system/bin/insmod /vendor/bin/insmod; do echo \"== \$b\"; \$b 2>&1 | head -n 3; done",
                    "cat ${staged.absolutePath} > /dev/null 2>&1 && echo READ_OK || echo READ_FAIL",
                    "od -An -tx1 ${staged.absolutePath} 2>/dev/null | head -n 1",
                    "dmesg 2>/dev/null | tail -n 10"
                ).exec().out.forEach { if (it.isNotBlank()) tlog("DIAG: $it", "INFO") }
                if (errText.contains("Invalid module format", true) ||
                    errText.contains("vermagic", true) ||
                    errText.contains("Exec format error", true)
                ) {
                    tlog("DIAGNOSE: vermagic / module format mismatch - this loader will not run on this kernel", "WARN")
                    tlog("ACTION: phone did not restart (nothing was forced).", "INFO")
                } else if (errText.contains("No such file or directory", true)) {
                    tlog("DIAGNOSE: staged file not openable - try: chmod 644 + copy the .ko to /data/local/tmp manually", "WARN")
                }
                tlog("SUPPORT: this kernel ($kernel) needs a custom loader - tap the WhatsApp button below", "FIX")
                return false
            }

            tlog("OTA: insmod OK (exit ${res.code})", "OK")
            // ueventd resets fresh nodes to 0600 root - force world R/W.
            Shell.cmd("chmod 666 /dev/$devNode 2>/dev/null").exec()

            // ---------- REBOOT GUARD 4: post-load health check + rescue ----------
            waitForStability()
            if (SafetyGuard.kernelLooksUnstable()) {
                tlog("SAFETY: kernel unstable after load (panic/oops signature caught)", "ERR")
                val newMods = SafetyGuard.newlyLoaded(before)
                if (newMods.isEmpty()) {
                    tlog("SAFETY: new module name not found - check /proc/modules", "WARN")
                }
                var rescued = false
                newMods.forEach { mod ->
                    tlog("RESCUE: rmmod $mod (preventing phone restart)", "FIX")
                    if (SafetyGuard.rescueUnload(mod)) {
                        rescued = true
                        tlog("RESCUE: $mod unloaded - kernel stable, phone will not restart", "OK")
                    } else {
                        tlog("RESCUE: rmmod $mod failed - module is still loaded", "WARN")
                    }
                }
                tlog(
                    if (rescued)
                        "RESULT: loader was unsafe, so it was removed. Phone did not restart."
                    else
                        "RESULT: loader unsafe - send the console log via WhatsApp (before any restart)",
                    "WARN"
                )
                tlog("SUPPORT: request a custom loader from the WhatsApp button below", "FIX")
                withContext(Dispatchers.Main) {
                    autoLoadOk.value = false
                    autoLoadStatus.value = if (rescued)
                        "Unsafe loader removed (no restart)" else "Loader unstable - contact support"
                    tstep("")
                }
                return false
            }

            tlog("SAFETY: kernel stable - no phone restart risk", "OK")
            withContext(Dispatchers.Main) { verifyModule() }
            if (!UniversalKernelLoader.abiCheck(context, this, devNode, before, entry.variant)) {
                tlog("RESULT: self-test failed - the driver was removed, phone safe", "WARN")
                withContext(Dispatchers.Main) {
                    autoLoadOk.value = false
                    autoLoadStatus.value = "Self-test failed (driver removed)"
                    tstep("")
                }
                return false
            }
            return true
            } finally {
                Shell.cmd("rm -f ${staged.absolutePath} 2>/dev/null").exec()
            }
        } finally {
            if (downloaded.exists()) downloaded.delete()
        }
    }

    /** Small settle window so dmesg can show a problem before we call it a success. */
    private suspend fun waitForStability() {
        withContext(Dispatchers.IO) { try { Thread.sleep(900) } catch (e: InterruptedException) { } }
    }

    private val _forceNote: String
        get() = "nearest-series loader, restart guard active"

    /**
     * UNIVERSAL AUTO-LOAD:
     * - Works on old & new kernels
     * - Auto-fixes problems (SELinux, permissions, vermagic mismatch, force-load)
     * - Auto-picks best embedded driver if no file is picked
     * - Streams every step into the terminal
     */
    fun autoLoadUniversal(context: Context, preferOta: Boolean = true, variant: String = "") {
        if (isBusy.value) return
        isBusy.value = true
        busyStep.value = "Starting..."
        autoLoadOk.value = null
        autoLoadStatus.value = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (preferOta) {
                    val handled = runOtaLoad(context, variant)
                    if (handled) {
                        isBusy.value = false
                        busyStep.value = ""
                        return@launch
                    }
                    tlog("OTA: no OTA driver; falling back to embedded", "INFO")
                }
                withContext(Dispatchers.Main) {
                    UniversalKernelLoader.autoLoad(context, this@DriverViewModel, variant)
                }
            } catch (e: Exception) {
                tlog("FATAL: ${e.message}", "ERR")
                autoLoadStatus.value = "FATAL: ${e.message}"
                autoLoadOk.value = false
            } finally {
                viewModelScope.launch(Dispatchers.Main) {
                    isBusy.value = false
                    busyStep.value = ""
                }
            }
        }
    }

    private fun addTerminalFor(entry: LogEntry) {
        val type = if (entry.exitCode == 0) "CMD" else "ERR"
        terminalLines.add(TerminalLine(entry.timestamp.substringBefore('.'), "$ ${entry.command}  (exit ${entry.exitCode})", type))
        entry.stderr.forEach { if (it.isNotBlank()) terminalLines.add(TerminalLine(entry.timestamp.substringBefore('.'), it, "ERR")) }
        trimTerminal()
    }

    private fun addLog(command: String, stdout: List<String>, stderr: List<String>, exitCode: Int) {
        val timestamp = dateFormat.format(Date())
        val entry = LogEntry(timestamp, command, stdout, stderr, exitCode)
        viewModelScope.launch(Dispatchers.Main) {
            logs.add(entry)
            addTerminalFor(entry)
        }
    }

    fun verifyModule() {
        viewModelScope.launch {
            val kernelRelease = try {
                Shell.cmd("uname -r").exec().out.firstOrNull()?.trim() ?: ""
            } catch (e: Exception) { "" }

            val lsmodRes = Shell.cmd("lsmod").exec()
            // Module names = first column of lsmod (skip header). Any device/model works.
            val moduleNames = lsmodRes.out.drop(1)
                .map { it.trim().split(Regex("\\s+")).firstOrNull() ?: "" }
                .filter { it.isNotBlank() && it != "Module" }

            // The device node name is chosen by the module itself, so first look for
            // /dev entries matching a loaded module name, then fall back to known tags.
            val devList = Shell.cmd("ls /dev 2>/dev/null").exec().out
            val matchedNodes = devList.filter { node ->
                moduleNames.any { m -> node.contains(m, ignoreCase = true) }
            }
            val candidateNodes = (moduleNames + matchedNodes + listOf("kloaderctl", "daisyctl", "entryi", "kmem_337"))
                .filter { it.isNotBlank() }.distinct()
            val devCmd = candidateNodes.joinToString(" ") { "ls /dev/$it 2>/dev/null;" } + " true"
            val devRes = Shell.cmd(devCmd).exec()

            val dmesgRes = Shell.cmd(
                "dmesg | grep -i -E 'kmem|kloader|entryi|module|vermagic|insmod|misc|driver' | tail -n 8"
            ).exec()

            val timestamp = dateFormat.format(Date())
            verificationResult.value = VerificationResult(
                lsmod = lsmodRes.out,
                deviceNodeFound = devRes.out.any { it.isNotBlank() },
                dmesgLogs = dmesgRes.out,
                timestamp = timestamp,
                kernelRelease = kernelRelease,
                loadedModules = moduleNames.size
            )

            addLog("lsmod", lsmodRes.out, lsmodRes.err, lsmodRes.code)
            addLog("ls /dev/<matching module nodes>", devRes.out, devRes.err, devRes.code)
            addLog("dmesg | grep module", dmesgRes.out, dmesgRes.err, dmesgRes.code)
        }
    }

    fun onFilePicked(context: Context, uri: Uri) {
        pickedFileUri.value = uri
        pickedFileName.value = getFileName(context, uri)
        addLog("File picked: ${pickedFileName.value}", emptyList(), emptyList(), 0)
    }

    /**
     * Module names this app is allowed to touch. A kernel module's lsmod name
     * is baked in at BUILD time (KBUILD_MODNAME / .modinfo "name=") and is
     * unrelated to the .ko file name:
     *
     *   rt_4.9.337-DaisyForGaming.ko  -> kmem_337
     *   qx_4.9.337-DaisyForGaming.ko  -> kmem_337_qx
     *   rt_4.14.117.ko                -> 5.10_A12
     *   qx_4.14.117.ko                -> entryi
     *
     * The list is seeded with the names seen in this driver's own builds; the
     * authoritative one is whatever the lsmod diff reports at load time, which
     * is what [loadedModuleName] holds. UNLOAD only ever rmmods a name from
     * this set - never "the last module in lsmod", which could be an unrelated
     * driver (touching that can take the phone's camera, touch, wifi, ... down).
     */
    private val KNOWN_DRIVER_MODULES = listOf(
        "kmem_337", "kmem_337_qx", "kmem", "entryi", "kloader",
        "5.10_A12", "wanbai", "daisy"
    )

    /** Every driver module name we may inspect or unload. */
    fun knownDriverModules(): List<String> =
        (KNOWN_DRIVER_MODULES + loadedModuleName.value).filter { it.isNotBlank() }.distinct()

    /**
     * Unload the memory driver. ONLY unloads - it never installs, never
     * patches a vermagic and never reboots. Returns the module it removed.
     */
    /**
     * The /dev node to ask the driver to register.
     *
     * The game-mod clients (Aincrad 3.7, Angry Mod V1) open a hardcoded path.
     * Reversing them showed the string `/dev` immediately followed by
     * `wanbai` and a `%s/%s` format, i.e. they build `/dev/wanbai` and open
     * that - with no discovery step and no way to be told a different name.
     *
     * So this must stay "wanbai". It was previously a random 8-letter string
     * "so anti-cheat cannot fingerprint it", which made the driver unlink
     * /dev/wanbai and create the random node instead: the load appeared to
     * succeed while the game apps could not open anything at all.
     */
    fun preferredDevNode(): String = if (devNodeOverride.value.isNotBlank()) {
        devNodeOverride.value.trim()
    } else {
        DEFAULT_DEV_NODE
    }

    /** Remember the last node we actually verified on /dev, for the next load. */
    fun rememberDevNode(node: String) {
        if (node.isNotBlank() && node != DEFAULT_DEV_NODE) devNodeOverride.value = node.trim()
    }

    fun unloadModule(context: Context) {
        if (isBusy.value) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val loaded = SafetyGuard.loadedModuleNames()
                    // 1. prefer the name this app actually loaded
                    val remembered = loadedModuleName.value
                    // 2. otherwise any known driver module the kernel holds
                    val known = knownDriverModules().filter { it in loaded }
                    // NEVER fall back to "some other module" - unloading a
                    // module we did not install can disable an unrelated driver.
                    val target = when {
                        remembered.isNotBlank() && remembered in loaded -> remembered
                        known.isNotEmpty() -> known.first()
                        else -> {
                            tlog("UNLOAD: no memory driver is loaded - nothing to unload", "WARN")
                            DriverAutoload.state(context)
                            if (DriverAutoload.enabled) {
                                tlog("UNLOAD: boot auto-load is ON, so it will load again at next boot", "INFO")
                            }
                            driverLoaded.value = false
                            return@withContext
                        }
                    }

                    tlog("UNLOAD: rmmod '$target'", "INFO")
                    var res = Shell.cmd("rmmod $target").exec()
                    if (!res.isSuccess) {
                        // busybox fallback (some ROMs ship a broken rmmod)
                        res = Shell.cmd(
                            "BB=\$(command -v busybox); [ -z \"\$BB\" ] && BB=/data/adb/magisk/busybox; \$BB rmmod $target"
                        ).exec()
                    }
                    tlog("UNLOAD: rmmod $target -> exit ${res.code}", if (res.isSuccess) "OK" else "ERR")
                    res.err.forEach { if (it.isNotBlank()) tlog("UNLOAD: $it", "WARN") }
                    addLog("rmmod $target", res.out, res.err, res.code)

                    if (res.isSuccess) {
                        tlog("UNLOAD: driver removed", "OK")
                        DriverAutoload.state(context)
                        if (DriverAutoload.enabled) {
                            tlog("UNLOAD: boot auto-load is still ON - it will load again at next boot", "WARN")
                            tlog("UNLOAD: turn the auto-load switch off to keep it unloaded", "INFO")
                        }
                    } else {
                        tlog("UNLOAD: FAILED - if the module is busy, a normal reboot clears it", "WARN")
                    }
                    setLoadedModule("")
                    refreshDriverState(context)
                } catch (e: Exception) {
                    tlog("UNLOAD ERROR: ${e.message}", "ERR")
                }
            }
        }
    }

    /** Turn boot auto-load on/off. The .ko was staged by the last successful load. */
    fun setBootAutoload(context: Context, on: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    DriverAutoload.state(context)
                    if (on) {
                        val staged = DriverAutoload.stagedKo
                        if (staged.isBlank()) {
                            tlog("AUTOLOAD: load the driver once first, then switch this on", "WARN")
                            autoloadEnabled.value = false
                            return@withContext
                        }
                        val ok = DriverAutoload.enable(
                            context,
                            File(staged),
                            loadedModuleName.value.ifBlank { DriverAutoload.moduleName },
                            DriverAutoload.variant,
                            DriverAutoload.devNode
                        ) { m, t -> tlog(m, t) }
                        autoloadEnabled.value = ok
                    } else {
                        DriverAutoload.disable(context) { m, t -> tlog(m, t) }
                        autoloadEnabled.value = false
                    }
                } catch (e: Exception) {
                    tlog("AUTOLOAD ERROR: ${e.message}", "ERR")
                    autoloadEnabled.value = false
                }
            }
        }
    }

    /** Re-read the real driver state from the kernel (called at screen open). */
    fun refreshDriverState(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            DriverAutoload.state(context)
            autoloadEnabled.value = DriverAutoload.enabled
            val loaded = SafetyGuard.loadedModuleNames()
            val hit = knownDriverModules().filter { it in loaded }
            if (hit.isNotEmpty()) setLoadedModule(hit.first())
            driverLoaded.value = hit.isNotEmpty()
            driverModule.value = hit.joinToString(", ")
        }
    }

    /**
     * Non-numeric tokens of a .ko file name - used to locate the module in lsmod,
     * because a module's internal name can differ from the .ko file name.
     */
    private fun nameTokens(fileName: String?): List<String> {
        val base = (fileName ?: "").removeSuffix(".ko")
        val tokens = base.split('_', '-', '.').filter { it.length >= 3 && it.any { c -> !c.isDigit() } }
        return tokens.ifEmpty { listOf("kloader", "driver") }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                name = name?.substring(cut + 1)
            }
        }
        return name
    }
}
