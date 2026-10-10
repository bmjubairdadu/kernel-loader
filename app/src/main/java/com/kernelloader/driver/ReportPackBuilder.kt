package com.kernelloader.driver

import android.content.Context
import com.kernelloader.BuildConfig
import com.topjohnwu.superuser.Shell
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ReportPackBuilder {

    // the dynamic-PIE extractor builds are ~10 KB; a larger cached copy is a
    // stale static build, a smaller one is a truncated/HTML download
    private const val MIN_KCRC_SIZE = 4096
    private const val MAX_KCRC_SIZE = 64 shl 20

    private val KCRC_MIRRORS = listOf(
        "https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/drivers/tools/",
        "https://cdn.jsdelivr.net/gh/bmjubairdadu/kernel-loader@drivers/tools/",
        "https://github.com/bmjubairdadu/kernel-loader/raw/refs/heads/drivers/tools/"
    )

    // ELF e_machine values so an arm build never runs on an arm64 shell
    private const val ELF_MACHINE_AARCH64 = 0xB7
    private const val ELF_MACHINE_ARM = 0x28

    private const val KCRC_TMP = "/data/local/tmp/.kl_kcrc_dump"
    private const val SYMVERS_TMP = "/data/local/tmp/.kl_symvers.txt"
    private const val CONFIG_TMP = "/data/local/tmp/.kl_config.txt"

    data class ReportPack(
        val zipFile: File?,
        val zipName: String,
        val caption: String,
        val fullText: String,
        val kcrcStatus: String
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

        val dir = File(context.cacheDir, "report")
        dir.deleteRecursively()
        dir.mkdirs()

        var kcrcStatus = ""

        // --- dmesg.txt : lines around the insmod error ---
        onStep("Collecting dmesg...")
        File(dir, "dmesg.txt").writeText(
            (report.dmesgTail + (if (report.lastError.isNotBlank()) listOf("", "LAST ERROR: ${report.lastError}") else emptyList()))
                .joinToString("\n")
                .ifBlank { "(unavailable)" }
        )

        // --- applog.txt : the app's own terminal log ---
        File(dir, "applog.txt").writeText(
            logText.lines().filter { it.isNotBlank() }.takeLast(200).joinToString("\n").ifBlank { "(empty)" }
        )

        // --- config.txt : full kernel config from /proc/config.gz ---
        onStep("Dumping kernel config...")
        val configOk = try {
            Shell.cmd(
                "zcat /proc/config.gz > $CONFIG_TMP 2>/dev/null",
                "[ -s $CONFIG_TMP ] || cat /proc/config > $CONFIG_TMP 2>/dev/null",
                "[ -s $CONFIG_TMP ] && echo CONFIG_OK || echo CONFIG_MISSING"
            ).exec()
        } catch (_: Exception) {
            null
        }
        val configOkFlag = configOk?.out?.any { it.contains("CONFIG_OK") } == true
        if (configOkFlag) {
            Shell.cmd("cp $CONFIG_TMP \"${File(dir, "config.txt").absolutePath}\" 2>/dev/null").exec()
        }
        if (!File(dir, "config.txt").exists() || File(dir, "config.txt").length() < 500L) {
            File(dir, "config_unavailable.txt").writeText(
                "kernel config could not be read from /proc/config.gz or /proc/config\n" +
                    "(kernel built without CONFIG_IKCONFIG?)"
            )
        }

        // --- Module.symvers.txt : CRC table via kcrc_dump ---
        if (!rootAvailable) {
            kcrcStatus = "root missing - kcrc_dump skipped"
        } else {
            onStep("Preparing kcrc_dump (CRC extractor)...")
            val tool = ensureKcrcDump(context)
            if (tool == null) {
                kcrcStatus = "kcrc_dump download failed (all mirrors)"
            } else {
                onStep("Running kcrc_dump on the kernel...")
                kcrcStatus = runKcrcDump(tool, dir)
            }
        }

        // --- report.txt : the full 14-field template + SOURCE ---
        File(dir, "report.txt").writeText(fullText)

        onStep("Packing report zip...")
        val zipName = "report_${report.kernelRelease.ifBlank { "unknown" }}.zip"
            .replace(Regex("""[^A-Za-z0-9._-]"""), "_")
        var zipFile: File? = null
        try {
            val zip = File(dir, zipName)
            ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { zos ->
                dir.listFiles { f -> f.isFile && f.name != zipName }
                    ?.sortedBy { it.name }
                    ?.forEach { f ->
                        zos.putNextEntry(ZipEntry(f.name))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
            }
            if (zip.length() > 0L) zipFile = zip
        } catch (_: Exception) {
            zipFile = null
        }

        // the zip carries everything - the loose parts are residue, drop them
        // as soon as the zip exists (the WhatsApp share reads only the zip)
        zipFile?.let { zip ->
            dir.listFiles { f -> f.isFile && f.name != zip.name }?.forEach { it.delete() }
        }

        Shell.cmd("rm -f $KCRC_TMP $SYMVERS_TMP $CONFIG_TMP 2>/dev/null").exec()

        return ReportPack(
            zipFile = zipFile,
            zipName = zipName,
            caption = caption,
            fullText = fullText,
            kcrcStatus = kcrcStatus
        )
    }

    /** Removes every trace of a previous report, the zip included. */
    fun wipeReportDir(context: Context) {
        runCatching { File(context.cacheDir, "report").deleteRecursively() }
    }

    private fun runKcrcDump(tool: File, outDir: File): String {
        val staged = Shell.cmd(
            "cp \"${tool.absolutePath}\" $KCRC_TMP",
            "chmod 755 $KCRC_TMP",
            "chown root:root $KCRC_TMP 2>/dev/null",
            "rm -f $SYMVERS_TMP"
        ).exec()
        if (!staged.isSuccess) {
            return "kcrc_dump staging failed (exit ${staged.code})"
        }
        val run = try {
            Shell.cmd("$KCRC_TMP $SYMVERS_TMP").exec()
        } catch (e: Exception) {
            return "kcrc_dump error: ${e.message}"
        }
        val statusLines = (run.out + run.err)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val entriesLine = statusLines.firstOrNull { it.contains("entries=") }
        val target = File(outDir, "Module.symvers.txt")
        Shell.cmd("cp $SYMVERS_TMP \"${target.absolutePath}\" 2>/dev/null").exec()
        val ok = target.length() > 1000L
        return if (ok) {
            "kcrc_dump OK · ${entriesLine ?: "${target.length()} bytes"}"
        } else {
            target.delete()
            File(outDir, "kcrc_status.txt").writeText(
                (statusLines.joinToString("\n").ifBlank { "exit ${run.code}" }) +
                    "\n\n" + if (statusLines.any { it.contains("no __kcrctab_", true) }) {
                    "kernel built WITHOUT MODVERSIONS - no CRC file needed for a rebuild"
                } else {
                    "kcrc_dump could not extract the CRC table - send this output to the builder"
                }
            )
            statusLines.joinToString(" | ").ifBlank { "kcrc_dump failed (exit ${run.code})" }.take(280)
        }
    }

    private fun ensureKcrcDump(context: Context): File? {
        val remote = if (is32BitShell()) "kcrc_dump-arm" else "kcrc_dump"
        val wantMachine = if (remote == "kcrc_dump-arm") ELF_MACHINE_ARM else ELF_MACHINE_AARCH64
        val urls = KCRC_MIRRORS.map { it + remote }
        val dir = File(context.filesDir, "tools").apply { mkdirs() }
        val cached = File(dir, remote)

        if (cached.exists() && isElf(cached, wantMachine) &&
            cached.length() in MIN_KCRC_SIZE..MAX_KCRC_SIZE
        ) {
            // the extractor ships on the drivers branch and can be rebuilt
            // without an app update - refresh when the published size changes
            val remoteSize = headContentLength(urls.first())
            if (remoteSize == null || remoteSize == cached.length()) return cached
            cached.delete()
        }

        for (url in urls) {
            val tmp = File(dir, "$remote.tmp")
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 60000
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
                        // drop the other arch's stale copy
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

    private fun headContentLength(url: String): Long? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "HEAD"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "KernelLoder-OTA/1.0")
        }
        conn.connect()
        val len = if (conn.responseCode in 200..299) {
            conn.contentLengthLong.takeIf { it > 0 }
        } else null
        conn.disconnect()
        len
    } catch (_: Exception) {
        null
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
}
