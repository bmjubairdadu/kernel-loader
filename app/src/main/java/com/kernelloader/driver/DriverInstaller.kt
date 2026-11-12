package com.kernelloader.driver

import android.content.Context
import java.io.File

class DriverInstaller(private val ctx: Context) {

    data class Result(
        val ok: Boolean,
        val module: String = "",
        val devName: String = "",
        val major: Int = 0,
        val node: String = "",
        val exitCode: Int = -1,
        val alreadyLoaded: Boolean = false,
        val error: String = "",
        val rawLog: String = ""
    )

    fun availableScripts(): List<String> {
        val cachedDir = File(ctx.filesDir, "drivers")
        val cachedList = if (cachedDir.exists()) {
            cachedDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".sh") }
                .map { it.name.removeSuffix(".sh") }
                .toList()
        } else emptyList()
        val rtList = ctx.assets.list("drivers/RT")
            ?.filter { it.endsWith(".sh") }
            ?.map { it.removeSuffix(".sh") }
            ?: emptyList()
        val rootList = ctx.assets.list("drivers")
            ?.filter { it.endsWith(".sh") }
            ?.map { it.removeSuffix(".sh") }
            ?: emptyList()
        return (cachedList + rtList + rootList).distinct().sorted()
    }

    fun candidates(release: String): List<String> {
        val v = release.substringBefore('-').trim()
        if (v.isEmpty()) return emptyList()
        val scored = availableScripts()
            .map { it to it.trimEnd { c -> c in 'a'..'z' } }
            .filter { (_, core) -> v == core || v.startsWith("$core.") }
        if (scored.isEmpty()) return emptyList()
        val best = scored.maxOf { it.second.length }
        return scored.filter { it.second.length == best }.map { it.first }.sorted()
    }

    fun installBest(release: String, onLine: (String) -> Unit = {}): Result {
        val list = candidates(release)
        if (list.isEmpty()) {
            return Result(ok = false, error = "no_driver_for_$release")
        }
        var last = Result(ok = false)
        for (stem in list) {
            onLine("### trying $stem for $release")
            last = install(stem, onLine)
            if (last.ok) return last
        }
        return last
    }

    fun install(assetStem: String, onLine: (String) -> Unit = {}): Result {
        val localA = File(ctx.filesDir, "drivers/$assetStem.sh")
        val localB = File(ctx.filesDir, "drivers/RT/$assetStem.sh")
        val candidateLocal = if (localA.exists()) localA else if (localB.exists()) localB else null
        if (candidateLocal != null) {
            return installFile(candidateLocal, onLine)
        }

        val work = File(ctx.cacheDir, "ftl").apply { mkdirs() }
        val scriptPath = if (ctx.assets.list("drivers/RT")?.contains("$assetStem.sh") == true) {
            "drivers/RT/$assetStem.sh"
        } else {
            "drivers/$assetStem.sh"
        }
        val safeName = "$assetStem.sh"
        copyAssetToRemote(scriptPath, safeName)
        copyAssetToRemote("sh/driver_load.sh", "driver_load.sh")
        return runWrapper(safeName, onLine)
    }

    fun installFile(sourceFile: File, onLine: (String) -> Unit = {}): Result {
        val safeName = sourceFile.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        copyToRemote(sourceFile, safeName)
        copyAssetToRemote("sh/driver_load.sh", "driver_load.sh")
        return runWrapper(safeName, onLine)
    }

    private fun copyToRemote(file: File, remoteName: String): Boolean = try {
        su("mkdir -p $REMOTE") {}
        val p = ProcessBuilder("su", "-c", "cat > '$REMOTE/$remoteName' && chmod 755 '$REMOTE/$remoteName'").start()
        file.inputStream().use { input ->
            p.outputStream.use { output ->
                input.copyTo(output)
            }
        }
        p.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun copyAssetToRemote(assetPath: String, remoteName: String): Boolean = try {
        su("mkdir -p $REMOTE") {}
        val p = ProcessBuilder("su", "-c", "cat > '$REMOTE/$remoteName' && chmod 755 '$REMOTE/$remoteName'").start()
        ctx.assets.open(assetPath).use { input ->
            p.outputStream.use { output ->
                input.copyTo(output)
            }
        }
        p.waitFor() == 0
    } catch (_: Exception) {
        false
    }

    private fun runWrapper(scriptName: String, onLine: (String) -> Unit): Result {
        val kv = HashMap<String, String>()
        val raw = StringBuilder()
        val cmd = "sh $REMOTE/driver_load.sh $REMOTE/$scriptName $MODULE"
        val rc = su(cmd) { line ->
            raw.appendLine(line)
            onLine(line)
            val eq = line.indexOf('=')
            if (line.startsWith("FTL_") && eq > 0) {
                kv[line.substring(0, eq)] = line.substring(eq + 1).trim()
            }
        }
        su("rm -f $REMOTE/$scriptName $REMOTE/driver_load.sh $REMOTE/.* 2>/dev/null") {}

        val ok = kv["FTL_OK"] == "1" && !kv["FTL_NODE"].isNullOrEmpty()
        return Result(
            ok = ok,
            module = kv["FTL_MODULE"].orEmpty(),
            devName = kv["FTL_DEVNAME"].orEmpty(),
            major = kv["FTL_MAJOR"]?.toIntOrNull() ?: 0,
            node = kv["FTL_NODE"].orEmpty(),
            exitCode = kv["FTL_EXIT"]?.toIntOrNull() ?: rc,
            alreadyLoaded = kv["FTL_ALREADY"] == "1",
            error = kv["FTL_ERROR"].orEmpty(),
            rawLog = raw.toString()
        )
    }

    fun isLoaded(module: String = MODULE): Boolean =
        su("grep -qw $module /proc/modules") {} == 0

    fun findNode(): String? {
        var found: String? = null
        val cmd = "for d in /sys/class/*/; do " +
            "cls=\$(basename \$d); " +
            "case \$cls in android_usb|avtimer|backlight|bdi|block|bluetooth|bt*|char|devfreq|diag|dma|dual_role_usb|dvb|extcon|fastrng|fastrpc|firmware|goodix*|gpio|graphics|hidraw|hwmon|i2c*|icesdcc|ieee*|input|iommu|ipa*|jpeg*|kgsl|lcd*|leds|lirc|mdio*|mem|misc|mmc*|msm*|net|phy|pmsg|power*|ppp|pwm|qseecom|ramdump|rc|regulator|rfkill|rmnet*|rotator|rtc|scsi*|smd*|sound|spmi*|ssc|subsys*|switch|thermal|tty*|uio|vc|video*|virtio*|vtconsole|wakeup|wcnss*|wwan*|BaseRemoteCtl|sensors) continue ;; esac; " +
            "if [ -c /dev/\$cls ]; then echo /dev/\$cls; break; fi; " +
            "for child in \"\$d\"*; do c=\$(basename \"\$child\"); if [ -c /dev/\$c ]; then echo /dev/\$c; break 2; fi; done; " +
            "done"
        su(cmd) { line ->
            val s = line.trim()
            if (s.startsWith("/dev/") && found == null) found = s
        }
        return found
    }

    fun client(node: String, vararg args: String, onLine: (String) -> Unit = {}): Int {
        copyAssetToRemote("bin/khack_client_arm64", "khack_client_arm64")
        val escaped = args.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val cmd = "$REMOTE/khack_client_arm64 '$node' $escaped"
        val rc = su(cmd, onLine)
        su("rm -f $REMOTE/khack_client_arm64 2>/dev/null") {}
        return rc
    }

    fun verifyLoad(module: String = MODULE, onLine: (String) -> Unit = {}): Pair<Boolean, String?> {
        copyAssetToRemote("sh/verify_load.sh", "verify_load.sh")
        var passed = false
        var foundNode: String? = null
        val cmd = "sh $REMOTE/verify_load.sh $module"
        val rc = su(cmd) { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank()) {
                if (trimmed.startsWith("RESULT: PASS")) passed = true
                if (trimmed.startsWith("NODE=")) {
                    foundNode = trimmed.substringAfter("NODE=").trim()
                }
                onLine(trimmed)
            }
        }
        su("rm -f $REMOTE/verify_load.sh 2>/dev/null") {}
        if (rc == 0) passed = true
        return Pair(passed, foundNode)
    }

    private fun su(cmd: String, onLine: (String) -> Unit): Int = try {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().useLines { seq -> seq.forEach(onLine) }
        p.waitFor()
    } catch (t: Throwable) {
        onLine("FTL_ERROR=${t.javaClass.simpleName}:${t.message}")
        -1
    }

    companion object {
        const val MODULE = "suvhwvwrtnb"
        const val ALT_MODULE = "devddgy"
        private const val REMOTE = "/data/local/tmp/ftl"
    }
}
