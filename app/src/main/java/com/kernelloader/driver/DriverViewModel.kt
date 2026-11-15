package com.kernelloader.driver

import android.app.Activity
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
import androidx.lifecycle.viewModelScope
import com.kernelloader.BuildConfig
import com.kernelloader.root.RootChecker
import com.kernelloader.update.AppUpdateChecker
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TerminalLine(
    val text: String,
    val type: String = "INFO",
    val time: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
)

class DriverViewModel(application: Application) : AndroidViewModel(application) {

    var driverLoaded = mutableStateOf(false)
    var hiddenLoaded = mutableStateOf(false)
    var driverModule = mutableStateOf("")
    var loadedModuleName = mutableStateOf("")
    var isBusy = mutableStateOf(false)
    var busyStep = mutableStateOf("")
    var autoLoadStatus = mutableStateOf("")
    var autoLoadOk = mutableStateOf<Boolean?>(null)
    var lastLoadError = mutableStateOf("")
    var lastBundleTried = mutableStateOf("")
    var pickedFileName = mutableStateOf("")
    var remoteManifest = mutableStateOf<OtaDriverStore.Manifest?>(null)
    var manifestStatus = mutableStateOf("IDLE")
    var appUpdate = mutableStateOf<AppUpdateChecker.UpdateInfo?>(null)
    var updateStatus = mutableStateOf("IDLE")
    var updateProgress = mutableStateOf(0)
    var rebooting = mutableStateOf(false)
    var verifyStatus = mutableStateOf("IDLE")
    var verifyDetail = mutableStateOf("")
    var kernelCheckStatus = mutableStateOf("IDLE")
    var kernelCheckIssuesCount = mutableStateOf(0)
    var kernelCheckHasBlockers = mutableStateOf(false)
    var loadErrorDialogData = mutableStateOf<DriverErrorInfo?>(null)

    val terminalLines = mutableStateListOf<TerminalLine>()

    private var pickedLocalFile: File? = null
    private var logFile: File? = null
    private var currentLoadJob: Job? = null
    private var downloadedUpdateApk: File? = null
    @Volatile private var startupStarted = false

    init {
        initLogStore(application)
        refreshDriverState(application)
    }

    fun refreshDriverState(context: Context? = null) {
        val appCtx = context ?: getApplication()
        val (active, info) = DriverSessionManager.isSessionActive(appCtx)
        if (active) {
            val (node, mod, stealth) = info
            val displayLabel = if (stealth) "Stealth" else mod.ifBlank { DriverInstaller.MODULE }
            driverLoaded.value = true
            hiddenLoaded.value = stealth
            loadedModuleName.value = displayLabel
            driverModule.value = if (node.isNotBlank() && node != "dynamic") "$displayLabel ($node)" else displayLabel
        } else {
            driverLoaded.value = false
            hiddenLoaded.value = false
            loadedModuleName.value = ""
            driverModule.value = ""
        }
    }

    fun dismissErrorDialog() {
        loadErrorDialogData.value = null
    }

