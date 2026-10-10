package com.kernelloader.driver

import android.content.Context
import com.kernelloader.root.RootChecker
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.util.Base64
import kotlin.coroutines.coroutineContext

object UniversalKernelLoader {

    private const val TMP_KO = "/data/local/tmp/kloader_auto.ko"
    private const val TMP_UNHIDE = "/data/local/tmp/kloader_unhide"
    private const val UNHIDE_ASSET = "bin/kloder_unhide"
    private const val UNHIDE_IOCTL = 0x806

    /** exact lsmod line match, so short names cannot hit unrelated modules */
    private fun lsmodKnownRegex(candidates: List<String>): String =
        candidates.joinToString("|") { it.replace(".", "\\.") }

    fun autoLoad(context: Context, vm: DriverViewModel, variant: String = "") {
        vm.tstep("Checking superuser...")
        vm.tlog("Starting load...", "INFO")

        val rootOk = try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
        if (!rootOk) {
            vm.tlog("Root missing", "ERR")
            finish(vm, false, "Root missing - grant superuser access")
            return
        }
        vm.tlog("Root OK", "OK")

        vm.tstep("Reading device info...")
        val kernel = Shell.cmd("uname -r").exec().out.firstOrNull()?.trim() ?: "unknown"
        val arch = Shell.cmd("uname -m").exec().out.firstOrNull()?.trim() ?: "unknown"
        
        vm.tlog("Device: $kernel · $arch", "INFO")

        Shell.cmd("setenforce 0 2>/dev/null").exec()

        vm.tstep("Preparing driver...")
        val cacheFile = File(context.cacheDir, "kloader_auto.ko")
        cacheFile.delete()
        val sourceName: String
        val pickedUri = vm.pickedFileUri.value
        if (pickedUri != null) {
            sourceName = vm.pickedFileName.value ?: "picked.ko"
            vm.tlog("Source: $sourceName", "INFO")
            try {
                context.contentResolver.openInputStream(pickedUri)?.use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                } ?: run {
                    vm.tlog("Cannot open file", "ERR")
                    finish(vm, false, "File open failed")
                    return
                }
            } catch (e: Exception) {
                vm.tlog("File read error", "ERR")
                finish(vm, false, "File read failed")
                return
            }
        } else {
            val best = findBestEmbeddedDriver(context, kernel, variant)
            if (best == null) {
                vm.tlog("No driver found", "ERR")
                finish(vm, false, "No .ko found - pick a file")
                return
            }
            sourceName = best.displayName
            
            val realVerm = EmbeddedDrivers.readVermagic(context, best.filename)
            val exactCover = realVerm.isNotEmpty() &&
                    RootChecker.kernelShortVersion(realVerm) == RootChecker.kernelShortVersion(kernel)
            vm.tlog(
                "Source: $sourceName" + if (exactCover) " (exact)" else " (nearest)",
                "INFO"
            )
            try {
                context.resources.assets.open(best.filename).use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                vm.tlog("Extract failed", "ERR")
                finish(vm, false, "Driver extract failed")
                return
            }
        }

        vm.tstep("Validating .ko...")
        if (!ensureElf(cacheFile)) {
            vm.tlog("Invalid .ko file", "ERR")
            finish(vm, false, "Not a valid .ko (ELF)")
            return
        }

        val vermagic = readVermagic(cacheFile)
        if (vermagic == null) {
            vm.tlog("No vermagic - trying anyway", "WARN")
        } else {
            val vmVersion = vermagic.substringBefore(' ')
            val vmArch = vermagic.substringAfterLast(' ', "")
            if (vmVersion == kernel) {
                vm.tlog("Vermagic matches", "OK")
            } else {
                vm.tlog("Built for $vmVersion (running $kernel)", "WARN")
            }
            if (vmArch.isNotEmpty() && arch == "aarch64" && !vmArch.contains("aarch64", true)) {
                vm.tlog("Arch mismatch", "WARN")
            }
        }

