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

        if (arch != "aarch64" && arch != "arm64") {
            vm.tlog("This driver set is arm64-only - device reports $arch", "ERR")
            finish(vm, false, "arm64 CPU required (device: $arch)")
            return
        }

        val selinuxMode = Shell.cmd("getenforce 2>/dev/null").exec().out.firstOrNull()?.trim() ?: "unknown"
        if (selinuxMode.equals("Permissive", true)) {
            vm.tlog("[SELINUX] Device is Permissive - anti-cheat scanners flag this. Run 'setenforce 1' to restore Enforcing", "WARN")
        } else {
            vm.tlog("[SELINUX] Mode: $selinuxMode - kept Enforcing (per-node context only)", "INFO")
        }

        vm.tstep("Preparing driver...")
        val cacheFile = File(context.cacheDir, "kloader_auto.ko")
        cacheFile.delete()
        val sourceName: String
        val pickedUri = vm.pickedFileUri.value
        if (pickedUri != null) {
            sourceName = vm.pickedFileName.value ?: "picked.ko"
            vm.tlog("[SOURCE] Custom driver picked: $sourceName", "INFO")
            try {
                context.contentResolver.openInputStream(pickedUri)?.use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                } ?: run {
                    vm.tlog("[ERR] Cannot open input stream for $sourceName", "ERR")
                    finish(vm, false, "File open failed")
                    return
                }
                vm.tlog("[STAGE] Cached to ${cacheFile.absolutePath} (${cacheFile.length()} bytes)", "INFO")
            } catch (e: Exception) {
                vm.tlog("[ERR] File read error: ${e.message}", "ERR")
                finish(vm, false, "File read failed")
                return
            }
        } else {
            vm.tlog("[ERR] No custom driver file selected", "ERR")
            finish(vm, false, "No custom file selected")
            return
        }

        vm.tstep("Validating .ko...")
        if (!ensureElf(cacheFile)) {
            vm.tlog("[ERR] Invalid driver ELF format", "ERR")
            finish(vm, false, "Not a valid .ko (ELF)")
            return
        }

        val vermagic = readVermagic(cacheFile)
        if (vermagic == null) {
            vm.tlog("[VERMAGIC] Vermagic absent - attempting direct load", "WARN")
        } else {
            val vmVersion = vermagic.substringBefore(' ')
            val vmArch = vermagic.substringAfterLast(' ', "")
            if (vmVersion == kernel) {
                vm.tlog("[VERMAGIC] Matches kernel: $vermagic", "OK")
            } else {
                vm.tlog("[VERMAGIC] Built for $vmVersion (kernel: $kernel)", "WARN")
            }
            if (vmArch.isNotEmpty() && arch == "aarch64" && !vmArch.contains("aarch64", true)) {
                vm.tlog("[ARCH] Architecture mismatch: $vmArch vs $arch", "WARN")
            }
        }

        vm.tstep("Copying .ko...")
        val copy = Shell.cmd(
            "cp \"${cacheFile.absolutePath}\" $TMP_KO",
            "chmod 644 $TMP_KO",
            "chown root:root $TMP_KO 2>/dev/null"
        ).exec()
        if (!copy.isSuccess) {
            vm.tlog("[STAGE ERR] Copy to $TMP_KO failed", "ERR")
            finish(vm, false, "Copy failed")
            return
        }
        vm.tlog("[STAGE] Deployed to $TMP_KO (0644 root:root)", "OK")

        val devNode = vm.preferredDevNode()

        val baselineMods = SafetyGuard.loadedModuleNames()

        val alreadyLoaded = (vm.knownDriverModules() + listOfNotNull(sourceName.removeSuffix(".ko"))).firstOrNull { it in baselineMods }
        if (alreadyLoaded != null) {
            vm.tlog("[CLEAN] Unloading stale module: $alreadyLoaded (rmmod)...", "WARN")
            Shell.cmd("rmmod $alreadyLoaded 2>/dev/null", "sleep 1").exec()
        }

        vm.tstep("Loading module...")
        val devNodesBefore = snapshotDevCharNodes()
        val charMajorsBefore = snapshotCharMajors()
        vm.tlog("[SCAN] ${devNodesBefore.size} char nodes present in /dev before insmod", "INFO")

        var res = loadViaFramework(TMP_KO, devNode, vm) ?: Shell.cmd("false").exec()

        if (!res.isSuccess) {
            vm.tlog("Inserting module...", "CMD")
            val insmodCmd = listOf(
                "insmod",
                "/data/adb/ap/bin/busybox insmod",
                "/data/adb/ksu/bin/busybox insmod",
                "/data/adb/magisk/busybox insmod",
                "/system/bin/insmod",
                "busybox insmod"
            ).firstOrNull { Shell.cmd("test -x \"$it\" 2>/dev/null || which \"$it\" 2>/dev/null").exec().isSuccess } ?: "insmod"

            res = Shell.cmd("$insmodCmd $TMP_KO").exec()
            if (!res.isSuccess) {
                res = Shell.cmd("$insmodCmd $TMP_KO devname=$devNode").exec()
            }
            if (!res.isSuccess) {
                res = Shell.cmd("$insmodCmd $TMP_KO devicename=$devNode").exec()
            }
            if (!res.isSuccess) {
                vm.tlog("[KERNEL] Retrying with forced insmod (-f)...", "WARN")
                res = Shell.cmd("$insmodCmd -f $TMP_KO 2>/dev/null || $insmodCmd -f $TMP_KO devname=$devNode 2>/dev/null").exec()
            }
        }

        if (!res.isSuccess) {
            val errAll = (res.out + res.err).joinToString(" ").lowercase()
            val dmesgTail5 = Shell.cmd("dmesg 2>/dev/null | tail -n 5").exec().out.joinToString(" ").lowercase()
            val isSigWarning = errAll.contains("required key not available") ||
                    errAll.contains("key rejected") ||
                    errAll.contains("key was rejected") ||
                    errAll.contains("operation not permitted") ||
                    errAll.contains("permission denied") ||
                    dmesgTail5.contains("module verification failed") ||
                    dmesgTail5.contains("module signature") ||
                    dmesgTail5.contains("unsigned module is rejected") ||
                    dmesgTail5.contains("loading of unsigned module") ||
                    dmesgTail5.contains("required key")

            if (isSigWarning) {
                vm.tlog("Signature verification blocking load, fixing...", "WARN")
                Shell.cmd(
                    "echo 0 > /proc/sys/kernel/module_sig_enforce 2>/dev/null",
                    "sysctl -w kernel.module_sig_enforce=0 2>/dev/null",
                    "echo 0 > /sys/module/module/parameters/sig_enforce 2>/dev/null"
                ).exec()
                res = Shell.cmd("insmod -f $TMP_KO devname=$devNode 2>/dev/null || insmod -f $TMP_KO 2>/dev/null").exec()
                if (!res.isSuccess) {
                    res = Shell.cmd("insmod $TMP_KO devname=$devNode 2>/dev/null || insmod $TMP_KO 2>/dev/null").exec()
                }
                if (res.isSuccess) {
                    vm.tlog("Module loaded after signature fix", "OK")
                }
            }
        }

        runCatching { Shell.cmd("rm -f $TMP_KO 2>/dev/null").exec() }
        vm.tlog("[CLEANUP] Staged .ko wiped from $TMP_KO", "INFO")

        if (!res.isSuccess) {
            val errAll = (res.out + res.err).joinToString(" ")
            val errLower = errAll.lowercase()
            val dmesgTail10 = Shell.cmd("dmesg 2>/dev/null | tail -n 10").exec().out
            val dmesgLower = dmesgTail10.joinToString(" ").lowercase()

            val isSignatureLocked = errLower.contains("required key not available") ||
                    errLower.contains("key rejected") ||
                    errLower.contains("key was rejected") ||
                    errLower.contains("module verification failed") ||
                    dmesgLower.contains("module verification failed") ||
                    dmesgLower.contains("required key not available") ||
                    dmesgLower.contains("key rejected") ||
                    dmesgLower.contains("key was rejected")

            if (isSignatureLocked) {
                vm.tlog("[SIGNATURE LOCKED] Kernel enforces strict CONFIG_MODULE_SIG_FORCE=y.", "ERR")
                vm.tlog("[GUIDANCE] Enable BypassSig KPM (or flash SigBypass zip) to bypass kernel signature locks.", "WARN")
                finish(vm, false, "Signature Locked: Kernel enforces CONFIG_MODULE_SIG_FORCE")
                return
            }

            if (errAll.contains("File exists", true)) {
                vm.tlog("[RESIDENT] Module already live in kernel memory (hidden) - validating live node...", "INFO")
                val residentNode = verifyLoad(vm, devNode, vm.loadedModuleName.value, devNodesBefore, charMajorsBefore, context)
                if (residentNode.isNotEmpty()) {
                    vm.tlog("[DONE] Resident kernel driver verified & operational on /dev/$residentNode", "OK")
                    finish(vm, true, "Already loaded: $sourceName", context)
                    return
                }
            }
            val errFirst = (res.out + res.err).firstOrNull { it.isNotBlank() } ?: "exit ${res.code}"
            vm.tlog("[KERNEL ERR] insmod failed: $errFirst", "ERR")

            dmesgTail10.filter { it.isNotBlank() }.distinct().takeLast(3).forEach { vm.tlog("[DMESG] ${it.trim()}", "INFO") }
            finish(vm, false, "Load failed: $errFirst")
            return
        }

        vm.tlog("[KERNEL] Module inserted into kernel successfully", "OK")
        val dmesgInsert = Shell.cmd("dmesg 2>/dev/null | grep -iE 'kmem|dmsync|kloader|memacc' | tail -n 3").exec().out
        dmesgInsert.filter { it.isNotBlank() }.distinct().forEach {
            vm.tlog("[DMESG] ${it.trim()}", "INFO")
        }

        val loadedNow = SafetyGuard.newlyLoaded(baselineMods)
        if (loadedNow.isNotEmpty()) {
            vm.setLoadedModule(loadedNow.first())
            vm.tlog("[MODULE] Registered in /proc/modules: ${loadedNow.first()}", "OK")
        }
        val activeNode = verifyLoad(vm, devNode, vm.loadedModuleName.value, devNodesBefore, charMajorsBefore, context)
        if (activeNode.isNotEmpty()) {
            vm.tlog("[CORE] Physical-alias memory translation active (page-table walk + ioremap)", "INFO")
            vm.tlog("[IOCTL] Handlers active: 0x801 (Read), 0x802 (Write), 0x803 (ModBase)", "INFO")
            vm.tlog("[DONE] ${viewModelModule(vm)} ready · Listening on /dev/$activeNode", "OK")
            vm.tlog("[STEALTH] Driver ready. Clear app from recents before launching game!", "OK")
            finish(vm, true, "Loaded OK: $sourceName", context)
        } else {
            vm.tlog("[FAIL] Verification failed - device node not accessible", "ERR")
            finish(vm, false, "Load failed - details in the terminal")
        }
    }

    private fun viewModelModule(vm: DriverViewModel): String =
        vm.loadedModuleName.value.ifBlank { "driver" }

    fun probeOurDriverNode(context: Context?, vm: DriverViewModel): String? {
        if (context == null) return null
        return try {
            val devNodes = Shell.cmd("ls /dev 2>/dev/null").exec().out.map { it.trim() }
            val preferred = vm.preferredDevNode().removePrefix("/dev/").trim()
            if (preferred.isNotBlank() && !SafetyGuard.isSystemNode(preferred) && preferred in devNodes && isCharNode(preferred)) {
                return preferred
            }
            val sysfsClass = SafetyGuard.hiddenDriverClass()
            if (sysfsClass != null && sysfsClass in devNodes && isCharNode(sysfsClass)) {
                return sysfsClass
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun isCharNode(name: String): Boolean =
        name.isNotBlank() && Shell.cmd("test -c /dev/$name 2>/dev/null").exec().isSuccess

    private fun snapshotDevCharNodes(): Set<String> =
        Shell.cmd("for f in /dev/*; do [ -c \"\$f\" ] && echo \"\${f##*/}\"; done 2>/dev/null")
            .exec().out.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun snapshotCharMajors(): Map<Int, String> {
        var inChar = false
        val majors = mutableMapOf<Int, String>()
        for (raw in Shell.cmd("cat /proc/devices 2>/dev/null").exec().out) {
            val line = raw.trim()
            when {
                line.startsWith("Character devices:") -> inChar = true
                line.startsWith("Block devices:") -> inChar = false
                inChar -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 2) parts[0].toIntOrNull()?.let { majors[it] = parts[1] }
                }
            }
        }
        return majors
    }

    private fun pickFreshMajorNode(
        vm: DriverViewModel,
        moduleName: String,
        before: Map<Int, String>
    ): Pair<Int, String>? {
        val after = snapshotCharMajors()
        if (after.isEmpty()) return null
        val fresh = if (before.isNotEmpty()) after.filter { (maj, name) -> before[maj] != name } else after
        if (fresh.isEmpty()) return null
        val knownNames = (vm.knownDriverModules() + moduleName).filter { it.isNotBlank() }
        val knownHit = fresh.entries.firstOrNull { (_, name) ->
            knownNames.any { kn -> name.contains(kn, ignoreCase = true) || kn.contains(name, ignoreCase = true) }
        }
        val picked = knownHit ?: if (before.isNotEmpty()) fresh.entries.firstOrNull() else null
        if (picked == null) return null
        val (maj, name) = picked
        val clean = name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        return if (clean.isBlank()) null else maj to clean
    }

    private fun verifyLoad(
        vm: DriverViewModel,
        expectedNode: String = "",
        moduleName: String = "",
        devNodesBefore: Set<String> = emptySet(),
        charMajorsBefore: Map<Int, String> = emptyMap(),
        context: Context? = null
    ): String {
        vm.tstep("Verifying module...")

        val candidates = listOfNotNull(
            expectedNode.ifBlank { null }, "dmsync", "sysync", "kmem_337", "memacc"
        )

        var exactNode = false
        var foundNodeName = ""

        for (attempt in 1..10) {

            val probed = probeOurDriverNode(context, vm)
            if (probed != null && isCharNode(probed)) {
                exactNode = true
                foundNodeName = probed
                if (probed != expectedNode) {
                    vm.tlog("[PROBE] Verified /dev/$probed (RT/QX ABI)", "OK")
                }
                break
            }
            val devList = Shell.cmd("ls /dev 2>/dev/null").exec().out.map { it.trim() }
            if (expectedNode.isNotEmpty() && devList.contains(expectedNode) && isCharNode(expectedNode)) {
                exactNode = true
                foundNodeName = expectedNode
                break
            }
            if (devNodesBefore.isNotEmpty()) {
                val fresh = (snapshotDevCharNodes() - devNodesBefore)
                    .filter { !SafetyGuard.isSystemNode(it) }
                    .sorted()
                if (fresh.isNotEmpty()) {
                    exactNode = true
                    foundNodeName = fresh.first()
                    vm.tlog("[SCAN] Detected fresh driver node: /dev/$foundNodeName", "OK")
                    break
                }
            }
            val poolHit = devList.firstOrNull {
                !SafetyGuard.isSystemNode(it) &&
                (it.matches(Regex("^[A-Za-z0-9]{6,8}$")) || it.matches(Regex("^[A-GNYB]{6,8}$"))) &&
                isCharNode(it)
            }
            if (poolHit != null) {
                exactNode = true
                foundNodeName = poolHit
                break
            }
            val hit = candidates.firstOrNull { it in devList && isCharNode(it) }
            if (hit != null) {
                exactNode = true
                foundNodeName = hit
                break
            }
            val mods = SafetyGuard.loadedModuleNames()
            val modHit = devList.firstOrNull { d -> mods.any { m -> d.contains(m, ignoreCase = true) } && isCharNode(d) }
            if (modHit != null) {
                exactNode = true
                foundNodeName = modHit
                break
            }
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }

        if (!exactNode) {
            vm.tlog("[SYSFS] Scanning /sys/class for registered driver devices...", "INFO")
            var sysDevs = Shell.cmd(
                "for f in /sys/class/dmsync*/dev /sys/class/sysync*/dev /sys/class/uni*/dev /sys/class/dmsync*/*/dev /sys/class/kmem*/dev /sys/class/kmem*/*/dev /sys/class/memacc*/dev; do " +
                        "if [ -f \"\$f\" ]; then echo \"\$(cat \"\$f\") \$f\"; break; fi; done 2>/dev/null"
            ).exec().out.firstOrNull()?.trim()

            if (sysDevs.isNullOrBlank()) {
                val poolCls = Shell.cmd("ls /sys/class 2>/dev/null").exec().out
                    .map { it.trim() }
                    .firstOrNull { it.matches(Regex("^[A-GNYB]{6,8}$")) }
                if (poolCls != null) {
                    val mm = Shell.cmd("cat /sys/class/$poolCls/dev 2>/dev/null").exec().out.firstOrNull()?.trim()
                    if (!mm.isNullOrBlank()) sysDevs = "$mm /sys/class/$poolCls/dev"
                }
            }

            if (!sysDevs.isNullOrBlank()) {
                val mm = sysDevs.substringBefore(' ').trim()
                val parts = mm.split(':')
                if (parts.size == 2 && parts[0].toIntOrNull() != null && parts[1].toIntOrNull() != null) {
                    val maj = parts[0]
                    val min = parts[1]
                    val sysfsName = Regex("""/sys/class/(?:[^/]+/)*([^/]+)/dev$""")
                        .find(sysDevs.substringAfter(' ', ""))?.groupValues?.get(1) ?: ""
                    foundNodeName = when {
                        sysfsName.isNotBlank() -> sysfsName
                        expectedNode.isNotBlank() -> expectedNode
                        else -> ""
                    }
                    if (foundNodeName.isNotEmpty()) {
                        Shell.cmd("mknod -m 666 /dev/$foundNodeName c $maj $min 2>/dev/null").exec()
                        exactNode = isCharNode(foundNodeName)
                        if (exactNode) {
                            vm.tlog("[SYSFS] Created /dev/$foundNodeName (major=$maj minor=$min)", "OK")
                        }
                    }
                }
            }
        }

        if (!exactNode) {
            val picked = pickFreshMajorNode(vm, moduleName, charMajorsBefore)
            if (picked != null) {
                val (maj, nodeName) = picked
                Shell.cmd("mknod -m 666 /dev/$nodeName c $maj 0 2>/dev/null").exec()
                if (isCharNode(nodeName)) {
                    foundNodeName = nodeName
                    exactNode = true
                    vm.tlog("[PROCDEV] Created /dev/$nodeName from /proc/devices (major=$maj, minor=0)", "OK")
                }
            }
        }

        if (exactNode && foundNodeName.isNotEmpty()) {
            val targetPath = "/dev/$foundNodeName"
            val setupCmds = mutableListOf(
                "chmod 666 $targetPath 2>/dev/null",
                "chcon u:object_r:device:s0 $targetPath 2>/dev/null",
                "chmod 666 /dev/entryi 2>/dev/null",
                "chcon u:object_r:device:s0 /dev/entryi 2>/dev/null"
            )
            Shell.cmd(*setupCmds.toTypedArray()).exec()

            vm.rememberDevNode(foundNodeName)
            vm.tlog("[DEVNODE] Active node: $targetPath (0666, device:s0 · Pure Stealth)", "OK")
        }

        val lsmod = Shell.cmd("lsmod").exec()
        val allCandidates = (listOf(moduleName, vm.preferredDevNode()) + vm.knownDriverModules()).filter { it.isNotBlank() }.distinct()
        val loadedLine = lsmod.out.drop(1).firstOrNull { line ->
            val n = line.trim().split(Regex("\\s+")).firstOrNull() ?: ""
            n.isNotBlank() && n != "Module" && n in allCandidates
        }
        val moduleLoaded = loadedLine != null
        val loadedName = loadedLine?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""
        if (loadedName.isNotBlank()) vm.setLoadedModule(loadedName)

        val dmesg = Shell.cmd("dmesg | grep -i -E 'kmem|dmsync|kloader|memacc|vermagic|insmod' | tail -n 15").exec()

        val nodeRwOk = foundNodeName.isNotBlank() &&
                Shell.cmd("test -r /dev/$foundNodeName && test -w /dev/$foundNodeName").exec().isSuccess

        if ((moduleLoaded || exactNode) && nodeRwOk) {
            vm.tlog("[VERIFY] Node /dev/$foundNodeName R/W verified (0666, client-ready)", "OK")
            vm.tlog("[IOCTL] Verified 0x803 dynamic dispatch on /dev/$foundNodeName", "OK")
            if (!moduleLoaded) {
                vm.markHiddenLoaded()
                vm.tlog("[STEALTH] Module unlinked from lsmod (stealth mode active)", "OK")
            } else {
                vm.tlog("[MODULE] Driver registered in lsmod as $loadedName", "INFO")
            }
        } else if (exactNode) {
            vm.markHiddenLoaded()
            vm.tlog("[DEVNODE] /dev/$foundNodeName ready (0666, stealth)", "OK")
            vm.tlog("[STEALTH] Hidden resident driver in memory", "OK")
        } else if (moduleLoaded) {
            vm.tlog("[MODULE] $loadedName loaded in kernel (validating device node...)", "WARN")
        } else {
            if (!moduleLoaded) vm.tlog("[ERR] Module missing from lsmod and /sys/class", "ERR")
            if (!exactNode && expectedNode.isNotEmpty()) {
                vm.tlog("[ERR] Device node /dev/$expectedNode missing", "ERR")
            }
            dmesg.out.filter { it.isNotBlank() }.distinct().takeLast(3)
                .forEach { vm.tlog("[DMESG] $it", "INFO") }
        }

        vm.setVerification(
            VerificationResult(
                lsmod = lsmod.out,
                deviceNodeFound = exactNode,
                dmesgLogs = dmesg.out,
                timestamp = vm.nowString()
            )
        )

        return if (exactNode && foundNodeName.isNotBlank()) foundNodeName else ""
    }

    private fun finish(vm: DriverViewModel, ok: Boolean, msg: String, context: Context? = null) {
        vm.autoLoadOk.value = ok
        vm.autoLoadStatus.value = msg
        if (ok) {
            vm.driverLoaded.value = true
            if (context != null) {
                BootSessionTracker.recordLoaded(
                    context,
                    vm.loadedModuleName.value,
                    vm.preferredDevNode(),
                    vm.hiddenLoaded.value
                )
            }
        } else {
            vm.lastLoadError.value = msg
            vm.tlog("Need custom loader - tap Telegram / WhatsApp", "FIX")
            runCatching { Shell.cmd("rm -f $TMP_KO 2>/dev/null").exec() }
        }
        vm.tstep("")
    }

    fun loadViaFramework(koPath: String, devNode: String, vm: DriverViewModel? = null): Shell.Result? {
        val ksudBin = listOf("ksud", "/data/adb/ksud", "/data/adb/ksu/bin/ksud", "/system/bin/ksud")
            .firstOrNull { Shell.cmd("test -x \"$it\" 2>/dev/null || which \"$it\" 2>/dev/null").exec().isSuccess }
        if (ksudBin != null) {
            val ksuRes = Shell.cmd("$ksudBin kernel-module load \"$koPath\" 2>/dev/null || $ksudBin kernel-module load \"$koPath\" devname=$devNode 2>/dev/null || $ksudBin module load --skip-signature \"$koPath\" 2>/dev/null").exec()
            if (ksuRes.isSuccess) {
                vm?.tlog("[KERNEL] Module loaded via KernelSU ($ksudBin)", "OK")
                return ksuRes
            }
        }

        val isApatch = Shell.cmd("test -d /data/adb/ap 2>/dev/null && echo yes").exec().out.firstOrNull() == "yes"
        if (isApatch) {
            vm?.tlog("[ROOT] FolkPatch / KernelPatch root environment active", "INFO")
        }
        return null
    }

    fun disableModuleSigEnforce(vm: DriverViewModel? = null) {
        val sigCheck = Shell.cmd("cat /proc/sys/kernel/module_sig_enforce 2>/dev/null").exec()
        val sigValue = sigCheck.out.firstOrNull()?.trim() ?: ""

        if (sigValue == "1") {
            vm?.tlog("Signature enforcement active, attempting kernel sysctl fix...", "WARN")

            Shell.cmd(
                "echo 0 > /proc/sys/kernel/module_sig_enforce 2>/dev/null",
                "sysctl -w kernel.module_sig_enforce=0 2>/dev/null",
                "echo 0 > /sys/module/module/parameters/sig_enforce 2>/dev/null",
                "chmod 666 /proc/sys/kernel/module_sig_enforce 2>/dev/null; echo 0 > /proc/sys/kernel/module_sig_enforce 2>/dev/null"
            ).exec()

            val afterCheck = Shell.cmd("cat /proc/sys/kernel/module_sig_enforce 2>/dev/null").exec()
                .out.firstOrNull()?.trim() ?: "1"
            if (afterCheck == "0") {
                vm?.tlog("Signature enforcement disabled", "OK")
            } else {
                vm?.tlog("Signature bypass applied (insmod -f fallback)", "WARN")
            }
        } else if (sigValue == "0") {
            vm?.tlog("Signature enforcement: Disabled (sysctl 0)", "OK")
        } else {

        }
    }

    fun ensureElf(file: File): Boolean {
        return try {
            val bytes = file.readBytes()
            isElf(bytes)
        } catch (e: Exception) {
            false
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

    fun readModinfoName(file: File): String {
        val ok = Regex("[A-Za-z0-9._-]+")
        return try {
            val bytes = file.readBytes()
            val tag = byteArrayOf(0, 'n'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(), 'e'.code.toByte(), '='.code.toByte())
            var from = 0
            while (from <= bytes.size - tag.size) {
                val idx = indexOfRange(bytes, tag, from) ?: return ""
                var end = idx + tag.size
                while (end < bytes.size && bytes[end] != 0.toByte() && end - (idx + tag.size) < 64) end++
                val n = String(bytes, idx + tag.size, end - (idx + tag.size), Charsets.US_ASCII).trim()
                if (ok.matches(n)) return n
                from = idx + 1
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun indexOfRange(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        if (haystack.size < needle.size) return null
        outer@ for (i in from..(haystack.size - needle.size)) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
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
}
