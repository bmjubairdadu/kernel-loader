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

    val DEFAULT_DEV_NODE = ""
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
        // Evaluate whenever a load record exists (the driver may be resident but
        // hidden with randomized names that isDriverResident cannot see).
        val isLoaded = driverLoaded.value ||
            DriverRevocationManager.isDriverResident() ||
            DriverRevocationManager.getLoadedRecord(getApplication()) != null
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
        // NOTE: isBusy stays true until the cancelled job's finally block runs -
        // clearing it here allowed a second load pipeline to start mid-insmod.
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
                    var devNode = preferredDevNode()
                    if (devNode.isBlank()) {
                        // Remembered node is lost on restart - ask the driver directly.
                        val probed = try { UniversalKernelLoader.probeOurDriverNode(getApplication(), this@DriverViewModel) } catch (e: Exception) { null }
                        if (probed != null) {
                            devNode = probed
                            rememberDevNode(probed)
                        }
                    }
                    val nodePath = "/dev/$devNode"
                    val nodeExists = devNode.isNotBlank() &&
                        Shell.cmd("test -c $nodePath").exec().isSuccess

                    if (!nodeExists) {
                        tlog("[DEV] $nodePath missing!", "ERR")
                        tlog("[FIX] Load a driver first, then tap VERIFY", "WARN")
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
                                tlog("[FIX 1] Re-load modern RT driver to sync IOCTL dispatch", "FIX")
                                tlog("[FIX 2] Tap LOAD button to reinstall active driver", "FIX")
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

                } catch (e: Exception) {
                    tlog("[TEST ERR] ${e.message?.take(36)}", "ERR")
                } finally {
                    Shell.cmd("rm -f /data/local/tmp/dmtest /data/local/tmp/dmtest_report.txt 2>/dev/null").exec()
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
        "/data/local/tmp/memacc.ko",
        "/data/adb/kloder/driver_rt.ko"
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
        val paramVariants = if (devNode.isNotBlank()) {
            listOf("devname=$devNode", "devicename=$devNode", "")
        } else {
            listOf("")
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

    private fun snapshotDevCharNodes(): Set<String> =
        Shell.cmd("for f in /dev/*; do [ -c \"\$f\" ] && echo \"\${f##*/}\"; done 2>/dev/null")
            .exec().out.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun snapshotSysClasses(): Set<String> =
        Shell.cmd("ls /sys/class 2>/dev/null").exec().out
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    /**
     * Devices where /dev is not devtmpfs-driven never materialize our
     * device_create() nodes, and randomized class names defeat every
     * prefix-glob fallback. The driver ALWAYS registers its classes in
     * /sys/class though, so: diff the class list against the pre-insmod
     * snapshot and mknod each fresh class device. Returns created node names.
     */
    private fun mknodNewSysClasses(before: Set<String>): List<String> {
        if (before.isEmpty()) return emptyList()
        val fresh = snapshotSysClasses() - before
        val made = mutableListOf<String>()
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
        if (made.isEmpty()) made.addAll(mknodPatternSysClasses())
        return made
    }

    /**
     * The driver's node/class names are 8 uppercase letters drawn from a fixed
     * pool (B,Y,N,A,C,D,E,F,G) - no stock Android class looks like that.  When
     * the before/after diff finds nothing (e.g. the module was ALREADY resident
     * from an earlier attempt), this pattern scan still finds its classes.
     */
    private fun mknodPatternSysClasses(): List<String> {
        val made = mutableListOf<String>()
        val classes = Shell.cmd("ls /sys/class 2>/dev/null").exec().out
            .map { it.trim() }.filter { it.matches(Regex("^[B-YA-N]{8}$")) && it.none { c -> c in "HIJKLMOPQRSTUVWXYZ".substring(0, 0) } }
        for (cls in classes) {
            if (!cls.all { it in "BDEFGACNY" }) continue
            val dev = Shell.cmd("cat /sys/class/$cls/dev 2>/dev/null").exec().out
                .firstOrNull()?.trim() ?: continue
            val mm = dev.split(":")
            val maj = mm.getOrNull(0)?.trim()?.toIntOrNull() ?: continue
            val min = mm.getOrNull(1)?.trim()?.toIntOrNull() ?: continue
            if (Shell.cmd("test -c /dev/$cls 2>/dev/null").exec().isSuccess) {
                made.add(cls)  // node already exists - still a valid candidate
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

    private suspend fun prepareAndVerifyDeviceNode(devNode: String, devNodesBefore: Set<String> = emptySet()): Boolean {
        tstep("Preparing device node...")
        tlog("Verifying stealth device node...", "INFO")
        var foundNode: String? = null
        for (i in 1..10) {
            val nodes = snapshotDevCharNodes()
            if (devNode.isNotBlank() && devNode in nodes) {
                foundNode = devNode
                break
            }
            if (devNodesBefore.isNotEmpty()) {
                val fresh = (nodes.toSet() - devNodesBefore).firstOrNull { it.isNotBlank() }
                if (fresh != null) {
                    foundNode = fresh
                    break
                }
            }
            val candidates = listOf("dmsync", "kmem_337", "memacc")
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
            delay(150)
        }

        if (foundNode == null) {
            // Probe first: node/class names are random, so ask the driver directly
            // before trusting any name-based guess.
            val probedNode = try { UniversalKernelLoader.probeOurDriverNode(getApplication(), this@DriverViewModel) } catch (e: Exception) { null }
            if (probedNode != null && probedNode.isNotBlank()) {
                foundNode = probedNode
                tlog("[PROBE] dmtest verified /dev/$probedNode (RT/QX ABI)", "OK")
            }
        }

        if (foundNode == null) {
            val sysDevs = Shell.cmd(
                "for f in /sys/class/dmsync*/dev /sys/class/sysync*/dev /sys/class/uni*/dev /sys/class/kmem*/dev /sys/class/memacc*/dev; do " +
                        "if [ -f \"\$f\" ]; then cat \"\$f\"; echo \" \${f}\"; break; fi; done 2>/dev/null"
            ).exec().out.firstOrNull()?.trim()

            if (!sysDevs.isNullOrBlank()) {
                val parts = sysDevs.split(Regex("\\s+"))
                val majMin = parts.firstOrNull()?.split(":")
                if (majMin != null && majMin.size == 2) {
                    val major = majMin[0].trim()
                    val minor = majMin[1].trim()
                    val sysPath = parts.getOrNull(1) ?: ""
                    val clsName = sysPath.split("/").filter { it.isNotBlank() }.dropLast(1).lastOrNull() ?: "kmem_node"
                    val targetName = devNode.ifBlank { clsName }
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
            val node = foundNode  // smart-cast anchor
            Shell.cmd(
                "chmod 0666 /dev/$node",
                "chcon u:object_r:device:s0 /dev/$node 2>/dev/null"
            ).exec()

            // Final gate: the node must actually answer OUR ioctl ABI, else a
            // concurrently-created stock node could get adopted and remembered.
            val probedOk = try { UniversalKernelLoader.probeOurDriverNode(getApplication(), this@DriverViewModel) } catch (e: Exception) { null }
            if (probedOk == null) {
                tlog("[PROBE FAIL] /dev/$node did not answer our ABI - rejecting node", "ERR")
                Shell.cmd("rm -f /dev/$node 2>/dev/null").exec()
                // A resident module with no answering node = an earlier load is
                // stuck in kernel memory (hidden, nodes lost). Only a reboot clears it.
                // Hidden modules vanish from lsmod but their chrdev registration
                // stays visible in /proc/devices - check both.
                val resident = Shell.cmd("lsmod 2>/dev/null; cat /proc/modules 2>/dev/null; cat /proc/devices 2>/dev/null").exec().out
                val hit = resident.filter {
                    it.contains("sysync") || it.contains("dmsync") || it.contains("memacc") || it.contains("kmem")
                }.map { it.trim() }.filter { it.isNotBlank() }
                if (hit.isNotEmpty()) {
                    tlog("[STALE] Driver is still resident in kernel memory (${hit.take(2).joinToString("; ").take(70)})", "ERR")
                    tlog("[STALE] Reboot the phone FIRST, then tap LOAD", "ERR")
                }
                val poolCls = Shell.cmd("ls /sys/class 2>/dev/null").exec().out
                    .map { it.trim() }.filter { it.matches(Regex("^[BDEFGACNY]{8}$")) }
                if (poolCls.isNotEmpty()) {
                    tlog("[DIAG] driver classes present: ${poolCls.joinToString(",")}", "INFO")
                }
                // One-paste diagnostics: kernel's own view of what happened.
                Shell.cmd(
                    "dmesg | grep -i -E 'sysync|dmsync|kptools|module' | tail -n 10"
                ).exec().out.filter { it.isNotBlank() }.take(6)
                    .forEach { tlog("[DIAG] ${it.take(110)}", "INFO") }
                val up = Shell.cmd("cat /proc/uptime 2>/dev/null").exec().out.firstOrNull()?.trim() ?: ""
                tlog("[DIAG] uptime: ${up.take(20)} (a short value means the phone WAS rebooted)", "INFO")
                otaFailReason = "Driver inserted but no usable device node appeared" +
                    (if (hit.isNotEmpty()) " - an old load is stuck: REBOOT the phone, then tap LOAD" else " - reboot and retry; if it repeats send the [DIAG] lines to support")
                tlog("[DEVNODE ERR] No node answered the ABI probe", "ERR")
                return false
            }

            rememberDevNode(node)
            tlog("Node /dev/$node active (0666)", "OK")
            val rwOk = Shell.cmd("test -r /dev/$node && test -w /dev/$node").exec().isSuccess
            if (rwOk) {
                tlog("/dev/$node verified (R/W OK)", "OK")
                tlog("Driver ready for use", "OK")
            }
            return true
        }

        val hiddenClass = SafetyGuard.hiddenDriverClass()
        if (hiddenClass != null) {
            tlog("[STEALTH] Hidden driver class active in sysfs ($hiddenClass)", "OK")
            return true
        }
        val currentMod = loadedModuleName.value
        if (currentMod.isNotBlank() && currentMod in SafetyGuard.loadedModuleNames()) {
            tlog("[MODULE] $currentMod loaded in kernel (node pending)", "WARN")
            return true
        }
        tlog("[DEVNODE ERR] Node /dev/$devNode missing", "ERR")
        return false
    }

    private fun firstLocalKo(variant: String = ""): File? {
        val explicit = when (variant.lowercase(Locale.US)) {
            OtaDriverStore.RT -> "/data/adb/kloder/driver_rt.ko"
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
        val candidateNames = listOfNotNull(
            loadedModuleName.value.ifBlank { null },
            driverModule.value.ifBlank { null }
        ).distinct()
        val stale = candidateNames.filter { it in live }
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
            val devNodesBefore = snapshotDevCharNodes()
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
            val nodeOk = prepareAndVerifyDeviceNode(devNode, devNodesBefore)
            if (!nodeOk) {
                withContext(Dispatchers.Main) {
                    autoLoadOk.value = false
                    autoLoadStatus.value = "/dev/$devNode missing"
                    tstep("")
                }
                return false
            }
            val mods = SafetyGuard.loadedModuleNames()
            val stagedBase = staged.nameWithoutExtension
            val ours = mods.firstOrNull { it.contains(stagedBase, ignoreCase = true) || stagedBase.contains(it, ignoreCase = true) }
            withContext(Dispatchers.Main) {
                if (ours != null) setLoadedModule(ours)
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
            tlog("[STEALTH] Driver ready. Clear app from recents before launching game!", "OK")
            true
        } finally {
            Shell.cmd("rm -f ${staged.absolutePath} 2>/dev/null").exec()
        }
    }

    private var otaFailReason: String = ""

    private suspend fun runOtaLoad(context: Context, variant: String = ""): Boolean {
        checkpoint()
        // Database is the ONLY driver source - no bundled or leftover local .ko
        // is ever loaded. The OTA download always happens fresh.
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

            val devNodesBefore = snapshotDevCharNodes()
            val sysClassesBefore = snapshotSysClasses()
            tlog("Inserting module into kernel...", "CMD")
            val res = runInsmodLadder(staged.absolutePath, devNode)
            if (res.isSuccess) {
                tlog("Module inserted into kernel", "OK")
                // /dev may not be devtmpfs on every device - re-create nodes
                // from any class the module just registered in /sys/class.
                val mknodded = mknodNewSysClasses(sysClassesBefore)
                if (mknodded.isNotEmpty()) {
                    tlog("[SYSFS] Created /dev nodes from new classes: ${mknodded.joinToString(", ")}", "FIX")
                }
            }

            if (!res.isSuccess) {
                tlog("Module insert failed", "ERR")
                val errText = (res.out + res.err).joinToString("\n").trim()
                if (errText.isNotBlank()) lastLoadError.value = errText.take(300)
                (res.out + res.err).firstOrNull { it.isNotBlank() }?.let {
                    tlog("Error: ${it.trim().take(90)}", "ERR")
                }
                // Kernel rejection (Unknown symbol / format error) is NOT a network
                // problem - give the right message so users report the dmesg lines.
                otaFailReason = "The kernel rejected this driver build - send the DMESG lines above to support if it repeats"

                Shell.cmd(
                    "dmesg 2>/dev/null | grep -i -E 'kmem|module|insmod|version magic|disagrees|Unknown symbol|format error|taint' | tail -n 8"
                ).exec().out.filter { it.isNotBlank() }.take(5)
                    .forEach { tlog("DMESG: ${it.take(90)}", "INFO") }

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
            val nodeOk = prepareAndVerifyDeviceNode(devNode, devNodesBefore)
            if (!nodeOk) {
                withContext(Dispatchers.Main) {
                    autoLoadOk.value = false
                    autoLoadStatus.value = "/dev/$devNode missing"
                    tstep("")
                }
                return false
            }
            val mods = SafetyGuard.loadedModuleNames()
            val stagedBase = staged.nameWithoutExtension
            val ours = mods.firstOrNull { it.contains(stagedBase, ignoreCase = true) || stagedBase.contains(it, ignoreCase = true) }
            withContext(Dispatchers.Main) {
                if (ours != null) setLoadedModule(ours)
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
        return OtaDriverStore.RT
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
                variant.isBlank() -> "${autoVar.uppercase()} (auto) → OTA database"
                else -> "${OtaDriverStore.variantLabel(variant)} (forced) → OTA database"
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
                val primaryVariant = OtaDriverStore.RT

                tlog("Target driver: RT Stealth Engine", "OK")
                delay(350L)
                tlog("Starting RT pipeline...", "CMD")

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
                    // User-provided .ko (BROWSE) - not bundled, their own file.
                    tlog("Loading custom file: ${pickedFileName.value}", "CMD")
                    tstep("Loading custom file...")
                    UniversalKernelLoader.autoLoad(context, this@DriverViewModel, OtaDriverStore.RT)
                } else {
                    // Drivers are NEVER bundled in the APK - the OTA database is the
                    // only source, so every load is a fresh DB download.
                    activePipelineVariant.value = primaryVariant
                    tlog("Loading RT driver...", "CMD")
                    tstep("Stage 1/1: RT driver (OTA / DB)")
                    val handled = runOtaLoad(context, primaryVariant)
                    if (handled) return@launch
                    checkpoint()

                    tlog("[NET] ${otaFailReason.ifBlank { "No internet connection - connect to the internet first, then tap LOAD" }}", "ERR")
                    tlog("[NET] Drivers always download from the database - no offline build is bundled.", "INFO")
                    autoLoadOk.value = false
                    autoLoadStatus.value = "Load failed - driver database unreachable"
                }
                if (autoLoadOk.value != true && !driverLoaded.value) {
                    tlog("RT pipeline failed", "ERR")
                    autoLoadOk.value = false
                    if (autoLoadStatus.value.isBlank()) {
                        autoLoadStatus.value = "Load failed — RT module could not be loaded"
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
                // Randomized node/class means lsmod says nothing - confirm via ABI probe.
                val probed = try { UniversalKernelLoader.probeOurDriverNode(getApplication(), this@DriverViewModel) } catch (e: Exception) { null }
                if (probed != null) {
                    rememberDevNode(probed)
                    markHiddenLoaded()
                    tlog("Driver running in stealth mode (/dev/$probed)", "OK")
                } else {
                    // Probe says nothing of ours is resident - clear stale state
                    // so the UI falls back to LOAD and revocation logic stays sane.
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
            val devNode = preferredDevNode()
            val nodePath = "/dev/$devNode"
            val nodeChar = Shell.cmd("test -c $nodePath 2>/dev/null").exec().isSuccess
            val nodeRw = nodeChar && Shell.cmd("test -r $nodePath && test -w $nodePath 2>/dev/null").exec().isSuccess
            val hiddenClass = SafetyGuard.hiddenDriverClass() != null
            val loadedMods = SafetyGuard.loadedModuleNames()
            val currentMod = loadedModuleName.value
            val isModuleInLsmod = currentMod.isNotBlank() && currentMod in loadedMods
            // Probe gate runs on IO (it shells out - never block Main): with randomized
            // node/class names a fixed-prefix hiddenClass check can't see the driver.
            // Skipped entirely when lsmod already shows our module.
            val probed = if (!isModuleInLsmod) try {
                UniversalKernelLoader.probeOurDriverNode(getApplication(), this@DriverViewModel)
            } catch (e: Exception) {
                null
            } else null

            withContext(Dispatchers.Main) {
                if (isModuleInLsmod) {
                    // Visible load wins over the probe's hidden classification.
                    driverLoaded.value = true
                    setLoadedModule(currentMod)
                } else if (probed != null) {
                    rememberDevNode(probed)
                    driverLoaded.value = true
                    markHiddenLoaded()
                } else if (hiddenClass) {
                    driverLoaded.value = true
                    markHiddenLoaded()
                } else {
                    // Nothing resident: a leftover mknod node survives until reboot
                    // and must NOT read as "loaded" - remove it and clear state.
                    if (nodeChar) {
                        Shell.cmd("rm -f $nodePath 2>/dev/null").exec()
                        tlog("[CLEAN] Removed stale node $nodePath", "INFO")
                    }
                    setLoadedModule("")
                    hiddenLoaded.value = false
                    driverLoaded.value = false
                    driverModule.value = ""
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