        vm.tstep("Copying .ko...")
        val copy = Shell.cmd(
            "cp \"${cacheFile.absolutePath}\" $TMP_KO",
            "chmod 644 $TMP_KO",
            "chown root:root $TMP_KO 2>/dev/null"
        ).exec()
        if (!copy.isSuccess) {
            vm.tlog("Copy failed", "ERR")
            finish(vm, false, "Copy failed")
            return
        }

        val devNode = vm.preferredDevNode()

        val baselineMods = SafetyGuard.loadedModuleNames()

        val alreadyLoaded = vm.knownDriverModules().firstOrNull { it in baselineMods }
        if (alreadyLoaded != null) {
            vm.tlog("Unloading previous $alreadyLoaded before loading...", "INFO")
            Shell.cmd("rmmod $alreadyLoaded 2>/dev/null", "sleep 1").exec()
        }

        vm.tstep("Loading module...")
        Shell.cmd("setenforce 0 2>/dev/null").exec()
        var res = Shell.cmd("insmod $TMP_KO devname=$devNode").exec()
        if (!res.isSuccess) {
            res = Shell.cmd("insmod $TMP_KO devicename=$devNode").exec()
        }
        if (!res.isSuccess) {
            res = Shell.cmd("insmod $TMP_KO").exec()
        }
        if (!res.isSuccess) {
            res = Shell.cmd("insmod -f $TMP_KO devname=$devNode 2>/dev/null || insmod -f $TMP_KO 2>/dev/null").exec()
        }

        if (!res.isSuccess) {
            val errFirst = (res.out + res.err).firstOrNull { it.isNotBlank() } ?: "exit ${res.code}"
            vm.tlog("Load failed - $errFirst", "ERR")
            finish(vm, false, "Load failed: $errFirst")
            return
        }

        val loadedNow = SafetyGuard.newlyLoaded(baselineMods)
        if (loadedNow.isNotEmpty()) {
            vm.setLoadedModule(loadedNow.first())
        }
        vm.tstep("Verifying module...")
        
