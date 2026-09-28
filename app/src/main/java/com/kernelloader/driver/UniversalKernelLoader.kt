package com.kernelloader.driver

import android.content.Context
import com.kernelloader.root.RootChecker
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

object UniversalKernelLoader {

    private const val TMP_KO = "/data/local/tmp/kloader_auto.ko"

    fun autoLoad(context: Context, vm: DriverViewModel, variant: String = "") {
        vm.tstep("Checking superuser...")
        if (variant.isNotBlank()) {
            vm.tlog("LOAD ${OtaDriverStore.variantLabel(variant).uppercase()} on any device", "INFO")
        } else {
            vm.tlog("LOAD on any device", "INFO")
        }

        val rootOk = try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
        if (!rootOk) {
            vm.tlog("ROOT: MISSING - grant Superuser/Su permission to the app!", "ERR")
            finish(vm, false, "Root missing - grant superuser access")
            return
        }
        vm.tlog("ROOT: OK (running as uid=0)", "OK")

        vm.tstep("Reading device info...")
        val brand = Shell.cmd("getprop ro.product.brand").exec().out.firstOrNull()?.trim() ?: ""
        val model = Shell.cmd("getprop ro.product.model").exec().out.firstOrNull()?.trim() ?: ""
        val kernel = Shell.cmd("uname -r").exec().out.firstOrNull()?.trim() ?: "unknown"
        val arch = Shell.cmd("uname -m").exec().out.firstOrNull()?.trim() ?: "unknown"
        val selinux = Shell.cmd("getenforce").exec().out.firstOrNull()?.trim() ?: "unknown"
        
        vm.tlog(
            "DEVICE: ${listOf(brand, model).filter { it.isNotEmpty() }.joinToString(" ")} · " +
                    "$kernel · $arch · SELinux $selinux",
            "INFO"
        )

        Shell.cmd("setenforce 0 2>/dev/null").exec()

        vm.tstep("Preparing driver .ko file...")
        val cacheFile = File(context.cacheDir, "kloader_auto.ko")
        val sourceName: String
        val pickedUri = vm.pickedFileUri.value
        if (pickedUri != null) {
            sourceName = vm.pickedFileName.value ?: "picked.ko"
            vm.tlog("SOURCE: user-picked file: $sourceName", "INFO")
            try {
                context.contentResolver.openInputStream(pickedUri)?.use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                } ?: run {
                    vm.tlog("ERROR: could not open picked file", "ERR")
                    finish(vm, false, "File open failed")
                    return
                }
            } catch (e: Exception) {
                vm.tlog("ERROR: reading picked file: ${e.message}", "ERR")
                finish(vm, false, "File read failed")
                return
            }
        } else {
            val best = findBestEmbeddedDriver(context, kernel, variant)
            if (best == null) {
                vm.tlog("ERROR: no file picked AND no embedded driver found", "ERR")
                finish(vm, false, "No .ko found - pick a file")
                return
            }
            sourceName = best.displayName
            
            val realVerm = EmbeddedDrivers.readVermagic(context, best.filename)
            val exactCover = realVerm.isNotEmpty() &&
                    RootChecker.kernelShortVersion(realVerm) == RootChecker.kernelShortVersion(kernel)
            vm.tlog(
                "SOURCE: $sourceName" + if (exactCover) " (exact build)" else " (nearest series)",
                "INFO"
            )
            try {
                context.resources.assets.open(best.filename).use { input ->
                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                vm.tlog("ERROR: extracting embedded driver: ${e.message}", "ERR")
                finish(vm, false, "Driver extract failed")
                return
            }
        }

        vm.tstep("Validating ELF kernel module...")
        if (!ensureElf(cacheFile)) {
            vm.tlog("ERROR: file is NOT an ELF kernel module (.ko) - insmod impossible", "ERR")
            vm.tlog("HINT: pick a valid .ko / installer .sh (.ko is auto-extracted from .sh)", "WARN")
            finish(vm, false, "Not a valid .ko (ELF)")
            return
        }

        val vermagic = readVermagic(cacheFile)
        if (vermagic == null) {
            vm.tlog("WARN: vermagic not found in .ko - will try load anyway", "WARN")
        } else {
            val vmVersion = vermagic.substringBefore(' ')
            val vmArch = vermagic.substringAfterLast(' ', "")
            if (vmVersion == kernel) {
                vm.tlog("MODULE: $sourceName · vermagic matches $kernel", "OK")
            } else {
                vm.tlog("MODULE: $sourceName · built for $vmVersion (running $kernel)", "WARN")
            }
            if (vmArch.isNotEmpty() && arch == "aarch64" && !vmArch.contains("aarch64", true)) {
                vm.tlog("MODULE: ARCH mismatch (.ko=$vmArch vs running=$arch) - load will fail", "WARN")
            }
        }

        vm.tstep("Copying .ko to /data/local/tmp...")
        val copy = Shell.cmd(
            "cp \"${cacheFile.absolutePath}\" $TMP_KO",
            "chmod 644 $TMP_KO",
            "chown root:root $TMP_KO 2>/dev/null"
        ).exec()
        if (!copy.isSuccess) {
            vm.tlog("ERROR: copy to /data/local/tmp failed: ${copy.err.joinToString(" ")}", "ERR")
            finish(vm, false, "Copy failed")
            return
        }

        val devNode = vm.preferredDevNode()

        val baselineMods = SafetyGuard.loadedModuleNames()

        val alreadyLoaded = vm.knownDriverModules().firstOrNull { it in baselineMods }
        if (alreadyLoaded != null) {
            vm.tstep("Checking module...")
            vm.tlog("STATUS: driver '$alreadyLoaded' is ALREADY loaded - nothing to do", "OK")
            vm.setLoadedModule(alreadyLoaded)
            Shell.cmd("chmod 666 /dev/$devNode 2>/dev/null").exec()
            verifyLoad(vm, sourceName, devNode, alreadyLoaded)
            finish(vm, true, "Already loaded: $alreadyLoaded")
            return
        }

        vm.tstep("Loading module (insmod)...")
        var res = Shell.cmd("insmod $TMP_KO devname=$devNode").exec()
        if (!res.isSuccess &&
            (res.out + res.err).joinToString("\n").contains("Unknown parameter", true)
        ) {
            res = Shell.cmd("insmod $TMP_KO").exec()
        }

        if (!res.isSuccess) {
            val errFirst = (res.out + res.err).firstOrNull { it.isNotBlank() } ?: "exit ${res.code}"
            vm.tlog("ERROR: insmod failed - $errFirst", "ERR")
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
            vm.tlog("DONE: ${viewModelModule(vm)} loaded and ready", "OK")
            finish(vm, true, "Loaded OK: $sourceName")
            
            stageForBoot(vm, context, variant, devNode)
        } else {
            vm.tlog("FAILED: see the lines above", "ERR")
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
        val lsmod = Shell.cmd("lsmod").exec()
        
        val candidates = (listOf(moduleName) + vm.knownDriverModules())
            .filter { it.isNotBlank() }.distinct()
        val loadedLine = lsmod.out.drop(1).firstOrNull { line ->
            val n = line.trim().split(Regex("\\s+")).firstOrNull() ?: ""
            n.isNotBlank() && n != "Module" && n in candidates
        }
        val moduleLoaded = loadedLine != null
        val loadedName = loadedLine?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: ""
        if (loadedName.isNotBlank()) vm.setLoadedModule(loadedName)
        
        val devList = Shell.cmd("ls /dev 2>/dev/null").exec().out
        if (expectedNode.isNotEmpty()) {
            
            Shell.cmd("chmod 666 /dev/$expectedNode 2>/dev/null").exec()
        }
        val devMatches = devList.filter { node ->
            (loadedName.isNotEmpty() && node.contains(loadedName, true)) ||
                    (expectedNode.isNotEmpty() && node.contains(expectedNode, true)) ||
                    node.contains("kloader", true) || node.contains("daisy", true) ||
                    node.contains("entryi", true) || node.contains("kmem", true)
        }
        
        val exactNode = expectedNode.isNotEmpty() && devList.any { it.trim() == expectedNode }
        val devExists = exactNode
        val dmesg = Shell.cmd("dmesg | grep -i -E 'kmem|kloader|entryi|vermagic|insmod' | tail -n 15").exec()

        if (exactNode) vm.rememberDevNode(expectedNode)
        
        if (moduleLoaded && exactNode) {
            vm.tlog("VERIFY: $loadedName loaded · /dev/$expectedNode ready", "OK")
        } else {
            if (!moduleLoaded) vm.tlog("VERIFY: the module is not visible in lsmod", "ERR")
            if (!exactNode) {
                vm.tlog("VERIFY: /dev/$expectedNode is MISSING", "ERR")
                vm.tlog(
                    "VERIFY: the driver registered " +
                            devMatches.joinToString(", ").ifEmpty { "nothing" } +
                            " instead, and the game apps only open /dev/$expectedNode",
                    "ERR"
                )
            }
            dmesg.out.filter { it.isNotBlank() }.distinct().takeLast(3)
                .forEach { vm.tlog("dmesg: $it", "INFO") }
        }

        vm.setVerification(
            VerificationResult(
                lsmod = lsmod.out,
                deviceNodeFound = devExists,
                dmesgLogs = dmesg.out,
                timestamp = vm.nowString()
            )
        )
        
        return moduleLoaded && (expectedNode.isBlank() || devExists)
    }

    private fun stageForBoot(
        vm: DriverViewModel,
        context: Context,
        variant: String,
        devNode: String
    ) {
        val modName = vm.loadedModuleName.value
        if (modName.isBlank()) {
            vm.tlog("AUTOLOAD: skipped - the loaded module name is unknown, so no safe boot script", "WARN")
            return
        }
        val ko = File(TMP_KO)
        if (!ko.exists()) {
            vm.tlog("AUTOLOAD: skipped - $TMP_KO is gone", "WARN")
            return
        }
        if (!DriverAutoload.hasBootRunner()) {
            vm.tlog("AUTOLOAD: no /data/adb/service.d runner found (Magisk/KernelSU?)", "WARN")
            vm.tlog("AUTOLOAD: the driver will NOT auto-load after a reboot - tap LOAD again", "INFO")
            return
        }
        vm.tlog("AUTOLOAD: staging so the driver comes back by itself after a reboot...", "INFO")
        val ok = DriverAutoload.enable(context, ko, modName, variant, devNode) { m, t ->
            vm.tlog(m, t)
        }
        vm.autoloadEnabled.value = ok
    }

    private fun finish(vm: DriverViewModel, ok: Boolean, msg: String) {        vm.autoLoadOk.value = ok
        vm.autoLoadStatus.value = msg
        if (!ok) {
            vm.tlog("SUPPORT: no exact loader found for this kernel, or the load failed.", "WARN")
            vm.tlog(
                "SUPPORT: message us on WhatsApp - we will build a custom loader for your kernel: wa.me/${SupportContact.WHATSAPP_NUMBER}",
                "FIX"
            )
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
