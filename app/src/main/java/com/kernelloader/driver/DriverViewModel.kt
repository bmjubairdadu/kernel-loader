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
    var sigPatchStatus = mutableStateOf("IDLE")
    var verifyStatus = mutableStateOf("IDLE")
    var verifyDetail = mutableStateOf("")
    var loadErrorDialogData = mutableStateOf<DriverErrorInfo?>(null)

    val terminalLines = mutableStateListOf<TerminalLine>()

    private var pickedLocalFile: File? = null
    private var logFile: File? = null
    private var currentLoadJob: Job? = null
    private var downloadedUpdateApk: File? = null

    init {
        initLogStore(application)
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
            line.startsWith("FTL_OK=1") || line.startsWith("PASS") || u.contains("RESULT: PASS") || u.contains("SUCCESS") || u.contains("[OK]") -> "OK"
            line.startsWith("FTL_ERROR=") && line != "FTL_ERROR=" -> "ERR"
            line.startsWith("FAIL") || u.contains("RESULT: FAIL") || u.contains("ERROR") || u.contains("FAIL") || u.contains("REJECTED") -> "ERR"
            line.startsWith("###") || line.startsWith("FTL_") -> "CMD"
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
                if (f.exists() && terminalLines.isEmpty()) {
                    val allRaw = f.readLines()
                    val filteredRaw = allRaw.filter { raw ->
                        !raw.contains("ipwho", ignoreCase = true) &&
                            !raw.contains("ipapi", ignoreCase = true) &&
                            !raw.contains("Detecting IP", ignoreCase = true) &&
                            !raw.contains("Country IP", ignoreCase = true) &&
                            !raw.contains("IP Auto", ignoreCase = true) &&
                            !raw.contains("=====") &&
                            !raw.contains("=== SCRIPT & DRIVER VERIFICATION") &&
                            !Regex("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b").containsMatchIn(raw)
                    }
                    if (filteredRaw.size != allRaw.size) {
                        f.writeText(
                            if (filteredRaw.isEmpty()) ""
                            else filteredRaw.takeLast(2000).joinToString("\n") + "\n"
                        )
                    }
                    val restored = filteredRaw.takeLast(2000).mapNotNull { raw ->
                        val parts = raw.split("\t", limit = 3)
                        if (parts.size == 3) TerminalLine(parts[2], parts[1], parts[0]) else null
                    }
                    withContext(Dispatchers.Main) {
                        if (terminalLines.isEmpty()) {
                            terminalLines.addAll(restored)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            checkPersistedSigStatus()
        }
    }

    private fun checkPersistedSigStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!RootChecker.isRootAvailable()) return@launch
                val prop = Shell.cmd("cat /data/adb/kernel_loader/status.prop 2>/dev/null")
                    .exec().out.joinToString("\n")
                if (prop.contains("sig_unlocked=true")) {
                    withContext(Dispatchers.Main) {
                        if (sigPatchStatus.value == "IDLE") {
                            sigPatchStatus.value = "DONE"
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun tlog(msg: String, type: String = "INFO") {
        val clean = stripAnsi(msg)
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
        terminalLines.joinToString("\n") { "- [${it.type}] ${it.text}" }

    fun refreshManifest() {
        viewModelScope.launch {
            manifestStatus.value = "LOADING"
            val app = getApplication<Application>()
            val m = withContext(Dispatchers.IO) { OtaDriverStore.loadManifest(app) }
            val release = withContext(Dispatchers.IO) {
                (RootChecker.getKernelRelease() ?: System.getProperty("os.version") ?: "").trim()
            }
            if (m != null) {
                remoteManifest.value = m
                manifestStatus.value = "OK"
                val totalCount = m.drivers.size
                tlog("[DATABASE] Connected to online database ($totalCount drivers available)", "OK")
                val matched = OtaDriverStore.candidatesForEntries(m.drivers, release)
                if (matched.isNotEmpty()) {
                    tlog(
                        "[DATABASE] Kernel $release is SUPPORTED -> Matching driver: ${matched.joinToString(", ") { "${it.stem()}.sh" }}",
                        "OK"
                    )
                } else {
                    tlog(
                        "[DATABASE] Kernel $release is NOT SUPPORTED in database ($totalCount drivers checked)",
                        "WARN"
                    )
                }
            } else {
                manifestStatus.value = "ERR"
                tlog("[DATABASE FAIL] Could not load database manifest from remote or cache", "ERR")
            }
        }
    }

    fun checkForAppUpdate(manual: Boolean = false) {
        viewModelScope.launch {
            if (manual) updateStatus.value = "CHECKING"
            tlog("[UPDATE] Checking for new version (current: v${BuildConfig.VERSION_NAME})...", "INFO")
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
                    tlog(
                        "[UPDATE] New version available: v${info.versionName} (build ${info.versionCode}) — Auto-downloading...",
                        "OK"
                    )
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
                        tlog(
                            "[UPDATE] v${info.versionName} auto-downloaded (${apk.length() / 1024} KB) — Tap INSTALL to install!",
                            "OK"
                        )
                    } else {
                        updateStatus.value = "AVAILABLE"
                        val errReason = apkResult.exceptionOrNull()?.message ?: "Download failed"
                        tlog("[UPDATE FAIL] Where: APK Auto-Download (${info.apkUrl})", "ERR")
                        tlog("[UPDATE FAIL] Why: $errReason", "ERR")
                    }
                }
                is AppUpdateChecker.UpdateCheck.UpToDate -> {
                    updateStatus.value = if (manual) "UP_TO_DATE" else "IDLE"
                    tlog("[UPDATE] App is up to date (v${BuildConfig.VERSION_NAME})", "OK")
                }
                is AppUpdateChecker.UpdateCheck.Offline -> {
                    updateStatus.value = if (manual) "ERR" else "IDLE"
                    tlog(
                        "[UPDATE] Update check unreachable/offline (current: v${BuildConfig.VERSION_NAME})",
                        "WARN"
                    )
                }
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

    fun autoLoadUniversal(
        context: Context,
        preferOta: Boolean = true,
        variant: String = ""
    ) {
        if (isBusy.value) return
        currentLoadJob = viewModelScope.launch {
            isBusy.value = true
            autoLoadStatus.value = "Loading..."
            autoLoadOk.value = null
            lastLoadError.value = ""
            try {
                val release = withContext(Dispatchers.IO) {
                    (RootChecker.getKernelRelease() ?: System.getProperty("os.version") ?: "").trim()
                }
                val vm = withContext(Dispatchers.IO) {
                    RootChecker.getKernelVermagic().trim()
                }
                val installer = DriverInstaller(context)
                val m = remoteManifest.value ?: withContext(Dispatchers.IO) {
                    OtaDriverStore.loadManifest(context)
                }
                val candidates = if (m != null) {
                    val cand = OtaDriverStore.candidatesForEntries(m.drivers, release)
                    if (cand.isNotEmpty()) cand
                    else listOfNotNull(OtaDriverStore.exactFor(m, release, variant, vm))
                } else emptyList()

                tlog("Starting driver installation...", "CMD")
                tlog("Kernel release : $release", "INFO")

                if (candidates.isEmpty()) {
                    val localCandidates = withContext(Dispatchers.IO) { installer.candidates(release) }
                    if (localCandidates.isEmpty()) {
                        lastBundleTried.value = "none"
                        tlog("[DATABASE] No matching driver found in database for kernel $release", "WARN")
                        tlog("Hint: Use 'CUSTOM .SH SCRIPT' to load a custom driver from storage.", "INFO")
                        applyInstallResult(DriverInstaller.Result(ok = false, error = "no_driver_for_$release"), release)
                        return@launch
                    }
                    lastBundleTried.value = localCandidates.joinToString(", ")
                    busyStep.value = "Running driver_load.sh..."
                    val res = withContext(Dispatchers.IO) {
                        installer.installBest(release) { line ->
                            tlog(line, classifyLine(line))
                        }
                    }
                    applyInstallResult(res, release)
                    if (res.ok) {
                        busyStep.value = "Verifying driver..."
                        withContext(Dispatchers.IO) {
                            installer.verifyLoad(res.module.ifBlank { DriverInstaller.MODULE }) { line ->
                                tlog(line, classifyLine(line))
                            }
                        }
                    }
                    return@launch
                }

                lastBundleTried.value = candidates.joinToString(", ") { it.stem() }
                tlog("Database candidate(s) : ${lastBundleTried.value}", "INFO")

                var finalResult = DriverInstaller.Result(ok = false, error = "download_failed")
                for (cand in candidates) {
                    busyStep.value = "Downloading ${cand.stem()}..."
                    tlog("Fetching driver from database: ${cand.file}...", "CMD")
                    val downloadedFile = withContext(Dispatchers.IO) {
                        OtaDriverStore.downloadDriver(context, cand) { progress ->
                            tlog(progress, if (progress.contains("failed", true) || progress.contains("error", true)) "ERR" else "INFO")
                        }
                    }
                    if (downloadedFile == null || !downloadedFile.exists()) {
                        tlog("Failed to download driver: ${cand.stem()}", "ERR")
                        continue
                    }

                    busyStep.value = "Running driver_load.sh..."
                    tlog("Executing driver installer...", "CMD")
                    val res = withContext(Dispatchers.IO) {
                        installer.installFile(downloadedFile) { line ->
                            tlog(line, classifyLine(line))
                        }
                    }
                    finalResult = res
                    if (res.ok) {
                        applyInstallResult(res, release)
                        busyStep.value = "Verifying driver..."
                        withContext(Dispatchers.IO) {
                            installer.verifyLoad(res.module.ifBlank { DriverInstaller.MODULE }) { line ->
                                tlog(line, classifyLine(line))
                            }
                        }
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
                tlog("ERROR: ${e.message}", "ERR")
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
            u.contains("no_driver_for") -> "Database Candidate Lookup (online database — no matching driver found)"
            u.contains("script_missing") -> "Script Staging (/data/local/tmp/ftl — script file missing)"
            u.contains("module_not_loaded") -> "Kernel Module Load (driver_load.sh -> insmod -> /proc/modules)"
            u.contains("node_not_created") -> "Device Node Creation (/sys/class -> /dev/<6-char> node missing)"
            u.contains("vermagic") || u.contains("exec format") -> "Kernel insmod Vermagic Check (module vermagic mismatch)"
            u.contains("key was rejected") || u.contains("required key") || u.contains("signature") -> "Kernel Module Signature Check (CONFIG_MODULE_SIG enforced)"
            u.contains("unknown symbol") || u.contains("disagrees about version") -> "Kernel Symbol CRC / Kallsyms Resolution"
            u.contains("operation not permitted") || u.contains("permission denied") -> "Root / SELinux / Security Hook Check"
            else -> "Script Execution (/data/local/tmp/ftl/driver_load.sh)"
        }
    }

    private suspend fun applyInstallResult(res: DriverInstaller.Result, targetLabel: String) {
        if (res.ok) {
            val mod = res.module.ifBlank { DriverInstaller.MODULE }
            val node = res.node.trim()
            val isStealth = res.rawLog.contains("FTL_STEALTH=1")
            driverLoaded.value = true
            hiddenLoaded.value = isStealth
            loadedModuleName.value = mod
            driverModule.value = if (node.isNotBlank()) "$mod ($node)" else mod
            autoLoadOk.value = true
            autoLoadStatus.value = "Loaded: ${driverModule.value}"
            verifyStatus.value = "PASS"
            verifyDetail.value = "$mod · $node"
            tlog(
                "LOADED: module=$mod dev=${res.devName} major=${res.major} node=$node already=${res.alreadyLoaded}",
                "OK"
            )
        } else {
            driverLoaded.value = false
            hiddenLoaded.value = false
            autoLoadOk.value = false
            val err = res.error.ifBlank { "exit=${res.exitCode}" }
            lastLoadError.value = err
            autoLoadStatus.value = "FAILED ($err)"
            val errInfo = DriverErrorInfo.fromError(err, res.rawLog)
            loadErrorDialogData.value = errInfo
            val whereFailed = detectFailureLocation(err, res.rawLog)
            tlog("[FAILED] $targetLabel", "ERR")
            tlog("Where: $whereFailed", "ERR")
            tlog("Error: $err (code: ${res.exitCode})", "ERR")
            tlog("Reason: ${errInfo.titleBn} (${errInfo.titleEn})", "ERR")
            tlog("Details: ${errInfo.descriptionBn}", "WARN")
            tlog("Solution: ${errInfo.solutionStepsBn.joinToString(" | ")}", "FIX")
            withContext(Dispatchers.IO) {
                try {
                    val dmesgLines = Shell.cmd(
                        "dmesg 2>/dev/null | grep -iE 'suvhwvwrtnb|devddgy|insmod|module|sig|vermagic|disagrees|denied|avc' | tail -n 8"
                    ).exec().out.filter { it.isNotBlank() }
                    if (dmesgLines.isNotEmpty()) {
                        tlog("Kernel dmesg log:", "WARN")
                        dmesgLines.forEach { dl -> tlog("  $dl", "ERR") }
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
            tlog("Pick a .sh file first", "WARN")
            return
        }
        if (isBusy.value) return
        currentLoadJob = viewModelScope.launch {
            isBusy.value = true
            busyStep.value = "Running ${localFile.name}..."
            autoLoadStatus.value = "Loading ${localFile.name}..."
            autoLoadOk.value = null
            lastLoadError.value = ""
            lastBundleTried.value = localFile.name
            try {
                tlog("Loading custom script: ${localFile.name}", "CMD")
                val installer = DriverInstaller(context)
                val res = withContext(Dispatchers.IO) {
                    installer.installFile(localFile) { line ->
                        tlog(line, classifyLine(line))
                    }
                }
                applyInstallResult(res, localFile.name)
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                autoLoadOk.value = false
                lastLoadError.value = e.message ?: "error"
                autoLoadStatus.value = "Error: ${e.message}"
                tlog("ERROR: ${e.message}", "ERR")
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
                        "rm -rf /data/local/tmp/ftl /data/tmpf 2>/dev/null",
                        "logcat -c 2>/dev/null"
                    ).exec()
                } catch (_: Exception) {
                }
                try {
                    File(context.cacheDir, "ftl").deleteRecursively()
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

    fun refreshDriverState(context: Context? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!RootChecker.isRootAvailable()) {
                    withContext(Dispatchers.Main) {
                        driverLoaded.value = false
                        hiddenLoaded.value = false
                        driverModule.value = ""
                        loadedModuleName.value = ""
                    }
                    return@launch
                }
                val installer = DriverInstaller(context ?: getApplication())
                val mainLoaded = installer.isLoaded(DriverInstaller.MODULE)
                val altLoaded = if (!mainLoaded) installer.isLoaded(DriverInstaller.ALT_MODULE) else false
                val node = installer.findNode()
                withContext(Dispatchers.Main) {
                    if (mainLoaded || altLoaded || !node.isNullOrBlank()) {
                        val mod = when {
                            mainLoaded -> DriverInstaller.MODULE
                            altLoaded -> DriverInstaller.ALT_MODULE
                            else -> DriverInstaller.MODULE
                        }
                        driverLoaded.value = true
                        hiddenLoaded.value = !mainLoaded && !altLoaded && !node.isNullOrBlank()
                        loadedModuleName.value = mod
                        driverModule.value = if (!node.isNullOrBlank()) "$mod ($node)" else mod
                    } else {
                        driverLoaded.value = false
                        hiddenLoaded.value = false
                        loadedModuleName.value = ""
                        driverModule.value = ""
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun checkDriverActivity(context: Context? = null) {
        viewModelScope.launch {
            isBusy.value = true
            busyStep.value = "Checking driver..."
            tlog("Checking driver status...", "CMD")
            withContext(Dispatchers.IO) {
                val appCtx = context ?: getApplication()
                val installer = DriverInstaller(appCtx)
                val mainLoaded = installer.isLoaded(DriverInstaller.MODULE)
                val altLoaded = if (!mainLoaded) installer.isLoaded(DriverInstaller.ALT_MODULE) else false
                val node = installer.findNode()
                if (!node.isNullOrBlank()) {
                    tlog("Active node found: $node", "OK")
                    installer.client(node, "ping") { line ->
                        tlog(line, classifyLine(line))
                    }
                } else {
                    tlog("No active 6-char device node in /dev", "WARN")
                }
                withContext(Dispatchers.Main) {
                    if (mainLoaded || altLoaded || !node.isNullOrBlank()) {
                        val mod = if (altLoaded) DriverInstaller.ALT_MODULE else DriverInstaller.MODULE
                        driverLoaded.value = true
                        loadedModuleName.value = mod
                        driverModule.value = if (!node.isNullOrBlank()) "$mod ($node)" else mod
                        tlog("Driver status: ACTIVE (${driverModule.value})", "OK")
                    } else {
                        driverLoaded.value = false
                        loadedModuleName.value = ""
                        driverModule.value = ""
                        tlog("Driver status: NOT LOADED", "ERR")
                    }
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
            busyStep.value = "Running verify_load.sh..."
            var passed = false
            var summaryDetail = ""
            withContext(Dispatchers.IO) {
                try {
                    val app = getApplication<Application>()
                    val installer = DriverInstaller(app)
                    val release = (RootChecker.getKernelRelease() ?: System.getProperty("os.version") ?: "").trim()
                    val targetMod = if (release.startsWith("6.12")) DriverInstaller.ALT_MODULE else DriverInstaller.MODULE
                    val (ok, node) = installer.verifyLoad(targetMod) { line ->
                        if (line != "---" && !line.startsWith("===")) {
                            val type = when {
                                line.startsWith("PASS") || line.contains("RESULT: PASS") || line.startsWith("NODE=") -> "OK"
                                line.startsWith("FAIL") || line.contains("RESULT: FAIL") -> "ERR"
                                else -> "INFO"
                            }
                            tlog(line, type)
                        }
                    }
                    passed = ok
                    if (passed) {
                        val activeNode = node ?: installer.findNode() ?: "dynamic"
                        summaryDetail = "$targetMod · $activeNode"
                        tlog("Status: Driver & script are working properly", "OK")
                    } else {
                        summaryDetail = "NOT LOADED"
                        tlog("Status: Driver or Script is NOT loaded", "WARN")
                        tlog("Hint: Tap LOAD above to install driver", "INFO")
                    }
                } catch (e: Exception) {
                    tlog("Verify failed: ${e.message}", "ERR")
                    summaryDetail = e.message ?: "Verification error"
                }
            }
            verifyStatus.value = if (passed) "PASS" else "FAIL"
            verifyDetail.value = summaryDetail
            refreshDriverState()
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

    fun patchKernelSignature(context: Context) {
        if (isBusy.value) return
        viewModelScope.launch {
            isBusy.value = true
            sigPatchStatus.value = "PATCHING"
            busyStep.value = "Patching boot image signature check..."
            tlog("Kernel signature bypass started", "CMD")
            tlog("Unpacking boot.img & patching module_sig_check...", "INFO")
            try {
                val (ok, detail) = withContext(Dispatchers.IO) {
                    UniversalKernelLoader.autoUnlockSignatureStandaloneDetail(context, this@DriverViewModel)
                }
                if (ok) {
                    val alreadyPatched = detail.contains("ALREADY_PATCHED")
                    sigPatchStatus.value = "DONE"
                    if (alreadyPatched) {
                        tlog("Boot partition is ALREADY patched & verified", "OK")
                        Toast.makeText(context, "Signature Verification is already disabled!", Toast.LENGTH_LONG).show()
                    } else {
                        tlog("Boot image patched & verified on flash! Rebooting in 3 seconds...", "OK")
                        Toast.makeText(context, "Signature Bypass Applied! Rebooting in 3s...", Toast.LENGTH_LONG).show()
                        delay(3000)
                        rebootDevice(context)
                    }
                } else {
                    sigPatchStatus.value = "FAILED"
                    val reason = detail.lineSequence().lastOrNull { it.isNotBlank() } ?: "Unknown error"
                    tlog("Signature bypass failed: $reason", "ERR")
                    tlog("Where: Boot partition unpack / module_sig_check binary patch", "ERR")
                    detail.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                        tlog("Stage: $line", "WARN")
                    }
                    Toast.makeText(context, "Signature Bypass Failed: $reason", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                sigPatchStatus.value = "FAILED"
                tlog("Signature bypass error: ${e.message}", "ERR")
            } finally {
                isBusy.value = false
                busyStep.value = ""
            }
        }
    }
}
