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

        // every .ko in the database and the assets is aarch64; a wrong-arch
        // device can only fail at insmod with "Exec format error", so say so
        // up front instead of burning the whole pipeline
        if (arch != "aarch64" && arch != "arm64") {
            vm.tlog("This driver set is arm64-only - device reports $arch", "ERR")
            finish(vm, false, "arm64 CPU required (device: $arch)")
            return
        }

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
        // stealth builds unlink themselves from /proc/modules, so lsmod has
        // no name to remember - the .ko's own modinfo still knows it
        val modName = vm.loadedModuleName.value.ifBlank { readModinfoName(koFile) }
        if (modName.isBlank()) {
            vm.tlog("Boot staging skipped - unknown module", "WARN")
            return
        }
        if (!koFile.exists()) {
            vm.tlog("Boot staging skipped - .ko gone", "WARN")
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

    /**
     * The module name a .ko registers under, from its modinfo section
     * (`\0name=<name>\0`). Pure Kotlin, no shell - used when the module has
     * unlinked itself from /proc/modules (stealth builds) and lsmod can no
     * longer tell us what to rmmod or stage. The name is kernel-generated
     * and trusted, but only accepted if it is a plain module name - "5.10_A12"
     * keeps its dot, and anything else is rejected instead of mangled.
     */
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
