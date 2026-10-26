package com.kernelloader.driver

import android.content.Context
import android.os.Process
import com.kernelloader.BuildConfig
import com.topjohnwu.superuser.Shell
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ReportPackBuilder {

    private const val MIN_KCRC_SIZE = 4096
    private const val MAX_KCRC_SIZE = 64 shl 20

    private val KCRC_MIRRORS = listOf(
        "https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/drivers/tools/",
        "https://cdn.jsdelivr.net/gh/bmjubairdadu/kernel-loader@drivers/tools/",
        "https://github.com/bmjubairdadu/kernel-loader/raw/refs/heads/drivers/tools/"
    )

    private const val ELF_MACHINE_AARCH64 = 0xB7
    private const val ELF_MACHINE_ARM = 0x28

    private const val KCRC_TMP = "/data/local/tmp/.kl_kcrc_dump"
    private const val SYMVERS_TMP = "/data/local/tmp/.kl_symvers.txt"

    data class ReportPack(
        val zipFile: File?,
        val zipName: String,
        val caption: String,
        val fullText: String,
        val kcrcStatus: String,
        val manifestSummary: String = ""
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

        try {
            staging.deleteRecursively()
            staging.mkdirs()
            output.mkdirs()
            output.listFiles()?.forEach { if (it.name.endsWith(".zip")) it.delete() }
        } catch (_: Exception) {}

        onStep("Collecting device info...")
        val report = DeviceReportCollector.collect(
            rootAvailable = rootAvailable,
            loadedModules = listOf(loadedModule),
            bundleTried = bundleTried,
            lastError = resultText
        )
        val fullText = report.buildText(
            appName = SupportContact.APP_NAME,
            appVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            matchStatus = matchStatus,
            resultText = resultText,
            logText = logText
        )

        val matchShort = when {
            matchStatus.contains("EXACT", true) -> "EXACT MATCH"
            matchStatus.contains("NO MATCH", true) -> "NO MATCH"
            else -> matchStatus.take(28)
        }
        val caption = "${report.deviceLine}, ${report.kernelCaption}, $matchShort"

        // 1. Text Reports
        File(staging, "report.txt").writeText(fullText)
        File(staging, "applog.txt").writeText(
            logText.lines().filter { it.isNotBlank() }.takeLast(300).joinToString("\n").ifBlank { "(empty)" }
        )

        onStep("Collecting dmesg...")
        val dmesgContent = (report.dmesgTail + (if (report.lastError.isNotBlank()) listOf("", "LAST ERROR: ${report.lastError}") else emptyList()))
            .joinToString("\n")
            .ifBlank { "(unavailable)" }
        File(staging, "dmesg.txt").writeText(dmesgContent)

        // 2. Kernel proc & system diagnostics
        onStep("Collecting kernel diagnostic files...")
        safeRunShell(
            "cat /proc/cmdline > \"${File(staging, "proc_cmdline.txt").absolutePath}\" 2>/dev/null",
            "cat /proc/version > \"${File(staging, "proc_version.txt").absolutePath}\" 2>/dev/null",
            "cat /proc/cpuinfo > \"${File(staging, "proc_cpuinfo.txt").absolutePath}\" 2>/dev/null",
            "cat /proc/modules > \"${File(staging, "proc_modules.txt").absolutePath}\" 2>/dev/null",
            "getprop | grep -iE 'ro\\.(product|build|board|soc|boot|hardware|arch)' > \"${File(staging, "device_props.txt").absolutePath}\" 2>/dev/null"
        )

        // 3. Kernel config
        onStep("Extracting kernel config...")
        extractKernelConfig(staging)

        // 4. Boot partition / boot.img
        var bootStatus = "skipped (no root)"
        if (rootAvailable) {
            val freeSpace = context.cacheDir.usableSpace
            if (freeSpace < 80 * 1024 * 1024L) {
                bootStatus = "skipped (low disk space: ${freeSpace / (1024 * 1024)}MB free)"
                File(staging, "boot_status.txt").writeText("Boot partition dump skipped: low disk space ($freeSpace bytes free)")
            } else {
                onStep("Locating and dumping boot.img...")
                bootStatus = dumpBootPartition(report.bootPartition, staging)
            }
        } else {
            File(staging, "boot_status.txt").writeText("Boot partition dump requires root privileges.")
        }

        // 5. Kernel symbols (/proc/kallsyms)
        var kallsymsStatus = "skipped (no root)"
        if (rootAvailable) {
            onStep("Extracting /proc/kallsyms...")
            kallsymsStatus = dumpKallsyms(staging)
        }

        // 6. Vendor kernel module sample
        onStep("Scanning vendor modules...")
        val vendorModStatus = captureVendorModule(staging)

        // 7. CRC extractor (kcrc_dump)
        var kcrcStatus = ""
        if (!rootAvailable) {
            kcrcStatus = "root missing - kcrc_dump skipped"
        } else {
            onStep("Checking CRC extractor (kcrc_dump)...")
            val tool = ensureKcrcDump(context)
            if (tool == null) {
                kcrcStatus = "kcrc_dump unavailable - kallsyms & boot.img collected"
                File(staging, "kcrc_status.txt").writeText(
                    "kcrc_dump tool could not be fetched or cached.\n" +
                    "Kernel symbols (/proc/kallsyms) and boot.img were collected instead."
                )
            } else {
                onStep("Extracting CRC table...")
                kcrcStatus = runKcrcDump(tool, staging)
            }
        }

        // 8. Build manifest
        val manifest = buildString {
            appendLine("=== KERNEL LOADER DIAGNOSTIC REPORT PACK ===")
            appendLine("DEVICE    : ${report.deviceLine} (board: ${report.board}, soc: ${report.soc})")
            appendLine("KERNEL    : ${report.kernelRelease} (${report.kernelArch})")
            appendLine("ANDROID   : ${report.androidVersion} (API ${report.sdkInt})")
            appendLine("ROOT      : ${if (report.rootAvailable) report.rootType else "No"}")
            appendLine("BOOT IMG  : $bootStatus")
            appendLine("KALLSYMS  : $kallsymsStatus")
            appendLine("VENDOR KO : $vendorModStatus")
            appendLine("CONFIG    : ${if (File(staging, "config.txt").exists()) "OK (${File(staging, "config.txt").length()} bytes)" else "unavailable"}")
            appendLine("KCRC/CRC  : $kcrcStatus")
            appendLine("TIMESTAMP : ${report.collectedAt}")
        }
        File(staging, "manifest.txt").writeText(manifest)

        // 9. CRITICAL FIX: Ensure all files in staging are readable by Java UID!
        safeRunShell(
            "chmod -R 777 \"${staging.absolutePath}\" 2>/dev/null",
            "chown -R ${Process.myUid()} \"${staging.absolutePath}\" 2>/dev/null"
        )

        // 10. Pack ZIP into output directory (SEPARATE from staging!)
        onStep("Packing report ZIP...")
        val rawZipName = "report_${report.deviceLine.replace(" ", "_")}_${report.kernelRelease.ifBlank { "unknown" }}.zip"
        val zipName = rawZipName.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        val zipTarget = File(output, zipName)

        var zipFile: File? = null
        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipTarget))).use { zos ->
                zos.setLevel(Deflater.BEST_COMPRESSION)
                val files = staging.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
                for (f in files) {
                    try {
                        if (f.length() > 0) {
                            val entry = ZipEntry(f.name).apply { time = f.lastModified() }
                            zos.putNextEntry(entry)
                            f.inputStream().use { input -> input.copyTo(zos) }
                            zos.closeEntry()
                        }
                    } catch (e: Exception) {
                        try {
                            val errEntry = ZipEntry("ERROR_${f.name}.txt")
                            zos.putNextEntry(errEntry)
                            zos.write("Failed to read ${f.name}: ${e.message}\n".toByteArray())
                            zos.closeEntry()
                        } catch (_: Exception) {}
                    }
                }
            }
            if (zipTarget.exists() && zipTarget.length() > 0L) {
                zipFile = zipTarget
            }
        } catch (_: Exception) {
            zipFile = null
        }

        // Clean staging to free device storage (reclaiming space immediately)
        try { staging.deleteRecursively() } catch (_: Exception) {}

        safeRunShell("rm -f $KCRC_TMP $SYMVERS_TMP 2>/dev/null")

        return ReportPack(
            zipFile = zipFile,
            zipName = zipName,
            caption = caption,
            fullText = fullText,
            kcrcStatus = kcrcStatus,
            manifestSummary = manifest
        )
    }

    fun wipeReportDir(context: Context) {
        runCatching {
            File(context.cacheDir, "report_pack").deleteRecursively()
            File(context.cacheDir, "report").deleteRecursively()
        }
    }

    private fun dumpBootPartition(candidatePath: String, staging: File): String {
        val bootDev = if (candidatePath.isNotBlank() && Shell.cmd("test -b '$candidatePath'").exec().isSuccess) {
            candidatePath
        } else {
            val (detected, _) = DeviceReportCollector.detectBootPartition()
            detected
        }
        if (bootDev.isBlank()) {
            File(staging, "boot_status.txt").writeText("Boot partition block device could not be identified.")
            return "boot partition block device not found"
        }

        // Read first 4KB to analyze header
        val headerFile = File(staging, "boot_header.bin")
        safeRunShell(
            "dd if=\"$bootDev\" of=\"${headerFile.absolutePath}\" bs=4096 count=1 2>/dev/null",
            "chmod 666 \"${headerFile.absolutePath}\" 2>/dev/null"
        )

        var exactSize: Long? = null
        if (headerFile.exists() && headerFile.length() >= 40) {
            exactSize = try {
                parseBootImageSize(headerFile.readBytes())
            } catch (_: Exception) { null }
        }

        val targetBoot = File(staging, "boot.img")
        val dumpCmd = if (exactSize != null && exactSize in 2_000_000L..90_000_000L) {
            val pages = (exactSize + 4095) / 4096
            "dd if=\"$bootDev\" of=\"${targetBoot.absolutePath}\" bs=4096 count=$pages 2>/dev/null"
        } else {
            "dd if=\"$bootDev\" of=\"${targetBoot.absolutePath}\" bs=1048576 count=64 2>/dev/null"
        }

        val res = safeRunShell(dumpCmd, "chmod 666 \"${targetBoot.absolutePath}\" 2>/dev/null")
        val size = if (targetBoot.exists()) targetBoot.length() else 0L

        val info = buildString {
            appendLine("DEVICE     : $bootDev")
            appendLine("HEADER OK  : ${exactSize != null}")
            if (exactSize != null) {
                appendLine("HEADER SZ  : $exactSize bytes (${exactSize / (1024 * 1024)} MB)")
            }
            appendLine("DUMPED SZ  : $size bytes (${size / (1024 * 1024)} MB)")
            appendLine("EXIT CODE  : ${res?.code ?: -1}")
        }
        File(staging, "boot_info.txt").writeText(info)

        return if (size > 1_000_000L) {
            "OK (${size / (1024 * 1024)}MB from $bootDev)"
        } else {
            targetBoot.delete()
            "failed (dumped $size bytes from $bootDev)"
        }
    }

    private fun parseBootImageSize(headerBytes: ByteArray): Long? {
        if (headerBytes.size < 40) return null
        val magic = String(headerBytes, 0, 8, Charsets.US_ASCII)
        if (magic != "ANDROID!") return null

        fun u32(offset: Int): Long {
            if (offset + 4 > headerBytes.size) return 0L
            return (headerBytes[offset].toLong() and 0xFF) or
                    ((headerBytes[offset + 1].toLong() and 0xFF) shl 8) or
                    ((headerBytes[offset + 2].toLong() and 0xFF) shl 16) or
                    ((headerBytes[offset + 3].toLong() and 0xFF) shl 24)
        }

        fun align(size: Long, page: Long): Long {
            if (page <= 0) return size
            return ((size + page - 1) / page) * page
        }

        val versionAt40 = if (headerBytes.size >= 44) u32(40) else 0L
        val versionAt28 = if (headerBytes.size >= 32) u32(28) else 0L

        if (versionAt28 in 3L..4L) {
            val kernelSize = u32(8)
            val ramdiskSize = u32(12)
            val headerSize = u32(20).takeIf { it > 0 } ?: 4096L
            val total = align(headerSize, 4096) + align(kernelSize, 4096) + align(ramdiskSize, 4096)
            if (total in 2_000_000L..90_000_000L) return total
        }

        val kernelSize = u32(8)
        val ramdiskSize = u32(16)
        val secondSize = u32(24)
        val pageSize = u32(36).takeIf { it in 1024L..16384L } ?: 2048L
        val dtbSize = if (versionAt40 >= 2L && headerBytes.size >= 1636) u32(1632) else 0L

        val total = pageSize +
                align(kernelSize, pageSize) +
                align(ramdiskSize, pageSize) +
                align(secondSize, pageSize) +
                align(dtbSize, pageSize)

        return if (total in 2_000_000L..90_000_000L) total else null
    }

    private fun dumpKallsyms(staging: File): String {
        val target = File(staging, "kallsyms.txt")
        safeRunShell(
            "echo 0 > /proc/sys/kernel/kptr_restrict 2>/dev/null",
            "cat /proc/kallsyms > \"${target.absolutePath}\" 2>/dev/null",
            "chmod 666 \"${target.absolutePath}\" 2>/dev/null"
        )
        if (!target.exists() || target.length() < 1000L) {
            target.delete()
            File(staging, "kallsyms_status.txt").writeText("Could not read /proc/kallsyms (empty or restricted).")
            return "kallsyms unavailable"
        }
        val linesCount = try {
            safeRunShell("wc -l < \"${target.absolutePath}\" 2>/dev/null")?.out?.firstOrNull()?.trim() ?: "unknown"
        } catch (_: Exception) { "unknown" }
        return "OK ($linesCount symbols, ${target.length() / 1024} KB)"
    }

    private fun captureVendorModule(staging: File): String {
        val findScript = listOf(
            "for dir in /vendor/lib/modules /system/lib/modules /odm/lib/modules /vendor/lib/modules/pronto; do",
            "  if [ -d \"\$dir\" ]; then",
            "    for f in \"\$dir\"/*.ko \"\$dir\"/*/*.ko; do",
            "      if [ -f \"\$f\" ]; then",
            "        sz=\$(stat -c %s \"\$f\" 2>/dev/null || ls -l \"\$f\" 2>/dev/null | awk '{print \$5}')",
            "        if [ -n \"\$sz\" ] && [ \"\$sz\" -ge 10000 ] && [ \"\$sz\" -le 2500000 ]; then",
            "          echo \"\$f\"",
            "          exit 0",
            "        fi",
            "      fi",
            "    done",
            "  fi",
            "done",
            "find /vendor/lib/modules /system/lib/modules -name '*.ko' 2>/dev/null | head -n 1"
        ).joinToString("\n")

        val foundKo = safeRunShell(findScript)?.out?.firstOrNull()?.trim().orEmpty()
        if (foundKo.isBlank() || !foundKo.endsWith(".ko")) {
            File(staging, "vendor_module_status.txt").writeText("No vendor .ko module found in standard directories.")
            return "no vendor module found"
        }

        val targetKo = File(staging, "sample_vendor_module.ko")
        val modinfoTarget = File(staging, "vendor_modinfo.txt")
        safeRunShell(
            "cp \"$foundKo\" \"${targetKo.absolutePath}\" 2>/dev/null",
            "chmod 666 \"${targetKo.absolutePath}\" 2>/dev/null",
            "(strings \"${targetKo.absolutePath}\" 2>/dev/null | grep -E '^(vermagic=|depends=|intree=|retpoline=|srcversion=)' || true) > \"${modinfoTarget.absolutePath}\" 2>/dev/null",
            "chmod 666 \"${modinfoTarget.absolutePath}\" 2>/dev/null"
        )

        return if (targetKo.exists() && targetKo.length() > 0) {
            "OK (${File(foundKo).name}, ${targetKo.length() / 1024} KB)"
        } else {
            "failed to copy $foundKo"
        }
    }

    private fun extractKernelConfig(staging: File) {
        val target = File(staging, "config.txt")
        safeRunShell(
            "zcat /proc/config.gz > \"${target.absolutePath}\" 2>/dev/null || " +
            "cat /proc/config > \"${target.absolutePath}\" 2>/dev/null || " +
            "zcat /boot/config-\$(uname -r) > \"${target.absolutePath}\" 2>/dev/null || " +
            "cat /boot/config-\$(uname -r) > \"${target.absolutePath}\" 2>/dev/null",
            "chmod 666 \"${target.absolutePath}\" 2>/dev/null"
        )
        if (!target.exists() || target.length() < 500L) {
            target.delete()
            File(staging, "config_unavailable.txt").writeText(
                "Kernel config could not be read from /proc/config.gz, /proc/config, or /boot/config-*\n" +
                "(Kernel built without CONFIG_IKCONFIG or /proc/config.gz unmounted)"
            )
        }
    }

    private fun runKcrcDump(tool: File, outDir: File): String {
        val staged = safeRunShell(
            "cp \"${tool.absolutePath}\" $KCRC_TMP",
            "chmod 755 $KCRC_TMP",
            "chown root:root $KCRC_TMP 2>/dev/null",
            "rm -f $SYMVERS_TMP"
        )
        if (staged?.isSuccess != true) {
            return "kcrc_dump staging failed (exit ${staged?.code ?: -1})"
        }
        val run = safeRunShell("$KCRC_TMP $SYMVERS_TMP")
        val statusLines = ((run?.out ?: emptyList()) + (run?.err ?: emptyList()))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val entriesLine = statusLines.firstOrNull { it.contains("entries=") }
        val target = File(outDir, "Module.symvers.txt")
        safeRunShell("cp $SYMVERS_TMP \"${target.absolutePath}\" 2>/dev/null", "chmod 666 \"${target.absolutePath}\" 2>/dev/null")
        val ok = target.length() > 1000L
        return if (ok) {
            "kcrc_dump OK · ${entriesLine ?: "${target.length()} bytes"}"
        } else {
            target.delete()
            File(outDir, "kcrc_status.txt").writeText(
                (statusLines.joinToString("\n").ifBlank { "exit ${run?.code ?: -1}" }) +
                    "\n\n" + if (statusLines.any { it.contains("no __kcrctab_", true) }) {
                    "kernel built WITHOUT MODVERSIONS - no CRC file needed for a rebuild"
                } else {
                    "kcrc_dump could not extract the CRC table - send this output to the builder"
                }
            )
            statusLines.joinToString(" | ").ifBlank { "kcrc_dump failed (exit ${run?.code ?: -1})" }.take(280)
        }
    }

    private fun ensureKcrcDump(context: Context): File? {
        val remote = if (is32BitShell()) "kcrc_dump-arm" else "kcrc_dump"
        val wantMachine = if (remote == "kcrc_dump-arm") ELF_MACHINE_ARM else ELF_MACHINE_AARCH64
        val dir = File(context.filesDir, "tools").apply { mkdirs() }
        val cached = File(dir, remote)

        if (cached.exists() && isElf(cached, wantMachine) &&
            cached.length() in MIN_KCRC_SIZE..MAX_KCRC_SIZE
        ) {
            return cached
        }

        val urls = KCRC_MIRRORS.map { it + remote }
        for (url in urls) {
            val tmp = File(dir, "$remote.tmp")
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "KernelLoder-OTA/1.0")
                }
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    conn.disconnect()
                    continue
                }
                conn.inputStream.use { input ->
                    FileOutputStream(tmp).use { output -> input.copyTo(output) }
                }
                conn.disconnect()
                if (tmp.length() in MIN_KCRC_SIZE..MAX_KCRC_SIZE && isElf(tmp, wantMachine)) {
                    cached.delete()
                    if (tmp.renameTo(cached)) {
                        File(dir, if (remote == "kcrc_dump") "kcrc_dump-arm" else "kcrc_dump").delete()
                        return cached
                    }
                }
                tmp.delete()
            } catch (_: Exception) {
                tmp.delete()
            }
        }
        return null
    }

    private fun is32BitShell(): Boolean = try {
        val m = Shell.cmd("uname -m").exec().out.firstOrNull()?.trim().orEmpty()
        m.startsWith("arm") && !m.startsWith("aarch64")
    } catch (_: Exception) {
        false
    }

    private fun isElf(f: File, machine: Int): Boolean = try {
        f.inputStream().use { s ->
            val h = ByteArray(20)
            if (s.read(h) != 20) false
            else h[0] == 0x7F.toByte() && h[1] == 'E'.code.toByte() &&
                h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() &&
                ((h[18].toInt() and 0xFF) or ((h[19].toInt() and 0xFF) shl 8)) == machine
        }
    } catch (_: Exception) {
        false
    }

    private fun safeRunShell(vararg commands: String): Shell.Result? = try {
        Shell.cmd(*commands).exec()
    } catch (_: Exception) {
        null
    }
}
