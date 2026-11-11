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
        val rtList = ctx.assets.list("drivers/RT")
            ?.filter { it.endsWith(".sh") }
            ?.map { it.removeSuffix(".sh") }
            ?: emptyList()
        val rootList = ctx.assets.list("drivers")
            ?.filter { it.endsWith(".sh") }
            ?.map { it.removeSuffix(".sh") }
            ?: emptyList()
        return (rtList + rootList).distinct().sorted()
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
        val work = File(ctx.cacheDir, "ftl").apply { mkdirs() }
        val scriptPath = if (ctx.assets.list("drivers/RT")?.contains("$assetStem.sh") == true) {
            "drivers/RT/$assetStem.sh"
        } else {
            "drivers/$assetStem.sh"
        }
        val script = copyAsset(scriptPath, File(work, "$assetStem.sh"))
        val wrapper = copyAsset("sh/driver_load.sh", File(work, "driver_load.sh"))
        return runWrapper(script, wrapper, onLine)
    }

    fun installFile(sourceFile: File, onLine: (String) -> Unit = {}): Result {
        val work = File(ctx.cacheDir, "ftl").apply { mkdirs() }
        val safeName = sourceFile.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val staged = File(work, safeName)
        if (sourceFile.absolutePath != staged.absolutePath) {
            sourceFile.copyTo(staged, overwrite = true)
        }
        val wrapper = copyAsset("sh/driver_load.sh", File(work, "driver_load.sh"))
        return runWrapper(staged, wrapper, onLine)
    }

    private fun runWrapper(script: File, wrapper: File, onLine: (String) -> Unit): Result {
        val stage = "mkdir -p $REMOTE && " +
            "cp -f '${script.absolutePath}' $REMOTE/ && " +
            "cp -f '${wrapper.absolutePath}' $REMOTE/ && " +
            "chmod 755 $REMOTE/*.sh"
        su(stage, onLine)

        val kv = HashMap<String, String>()
        val raw = StringBuilder()
        val cmd = "sh $REMOTE/driver_load.sh $REMOTE/${script.name} $MODULE"
        val rc = su(cmd) { line ->
            raw.appendLine(line)
            onLine(line)
            val eq = line.indexOf('=')
            if (line.startsWith("FTL_") && eq > 0) {
                kv[line.substring(0, eq)] = line.substring(eq + 1).trim()
            }
        }

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
        su(
            "for d in /sys/class/*/; do " +
                "n=\$(basename \$d); " +
                "[ -e \$d\$n ] && [ \${#n} -eq 6 ] && [ -e /dev/\$n ] && echo \$n; " +
                "done"
        ) { line ->
            val s = line.trim()
            if (s.length == 6 && found == null) found = "/dev/$s"
        }
        return found
    }

    fun client(node: String, vararg args: String, onLine: (String) -> Unit = {}): Int {
        val work = File(ctx.cacheDir, "ftl").apply { mkdirs() }
        val bin = copyAsset("bin/khack_client_arm64", File(work, "khack_client_arm64"))
        val escaped = args.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
        val cmd = "mkdir -p $REMOTE && cp -f '${bin.absolutePath}' $REMOTE/khack_client_arm64 && " +
            "chmod 755 $REMOTE/khack_client_arm64 && " +
            "$REMOTE/khack_client_arm64 '$node' $escaped"
        return su(cmd, onLine)
    }

    fun verifyLoad(module: String = MODULE, onLine: (String) -> Unit = {}): Pair<Boolean, String?> {
        val work = File(ctx.cacheDir, "ftl").apply { mkdirs() }
        val script = copyAsset("sh/verify_load.sh", File(work, "verify_load.sh"))
        var passed = false
        var foundNode: String? = null
        val cmd = "mkdir -p $REMOTE && cp -f '${script.absolutePath}' $REMOTE/verify_load.sh && " +
            "chmod 755 $REMOTE/verify_load.sh && " +
            "$REMOTE/verify_load.sh $module"
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
        if (rc == 0) passed = true
        return Pair(passed, foundNode)
    }

    private fun copyAsset(assetPath: String, dst: File): File {
        ctx.assets.open(assetPath).use { ins ->
            dst.outputStream().use { outs -> ins.copyTo(outs) }
        }
        return dst
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
