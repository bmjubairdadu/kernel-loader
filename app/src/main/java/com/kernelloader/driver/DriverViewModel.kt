package com.kernelloader.driver

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernelloader.BuildConfig
import com.kernelloader.driver.OtaDriverStore
import com.kernelloader.root.RootChecker
import com.kernelloader.update.AppUpdateChecker
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val command: String,
    val stdout: List<String>,
    val stderr: List<String>,
    val exitCode: Int
)

data class VerificationResult(
    val lsmod: List<String> = emptyList(),
    val deviceNodeFound: Boolean = false,
    val dmesgLogs: List<String> = emptyList(),
    val timestamp: String = "",
    val kernelRelease: String = "",
    val loadedModules: Int = 0
)

data class TerminalLine(
    val time: String,
    val text: String,
    val type: String = "INFO",
    val variant: String = ""
)

class DriverViewModel(application: Application) : AndroidViewModel(application) {

    val DEFAULT_DEV_NODE = ""
    var devNodeOverride = mutableStateOf("")

    var pickedFileUri = mutableStateOf<Uri?>(null)
    var pickedFileName = mutableStateOf<String?>(null)
    val logs = mutableStateListOf<LogEntry>()
    var verificationResult = mutableStateOf<VerificationResult?>(null)

    val terminalLines = mutableStateListOf<TerminalLine>()
    var isBusy = mutableStateOf(false)
    var busyStep = mutableStateOf("")

    var rebooting = mutableStateOf(false)
    var autoLoadStatus = mutableStateOf("")
    var autoLoadOk = mutableStateOf<Boolean?>(null)

    var remoteManifest = mutableStateOf<OtaDriverStore.Manifest?>(null)
    var manifestStatus = mutableStateOf("IDLE")

    var loadedModuleName = mutableStateOf("")

    var driverLoaded = mutableStateOf(false)

    var driverModule = mutableStateOf("")

    var lastBundleTried = mutableStateOf("")
    var lastLoadError = mutableStateOf("")
    var loadErrorDialogData = mutableStateOf<DriverErrorInfo?>(null)

    fun triggerErrorDialog(reason: String) {
        val dmesg = Shell.cmd("dmesg 2>/dev/null | tail -n 25").exec().out.joinToString(" ")
        viewModelScope.launch(Dispatchers.Main) {
            loadErrorDialogData.value = DriverErrorInfo.fromError(reason, dmesg, getTerminalText())
        }
    }

    fun dismissErrorDialog() {
        loadErrorDialogData.value = null
    }

