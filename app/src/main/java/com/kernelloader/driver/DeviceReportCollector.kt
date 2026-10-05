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
        "/data/adb/kloder/driver_rt.ko",
        "/data/adb/kloder/driver_qx.ko",
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
        val rootType: String,
        val bundleTried: String,
        val configFlags: List<String>,
        val vermagic: String,
        val vermagicSource: String,
        val sourceUrl: String,
        val loadedOurs: List<String>,
        val dmesgTail: List<String>,
        val lastError: String,
        val collectedAt: String
    ) {
        private val kernelShort: String =
            Regex("""\d+\.\d+\.\d+""").find(kernelRelease)?.value ?: "unknown"

        // "4.14.284-LONGSU-gffbd2268fef7" -> "4.14.284-LONGSU"
        val kernelCaption: String =
            Regex("""\d+\.\d+\.\d+(?:-[A-Za-z0-9._]+)?""").find(kernelRelease)?.value ?: kernelShort

        val deviceLine: String get() = "$manufacturer $model".trim()

        val issueTitle: String =
            "[BUILD REQUEST] $manufacturer $model — $kernelShort ($kernelArch)"

        fun buildText(
            appName: String,
            appVersion: String,
            matchStatus: String,
            resultText: String,
            logText: String,
            kcrcNote: String = ""
        ): String = buildString {
            appendLine("⚡ $appName — AUTO BUILD REQUEST")
            appendLine("═══════════════════════════")
            appendLine("KERNEL    : $kernelRelease")
            appendLine("VERSION   : ${procVersion.take(220)}")
            appendLine("ARCH      : $kernelArch")
            appendLine("PAGE SIZE : $pageSize")
            appendLine("DEVICE    : $deviceLine (board: $board, soc: $soc)")
            appendLine("ANDROID   : $androidVersion (API $sdkInt)")
            appendLine("FP        : ${fingerprint.take(140)}")
            appendLine("ROOT      : ${if (rootAvailable) "yes ($rootType)" else "no"}")
            appendLine("BUNDLE    : ${bundleTried.ifBlank { "auto (RT → QX → built-in)" }}")
            appendLine("DB MATCH  : $matchStatus")
            appendLine("VERMAGIC  : ${vermagic.ifBlank { procVersion.take(150) }}")
            if (vermagic.isNotBlank()) {
                appendLine("  (from: $vermagicSource)")
            }
            appendLine("SOURCE    : ${sourceUrl.ifBlank { "unknown" }}")
            appendLine("RESULT    : ${lastError.ifBlank { resultText }.take(320)}")
            appendLine("DMESG     :")
            if (dmesgTail.isEmpty()) appendLine("  (unavailable)")
            else dmesgTail.forEach { appendLine("  $it") }
            appendLine("APP LOG   :")
            val tail = logText.lines().filter { it.isNotBlank() }.takeLast(50)
            if (tail.isEmpty()) appendLine("  (empty)")
            else tail.forEach { appendLine("  $it") }
            if (configFlags.isNotEmpty()) {
                appendLine("CONFIG    :")
                configFlags.forEach { appendLine("  $it") }
            }
            if (kcrcNote.isNotBlank()) appendLine("KCRC      : $kcrcNote")
            if (loadedOurs.isNotEmpty()) {
                appendLine("LOADED    : ${loadedOurs.joinToString(", ")}")
            }
            appendLine("APP       : $appName $appVersion · $collectedAt")
        }
    }

    fun collect(
        rootAvailable: Boolean,
        loadedModules: List<String>,
        bundleTried: String = "",
        lastError: String = ""
    ): DeviceReport {
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

        val dmesgTail = cmdOut(
            "dmesg 2>/dev/null | grep -iE 'insmod|module|vermagic|kmem|kloader|entryi|daisy|wanbai|symbol' | tail -n 20"
        )
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(20)
            .ifEmpty {
                cmdOut("dmesg 2>/dev/null | tail -n 20")
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .take(20)
            }

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
            rootType = if (rootAvailable) detectRootType() else "",
            bundleTried = bundleTried,
            configFlags = configFlags,
            vermagic = vermagic,
            vermagicSource = vermagicSource,
            sourceUrl = Regex("""https?://[^\s"']+""").find(procVersion)?.value.orEmpty(),
            loadedOurs = loadedModules.filter { it.isNotBlank() },
            dmesgTail = dmesgTail,
            lastError = lastError.trim(),
            collectedAt = dateFormat.format(Date())
        )
    }

    private fun detectRootType(): String {
        val suVersion = cmdOut("su -v 2>/dev/null").lineSequence().firstOrNull().orEmpty().trim()
        val suLower = suVersion.lowercase(Locale.US)
        return when {
            suLower.contains("magisk") -> suVersion.take(48)
            suLower.contains("kernelsu") || suLower.contains("ksu") -> suVersion.take(48)
            suLower.contains("apatch") -> suVersion.take(48)
            Shell.cmd("test -d /data/adb/magisk 2>/dev/null").exec().isSuccess ->
                "Magisk ${suVersion.take(24)}".trim()
            Shell.cmd("test -d /data/adb/ksu 2>/dev/null").exec().isSuccess ->
                "KernelSU ${suVersion.take(24)}".trim()
            Shell.cmd("test -d /data/adb/ap 2>/dev/null").exec().isSuccess ->
                "APatch ${suVersion.take(24)}".trim()
            suVersion.isNotBlank() -> "su (${suVersion.take(48)})"
            else -> "yes (unknown su)"
        }
    }

    private fun cmdOut(cmd: String): String = try {
        Shell.cmd(cmd).exec().out
            .joinToString("\n")
            .trim()
    } catch (_: Exception) {
        ""
    }
}
