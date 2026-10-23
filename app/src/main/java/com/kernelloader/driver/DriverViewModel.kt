package com.kernelloader.driver

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kernelloader.BuildConfig
import com.kernelloader.driver.OtaDriverStore
import com.kernelloader.mem.MemDirect
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

object EmbeddedDrivers {

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
                "QX" -> "QX driver for $version (QX ABI - ioctl 0x801/0x802/0x803 + 0x804/0x805 handshake)"
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

    fun readVermagic(context: Context, assetPath: String): String {
        return try {
            val bytes = context.resources.assets.open(assetPath).use { input ->
                val buf = ByteArray(1 shl 20)
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

    val DEFAULT_DEV_NODE = "wanbai"
    var devNodeOverride = mutableStateOf("")

    var pickedFileUri = mutableStateOf<Uri?>(null)
    var pickedFileName = mutableStateOf<String?>(null)
    val logs = mutableStateListOf<LogEntry>()
    var verificationResult = mutableStateOf<VerificationResult?>(null)

    val terminalLines = mutableStateListOf<TerminalLine>()
    var isBusy = mutableStateOf(false)
    var busyStep = mutableStateOf("")

    var isBootPatching = mutableStateOf(false)
    var bootPatchResult = mutableStateOf<Boolean?>(null)
    var bootPatchStatus = mutableStateOf("")
    var bootBackupPath = mutableStateOf("")

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

    fun setLoadedModule(name: String) {
        if (loadedModuleName.value != name) loadedModuleName.value = name
        driverLoaded.value = name.isNotBlank()
        if (name.isNotBlank()) hiddenLoaded.value = false
    }

    fun markHiddenLoaded() {
        loadedModuleName.value = ""
        driverModule.value = ""
        driverLoaded.value = true
        hiddenLoaded.value = true
    }

    var hiddenLoaded = mutableStateOf(false)

    var isDriverRevoked = mutableStateOf(false)
    var revokedReason = mutableStateOf("")
    var isDriverUpgraded = mutableStateOf(false)
    var upgradedDriverFile = mutableStateOf("")

    private var revocationWatcherJob: Job? = null
    private var upgradeNotified = false

    var appUpdate = mutableStateOf<AppUpdateChecker.UpdateInfo?>(null)
    var updateStatus = mutableStateOf("IDLE")
    var updateProgress = mutableStateOf(-1)
    var updateMsg = mutableStateOf("")

    private var updateCheckJob: Job? = null
    private var downloadJob: Job? = null

    fun startPeriodicRevocationWatcher() {
        revocationWatcherJob?.cancel()
        revocationWatcherJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30_000L)
                checkRevocationInternal(silent = true)
            }
        }
    }

    suspend fun checkRevocationInternal(silent: Boolean = false) {
        val isLoaded = driverLoaded.value || DriverRevocationManager.isDriverResident()
        if (!isLoaded) return

        val manifest = remoteManifest.value ?: OtaDriverStore.fetchManifest() ?: return
        val verdict = DriverRevocationManager.evaluate(getApplication(), manifest, isLoaded)
        when (verdict) {
            is DriverRevocationManager.RevocationVerdict.Revoked -> {
                withContext(Dispatchers.Main) {
                    isDriverRevoked.value = true
                    revokedReason.value = verdict.reason
                    autoLoadOk.value = false
                    autoLoadStatus.value = "Access revoked - driver removed from DB"
                }
                DriverRevocationManager.enforceRevocation(getApplication(), verdict.reason) { msg, kind ->
                    tlog(msg, kind)
                }
            }
            is DriverRevocationManager.RevocationVerdict.Upgraded -> {
                withContext(Dispatchers.Main) {
                    isDriverUpgraded.value = true
                    upgradedDriverFile.value = verdict.newEntry.file
                }
                if (!upgradeNotified) {
                    upgradeNotified = true
                    tlog("[DB] Upgraded driver available (${verdict.newEntry.file}) - current session active, loads on next restart", "INFO")
                }
            }
            is DriverRevocationManager.RevocationVerdict.Valid -> {
                withContext(Dispatchers.Main) {
                    if (isDriverRevoked.value) {
                        isDriverRevoked.value = false
                        revokedReason.value = ""
                    }
                }
            }
            else -> {}
        }
    }

