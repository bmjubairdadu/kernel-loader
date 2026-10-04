package com.kernelloader.driver

import android.os.Build
import com.kernelloader.root.RootChecker
import com.topjohnwu.superuser.Shell
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DeviceReportCollector {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    private val VERMAGIC_KO_CANDIDATES = listOf(
        "/data/adb/kloder/driver.ko",
        "/data/local/tmp/driver.ko",
        "/vendor/lib/modules/*.ko",
        "/odm/lib/modules/*.ko",
        "/system/lib/modules/*.ko"
    )

    data class DeviceReport(
        val kernelRelease: String,
        val kernelArch: String,
        val procVersion: String,
        val manufacturer: String,
        val model: String,
        val board: String,
        val soc: String,
        val fingerprint: String,
        val androidVersion: String,
        val sdkInt: Int,
        val pageSize: String,
        val rootAvailable: Boolean,
        val configFlags: List<String>,
        val vermagic: String,
        val vermagicSource: String,
        val loadedOurs: List<String>,
        val dmesgTail: List<String>,
        val collectedAt: String
    ) {
        private val kernelShort: String =
            Regex("""\d+\.\d+\.\d+""").find(kernelRelease)?.value ?: "unknown"

        val issueTitle: String =
            "[BUILD REQUEST] $manufacturer $model — $kernelShort ($kernelArch)"

        fun buildText(
            appName: String,
            appVersion: String,
            matchStatus: String,
            resultText: String,
            logText: String
        ): String = buildString {
            appendLine("⚡ $appName — AUTO BUILD REQUEST")
            appendLine("═══════════════════════════")
            appendLine("KERNEL    : $kernelRelease")
            appendLine("ARCH      : $kernelArch")
            appendLine("PAGE SIZE : $pageSize")
            if (procVersion.isNotBlank()) {
                appendLine("VERSION   : ${procVersion.take(160)}")
            }
            appendLine("DEVICE    : $manufacturer $model (board: $board, soc: $soc)")
            appendLine("ANDROID   : $androidVersion (API $sdkInt)")
            if (fingerprint.isNotBlank()) appendLine("FP        : ${fingerprint.take(110)}")
            appendLine("ROOT      : ${if (rootAvailable) "yes" else "no"}")
            appendLine("DB MATCH  : $matchStatus")
            if (vermagic.isNotBlank()) {
                appendLine("VERMAGIC  : $vermagic")
                appendLine("  (from: $vermagicSource)")
            }
            if (configFlags.isNotEmpty()) {
                appendLine("CONFIG FLAGS:")
                configFlags.forEach { appendLine("  $it") }
            }
            if (loadedOurs.isNotEmpty()) {
                appendLine("LOADED    : ${loadedOurs.joinToString(", ")}")
            }
            if (dmesgTail.isNotEmpty()) {
                appendLine("DMESG TAIL:")
                dmesgTail.forEach { appendLine("  ${it.take(120)}") }
            }
            if (resultText.isNotBlank()) appendLine("RESULT    : ${resultText.take(100)}")
            appendLine("APP       : $appName $appVersion · $collectedAt")
            val tail = logText.lines().filter { it.isNotBlank() }.takeLast(12).joinToString("\n")
            if (tail.isNotBlank()) {
                appendLine("LOG TAIL:")
                append(tail)
            }
        }
    }

    fun collect(rootAvailable: Boolean, loadedModules: List<String>): DeviceReport {
        val kernelRelease = cmdOut("uname -r")
        val arch = cmdOut("uname -m")
        val procVersion = cmdOut("cat /proc/version").lineSequence().firstOrNull().orEmpty()
        val soc = cmdOut("getprop ro.soc.model").ifBlank {
            cmdOut("getprop ro.board.platform")
        }
        val pageSize = cmdOut("getconf PAGESIZE")

        val configFlags = cmdOut(
            "zcat /proc/config.gz 2>/dev/null | " +
                "grep -E '^CONFIG_(SMP|PREEMPT|PREEMPT_DYNAMIC|MODVERSIONS|MODULE_UNLOAD|MODULE_SIG|MODULE_COMPRESS_XZ|LOCALVERSION|LOCALVERSION_AUTO|TRIM_UNUSED_KSYMS)=' || " +
                "grep -E '^CONFIG_(SMP|PREEMPT|PREEMPT_DYNAMIC|MODVERSIONS|MODULE_UNLOAD|MODULE_SIG|LOCALVERSION|LOCALVERSION_AUTO|TRIM_UNUSED_KSYMS)=' /proc/config 2>/dev/null"
        )
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("CONFIG_") }
            .distinct()
            .take(14)

        var vermagic = ""
        var vermagicSource = ""
        for (candidate in VERMAGIC_KO_CANDIDATES) {
            val file = cmdOut("ls $candidate 2>/dev/null | head -n 1")
                .lineSequence().firstOrNull()?.trim().orEmpty()
            if (file.isBlank()) continue
            val raw = cmdOut(
                "cat \"$file\" 2>/dev/null | (strings 2>/dev/null || /data/adb/magisk/busybox strings 2>/dev/null) | grep -m1 vermagic="
            )
            val v = raw.substringAfter("vermagic=").trim()
            if (v.isNotBlank()) {
                vermagic = v
                vermagicSource = file
                break
            }
        }

        val dmesgTail = cmdOut("dmesg 2>/dev/null | tail -n 5")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(5)

        return DeviceReport(
            kernelRelease = kernelRelease.ifBlank { RootChecker.getKernelRelease().orEmpty() },
            kernelArch = arch,
            procVersion = procVersion,
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase(Locale.US) },
            model = Build.MODEL,
            board = Build.BOARD,
            soc = soc,
            fingerprint = Build.FINGERPRINT.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            pageSize = pageSize,
            rootAvailable = rootAvailable,
            configFlags = configFlags,
            vermagic = vermagic,
            vermagicSource = vermagicSource,
            loadedOurs = loadedModules.filter { it.isNotBlank() },
            dmesgTail = dmesgTail,
            collectedAt = dateFormat.format(Date())
        )
    }

    private fun cmdOut(cmd: String): String = try {
        Shell.cmd(cmd).exec().out
            .joinToString("\n")
            .trim()
    } catch (_: Exception) {
        ""
    }
}
