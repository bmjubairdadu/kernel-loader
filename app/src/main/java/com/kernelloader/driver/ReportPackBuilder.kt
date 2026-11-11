package com.kernelloader.driver

import android.content.Context
import android.os.Build
import android.os.Process
import com.kernelloader.BuildConfig
import com.kernelloader.root.RootChecker
import com.topjohnwu.superuser.Shell
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ReportPackBuilder {

    const val DEFAULT_ZIP_NAME = "device_dump.zip"

    data class ReportPack(
        val zipFile: File?,
        val zipName: String,
        val caption: String,
        val fullText: String,
        val kcrcStatus: String,
        val manifestSummary: String = ""
    )

    private data class StockKoResult(
        val status: String,
        val sourcePath: String,
        val vermagic: String,
        val elfInfo: ElfInfo.Info?,
        val fileSize: Long
    )

    private data class BootDumpResult(
        val status: String,
        val bootLink: String,
        val bootPart: String,
        val slotSuffix: String,
        val dumpedBytes: Long,
        val headerSummary: String
    )

    fun buildPack(
        context: Context,
        rootAvailable: Boolean,
        loadedModule: String,
        bundleTried: String,
        matchStatus: String,
        resultText: String,
        logText: String,
        onStep: (String) -> Unit = {}
    ): ReportPack {
        val baseDir = File(context.cacheDir, "report_pack")
        val staging = File(baseDir, "staging")
        val output = File(baseDir, "output")

        val stockModulesDir = File(staging, "stock_modules")
        val bootDir = File(staging, "boot")

        try {
            staging.deleteRecursively()
            staging.mkdirs()
            stockModulesDir.mkdirs()
            bootDir.mkdirs()
            output.mkdirs()
            output.listFiles()?.forEach { if (it.name.endsWith(".zip")) it.delete() }
        } catch (_: Exception) {}

        onStep("Collecting device_info.json specs...")
        val model = getProp("ro.product.model").ifBlank { Build.MODEL.orEmpty() }
        val brand = getProp("ro.product.brand").ifBlank { Build.BRAND.orEmpty() }
        val manufacturer = getProp("ro.product.manufacturer").ifBlank { Build.MANUFACTURER.orEmpty() }
        val androidVersion = getProp("ro.build.version.release").ifBlank { Build.VERSION.RELEASE.orEmpty() }
        val sdkApi = getProp("ro.build.version.sdk").ifBlank { Build.VERSION.SDK_INT.toString() }
        val cpuAbi = getProp("ro.product.cpu.abi").ifBlank {
            Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        }
        val socChipset = getProp("ro.board.platform")
            .ifBlank { getProp("ro.soc.model") }
            .ifBlank { getProp("ro.hardware") }
            .ifBlank { Build.BOARD.orEmpty() }
        val kernelRelease = runCmdOut("uname -r").ifBlank { RootChecker.getKernelRelease().orEmpty() }
        val fullVersion = runCmdOut("cat /proc/version").ifBlank {
            System.getProperty("os.version").orEmpty()
        }
        val kernelArch = runCmdOut("uname -m").ifBlank { cpuAbi }
        val securityPatch = getProp("ro.build.version.security_patch")
        val fingerprint = getProp("ro.build.fingerprint").ifBlank { Build.FINGERPRINT.orEmpty() }
        val pageSize = runCmdOut("getconf PAGESIZE").ifBlank { "4096" }
        val cmdline = runCmdOut("cat /proc/cmdline")

        val report = DeviceReportCollector.collect(
            rootAvailable = rootAvailable,
            loadedModules = listOf(loadedModule),
            bundleTried = bundleTried,
            lastError = resultText
        )

        onStep("Extracting config.gz (/proc/config.gz)...")
        val (configStatus, extractedConfigFlags) = extractConfigGz(staging)
        val allConfigFlags = (extractedConfigFlags + report.configFlags).distinct()

        onStep("Extracting kallsyms.txt (/proc/kallsyms)...")
        val kallsymsStatus = dumpKallsyms(staging)

        onStep("Extracting stock_modules/sample.ko...")
        val stockKoResult = captureStockModule(stockModulesDir)

        val resolvedVermagic = stockKoResult.vermagic
            .ifBlank { report.vermagic }
            .ifBlank { kernelRelease }
        val shortVermagic = RootChecker.getShortVermagic(
            vermagic = resolvedVermagic,
            kernelRelease = kernelRelease
        ).ifBlank { kernelRelease }

        val safeZipBase = shortVermagic
            .replace(Regex("""[^A-Za-z0-9._+-]"""), "_")
            .trim('_')
            .ifBlank { "device_dump" }
        val zipName = "$safeZipBase.zip"

        onStep("Dumping boot/boot.img & boot/kernel_header.txt...")
        val bootResult = dumpBootAndHeader(
            context = context,
            rootAvailable = rootAvailable,
            bootDir = bootDir,
            kernelRelease = kernelRelease,
            kernelArch = kernelArch,
            fullVersion = fullVersion,
            cmdline = cmdline,
            pageSize = pageSize,
            stockKo = stockKoResult,
            configFlags = allConfigFlags
        )

        onStep("Writing device_info.json...")
        val deviceInfoJson = buildDeviceInfoJson(
            zipName = zipName,
            model = model,
            brand = brand,
            manufacturer = manufacturer,
            androidVersion = androidVersion,
            sdkApi = sdkApi,
            cpuAbi = cpuAbi,
            socChipset = socChipset,
            kernelRelease = kernelRelease,
            fullVersion = fullVersion,
            kernelArch = kernelArch,
            vermagic = resolvedVermagic,
            shortVermagic = shortVermagic,
            securityPatch = securityPatch,
            fingerprint = fingerprint,
            pageSize = pageSize,
            cmdline = cmdline,
            rootAvailable = rootAvailable,
            rootType = report.rootType,
            stockKo = stockKoResult,
            bootResult = bootResult,
            configStatus = configStatus,
            kallsymsStatus = kallsymsStatus,
            configFlags = allConfigFlags,
            matchStatus = matchStatus,
            resultText = resultText,
            collectedAt = report.collectedAt
        )
        File(staging, "device_info.json").writeText(deviceInfoJson)

        safeRunShell(
            "chmod -R 666 \"${staging.absolutePath}\" 2>/dev/null",
            "chmod 777 \"${staging.absolutePath}\" \"${stockModulesDir.absolutePath}\" \"${bootDir.absolutePath}\" 2>/dev/null",
            "chown -R ${Process.myUid()} \"${staging.absolutePath}\" 2>/dev/null"
        )

        onStep("Packing $zipName...")
        val zipTarget = File(output, zipName)
        var zipFile: File? = null

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipTarget), 65536)).use { zos ->
                zos.setLevel(Deflater.BEST_SPEED)
                val allFiles = staging.walkTopDown()
                    .filter { it.isFile && it.length() > 0L }
                    .sortedBy { fileOrderWeight(it.relativeTo(staging).invariantSeparatorsPath) }
                    .toList()

                val buffer = ByteArray(65536)
                for (f in allFiles) {
                    val relPath = f.relativeTo(staging).invariantSeparatorsPath
                    try {
                        val entry = ZipEntry(relPath).apply { time = f.lastModified() }
                        zos.putNextEntry(entry)
                        f.inputStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                zos.write(buffer, 0, read)
                            }
                        }
                        zos.closeEntry()
                    } catch (_: Exception) {
                        try { zos.closeEntry() } catch (_: Exception) {}
                    }
                }
            }
            if (zipTarget.exists() && zipTarget.length() > 0L) {
                zipFile = zipTarget
            }
        } catch (_: Exception) {
            zipFile = null
        }

        try { staging.deleteRecursively() } catch (_: Exception) {}

        val fullText = report.buildText(
            appName = SupportContact.APP_NAME,
            appVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            matchStatus = matchStatus,
            resultText = resultText,
            logText = logText
        )

        val manifest = buildString {
            appendLine("=== KERNEL LOADER DEVICE DUMP ($zipName) ===")
            appendLine("MODEL       : $model ($brand / $manufacturer)")
            appendLine("ANDROID     : $androidVersion (SDK $sdkApi) · ABI: $cpuAbi")
            appendLine("SOC/CHIPSET : $socChipset")
            appendLine("KERNEL      : $kernelRelease")
            appendLine("VERMAGIC    : $resolvedVermagic")
            appendLine("SHORT VM    : $shortVermagic")
            appendLine("CONFIG.GZ   : $configStatus")
            appendLine("KALLSYMS    : $kallsymsStatus")
            appendLine("SAMPLE.KO   : ${stockKoResult.status}")
            appendLine("BOOT.IMG    : ${bootResult.status}")
            appendLine("TIMESTAMP   : ${report.collectedAt}")
        }

        val caption = buildString {
            appendLine("📦 $zipName — ${SupportContact.APP_NAME}")
            appendLine("📱 Device: $manufacturer $model ($socChipset)")
            appendLine("🤖 Android: $androidVersion (SDK $sdkApi) | ABI: $cpuAbi")
            appendLine("🐧 Kernel: $kernelRelease")
            appendLine("🔑 Vermagic: $shortVermagic")
            appendLine("📂 Config: $configStatus | Kallsyms: $kallsymsStatus")
            append("🧩 Sample.ko: ${stockKoResult.status} | Boot.img: ${bootResult.status}")
        }

        return ReportPack(
            zipFile = zipFile,
            zipName = zipName,
            caption = caption,
            fullText = fullText,
            kcrcStatus = stockKoResult.status,
            manifestSummary = manifest
        )
    }

    private fun fileOrderWeight(relPath: String): String = when (relPath) {
        "device_info.json" -> "01_$relPath"
        "config.gz" -> "02_$relPath"
        "kallsyms.txt" -> "03_$relPath"
        "stock_modules/sample.ko" -> "04_$relPath"
        "boot/boot.img" -> "05_$relPath"
        "boot/kernel_header.txt" -> "06_$relPath"
        else -> "99_$relPath"
    }

    fun wipeReportDir(context: Context) {
        runCatching {
            File(context.cacheDir, "report_pack").deleteRecursively()
            File(context.cacheDir, "report").deleteRecursively()
        }
    }

    private fun extractConfigGz(staging: File): Pair<String, List<String>> {
        val targetGz = File(staging, "config.gz")

        safeRunShell(
            "modprobe configs 2>/dev/null || insmod /vendor/lib/modules/configs.ko 2>/dev/null || insmod /system/lib/modules/configs.ko 2>/dev/null || true",
            "cat /proc/config.gz > \"${targetGz.absolutePath}\" 2>/dev/null",
            "chmod 666 \"${targetGz.absolutePath}\" 2>/dev/null"
        )

        if (!isValidGzip(targetGz)) {
            targetGz.delete()
            runCatching {
                val procConfigGz = File("/proc/config.gz")
                if (procConfigGz.exists() && procConfigGz.canRead()) {
                    procConfigGz.inputStream().use { input ->
                        FileOutputStream(targetGz).use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        if (!isValidGzip(targetGz)) {
            targetGz.delete()
            val tmpTxt = File(staging, ".tmp_config.txt")
            safeRunShell(
                "cat /proc/config > \"${tmpTxt.absolutePath}\" 2>/dev/null || " +
                        "cat /boot/config-\$(uname -r) > \"${tmpTxt.absolutePath}\" 2>/dev/null",
                "chmod 666 \"${tmpTxt.absolutePath}\" 2>/dev/null"
            )
            if (tmpTxt.exists() && tmpTxt.length() > 200L) {
                runCatching {
                    GZIPOutputStream(FileOutputStream(targetGz)).use { gos ->
                        tmpTxt.inputStream().use { input -> input.copyTo(gos) }
                    }
                }
            }
            tmpTxt.delete()
        }

        if (!isValidGzip(targetGz)) {
            targetGz.delete()
            return "unavailable" to emptyList()
        }

        val importantPrefixes = listOf(
            "CONFIG_MODULES=",
            "CONFIG_MODVERSIONS=",
            "CONFIG_HW_BREAKPOINT=",
            "CONFIG_KALLSYMS=",
            "CONFIG_KALLSYMS_ALL=",
            "CONFIG_MODULE_UNLOAD=",
            "CONFIG_MODULE_FORCE_LOAD=",
            "CONFIG_MODULE_FORCE_UNLOAD=",
            "CONFIG_MODULE_SIG=",
            "CONFIG_MODULE_SIG_FORCE=",
            "CONFIG_MODULE_SIG_ALL=",
            "CONFIG_CFI_CLANG=",
            "CONFIG_LTO_CLANG=",
            "CONFIG_LTO_CLANG_THIN=",
            "CONFIG_LTO_CLANG_FULL=",
            "CONFIG_SHADOW_CALL_STACK=",
            "CONFIG_ARM64_PTR_AUTH=",
            "CONFIG_DEBUG_INFO_BTF=",
            "CONFIG_TRIM_UNUSED_KSYMS=",
            "CONFIG_LOCALVERSION=",
            "CONFIG_LOCALVERSION_AUTO=",
            "CONFIG_PREEMPT=",
            "CONFIG_SMP="
        )

        val flags = mutableListOf<String>()
        runCatching {
            GZIPInputStream(targetGz.inputStream()).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (importantPrefixes.any { trimmed.startsWith(it) }) {
                        flags.add(trimmed)
                    }
                }
            }
        }

        return "OK (${targetGz.length() / 1024} KB)" to flags
    }

    private fun isValidGzip(file: File): Boolean {
        if (!file.exists() || file.length() < 100L) return false
        return try {
            file.inputStream().use { s ->
                val b0 = s.read()
                val b1 = s.read()
                b0 == 0x1F && b1 == 0x8B
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun dumpKallsyms(staging: File): String {
        val target = File(staging, "kallsyms.txt")
        safeRunShell(
            "echo 0 > /proc/sys/kernel/kptr_restrict 2>/dev/null || true",
            "cat /proc/kallsyms > \"${target.absolutePath}\" 2>/dev/null",
            "chmod 666 \"${target.absolutePath}\" 2>/dev/null"
        )

        if (!target.exists() || target.length() < 500L) {
            runCatching {
                val procKallsyms = File("/proc/kallsyms")
                if (procKallsyms.exists() && procKallsyms.canRead()) {
                    procKallsyms.inputStream().use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        if (!target.exists() || target.length() < 500L) {
            target.delete()
            return "unavailable"
        }

        return "OK (${target.length() / 1024} KB)"
    }

    private fun captureStockModule(stockModulesDir: File): StockKoResult {
        stockModulesDir.mkdirs()
        val targetKo = File(stockModulesDir, "sample.ko")

        val findCmd = buildString {
            append("FOUND=\$(find /vendor/lib/modules /system/lib/modules /odm/lib/modules /vendor_dlkm/lib/modules /system_dlkm/lib/modules -type f -name '*.ko' -size +4k -size -5000k 2>/dev/null | head -n 1); ")
            append("if [ -z \"\$FOUND\" ]; then ")
            append("FOUND=\$(find /vendor/lib/modules /system/lib/modules /odm/lib/modules /vendor_dlkm/lib/modules /system_dlkm/lib/modules -name '*.ko' 2>/dev/null | head -n 1); ")
            append("fi; ")
            append("echo \"\$FOUND\"")
        }

        var foundPath = safeRunShell(findCmd)?.out?.firstOrNull()?.trim().orEmpty()

        if (foundPath.isBlank()) {
            val searchDirs = listOf(
                File("/vendor/lib/modules"),
                File("/system/lib/modules"),
                File("/odm/lib/modules"),
                File("/vendor_dlkm/lib/modules"),
                File("/system_dlkm/lib/modules")
            )
            for (dir in searchDirs) {
                if (dir.exists() && dir.isDirectory) {
                    val candidate = dir.walkTopDown()
                        .firstOrNull { it.isFile && it.name.endsWith(".ko") && it.length() > 1024L }
                    if (candidate != null) {
                        foundPath = candidate.absolutePath
                        break
                    }
                }
            }
        }

        if (foundPath.isBlank() || !foundPath.endsWith(".ko")) {
            return StockKoResult(
                status = "not found",
                sourcePath = "",
                vermagic = "",
                elfInfo = null,
                fileSize = 0L
            )
        }

        safeRunShell(
            "cat \"$foundPath\" > \"${targetKo.absolutePath}\" 2>/dev/null || cp \"$foundPath\" \"${targetKo.absolutePath}\" 2>/dev/null",
            "chmod 666 \"${targetKo.absolutePath}\" 2>/dev/null"
        )

        if (!targetKo.exists() || targetKo.length() == 0L) {
            runCatching {
                val srcFile = File(foundPath)
                if (srcFile.exists() && srcFile.canRead()) {
                    srcFile.inputStream().use { input ->
                        FileOutputStream(targetKo).use { output -> input.copyTo(output) }
                    }
                }
            }
        }

        if (!targetKo.exists() || targetKo.length() == 0L) {
            targetKo.delete()
            return StockKoResult(
                status = "failed to copy ($foundPath)",
                sourcePath = foundPath,
                vermagic = "",
                elfInfo = null,
                fileSize = 0L
            )
        }

        val bytes = try {
            targetKo.readBytes()
        } catch (_: Exception) {
            ByteArray(0)
        }

        val elfInfo = if (bytes.isNotEmpty()) ElfInfo.parse(bytes) else null
        val vermagic = elfInfo?.vermagic?.takeIf { it.isNotBlank() }
            ?: extractVermagicFromBytes(bytes)

        return StockKoResult(
            status = "OK (${targetKo.length() / 1024} KB)",
            sourcePath = foundPath,
            vermagic = vermagic,
            elfInfo = elfInfo,
            fileSize = targetKo.length()
        )
    }

    private fun extractVermagicFromBytes(bytes: ByteArray): String {
        val needle = "vermagic=".toByteArray(Charsets.US_ASCII)
        if (bytes.size < needle.size) return ""
        outer@ for (i in 0..(bytes.size - needle.size)) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            val start = i + needle.size
            var end = start
            while (end < bytes.size && bytes[end] != 0.toByte() && bytes[end] != '\n'.code.toByte()) {
                end++
            }
            if (end > start) {
                return String(bytes, start, end - start, Charsets.ISO_8859_1).trim()
            }
        }
        return ""
    }

    private fun dumpBootAndHeader(
        context: Context,
        rootAvailable: Boolean,
        bootDir: File,
        kernelRelease: String,
        kernelArch: String,
        fullVersion: String,
        cmdline: String,
        pageSize: String,
        stockKo: StockKoResult,
        configFlags: List<String>
    ): BootDumpResult {
        bootDir.mkdirs()
        val bootImgFile = File(bootDir, "boot.img")
        val kernelHeaderFile = File(bootDir, "kernel_header.txt")

        val slot = getProp("ro.boot.slot_suffix").ifBlank {
            getProp("ro.boot.slot").let { if (it.isNotBlank() && !it.startsWith("_")) "_$it" else it }
        }.trim()

        val resolveScript = buildString {
            append("SLOT=\"$slot\"; ")
            append("BOOT_LINK=\"\"; ")
            append("for p in \"/dev/block/by-name/boot\$SLOT\" \"/dev/block/bootdevice/by-name/boot\$SLOT\" \"/dev/block/by-name/boot\" \"/dev/block/bootdevice/by-name/boot\" /dev/block/platform/*/by-name/boot\$SLOT /dev/block/platform/*/by-name/boot \"/dev/block/mapper/boot\$SLOT\" \"/dev/block/mapper/boot\"; do ")
            append("  if [ -e \"\$p\" ] || [ -L \"\$p\" ]; then BOOT_LINK=\"\$p\"; break; fi; ")
            append("done; ")
            append("BOOT_PART=\$(ls -l \"\$BOOT_LINK\" 2>/dev/null | awk '{print \$NF}'); ")
            append("if [ -z \"\$BOOT_PART\" ] || [ ! -b \"\$BOOT_PART\" ]; then ")
            append("  BOOT_PART=\$(readlink -f \"\$BOOT_LINK\" 2>/dev/null || echo \"\$BOOT_LINK\"); ")
            append("fi; ")
            append("echo \"\$BOOT_LINK|\$BOOT_PART\"")
        }

        val resolvedLine = if (rootAvailable) {
            safeRunShell(resolveScript)?.out?.firstOrNull()?.trim().orEmpty()
        } else ""

        val bootLink = resolvedLine.substringBefore("|", "").trim()
        val bootPart = resolvedLine.substringAfter("|", "").trim().ifBlank {
            if (rootAvailable) DeviceReportCollector.detectBootPartition().first else ""
        }

        var bootStatus = "skipped (no root)"
        var headerBytes = ByteArray(0)

        if (rootAvailable && bootPart.isNotBlank()) {
            val freeSpace = context.cacheDir.usableSpace
            if (freeSpace < 80L * 1024L * 1024L) {
                bootStatus = "skipped (low storage: ${freeSpace / (1024 * 1024)} MB)"
            } else {

                safeRunShell(
                    "dd if=\"$bootPart\" of=\"${bootImgFile.absolutePath}\" bs=4096 count=16384 2>/dev/null",
                    "chmod 666 \"${bootImgFile.absolutePath}\" 2>/dev/null"
                )
                if (bootImgFile.exists() && bootImgFile.length() > 65536L) {
                    bootStatus = "OK (${bootImgFile.length() / (1024 * 1024)} MB)"
                    headerBytes = try {
                        bootImgFile.inputStream().use { s ->
                            val buf = ByteArray(4096)
                            val n = s.read(buf)
                            if (n > 0) buf.copyOf(n) else ByteArray(0)
                        }
                    } catch (_: Exception) {
                        ByteArray(0)
                    }
                } else {
                    bootImgFile.delete()
                    bootStatus = "failed (could not read $bootPart)"
                }
            }
        } else if (rootAvailable) {
            bootStatus = "boot block device not found"
        }

        val parsedHeaderInfo = parseBootHeaderDetails(headerBytes)
        val headerShortVm = RootChecker.getShortVermagic(
            vermagic = stockKo.vermagic,
            kernelRelease = kernelRelease
        )

        val headerText = buildString {
            appendLine("# ========================================================")
            appendLine("# KERNEL & BOOT HEADER DIAGNOSTIC (boot/kernel_header.txt)")
            appendLine("# ========================================================")
            appendLine()
            appendLine("[KERNEL IDENTIFICATION]")
            appendLine("kernel_release      = $kernelRelease")
            appendLine("kernel_arch         = $kernelArch")
            appendLine("page_size           = $pageSize")
            appendLine("full_version        = $fullVersion")
            appendLine("cmdline             = $cmdline")
            appendLine("vermagic            = ${stockKo.vermagic.ifBlank { "(unavailable)" }}")
            appendLine("short_vermagic      = $headerShortVm")
            appendLine()
            appendLine("[BOOT PARTITION & IMAGE]")
            appendLine("slot_suffix         = ${slot.ifBlank { "none" }}")
            appendLine("boot_symlink        = ${bootLink.ifBlank { "/dev/block/by-name/boot" }}")
            appendLine("boot_block_device   = ${bootPart.ifBlank { "(not resolved)" }}")
            appendLine("boot_dump_cmd       = dd if=${bootPart.ifBlank { "\$BOOT_PART" }} of=boot.img bs=4096 count=16384")
            appendLine("boot_dump_status    = $bootStatus")
            appendLine("boot_dumped_bytes   = ${if (bootImgFile.exists()) bootImgFile.length() else 0L}")
            appendLine()
            appendLine("[ANDROID BOOT IMAGE HEADER]")
            appendLine(parsedHeaderInfo)
            appendLine()
            appendLine("[STOCK MODULE METADATA (stock_modules/sample.ko)]")
            appendLine("source_path         = ${stockKo.sourcePath.ifBlank { "(not found)" }}")
            appendLine("file_size_bytes     = ${stockKo.fileSize}")
            val elf = stockKo.elfInfo
            if (elf != null) {
                appendLine("module_name         = ${elf.moduleName}")
                appendLine("vermagic            = ${elf.vermagic}")
                appendLine("modversions         = ${elf.modversions}")
                appendLine("cfi_mode            = ${elf.cfi}")
                appendLine("module_layout_crc   = ${elf.moduleLayoutCrc?.let { "0x%08x".format(it) } ?: "none"}")
                appendLine("build_id            = ${elf.buildId}")
                appendLine("undefined_symbols   = ${elf.undefinedSymbols.size}")
                appendLine("version_crc_count   = ${elf.versions.size}")
                if (elf.versions.isNotEmpty()) {
                    appendLine()
                    appendLine("[STOCK MODULE __versions CRC TABLE]")
                    elf.versions.forEach { (sym, crc) ->
                        appendLine("0x%08x\t%s".format(crc, sym))
                    }
                }
            }
            if (configFlags.isNotEmpty()) {
                appendLine()
                appendLine("[KEY KERNEL CONFIG FLAGS]")
                configFlags.forEach { appendLine(it) }
            }
            if (headerBytes.isNotEmpty()) {
                appendLine()
                appendLine("[BOOT HEADER HEX DUMP (FIRST 128 BYTES)]")
                val limit = minOf(headerBytes.size, 128)
                for (offset in 0 until limit step 16) {
                    val chunk = headerBytes.sliceArray(offset until minOf(offset + 16, limit))
                    val hex = chunk.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
                    val ascii = chunk.map { b ->
                        val c = b.toInt() and 0xFF
                        if (c in 32..126) c.toChar() else '.'
                    }.joinToString("")
                    appendLine("%04x: %-48s |%s|".format(offset, hex, ascii))
                }
            }
        }

        kernelHeaderFile.writeText(headerText)

        return BootDumpResult(
            status = bootStatus,
            bootLink = bootLink,
            bootPart = bootPart,
            slotSuffix = slot,
            dumpedBytes = if (bootImgFile.exists()) bootImgFile.length() else 0L,
            headerSummary = parsedHeaderInfo
        )
    }

    private fun parseBootHeaderDetails(headerBytes: ByteArray): String {
        if (headerBytes.size < 64) {
            return "header_present      = false (boot.img not dumped or unreadable)"
        }
        val magic = String(headerBytes, 0, 8, Charsets.US_ASCII)
        if (magic != "ANDROID!") {
            return "header_present      = true\nmagic               = ${magic.replace(Regex("[^\\x20-\\x7E]"), ".")} (non-standard magic)"
        }

        fun u32(offset: Int): Long {
            if (offset + 4 > headerBytes.size) return 0L
            return (headerBytes[offset].toLong() and 0xFF) or
                    ((headerBytes[offset + 1].toLong() and 0xFF) shl 8) or
                    ((headerBytes[offset + 2].toLong() and 0xFF) shl 16) or
                    ((headerBytes[offset + 3].toLong() and 0xFF) shl 24)
        }

        val headerVer = u32(40)
        return buildString {
            appendLine("magic               = ANDROID!")
            appendLine("header_version      = $headerVer")
            if (headerVer in 3L..4L) {
                val kernelSize = u32(8)
                val ramdiskSize = u32(12)
                val osVersion = u32(16)
                val headerSize = u32(20)
                val cmdline = if (headerBytes.size >= 64 + 512) {
                    String(headerBytes, 64, 512, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
                } else ""
                appendLine("kernel_size         = $kernelSize bytes")
                appendLine("ramdisk_size        = $ramdiskSize bytes")
                appendLine("header_size         = $headerSize bytes")
                appendLine("os_version_raw      = 0x%08x".format(osVersion))
                if (cmdline.isNotBlank()) {
                    appendLine("header_cmdline      = $cmdline")
                }
            } else {
                val kernelSize = u32(8)
                val kernelAddr = u32(12)
                val ramdiskSize = u32(16)
                val ramdiskAddr = u32(20)
                val secondSize = u32(24)
                val pageSize = u32(36)
                val osVersion = u32(44)
                val cmdline = if (headerBytes.size >= 64 + 512) {
                    String(headerBytes, 64, 512, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
                } else ""
                appendLine("kernel_size         = $kernelSize bytes")
                appendLine("kernel_addr         = 0x%08x".format(kernelAddr))
                appendLine("ramdisk_size        = $ramdiskSize bytes")
                appendLine("ramdisk_addr        = 0x%08x".format(ramdiskAddr))
                appendLine("second_size         = $secondSize bytes")
                appendLine("page_size           = $pageSize bytes")
                appendLine("os_version_raw      = 0x%08x".format(osVersion))
                if (cmdline.isNotBlank()) {
                    appendLine("header_cmdline      = $cmdline")
                }
            }
        }.trimEnd()
    }

    private fun buildDeviceInfoJson(
        zipName: String,
        model: String,
        brand: String,
        manufacturer: String,
        androidVersion: String,
        sdkApi: String,
        cpuAbi: String,
        socChipset: String,
        kernelRelease: String,
        fullVersion: String,
        kernelArch: String,
        vermagic: String,
        shortVermagic: String,
        securityPatch: String,
        fingerprint: String,
        pageSize: String,
        cmdline: String,
        rootAvailable: Boolean,
        rootType: String,
        stockKo: StockKoResult,
        bootResult: BootDumpResult,
        configStatus: String,
        kallsymsStatus: String,
        configFlags: List<String>,
        matchStatus: String,
        resultText: String,
        collectedAt: String
    ): String {
        val root = JSONObject()

        root.put("model", model)
        root.put("brand", brand)
        root.put("manufacturer", manufacturer)
        root.put("android_version", androidVersion)
        root.put("sdk_api", sdkApi)
        root.put("cpu_abi", cpuAbi)
        root.put("soc_chipset", socChipset)
        root.put("kernel_release", kernelRelease)
        root.put("full_version", fullVersion)

        root.put("Model", model)
        root.put("Brand", brand)
        root.put("Manufacturer", manufacturer)
        root.put("Android Version", androidVersion)
        root.put("SDK API", sdkApi)
        root.put("CPU ABI", cpuAbi)
        root.put("SoC / Chipset", socChipset)
        root.put("Kernel Release", kernelRelease)
        root.put("Full Version", fullVersion)

        root.put("zip_name", zipName)
        root.put("kernel_arch", kernelArch)
        root.put("vermagic", vermagic)
        root.put("short_vermagic", shortVermagic)
        root.put("security_patch", securityPatch)
        root.put("fingerprint", fingerprint)
        root.put("page_size", pageSize)
        root.put("cmdline", cmdline)
        root.put("root_available", rootAvailable)
        root.put("root_type", rootType)
        root.put("db_match_status", matchStatus)
        root.put("last_result", resultText)
        root.put("collected_at", collectedAt)
        root.put("app_version", "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

        val stockModJson = JSONObject().apply {
            put("zip_path", "stock_modules/sample.ko")
            put("device_source_path", stockKo.sourcePath)
            put("status", stockKo.status)
            put("size_bytes", stockKo.fileSize)
            put("vermagic", stockKo.vermagic)
            stockKo.elfInfo?.let { elf ->
                put("module_name", elf.moduleName)
                put("modversions", elf.modversions)
                put("cfi", elf.cfi)
                put("module_layout_crc", elf.moduleLayoutCrc?.let { "0x%08x".format(it) } ?: "")
                put("crc_count", elf.versions.size)
            }
        }
        root.put("stock_module", stockModJson)

        val bootJson = JSONObject().apply {
            put("zip_path", "boot/boot.img")
            put("header_zip_path", "boot/kernel_header.txt")
            put("boot_symlink", bootResult.bootLink)
            put("boot_block_device", bootResult.bootPart)
            put("slot_suffix", bootResult.slotSuffix)
            put("status", bootResult.status)
            put("dumped_bytes", bootResult.dumpedBytes)
        }
        root.put("boot", bootJson)

        val filesJson = JSONObject().apply {
            put("device_info.json", "OK")
            put("config.gz", configStatus)
            put("kallsyms.txt", kallsymsStatus)
            put("stock_modules/sample.ko", stockKo.status)
            put("boot/boot.img", bootResult.status)
            put("boot/kernel_header.txt", "OK")
        }
        root.put("package_contents", filesJson)

        val flagsArray = JSONArray()
        configFlags.forEach { flagsArray.put(it) }
        root.put("config_flags", flagsArray)

        return root.toString(2)
    }

    private fun getProp(prop: String): String {
        val v = runCmdOut("getprop $prop")
        if (v.isNotBlank()) return v
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", prop))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out
        } catch (_: Exception) {
            ""
        }
    }

    private fun runCmdOut(cmd: String): String {
        val shellRes = safeRunShell(cmd)?.out?.joinToString("\n")?.trim().orEmpty()
        if (shellRes.isNotBlank()) return shellRes
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            out
        } catch (_: Exception) {
            ""
        }
    }

    private fun safeRunShell(vararg commands: String): Shell.Result? = try {
        Shell.cmd(*commands).exec()
    } catch (_: Exception) {
        null
    }
}