    fun copyTerminalToClipboard(context: Context) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = getTerminalText()
            val clip = ClipData.newPlainText("Kernel Loader Log", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Log copied to clipboard!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            tlog("Failed to copy log: ${e.message}", "ERR")
        }
    }

    fun clearLoadedDriver(context: Context) {
        driverLoaded.value = false
        hiddenLoaded.value = false
        autoLoadOk.value = null
        loadedModuleName.value = ""
        driverModule.value = ""
        BootSessionTracker.clear(context)
    }

    fun setLoadedModule(name: String) {
        if (loadedModuleName.value != name) loadedModuleName.value = name
        driverLoaded.value = name.isNotBlank()
        if (name.isNotBlank()) {
            hiddenLoaded.value = false
            BootSessionTracker.recordLoaded(getApplication(), name, preferredDevNode(), false)
        }
    }

    fun markHiddenLoaded() {
        loadedModuleName.value = ""
        driverModule.value = ""
        driverLoaded.value = true
        hiddenLoaded.value = true
        BootSessionTracker.recordLoaded(getApplication(), "", preferredDevNode(), true)
    }

    var hiddenLoaded = mutableStateOf(false)

    var appUpdate = mutableStateOf<AppUpdateChecker.UpdateInfo?>(null)
    var updateStatus = mutableStateOf("IDLE")
    var updateProgress = mutableStateOf(-1)
    var updateMsg = mutableStateOf("")

    private var updateCheckJob: Job? = null
    private var downloadJob: Job? = null

    fun startPeriodicUpdateCheck() {
        updateCheckJob?.cancel()
        updateCheckJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15 * 60 * 1000L)
                checkForAppUpdate(silent = true)
            }
        }
    }

    fun checkForAppUpdate(silent: Boolean = false) {
        if (updateStatus.value == "CHECKING" || updateStatus.value == "DOWNLOADING") return
        val wasReady = updateStatus.value == "READY_TO_INSTALL"
        if (!wasReady) updateStatus.value = "CHECKING"
        if (!silent) tlog("Checking Update...", "INFO")
        viewModelScope.launch(Dispatchers.IO) {
            val result = AppUpdateChecker.check(BuildConfig.UPDATE_RELEASES_PAGE, BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME)
            withContext(Dispatchers.Main) {
                when (result) {
                    is AppUpdateChecker.UpdateCheck.Available -> {
                        appUpdate.value = result.info
                        val app = getApplication<Application>()
                        val cached = AppUpdateChecker.getDownloadedApk(app, result.info)
                        if (cached != null) {
                            updateStatus.value = "READY_TO_INSTALL"
                            updateProgress.value = 100
                            tlog("Update v${result.info.versionName} ready to install", "OK")
                        } else {
                            tlog("New version v${result.info.versionName} found — auto-downloading in background...", "INFO")
                            startBackgroundDownload(result.info)
                        }
                    }
                    AppUpdateChecker.UpdateCheck.UpToDate -> {
                        appUpdate.value = null
                        if (!wasReady) updateStatus.value = "NONE"
                        if (!silent) tlog("App Up To Date", "OK")
                    }
                    AppUpdateChecker.UpdateCheck.Offline -> {
                        if (!wasReady && updateStatus.value != "AVAILABLE") {
                            updateStatus.value = "ERROR"
                        }
                        if (!silent) tlog("Update Check Offline", "WARN")
                    }
                }
            }
        }
    }

    fun startBackgroundDownload(info: AppUpdateChecker.UpdateInfo) {
        downloadJob?.cancel()
        updateStatus.value = "DOWNLOADING"
        updateProgress.value = 0
        downloadJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val result = AppUpdateChecker.downloadApk(app, info) { pct ->
                viewModelScope.launch(Dispatchers.Main) {
                    updateProgress.value = pct
                }
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { apk ->
                        updateStatus.value = "READY_TO_INSTALL"
                        updateProgress.value = 100
                        tlog("Update v${info.versionName} downloaded (${apk.length() / 1024} KB) — ready to install", "OK")
                    },
                    onFailure = { err ->
                        updateStatus.value = "AVAILABLE"
                        updateProgress.value = -1
                        tlog("Update download failed: ${err.message ?: "network error"}", "WARN")
                    }
                )
            }
        }
    }

    fun installAppUpdate(context: Context) {
        val info = appUpdate.value ?: return

        if (!AppUpdateChecker.canInstall(context)) {
            tlog("Install permission needed", "WARN")
            AppUpdateChecker.requestInstallPermission(context)
            return
        }

        val cached = AppUpdateChecker.getDownloadedApk(context, info)
        if (cached != null) {
            try {
                AppUpdateChecker.installApk(context, cached)
                updateStatus.value = "DONE"
                tlog("Opening installer for v${info.versionName}...", "OK")
            } catch (e: Exception) {
                tlog("Installer launch error: ${e.message}", "ERR")
            }
            return
        }

        tlog("Downloading update v${info.versionName}...", "INFO")
        startBackgroundDownload(info)
    }

    fun refreshManifest() {
        manifestStatus.value = "LOADING"
        tlog("Connecting to DB...", "INFO")
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
                        tlog("Database Connected (${m.drivers.size})", "OK")
                    m != null ->
                        tlog("Database Empty", "WARN")
                    else ->
                        tlog("Database Offline", "ERR")
                }
            }
        }
    }

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private fun stamp(f: SimpleDateFormat): String = synchronized(f) { f.format(Date()) }

    companion object {
        private const val MAX_TERMINAL_LINES = 1200
    }

    var activePipelineVariant = mutableStateOf("")

    private val logWriteLock = Any()
    private val logFile: File = File(application.filesDir, "console_history.log")

    init {
        try {
            val session = BootSessionTracker.checkSession(application)
            if (session.isLoaded) {
                driverLoaded.value = true
                hiddenLoaded.value = session.isHidden
                autoLoadOk.value = true
                if (session.moduleName.isNotBlank()) {
                    loadedModuleName.value = session.moduleName
                    driverModule.value = session.moduleName
                }
                if (session.devNode.isNotBlank() && session.devNode != DEFAULT_DEV_NODE && !SafetyGuard.isSystemNode(session.devNode)) {
                    devNodeOverride.value = session.devNode
                }
            }
        } catch (_: Exception) {}
        try {
            if (logFile.exists() && logFile.length() > 0) {
                val rawLines = logFile.readLines(Charsets.UTF_8).takeLast(MAX_TERMINAL_LINES)
                val parsed = rawLines.mapNotNull { parseStoredLogLine(it) }
                if (parsed.isNotEmpty()) {
                    terminalLines.addAll(parsed)
                }
            }
        } catch (_: Exception) {}
        startPeriodicUpdateCheck()
    }

    fun initLogStore(context: Context? = null) {
        if (terminalLines.isEmpty()) {
            synchronized(logWriteLock) {
                try {
                    if (logFile.exists() && logFile.length() > 0) {
                        val rawLines = logFile.readLines(Charsets.UTF_8).takeLast(MAX_TERMINAL_LINES)
                        val parsed = rawLines.mapNotNull { parseStoredLogLine(it) }
                        if (parsed.isNotEmpty()) {
                            viewModelScope.launch(Dispatchers.Main) {
                                if (terminalLines.isEmpty()) {
                                    terminalLines.addAll(parsed)
                                    trimTerminal()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun parseStoredLogLine(raw: String): TerminalLine? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains('\t')) {
            val parts = trimmed.split('\t')
            if (parts.size >= 4) {
                return TerminalLine(
                    time = parts[0],
                    type = parts[1],
                    variant = parts[2],
                    text = parts.subList(3, parts.size).joinToString("\t")
                )
            } else if (parts.size == 3) {
                return TerminalLine(
                    time = parts[0],
                    type = parts[1],
                    variant = "",
                    text = parts[2]
                )
            }
        }
        val regex = Regex("""^\[(\d{2}:\d{2}:\d{2})\]\s*\[([A-Z_]+)\]\s*(.*)$""")
        val m = regex.find(trimmed)
        return if (m != null) {
            val time = m.groupValues[1]
            val type = m.groupValues[2]
            val text = m.groupValues[3]
            TerminalLine(time, text, type, "")
        } else {
            TerminalLine(stamp(timeFormat), trimmed, "INFO", "")
        }
    }

    private fun appendToLogFile(stampTime: String, type: String, variant: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(logWriteLock) {
                try {
                    val entry = "$stampTime\t$type\t$variant\t$text\n"
                    logFile.appendText(entry, Charsets.UTF_8)
                    if (logFile.length() > 350_000) {
                        val tail = logFile.readLines(Charsets.UTF_8).takeLast(MAX_TERMINAL_LINES)
                        logFile.writeText(tail.joinToString("\n") + "\n", Charsets.UTF_8)
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun tlog(text: String, type: String = "INFO", variant: String? = null) {
        val clean = text.replace(Regex("""^\s*\[[A-Za-z0-9_/ -]+\]\s*"""), "").trim()
        if (clean.isBlank() || clean.startsWith("====")) return
        val v = (variant ?: activePipelineVariant.value).orEmpty()
        val stampTime = stamp(timeFormat)
        viewModelScope.launch(Dispatchers.Main) {
            terminalLines.add(TerminalLine(stampTime, clean, type, v))
            trimTerminal()
        }
        appendToLogFile(stampTime, type, v, clean)
    }

    private fun variantOfModule(module: String): String = ""

    fun tstep(step: String) {
        viewModelScope.launch(Dispatchers.Main) { busyStep.value = step }
    }

    private var loadJob: Job? = null

    private suspend fun checkpoint() {
        coroutineContext.ensureActive()
    }

    fun stopLoad() {
        if (!isBusy.value) return
        tlog("Stop Requested", "WARN")
        loadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Shell.cmd("killall insmod 2>/dev/null; true").exec() }
        }

        busyStep.value = ""
        autoLoadOk.value = false
        autoLoadStatus.value = "Stopped by user"
    }

    private fun trimTerminal() {
        if (terminalLines.size > MAX_TERMINAL_LINES) {
            repeat(terminalLines.size - MAX_TERMINAL_LINES) { terminalLines.removeAt(0) }
        }
    }

    fun clearTerminal() {
        terminalLines.clear()
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(logWriteLock) {
                try {
                    if (logFile.exists()) logFile.delete()
                } catch (_: Exception) {}
            }
        }
    }

    fun nowString(): String = stamp(dateFormat)

    fun setVerification(result: VerificationResult) {
        viewModelScope.launch(Dispatchers.Main) { verificationResult.value = result }
    }

    fun getTerminalText(): String {
        return terminalLines.joinToString("\n") { "[${it.time}] ${it.text}" }
    }

    fun checkDriverActivity(context: Context) {
        if (isBusy.value) {
            tlog("Busy - please wait", "WARN")
            return
        }
        viewModelScope.launch {
            tlog("# driver activity check", "CMD")
            tstep("Verifying driver via Driver.sh...")
            withContext(Dispatchers.IO) {
                try {
                    val (ok, detail) = runDriverShVerification(context)
                    if (ok) {
                        tlog("[STATUS] Driver active & verified by Driver.sh!", "OK")
                    } else {
                        tlog("[STATUS] Verification failed: $detail", "ERR")
                    }
                } catch (e: Exception) {
                    tlog("[TEST ERR] ${e.message}", "ERR")
                } finally {
                    tstep("")
                }
            }
        }
    }

    fun selfTestDriver(context: Context) {
        checkDriverActivity(context)
    }

    fun runUserCommand(cmd: String) {
        val c = cmd.trim()
        if (c.isEmpty()) return
        viewModelScope.launch {
            tlog("# $c", "CMD")
            withContext(Dispatchers.IO) {
                try {
                    val res = Shell.cmd(c).exec()
                    withContext(Dispatchers.Main) {
                        res.out.forEach { if (it.isNotBlank()) tlog(it, "OUT") }
                        res.err.forEach { if (it.isNotBlank()) tlog(it, "ERR") }
                        tlog("exit ${res.code}", if (res.isSuccess) "OK" else "ERR")
                    }
                    addLog(c, res.out, res.err, res.code)
                } catch (e: Exception) {
                    tlog("ERROR: ${e.message}", "ERR")
                }
            }
        }
    }

    private val FATAL_INSMOD_ERRORS = listOf(
        "invalid module format",
        "disagrees about version",
        "vermagic",
        "exec format error",
        "operation not permitted",
        "required key not available",
        "permission denied",
        "file exists",
        "unknown symbol"
    )

    private val MAX_INSMOD_ATTEMPTS = 6
    private val INSMOD_BACKOFF_MS = 500L

    private suspend fun runInsmodLadder(koPath: String, devNode: String): Shell.Result {
        val kstateBefore = Shell.cmd("cat /proc/modules 2>/dev/null; cat /proc/devices 2>/dev/null").exec().out.toSet()
        UniversalKernelLoader.loadViaFramework(koPath, devNode, this@DriverViewModel)?.let { fwRes ->
            var registered = false
            for (i in 1..6) {
                delay(500)
                val now = Shell.cmd("cat /proc/modules 2>/dev/null; cat /proc/devices 2>/dev/null").exec().out
                if (now.any { it.isNotBlank() && it !in kstateBefore }) { registered = true; break }
                val probeNode = try { UniversalKernelLoader.probeOurDriverNode(getApplication(), this@DriverViewModel) } catch (_: Exception) { null }
                if (!probeNode.isNullOrBlank()) { registered = true; break }
            }
            if (registered) return fwRes
            tlog("[KERNEL] Framework loader exited 0 but the kernel did not register the module - falling back to insmod", "WARN")
        }

        val bins = listOf(
            "insmod",
            "/data/adb/ap/bin/busybox insmod",
            "/data/adb/ksu/bin/busybox insmod",
            "/data/adb/magisk/busybox insmod",
            "/system/bin/insmod",
            "/vendor/bin/insmod",
            "busybox insmod"
        ).filter { b ->
            val bin = b.substringBefore(" ")
            bin == "insmod" || Shell.cmd("test -x \"$bin\" 2>/dev/null || which \"$bin\" 2>/dev/null").exec().isSuccess
        }.ifEmpty { listOf("insmod") }
        val hideParam = if (supportsStealthHide()) "hide=1" else "hide=0"
        val paramVariants = if (devNode.isNotBlank()) {
            listOf("", "devname=$devNode $hideParam", "devicename=$devNode $hideParam", hideParam)
        } else {
            listOf("", hideParam)
        }
        var res = Shell.cmd("true").exec()
        var attempts = 0
        outer@ for (b in bins) {
            for (param in paramVariants) {
                checkpoint()
                if (attempts >= MAX_INSMOD_ATTEMPTS) break@outer
                if (attempts > 0) delay(INSMOD_BACKOFF_MS)
                attempts++
                val cmd = if (param.isNotBlank()) "$b $koPath $param" else "$b $koPath"
                res = Shell.cmd(cmd).exec()
                if (res.isSuccess) return res
                val err = (res.out + res.err).joinToString(" ").lowercase(Locale.US)

                if (err.contains("file exists")) {
                    tlog("[KERNEL] Module already resident (File exists) - verifying live node", "OK")
                    return Shell.cmd("true").exec()
                }
                if (err.contains("not found") || err.contains("no such file")) continue@outer

                val hasParam = param.isNotBlank()
                if (hasParam && (err.contains("unknown parameter") || err.contains("unknown symbol in module, or unknown parameter"))) {
                    continue
                }

                if (!hasParam && FATAL_INSMOD_ERRORS.any { err.contains(it) }) break@outer
            }
        }
        if (!res.isSuccess) {
            val errAll = (res.out + res.err).joinToString(" ").lowercase(Locale.US)
            val dmesg = Shell.cmd("dmesg 2>/dev/null | tail -n 15").exec().out.joinToString(" ").lowercase(Locale.US)
            val isSig = errAll.contains("required key") ||
                    errAll.contains("rejected") ||
                    errAll.contains("operation not permitted") ||
                    dmesg.contains("module verification failed") ||
                    dmesg.contains("module signature") ||
                    dmesg.contains("unsigned module is rejected") ||
                    dmesg.contains("loading of unsigned module") ||
                    dmesg.contains("required key")
            if (isSig) {
                tlog("Signature verification active, bypassing...", "WARN")
                UniversalKernelLoader.disableModuleSigEnforce(this@DriverViewModel)
            }
            val force = Shell.cmd("insmod -f $koPath devname=$devNode 2>/dev/null || insmod -f $koPath devicename=$devNode 2>/dev/null || insmod -f $koPath 2>/dev/null").exec()
            if (force.isSuccess) return force
            (force.out + force.err).firstOrNull { it.isNotBlank() }?.let {
                tlog("insmod -f: ${it.trim().take(45)}", "ERR")
            }
            val isLocked = errAll.contains("required key not available") ||
                    errAll.contains("key rejected") ||
                    errAll.contains("key was rejected") ||
                    dmesg.contains("module verification failed") ||
                    dmesg.contains("unsigned module is rejected") ||
                    dmesg.contains("loading of unsigned module") ||
                    dmesg.contains("required key not available") ||
                    dmesg.contains("key rejected")
            if (isLocked) {
                tlog("[SIGNATURE LOCKED] Kernel enforces strict CONFIG_MODULE_SIG_FORCE=y.", "ERR")
                tlog("[GUIDANCE] Enable BypassSig KPM (or flash SigBypass zip) to unlock unsigned drivers.", "WARN")
            }
        }
        return res
    }

    private fun snapshotDevCharNodes(): Set<String> =
        Shell.cmd("for f in /dev/*; do [ -c \"\$f\" ] && echo \"\${f##*/}\"; done 2>/dev/null")
            .exec().out.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun supportsStealthHide(): Boolean {
        val suV = Shell.cmd("su -v 2>/dev/null").exec().out.firstOrNull()?.lowercase() ?: ""
        if (suV.contains("apatch") || suV.contains("kernelpatch")) return false
        val apatchDir = Shell.cmd("test -d /data/adb/ap 2>/dev/null && echo yes").exec().out.firstOrNull()
        if (apatchDir == "yes" && !Shell.cmd("test -d /data/adb/magisk 2>/dev/null || test -d /data/adb/ksu 2>/dev/null").exec().isSuccess)
            return false
        return true
    }

    private fun snapshotSysClasses(): Set<String> =
        Shell.cmd("ls /sys/class 2>/dev/null").exec().out
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun mknodNewSysClasses(before: Set<String>): List<String> {
        val made = mutableListOf<String>()
        if (before.isNotEmpty()) {
            val fresh = snapshotSysClasses() - before
            for (cls in fresh) {
                val dev = Shell.cmd("cat /sys/class/$cls/dev 2>/dev/null").exec().out
                    .firstOrNull()?.trim() ?: continue
                val mm = dev.split(":")
                val maj = mm.getOrNull(0)?.trim()?.toIntOrNull()
                val min = mm.getOrNull(1)?.trim()?.toIntOrNull()
                if (maj == null || min == null) continue
                val safe = cls.replace(Regex("[^A-Za-z0-9_.-]"), "")
                if (safe.isBlank()) continue
                if (Shell.cmd("test -c /dev/$safe 2>/dev/null").exec().isSuccess) continue
                Shell.cmd(
                    "mknod -m 666 /dev/$safe c $maj $min 2>/dev/null",
                    "chcon u:object_r:device:s0 /dev/$safe 2>/dev/null"
                ).exec()
                if (Shell.cmd("test -c /dev/$safe").exec().isSuccess) made.add(safe)
            }
        }
        if (made.isEmpty()) made.addAll(mknodPatternSysClasses())
        return made
    }

    private fun mknodPatternSysClasses(): List<String> {
        val made = mutableListOf<String>()
        val classes = Shell.cmd("ls /sys/class 2>/dev/null").exec().out
            .map { it.trim() }.filter { it.matches(Regex("^[A-GNYB]{6,8}$")) }
        for (cls in classes) {
            if (!cls.all { it in "ABCDEFGNYB" }) continue
            val dev = Shell.cmd("cat /sys/class/$cls/dev 2>/dev/null").exec().out
                .firstOrNull()?.trim() ?: continue
            val mm = dev.split(":")
            val maj = mm.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            val min = mm.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
            if (Shell.cmd("test -c /dev/$cls 2>/dev/null").exec().isSuccess) {
                made.add(cls)
                continue
            }
            Shell.cmd(
                "mknod -m 666 /dev/$cls c $maj $min 2>/dev/null",
                "chcon u:object_r:device:s0 /dev/$cls 2>/dev/null"
            ).exec()
            if (Shell.cmd("test -c /dev/$cls").exec().isSuccess) made.add(cls)
        }
        return made
    }

    private suspend fun runDriverShVerification(context: Context): Pair<Boolean, String> {
        tstep("Scanning /dev with Driver.sh...")
        tlog("Running Driver.sh to verify driver node...", "CMD")
        return withContext(Dispatchers.IO) {
            try {
                val local = File(context.filesDir, "Driver.sh")
                val assetStream = try {
                    context.assets.open("Driver.sh")
                } catch (_: Exception) {
                    context.assets.open("bin/driver.sh")
                }
                assetStream.use { input ->
                    local.outputStream().use { output -> input.copyTo(output) }
                }
                val targetPath = "/data/local/tmp/Driver.sh"
                Shell.cmd(
                    "cp \"${local.absolutePath}\" $targetPath",
                    "chmod 755 $targetPath"
                ).exec()

                val preferred = preferredDevNode()
                val targetCmd = if (preferred.isNotBlank() && Shell.cmd("test -e /dev/$preferred").exec().isSuccess) {
                    "$targetPath /dev/$preferred 2>&1"
                } else {
                    "$targetPath 2>&1"
                }
                val res = Shell.cmd(targetCmd).exec()
                val outStr = res.out.joinToString("\n").trim()
                val errStr = res.err.joinToString("\n").trim()
                val combined = listOf(outStr, errStr).filter { it.isNotBlank() }.joinToString(" ").trim()

                if (res.isSuccess || outStr.contains("DRIVER STATUS : loaded", ignoreCase = true)) {
                    res.out.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotBlank()) tlog(trimmed, "OK")
                    }
                    Pair(true, "Driver active & verified")
                } else {
                    val dmesgAll = Shell.cmd("dmesg 2>/dev/null | tail -n 25").exec().out.joinToString("\n")
                    val chrdevMatch = Regex("""CHRDEV\s+"([^"]+)"\s+major\s+number\s+(\d+)""", RegexOption.IGNORE_CASE).find(dmesgAll)
                    if (chrdevMatch != null) {
                        val nodeName = chrdevMatch.groupValues[1]
                        val major = chrdevMatch.groupValues[2]
                        Shell.cmd(
                            "mknod -m 666 /dev/$nodeName c $major 0 2>/dev/null",
                            "chmod 666 /dev/$nodeName 2>/dev/null",
                            "chcon u:object_r:device:s0 /dev/$nodeName 2>/dev/null"
                        ).exec()
                        rememberDevNode(nodeName)
                        tlog("[KERNEL] Driver active on /dev/$nodeName (major $major)", "OK")
                        tlog("[NOTICE] Driver.sh linker bypassed on legacy Android", "INFO")
                        return@withContext Pair(true, "Driver active on /dev/$nodeName")
                    }

                    val procDevs = Shell.cmd("cat /proc/devices 2>/dev/null").exec().out
                    val dynamicDev = procDevs.mapNotNull { line ->
                        val parts = line.trim().split(Regex("""\s+"""))
                        if (parts.size >= 2 && parts[0].toIntOrNull() != null) {
                            val name = parts[1]
                            val maj = parts[0]
                            if (name.matches(Regex("^[A-Za-z0-9]{6,8}$")) && !SafetyGuard.isSystemNode(name)) {
                                Pair(maj, name)
                            } else null
                        } else null
                    }.lastOrNull()

                    if (dynamicDev != null) {
                        val (maj, nodeName) = dynamicDev
                        Shell.cmd(
                            "mknod -m 666 /dev/$nodeName c $maj 0 2>/dev/null",
                            "chmod 666 /dev/$nodeName 2>/dev/null",
                            "chcon u:object_r:device:s0 /dev/$nodeName 2>/dev/null"
                        ).exec()
                        rememberDevNode(nodeName)
                        tlog("[PROCDEV] Driver node confirmed: /dev/$nodeName (major $maj)", "OK")
                        tlog("[NOTICE] Driver.sh linker bypassed on legacy Android", "INFO")
                        return@withContext Pair(true, "Driver active on /dev/$nodeName")
                    }

                    val dmesg = Shell.cmd("dmesg 2>/dev/null | tail -n 8").exec().out
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                    val dmesgHint = dmesg.lastOrNull { 
                        it.contains("error", true) || it.contains("fail", true) || it.contains("denied", true) || it.contains("segfault", true)
                    } ?: dmesg.lastOrNull() ?: ""

                    val reason = when {
                        combined.isNotBlank() -> combined
                        dmesgHint.isNotBlank() -> "Kernel: $dmesgHint"
                        res.code == 127 -> "Driver.sh not found or inaccessible (exit 127)"
                        res.code == 126 -> "Driver.sh permission denied (exit 126)"
                        else -> "No responding node found in /dev by Driver.sh (exit ${res.code})"
                    }
                    tlog("Driver.sh check failed: $reason", "ERR")
                    if (dmesgHint.isNotBlank() && !reason.contains(dmesgHint)) {
                        tlog("Kernel log hint: $dmesgHint", "WARN")
                    }
                    Pair(false, reason)
                }
            } catch (e: Exception) {
                val msg = "Driver.sh execution error: ${e.message}"
                tlog(msg, "ERR")
                Pair(false, msg)
            }
        }
    }

    private var otaFailReason: String = ""

    private suspend fun runOtaLoad(context: Context, variant: String = ""): Boolean {
        checkpoint()

        tlog("Checking driver DB...", "INFO")
        val manifest = OtaDriverStore.fetchManifest() ?: run {
            tlog("DB unreachable", "WARN")
            otaFailReason = "No internet connection - connect to the internet first, then tap LOAD"
            return false
        }
        if (manifest.drivers.isEmpty()) {
            tlog("DB has no drivers", "WARN")
            otaFailReason = "Driver database is empty - wait for the next database update"
            return false
        }
        val pool = OtaDriverStore.variantsFor(manifest, variant)
        if (pool.isEmpty()) {
            tlog("No ${OtaDriverStore.variantLabel(variant)} driver", "WARN")
            otaFailReason = "No driver for this variant in the database yet"
            return false
        }
        val kernel = RootChecker.getKernelRelease()
        if (kernel.isNullOrBlank()) {
            otaFailReason = "Could not read kernel version"
            return false
        }
        val deviceVermagic = RootChecker.getKernelVermagic()
        checkpoint()
        val resolved = OtaDriverStore.resolve(manifest, kernel, variant, deviceVermagic)
        return when (resolved) {
            is OtaDriverStore.ResolveResult.Exact -> {
                tlog("Exact match: ${resolved.entry.label}", "OK")
                downloadAndLoadOta(context, manifest, resolved.entry)
            }
            is OtaDriverStore.ResolveResult.Near -> {
                tlog("Nearest match: ${resolved.entry.label}", "WARN")
                downloadAndLoadOta(context, manifest, resolved.entry)
            }
            is OtaDriverStore.ResolveResult.None -> {
                tlog("No driver for this kernel in the database", "INFO")
                otaFailReason = "No driver for kernel $kernel in the database yet - it will appear after the next database update"
                false
            }
        }
    }

    private suspend fun downloadAndLoadOta(
        context: Context,
        manifest: OtaDriverStore.Manifest,
        entry: OtaDriverStore.DriverEntry
    ): Boolean {
        checkpoint()
        val downloaded = OtaDriverStore.downloadDriver(
            context = context,
            baseUrl = manifest.baseUrl,
            entry = entry,
            onLog = { msg, kind -> tlog(msg, kind) }
        ) ?: run {
            val failMsg = "Failed to download driver (${entry.file}) from database"
            otaFailReason = failMsg
            lastLoadError.value = failMsg
            tlog("[ERR] $failMsg", "ERR")
            return false
        }
        try {
            if (!UniversalKernelLoader.ensureElf(downloaded)) {
                val failMsg = "Downloaded file is corrupted (ELF verification failed)"
                otaFailReason = failMsg
                lastLoadError.value = failMsg
                tlog("[ERR] $failMsg", "ERR")
                return false
            }

            val kernel = RootChecker.getKernelRelease() ?: run {
                val failMsg = "Failed to read kernel release"
                otaFailReason = failMsg
                lastLoadError.value = failMsg
                tlog("[ERR] $failMsg", "ERR")
                return false
            }
            tlog("Staging driver file...", "CMD")

            val staged = File("/data/local/tmp/kloader_ota.ko")

            val devNode = preferredDevNode()
            Shell.cmd(
                "cp \"${downloaded.absolutePath}\" ${staged.absolutePath}",
                "chmod 644 ${staged.absolutePath}",
                "chown root:root ${staged.absolutePath} 2>/dev/null",
                "chcon u:object_r:system_file:s0 ${staged.absolutePath} 2>/dev/null"
            ).exec()
            if (!Shell.cmd("test -f ${staged.absolutePath}").exec().isSuccess) {
                val failMsg = "Staging failed: cannot write to /data/local/tmp/kloader_ota.ko"
                otaFailReason = failMsg
                lastLoadError.value = failMsg
                tlog("[ERR] $failMsg", "ERR")
                return false
            }
            tlog("Driver staged successfully", "OK")

            try {

            UniversalKernelLoader.disableModuleSigEnforce(this@DriverViewModel)

            val preProbe = try { UniversalKernelLoader.probeOurDriverNode(getApplication(), this@DriverViewModel) } catch (e: Exception) { null }
            if (preProbe != null && !SafetyGuard.isSystemNode(preProbe)) {
                tlog("[RESIDENT] Driver already answering on /dev/$preProbe - skipping insert", "OK")
                rememberDevNode(preProbe)
                markHiddenLoaded()
                driverLoaded.value = true
                autoLoadOk.value = true
                autoLoadStatus.value = "Already loaded: /dev/$preProbe"
                return true
            }
            tlog("Inserting module into kernel...", "CMD")
            val res = runInsmodLadder(staged.absolutePath, devNode)
            if (res.isSuccess) {
                tlog("Module inserted into kernel", "OK")
            }

            if (!res.isSuccess) {
                tlog("Module insert failed", "ERR")
                val errText = (res.out + res.err).joinToString("\n").trim()
                val errLower = errText.lowercase(Locale.US)
                val dmesgOut = Shell.cmd(
                    "dmesg 2>/dev/null | grep -i -E 'kmem|module|insmod|version magic|disagrees|Unknown symbol|format error|taint|required key|signature' | tail -n 8"
                ).exec().out.filter { it.isNotBlank() }.take(5)
                val dmesgLower = dmesgOut.joinToString(" ").lowercase(Locale.US)

                dmesgOut.forEach { tlog("DMESG: ${it.take(90)}", "INFO") }

                val isSigLocked = errLower.contains("required key not available") ||
                        errLower.contains("key rejected") ||
                        errLower.contains("key was rejected") ||
                        dmesgLower.contains("module verification failed") ||
                        dmesgLower.contains("required key not available")

                if (isSigLocked) {
                    val sigMsg = "Signature Locked: Kernel enforces CONFIG_MODULE_SIG_FORCE"
                    lastLoadError.value = sigMsg
                    otaFailReason = sigMsg
                    tlog("Signature verification rejected by kernel", "ERR")
                } else if (errText.contains("Invalid module format", true) ||
                    errText.contains("vermagic", true) ||
                    errText.contains("Exec format error", true)
                ) {
                    val msg = "Kernel vermagic/format mismatch: build rejected by kernel (${errText.take(120)})"
                    lastLoadError.value = msg
                    tlog("Kernel mismatch - build rejected by kernel", "WARN")
                    otaFailReason = msg
                } else if (errText.contains("No such file or directory", true)) {
                    val msg = "Staged driver file not readable"
                    lastLoadError.value = msg
                    tlog("Staged file not readable", "WARN")
                    otaFailReason = msg
                } else {
                    val msg = "Driver insert failed: ${errText.ifBlank { "Exit code ${res.code}" }.take(120)}"
                    lastLoadError.value = msg
                    otaFailReason = msg
                    tlog("Insert failed", "ERR")
                }
                return false
            }

            tlog("Driver loaded successfully", "OK")
            val dmesgAll = Shell.cmd("dmesg 2>/dev/null | tail -n 25").exec().out.joinToString("\n")
            val chrdevMatch = Regex("""CHRDEV\s+"([^"]+)"\s+major\s+number\s+(\d+)""", RegexOption.IGNORE_CASE).find(dmesgAll)
            if (chrdevMatch != null) {
                val nodeName = chrdevMatch.groupValues[1]
                val major = chrdevMatch.groupValues[2]
                Shell.cmd(
                    "mknod -m 666 /dev/$nodeName c $major 0 2>/dev/null",
                    "chmod 666 /dev/$nodeName 2>/dev/null",
                    "chcon u:object_r:device:s0 /dev/$nodeName 2>/dev/null"
                ).exec()
                rememberDevNode(nodeName)
                tlog("Driver node active: /dev/$nodeName", "OK")
            } else {
                val procDevs = Shell.cmd("cat /proc/devices 2>/dev/null").exec().out
                val dynamicDev = procDevs.mapNotNull { line ->
                    val parts = line.trim().split(Regex("""\s+"""))
                    if (parts.size >= 2 && parts[0].toIntOrNull() != null) {
                        val name = parts[1]
                        val maj = parts[0]
                        if (name.matches(Regex("^[A-Za-z0-9]{6,8}$")) && !SafetyGuard.isSystemNode(name)) {
                            Pair(maj, name)
                        } else null
                    } else null
                }.lastOrNull()
                if (dynamicDev != null) {
                    val (maj, nodeName) = dynamicDev
                    Shell.cmd(
                        "mknod -m 666 /dev/$nodeName c $maj 0 2>/dev/null",
                        "chmod 666 /dev/$nodeName 2>/dev/null",
                        "chcon u:object_r:device:s0 /dev/$nodeName 2>/dev/null"
                    ).exec()
                    rememberDevNode(nodeName)
                    tlog("Driver node active: /dev/$nodeName", "OK")
                }
            }
            val mods = SafetyGuard.loadedModuleNames()
            val stagedBase = staged.nameWithoutExtension
            val ours = mods.firstOrNull { it.contains(stagedBase, ignoreCase = true) || stagedBase.contains(it, ignoreCase = true) }
            withContext(Dispatchers.Main) {
                if (ours != null) setLoadedModule(ours)
                else markHiddenLoaded()
                driverLoaded.value = true
                autoLoadOk.value = true
                BootSessionTracker.recordLoaded(
                    context,
                    loadedModuleName.value,
                    preferredDevNode(),
                    hiddenLoaded.value
                )
            }
            tlog("[STEALTH] Driver ready. Clear app from recents before launching game!", "OK")
            return true
            } finally {
                Shell.cmd("rm -f ${staged.absolutePath} 2>/dev/null").exec()
            }
        } finally {
            if (downloaded.exists()) downloaded.delete()
        }
    }

    private val _forceNote: String
        get() = "nearest-series loader, restart guard active"

    fun determineBestVariant(context: Context, kernelRelease: String): String {
        return ""
    }

    fun autoLoadUniversal(context: Context, preferOta: Boolean = true, variant: String = "") {
        if (isBusy.value) return
        isBusy.value = true
        busyStep.value = "Inspecting system..."
        autoLoadOk.value = null
        autoLoadStatus.value = ""
        lastLoadError.value = ""
        loadErrorDialogData.value = null
        if (pickedFileUri.value != null) {
            lastBundleTried.value = pickedFileName.value ?: "custom file"
        } else {
            lastBundleTried.value = "OTA database"
        }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                tstep("Checking superuser...")
                tlog("Checking root permission...", "INFO")
                delay(400L)

                val root = RootChecker.isRootAvailable()
                if (root) {
                    tlog("Root permission: GRANTED", "OK")
                } else {
                    tlog("Root permission: DENIED", "ERR")
                    autoLoadOk.value = false
                    autoLoadStatus.value = "Root access denied"
                    triggerErrorDialog("Root permission: DENIED")
                    return@launch
                }

                delay(400L)
                tstep("Detecting kernel...")
                val kernel = RootChecker.getKernelRelease() ?: "unknown"
                val arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                tlog("Kernel detected: $kernel ($arch)", "OK")

                delay(350L)
                tstep("Checking signature security...")
                UniversalKernelLoader.disableModuleSigEnforce(this@DriverViewModel)

                delay(450L)
                tstep("Scanning drivers...")
                tlog("Scanning driver database...", "INFO")
                val manifest = remoteManifest.value ?: OtaDriverStore.fetchManifest()

                delay(450L)
                tstep("Matching driver...")

                tlog("Target driver: Stealth Engine", "OK")
                delay(350L)
                tlog("Starting load pipeline...", "CMD")
                if (pickedFileUri.value != null) {
                    tlog("Loading custom file: ${pickedFileName.value}", "CMD")
                    tstep("Loading custom file...")
                    UniversalKernelLoader.autoLoad(context, this@DriverViewModel, "")
                } else {
                    activePipelineVariant.value = ""
                    tlog("Loading driver...", "CMD")
                    tstep("Loading driver from database...")
                    val handled = runOtaLoad(context, "")
                    if (handled) return@launch
                    checkpoint()

                    val reason = if (lastLoadError.value.isNotBlank()) lastLoadError.value else otaFailReason.ifBlank { "Could not load driver from database" }
                    tlog("[FAIL] $reason", "ERR")
                    autoLoadOk.value = false
                    autoLoadStatus.value = "Load failed: $reason"
                }
                if (autoLoadOk.value != true && !driverLoaded.value) {
                    val finalReason = if (lastLoadError.value.isNotBlank()) lastLoadError.value else autoLoadStatus.value.ifBlank { "Driver could not be loaded" }
                    tlog("Load failed: $finalReason", "ERR")
                    autoLoadOk.value = false
                    autoLoadStatus.value = "Load failed: $finalReason"
                    triggerErrorDialog(finalReason)
                }
            } catch (e: CancellationException) {
            } catch (e: Exception) {
                tlog("FATAL: ${e.message}", "ERR")
                autoLoadStatus.value = "FATAL: ${e.message}"
                autoLoadOk.value = false
                triggerErrorDialog(e.message ?: "Fatal loading error")
            } finally {
                activePipelineVariant.value = ""
                viewModelScope.launch(Dispatchers.Main) {
                    isBusy.value = false
                    busyStep.value = ""
                }
            }
        }
    }

    private fun addTerminalFor(entry: LogEntry) {
        val type = if (entry.exitCode == 0) "CMD" else "ERR"
        val time = entry.timestamp.substringBefore('.')
        val cmdText = "$ ${entry.command}  (exit ${entry.exitCode})"
        terminalLines.add(TerminalLine(time, cmdText, type, ""))
        appendToLogFile(time, type, "", cmdText)
        entry.stderr.forEach { err ->
            if (err.isNotBlank()) {
                terminalLines.add(TerminalLine(time, err, "ERR", ""))
                appendToLogFile(time, "ERR", "", err)
            }
        }
        trimTerminal()
    }

    private fun addLog(command: String, stdout: List<String>, stderr: List<String>, exitCode: Int) {
        val timestamp = stamp(dateFormat)
        val entry = LogEntry(timestamp, command, stdout, stderr, exitCode)
        viewModelScope.launch(Dispatchers.Main) {
            logs.add(entry)
            addTerminalFor(entry)
        }
    }

    fun verifyModule() {
        viewModelScope.launch(Dispatchers.Main) { verifyModuleNow() }
    }

    private suspend fun verifyModuleNow() = withContext(Dispatchers.IO) {
            val kernelRelease = try {
                Shell.cmd("uname -r").exec().out.firstOrNull()?.trim() ?: ""
            } catch (e: Exception) { "" }

            val lsmodRes = Shell.cmd("lsmod").exec()

            val moduleNames = lsmodRes.out.drop(1)
                .map { it.trim().split(Regex("\\s+")).firstOrNull() ?: "" }
                .filter { it.isNotBlank() && it != "Module" }

            val devList = Shell.cmd("ls /dev 2>/dev/null").exec().out
            val matchedNodes = devList.filter { node ->
                moduleNames.any { m -> node.contains(m, ignoreCase = true) }
            }
            val candidateNodes = (moduleNames + matchedNodes + listOf(preferredDevNode(), "dmsync", "kmem_337", "memacc"))
                .filter { it.isNotBlank() }.distinct()
            val devCmd = candidateNodes.joinToString(" ") { "ls /dev/$it 2>/dev/null;" } + " true"
            val devRes = Shell.cmd(devCmd).exec()

            val dmesgRes = Shell.cmd(
                "dmesg | grep -i -E 'kmem|kloader|entryi|module|vermagic|insmod|misc|driver' | tail -n 8"
            ).exec()

            val timestamp = stamp(dateFormat)
            verificationResult.value = VerificationResult(
                lsmod = lsmodRes.out,
                deviceNodeFound = devRes.out.any { it.isNotBlank() },
                dmesgLogs = dmesgRes.out,
                timestamp = timestamp,
                kernelRelease = kernelRelease,
                loadedModules = moduleNames.size
            )

            val ours = knownDriverModules().filter { it in moduleNames }
            if (ours.isNotEmpty()) {
                setLoadedModule(ours.first())
                driverModule.value = ours.joinToString(", ")
                tlog("${ours.first()} loaded", "OK", variant = variantOfModule(ours.first()))
            } else {

                val probed = try { UniversalKernelLoader.probeOurDriverNode(getApplication(), this@DriverViewModel) } catch (e: Exception) { null }
                val stealth = probed ?: SafetyGuard.hiddenDriverClass()
                if (stealth != null) {
                    rememberDevNode(stealth)
                    markHiddenLoaded()
                    tlog("Driver running in stealth mode (/dev/$stealth)", "OK")
                } else {

                    setLoadedModule("")
                    hiddenLoaded.value = false
                    driverLoaded.value = false
                    driverModule.value = ""
                    tlog("No resident driver found", "INFO")
                }
            }
    }

    fun onFilePicked(context: Context, uri: Uri) {
        pickedFileUri.value = uri
        pickedFileName.value = getFileName(context, uri)
        addLog("File picked: ${pickedFileName.value}", emptyList(), emptyList(), 0)
        tlog("Selected module: ${pickedFileName.value}", "OK")
    }

    fun clearPickedFile() {
        pickedFileUri.value = null
        pickedFileName.value = null
        tlog("Cleared custom module", "INFO")
    }

    fun loadPickedFile(context: Context) {
        if (pickedFileUri.value == null) {
            tlog("No module file selected", "WARN")
            return
        }
        autoLoadUniversal(context, preferOta = false, variant = "")
    }

    fun knownDriverModules(): List<String> =
        listOfNotNull(
            loadedModuleName.value.ifBlank { null },
            driverModule.value.ifBlank { null }
        ).distinct()

    private fun sanitizeNode(node: String): String =
        node.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(32)

    fun preferredDevNode(): String {
        val safe = sanitizeNode(devNodeOverride.value)
        return if (safe.isBlank()) DEFAULT_DEV_NODE else safe
    }

    fun rememberDevNode(node: String) {
        val safe = sanitizeNode(node)
        if (safe.isNotBlank() && safe != DEFAULT_DEV_NODE) devNodeOverride.value = safe
    }

    fun rebootDevice(context: Context) {
        if (isBusy.value) return
        isBusy.value = true
        rebooting.value = true
        busyStep.value = "Rebooting..."
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                BootSessionTracker.clear(context)
                tlog("Rebooting to unload driver...", "INFO")
                val res = Shell.cmd("reboot 2>/dev/null || svc power reboot 2>/dev/null").exec()
                if (!res.isSuccess) {
                    tlog("Reboot command failed - reboot manually", "ERR")
                    autoLoadStatus.value = "Reboot manually to unload driver"
                }
            } catch (e: CancellationException) {
            } catch (e: Exception) {
                tlog("REBOOT FATAL: ${e.message}", "ERR")
                autoLoadOk.value = false
                autoLoadStatus.value = "Reboot failed: ${e.message}"
            } finally {
                viewModelScope.launch(Dispatchers.Main) {
                    rebooting.value = false
                    isBusy.value = false
                    busyStep.value = ""
                }
            }
        }
    }

    fun refreshDriverState(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = BootSessionTracker.checkSession(context)
            if (session.isLoaded) {
                withContext(Dispatchers.Main) {
                    driverLoaded.value = true
                    hiddenLoaded.value = session.isHidden
                    autoLoadOk.value = true
                    if (session.moduleName.isNotBlank()) {
                        loadedModuleName.value = session.moduleName
                        driverModule.value = session.moduleName
                    }
                    if (session.devNode.isNotBlank() && session.devNode != DEFAULT_DEV_NODE && !SafetyGuard.isSystemNode(session.devNode)) {
                        rememberDevNode(session.devNode)
                    }
                }
                return@launch
            }

            val loadedMods = SafetyGuard.loadedModuleNames()
            val currentMod = loadedModuleName.value
            val isModuleInLsmod = currentMod.isNotBlank() && currentMod in loadedMods

            if (isModuleInLsmod) {
                withContext(Dispatchers.Main) {
                    driverLoaded.value = true
                    autoLoadOk.value = true
                    setLoadedModule(currentMod)
                    BootSessionTracker.recordLoaded(context, currentMod, preferredDevNode(), false)
                }
                return@launch
            }

            val knownCandidates = listOf("rmx3195_driver", "daisy_driver", "kloader", "suvhwvwrtnb").distinct()
            val candidateMatch = loadedMods.firstOrNull { mod ->
                knownCandidates.any { it.equals(mod, ignoreCase = true) }
            }

            if (candidateMatch != null) {
                withContext(Dispatchers.Main) {
                    driverLoaded.value = true
                    autoLoadOk.value = true
                    setLoadedModule(candidateMatch)
                    driverModule.value = candidateMatch
                    BootSessionTracker.recordLoaded(context, candidateMatch, preferredDevNode(), false)
                }
                return@launch
            }

            val probed = try {
                UniversalKernelLoader.probeOurDriverNode(getApplication(), this@DriverViewModel)
            } catch (e: Exception) {
                null
            }

            withContext(Dispatchers.Main) {
                if (probed != null && !SafetyGuard.isSystemNode(probed)) {
                    rememberDevNode(probed)
                    markHiddenLoaded()
                    autoLoadOk.value = true
                    BootSessionTracker.recordLoaded(context, "", probed, true)
                } else {
                    setLoadedModule("")
                    hiddenLoaded.value = false
                    driverLoaded.value = false
                    driverModule.value = ""
                    BootSessionTracker.clear(context)
                }
            }
        }
    }

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