    fun copyTerminalToClipboard(context: Context) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("KernelLoader Log", getTerminalText()))
            Toast.makeText(context, "Terminal log copied", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    private fun stripAnsi(s: String): String =
        s.replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "")

    private fun classifyLine(line: String): String {
        val u = line.uppercase()
        return when {
            u.contains("SUCCESS") || u.contains("ACCEPTED") || u.contains("ACTIVE NODE:") || u.contains("PERMISSIONS LOCKED") || u.contains("PAYLOAD STAGED") || line.startsWith("PASS") || u.contains("RESULT: PASS") -> "OK"
            u.contains("ERROR") || u.contains("FAILED") || u.contains("REJECTED") || line.startsWith("FAIL") || u.contains("RESULT: FAIL") -> "ERR"
            u.contains("WARN") -> "WARN"
            line.startsWith("- ") || line.startsWith("[~]") -> "CMD"
            else -> "INFO"
        }
    }

    fun initLogStore(context: Context) {
        if (logFile != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val f = File(dir, "terminal.log")
        logFile = f
        viewModelScope.launch(Dispatchers.IO) {
            try {
                f.writeText("")
            } catch (_: Exception) {
            }
        }
    }

    fun tlog(msg: String, type: String = "INFO") {
        val clean = stripAnsi(msg).trimEnd()
        if (clean.isBlank()) return
        val line = TerminalLine(clean, type)
        val runner = Runnable {
            terminalLines.add(line)
            if (terminalLines.size > 2000) {
                terminalLines.removeRange(0, terminalLines.size - 2000)
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runner.run()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post(runner)
        }
        try {
            val f = logFile ?: File(File(getApplication<Application>().filesDir, "logs").apply { mkdirs() }, "terminal.log").also {
                logFile = it
            }
            val safe = clean.replace("\n", " ").replace("\t", " ")
            f.appendText("${line.time}\t${line.type}\t$safe\n")
            if (f.length() > 1024 * 1024) {
                val keep = f.readLines().takeLast(2000).joinToString("\n") + "\n"
                f.writeText(keep)
            }
        } catch (_: Exception) {
        }
    }

    fun clearTerminal() {
        terminalLines.clear()
        try {
            logFile?.writeText("")
        } catch (_: Exception) {
        }
    }

    fun getTerminalText(): String =
        terminalLines.joinToString("\n") { it.text }

    fun runStartupChecksOnce(context: Context) {
        if (startupStarted) return
        startupStarted = true
        viewModelScope.launch {
            com.kernelloader.ui.IpLanguageManager.detectIpAndLanguage(context)
            runUpdateCheckSuspend(manual = false)
            runManifestRefreshSuspend(forceRefresh = false)
        }
    }

    fun refreshManifest(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            runManifestRefreshSuspend(forceRefresh = forceRefresh)
        }
    }

    private suspend fun runManifestRefreshSuspend(forceRefresh: Boolean) {
        manifestStatus.value = "LOADING"
        val app = getApplication<Application>()
        val m = withContext(Dispatchers.IO) { OtaDriverStore.loadManifest(app, forceRefresh = forceRefresh) }
        val (release, vm) = withContext(Dispatchers.IO) {
            val rel = (RootChecker.getKernelRelease() ?: System.getProperty("os.version") ?: "").trim()
            val verm = RootChecker.getKernelVermagic().trim()
            rel to verm
        }
        if (m != null) {
            remoteManifest.value = m
            manifestStatus.value = "OK"
            val totalCount = m.drivers.size
            tlog("Database connected ($totalCount drivers available)", "OK")
            val matched = OtaDriverStore.candidatesForEntries(m.drivers, release, vm)
            if (matched.isNotEmpty()) {
                val candidateNames = matched.map { "${it.stem()}.sh" }.distinct().joinToString(", ")
                tlog(
                    "Kernel $release supported ($candidateNames)",
                    "OK"
                )
            } else {
                tlog(
                    "Kernel $release not found in database ($totalCount checked)",
                    "WARN"
                )
            }
        } else {
            manifestStatus.value = "ERR"
            tlog("Database connection failed", "ERR")
        }
    }

    fun checkForAppUpdate(manual: Boolean = false) {
        viewModelScope.launch {
            runUpdateCheckSuspend(manual = manual)
        }
    }

    private suspend fun runUpdateCheckSuspend(manual: Boolean) {
        if (manual) updateStatus.value = "CHECKING"
        val result = withContext(Dispatchers.IO) {
            AppUpdateChecker.check(
                releasesPageUrl = BuildConfig.UPDATE_RELEASES_PAGE,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME
            )
        }
        when (result) {
            is AppUpdateChecker.UpdateCheck.Available -> {
                val info = result.info
                appUpdate.value = info
                tlog("New app update available: v${info.versionName} — Downloading...", "OK")
                updateStatus.value = "DOWNLOADING"
                updateProgress.value = 0
                val apkResult = withContext(Dispatchers.IO) {
                    AppUpdateChecker.downloadApk(getApplication(), info) { pct ->
                        updateProgress.value = pct
                    }
                }
                val apk = apkResult.getOrNull()
                if (apk != null && apk.exists()) {
                    downloadedUpdateApk = apk
                    updateStatus.value = "READY_TO_INSTALL"
                    tlog("Update v${info.versionName} ready (${apk.length() / 1024} KB) — Tap banner to install", "OK")
                } else {
                    updateStatus.value = "AVAILABLE"
                    val errReason = apkResult.exceptionOrNull()?.message ?: "Download failed"
                    tlog("Update download failed: $errReason", "ERR")
                }
            }
            is AppUpdateChecker.UpdateCheck.UpToDate -> {
                updateStatus.value = if (manual) "UP_TO_DATE" else "IDLE"
                tlog("App up to date (v${BuildConfig.VERSION_NAME})", "OK")
            }
            is AppUpdateChecker.UpdateCheck.Offline -> {
                updateStatus.value = if (manual) "ERR" else "IDLE"
                tlog("Update check offline (v${BuildConfig.VERSION_NAME})", "WARN")
            }
        }
    }

    fun installAppUpdate(context: Context) {
        val info = appUpdate.value ?: return
        if (updateStatus.value == "DOWNLOADING" || updateStatus.value == "INSTALLING") return
        viewModelScope.launch {
            var apk = downloadedUpdateApk?.takeIf { it.exists() && it.length() > 0L }
            if (apk == null) {
                updateStatus.value = "DOWNLOADING"
                updateProgress.value = 0
                tlog("[UPDATE] Downloading update v${info.versionName}...", "CMD")
                val apkResult = withContext(Dispatchers.IO) {
                    AppUpdateChecker.downloadApk(context, info) { pct ->
                        updateProgress.value = pct
                    }
                }
                apk = apkResult.getOrNull()
                if (apk == null) {
                    updateStatus.value = "ERR"
                    val reason = apkResult.exceptionOrNull()?.message ?: "Download failed"
                    tlog("[UPDATE FAIL] Where: installAppUpdate -> downloadApk (${info.apkUrl})", "ERR")
                    tlog("[UPDATE FAIL] Why: $reason", "ERR")
                    return@launch
                }
                downloadedUpdateApk = apk
                tlog("[UPDATE] Downloaded: ${apk.name} (${apk.length() / 1024} KB)", "OK")
            }
            updateStatus.value = "INSTALLING"
            tlog("[UPDATE] Installing v${info.versionName} (${apk.name})...", "CMD")
            val silentOk = withContext(Dispatchers.IO) {
                if (!RootChecker.isRootAvailable()) return@withContext false
                val tmp = "/data/local/tmp/kl_update.apk"
                val r = Shell.cmd(
                    "cp \"${apk.absolutePath}\" $tmp",
                    "chmod 644 $tmp",
                    "pm install -r -d $tmp",
                    "rm -f $tmp"
                ).exec()
                r.out.joinToString("\n").contains("Success", ignoreCase = true)
            }
            if (silentOk) {
                tlog("[UPDATE] Installed v${info.versionName} silently via root!", "OK")
                updateStatus.value = "DONE"
                return@launch
            }
            try {
                AppUpdateChecker.installApk(context, apk)
                updateStatus.value = "PROMPTED"
                tlog("[UPDATE] System package installer opened — confirm Install on screen", "OK")
            } catch (e: Exception) {
                updateStatus.value = "ERR"
                tlog("[UPDATE FAIL] Where: AppUpdateChecker.installApk (${apk.absolutePath})", "ERR")
                tlog("[UPDATE FAIL] Why: ${e.message}", "ERR")
            }
        }
    }

    fun stopLoad() {
        currentLoadJob?.cancel()
        currentLoadJob = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Shell.cmd("rm -rf /data/local/tmp/ftl /data/tmpf 2>/dev/null").exec()
            } catch (_: Exception) {
            }
            withContext(Dispatchers.Main) {
                isBusy.value = false
                busyStep.value = ""
                autoLoadStatus.value = "Stopped by user"
                autoLoadOk.value = false
                tlog("Load stopped by user", "WARN")
            }
        }
    }

    private suspend fun runPreLoadDeviceCheck(): Triple<Boolean, String, String> {
        busyStep.value = "Checking device information..."
        tlog("Checking device information...", "CMD")
        val info = withContext(Dispatchers.IO) {
            val rootOk = RootChecker.isRootAvailable()
            val release = (RootChecker.getKernelRelease() ?: System.getProperty("os.version") ?: "").trim()
            val vm = RootChecker.getKernelVermagic().trim()
            val model = android.os.Build.MODEL.orEmpty().ifBlank { "Android Device" }
            val sdk = android.os.Build.VERSION.SDK_INT
            val androidVer = android.os.Build.VERSION.RELEASE.orEmpty()
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "arm64-v8a" }
            listOf(rootOk.toString(), release, vm, model, "$androidVer (API $sdk)", abi)
        }
        val rootOk = info[0] == "true"
        val release = info[1]
        val vm = info[2]
        val model = info[3]
        val osText = info[4]
        val abi = info[5]
        tlog("Device: $model · Android $osText · $abi", "INFO")
        tlog("Kernel: ${release.ifBlank { "Unknown" }}", "INFO")
        tlog("Root access: ${if (rootOk) "Granted" else "Denied"}", if (rootOk) "OK" else "ERR")
        if (!rootOk) {
            autoLoadOk.value = false
            lastLoadError.value = "root_denied"
            autoLoadStatus.value = "Root required"
            tlog("Root permission is required to load driver", "ERR")
        }
        return Triple(rootOk, release, vm)
    }

    private fun logFilteredInstallerLine(rawLine: String) {
        val clean = stripAnsi(rawLine).trim()
        if (clean.isBlank()) return
        if (
            clean.startsWith("FTL_") ||
            clean.startsWith("###") ||
            clean.startsWith("===") ||
            clean.startsWith("---") ||
            clean.startsWith("RESULT:") ||
            clean.startsWith("NODE=") ||
            clean.startsWith("STEALTH=") ||
            clean.startsWith("IN_USE=") ||
            clean.startsWith("OWNER_PID=") ||
            clean.startsWith("module :") ||
            clean.startsWith("kernel :") ||
            clean.startsWith("root   :")
        ) {
            return
        }
        tlog(clean, classifyLine(clean))
    }

    fun autoLoadUniversal(
        context: Context,
        preferOta: Boolean = true,
        variant: String = ""
    ) {
        if (isBusy.value) return
        currentLoadJob = viewModelScope.launch {
            isBusy.value = true
            autoLoadStatus.value = "Checking device..."
            autoLoadOk.value = null
            lastLoadError.value = ""
            try {
                val (rootOk, release, vm) = runPreLoadDeviceCheck()
                if (!rootOk) return@launch

                val installer = DriverInstaller(context)
                val m = remoteManifest.value ?: withContext(Dispatchers.IO) {
                    OtaDriverStore.loadManifest(context)
                }
                val candidates = if (m != null) {
                    val cand = OtaDriverStore.candidatesForEntries(m.drivers, release, vm)
                    if (cand.isNotEmpty()) cand
                    else listOfNotNull(OtaDriverStore.exactFor(m, release, variant, vm))
                } else emptyList()

                if (candidates.isEmpty()) {
                    val localCandidates = withContext(Dispatchers.IO) { installer.candidates(release) }
                    if (localCandidates.isEmpty()) {
                        lastBundleTried.value = "none"
                        tlog("No matching driver found in database for kernel $release", "WARN")
                        tlog("Use Custom Script to load a local .sh driver", "INFO")
                        applyInstallResult(DriverInstaller.Result(ok = false, error = "no_driver_for_$release"), release)
                        return@launch
                    }
                    lastBundleTried.value = localCandidates.joinToString(", ")
                    tlog("Loading driver: ${lastBundleTried.value}...", "CMD")
                    busyStep.value = "Loading ${lastBundleTried.value}..."
                    val res = withContext(Dispatchers.IO) {
                        installer.installBest(release) { line ->
                            logFilteredInstallerLine(line)
                        }
                    }
                    applyInstallResult(res, release)
                    return@launch
                }

                lastBundleTried.value = candidates.joinToString(", ") { it.stem() }
                var finalResult = DriverInstaller.Result(ok = false, error = "download_failed")
                for (cand in candidates) {
                    busyStep.value = "Preparing ${cand.stem()}..."
                    tlog("Preparing driver: ${cand.stem()}.sh", "CMD")
                    val downloadedFile = withContext(Dispatchers.IO) {
                        OtaDriverStore.downloadDriver(context, cand) { }
                    }
                    if (downloadedFile == null || !downloadedFile.exists()) {
                        tlog("Failed to download driver: ${cand.stem()}.sh", "ERR")
                        continue
                    }

                    busyStep.value = "Loading ${cand.stem()}.sh..."
                    tlog("Loading driver into kernel...", "CMD")
                    val res = withContext(Dispatchers.IO) {
                        installer.installFile(downloadedFile) { line ->
                            logFilteredInstallerLine(line)
                        }
                    }
                    finalResult = res
                    if (res.ok) {
                        applyInstallResult(res, release)
                        break
                    }
                }

                if (!finalResult.ok) {
                    applyInstallResult(finalResult, release)
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                autoLoadOk.value = false
                lastLoadError.value = e.message ?: "error"
                autoLoadStatus.value = "Error: ${e.message}"
                tlog("Error: ${e.message}", "ERR")
            } finally {
                isBusy.value = false
                busyStep.value = ""
                currentLoadJob = null
            }
        }
    }

    private fun detectFailureLocation(err: String, rawLog: String): String {
        val u = (err + "\n" + rawLog).lowercase(Locale.US)
        return when {
            u.contains("no_driver_for") -> "Database lookup (no matching driver found)"
            u.contains("script_missing") -> "Script staging (script file missing)"
            u.contains("module_not_loaded") -> "Kernel module load (insmod)"
            u.contains("node_not_created") -> "Device node creation"
            u.contains("vermagic") || u.contains("exec format") -> "Kernel vermagic mismatch"
            u.contains("key was rejected") || u.contains("required key") || u.contains("signature") -> "Kernel module signature check"
            u.contains("unknown symbol") || u.contains("disagrees about version") -> "Kernel symbol version mismatch"
            u.contains("operation not permitted") || u.contains("permission denied") -> "Root / SELinux permission"
            else -> "Driver execution"
        }
    }

    private suspend fun applyInstallResult(res: DriverInstaller.Result, targetLabel: String) {
        if (res.ok) {
            val mod = res.module.ifBlank { DriverInstaller.MODULE }
            val node = res.node.trim()
            val isStealth = res.rawLog.contains("FTL_STEALTH=1") || res.rawLog.contains("stealth")
            val displayLabel = if (isStealth) "Stealth" else mod
            driverLoaded.value = true
            hiddenLoaded.value = isStealth
            loadedModuleName.value = displayLabel
            driverModule.value = if (node.isNotBlank()) "$displayLabel ($node)" else displayLabel
            DriverSessionManager.markLoaded(getApplication(), node, mod, isStealth)
            autoLoadOk.value = true
            autoLoadStatus.value = if (res.alreadyLoaded) {
                "Already Loaded: ${driverModule.value}"
            } else {
                "Loaded: ${driverModule.value}"
            }
            verifyStatus.value = "PASS"
            verifyDetail.value = if (node.isNotBlank()) "$displayLabel · $node" else displayLabel
            val stealthText = if (isStealth) "Stealth Mode" else "Standard Mode"
            val nodePart = if (node.isNotBlank()) " · Node: $node" else ""
            if (res.alreadyLoaded) {
                tlog("Driver already active ($stealthText$nodePart)", "OK")
            } else {
                tlog("Driver loaded successfully ($stealthText$nodePart)", "OK")
            }
        } else {
            driverLoaded.value = false
            hiddenLoaded.value = false
            autoLoadOk.value = false
            val err = res.error.ifBlank { "exit=${res.exitCode}" }
            lastLoadError.value = err
            autoLoadStatus.value = "Failed ($err)"
            val errInfo = DriverErrorInfo.fromError(err, res.rawLog)
            loadErrorDialogData.value = errInfo
            val whereFailed = detectFailureLocation(err, res.rawLog)
            tlog("Driver load failed ($targetLabel): ${errInfo.titleEn}", "ERR")
            tlog("Stage: $whereFailed", "WARN")
            withContext(Dispatchers.IO) {
                try {
                    val dmesgLines = Shell.cmd(
                        "dmesg 2>/dev/null | grep -iE 'insmod|module|sig|vermagic|disagrees|denied|avc' | tail -n 5"
                    ).exec().out.filter { it.isNotBlank() }
                    if (dmesgLines.isNotEmpty()) {
                        dmesgLines.forEach { dl -> tlog("Kernel: ${dl.trim()}", "ERR") }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    fun onFilePicked(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var displayName = "custom_driver.sh"
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && c.moveToFirst()) {
                        displayName = c.getString(idx) ?: displayName
                    }
                }
                val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val outFile = File(context.cacheDir, safeName)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                pickedLocalFile = outFile
                withContext(Dispatchers.Main) {
                    pickedFileName.value = displayName
                    tlog("Selected custom script: $displayName (${outFile.length()} bytes)", "OK")
                }
                loadPickedFile(context)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tlog("Failed to read file: ${e.message}", "ERR")
                }
            }
        }
    }

    fun clearPickedFile() {
        pickedLocalFile?.delete()
        pickedLocalFile = null
        pickedFileName.value = ""
    }

    fun loadPickedFile(context: Context) {
        val localFile = pickedLocalFile
        if (localFile == null || !localFile.exists()) {
            tlog("Select a .sh driver file first", "WARN")
            return
        }
        if (isBusy.value) return
        currentLoadJob = viewModelScope.launch {
            isBusy.value = true
            busyStep.value = "Checking device..."
            autoLoadStatus.value = "Checking device..."
            autoLoadOk.value = null
            lastLoadError.value = ""
            lastBundleTried.value = localFile.name
            try {
                val (rootOk, _) = runPreLoadDeviceCheck()
                if (!rootOk) return@launch

                busyStep.value = "Loading ${localFile.name}..."
                autoLoadStatus.value = "Loading ${localFile.name}..."
                tlog("Loading custom driver: ${localFile.name}", "CMD")
                val installer = DriverInstaller(context)
                val res = withContext(Dispatchers.IO) {
                    installer.installFile(localFile) { line ->
                        logFilteredInstallerLine(line)
                    }
                }
                if (res.ok) {
                    runCatching { localFile.delete() }
                    pickedLocalFile = null
                }
                applyInstallResult(res, localFile.name)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                autoLoadOk.value = false
                lastLoadError.value = e.message ?: "error"
                autoLoadStatus.value = "Error: ${e.message}"
                tlog("Error: ${e.message}", "ERR")
            } finally {
                isBusy.value = false
                busyStep.value = ""
                currentLoadJob = null
            }
        }
    }

    fun autoCleanupAndExit(context: Context) {
        performStealthCleanupAndExit(context)
    }

    companion object {
        fun performStealthCleanupAndExit(context: Context) {
            Thread {
                try {
                    Shell.cmd(
                        "rm -rf /data/local/tmp/ftl /data/local/tmp/kl_sig_unlock /data/tmpf /data/local/tmp/kl_update.apk /data/local/tmp/magiskboot /data/local/tmp/sigpatch /data/.km_* /dev/.kloder_session 2>/dev/null",
                        "dmesg -C 2>/dev/null",
                        "logcat -c 2>/dev/null"
                    ).exec()
                } catch (_: Exception) {
                }
                try {
                    File(context.cacheDir, "ftl").deleteRecursively()
                    context.cacheDir.listFiles()?.forEach { f ->
                        if (f.isFile && (f.name.endsWith(".sh") || f.name.endsWith(".apk"))) {
                            f.delete()
                        }
                    }
                } catch (_: Exception) {
                }
                try {
                    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                    am?.appTasks?.forEach { task ->
                        runCatching { task.finishAndRemoveTask() }
                    }
                    (context as? Activity)?.finishAndRemoveTask()
                } catch (_: Exception) {
                }
                android.os.Process.killProcess(android.os.Process.myPid())
            }.start()
        }
    }

    fun rebootDevice(context: Context? = null) {
        if (rebooting.value) return
        rebooting.value = true
        tlog("Rebooting device...", "WARN")
        val appCtx = context ?: getApplication()
        DriverSessionManager.clearSession(appCtx)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(400)
                Shell.cmd(
                    "sync",
                    "svc power reboot || setprop sys.powerctl reboot || reboot"
                ).exec()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    rebooting.value = false
                    tlog("Reboot failed: ${e.message}", "ERR")
                }
            }
        }
    }

    private fun formatVerifyLine(raw: String): Pair<String, String>? {
        val clean = raw.trim()
        if (
            clean.isBlank() ||
            clean == "---" ||
            clean.startsWith("===") ||
            clean.startsWith("module :") ||
            clean.startsWith("kernel :") ||
            clean.startsWith("root") ||
            clean.startsWith("RESULT:") ||
            clean.startsWith("NODE=") ||
            clean.startsWith("STEALTH=") ||
            clean.startsWith("IN_USE=") ||
            clean.startsWith("OWNER_PID=")
        ) {
            return null
        }
        return when {
            clean.startsWith("PASS: ") -> clean.removePrefix("PASS: ") to "OK"
            clean.startsWith("FAIL: ") -> clean.removePrefix("FAIL: ") to "ERR"
            clean.contains("SUCCESS") || clean.contains("ACTIVE") || clean.contains("OPERATIONAL") || clean.contains("ENGAGED") -> clean to "OK"
            clean.contains("FAILED") || clean.contains("ERROR") -> clean to "ERR"
            clean.startsWith("- ") -> clean to "CMD"
            else -> clean to "INFO"
        }
    }

    fun checkDriverActivity(context: Context? = null) {
        if (isBusy.value) return
        viewModelScope.launch {
            isBusy.value = true
            verifyStatus.value = "CHECKING"
            busyStep.value = "Testing driver R/W..."
            tlog("Checking active driver & IOCTL R/W...", "CMD")
            withContext(Dispatchers.IO) {
                val appCtx = context ?: getApplication()
                val (_, cachedInfo) = DriverSessionManager.isSessionActive(appCtx)
                val installer = DriverInstaller(appCtx)
                val release = (RootChecker.getKernelRelease() ?: System.getProperty("os.version") ?: "").trim()
                val defaultMod = if (release.startsWith("6.12")) DriverInstaller.ALT_MODULE else DriverInstaller.MODULE
                val targetMod = cachedInfo.second.ifBlank { defaultMod }
                val vr = installer.verifyLoadDetailed(targetMod) { line ->
                    formatVerifyLine(line)?.let { (msg, type) -> tlog(msg, type) }
                }
                if (!vr.ok) {
                    DriverSessionManager.clearSession(appCtx)
                    withContext(Dispatchers.Main) {
                        driverLoaded.value = false
                        hiddenLoaded.value = false
                        loadedModuleName.value = ""
                        driverModule.value = ""
                        verifyStatus.value = "FAIL"
                        verifyDetail.value = "NOT LOADED"
                    }
                    tlog("Driver is not loaded", "WARN")
                    return@withContext
                }

                val activeNode = vr.node?.takeIf { it.isNotBlank() } ?: installer.findNode().orEmpty()
                val displayLabel = if (vr.stealth) "Stealth" else targetMod
                DriverSessionManager.markLoaded(appCtx, activeNode.ifBlank { "dynamic" }, targetMod, vr.stealth)

                if (vr.inUse) {
                    val ownerNote = if (vr.ownerPid > 0) " (PID ${vr.ownerPid})" else ""
                    withContext(Dispatchers.Main) {
                        driverLoaded.value = true
                        hiddenLoaded.value = vr.stealth
                        loadedModuleName.value = displayLabel
                        driverModule.value = if (activeNode.isNotBlank()) "$displayLabel ($activeNode)" else displayLabel
                        verifyStatus.value = "PASS"
                        verifyDetail.value = "Active in use$ownerNote · $activeNode"
                    }
                    tlog("Node $activeNode is open by client$ownerNote (close tool first for standalone R/W test)", "OK")
                } else if (activeNode.startsWith("/dev/")) {
                    tlog("Running live IOCTL base + read test on $activeNode...", "CMD")
                    val (rwOk, detail) = installer.testReadWrite(activeNode, android.os.Process.myPid()) { line ->
                        when {
                            line.startsWith("BASE=") -> tlog("libc.so base: ${line.substringAfter("BASE=")}", "INFO")
                            line.startsWith("WRITE_OK=") -> tlog("Write/readback canary: ${line.substringAfter("WRITE_OK=")}", "OK")
                            else -> tlog(line, if (line.lowercase().contains("7f 45 4c 46")) "OK" else "INFO")
                        }
                    }
                    withContext(Dispatchers.Main) {
                        driverLoaded.value = true
                        hiddenLoaded.value = vr.stealth
                        loadedModuleName.value = displayLabel
                        driverModule.value = "$displayLabel ($activeNode)"
                        if (rwOk) {
                            verifyStatus.value = "PASS"
                            verifyDetail.value = "R/W OK · $activeNode"
                            tlog("Live R/W verified: $detail", "OK")
                        } else {
                            verifyStatus.value = "FAIL"
                            verifyDetail.value = "R/W FAILED · $activeNode"
                            tlog("Live R/W test failed: $detail", "ERR")
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        driverLoaded.value = true
                        hiddenLoaded.value = vr.stealth
                        loadedModuleName.value = displayLabel
                        driverModule.value = displayLabel
                        verifyStatus.value = "PASS"
                        verifyDetail.value = "$displayLabel · dynamic"
                    }
                    tlog("Driver active ($displayLabel · no /dev node to test)", "OK")
                }
            }
            isBusy.value = false
            busyStep.value = ""
        }
    }

    fun verifyModule() {
        if (isBusy.value) return
        viewModelScope.launch {
            isBusy.value = true
            verifyStatus.value = "CHECKING"
            verifyDetail.value = ""
            busyStep.value = "Verifying driver..."
            tlog("Verifying driver status...", "CMD")
            var passed = false
            var summaryDetail = ""
            withContext(Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    val installer = DriverInstaller(app)
                    val (_, cachedInfo) = DriverSessionManager.isSessionActive(app)
                    val release = (RootChecker.getKernelRelease() ?: System.getProperty("os.version") ?: "").trim()
                    val defaultMod = if (release.startsWith("6.12")) DriverInstaller.ALT_MODULE else DriverInstaller.MODULE
                    val targetMod = cachedInfo.second.ifBlank { defaultMod }
                    val vr = installer.verifyLoadDetailed(targetMod) { line ->
                        formatVerifyLine(line)?.let { (msg, type) -> tlog(msg, type) }
                    }
                    passed = vr.ok
                    if (passed) {
                        val activeNode = vr.node?.takeIf { it.isNotBlank() } ?: installer.findNode() ?: "dynamic"
                        val displayLabel = if (vr.stealth) "Stealth" else targetMod
                        summaryDetail = if (activeNode.isNotBlank()) "$displayLabel · $activeNode" else displayLabel
                        DriverSessionManager.markLoaded(app, activeNode, targetMod, vr.stealth)
                        withContext(Dispatchers.Main) {
                            driverLoaded.value = true
                            hiddenLoaded.value = vr.stealth
                            loadedModuleName.value = displayLabel
                            driverModule.value = if (activeNode.isNotBlank()) "$displayLabel ($activeNode)" else displayLabel
                        }
                        tlog("Driver verified active ($summaryDetail)", "OK")
                    } else {
                        summaryDetail = "NOT LOADED"
                        DriverSessionManager.clearSession(app)
                        withContext(Dispatchers.Main) {
                            driverLoaded.value = false
                            hiddenLoaded.value = false
                            loadedModuleName.value = ""
                            driverModule.value = ""
                        }
                        tlog("No active driver loaded", "WARN")
                    }
                } catch (e: Exception) {
                    tlog("Verify error: ${e.message}", "ERR")
                    summaryDetail = e.message ?: "Verification error"
                }
            }
            verifyStatus.value = if (passed) "PASS" else "FAIL"
            verifyDetail.value = summaryDetail
            isBusy.value = false
            busyStep.value = ""
        }
    }

    fun checkKernelCompatibility() {
        if (isBusy.value) return
        viewModelScope.launch {
            isBusy.value = true
            kernelCheckStatus.value = "SCANNING"
            kernelCheckIssuesCount.value = 0
            kernelCheckHasBlockers.value = false
            busyStep.value = "Auditing kernel..."
            tlog("- Starting Kernel Compatibility Audit...", "CMD")
            withContext(Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    val auditor = KernelAuditor(app)
                    val (issueCount, hasBlockers) = auditor.runAudit { msg, type ->
                        tlog(msg, type)
                    }
                    withContext(Dispatchers.Main) {
                        kernelCheckIssuesCount.value = issueCount
                        kernelCheckHasBlockers.value = hasBlockers
                        kernelCheckStatus.value = "DONE"
                    }
                } catch (e: Exception) {
                    tlog("Audit error: ${e.message}", "ERR")
                    withContext(Dispatchers.Main) {
                        kernelCheckStatus.value = "DONE"
                    }
                }
            }
            isBusy.value = false
            busyStep.value = ""
        }
    }

    fun runUserCommand(command: String) {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || isBusy.value) return
        viewModelScope.launch {
            isBusy.value = true
            busyStep.value = "Running: $trimmed"
            tlog("$ $trimmed", "CMD")
            withContext(Dispatchers.IO) {
                try {
                    val result = Shell.cmd(trimmed).exec()
                    val lines = (result.out + result.err).filter { it.isNotBlank() }
                    if (lines.isEmpty()) {
                        tlog("(exit ${result.code}, no output)", if (result.isSuccess) "OK" else "WARN")
                    } else {
                        for (l in lines) {
                            tlog(l, if (result.isSuccess) "INFO" else "ERR")
                        }
                        tlog("[exit ${result.code}]", if (result.isSuccess) "OK" else "ERR")
                    }
                } catch (e: Exception) {
                    tlog("Error: ${e.message}", "ERR")
                }
            }
            isBusy.value = false
            busyStep.value = ""
        }
    }
}