        val ok = verifyLoad(vm, sourceName, devNode, vm.loadedModuleName.value)
        if (ok) {
            vm.tlog("Done - ${viewModelModule(vm)} ready", "OK")
            finish(vm, true, "Loaded OK: $sourceName")

            stageForBoot(vm, context, variant, devNode)
            runCatching { Shell.cmd("rm -f $TMP_KO 2>/dev/null").exec() }
        } else {
            vm.tlog("Load failed", "ERR")
            finish(vm, false, "Load failed - details in the terminal")
        }
    }

    suspend fun unloadDriver(vm: DriverViewModel, context: Context) {
        vm.tstep("Checking superuser...")
        vm.tlog("Starting unload...", "INFO")
        val rootOk = try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
        if (!rootOk) {
            vm.tlog("Root missing", "ERR")
            finishUnload(vm, false, "Root missing - grant superuser access")
            return
        }

        val node = vm.preferredDevNode()
        val nodePath = "/dev/$node"

        vm.tstep("Reading kernel state...")
        val taintedBefore = Shell.cmd("cat /proc/sys/kernel/tainted 2>/dev/null").exec()
            .out.firstOrNull()?.trim().orEmpty()
        val nodePresent = Shell.cmd("test -e $nodePath 2>/dev/null").exec().isSuccess
        // the ladder must cover every module name this driver family can
        // register: embedded qx builds come up as "entryi", rt builds as
        // "5.10_A12", local kmem builds as kmem_337 / kmem_337_qx. The staged
        // .ko may register under yet another name — ask its modinfo and try
        // that first, so a driver the app itself installed is always unloadable.
        DriverAutoload.state(context)
        val stagedName = vm.stagedModuleName()
        var candidates = vm.knownDriverModules()
        if (stagedName.isNotBlank() && stagedName !in candidates) {
            candidates = candidates + stagedName
        }
        if (stagedName.isNotBlank()) {
            candidates = listOf(stagedName) + candidates.filter { it != stagedName }
        }
        val visible = candidates.filter { it in SafetyGuard.loadedModuleNames() }
        if (nodePresent) vm.tlog("$nodePath live", "INFO")
        if (stagedName.isNotBlank() && visible.isEmpty()) vm.tlog("Staged driver registers as '$stagedName'", "INFO")
        if (visible.isNotEmpty()) vm.tlog("lsmod: ${visible.joinToString()}", "INFO")

        if (!nodePresent && visible.isEmpty()) {
            vm.tlog("Nothing loaded - no $nodePath, no known module in lsmod", "WARN")
            DriverAutoload.state(context)
            if (DriverAutoload.enabled) {
                vm.tlog("Auto-load is ON - will reload at boot", "INFO")
            }
            vm.setLoadedModule("")
            finishUnload(vm, true, "Nothing loaded")
            return
        }

        // unhide only when the module is actually hidden: node live but
        // nothing in lsmod. A visible module rmmods directly.
        val moduleHidden = visible.isEmpty() && nodePresent
        var unhid = false
        if (moduleHidden) {
            vm.tstep("Unhiding via $nodePath...")
            unhid = unhideNode(vm, context, nodePath)
            if (!unhid) {
                // the root shell is normally allowed the ioctl; permissive
                // SELinux is the fallback path, not the default
                vm.tstep("Checking SELinux...")
                val enforce = Shell.cmd("getenforce 2>/dev/null").exec().out.firstOrNull()?.trim().orEmpty()
                if (enforce.equals("Enforcing", true)) {
                    val set = Shell.cmd("setenforce 0").exec()
                    if (set.isSuccess) {
                        vm.tlog("SELinux -> Permissive", "INFO")
                        delay(300)
                    } else {
                        val why = (set.out + set.err).firstOrNull { it.isNotBlank() }?.trim()
                            ?: "exit ${set.code}"
                        vm.tlog("setenforce 0 denied - $why", "WARN")
                    }
                    vm.tstep("Retrying unhide...")
                    unhid = unhideNode(vm, context, nodePath)
                }
            }
            if (!unhid) {
                vm.tlog("Unhide not confirmed - rmmod may reject a hidden module", "WARN")
            }
        }

        vm.tstep("Removing module...")
        // visible names first (the usual case is exactly one); the full
        // ladder is only walked when nothing is visible
        val targets = when {
            visible.isNotEmpty() -> visible
            unhid -> candidates.filter { it in SafetyGuard.loadedModuleNames() }.ifEmpty { candidates }
            else -> candidates
        }
        var rmOk = false
        var lastStderr = ""
        for (name in targets) {
            coroutineContext.ensureActive()
            // the shell is already root; an su -c wrapper would only add a
            // context re-negotiation per attempt
            val res = Shell.cmd("rmmod $name").exec()
            val err = (res.out + res.err).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (res.isSuccess) {
                vm.tlog("rmmod $name -> exit 0", "OK")
                rmOk = true
                break
            }
            if (err.isNotBlank()) lastStderr = err
            vm.tlog("rmmod $name -> exit ${res.code}" + if (err.isNotBlank()) " - $err" else "", "WARN")
        }

        vm.tstep("Verifying unload...")
        coroutineContext.ensureActive()
        var nodeGone = !Shell.cmd("test -e $nodePath 2>/dev/null").exec().isSuccess
        if (!nodeGone) {
            delay(300)
            nodeGone = !Shell.cmd("test -e $nodePath 2>/dev/null").exec().isSuccess
        }
        val leftover = Shell.cmd(
            "lsmod | grep -E '^(${lsmodKnownRegex(candidates)})[[:space:]]' 2>/dev/null"
        ).exec().out.filter { it.isNotBlank() }
        val taintedAfter = Shell.cmd("cat /proc/sys/kernel/tainted 2>/dev/null").exec()
            .out.firstOrNull()?.trim().orEmpty()
        val taintOk = taintedBefore.isBlank() || taintedAfter.isBlank() || taintedBefore == taintedAfter

        if (rmOk && nodeGone && leftover.isEmpty() && taintOk) {
            vm.tlog("Verified - node gone, lsmod clean, taint ${taintedAfter.ifBlank { "n/a" }}", "OK")
            vm.setLoadedModule("")
            DriverAutoload.state(context)
            if (DriverAutoload.enabled) {
                vm.tlog("Auto-load still ON - staged .ko and boot script untouched, will reload at boot", "WARN")
            }
            finishUnload(vm, true, "Unloaded OK")
        } else {
            val step = when {
                !rmOk -> "rmmod - all candidates rejected"
                !nodeGone -> "verify - $nodePath still present"
                leftover.isNotEmpty() ->
                    "verify - still in lsmod: ${leftover.first().trim().substringBefore(' ')}"
                else -> "verify - kernel taint ${taintedBefore.ifBlank { "?" }} -> ${taintedAfter.ifBlank { "?" }}"
            }
            vm.tlog("Unload failed at: $step", "ERR")
            if (lastStderr.isNotBlank()) vm.tlog("stderr: $lastStderr", "ERR")
            if (visible.isEmpty() && nodePresent) {
                vm.tlog("Node $nodePath answered but no known module is in lsmod - it may be loaded under an unknown name or the node is stale; a reboot clears either", "WARN")
            }
            DriverAutoload.state(context)
            if (DriverAutoload.enabled) {
                vm.tlog("Auto-load is ON - it will reload at boot", "INFO")
            }
            finishUnload(vm, false, "Unload failed - details in the terminal")
        }
    }

    private fun unhideNode(vm: DriverViewModel, context: Context, nodePath: String): Boolean {
        val bin = File(context.cacheDir, "kloder_unhide")
        try {
            context.assets.open(UNHIDE_ASSET).use { input ->
                bin.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            vm.tlog("Unhide helper missing - ${e.message ?: UNHIDE_ASSET}", "WARN")
            return false
        }
        val res = Shell.cmd(
            "cp \"${bin.absolutePath}\" $TMP_UNHIDE",
            "chmod 700 $TMP_UNHIDE",
            "chcon u:object_r:system_file:s0 $TMP_UNHIDE 2>/dev/null",
            "$TMP_UNHIDE $nodePath"
        ).exec()
        bin.delete()
        Shell.cmd("rm -f $TMP_UNHIDE 2>/dev/null").exec()
        val out = (res.out + res.err).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (res.isSuccess) {
            vm.tlog("Unhide ioctl $UNHIDE_IOCTL -> 0 ($nodePath)", "OK")
            true
        } else {
            vm.tlog("Unhide failed - ${out.ifBlank { "exit ${res.code}" }}", "WARN")
            false
        }
    }

    private fun finishUnload(vm: DriverViewModel, ok: Boolean, msg: String) {
        vm.autoLoadOk.value = ok
        vm.autoLoadStatus.value = msg
        vm.tstep("")
    }

    private fun viewModelModule(vm: DriverViewModel): String =
        vm.loadedModuleName.value.ifBlank { "driver" }

    private fun verifyLoad(
        vm: DriverViewModel,
        sourceName: String,
        expectedNode: String = "",
        moduleName: String = ""
    ): Boolean {
        vm.tstep("Verifying module...")
        Shell.cmd("setenforce 0 2>/dev/null").exec()

        // Wait with retry for device node to appear (ueventd needs time)
        var exactNode = false
        var foundNodeName = expectedNode
        for (attempt in 1..10) {
            val devList = Shell.cmd("ls /dev 2>/dev/null").exec().out.map { it.trim() }
            if (expectedNode.isNotEmpty() && devList.contains(expectedNode)) {
                exactNode = true
                foundNodeName = expectedNode
                break
            }
            val candidates = listOf("wanbai", "kmem_337", "kmem_337_qx", "entryi", "memacc", "memacc_qx", "kloaderctl", "daisyctl")
            val hit = candidates.firstOrNull { it in devList }
            if (hit != null) {
                exactNode = true
                foundNodeName = hit
                break
            }
            val mods = SafetyGuard.loadedModuleNames()
            val modHit = devList.firstOrNull { d -> mods.any { m -> d.contains(m, ignoreCase = true) } }
            if (modHit != null) {
                exactNode = true
                foundNodeName = modHit
                break
            }
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }

        if (exactNode && foundNodeName.isNotEmpty()) {
            Shell.cmd(
                "chmod 666 /dev/$foundNodeName",
                "chcon u:object_r:null_device:s0 /dev/$foundNodeName 2>/dev/null || chcon u:object_r:device:s0 /dev/$foundNodeName 2>/dev/null"
            ).exec()
            if (foundNodeName != expectedNode && expectedNode.isNotEmpty()) {
                Shell.cmd("ln -s /dev/$foundNodeName /dev/$expectedNode 2>/dev/null; chmod 666 /dev/$expectedNode 2>/dev/null").exec()
            }
            if (foundNodeName != "wanbai" && expectedNode != "wanbai") {
                Shell.cmd("ln -s /dev/$foundNodeName /dev/wanbai 2>/dev/null; chmod 666 /dev/wanbai 2>/dev/null").exec()
            }
            vm.rememberDevNode(foundNodeName)
        }

        val lsmod = Shell.cmd("lsmod").exec()
        val candidates = (listOf(moduleName) + vm.knownDriverModules()).filter { it.isNotBlank() }.distinct()
        val loadedLine = lsmod.out.drop(1).firstOrNull { line ->
            val n = line.trim().split(Regex("\\s+")).firstOrNull() ?: ""
            n.isNotBlank() && n != "Module" && n in candidates
        }
        val moduleLoaded = loadedLine != null
        val loadedName = loadedLine?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""
        if (loadedName.isNotBlank()) vm.setLoadedModule(loadedName)

        val dmesg = Shell.cmd("dmesg | grep -i -E 'kmem|kloader|entryi|vermagic|insmod' | tail -n 15").exec()

        if (moduleLoaded && exactNode) {
            vm.tlog("Verified - $loadedName ready (/dev/$foundNodeName perms 666)", "OK")
        } else if (!moduleLoaded && exactNode) {
            vm.markHiddenLoaded()
            vm.tlog("Verified - /dev/$foundNodeName ready (perms 666, module hidden)", "OK")
        } else if (moduleLoaded) {
            vm.tlog("Verified - $loadedName loaded", "OK")
        } else {
            if (!moduleLoaded) vm.tlog("Module not in lsmod", "ERR")
            if (!exactNode && expectedNode.isNotEmpty()) {
                vm.tlog("/dev/$expectedNode missing", "ERR")
            }
            dmesg.out.filter { it.isNotBlank() }.distinct().takeLast(3)
                .forEach { vm.tlog("dmesg: $it", "INFO") }
        }

        vm.setVerification(
            VerificationResult(
                lsmod = lsmod.out,
                deviceNodeFound = exactNode,
                dmesgLogs = dmesg.out,
                timestamp = vm.nowString()
            )
        )

        return moduleLoaded || exactNode
    }

    fun stageForBoot(
        vm: DriverViewModel,
        context: Context,
        variant: String,
        devNode: String,
        koFile: File = File(TMP_KO)
    ) {
        val modName = vm.loadedModuleName.value
        if (modName.isBlank()) {
            return
        }
        if (!koFile.exists()) {
            return
        }

        // Save staged driver and settings so they are ready if user decides to toggle autoload on
        DriverAutoload.state(context)
        DriverAutoload.stageDriver(context, koFile, modName, variant, devNode)

        // ONLY install the boot script and keep enabled if user had already switched autoload ON!
        if (DriverAutoload.enabled) {
            if (!DriverAutoload.hasBootRunner()) {
                vm.tlog("No boot runner (Magisk/KernelSU?)", "WARN")
                return
            }
            vm.tlog("Updating boot auto-load...", "INFO")
            val ok = DriverAutoload.enable(context, koFile, modName, variant, devNode) { m, t ->
                vm.tlog(m, t)
            }
            vm.autoloadEnabled.value = ok
        } else {
            // Autoload is OFF. Never enable it automatically!
            vm.autoloadEnabled.value = false
        }
    }

    private fun finish(vm: DriverViewModel, ok: Boolean, msg: String) {        vm.autoLoadOk.value = ok
        vm.autoLoadStatus.value = msg
        if (!ok) {
            vm.lastLoadError.value = msg
            vm.tlog("Need custom loader - tap WhatsApp", "FIX")
            runCatching { Shell.cmd("rm -f $TMP_KO 2>/dev/null").exec() }
        }
        vm.tstep("")
    }

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

    private fun extractEmbeddedKo(bytes: ByteArray): ByteArray? {
        // a huge non-ELF blob would make the base64 scan spin the CPU for
        // minutes on backtracking; real embedded builds are far smaller
        if (bytes.size > (16 shl 20)) return null
        return try {
            val text = String(bytes, Charsets.ISO_8859_1)
            var best: ByteArray? = null
            
            for (m in Regex("""[A-Za-z0-9+/=\s]{4096,}""").findAll(text)) {
                val b64 = m.value.replace(Regex("""\s"""), "")
                val cut = b64.substring(0, b64.length / 4 * 4)   
                try {
                    val decoded = Base64.getDecoder().decode(cut)
                    if (isElf(decoded) && (best == null || decoded.size > best!!.size)) best = decoded
                } catch (_: Exception) {  }
            }
            best
        } catch (e: Exception) {
            null
        }
    }

    private fun isElf(b: ByteArray): Boolean =
            b.size > 4 && b[0] == 0x7F.toByte() && b[1] == 'E'.code.toByte() &&
            b[2] == 'L'.code.toByte() && b[3] == 'F'.code.toByte()

    fun readVermagic(file: File): String? {
        val bytes = file.readBytes()
        val tag = "vermagic=".toByteArray(Charsets.US_ASCII)
        val idx = indexOf(bytes, tag) ?: return null
        var end = idx + tag.size
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        return String(bytes, idx + tag.size, end - (idx + tag.size), Charsets.US_ASCII)
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
            
            val realRelease = EmbeddedDrivers.readVermagic(context, d.filename)
            val realShort = if (realRelease.isNotEmpty()) RootChecker.kernelShortVersion(realRelease) else ""
            val dm = ver.find(if (realShort.isNotEmpty()) realShort else d.version)
            val dMajor = dm?.groupValues?.get(1)?.toIntOrNull()
            val dMinor = dm?.groupValues?.get(2)?.toIntOrNull()
            val dPatch = dm?.groupValues?.get(3)?.toIntOrNull()
            val exactShort = realShort.isNotEmpty() &&
                realShort == RootChecker.kernelShortVersion(kernelRelease)

            if (exactShort) {
                
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
                    
                    val patchBonus = if (dPatch != null && kPatch != null)
                        (255 - kotlin.math.abs(dPatch - kPatch)).coerceAtLeast(0) else 0
                    s = 600 + patchBonus
                } else if (dMajor == kMajor) {
                    
                    val minorDist = dMinor?.let { kotlin.math.abs(it - (kMinor ?: it)) } ?: 0
                    s = 300 + (255 - minorDist * 8).coerceAtLeast(0)
                } else {
                    
                    val majorDist = kotlin.math.abs(dMajor - kMajor)
                    s = (80 - majorDist * 12).coerceAtLeast(8)
                }
            }
            if (s > 0) {
                
                val realName = realRelease.lowercase()
                val runningName = kernelRelease.lowercase()
                val daisyPair =
                    realName.contains("daisy") && runningName.contains("daisy")
                if (d.type == "UNI") s += 60
                if ((d.type == "NATIVE" || d.type == "KERNEL" || d.type == "DAISY") && daisyPair) {
                    s += 200
                }
                
                if (want.isNotEmpty()) {
                    if (d.variant == want) s += 40
                    else if (d.variant.isBlank()) s += 10
                } else {
                    if (d.variant == OtaDriverStore.QX) s += 5   
                }
            }
            return s
        }

        return pool.map { it to score(it) }.filter { it.second > 0 }
                .maxByOrNull { it.second }?.first
    }
}
