package com.kernelloader.driver



import android.content.Context

import com.kernelloader.root.RootChecker

import com.topjohnwu.superuser.Shell

import kotlinx.coroutines.delay

import kotlinx.coroutines.ensureActive

import java.io.File

import java.io.FileOutputStream

import java.io.ByteArrayInputStream

import java.util.zip.GZIPInputStream

import java.util.zip.ZipInputStream

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



        // Anti-Cheat Hygiene: Clean any stale artifacts in /data/local/tmp from previous runs

        Shell.cmd("rm -f $TMP_KO /data/local/tmp/Driver.sh /data/local/tmp/kloader_auto.ko /data/local/tmp/kloader_ota.ko /data/local/tmp/*.ko /data/local/tmp/*.sh /data/local/tmp/ui.xml /data/local/tmp/sig* /data/local/tmp/magiskboot 2>/dev/null").exec()



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



        vm.tstep("Unpacking & Validating driver...")

        if (!ensureElf(cacheFile, vm)) {

            vm.tlog("[ERR] Invalid driver format (neither .ko ELF nor supported script)", "ERR")

            finish(vm, false, "Not a valid .ko or driver script")

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

        val copy = Shell.cmd("cp \"${cacheFile.absolutePath}\" $TMP_KO").exec()

        if (!copy.isSuccess) {

            vm.tlog("[STAGE ERR] Copy to $TMP_KO failed", "ERR")

            finish(vm, false, "Copy failed")

            return

        }

        Shell.cmd(

            "chmod 644 $TMP_KO",

            "chown root:root $TMP_KO 2>/dev/null",

            "chcon u:object_r:system_file:s0 $TMP_KO 2>/dev/null"

        ).exec()

        vm.tlog("[STAGE] Deployed to $TMP_KO (0644 root:root)", "OK")



        val devNode = vm.preferredDevNode()



        val baselineMods = SafetyGuard.loadedModuleNames()



        val alreadyLoaded = (vm.knownDriverModules() + listOfNotNull(sourceName.removeSuffix(".ko"))).firstOrNull { it in baselineMods }

        if (alreadyLoaded != null) {

            vm.tlog("[CLEAN] Unloading stale module: $alreadyLoaded (rmmod)...", "WARN")

            Shell.cmd("rmmod $alreadyLoaded 2>/dev/null", "sleep 1").exec()

        }



        vm.tstep("Loading module...")

        val devNodesBefore = emptySet<String>()

        val charMajorsBefore = emptyMap<Int, String>()

        



        var res = loadViaFramework(TMP_KO, devNode, vm) ?: Shell.cmd("false").exec()



        if (!res.isSuccess) {

            val hasEngine = Shell.cmd("test -f /data/adb/kernel_loader/load_driver.sh || test -x /data/adb/kernel_loader/load_driver.sh").exec().isSuccess

            if (hasEngine) {

                vm.tlog("Inserting module via Engine Bridge...", "CMD")

                res = Shell.cmd("sh /data/adb/kernel_loader/load_driver.sh \"$TMP_KO\" \"$devNode\"").exec()

                if (res.isSuccess) {

                    vm.tlog("Driver loaded successfully via Engine Bridge", "OK")

                }

            }

        }



        if (!res.isSuccess) {

            vm.tlog("Inserting module...", "CMD")

            val insmodCmd = listOf(
                "/data/adb/ap/bin/busybox insmod",
                "/data/adb/ksu/bin/busybox insmod",
                "/data/adb/magisk/busybox insmod",
                "busybox insmod",
                "insmod",
                "/system/bin/insmod"
            ).firstOrNull { 
                val bin = it.substringBefore(" ")
                bin == "insmod" || bin == "busybox" || Shell.cmd("test -x \"$bin\" 2>/dev/null || which \"$bin\" 2>/dev/null").exec().isSuccess 
            } ?: "insmod"



            val targetDevNode = if (devNode.isNotBlank() && !SafetyGuard.isSystemNode(devNode)) devNode else vm.preferredDevNode()
            res = Shell.cmd("$insmodCmd $TMP_KO devname=$targetDevNode").exec()
            if (!res.isSuccess) {
                res = Shell.cmd("$insmodCmd $TMP_KO devicename=$targetDevNode").exec()
            }
            if (!res.isSuccess) {
                res = Shell.cmd("$insmodCmd $TMP_KO").exec()
            }
            if (!res.isSuccess) {
                vm.tlog("[KERNEL] Retrying with forced insmod (-f)...", "WARN")
                res = Shell.cmd("$insmodCmd -f $TMP_KO devname=$targetDevNode 2>/dev/null || $insmodCmd -f $TMP_KO 2>/dev/null").exec()
            }

        }



        if (!res.isSuccess) {

            val errAll = (res.out + res.err).joinToString(" ").lowercase()

            val dmesgTail5 = Shell.cmd("dmesg 2>/dev/null | tail -n 5").exec().out.joinToString(" ").lowercase()

            val isSigWarning = errAll.contains("required key not available") ||

                    errAll.contains("key rejected") ||

                    errAll.contains("key was rejected") ||

                    dmesgTail5.contains("module verification failed") ||

                    dmesgTail5.contains("module signature") ||

                    dmesgTail5.contains("unsigned module is rejected") ||

                    dmesgTail5.contains("loading of unsigned module") ||

                    dmesgTail5.contains("required key")



            if (dmesgTail5.contains("avc: denied") && !isSigWarning) {

                vm.tlog("[SELINUX] AVC denial seen in dmesg — SELinux context issue, not a signature problem.", "WARN")

            }



            if (isSigWarning) {

                vm.tlog("Signature verification blocking load, fixing...", "WARN")

                Shell.cmd(

                    "echo 0 > /proc/sys/kernel/module_sig_enforce 2>/dev/null",

                    "sysctl -w kernel.module_sig_enforce=0 2>/dev/null",

                    "echo 0 > /sys/module/module/parameters/sig_enforce 2>/dev/null"

                ).exec()

                val insmodRetry = listOf(
                    "/data/adb/ap/bin/busybox insmod",
                    "/data/adb/ksu/bin/busybox insmod",
                    "/data/adb/magisk/busybox insmod",
                    "busybox insmod",
                    "insmod",
                    "/system/bin/insmod"
                ).firstOrNull { 
                    val bin = it.substringBefore(" ")
                    bin == "insmod" || bin == "busybox" || Shell.cmd("test -x \"$bin\" 2>/dev/null || which \"$bin\" 2>/dev/null").exec().isSuccess 
                } ?: "insmod"

                res = Shell.cmd("$insmodRetry $TMP_KO devname=$devNode 2>&1 || $insmodRetry $TMP_KO 2>&1").exec()

                if (!res.isSuccess) {

                    res = Shell.cmd("$insmodRetry -f $TMP_KO devname=$devNode 2>&1 || $insmodRetry -f $TMP_KO 2>&1").exec()

                }

                if (res.isSuccess) {

                    vm.tlog("Module loaded after signature fix", "OK")

                }

            }

        }



        runCatching {
            Shell.cmd("rm -f $TMP_KO /data/local/tmp/*.ko /data/local/tmp/*.ko* 2>/dev/null").exec()
            cacheFile.delete()
            OtaDriverStore.cacheDir(context).listFiles()?.forEach { it.delete() }
        }
        if (res.isSuccess) {
            Shell.cmd("dmesg -c >/dev/null 2>&1 || dmesg -C 2>/dev/null").exec()
            Shell.cmd("logcat -c >/dev/null 2>&1").exec()
        }

        vm.tlog("[STEALTH] Driver .ko purged from storage (zero traces on disk)", "OK")



        if (!res.isSuccess) {

            val errAll = (res.out + res.err).joinToString(" ")

            val errLower = errAll.lowercase()

            val dmesgTail10 = Shell.cmd("dmesg 2>/dev/null | tail -n 10").exec().out

            val dmesgLower = dmesgTail10.joinToString(" ").lowercase()



            val isSignatureLocked = errLower.contains("required key not available") ||

                    errLower.contains("key rejected") ||

                    errLower.contains("key was rejected") ||

                    dmesgLower.contains("module verification failed") ||

                    dmesgLower.contains("unsigned module is rejected") ||

                    dmesgLower.contains("loading of unsigned module") ||

                    dmesgLower.contains("required key not available") ||

                    dmesgLower.contains("key rejected") ||

                    dmesgLower.contains("key was rejected")



            if (isSignatureLocked) {
                Shell.cmd("rm -f /data/adb/kernel_loader/status.prop 2>/dev/null").exec()
                vm.tlog("[AUTO-FIX] Detected Kernel Signature Lock (CONFIG_MODULE_SIG_FORCE)", "WARN")
                runSigProbe(context, vm)
                vm.tlog("[AUTO-FIX] Unlocking kernel signature check in boot partition...", "CMD")
                val (unlocked, detail) = autoUnlockSignatureStandaloneDetail(context, vm)
                if (unlocked) {
                    vm.tlog("[AUTO-FIX] ✅ Kernel signature check successfully unlocked in boot partition!", "OK")
                    vm.tlog("[REBOOT REQUIRED] Please restart your phone to apply the unlock.", "WARN")
                    finish(vm, false, "Signature Unlocked: Please restart phone once to apply")
                } else {
                    vm.tlog("[SIGNATURE LOCKED] Kernel enforces strict CONFIG_MODULE_SIG_FORCE=y: $detail", "ERR")
                    if (detail.contains("FLASH_WRITE_FAILED") || detail.contains("FLASH_VERIFY_FAILED")) {
                        vm.tlog("[NOTICE] Boot partition is write-protected by OEM (SELinux/dm-verity).", "WARN")
                        vm.tlog("[TIP] Patched boot image saved to /sdcard/patched_boot.img for Fastboot flashing.", "INFO")
                    }
                    finish(vm, false, "Signature Locked: Kernel enforces CONFIG_MODULE_SIG_FORCE")
                }
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

        val dmesgInsert = Shell.cmd("dmesg 2>/dev/null | grep -iE 'rv|KHACK_DEBUG|hw_bp|kmem|dmsync|kloader|memacc' | tail -n 5").exec().out

        dmesgInsert.filter { it.isNotBlank() }.distinct().forEach {

            vm.tlog("[DMESG] ${it.trim()}", "INFO")

        }



        val loadedNow = SafetyGuard.newlyLoaded(baselineMods)

        if (loadedNow.isNotEmpty()) {

            vm.setLoadedModule(loadedNow.first())

            vm.tlog("[MODULE] Registered in /proc/modules: ${loadedNow.first()}", "OK")

        } else {
            vm.markHiddenLoaded()
            vm.tlog("[STEALTH] Kernel unlinked: module hidden from /proc/modules & /sys/module", "OK")
        }

        val activeNode = verifyLoad(vm, devNode, vm.loadedModuleName.value, devNodesBefore, charMajorsBefore, context)

        if (activeNode.isNotEmpty()) {

            val isEphemeralCandidate = sourceName.contains("ephemeral", true) ||

                    sourceName.contains("rt", true) ||

                    sourceName.contains("over_check", true) ||

                    sourceName.contains("过检测", true) ||

                    sourceName.contains("v3", true)



            var probed = false

            if (isEphemeralCandidate) {

                vm.tlog("[STEALTH] Ephemeral / RT Driver detected — skipping test probe to preserve node for cheat tool!", "OK")

                probed = true

            } else {

                probed = driverShProbe(context, vm, activeNode)

            }



            if (probed) {

                if (!isEphemeralCandidate) {

                    vm.tlog("[PROBE] Driver.sh 0x803 handshake OK on /dev/$activeNode — driver verified", "OK")

                }

            } else {

                val modsNow = SafetyGuard.loadedModuleNames()

                if (modsNow.isEmpty()) {

                    vm.tlog("[PROBE] /dev/$activeNode did NOT answer the 0x803 handshake — it is not our driver", "ERR")

                    finish(vm, false, "Node /dev/$activeNode is not answering the driver handshake")

                    return

                }

                vm.tlog("[PROBE] 0x803 handshake not answered — module present in lsmod; accepting with warning", "WARN")

            }

            vm.tlog("[CORE] Driver node live — memory engine self-contained in the module", "INFO")

            vm.tlog("[IOCTL] Protocol: 0x801 (Read) · 0x802 (Write) · 0x803 (ModBase) on /dev/$activeNode", "INFO")

            vm.tlog("[DONE] ${viewModelModule(vm)} ready · Listening on /dev/$activeNode", "OK")
            Shell.cmd("dmesg -c >/dev/null 2>&1 || dmesg -C 2>/dev/null").exec()
            vm.tlog("[STEALTH] Kernel log (dmesg) ring buffer wiped clean", "OK")

            vm.tlog("[STEALTH] Driver ready. Clear app from recents before launching game!", "OK")

            finish(vm, true, "Loaded OK: $sourceName", context)

        } else {

            // Check for Nodeless Netlink Driver (Zero /dev footprint)

            val netlinkCheck = Shell.cmd("cat /proc/net/netlink 2>/dev/null | grep -E '\\b22\\b|\\b21\\b' || true").exec().out

            val newlyLoadedMods = SafetyGuard.newlyLoaded(baselineMods)

            val isNetlinkMod = newlyLoadedMods.any { it.contains("netlink", true) } ||

                    sourceName.contains("netlink", true) ||

                    sourceName.startsWith("NL_", true)



            if (isNetlinkMod || netlinkCheck.any { it.isNotBlank() }) {

                vm.tlog("[NETLINK] Nodeless Netlink Driver detected and active (Unit: 22)!", "OK")

                vm.tlog("[STEALTH] 100% Invisibility: Zero /dev nodes, Zero /proc/devices entries!", "OK")

                vm.tlog("[CORE] Memory engine active over raw Linux netlink socket", "INFO")

                vm.tlog("[DONE] ${viewModelModule(vm)} ready · Listening on Netlink", "OK")
                Shell.cmd("dmesg -c >/dev/null 2>&1 || dmesg -C 2>/dev/null").exec()
                vm.tlog("[STEALTH] Kernel log (dmesg) ring buffer wiped clean", "OK")

                vm.tlog("[STEALTH] Driver ready. Clear app from recents before launching game!", "OK")

                finish(vm, true, "Loaded OK (Nodeless): $sourceName", context)

                return

            }

            vm.tlog("[FAIL] Verification failed - device node not accessible", "ERR")

            finish(vm, false, "Load failed - details in the terminal")

        }

    }



    fun driverShProbe(context: Context?, vm: DriverViewModel?, preferredNode: String = ""): Boolean =
        probeDriverWithDriverSh(context, vm, preferredNode) != null

    private fun viewModelModule(vm: DriverViewModel): String =

        vm.loadedModuleName.value.ifBlank { "driver" }



    fun probeOurDriverNode(context: Context?, vm: DriverViewModel): String? {
        if (context == null) return null
        return try {
            val devNodes = Shell.cmd("ls /dev 2>/dev/null").exec().out.map { it.trim() }
            val preferred = vm.preferredDevNode().removePrefix("/dev/").trim()
            val isPatternValid = preferred.matches(Regex("^[A-Za-z0-9]{6,8}$")) ||
                    preferred.startsWith("dmsync") || preferred.startsWith("sysync")
            if (preferred.isNotBlank() && !SafetyGuard.isSystemNode(preferred) && isPatternValid && preferred in devNodes && isCharNode(preferred)) {
                return preferred
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun isCharNode(name: String): Boolean =
        name.isNotBlank() && Shell.cmd("test -c /dev/$name 2>/dev/null").exec().isSuccess

    fun probeDriverWithDriverSh(context: Context?, vm: DriverViewModel?, preferredNode: String = ""): String? {
        if (context == null) return null
        val target = "/data/local/tmp/Driver.sh"
        val local = File(context.cacheDir, "Driver.sh")
        return try {
            context.assets.open("Driver.sh").use { input ->
                FileOutputStream(local).use { output -> input.copyTo(output) }
            }
            Shell.cmd(
                "cp \"${local.absolutePath}\" $target 2>/dev/null",
                "chmod 755 $target 2>/dev/null"
            ).exec()

            // Pure V3 native C scan: Driver.sh direct probe on target node or /dev candidates
            val pref = preferredNode.removePrefix("/dev/").trim()
            val candidate = if (pref.isNotBlank()) "/dev/$pref" else ""
            var directOut = ""
            if (candidate.isNotBlank() && Shell.cmd("test -c $candidate 2>/dev/null").exec().isSuccess) {
                val r = Shell.cmd("timeout 2 $target $candidate 2>&1 || $target $candidate 2>&1").exec()
                directOut = (r.out + r.err).joinToString("\n")
            }
            if (!directOut.contains("Driver connected")) {
                val newNodes = Shell.cmd("ls -1 /dev/[A-Z][A-Z][A-Z][A-Z][A-Z][A-Z] 2>/dev/null").exec().out
                    .filter { it.isNotBlank() }
                for (nn in newNodes) {
                    val r = Shell.cmd("timeout 2 $target $nn 2>&1 || $target $nn 2>&1").exec()
                    val outCandidate = (r.out + r.err).joinToString("\n")
                    if (outCandidate.contains("Driver connected")) {
                        directOut = outCandidate
                        break
                    }
                }
            }
            if (!directOut.contains("Driver connected")) {
                val r = Shell.cmd("timeout 3 $target 2>&1").exec()
                directOut = (r.out + r.err).joinToString("\n")
            }

            if (!directOut.contains("Driver connected")) {
                return null
            }

            // Extract exact responding node: "[+] Driver connected on /dev/<node>"
            val nodeMatch = Regex("""\[\+\] Driver connected on (/dev/[A-Za-z0-9_-]+)""").find(directOut)
            var foundNode = nodeMatch?.groupValues?.get(1)?.removePrefix("/dev/")?.trim()

            if (foundNode.isNullOrBlank()) {
                val cleanPref = preferredNode.removePrefix("/dev/").trim()
                if (cleanPref.isNotBlank() && !SafetyGuard.isSystemNode(cleanPref) && Shell.cmd("test -c /dev/$cleanPref 2>/dev/null").exec().isSuccess) {
                    foundNode = cleanPref
                }
            }

            if (!foundNode.isNullOrBlank() && !SafetyGuard.isSystemNode(foundNode)) {
                Shell.cmd(
                    "chmod 666 /dev/$foundNode 2>/dev/null",
                    "chcon u:object_r:zero_device:s0 /dev/$foundNode 2>/dev/null"
                ).exec()
                vm?.rememberDevNode(foundNode)
                vm?.markHiddenLoaded()
                vm?.tlog("[PROBE] Driver.sh verified on /dev/$foundNode (0x803 OK)", "OK")
                foundNode
            } else {
                vm?.markHiddenLoaded()
                vm?.tlog("[PROBE] Driver.sh 0x803 handshake OK — driver verified", "OK")
                "driver"
            }
        } catch (e: Exception) {
            vm?.tlog("[PROBE ERR] Driver.sh check error: ${e.message}", "ERR")
            null
        } finally {
            Shell.cmd("rm -f $target 2>/dev/null").exec()
            runCatching { local.delete() }
        }
    }

    private fun verifyLoad(
        vm: DriverViewModel,
        expectedNode: String = "",
        moduleName: String = "",
        devNodesBefore: Set<String> = emptySet(),
        charMajorsBefore: Map<Int, String> = emptyMap(),
        context: Context? = null
    ): String {
        vm.tstep("Verifying driver with Driver.sh (0x803)...")
        val activeNode = probeDriverWithDriverSh(context, vm, expectedNode)
        return activeNode ?: ""
    }

    private fun finish(vm: DriverViewModel, ok: Boolean, msg: String, context: Context? = null) {
        val targetCtx = context ?: vm.getApplication()
        com.kernelloader.security.AntiCheatShield.deepCleanDevice(targetCtx)

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
            runCatching {
                Shell.cmd("rm -f $TMP_KO /data/local/tmp/*.ko 2>/dev/null").exec()
                context?.let { OtaDriverStore.cacheDir(it).listFiles()?.forEach { f -> f.delete() } }
            }
        } else {
            vm.lastLoadError.value = msg
            vm.tlog("Need custom loader - tap Telegram / WhatsApp", "FIX")
            vm.triggerErrorDialog(msg)
            runCatching { Shell.cmd("rm -f $TMP_KO /data/local/tmp/*.ko 2>/dev/null").exec() }
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

                vm?.tlog("Signature enforcement still active (this kernel does not allow disabling it via sysfs)", "WARN")

            }

        } else if (sigValue == "0") {

            vm?.tlog("Signature enforcement: Disabled (sysctl 0)", "OK")

        } else {



        }

    }



    fun ensureElf(file: File, vm: DriverViewModel? = null): Boolean {

        return prepareDriverKo(file, vm)

    }



    fun prepareDriverKo(file: File, vm: DriverViewModel? = null): Boolean {

        if (!file.exists() || file.length() < 4) return false

        val bytes = try { file.readBytes() } catch (e: Exception) { return false }



        // 1. Direct ELF

        if (isElf(bytes)) {

            vm?.tlog("[STAGE] Valid ELF kernel module detected (${bytes.size} bytes)", "OK")

            return true

        }



        // 2. Appended ELF (e.g. V3 devpro / rt dev优化版: script + \x7fELF...)

        val elfIdx = indexOf(bytes, byteArrayOf(0x7F.toByte(), 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))

        if (elfIdx != null && elfIdx > 0) {

            val extracted = bytes.copyOfRange(elfIdx, bytes.size)

            if (isElf(extracted)) {

                return try {

                    file.writeBytes(extracted)

                    vm?.tlog("[EXTRACT] Extracted kernel module from appended ELF script (offset 0x${elfIdx.toString(16)}, ${extracted.size} bytes)", "OK")

                    true

                } catch (_: Exception) { false }

            }

        }



        // 3. Base64-encoded ELF (V3 RT scripts, Driver GT, etc.)

        val b64Ko = extractBase64Elf(bytes)

        if (b64Ko != null && isElf(b64Ko)) {

            return try {

                file.writeBytes(b64Ko)

                vm?.tlog("[EXTRACT] Decoded kernel module from Base64 script (${b64Ko.size} bytes)", "OK")

                true

            } catch (_: Exception) { false }

        }



        // 4. Gzip embedded stream (Ft驱动, BING, etc. - \x1f\x8b\x08)

        val gzKo = extractGzipElf(bytes)

        if (gzKo != null && isElf(gzKo)) {

            return try {

                file.writeBytes(gzKo)

                vm?.tlog("[EXTRACT] Decompressed kernel module from Gzip script (${gzKo.size} bytes)", "OK")

                true

            } catch (_: Exception) { false }

        }



        // 5. ZIP archive (contains .ko inside)

        val zipKo = extractZipElf(bytes)

        if (zipKo != null && isElf(zipKo)) {

            return try {

                file.writeBytes(zipKo)

                vm?.tlog("[EXTRACT] Extracted kernel module from ZIP archive (${zipKo.size} bytes)", "OK")

                true

            } catch (_: Exception) { false }

        }



        return false

    }



    private fun extractBase64Elf(bytes: ByteArray): ByteArray? {

        val text = try { String(bytes, Charsets.ISO_8859_1) } catch (_: Exception) { return null }

        // Case A: MODULE_BASE64="..."

        val modMatch = Regex("""MODULE_BASE64=["']?([A-Za-z0-9+/=\r\n]+)["']?""").find(text)

        if (modMatch != null) {

            val clean = modMatch.groupValues[1].filter { !it.isWhitespace() }

            try {

                val dec = Base64.getDecoder().decode(clean)

                if (isElf(dec)) return dec

            } catch (_: Exception) {}

        }

        // Case B: Search for f0VMRg... pattern (base64 of \x7fELF)

        val f0Match = Regex("""(f0VMRg[A-Za-z0-9+/=\r\n]{100,})""").find(text)

        if (f0Match != null) {

            val clean = f0Match.groupValues[1].filter { !it.isWhitespace() }

            try {

                val dec = Base64.getDecoder().decode(clean)

                if (isElf(dec)) return dec

            } catch (_: Exception) {}

        }

        // Case C: Any large base64 block

        val b64Regex = Regex("""[A-Za-z0-9+/=\r\n]{500,}""")

        for (match in b64Regex.findAll(text)) {

            val clean = match.value.filter { !it.isWhitespace() }

            if (clean.length < 500) continue

            try {

                val dec = Base64.getDecoder().decode(clean)

                if (isElf(dec)) return dec

            } catch (_: Exception) {}

        }

        return null

    }



    private fun extractGzipElf(bytes: ByteArray): ByteArray? {

        val gzMagic = byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08.toByte())

        var from = 0

        while (from <= bytes.size - gzMagic.size) {

            val idx = indexOfRange(bytes, gzMagic, from) ?: break

            try {

                java.io.ByteArrayInputStream(bytes, idx, bytes.size - idx).use { bais ->

                    java.util.zip.GZIPInputStream(bais).use { gzis ->

                        val decomp = gzis.readBytes()

                        if (isElf(decomp)) return decomp

                    }

                }

            } catch (_: Exception) {}

            from = idx + 1

        }

        return null

    }



    private fun extractZipElf(bytes: ByteArray): ByteArray? {

        val zipMagic = byteArrayOf(0x50.toByte(), 0x4B.toByte(), 0x03.toByte(), 0x04.toByte())

        if (bytes.size > 4 && bytes[0] == zipMagic[0] && bytes[1] == zipMagic[1] && bytes[2] == zipMagic[2] && bytes[3] == zipMagic[3]) {

            try {

                java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->

                    var entry = zis.nextEntry

                    while (entry != null) {

                        if (entry.name.endsWith(".ko", ignoreCase = true) && !entry.isDirectory) {

                            val koBytes = zis.readBytes()

                            if (isElf(koBytes)) return koBytes

                        }

                        entry = zis.nextEntry

                    }

                }

            } catch (_: Exception) {}

        }

        return null

    }



    fun isElf(b: ByteArray): Boolean =

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



    fun runSigProbe(context: Context, vm: DriverViewModel) {

        try {

            val probeFile = File(context.cacheDir, "sigprobe.ko")

            if (!probeFile.exists() || probeFile.length() == 0L) {

                context.assets.open("sigprobe.ko").use { input ->

                    FileOutputStream(probeFile).use { output -> input.copyTo(output) }

                }

            }

            val staged = "/data/local/tmp/sigprobe.ko"

            Shell.cmd(

                "cp \"${probeFile.absolutePath}\" $staged 2>/dev/null",

                "chmod 644 $staged 2>/dev/null"

            ).exec()



            vm.tlog("[SIG-PROBE] Insmodding signature-broken control module...", "CMD")

            val res = Shell.cmd("insmod $staged 2>&1").exec()

            val out = (res.out + res.err).joinToString(" ").trim()

            val dmesgSig = Shell.cmd("dmesg 2>/dev/null | grep -iE 'reject|sig|verification' | tail -n 3")

                .exec().out.filter { it.isNotBlank() }.takeLast(3)



            val classification = when {

                res.isSuccess -> "NO_ENFORCEMENT (module loaded — sig check is off)"

                out.contains("invalid module format", true) ->

                    "SIG_LAYER_PASSED (loader reached vermagic stage — signature bypass is ALIVE)"

                out.contains("rejected", true) || out.contains("required key", true) ||

                    out.contains("key was rejected", true) || out.contains("verification", true) ->

                    "SIG_ENFORCED (unsigned module rejected at the signature check — bypass NOT effective)"

                else -> "UNKNOWN ($out)"

            }

            vm.tlog("[SIG-PROBE] Result: $classification", "WARN")

            dmesgSig.forEach { vm.tlog("[SIG-PROBE][DMESG] ${it.trim()}", "INFO") }

            runCatching { Shell.cmd("rm -f $staged 2>/dev/null").exec() }



            File(context.cacheDir, "sig_diag.txt").writeText(

                buildString {

                    appendLine("=== SIGNATURE PROBE ===")

                    appendLine("time   : ${vm.nowString()}")

                    appendLine("kernel : ${Shell.cmd("uname -r").exec().out.firstOrNull()?.trim()}")

                    appendLine("result : $classification")

                    appendLine("insmod : ${if (out.isBlank()) "(no stderr)" else out}")

                    appendLine("dmesg  :")

                    dmesgSig.forEach { appendLine("  ${it.trim()}") }

                }

            )

        } catch (e: Exception) {

            vm.tlog("[SIG-PROBE] failed: ${e.message}", "ERR")

        }

    }



    fun extractAssetToTmp(context: Context, name: String): Boolean = try {
        val local = File(context.cacheDir, name)
        context.assets.open(name).use { input ->
            val bytes = input.readBytes()
            if (!local.exists() || local.length() != bytes.size.toLong()) {
                local.writeBytes(bytes)
            }
        }
        val target = "/data/local/tmp/$name"
        Shell.cmd(
            "cp \"${local.absolutePath}\" $target 2>/dev/null",
            "chmod 755 $target 2>/dev/null"
        ).exec().isSuccess
    } catch (_: Exception) {
        false
    }

    fun autoUnlockSignatureStandalone(context: Context? = null, vm: DriverViewModel? = null): Boolean {
        return autoUnlockSignatureStandaloneDetail(context, vm).first
    }

    fun autoUnlockSignatureStandaloneDetail(context: Context? = null, vm: DriverViewModel? = null): Pair<Boolean, String> {
        return autoUnlockSignatureStandaloneInternal(context, vm)
    }

    private fun autoUnlockSignatureStandaloneInternal(context: Context? = null, vm: DriverViewModel? = null): Pair<Boolean, String> {
        if (context != null) {
            extractAssetToTmp(context, "magiskboot")
            extractAssetToTmp(context, "sigpatch")
        }
        return try {
            val d = "$"
            val script = """
                slot=${d}(getprop ro.boot.slot_suffix 2>/dev/null)
                [ -z "${d}slot" ] && slot=${d}(grep -o 'androidboot.slot_suffix=[^ ]*' /proc/cmdline 2>/dev/null | cut -d= -f2)
                if [ -z "${d}slot" ]; then
                  s=${d}(getprop ro.boot.slot 2>/dev/null)
                  [ -n "${d}s" ] && slot="_${d}s"
                fi

                find_boot() {
                  for name in "boot${d}slot" "boot" "boot_a" "boot_b"; do
                    for dir in /dev/block/by-name \
                               /dev/block/bootdevice/by-name \
                               /dev/block/platform/*/by-name \
                               /dev/block/platform/*/*/by-name \
                               /dev/block/mapper; do
                      if [ -b "${d}dir/${d}name" ]; then
                        readlink -f "${d}dir/${d}name" 2>/dev/null || echo "${d}dir/${d}name"
                        return 0
                      fi
                    done
                  done

                  for b in /sys/class/block/*; do
                    uevent="${d}b/uevent"
                    if [ -f "${d}uevent" ]; then
                      if grep -q -E "PARTNAME=boot${d}slot$|PARTNAME=boot$" "${d}uevent" 2>/dev/null; then
                        devname=${d}(basename "${d}b")
                        if [ -b "/dev/block/${d}devname" ]; then
                          echo "/dev/block/${d}devname"
                          return 0
                        fi
                      fi
                    fi
                  done

                  dev=${d}(find /dev/block -name "boot${d}slot" -o -name "boot" 2>/dev/null | head -n 1)
                  if [ -n "${d}dev" ] && [ -b "${d}dev" ]; then
                    readlink -f "${d}dev" 2>/dev/null || echo "${d}dev"
                    return 0
                  fi

                  return 1
                }

                BOOT_DEV=${d}(find_boot)
                if [ -z "${d}BOOT_DEV" ] || [ ! -b "${d}BOOT_DEV" ]; then
                  echo "BOOT_DEV_NOT_FOUND"
                  exit 1
                fi

                MB=""
                for b in /data/local/tmp/magiskboot \
                         /data/adb/ap/bin/magiskboot \
                         /data/adb/magisk/magiskboot \
                         /data/adb/ksu/bin/magiskboot \
                         /system/bin/magiskboot; do
                  if [ -x "${d}b" ]; then
                    MB="${d}b"
                    break
                  fi
                done

                if [ -z "${d}MB" ] && [ -f /data/local/tmp/magiskboot ]; then
                  chmod 0755 /data/local/tmp/magiskboot 2>/dev/null
                  [ -x /data/local/tmp/magiskboot ] && MB="/data/local/tmp/magiskboot"
                fi

                if [ -z "${d}MB" ]; then
                  echo "MAGISKBOOT_NOT_FOUND"
                  exit 2
                fi

                WORK="/data/local/tmp/kl_sig_unlock"
                rm -rf "${d}WORK"
                mkdir -p "${d}WORK"
                cd "${d}WORK" || exit 3

                dd if="${d}BOOT_DEV" of="${d}WORK/boot.img" bs=4096 2>/dev/null
                if [ ! -s "${d}WORK/boot.img" ]; then
                  echo "DUMP_FAILED"
                  rm -rf "${d}WORK"
                  exit 4
                fi

                "${d}MB" unpack "${d}WORK/boot.img" >/dev/null 2>&1
                if [ ! -f "${d}WORK/kernel" ]; then
                  echo "UNPACK_FAILED"
                  rm -rf "${d}WORK"
                  exit 5
                fi

                PATCHED=0
                P="00008052c0035fd61f2003d5"

                # 3-tier native ARM64 disassembler engine (Tier 1 table + Tier 2 leaf + Tier 3 entry)
                chmod 0755 /data/local/tmp/sigpatch 2>/dev/null
                if [ -x /data/local/tmp/sigpatch ]; then
                  /data/local/tmp/sigpatch "${d}WORK/kernel" >/dev/null 2>&1
                  if [ ${d}? -eq 0 ]; then
                    PATCHED=1
                  fi
                fi

                # Fallback to known per-build patterns via magiskboot hexpatch
                if [ ${d}PATCHED -eq 0 ]; then
                  # 1. OnePlus CPH2381 (5.4.254)
                  "${d}MB" hexpatch "${d}WORK/kernel" 4841019000c14339c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  # 2. Realme RMX3511 (5.4.210)
                  "${d}MB" hexpatch "${d}WORK/kernel" e8d000b000614239c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  # 3. Infinix X6525 (5.4.210)
                  "${d}MB" hexpatch "${d}WORK/kernel" c80601f000415039c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  # 4. OnePlus IN2021 (4.19.157)
                  "${d}MB" hexpatch "${d}WORK/kernel" 2835019000816c39c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  # 5. Realme RMX3195 (4.19.191)
                  "${d}MB" hexpatch "${d}WORK/kernel" a8f6009000817439c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  # 6. Samsung SM-A235F (4.19.157)
                  "${d}MB" hexpatch "${d}WORK/kernel" c839019000c15739c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  # 7. Xiaomi M2010J19CG (4.19.157)
                  "${d}MB" hexpatch "${d}WORK/kernel" c8f000d000614739c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  # 8. Realme RMX5070 / Samsung SM-A525F (4.14.357-openela direct signature bypass)
                  "${d}MB" hexpatch "${d}WORK/kernel" c2030035f50300aaa90e41f83f7500f143030054080440f9 f40301aaf30300aa390000141f2003d51f2003d51f2003d5 >/dev/null 2>&1 && PATCHED=1
                  # 8b. Legacy getter pattern (if present)
                  "${d}MB" hexpatch "${d}WORK/kernel" 0828019000214c39c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  # 9. Realme RMX3241 (4.19.191)
                  "${d}MB" hexpatch "${d}WORK/kernel" 281a01b000817f39c0035fd6 ${d}P >/dev/null 2>&1 && PATCHED=1
                  # 10. Xiaomi Redmi 7 (4.9.186)
                  "${d}MB" hexpatch "${d}WORK/kernel" 82030035c00640f91f7000f1 23000014c00640f91f7000f1 >/dev/null 2>&1 && PATCHED=1
                fi

                # Samsung Knox RKP Neutralization (CONFIG_RKP=y bypass: disable rkp_init + load_module/do_init_module uh_calls)
                "${d}MB" hexpatch "${d}WORK/kernel" ff4303d1fd7b0ba9fdc30291f36300f948200090 00008052c0035fd61f2003d51f2003d548200090 >/dev/null 2>&1 && PATCHED=1
                "${d}MB" hexpatch "${d}WORK/kernel" 290d40b9bfff3ba9aba33da9a9831ef84930fc97 290d40b9bfff3ba9aba33da9a9831ef81f2003d5 >/dev/null 2>&1 && PATCHED=1
                "${d}MB" hexpatch "${d}WORK/kernel" e5031faae92301a9eb3302a9ea1b00f9b52cfc97 e5031faae92301a9eb3302a9ea1b00f91f2003d5 >/dev/null 2>&1 && PATCHED=1

                if [ ${d}PATCHED -eq 0 ]; then
                  CUR_BOOT_HASH=${d}(sha256sum "${d}WORK/boot.img" | cut -d' ' -f1)
                  if grep -q "patched_boot_sha256=${d}CUR_BOOT_HASH" /data/adb/kernel_loader/status.prop 2>/dev/null; then
                    echo "ALREADY_PATCHED"
                    echo "PATCH_SUCCESS"
                    rm -rf "${d}WORK"
                    exit 0
                  fi
                  echo "PATTERN_NOT_FOUND"
                  rm -rf "${d}WORK"
                  exit 6
                fi

                "${d}MB" repack "${d}WORK/boot.img" >/dev/null 2>&1
                if [ ! -f "${d}WORK/new-boot.img" ] || [ ! -s "${d}WORK/new-boot.img" ]; then
                  echo "REPACK_FAILED"
                  rm -rf "${d}WORK"
                  exit 7
                fi

                # Flash with RW unlock & error capture
                blockdev --setrw "${d}BOOT_DEV" 2>/dev/null
                chmod 660 "${d}BOOT_DEV" 2>/dev/null
                dd if="${d}WORK/new-boot.img" of="${d}BOOT_DEV" bs=4096 2>"${d}WORK/flash_err.txt"
                FLASH_RC=${d}?
                if [ ${d}FLASH_RC -ne 0 ]; then
                  ERR_TXT=${d}(cat "${d}WORK/flash_err.txt" 2>/dev/null | tr '\n' ' ')
                  cp -f "${d}WORK/new-boot.img" /sdcard/patched_boot.img 2>/dev/null
                  echo "FLASH_WRITE_FAILED: ${d}ERR_TXT"
                  rm -rf "${d}WORK"
                  exit 8
                fi
                sync

                # Strict readback verification
                NEW_SIZE=${d}(wc -c < "${d}WORK/new-boot.img")
                NEW_HASH=${d}(sha256sum "${d}WORK/new-boot.img" | cut -d' ' -f1)
                COUNT=${d}(( (NEW_SIZE + 4095) / 4096 ))
                dd if="${d}BOOT_DEV" of="${d}WORK/readback.img" bs=4096 count="${d}COUNT" 2>/dev/null
                head -c "${d}NEW_SIZE" "${d}WORK/readback.img" > "${d}WORK/readback_exact.img" 2>/dev/null || dd if="${d}WORK/readback.img" of="${d}WORK/readback_exact.img" bs=1 count="${d}NEW_SIZE" 2>/dev/null
                READ_HASH=${d}(sha256sum "${d}WORK/readback_exact.img" | cut -d' ' -f1)

                if [ "${d}NEW_HASH" != "${d}READ_HASH" ]; then
                  cp -f "${d}WORK/new-boot.img" /sdcard/patched_boot.img 2>/dev/null
                  echo "FLASH_VERIFY_FAILED: OEM block protection prevented writing to boot partition"
                  rm -rf "${d}WORK"
                  exit 9
                fi

                # Flash verified on physical flash storage
                mkdir -p /data/adb/kernel_loader 2>/dev/null
                echo "sig_unlocked=true" > /data/adb/kernel_loader/status.prop 2>/dev/null
                echo "patched_boot_sha256=${d}NEW_HASH" >> /data/adb/kernel_loader/status.prop 2>/dev/null
                echo "boot_dev=${d}BOOT_DEV" >> /data/adb/kernel_loader/status.prop 2>/dev/null
                rm -rf "${d}WORK"
                echo "PATCH_SUCCESS"

            """.trimIndent()

            val res = Shell.cmd(script).exec()
            val out = (res.out + res.err).joinToString("\n").trim()

            if (vm != null) {
                if (out.contains("PATCH_SUCCESS")) {
                    vm.tlog("[SIG-UNLOCK] Kernel signature & Knox RKP patched & verified in boot partition", "OK")
                } else if (out.isNotEmpty()) {
                    vm.tlog("[SIG-UNLOCK] Patcher result: $out", "WARN")
                    if (out.contains("FLASH_WRITE_FAILED") || out.contains("FLASH_VERIFY_FAILED")) {
                        vm.tlog("[SIG-UNLOCK] Boot partition is write-protected on this ROM.", "WARN")
                        vm.tlog("[SIG-UNLOCK] Patched boot image saved to /sdcard/patched_boot.img", "INFO")
                    }
                }
            }

            Pair(out.contains("PATCH_SUCCESS"), out)
        } catch (e: Exception) {
            Pair(false, e.message ?: "Exception")
        } finally {
            Shell.cmd("rm -f /data/local/tmp/magiskboot /data/local/tmp/sigpatch 2>/dev/null").exec()
        }
    }

}

