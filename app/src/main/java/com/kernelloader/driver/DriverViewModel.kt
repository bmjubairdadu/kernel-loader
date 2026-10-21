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

    // true only while the reboot-to-unload is in flight: the restart ring spins
    // fast and shows a spinner for this, not for unrelated busy actions
    var rebooting = mutableStateOf(false)
    var autoLoadStatus = mutableStateOf("")   
    var autoLoadOk = mutableStateOf<Boolean?>(null)

    var remoteManifest = mutableStateOf<OtaDriverStore.Manifest?>(null)
    var manifestStatus = mutableStateOf("IDLE")   

    var loadedModuleName = mutableStateOf("")

    var driverLoaded = mutableStateOf(false)

    var driverModule = mutableStateOf("")

    // report context: which driver family the last pipeline tried + the exact insmod error
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
                delay(15 * 60 * 1000L) // check every 15 minutes
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
            val result = AppUpdateChecker.check(BuildConfig.UPDATE_RELEASES_PAGE, BuildConfig.VERSION_CODE)
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

    // SimpleDateFormat is not thread-safe and these are reached from both the
    // main thread and IO (load/verify paths) - serialize every format call
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
        val v = (variant ?: activePipelineVariant.value).orEmpty()
        val stampTime = stamp(timeFormat)
        viewModelScope.launch(Dispatchers.Main) {
            terminalLines.add(TerminalLine(stampTime, text, type, v))
            trimTerminal()
        }
        appendToLogFile(stampTime, type, v, text)
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

    /**
     * Compact Driver & Read/Write Activity Monitor:
     * Validates device node (/dev/wanbai), SELinux, active clients, and exercises
     * ioctl 0x803 (module base), 0x801 (read) and 0x802 (write).
     * Output is formatted in short, compact lines tailored for the small console panel.
     */
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
                    Shell.cmd("setenforce 0 2>/dev/null").exec()

                    // 1. Check device node and permissions
                    val devNode = preferredDevNode()
                    val targetNode = if (Shell.cmd("test -e /dev/wanbai").exec().isSuccess) "wanbai"
                                     else devNode
                    val nodePath = "/dev/$targetNode"
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

                    // 2. Check active clients (apps/tools reading or writing)
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

                    // 3. Detailed R/W test via dmtest
                    val local = File(context.filesDir, "dmtest")
                    // Always refresh from the APK asset.  A cached copy from an
                    // older APK would otherwise keep probing obsolete commands.
                    context.assets.open("dmtest").use { input ->
                        local.outputStream().use { output -> input.copyTo(output) }
                    }
                    val testRun = Shell.cmd(
                        "cp \"${local.absolutePath}\" /data/local/tmp/dmtest 2>/dev/null",
                        "chmod 755 /data/local/tmp/dmtest 2>/dev/null",
                        "/data/local/tmp/dmtest /data/local/tmp/dmtest_report.txt 2>&1"
                    ).exec()

                    val outLines = testRun.out

                    // 1. Probe & Candidate verification details
                    val probeLines = outLines.filter { it.contains("probe:") || it.contains("open FAIL") || it.contains("verdict: our driver") }
                    probeLines.forEach { line ->
                        val clean = line.trim()
                        if (clean.contains("open FAIL") || clean.contains("NOT ours") || clean.contains("ABSENT")) {
                            tlog("[PROBE FAIL] $clean", "ERR")
                        } else if (clean.contains("open OK") || clean.contains("OUR DRIVER")) {
                            tlog("[PROBE] $clean", "OK")
                        }
                    }

                    // 2. ioctl 0x803 (Module Base) details
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

                    // 3. ioctl 0x801 (Memory Read) details
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

                    // 4. ioctl 0x802 (Memory Write) details
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

                    // 5. Cross-Process / Child testing details
                    val xprocLines = outLines.filter { it.contains("xproc") || it.contains("child pid") }
                    val failedXproc = xprocLines.filter { it.contains("FAIL") }
                    if (failedXproc.isNotEmpty()) {
                        failedXproc.forEach { tlog("[CHILD FAIL] " + it.trim(), "ERR") }
                    } else if (xprocLines.any { it.contains("PASS") }) {
                        val childPassCount = xprocLines.count { it.contains("PASS") }
                        tlog("[CHILD] Cross-Proc R/W: PASS ($childPassCount/4 cross-page verified)", "OK")
                    }

                    // 6. Overall summary line
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
                                tlog("[FIX 1] Set SELinux to Permissive: su -c setenforce 0", "FIX")
                                tlog("[FIX 2] Add Magisk policy: supolicy --live 'allow * * chr_file *'", "FIX")
                            }
                            errText.contains("errno=25") || errText.contains("inappropriate ioctl") || errText.contains("not ours") -> {
                                tlog("[CAUSE] Error 25: ENOTTY (Driver ABI Mismatch)", "ERR")
                                tlog("[FIX 1] Driver is loaded as wrong variant (RT vs QX)", "FIX")
                                tlog("[FIX 2] Use FORCE button on Home screen to switch RT <-> QX", "FIX")
                            }
                            errText.contains("errno=2") || errText.contains("no such file") || errText.contains("no candidate") -> {
                                tlog("[CAUSE] Error 2: ENOENT (Device node missing)", "ERR")
                                tlog("[FIX 1] Tap LOAD button to insmod and recreate /dev/wanbai", "FIX")
                                tlog("[FIX 2] Run: mknod /dev/wanbai c <major> <minor>", "FIX")
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

    // deterministic kernel rejections: retrying the same .ko through other
    // loader binaries cannot succeed, so the ladder must stop instead of
    // hammering insmod (each failed insmod wakes the whole module loader)
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

    /**
     * One bounded insmod ladder for every load path: devname form first, plain
     * form for legacy .ko builds, at most [MAX_INSMOD_ATTEMPTS] real attempts
     * with a small backoff between them. A missing loader binary moves on to
     * the next candidate; a deterministic kernel rejection aborts the ladder.
     */
    private suspend fun runInsmodLadder(koPath: String, devNode: String): Shell.Result {
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
            val force = Shell.cmd("insmod -f $koPath devname=$devNode 2>/dev/null || insmod -f $koPath devicename=$devNode 2>/dev/null || insmod -f $koPath 2>/dev/null").exec()
            if (force.isSuccess) return force
            (force.out + force.err).firstOrNull { it.isNotBlank() }?.let {
                tlog("insmod -f: ${it.trim()}", "ERR")
            }
        }
        return res
    }

    private suspend fun prepareAndVerifyDeviceNode(devNode: String): Boolean {
        tstep("Preparing device node...")
        tlog("[SECURITY] Injected untrusted_app chr_file policies", "INFO")
        Shell.cmd(
            "setenforce 0 2>/dev/null",
            "magiskpolicy --live 'allow untrusted_app * chr_file *' 2>/dev/null || supolicy --live 'allow untrusted_app * chr_file *' 2>/dev/null"
        ).exec()
        var foundNode: String? = null
        tlog("[SYSFS] Scanning /dev & /sys/class for active character device...", "INFO")
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

        // Deep fallback: if ueventd did not create the node in /dev, check sysfs /sys/class
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
                        "chcon u:object_r:null_device:s0 /dev/$targetName 2>/dev/null || chcon u:object_r:device:s0 /dev/$targetName 2>/dev/null"
                    ).exec()
                    foundNode = targetName
                    tlog("[SYSFS] Created /dev/$targetName from sysfs ($major:$minor)", "FIX")
                }
            }
        }

        if (foundNode != null) {
            Shell.cmd(
                "chmod 0666 /dev/$foundNode",
                "chcon u:object_r:null_device:s0 /dev/$foundNode 2>/dev/null || chcon u:object_r:device:s0 /dev/$foundNode 2>/dev/null"
            ).exec()

            val allAliases = listOf("wanbai", "dmsync", "dmsync_rt", "dmsync_qx", "memacc", "memacc_qx", "entryi", "kmem_337", "CheckMe", "lanran", "video188")
            val linkCmds = allAliases.filter { it != foundNode }.map { alias ->
                "rm -f /dev/$alias 2>/dev/null; ln -sf /dev/$foundNode /dev/$alias 2>/dev/null; chmod 0666 /dev/$alias 2>/dev/null; chcon -h u:object_r:null_device:s0 /dev/$alias 2>/dev/null"
            }
            if (linkCmds.isNotEmpty()) {
                Shell.cmd(*linkCmds.toTypedArray()).exec()
            }

            rememberDevNode(foundNode)
            tlog("[DEVNODE] Device node /dev/$foundNode active (perms 0666, SELinux ok)", "OK")
            tlog("[DEVNODE] All aliases symlinked (/dev/wanbai, /dev/dmsync, /dev/memacc...)", "INFO")
            val rwOk = Shell.cmd("test -r /dev/wanbai && test -w /dev/wanbai").exec().isSuccess
            if (rwOk) {
                tlog("[DEVNODE] /dev/wanbai client R/W verified (0666)", "OK")
                tlog("[READY] Kernel driver operational · Ready for client requests", "OK")
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

    /**
     * rmmod every module of this family that is actually live before a fresh
     * insmod — the embedded qx/rt .ko builds come up as "entryi" / "5.10_A12",
     * so removing only kmem_337 variants would leave the old driver loaded and
     * the next insmod would die with "File exists". Nothing live -> no rmmod
     * and no wait: a fresh load must not pay for the switch case.
     */
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
        tlog("[SOURCE] Local driver found: ${localKo.name}", "INFO")
        val devNode = preferredDevNode()
        val staged = File("/data/local/tmp/kloader_local.ko")
        tlog("[STAGE] Staging: ${localKo.absolutePath} -> ${staged.absolutePath}", "CMD")
        Shell.cmd(
            "cp \"${localKo.absolutePath}\" ${staged.absolutePath}",
            "chmod 644 ${staged.absolutePath}",
            "chown root:root ${staged.absolutePath} 2>/dev/null",
            "chcon u:object_r:system_file:s0 ${staged.absolutePath} 2>/dev/null",
            "setenforce 0 2>/dev/null"
        ).exec()
        if (!Shell.cmd("test -f ${staged.absolutePath}").exec().isSuccess) {
            tlog("[STAGE ERR] Staging failed for ${staged.absolutePath}", "ERR")
            return false
        }
        tlog("[STAGE] File ready at ${staged.absolutePath} (perms 0644, system_file)", "OK")
        rmmodKnownDrivers()
        return try {
            tlog("[KERNEL] Executing insmod ladder for ${staged.name} (devname=$devNode)...", "CMD")
            val res = runInsmodLadder(staged.absolutePath, devNode)
            if (!res.isSuccess) {
                (res.out + res.err).firstOrNull { it.isNotBlank() }?.let {
                    tlog("[KERNEL ERR] insmod: ${it.trim()}", "ERR")
                    lastLoadError.value = it.trim().take(300)
                }
                return false
            }
            tlog("[KERNEL] Module inserted into kernel space", "OK")
            tlog("[CORE] Physical-alias memory translation active (page-table walk + ioremap)", "INFO")
            tlog("[IOCTL] Handlers active: 0x801 (Read), 0x802 (Write), 0x803 (ModBase)", "INFO")
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
            tlog("[STAGE] Staging: ${downloaded.name} -> /data/local/tmp/kloader_ota.ko", "CMD")

            val staged = File("/data/local/tmp/kloader_ota.ko")
            
            val devNode = preferredDevNode()
            Shell.cmd(
                "cp \"${downloaded.absolutePath}\" ${staged.absolutePath}",
                "chmod 644 ${staged.absolutePath}",
                "chown root:root ${staged.absolutePath} 2>/dev/null",
                "chcon u:object_r:system_file:s0 ${staged.absolutePath} 2>/dev/null",
                "setenforce 0 2>/dev/null"
            ).exec()
            if (!Shell.cmd("test -f ${staged.absolutePath}").exec().isSuccess) {
                tlog("[STAGE ERR] Staging to ${staged.absolutePath} failed", "ERR")
                return false
            }
            tlog("[STAGE] Deployed to ${staged.absolutePath} (perms 0644, system_file)", "OK")

            rmmodKnownDrivers()
            try {

            tlog("[KERNEL] Executing insmod ladder for ${staged.name} (devname=$devNode)...", "CMD")
            val res = runInsmodLadder(staged.absolutePath, devNode)
            if (res.isSuccess) {
                tlog("[KERNEL] Module inserted into kernel space", "OK")
                tlog("[CORE] Physical-alias memory translation active (page-table walk + ioremap)", "INFO")
                tlog("[IOCTL] Handlers active: 0x801 (Read), 0x802 (Write), 0x803 (ModBase)", "INFO")
            }

            if (!res.isSuccess) {
                tlog("[KERNEL ERR] insmod failed", "ERR")
                val errText = (res.out + res.err).joinToString("\n").trim()
                if (errText.isNotBlank()) lastLoadError.value = errText.take(300)
                (res.out + res.err).firstOrNull { it.isNotBlank() }?.let {
                    tlog("[KERNEL ERR] insmod: ${it.trim()}", "ERR")
                }

                Shell.cmd(
                    "dmesg 2>/dev/null | grep -i -E 'kmem|module|insmod|version magic|disagrees|Unknown symbol|format error|taint' | tail -n 8"
                ).exec().out.filter { it.isNotBlank() }.take(5)
                    .forEach { tlog("[DMESG] $it", "INFO") }

                if (errText.contains("Invalid module format", true) ||
                    errText.contains("vermagic", true) ||
                    errText.contains("Exec format error", true)
                ) {
                    tlog("[MISMATCH] Kernel mismatch", "WARN")
                } else if (errText.contains("No such file or directory", true)) {
                    tlog("[ERR] Staged file not readable", "WARN")
                }
                tlog("Need custom loader - tap Telegram / WhatsApp", "FIX")
                return false
            }

            tlog("[KERNEL] Driver loaded successfully", "OK")
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

    fun autoLoadUniversal(context: Context, preferOta: Boolean = true, variant: String = "") {
        if (isBusy.value) return
        isBusy.value = true
        busyStep.value = "Starting..."
        autoLoadOk.value = null
        autoLoadStatus.value = ""
        lastLoadError.value = ""
        if (pickedFileUri.value != null) {
            lastBundleTried.value = pickedFileName.value ?: "custom file"
        } else {
            lastBundleTried.value = when {
                variant.isBlank() && preferOta -> "RT → QX → built-in"
                preferOta -> "${OtaDriverStore.variantLabel(variant)} (forced)"
                else -> "built-in"
            }
        }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // 3.2+ driver hides from lsmod on load - a second LOAD would
                // insmod into "File exists". Reuse the resident driver instead.
                val resident = SafetyGuard.hiddenDriverClass()
                if (resident != null) {
                    tlog("[RESIDENT] Driver already loaded in kernel ($resident, hidden) - reusing", "OK")
                    tlog("[CORE] Physical-alias memory translation active (page-table walk + ioremap)", "INFO")
                    tlog("[IOCTL] Handlers active: 0x801 (Read), 0x802 (Write), 0x803 (ModBase)", "INFO")
                    if (prepareAndVerifyDeviceNode(preferredDevNode())) {
                        markHiddenLoaded()
                        driverLoaded.value = true
                        autoLoadOk.value = true
                        autoLoadStatus.value = "Already loaded: /dev/${preferredDevNode()}"
                        tlog("[DONE] Resident driver ready · Active on /dev/${preferredDevNode()}", "OK")
                    } else {
                        tlog("[WARN] Resident driver has no live node - reload after reboot", "WARN")
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
                    activePipelineVariant.value = OtaDriverStore.RT
                    tlog("PIPELINE 1/3 — RT driver", "CMD")
                    tstep("Stage 1/3: RT driver")
                    val rtHandled = runOtaLoad(context, OtaDriverStore.RT)
                    if (rtHandled) return@launch
                    checkpoint()
                    activePipelineVariant.value = ""
                    tlog("RT Failed → QX", "WARN")
                    activePipelineVariant.value = OtaDriverStore.QX
                    tlog("PIPELINE 2/3 — QX driver", "CMD")
                    tstep("Stage 2/3: QX driver")
                    val qxHandled = runOtaLoad(context, OtaDriverStore.QX)
                    if (qxHandled) return@launch
                    checkpoint()
                    activePipelineVariant.value = ""
                    tlog("QX Failed → Built-in", "WARN")
                    tlog("PIPELINE 3/3 — Built-in", "CMD")
                    tstep("Stage 3/3: built-in loader")
                    UniversalKernelLoader.autoLoad(context, this@DriverViewModel, variant)
                } else if (preferOta) {
                    activePipelineVariant.value = variant.lowercase(Locale.US)
                    tlog("PIPELINE — ${OtaDriverStore.variantLabel(variant)} driver (forced)", "CMD")
                    tstep("Stage 1/2: ${OtaDriverStore.variantLabel(variant)} driver")
                    val handled = runOtaLoad(context, variant)
                    if (handled) return@launch
                    checkpoint()
                    activePipelineVariant.value = ""
                    tlog("DB driver failed — trying built-in loader", "INFO")
                    tstep("Stage 2/2: built-in loader")
                    UniversalKernelLoader.autoLoad(context, this@DriverViewModel, variant)
                } else {
                    UniversalKernelLoader.autoLoad(context, this@DriverViewModel, variant)
                }
                if (autoLoadOk.value != true && !driverLoaded.value) {
                    tlog("All Pipelines Failed", "ERR")
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

    /**
     * Awaited by the load pipeline so the loaded/hidden verdict is settled
     * before the next step runs. Every line here is a root-shell round trip:
     * this must never be called on the main thread.
     */
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
                // 3.2+ drivers unlink themselves from /proc/modules on load:
                // an empty lsmod next to a live sysfs class means "loaded and
                // hidden", which is the normal success state, not a failure
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
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                // the driver hides on load and offers no unhide -
                // only a reboot unloads it (same as reference drivers)
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
            val nodePresent = Shell.cmd("test -e /dev/${preferredDevNode()} 2>/dev/null || test -e /dev/wanbai 2>/dev/null").exec().isSuccess
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
}
