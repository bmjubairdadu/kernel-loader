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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

class DriverViewModel : ViewModel() {
    
    val DEFAULT_DEV_NODE = "wanbai"
    var devNodeOverride = mutableStateOf("")

    var pickedFileUri = mutableStateOf<Uri?>(null)
    var pickedFileName = mutableStateOf<String?>(null)
    val logs = mutableStateListOf<LogEntry>()
    var verificationResult = mutableStateOf<VerificationResult?>(null)

    val terminalLines = mutableStateListOf<TerminalLine>()
    var isBusy = mutableStateOf(false)
    var busyStep = mutableStateOf("")
    var autoLoadStatus = mutableStateOf("")   
    var autoLoadOk = mutableStateOf<Boolean?>(null)

    var remoteManifest = mutableStateOf<OtaDriverStore.Manifest?>(null)
    var manifestStatus = mutableStateOf("IDLE")   

    var loadedModuleName = mutableStateOf("")

    var driverLoaded = mutableStateOf(false)

    var driverModule = mutableStateOf("")

    var autoloadEnabled = mutableStateOf(false)

    // report context: which driver family the last pipeline tried + the exact insmod error
    var lastBundleTried = mutableStateOf("")
    var lastLoadError = mutableStateOf("")

    fun setLoadedModule(name: String) {
        if (loadedModuleName.value != name) loadedModuleName.value = name
        driverLoaded.value = name.isNotBlank()
    }

    var appUpdate = mutableStateOf<AppUpdateChecker.UpdateInfo?>(null)
    var updateStatus = mutableStateOf("IDLE")     
    var updateProgress = mutableStateOf(-1)       
    var updateMsg = mutableStateOf("")

    fun checkForAppUpdate() {
        if (updateStatus.value == "CHECKING" || updateStatus.value == "DOWNLOADING") return
        updateStatus.value = "CHECKING"
        tlog("Checking Update...", "INFO")
        viewModelScope.launch(Dispatchers.IO) {
            val result = AppUpdateChecker.check(BuildConfig.UPDATE_RELEASES_PAGE, BuildConfig.VERSION_CODE)
            withContext(Dispatchers.Main) {
                when (result) {
                    is AppUpdateChecker.UpdateCheck.Available -> {
                        appUpdate.value = result.info
                        updateStatus.value = "AVAILABLE"
                        tlog("Update Available: v${result.info.versionName}", "WARN")
                    }
                    AppUpdateChecker.UpdateCheck.UpToDate -> {
                        appUpdate.value = null
                        updateStatus.value = "NONE"
                        tlog("App Up To Date", "OK")
                    }
                    AppUpdateChecker.UpdateCheck.Offline -> {
                        if (updateStatus.value != "AVAILABLE") updateStatus.value = "ERROR"
                        tlog("Update Check Offline", "WARN")
                    }
                }
            }
        }
    }

