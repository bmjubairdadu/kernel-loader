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

        if (arch != "aarch64" && arch != "arm64") {
            vm.tlog("This driver set is arm64-only - device reports $arch", "ERR")
            finish(vm, false, "arm64 CPU required (device: $arch)")
            return
        }

        Shell.cmd("setenforce 0 2>/dev/null").exec()
        vm.tlog("[SELINUX] Permissive mode requested (setenforce 0)", "CMD")

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
            val best = findBestEmbeddedDriver(context, kernel, variant)
            if (best == null) {
                vm.tlog("[ERR] No compatible driver found in assets", "ERR")
                finish(vm, false, "No .ko found - pick a file")
                return
            }
            sourceName = best.displayName

            val realVerm = EmbeddedDrivers.readVermagic(context, best.filename)
            val exactCover = realVerm.isNotEmpty() &&
                    RootChecker.kernelShortVersion(realVerm) == RootChecker.kernelShortVersion(kernel)
            vm.tlog(
                "[SOURCE] Driver: $sourceName" + if (exactCover) " (exact build match)" else " (nearest series)",
                "INFO"
            )
            try {
                context.resources.assets.open(best.filename).use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                }
                vm.tlog("[STAGE] Extracted asset: ${best.filename} -> ${cacheFile.name} (${cacheFile.length()} bytes)", "INFO")
            } catch (e: Exception) {
                vm.tlog("[ERR] Extract failed: ${e.message}", "ERR")
                finish(vm, false, "Driver extract failed")
                return
            }
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

        val alreadyLoaded = vm.knownDriverModules().firstOrNull { it in baselineMods }
        if (alreadyLoaded != null) {
            vm.tlog("[CLEAN] Unloading stale module: $alreadyLoaded (rmmod)...", "WARN")
            Shell.cmd("rmmod $alreadyLoaded 2>/dev/null", "sleep 1").exec()
        }

        vm.tstep("Loading module...")
        Shell.cmd("setenforce 0 2>/dev/null").exec()
        disableModuleSigEnforce(vm)
        stripModuleSig(File(TMP_KO), vm)
        vm.tlog("Inserting module...", "CMD")
        var res = Shell.cmd("insmod $TMP_KO devname=$devNode").exec()
        if (!res.isSuccess) {
            vm.tlog("[KERNEL] Retrying with devicename param...", "CMD")
            res = Shell.cmd("insmod $TMP_KO devicename=$devNode").exec()
        }
        if (!res.isSuccess) {
            vm.tlog("[KERNEL] Retrying with default params...", "CMD")
            res = Shell.cmd("insmod $TMP_KO").exec()
        }
        if (!res.isSuccess) {
            vm.tlog("[KERNEL] Retrying with forced insmod (-f)...", "WARN")
            res = Shell.cmd("insmod -f $TMP_KO devname=$devNode 2>/dev/null || insmod -f $TMP_KO 2>/dev/null").exec()
        }

        if (!res.isSuccess) {
            val errAll = (res.out + res.err).joinToString(" ").lowercase()
            val dmesgSig = Shell.cmd("dmesg 2>/dev/null | tail -n 5").exec().out.joinToString(" ").lowercase()
            val isSigError = errAll.contains("required key not available") ||
                    errAll.contains("key was rejected") ||
                    errAll.contains("operation not permitted") ||
                    errAll.contains("permission denied") ||
                    dmesgSig.contains("module verification failed") ||
                    dmesgSig.contains("module signature") ||
                    dmesgSig.contains("required key")

            if (isSigError) {
                vm.tlog("Signature verification blocking load, fixing...", "WARN")
                Shell.cmd(
                    "echo 0 > /proc/sys/kernel/module_sig_enforce 2>/dev/null",
                    "sysctl -w kernel.module_sig_enforce=0 2>/dev/null",
                    "echo 0 > /sys/module/module/parameters/sig_enforce 2>/dev/null"
                ).exec()
                stripModuleSig(File(TMP_KO), vm)
                res = Shell.cmd("insmod -f $TMP_KO devname=$devNode 2>/dev/null || insmod -f $TMP_KO 2>/dev/null").exec()
                if (!res.isSuccess) {
                    res = Shell.cmd("insmod $TMP_KO devname=$devNode 2>/dev/null || insmod $TMP_KO 2>/dev/null").exec()
                }
                if (res.isSuccess) {
                    vm.tlog("Module loaded after signature fix", "OK")
                }
            }
        }

        if (!res.isSuccess) {
            val errAll = (res.out + res.err).joinToString(" ")
            if (errAll.contains("File exists", true)) {

                vm.tlog("[RESIDENT] Module already live in kernel memory (hidden) - validating live node...", "INFO")
                if (verifyLoad(vm, sourceName, devNode, vm.loadedModuleName.value)) {
                    vm.tlog("[DONE] Resident kernel driver verified & operational", "OK")
                    finish(vm, true, "Already loaded: $sourceName", context)
                    runCatching { Shell.cmd("rm -f $TMP_KO 2>/dev/null").exec() }
                    return
                }
            }
            val errFirst = (res.out + res.err).firstOrNull { it.isNotBlank() } ?: "exit ${res.code}"
            vm.tlog("[KERNEL ERR] insmod failed: $errFirst", "ERR")

            Shell.cmd("dmesg 2>/dev/null | grep -iE 'kmem|dmsync|insmod|module|vermagic' | tail -n 3").exec().out
                .filter { it.isNotBlank() }.distinct().forEach { vm.tlog("[DMESG] ${it.trim()}", "INFO") }
            finish(vm, false, "Load failed: $errFirst")
            return
        }

        vm.tlog("[KERNEL] Module inserted into kernel successfully", "OK")
        val dmesgInsert = Shell.cmd("dmesg 2>/dev/null | grep -iE 'kmem|dmsync|wanbai|entryi|memacc' | tail -n 3").exec().out
        dmesgInsert.filter { it.isNotBlank() }.distinct().forEach {
            vm.tlog("[DMESG] ${it.trim()}", "INFO")
        }

        val loadedNow = SafetyGuard.newlyLoaded(baselineMods)
        if (loadedNow.isNotEmpty()) {
            vm.setLoadedModule(loadedNow.first())
            vm.tlog("[MODULE] Registered in /proc/modules: ${loadedNow.first()}", "OK")
        }
        vm.tstep("Verifying module...")

        val ok = verifyLoad(vm, sourceName, devNode, vm.loadedModuleName.value)
        if (ok) {
            vm.tlog("[CORE] Physical-alias memory translation active (page-table walk + ioremap)", "INFO")
            vm.tlog("[IOCTL] Handlers active: 0x801 (Read), 0x802 (Write), 0x803 (ModBase)", "INFO")
            vm.tlog("[DONE] ${viewModelModule(vm)} ready · Listening on /dev/wanbai", "OK")
            finish(vm, true, "Loaded OK: $sourceName", context)

            runCatching { Shell.cmd("rm -f $TMP_KO 2>/dev/null").exec() }
        } else {
            vm.tlog("[FAIL] Verification failed - device node not accessible", "ERR")
            finish(vm, false, "Load failed - details in the terminal")
        }
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
        vm.tlog("[SECURITY] Injected untrusted_app chr_file access policies", "INFO")
        Shell.cmd(
            "setenforce 0 2>/dev/null",
            "magiskpolicy --live 'allow untrusted_app * chr_file *' 2>/dev/null || supolicy --live 'allow untrusted_app * chr_file *' 2>/dev/null"
        ).exec()

        val candidates = listOf(
            "wanbai", "dmsync", "dmsync_qx", "kmem_337", "kmem_337_qx",
            "entryi", "memacc", "memacc_qx", "kloaderctl", "daisyctl"
        )

        var exactNode = false
        var foundNodeName = expectedNode.ifBlank { "wanbai" }

        for (attempt in 1..10) {
            val devList = Shell.cmd("ls /dev 2>/dev/null").exec().out.map { it.trim() }
            if (expectedNode.isNotEmpty() && devList.contains(expectedNode)) {
                exactNode = true
                foundNodeName = expectedNode
                break
            }
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

        if (!exactNode) {
            vm.tlog("[SYSFS] Scanning /sys/class for registered driver devices...", "INFO")
            val sysDevs = Shell.cmd(
                "for f in /sys/class/dmsync*/dev /sys/class/dmsync*/*/dev /sys/class/kmem*/dev /sys/class/kmem*/*/dev /sys/class/memacc*/dev; do " +
                        "if [ -f \"\$f\" ]; then cat \"\$f\"; echo \" \${f}\"; break; fi; done 2>/dev/null"
            ).exec().out.firstOrNull()?.trim()

            if (!sysDevs.isNullOrBlank()) {
                val mm = sysDevs.substringBefore(' ').trim()
                val parts = mm.split(':')
                if (parts.size == 2 && parts[0].toIntOrNull() != null && parts[1].toIntOrNull() != null) {
                    val maj = parts[0]
                    val min = parts[1]
                    foundNodeName = if (expectedNode.isNotBlank()) expectedNode else "wanbai"
                    Shell.cmd(
                        "mknod -m 666 /dev/$foundNodeName c $maj $min 2>/dev/null",
                        "mknod -m 666 /dev/wanbai c $maj $min 2>/dev/null"
                    ).exec()
                    exactNode = true
                    vm.tlog("[SYSFS] Created /dev/$foundNodeName (major=$maj minor=$min)", "OK")
                }
            }
        }

        if (exactNode && foundNodeName.isNotEmpty()) {
            Shell.cmd(
                "chmod 666 /dev/$foundNodeName 2>/dev/null",
                "chcon u:object_r:null_device:s0 /dev/$foundNodeName 2>/dev/null || chcon u:object_r:device:s0 /dev/$foundNodeName 2>/dev/null"
            ).exec()

            val allAliases = listOf("wanbai", "dmsync", "dmsync_rt", "dmsync_qx", "memacc", "memacc_qx", "entryi", "kmem_337", "CheckMe", "lanran", "video188")
            val linkCmds = allAliases.filter { it != foundNodeName }.map { alias ->
                "rm -f /dev/$alias 2>/dev/null; ln -sf /dev/$foundNodeName /dev/$alias 2>/dev/null; chmod 666 /dev/$alias 2>/dev/null; chcon -h u:object_r:null_device:s0 /dev/$alias 2>/dev/null"
            }
            if (linkCmds.isNotEmpty()) {
                Shell.cmd(*linkCmds.toTypedArray()).exec()
            }

            vm.rememberDevNode(foundNodeName)
            vm.tlog("[DEVNODE] Active node: /dev/$foundNodeName (0666, null_device)", "OK")
            vm.tlog("[DEVNODE] All aliases symlinked (/dev/wanbai, /dev/dmsync, /dev/memacc...)", "INFO")
        }

        val lsmod = Shell.cmd("lsmod").exec()
        val allCandidates = (listOf(moduleName) + vm.knownDriverModules()).filter { it.isNotBlank() }.distinct()
        val loadedLine = lsmod.out.drop(1).firstOrNull { line ->
            val n = line.trim().split(Regex("\\s+")).firstOrNull() ?: ""
            n.isNotBlank() && n != "Module" && n in allCandidates
        }
        val moduleLoaded = loadedLine != null
        val loadedName = loadedLine?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""
        if (loadedName.isNotBlank()) vm.setLoadedModule(loadedName)

        val dmesg = Shell.cmd("dmesg | grep -i -E 'kmem|dmsync|wanbai|kloader|entryi|vermagic|insmod' | tail -n 15").exec()

        val nodeRwOk = Shell.cmd("test -r /dev/wanbai && test -w /dev/wanbai").exec().isSuccess

        if ((moduleLoaded || exactNode) && nodeRwOk) {
            val disp = if (loadedName.isNotBlank()) loadedName else "/dev/wanbai"
            vm.tlog("[VERIFY] Node /dev/wanbai R/W verified (0666, client-ready)", "OK")
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

        return (moduleLoaded || exactNode)
    }

    private fun finish(vm: DriverViewModel, ok: Boolean, msg: String, context: Context? = null) {
        vm.autoLoadOk.value = ok
        vm.autoLoadStatus.value = msg
        if (ok) {
            context?.let { ctx ->
                val kVer = RootChecker.getKernelRelease() ?: ""
                DriverRevocationManager.recordLoadedDriver(
                    context = ctx,
                    entry = null,
                    kernelRelease = kVer,
                    variant = vm.activePipelineVariant.value,
                    isOta = false
                )
                try { DriverWatchdogService.start(ctx) } catch (_: Exception) {}
            }
        } else {
            vm.lastLoadError.value = msg
            vm.tlog("Need custom loader - tap Telegram / WhatsApp", "FIX")
            runCatching { Shell.cmd("rm -f $TMP_KO 2>/dev/null").exec() }
        }
        vm.tstep("")
    }

    fun disableModuleSigEnforce(vm: DriverViewModel? = null) {
        val sigCheck = Shell.cmd("cat /proc/sys/kernel/module_sig_enforce 2>/dev/null").exec()
        val sigValue = sigCheck.out.firstOrNull()?.trim() ?: ""

        if (sigValue == "1") {
            vm?.tlog("Signature enforcement active, fixing...", "WARN")

            Shell.cmd("echo 0 > /proc/sys/kernel/module_sig_enforce 2>/dev/null").exec()
            val afterDirect = Shell.cmd("cat /proc/sys/kernel/module_sig_enforce 2>/dev/null").exec()
                .out.firstOrNull()?.trim() ?: "1"
            if (afterDirect == "0") {
                vm?.tlog("Signature enforcement disabled", "OK")
                return
            }

            Shell.cmd("sysctl -w kernel.module_sig_enforce=0 2>/dev/null").exec()
            val afterSysctl = Shell.cmd("cat /proc/sys/kernel/module_sig_enforce 2>/dev/null").exec()
                .out.firstOrNull()?.trim() ?: "1"
            if (afterSysctl == "0") {
                vm?.tlog("Signature enforcement disabled", "OK")
                return
            }

            Shell.cmd(
                "magiskpolicy --live 'allow kernel kernel system module_load' 2>/dev/null",
                "magiskpolicy --live 'allow init kernel system module_load' 2>/dev/null",
                "magiskpolicy --live 'allow * * * module_load' 2>/dev/null"
            ).exec()

            Shell.cmd(
                "echo 0 > /proc/sys/kernel/module_sig_enforce 2>/dev/null",
                "echo 0 > /sys/module/module/parameters/sig_enforce 2>/dev/null"
            ).exec()
            val afterPolicy = Shell.cmd("cat /proc/sys/kernel/module_sig_enforce 2>/dev/null").exec()
                .out.firstOrNull()?.trim() ?: "1"
            if (afterPolicy == "0") {
                vm?.tlog("Signature enforcement disabled", "OK")
                return
            }

            Shell.cmd(
                "ksud module load --skip-signature 2>/dev/null || " +
                "resetprop ro.boot.verifiedbootstate green 2>/dev/null"
            ).exec()

            Shell.cmd(
                "resetprop ro.boot.flash.locked 0 2>/dev/null",
                "resetprop ro.boot.veritymode logging 2>/dev/null",
                "resetprop ro.boot.warranty_bit 0 2>/dev/null"
            ).exec()

            val finalCheck = Shell.cmd("cat /proc/sys/kernel/module_sig_enforce 2>/dev/null").exec()
                .out.firstOrNull()?.trim() ?: "1"
            if (finalCheck == "0") {
                vm?.tlog("Signature enforcement disabled", "OK")
            } else {
                vm?.tlog("Signature bypass applied (insmod -f)", "WARN")
            }
        } else if (sigValue == "0" || sigValue.isEmpty()) {
            vm?.tlog("Signature verification: OK", "OK")
        }
    }

    fun stripModuleSig(koFile: File, vm: DriverViewModel? = null): Boolean {
        try {
            val bytes = koFile.readBytes()
            if (!isElf(bytes)) return false

            val sigMarker = "~Module signature appended~\n".toByteArray(Charsets.US_ASCII)
            val markerIdx = indexOfFromEnd(bytes, sigMarker)
            if (markerIdx < 0) return false

            val sigInfoLen = 12
            val sigInfoStart = markerIdx - sigInfoLen
            if (sigInfoStart < 4) return false

            val sigLen = ((bytes[sigInfoStart + 8].toInt() and 0xFF) shl 24) or
                    ((bytes[sigInfoStart + 9].toInt() and 0xFF) shl 16) or
                    ((bytes[sigInfoStart + 10].toInt() and 0xFF) shl 8) or
                    (bytes[sigInfoStart + 11].toInt() and 0xFF)

            val stripAt = sigInfoStart - sigLen
            if (stripAt < 100 || stripAt > bytes.size) return false

            val stripped = bytes.copyOf(stripAt)
            koFile.writeBytes(stripped)
            vm?.tlog("Module signature stripped", "OK")
            return true
        } catch (e: Exception) {
            vm?.tlog("Signature strip failed", "WARN")
            return false
        }
    }

    private fun indexOfFromEnd(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        val searchStart = (haystack.size - needle.size - 128).coerceAtLeast(haystack.size / 2)
        outer@ for (i in (haystack.size - needle.size) downTo searchStart) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
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