    fun checkRevocationOnResume(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            checkRevocationInternal(silent = true)
        }
    }

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
            if (m != null) {
                checkRevocationInternal(silent = true)
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
            isDriverRevoked.value = DriverRevocationManager.isRevoked(application)
            revokedReason.value = DriverRevocationManager.getRevokedReason(application)
            if (logFile.exists() && logFile.length() > 0) {
                val rawLines = logFile.readLines(Charsets.UTF_8).takeLast(MAX_TERMINAL_LINES)
                val parsed = rawLines.mapNotNull { parseStoredLogLine(it) }
                if (parsed.isNotEmpty()) {
                    terminalLines.addAll(parsed)
                }
            }
        } catch (_: Exception) {}
        startPeriodicUpdateCheck()
        startPeriodicRevocationWatcher()
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

    private fun variantOfModule(module: String): String = when {
        module.contains("qx", true) -> OtaDriverStore.QX
        module.isNotBlank() -> OtaDriverStore.RT
        else -> ""
    }

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
        isBusy.value = false
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

    fun memTest() {
        viewModelScope.launch {
            tlog("# memtest", "CMD")
            withContext(Dispatchers.IO) {
                try {
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

    fun checkDriverActivity(context: Context) {
        if (isBusy.value) {
            tlog("Busy - please wait", "WARN")
            return
        }
        viewModelScope.launch {
            tlog("# driver activity check", "CMD")
            tstep("Checking driver & R/W...")
            withContext(Dispatchers.IO) {
                try {
                    val devNode = preferredDevNode()
                    val nodePath = "/dev/$devNode"
                    val nodeExists = Shell.cmd("test -e $nodePath").exec().isSuccess

                    if (!nodeExists) {
                        tlog("[DEV] $nodePath missing!", "ERR")
                        tlog("[FIX] Load a driver first", "WARN")
                        return@withContext
                    }

                    val permOut = Shell.cmd("ls -l $nodePath 2>/dev/null").exec().out.firstOrNull() ?: ""
                    val enf = Shell.cmd("getenforce 2>/dev/null").exec().out.firstOrNull()?.trim() ?: "unknown"
                    val is666 = permOut.contains("rw-rw-rw-") || permOut.contains("crw-rw-rw-")
                    val permStatus = if (is666) "0666" else "restricted"
                    tlog("[DEV] $nodePath · $permStatus · $enf", if (is666) "OK" else "WARN")

                    val lsof = Shell.cmd("lsof $nodePath 2>/dev/null | tail -n +2").exec().out
                    val clients = lsof.mapNotNull { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2) "${parts[0]}(PID ${parts[1]})" else null
                    }.distinct()
                    if (clients.isNotEmpty()) {
                        tlog("[CLIENT] Active: ${clients.take(2).joinToString(", ")}", "INFO")
                    } else {
                        tlog("[CLIENT] Idle · Ready for apps", "INFO")
                    }

                    val local = File(context.filesDir, "dmtest")

                    context.assets.open("dmtest").use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    }
                    val testRun = Shell.cmd(
                        "cp \"${local.absolutePath}\" /data/local/tmp/dmtest 2>/dev/null",
                        "chmod 755 /data/local/tmp/dmtest 2>/dev/null",
                        "/data/local/tmp/dmtest /data/local/tmp/dmtest_report.txt 2>&1"
                    ).exec()

                    val outLines = testRun.out

                    val probeLines = outLines.filter { it.contains("probe:") || it.contains("open FAIL") || it.contains("verdict: our driver") }
                    probeLines.forEach { line ->
                        val clean = line.trim()
                        if (clean.contains("open FAIL") || clean.contains("NOT ours") || clean.contains("ABSENT")) {
                            tlog("[PROBE FAIL] $clean", "ERR")
                        } else if (clean.contains("open OK") || clean.contains("OUR DRIVER")) {
                            tlog("[PROBE] $clean", "OK")
                        }
                    }

                    val baseLines = outLines.filter { it.contains("0x803") }
                    val failedBase = baseLines.filter { it.contains("FAIL") || it.contains("rc=-1") }
                    val passBase = baseLines.filter { it.contains("PASS") }

                    if (failedBase.isNotEmpty()) {
                        failedBase.forEach { tlog("[0x803 FAIL] " + it.trim(), "ERR") }
                    } else if (passBase.isNotEmpty()) {
                        val sample = passBase.firstOrNull { it.contains("libc.so") } ?: passBase.first()
                        tlog("[0x803] " + sample.trim(), "OK")
                    } else {
                        tlog("[0x803] ModBase: NOT RUN", "WARN")
                    }

                    val readLines = outLines.filter { it.contains("read own marker") || (it.contains("0x801") && it.contains("read")) }
                    val failedRead = readLines.filter { it.contains("FAIL") }
                    if (failedRead.isNotEmpty()) {
                        failedRead.forEach { tlog("[0x801 FAIL] " + it.trim(), "ERR") }
                    } else if (readLines.any { it.contains("PASS") }) {
                        val passLine = readLines.first { it.contains("PASS") }.trim()
                        tlog("[0x801] " + passLine, "OK")
                    } else {
                        val hasAny801 = outLines.any { it.contains("0x801") && it.contains("PASS") }
                        tlog("[0x801] MemRead: " + (if (hasAny801) "PASS" else "FAIL"), if (hasAny801) "OK" else "ERR")
                    }

                    val writeLines = outLines.filter { it.contains("write own target") || (it.contains("0x802") && it.contains("write")) }
                    val failedWrite = writeLines.filter { it.contains("FAIL") }
                    if (failedWrite.isNotEmpty()) {
                        failedWrite.forEach { tlog("[0x802 FAIL] " + it.trim(), "ERR") }
                    } else if (writeLines.any { it.contains("PASS") }) {
                        val passLine = writeLines.first { it.contains("PASS") }.trim()
                        tlog("[0x802] " + passLine, "OK")
                    } else {
                        val hasAny802 = outLines.any { it.contains("0x802") && it.contains("PASS") }
                        tlog("[0x802] MemWrite: " + (if (hasAny802) "PASS" else "FAIL"), if (hasAny802) "OK" else "ERR")
                    }

                    val xprocLines = outLines.filter { it.contains("xproc") || it.contains("child pid") }
                    val failedXproc = xprocLines.filter { it.contains("FAIL") }
                    if (failedXproc.isNotEmpty()) {
                        failedXproc.forEach { tlog("[CHILD FAIL] " + it.trim(), "ERR") }
                    } else if (xprocLines.any { it.contains("PASS") }) {
                        val childPassCount = xprocLines.count { it.contains("PASS") }
                        tlog("[CHILD] Cross-Proc R/W: PASS ($childPassCount/4 cross-page verified)", "OK")
                    }

                    val summaryLine = outLines.firstOrNull { it.contains("SUMMARY:") }
                    if (summaryLine != null) {
                        val isAllOk = summaryLine.contains("FAIL 0")
                        tlog(summaryLine.trim(), if (isAllOk) "OK" else "ERR")
                    }

                    val allFailedLines = outLines.filter {
                        (it.contains("FAIL") && !it.contains("FAIL 0")) ||
                            it.contains("NOT ours")
                    }

                    if (allFailedLines.isEmpty() && outLines.any { it.contains("PASS") }) {
                        tlog("[STATUS] Driver 100% active & operational!", "OK")
                    } else {
                        tlog("--- ROOT CAUSE & FIX GUIDE ---", "CMD")
                        val errText = allFailedLines.joinToString(" ").lowercase(Locale.US)

                        when {
                            errText.contains("errno=13") || errText.contains("permission denied") -> {
                                tlog("[CAUSE] Error 13: Permission Denied (SELinux blocked)", "ERR")
                                tlog("[FIX 1] Inject live policy: supolicy --live 'allow untrusted_app * chr_file *'", "FIX")
                                tlog("[FIX 2] Set node context: chcon u:object_r:device:s0 /dev/${preferredDevNode()}", "FIX")
                            }
                            errText.contains("errno=25") || errText.contains("inappropriate ioctl") || errText.contains("not ours") -> {
                                tlog("[CAUSE] Error 25: ENOTTY (Driver ABI Mismatch)", "ERR")
                                tlog("[FIX 1] Driver is loaded as wrong variant (RT vs QX)", "FIX")
                                tlog("[FIX 2] Use FORCE button on Home screen to switch RT <-> QX", "FIX")
                            }
                            errText.contains("errno=2") || errText.contains("no such file") || errText.contains("no candidate") -> {
                                tlog("[CAUSE] Error 2: ENOENT (Device node missing)", "ERR")
                                tlog("[FIX 1] Tap LOAD button to insmod and recreate device node", "FIX")
                                tlog("[FIX 2] Run: mknod /dev/${preferredDevNode()} c <major> <minor>", "FIX")
                            }
                            errText.contains("errno=14") || errText.contains("bad address") -> {
                                tlog("[CAUSE] Error 14: EFAULT (Bad memory address)", "ERR")
                                tlog("[FIX 1] Target memory pointer is unmapped in client process", "FIX")
                                tlog("[FIX 2] Re-check target process memory map via /proc/<pid>/maps", "FIX")
                            }
                            errText.contains("errno=1") || errText.contains("operation not permitted") -> {
                                tlog("[CAUSE] Error 1: EPERM (Page Write Protected)", "ERR")
                                tlog("[FIX 1] Kernel CR0.WP or page table protection prevented write", "FIX")
                                tlog("[FIX 2] Driver needs remap_pfn_range / write-permission patch", "FIX")
                            }
                            else -> {
                                val firstErr = allFailedLines.firstOrNull()?.trim() ?: "Unknown test failure"
                                tlog("[CAUSE] $firstErr", "ERR")
                                tlog("[FIX] Check /sdcard/Download/dmtest_report.txt for full log", "FIX")
                            }
                        }
                    }

                    Shell.cmd(
                        "cp /data/local/tmp/dmtest_report.txt /sdcard/Download/dmtest_report.txt 2>/dev/null" +
                                " || cp /data/local/tmp/dmtest_report.txt /storage/emulated/0/Download/dmtest_report.txt 2>/dev/null"
                    ).exec()
                    tlog("[REPORT] /sdcard/Download/dmtest_report.txt", "INFO")
                } catch (e: Exception) {
                    tlog("[TEST ERR] ${e.message?.take(36)}", "ERR")
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

    private val LOCAL_KO_CANDIDATES = listOf(
        "/data/adb/kloder/driver.ko",
        "/data/local/tmp/driver.ko",
        "/data/local/tmp/kmem.ko",
        "/data/local/tmp/kmem_337.ko",
        "/data/local/tmp/kmem_337_qx.ko",
        "/data/local/tmp/memacc.ko",
        "/data/local/tmp/memacc_qx.ko",
        "/data/local/tmp/wanbai.ko",
        "/data/adb/kloder/driver_rt.ko",
        "/data/adb/kloder/driver_qx.ko"
    )

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

    private val MAX_INSMOD_ATTEMPTS = 4
    private val INSMOD_BACKOFF_MS = 500L

    private suspend fun runInsmodLadder(koPath: String, devNode: String): Shell.Result {
        UniversalKernelLoader.loadViaFramework(koPath, devNode, this@DriverViewModel)?.let { return it }

        val bins = listOf(
            "insmod", "/system/bin/insmod", "/vendor/bin/insmod",
            "/data/adb/magisk/busybox insmod", "busybox insmod"
        )
        val paramVariants = listOf(
            "devname=$devNode",
            "devicename=$devNode",
            ""
        )
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
            val dmesg = Shell.cmd("dmesg 2>/dev/null | tail -n 10").exec().out.joinToString(" ").lowercase(Locale.US)
            val isSig = errAll.contains("required key") ||
                    errAll.contains("rejected") ||
                    errAll.contains("operation not permitted") ||
                    dmesg.contains("module verification failed") ||
                    dmesg.contains("module signature")
            if (isSig) {
                tlog("Signature verification active, bypassing...", "WARN")
                UniversalKernelLoader.disableModuleSigEnforce(this@DriverViewModel)
                UniversalKernelLoader.stripModuleSig(File(koPath), this@DriverViewModel)
            }
            val force = Shell.cmd("insmod -f $koPath devname=$devNode 2>/dev/null || insmod -f $koPath devicename=$devNode 2>/dev/null || insmod -f $koPath 2>/dev/null").exec()
            if (force.isSuccess) return force
            (force.out + force.err).firstOrNull { it.isNotBlank() }?.let {
                tlog("insmod -f: ${it.trim().take(35)}", "ERR")
            }
            val isLocked = errAll.contains("required key not available") ||
                    errAll.contains("key rejected") ||
                    errAll.contains("key was rejected") ||
                    dmesg.contains("module verification failed") ||
                    dmesg.contains("required key not available") ||
                    dmesg.contains("key rejected")
            if (isLocked) {
                tlog("[SIGNATURE LOCKED] Kernel enforces strict CONFIG_MODULE_SIG_FORCE=y.", "ERR")
                tlog("[GUIDANCE] This device requires a patched boot image via official APatch / KernelSU or a custom kernel to allow unsigned modules.", "WARN")
            }
        }
        return res
    }

    private suspend fun prepareAndVerifyDeviceNode(devNode: String): Boolean {
        tstep("Preparing device node...")
        tlog("Configuring SELinux policy...", "INFO")
        Shell.cmd(
            "magiskpolicy --live 'allow untrusted_app * chr_file *' 2>/dev/null || supolicy --live 'allow untrusted_app * chr_file *' 2>/dev/null"
        ).exec()
        var foundNode: String? = null
        for (i in 1..10) {
            val nodes = Shell.cmd("ls /dev 2>/dev/null").exec().out.map { it.trim() }
            if (devNode in nodes) {
                foundNode = devNode
                break
            }
            val candidates = listOf("wanbai", "dmsync", "dmsync_qx", "kmem_337", "kmem_337_qx", "entryi", "memacc", "memacc_qx", "kloaderctl", "daisyctl")
            val hit = candidates.firstOrNull { it in nodes }
            if (hit != null) {
                foundNode = hit
                break
            }
            val mods = SafetyGuard.loadedModuleNames()
            val modHit = nodes.firstOrNull { d -> mods.any { m -> d.contains(m, ignoreCase = true) } }
            if (modHit != null) {
                foundNode = modHit
                break
            }
            delay(200)
        }

        if (foundNode == null) {
            val sysDevs = Shell.cmd(
                "for f in /sys/class/dmsync*/dev /sys/class/dmsync*/*/dev /sys/class/kmem*/dev /sys/class/kmem*/*/dev /sys/class/memacc*/dev; do " +
                        "if [ -f \"\$f\" ]; then cat \"\$f\"; echo \" \${f}\"; break; fi; done 2>/dev/null"
            ).exec().out.firstOrNull()?.trim()

            if (!sysDevs.isNullOrBlank()) {
                val parts = sysDevs.split(Regex("\\s+"))
                val majMin = parts.firstOrNull()?.split(":")
                if (majMin != null && majMin.size == 2) {
                    val major = majMin[0].trim()
                    val minor = majMin[1].trim()
                    val targetName = devNode.ifBlank { "wanbai" }
                    Shell.cmd(
                        "mknod /dev/$targetName c $major $minor 2>/dev/null",
                        "chmod 0666 /dev/$targetName 2>/dev/null",
                        "chcon u:object_r:device:s0 /dev/$targetName 2>/dev/null"
                    ).exec()
                    foundNode = targetName
                    tlog("[SYSFS] Created /dev/$targetName from sysfs ($major:$minor)", "FIX")
                }
            }
        }

        if (foundNode != null) {
            Shell.cmd(
                "chmod 0666 /dev/$foundNode",
                "chcon u:object_r:device:s0 /dev/$foundNode 2>/dev/null"
            ).exec()

            rememberDevNode(foundNode)
            tlog("Node /dev/$foundNode active (0666)", "OK")
            val rwOk = Shell.cmd("test -r /dev/$foundNode && test -w /dev/$foundNode").exec().isSuccess
            if (rwOk) {
                tlog("/dev/$foundNode verified (R/W OK)", "OK")
                tlog("Driver ready for use", "OK")
            }
            return true
        }

        val loaded = SafetyGuard.loadedModuleNames()
        val hit = knownDriverModules().filter { it in loaded }
        if (hit.isNotEmpty()) {
            tlog("[MODULE] ${hit.first()} loaded in kernel (node pending)", "WARN")
            return true
        }
        tlog("[DEVNODE ERR] Node /dev/$devNode missing", "ERR")
        return false
    }

    private fun firstLocalKo(variant: String = ""): File? {
        val explicit = when (variant.lowercase(Locale.US)) {
            OtaDriverStore.RT -> "/data/adb/kloder/driver_rt.ko"
            OtaDriverStore.QX -> "/data/adb/kloder/driver_qx.ko"
            else -> null
        }
        if (explicit != null) {
            return if (Shell.cmd("test -f '$explicit'").exec().isSuccess) File(explicit) else null
        }
        val staticHit = LOCAL_KO_CANDIDATES.firstOrNull { Shell.cmd("test -f '$it'").exec().isSuccess }
        if (staticHit != null) return File(staticHit)

        val tmpKos = Shell.cmd("ls /data/local/tmp/*.ko 2>/dev/null").exec().out
            .map { it.trim() }
            .filter { it.endsWith(".ko") && it != "/data/local/tmp/kloader_ota.ko" && it != "/data/local/tmp/kloader_local.ko" && it != "/data/local/tmp/kmem.ko" }
        return tmpKos.firstOrNull()?.let { File(it) }
    }

    private fun rmmodKnownDrivers() {
        val live = try { SafetyGuard.loadedModuleNames() } catch (e: Exception) { emptySet() }
        val stale = knownDriverModules().filter { it in live }
        if (stale.isEmpty()) return
        tlog("[CLEAN] Unloading stale module(s): ${stale.joinToString(", ")} (rmmod)", "WARN")
        Shell.cmd(
            *stale.map { "rmmod $it 2>/dev/null" }.toTypedArray(),
            "sleep 0.4"
        ).exec()
    }

    private suspend fun loadStagedKo(context: Context, localKo: File): Boolean {
        tlog("Local driver found: ${localKo.name}", "INFO")
        val devNode = preferredDevNode()
        val staged = File("/data/local/tmp/kloader_local.ko")
        tlog("Staging local driver...", "CMD")
        Shell.cmd(
            "cp \"${localKo.absolutePath}\" ${staged.absolutePath}",
            "chmod 644 ${staged.absolutePath}",
            "chown root:root ${staged.absolutePath} 2>/dev/null",
            "chcon u:object_r:system_file:s0 ${staged.absolutePath} 2>/dev/null"
        ).exec()
        if (!Shell.cmd("test -f ${staged.absolutePath}").exec().isSuccess) {
            tlog("Staging failed", "ERR")
            return false
        }
        tlog("Driver staged successfully", "OK")
        rmmodKnownDrivers()
        return try {
            UniversalKernelLoader.disableModuleSigEnforce(this@DriverViewModel)
            UniversalKernelLoader.stripModuleSig(staged, this@DriverViewModel)
            tlog("Inserting module into kernel...", "CMD")
            val res = runInsmodLadder(staged.absolutePath, devNode)
            if (!res.isSuccess) {
                (res.out + res.err).firstOrNull { it.isNotBlank() }?.let {
                    tlog("Error: ${it.trim().take(35)}", "ERR")
                    lastLoadError.value = it.trim().take(300)
                }
                return false
            }
            tlog("Module inserted into kernel", "OK")
            verifyModuleNow()
            val nodeOk = prepareAndVerifyDeviceNode(devNode)
            if (!nodeOk) {
                withContext(Dispatchers.Main) {
                    autoLoadOk.value = false
                    autoLoadStatus.value = "/dev/$devNode missing"
                    tstep("")
                }
                return false
            }
            val mods = SafetyGuard.loadedModuleNames()
            val ours = knownDriverModules().filter { it in mods }
            withContext(Dispatchers.Main) {
                if (ours.isNotEmpty()) setLoadedModule(ours.first())
                else markHiddenLoaded()
            }
            val kRelease = RootChecker.getKernelRelease() ?: ""
            DriverRevocationManager.recordLoadedDriver(
                context = context,
                entry = null,
                kernelRelease = kRelease,
                variant = activePipelineVariant.value,
                isOta = false
            )
            try { DriverWatchdogService.start(context) } catch (_: Exception) {}
            true
        } finally {
            Shell.cmd("rm -f ${staged.absolutePath} 2>/dev/null").exec()
        }
    }

    private suspend fun runOtaLoad(context: Context, variant: String = ""): Boolean {
        checkpoint()
        val localKo = firstLocalKo(variant)
        if (localKo != null) {
            if (loadStagedKo(context, localKo)) return true
            tlog("Local driver failed - trying DB", "WARN")
        }
        checkpoint()
        tlog("Checking driver DB...", "INFO")
        val manifest = OtaDriverStore.fetchManifest() ?: run {
            tlog("DB unavailable", "WARN")
            return false
        }
        if (manifest.drivers.isEmpty()) {
            tlog("DB has no drivers", "WARN")
            return false
        }
        val pool = OtaDriverStore.variantsFor(manifest, variant)
        if (pool.isEmpty()) {
            tlog("No ${OtaDriverStore.variantLabel(variant)} driver", "WARN")
            return false
        }
        val kernel = RootChecker.getKernelRelease() ?: return false
        checkpoint()
        val resolved = OtaDriverStore.resolve(manifest, kernel, variant)
        return when (resolved) {
            is OtaDriverStore.ResolveResult.Exact -> {
                tlog("Exact match: $kernel", "OK")
                downloadAndLoadOta(context, manifest, resolved.entry)
            }
            is OtaDriverStore.ResolveResult.Near -> {
                tlog("Nearest match: ${resolved.entry.version}", "WARN")
                downloadAndLoadOta(context, manifest, resolved.entry)
            }
            is OtaDriverStore.ResolveResult.None -> {
                tlog("No driver for this kernel", "INFO")
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
        ) ?: return false
        try {
            if (!UniversalKernelLoader.ensureElf(downloaded)) {
                tlog("[ERR] Invalid downloaded .ko file (ELF check failed)", "ERR")
                return false
            }

            val kernel = RootChecker.getKernelRelease() ?: return false
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
                tlog("Staging failed", "ERR")
                return false
            }
            tlog("Driver staged successfully", "OK")

            rmmodKnownDrivers()
            try {

            UniversalKernelLoader.disableModuleSigEnforce(this@DriverViewModel)
            UniversalKernelLoader.stripModuleSig(staged, this@DriverViewModel)

            tlog("Inserting module into kernel...", "CMD")
            val res = runInsmodLadder(staged.absolutePath, devNode)
            if (res.isSuccess) {
                tlog("Module inserted into kernel", "OK")
            }

            if (!res.isSuccess) {
                tlog("Module insert failed", "ERR")
                val errText = (res.out + res.err).joinToString("\n").trim()
                if (errText.isNotBlank()) lastLoadError.value = errText.take(300)
                (res.out + res.err).firstOrNull { it.isNotBlank() }?.let {
                    tlog("Error: ${it.trim().take(35)}", "ERR")
                }

                Shell.cmd(
                    "dmesg 2>/dev/null | grep -i -E 'kmem|module|insmod|version magic|disagrees|Unknown symbol|format error|taint' | tail -n 8"
                ).exec().out.filter { it.isNotBlank() }.take(5)
                    .forEach { tlog("DMESG: ${it.take(35)}", "INFO") }

                if (errText.contains("Invalid module format", true) ||
                    errText.contains("vermagic", true) ||
                    errText.contains("Exec format error", true)
                ) {
                    tlog("Kernel mismatch", "WARN")
                } else if (errText.contains("No such file or directory", true)) {
                    tlog("Staged file not readable", "WARN")
                }
                tlog("Need custom loader - tap Support", "FIX")
                return false
            }

            tlog("Driver loaded successfully", "OK")
            verifyModuleNow()
            val nodeOk = prepareAndVerifyDeviceNode(devNode)
            if (!nodeOk) {
                withContext(Dispatchers.Main) {
                    autoLoadOk.value = false
                    autoLoadStatus.value = "/dev/$devNode missing"
                    tstep("")
                }
                return false
            }
            val mods = SafetyGuard.loadedModuleNames()
            val ours = knownDriverModules().filter { it in mods }
            withContext(Dispatchers.Main) {
                if (ours.isNotEmpty()) setLoadedModule(ours.first())
                else markHiddenLoaded()
            }
            DriverRevocationManager.recordLoadedDriver(
                context = context,
                entry = entry,
                kernelRelease = kernel,
                variant = entry.variant,
                isOta = true
            )
            upgradeNotified = false
            try { DriverWatchdogService.start(context) } catch (_: Exception) {}
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
        val manifest = remoteManifest.value
        val short = RootChecker.kernelShortVersion(kernelRelease)

        val rtOta = manifest?.let { OtaDriverStore.exactFor(it, kernelRelease, OtaDriverStore.RT) }
        val qxOta = manifest?.let { OtaDriverStore.exactFor(it, kernelRelease, OtaDriverStore.QX) }

        val rtAsset = UniversalKernelLoader.findBestEmbeddedDriver(context, kernelRelease, OtaDriverStore.RT)
        val rtAssetVermagic = rtAsset?.let { EmbeddedDrivers.readVermagic(context, it.filename) } ?: ""
        val rtFullMatch = (rtOta != null && rtOta.version.startsWith(kernelRelease)) ||
                (rtAssetVermagic.isNotEmpty() && rtAssetVermagic == kernelRelease)
        val rtShortMatch = (rtOta != null) || (rtAsset != null && RootChecker.kernelShortVersion(rtAsset.version) == short)

        val qxAsset = UniversalKernelLoader.findBestEmbeddedDriver(context, kernelRelease, OtaDriverStore.QX)
        val qxAssetVermagic = qxAsset?.let { EmbeddedDrivers.readVermagic(context, it.filename) } ?: ""
        val qxFullMatch = (qxOta != null && qxOta.version.startsWith(kernelRelease)) ||
                (qxAssetVermagic.isNotEmpty() && qxAssetVermagic == kernelRelease)
        val qxShortMatch = (qxOta != null) || (qxAsset != null && RootChecker.kernelShortVersion(qxAsset.version) == short)

        return when {
            qxFullMatch && !rtFullMatch -> OtaDriverStore.QX
            rtFullMatch && !qxFullMatch -> OtaDriverStore.RT
            qxFullMatch && rtFullMatch -> {
                val major = kernelRelease.split('.').firstOrNull()?.toIntOrNull() ?: 4
                val isDaisy = kernelRelease.contains("Daisy", ignoreCase = true)
                if (isDaisy || major <= 4) OtaDriverStore.QX else OtaDriverStore.RT
            }
            qxShortMatch && !rtShortMatch -> OtaDriverStore.QX
            rtShortMatch && !qxShortMatch -> OtaDriverStore.RT
            else -> {
                val qxResolve = manifest?.let { OtaDriverStore.resolve(it, kernelRelease, OtaDriverStore.QX) }
                val rtResolve = manifest?.let { OtaDriverStore.resolve(it, kernelRelease, OtaDriverStore.RT) }
                val qxDist = if (qxResolve is OtaDriverStore.ResolveResult.Near) qxResolve.distance else Int.MAX_VALUE
                val rtDist = if (rtResolve is OtaDriverStore.ResolveResult.Near) rtResolve.distance else Int.MAX_VALUE

                if (qxDist < rtDist) OtaDriverStore.QX
                else if (rtDist < qxDist) OtaDriverStore.RT
                else {
                    val major = kernelRelease.split('.').firstOrNull()?.toIntOrNull() ?: 4
                    if (major <= 4) OtaDriverStore.QX else OtaDriverStore.RT
                }
            }
        }
    }

    fun autoLoadUniversal(context: Context, preferOta: Boolean = true, variant: String = "") {
        if (isBusy.value) return
        if (DriverRevocationManager.isRevoked(context)) {
            tlog("[REVOKED] Driver was removed from database by administrator.", "ERR")
            tlog("[REVOKED] Access revoked - cannot load. Contact admin.", "ERR")
            autoLoadOk.value = false
            autoLoadStatus.value = "Access revoked - driver deleted from DB"
            return
        }
        isBusy.value = true
        busyStep.value = "Inspecting system..."
        autoLoadOk.value = null
        autoLoadStatus.value = ""
        lastLoadError.value = ""
        if (pickedFileUri.value != null) {
            lastBundleTried.value = pickedFileName.value ?: "custom file"
        } else {
            val autoVar = determineBestVariant(context, RootChecker.getKernelRelease() ?: "")
            lastBundleTried.value = when {
                variant.isBlank() && preferOta -> "${autoVar.uppercase()} (auto) → built-in"
                preferOta -> "${OtaDriverStore.variantLabel(variant)} (forced)"
                else -> "built-in"
            }
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
                    return@launch
                }

                delay(400L)
                tstep("Detecting kernel...")
                val kernel = RootChecker.getKernelRelease() ?: "unknown"
                val short = RootChecker.kernelShortVersion(kernel)
                val arch = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
                tlog("Kernel detected: $short ($arch)", "OK")

                delay(350L)
                tstep("Checking signature security...")
                UniversalKernelLoader.disableModuleSigEnforce(this@DriverViewModel)

                delay(450L)
                tstep("Scanning drivers...")
                tlog("Scanning driver database...", "INFO")
                val manifest = remoteManifest.value ?: OtaDriverStore.fetchManifest()

                delay(450L)
                tstep("Matching best variant...")
                val primaryVariant = when {
                    variant.isNotBlank() -> variant.lowercase(Locale.US)
                    else -> determineBestVariant(context, kernel)
                }
                val secondaryVariant = if (primaryVariant == OtaDriverStore.RT) OtaDriverStore.QX else OtaDriverStore.RT

                tlog("Best match: ${primaryVariant.uppercase()} driver", "OK")
                delay(350L)
                tlog("Starting ${primaryVariant.uppercase()} pipeline...", "CMD")

                val resident = SafetyGuard.hiddenDriverClass()
                if (resident != null) {
                    tlog("Driver already resident in kernel", "OK")
                    if (prepareAndVerifyDeviceNode(preferredDevNode())) {
                        markHiddenLoaded()
                        driverLoaded.value = true
                        autoLoadOk.value = true
                        autoLoadStatus.value = "Already loaded: /dev/${preferredDevNode()}"
                        tlog("Resident driver ready: /dev/${preferredDevNode()}", "OK")
                    } else {
                        tlog("Resident driver has no live node", "WARN")
                        autoLoadOk.value = false
                        autoLoadStatus.value = "Driver resident but node missing - reboot, then LOAD"
                    }
                    return@launch
                }
                if (pickedFileUri.value != null) {
                    tlog("Loading custom file: ${pickedFileName.value}", "CMD")
                    tstep("Loading custom file...")
                    UniversalKernelLoader.autoLoad(context, this@DriverViewModel, variant)
                } else if (variant.isBlank() && preferOta) {
                    activePipelineVariant.value = primaryVariant
                    tlog("Loading ${primaryVariant.uppercase()} driver...", "CMD")
                    tstep("Stage 1/3: ${primaryVariant.uppercase()} driver")
                    val primaryHandled = runOtaLoad(context, primaryVariant)
                    if (primaryHandled) return@launch
                    checkpoint()

                    activePipelineVariant.value = ""
                    tlog("${primaryVariant.uppercase()} failed, trying ${secondaryVariant.uppercase()}...", "WARN")
                    activePipelineVariant.value = secondaryVariant
                    tlog("Loading ${secondaryVariant.uppercase()} driver...", "CMD")
                    tstep("Stage 2/3: ${secondaryVariant.uppercase()} driver")
                    val secondaryHandled = runOtaLoad(context, secondaryVariant)
                    if (secondaryHandled) return@launch
                    checkpoint()

                    activePipelineVariant.value = ""
                    tlog("OTA failed, trying built-in driver...", "WARN")
                    tstep("Stage 3/3: built-in loader")
                    UniversalKernelLoader.autoLoad(context, this@DriverViewModel, primaryVariant)
                } else if (preferOta) {
                    activePipelineVariant.value = variant.lowercase(Locale.US)
                    tlog("Loading ${OtaDriverStore.variantLabel(variant)} driver...", "CMD")
                    tstep("Loading driver...")
                    val handled = runOtaLoad(context, variant)
                    if (handled) return@launch
                    checkpoint()
                    activePipelineVariant.value = ""
                    tlog("DB driver failed, trying built-in...", "INFO")
                    tstep("Built-in loader...")
                    UniversalKernelLoader.autoLoad(context, this@DriverViewModel, variant)
                } else {
                    UniversalKernelLoader.autoLoad(context, this@DriverViewModel, variant)
                }
                if (autoLoadOk.value != true && !driverLoaded.value) {
                    tlog("All pipelines failed", "ERR")
                    autoLoadOk.value = false
                    if (autoLoadStatus.value.isBlank()) {
                        autoLoadStatus.value = "Load failed — RT, QX and built-in all failed"
                    }
                }
            } catch (e: CancellationException) {
            } catch (e: Exception) {
                tlog("FATAL: ${e.message}", "ERR")
                autoLoadStatus.value = "FATAL: ${e.message}"
                autoLoadOk.value = false
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
            val candidateNodes = (moduleNames + matchedNodes + listOf("kloaderctl", "daisyctl", "entryi", "kmem_337", "wanbai"))
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

                val hiddenClass = SafetyGuard.hiddenDriverClass()
                if (hiddenClass != null) {
                    tlog("Driver hidden (class $hiddenClass live, not in lsmod)", "OK")
                } else {
                    setLoadedModule("")
                    tlog("No driver loaded", "WARN")
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

    private val KNOWN_DRIVER_MODULES = listOf(
        "memacc", "memacc_qx", "kmem_337", "kmem_337_qx", "kmem", "entryi", "kloader",
        "5.10_A12", "wanbai", "daisy"
    )

    fun knownDriverModules(): List<String> =
        (KNOWN_DRIVER_MODULES + loadedModuleName.value).filter { it.isNotBlank() }.distinct()

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
        try { DriverWatchdogService.stop(context) } catch (_: Exception) {}
        try { DriverRevocationManager.stopRootDaemon() } catch (_: Exception) {}
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {

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
            val loaded = SafetyGuard.loadedModuleNames()
            val hit = knownDriverModules().filter { it in loaded }
            val nodePresent = Shell.cmd("test -e /dev/${preferredDevNode()} 2>/dev/null").exec().isSuccess
            val hiddenClass = SafetyGuard.hiddenDriverClass() != null
            withContext(Dispatchers.Main) {
                if (hit.isNotEmpty()) {
                    setLoadedModule(hit.first())
                } else if (nodePresent || hiddenClass) {
                    markHiddenLoaded()
                } else {
                    setLoadedModule("")
                    hiddenLoaded.value = false
                }
                driverLoaded.value = hit.isNotEmpty() || nodePresent || hiddenClass
                driverModule.value = hit.joinToString(", ")
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

    fun patchBootImage(context: Context, switchSlot: Boolean = false) {
        if (isBusy.value || isBootPatching.value) return
        isBootPatching.value = true
        isBusy.value = true
        bootPatchResult.value = null
        bootPatchStatus.value = "Starting boot patch pipeline..."
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = BootPatcherEngine.runPipeline(context, this@DriverViewModel, switchSlot)
                withContext(Dispatchers.Main) {
                    bootPatchResult.value = res.success
                    bootPatchStatus.value = res.message
                    bootBackupPath.value = res.backupPath
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    bootPatchResult.value = false
                    bootPatchStatus.value = "Error: ${e.message}"
                }
                tlog("[BOOT ERR] Fatal: ${e.message}", "ERR")
            } finally {
                withContext(Dispatchers.Main) {
                    isBootPatching.value = false
                    isBusy.value = false
                    busyStep.value = ""
                }
            }
        }
    }
}