    fun installAppUpdate(context: Context) {
        val info = appUpdate.value ?: return
        updateStatus.value = "DOWNLOADING"
        updateProgress.value = 0
        viewModelScope.launch(Dispatchers.IO) {
            if (!AppUpdateChecker.canInstall(context)) {
                withContext(Dispatchers.Main) {
                    tlog("Install permission needed", "WARN")
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

    companion object {
        private const val MAX_TERMINAL_LINES = 1200
    }

    var activePipelineVariant = mutableStateOf("")

    fun tlog(text: String, type: String = "INFO", variant: String? = null) {
        val v = variant ?: activePipelineVariant.value
        viewModelScope.launch(Dispatchers.Main) {
            terminalLines.add(TerminalLine(timeFormat.format(Date()), text, type, v))
            trimTerminal()
        }
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

    fun clearTerminal() { terminalLines.clear() }

    fun nowString(): String = dateFormat.format(Date())

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

    private val LOCAL_KO_CANDIDATES = listOf(
        "/data/adb/kloder/driver.ko",
        "/data/local/tmp/driver.ko",
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
        var res = Shell.cmd("true").exec()
        var attempts = 0
        outer@ for (b in bins) {
            for (withDev in listOf(true, false)) {
                checkpoint()
                if (attempts >= MAX_INSMOD_ATTEMPTS) break@outer
                if (attempts > 0) delay(INSMOD_BACKOFF_MS)
                attempts++
                res = Shell.cmd(if (withDev) "$b $koPath devname=$devNode" else "$b $koPath").exec()
                if (res.isSuccess) return res
                val err = (res.out + res.err).joinToString(" ").lowercase(Locale.US)
                if (err.contains("not found") || err.contains("no such file")) continue@outer
                // fatal check MUST come first: toybox reports one combined line
                // ("unknown symbol in module, or unknown parameter") and the
                // kernel's own dmesg line names the real deterministic reason
                if (FATAL_INSMOD_ERRORS.any { err.contains(it) }) break@outer
                if (err.contains("unknown parameter")) continue
            }
        }
        return res
    }

    private fun firstLocalKo(variant: String = ""): File? {
        val explicit = when (variant.lowercase(Locale.US)) {
            OtaDriverStore.RT -> "/data/adb/kloder/driver_rt.ko"
            OtaDriverStore.QX -> "/data/adb/kloder/driver_qx.ko"
            else -> null
        }
        if (explicit != null) {
            return if (Shell.cmd("test -f $explicit").exec().isSuccess) File(explicit) else null
        }
        return LOCAL_KO_CANDIDATES.firstOrNull { Shell.cmd("test -f $it").exec().isSuccess }
            ?.let { File(it) }
    }

    private suspend fun loadStagedKo(context: Context, localKo: File): Boolean {
        tlog("Using local driver.ko", "INFO")
        val devNode = preferredDevNode()
        val staged = File("/data/local/tmp/kloader_local.ko")
        Shell.cmd(
            "cp \"${localKo.absolutePath}\" ${staged.absolutePath}",
            "chmod 644 ${staged.absolutePath}",
            "chown root:root ${staged.absolutePath} 2>/dev/null",
            "chcon u:object_r:system_file:s0 ${staged.absolutePath} 2>/dev/null",
            "setenforce 0 2>/dev/null"
        ).exec()
        if (!Shell.cmd("test -f ${staged.absolutePath}").exec().isSuccess) {
            tlog("Staging failed", "ERR")
            return false
        }
        Shell.cmd("rmmod kmem_337 2>/dev/null", "rmmod kmem_337_qx 2>/dev/null", "sleep 1").exec()
        return try {
            val res = runInsmodLadder(staged.absolutePath, devNode)
            if (!res.isSuccess) {
                (res.out + res.err).firstOrNull { it.isNotBlank() }?.let {
                    tlog("insmod: ${it.trim()}", "ERR")
                    lastLoadError.value = it.trim().take(300)
                }
                return false
            }
            tlog("Load OK", "OK")
            Shell.cmd("chmod 666 /dev/$devNode 2>/dev/null").exec()
            withContext(Dispatchers.Main) { verifyModule() }
            val nodes = Shell.cmd("ls /dev 2>/dev/null").exec().out.map { it.trim() }
            if (devNode !in nodes) {
                tlog("Node /dev/$devNode missing", "ERR")
                return false
            }
            rememberDevNode(devNode)
            withContext(Dispatchers.Main) {
                val mods = SafetyGuard.loadedModuleNames()
                val ours = knownDriverModules().filter { it in mods }
                if (ours.isNotEmpty()) setLoadedModule(ours.first())
            }
            UniversalKernelLoader.stageForBoot(this@DriverViewModel, context, "", devNode, staged)
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
                tlog("Invalid .ko file", "ERR")
                return false
            }

            val kernel = RootChecker.getKernelRelease() ?: return false
            tlog("Loading driver...", "INFO")

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
                tlog("Staging failed", "ERR")
                return false
            }
            
            Shell.cmd("rmmod kmem_337 2>/dev/null", "rmmod kmem_337_qx 2>/dev/null", "sleep 1").exec()
            try {

            val res = runInsmodLadder(staged.absolutePath, devNode)
            if (res.isSuccess) {
                tlog("Driver loaded", "OK")
            }

            if (!res.isSuccess) {
                tlog("Load failed", "ERR")
                val errText = (res.out + res.err).joinToString("\n").trim()
                if (errText.isNotBlank()) lastLoadError.value = errText.take(300)
                (res.out + res.err).firstOrNull { it.isNotBlank() }?.let {
                    tlog("insmod: ${it.trim()}", "ERR")
                }

                Shell.cmd(
                    "dmesg 2>/dev/null | grep -i -E 'kmem|module|insmod' | tail -n 5"
                ).exec().out.filter { it.isNotBlank() }.take(3)
                    .forEach { tlog("dmesg: $it", "INFO") }

                if (errText.contains("Invalid module format", true) ||
                    errText.contains("vermagic", true) ||
                    errText.contains("Exec format error", true)
                ) {
                    tlog("Kernel mismatch", "WARN")
                } else if (errText.contains("No such file or directory", true)) {
                    tlog("Staged file not readable", "WARN")
                }
                tlog("Need custom loader - tap WhatsApp", "FIX")
                return false
            }

            tlog("Load OK", "OK")

            Shell.cmd("chmod 666 /dev/$devNode 2>/dev/null").exec()

            withContext(Dispatchers.Main) { verifyModule() }

            tstep("Checking /dev/$devNode...")
            val nodes = Shell.cmd("ls /dev 2>/dev/null").exec().out.map { it.trim() }
            if (nodes.any { it == devNode }) {
                Shell.cmd("chmod 666 /dev/$devNode 2>/dev/null").exec()
                val mode = Shell.cmd("ls -l /dev/$devNode 2>/dev/null").exec().out.firstOrNull()?.trim().orEmpty()
                if (mode.contains("rw-rw-rw-")) {
                    tlog("Node ready", "OK")
                } else {
                    tlog("Node not world-R/W", "WARN")
                }
            } else {
                tlog("Node /dev/$devNode missing", "ERR")
                withContext(Dispatchers.Main) {
                    autoLoadOk.value = false
                    autoLoadStatus.value = "/dev/$devNode missing"
                    tstep("")
                }
                return false
            }
            rememberDevNode(devNode)
            withContext(Dispatchers.Main) {
                val mods = SafetyGuard.loadedModuleNames()
                val ours = knownDriverModules().filter { it in mods }
                if (ours.isNotEmpty()) setLoadedModule(ours.first())
            }
            UniversalKernelLoader.stageForBoot(this@DriverViewModel, context, entry.variant, devNode, staged)
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
        lastBundleTried.value = when {
            variant.isBlank() && preferOta -> "RT → QX → built-in"
            preferOta -> "${OtaDriverStore.variantLabel(variant)} (forced)"
            else -> "built-in"
        }
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                if (variant.isBlank() && preferOta) {
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
                    withContext(Dispatchers.Main) {
                        UniversalKernelLoader.autoLoad(context, this@DriverViewModel, variant)
                    }
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
                    withContext(Dispatchers.Main) {
                        UniversalKernelLoader.autoLoad(context, this@DriverViewModel, variant)
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        UniversalKernelLoader.autoLoad(context, this@DriverViewModel, variant)
                    }
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
            
            val moduleNames = lsmodRes.out.drop(1)
                .map { it.trim().split(Regex("\\s+")).firstOrNull() ?: "" }
                .filter { it.isNotBlank() && it != "Module" }

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

            val ours = knownDriverModules().filter { it in moduleNames }
            if (ours.isNotEmpty()) {
                setLoadedModule(ours.first())
                driverModule.value = ours.joinToString(", ")
                tlog("${ours.first()} loaded", "OK", variant = variantOfModule(ours.first()))
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
    }

    private val KNOWN_DRIVER_MODULES = listOf(
        "kmem_337", "kmem_337_qx", "kmem", "entryi", "kloader",
        "5.10_A12", "wanbai", "daisy"
    )

    fun knownDriverModules(): List<String> =
        (KNOWN_DRIVER_MODULES + loadedModuleName.value).filter { it.isNotBlank() }.distinct()

    fun preferredDevNode(): String = if (devNodeOverride.value.isNotBlank()) {
        devNodeOverride.value.trim()
    } else {
        DEFAULT_DEV_NODE
    }

    fun rememberDevNode(node: String) {
        if (node.isNotBlank() && node != DEFAULT_DEV_NODE) devNodeOverride.value = node.trim()
    }

    fun unloadModule(context: Context) {
        if (isBusy.value) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val loaded = SafetyGuard.loadedModuleNames()
                    
                    val remembered = loadedModuleName.value
                    
                    val known = knownDriverModules().filter { it in loaded }
                    
                    val target = when {
                        remembered.isNotBlank() && remembered in loaded -> remembered
                        known.isNotEmpty() -> known.first()
                        else -> {
                            tlog("No driver loaded", "WARN")
                            DriverAutoload.state(context)
                            if (DriverAutoload.enabled) {
                                tlog("Auto-load is ON - will reload at boot", "INFO")
                            }
                            driverLoaded.value = false
                            return@withContext
                        }
                    }

                    tlog("Unloading '$target'...", "INFO", variant = variantOfModule(target))
                    var res = Shell.cmd("rmmod $target").exec()
                    if (!res.isSuccess) {

                        res = Shell.cmd(
                            "BB=\$(command -v busybox); [ -z \"\$BB\" ] && BB=/data/adb/magisk/busybox; \$BB rmmod $target"
                        ).exec()
                    }
                    tlog(
                        "Unload $target -> exit ${res.code}",
                        if (res.isSuccess) "OK" else "ERR",
                        variant = variantOfModule(target)
                    )
                    res.err.forEach { if (it.isNotBlank()) tlog(it, "WARN") }
                    addLog("rmmod $target", res.out, res.err, res.code)

                    if (res.isSuccess) {
                        tlog("Driver removed", "OK", variant = variantOfModule(target))
                        DriverAutoload.state(context)
                        if (DriverAutoload.enabled) {
                            tlog("Auto-load still ON - will reload at boot", "WARN")
                        }
                    } else {
                        tlog("Unload failed - reboot clears it", "WARN")
                    }
                    setLoadedModule("")
                    refreshDriverState(context)
                } catch (e: Exception) {
                    tlog("UNLOAD ERROR: ${e.message}", "ERR")
                }
            }
        }
    }

    fun setBootAutoload(context: Context, on: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    DriverAutoload.state(context)
                    activePipelineVariant.value = variantOfModule(DriverAutoload.moduleName)
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
                } finally {
                    activePipelineVariant.value = ""
                }
            }
        }
    }

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
