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
    val type: String = "INFO"  
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
        tlog("Checking for updates...", "INFO")
        viewModelScope.launch(Dispatchers.IO) {
            val result = AppUpdateChecker.check(BuildConfig.UPDATE_API_URL, BuildConfig.VERSION_CODE)
            withContext(Dispatchers.Main) {
                when (result) {
                    is AppUpdateChecker.UpdateCheck.Available -> {
                        appUpdate.value = result.info
                        updateStatus.value = "AVAILABLE"
                        tlog("Update available: v${result.info.versionName}", "OK")
                    }
                    AppUpdateChecker.UpdateCheck.UpToDate -> {
                        appUpdate.value = null
                        updateStatus.value = "NONE"
                        tlog("App up-to-date", "OK")
                    }
                    AppUpdateChecker.UpdateCheck.Offline -> {
                        if (updateStatus.value != "AVAILABLE") updateStatus.value = "ERROR"
                        tlog("Update check offline", "WARN")
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
        tlog("Connecting to driver DB...", "INFO")
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
                        tlog("DB connected - ${m.drivers.size} drivers", "OK")
                    m != null ->
                        tlog("DB empty", "WARN")
                    else ->
                        tlog("DB failed - $error", "ERR")
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

    private suspend fun runOtaLoad(context: Context, variant: String = ""): Boolean {
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
        val resolved = OtaDriverStore.resolve(manifest, kernel, variant)
        when (resolved) {
            is OtaDriverStore.ResolveResult.Exact -> {
                tlog("Exact match: $kernel", "OK")
                return downloadAndLoadOta(context, manifest, resolved.entry)
            }
            is OtaDriverStore.ResolveResult.Near -> {
                val target = resolved.entry.version
                tlog("Nearest match: $target", "WARN")
                return downloadAndLoadOta(context, manifest, resolved.entry)
            }
            is OtaDriverStore.ResolveResult.None -> {
                tlog("No driver for this kernel", "INFO")
                return false
            }
        }
    }

    private suspend fun downloadAndLoadOta(
        context: Context,
        manifest: OtaDriverStore.Manifest,
        entry: OtaDriverStore.DriverEntry
    ): Boolean {
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

            val bins = listOf(
                "/system/bin/insmod", "/vendor/bin/insmod", "insmod",
                "/data/adb/magisk/busybox insmod", "busybox insmod"
            )
            val forms = mutableListOf<String>()
            for (b in bins) {
                forms.add("$b ${staged.absolutePath} devname=$devNode")
                forms.add("$b ${staged.absolutePath}")
            }
            var res = Shell.cmd("true").exec()
            for (cmd in forms) {
                res = Shell.cmd(cmd).exec()
                if (res.isSuccess) {
                    tlog("Driver loaded", "OK")
                    break
                }
            }

            if (!res.isSuccess) {
                tlog("Load failed", "ERR")
                val errText = (res.out + res.err).joinToString("\n")

                Shell.cmd(
                    "ls -l /system/bin/insmod /vendor/bin/insmod 2>&1",
                    "for b in /system/bin/insmod /vendor/bin/insmod; do echo \"== \$b\"; \$b 2>&1 | head -n 3; done",
                    "cat ${staged.absolutePath} > /dev/null 2>&1 && echo READ_OK || echo READ_FAIL",
                    "od -An -tx1 ${staged.absolutePath} 2>/dev/null | head -n 1",
                    "dmesg 2>/dev/null | tail -n 10"
                ).exec()
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (preferOta) {
                    val handled = runOtaLoad(context, variant)
                    if (handled) {
                        isBusy.value = false
                        busyStep.value = ""
                        return@launch
                    }
                    tlog("No DB driver - trying built-in", "INFO")                }
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
                tlog("${ours.first()} loaded", "OK")
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

                    tlog("Unloading '$target'...", "INFO")
                    var res = Shell.cmd("rmmod $target").exec()
                    if (!res.isSuccess) {
                        
                        res = Shell.cmd(
                            "BB=\$(command -v busybox); [ -z \"\$BB\" ] && BB=/data/adb/magisk/busybox; \$BB rmmod $target"
                        ).exec()
                    }
                    tlog("Unload $target -> exit ${res.code}", if (res.isSuccess) "OK" else "ERR")
                    res.err.forEach { if (it.isNotBlank()) tlog(it, "WARN") }
                    addLog("rmmod $target", res.out, res.err, res.code)

                    if (res.isSuccess) {
                        tlog("Driver removed", "OK")
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
