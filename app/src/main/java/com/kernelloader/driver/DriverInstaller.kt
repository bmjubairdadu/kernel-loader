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
        su("rm -rf $REMOTE 2>/dev/null") {}

        val ok = kv["FTL_OK"] == "1"
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

    data class VerifyResult(
        val ok: Boolean,
        val node: String? = null,
        val stealth: Boolean = false,
        val inUse: Boolean = false,
        val ownerPid: Int = 0
    )

    fun isLoaded(module: String = MODULE): Boolean =
        verifyLoadDetailed(module).ok

    fun findNode(): String? {
        var digitNode: String? = null
        val cmd = "for d in /sys/class/*/; do " +
            "[ -d \"\$d\" ] || continue; " +
            "cls=\$(basename \"\$d\"); " +
            "case \"\$cls\" in [0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9]) if [ -c \"/dev/\$cls\" ]; then echo \"/dev/\$cls\"; fi ;; esac; " +
            "for child in \"\$d\"*; do c=\$(basename \"\$child\"); case \"\$c\" in [0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9]) if [ -c \"/dev/\$c\" ]; then echo \"/dev/\$c\"; fi ;; esac; done; " +
            "done; " +
            "ls -l /proc/[0-9]*/fd 2>/dev/null | awk '/ -> \\/dev\\/[0-9][A-Za-z0-9]{5} \\(deleted\\)\$/ { dev=\$(NF-1); sub(/^\\/dev\\//,\"\",dev); if (dev !~ /^(binder|common|ashmem|random)\$/) { print \"/dev/\" dev; exit } }'"
        su(cmd) { line ->
            val s = line.trim()
            if (s.startsWith("/dev/") && s.length >= 6) {
                val nodeName = s.substringAfterLast('/')
                if (nodeName.length == 6 && nodeName[0].isDigit() && digitNode == null) {
                    digitNode = s
                }
            }
        }
        return digitNode
    }

    fun client(node: String, vararg args: String, onLine: (String) -> Unit = {}): Int {
        copyAssetToRemote("bin/khack_client_arm64", "khack_client_arm64")
        return try {
            val escaped = args.joinToString(" ") { "'${it.replace("'", "'\\''")}'" }
            val safeNode = node.replace("'", "'\\''")
            val cmd = "$REMOTE/khack_client_arm64 '$safeNode' $escaped"
            su(cmd, onLine)
        } finally {
            su("rm -rf $REMOTE 2>/dev/null") {}
        }
    }

    fun testReadWrite(node: String, targetPid: Int, onLine: (String) -> Unit = {}): Pair<Boolean, String> {
        if (!node.startsWith("/dev/") || targetPid <= 0) {
            return Pair(false, "invalid_target")
        }
        if (!copyAssetToRemote("bin/khack_client_arm64", "khack_client_arm64")) {
            return Pair(false, "client_stage_failed")
        }
        return try {
            val safeNode = node.replace("'", "'\\''")
            val cmd = "$REMOTE/khack_client_arm64 '$safeNode' selftest $targetPid libc.so 2>&1"
            var baseHex = ""
            var writeOk = ""
            val lines = ArrayList<String>()
            val rc = su(cmd) { line ->
                val t = line.trim()
                if (t.isNotBlank()) {
                    if (t.startsWith("BASE=")) {
                        baseHex = t.substringAfter("BASE=").trim()
                    } else if (t.startsWith("WRITE_OK=")) {
                        writeOk = t.substringAfter("WRITE_OK=").trim()
                    } else {
                        lines += t
                    }
                    onLine(t)
                }
            }
            val dump = lines.joinToString("\n").lowercase()
            val hasElfMagic = dump.contains("7f 45 4c 46")
            if (rc == 0 && baseHex.startsWith("0x") && baseHex != "0x0" && hasElfMagic && writeOk.isNotBlank()) {
                Pair(true, "libc.so=$baseHex (ELF + R/W OK)")
            } else {
                Pair(false, "rc=$rc base=$baseHex ${lines.lastOrNull().orEmpty()}".trim())
            }
        } finally {
            su("rm -rf $REMOTE 2>/dev/null") {}
        }
    }

    fun verifyLoad(module: String = MODULE, onLine: (String) -> Unit = {}): Pair<Boolean, String?> {
        val vr = verifyLoadDetailed(module, onLine)
        return Pair(vr.ok, vr.node)
    }

    fun verifyLoadDetailed(module: String = MODULE, onLine: (String) -> Unit = {}): VerifyResult =
        synchronized(verifyLock) {
            copyAssetToRemote("sh/verify_load.sh", "verify_load.sh")
            var passed = false
            var foundNode: String? = null
            var isStealth = false
            var inUse = false
            var ownerPid = 0
            val knownNode = DriverSessionManager.isSessionActive(ctx).second.first.ifBlank { "" }
            val cmd = if (knownNode.isNotBlank()) "sh $REMOTE/verify_load.sh '$module' '$knownNode'" else "sh $REMOTE/verify_load.sh '$module'"
            val rc = su(cmd) { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) {
                    if (trimmed.startsWith("RESULT: PASS")) passed = true
                    if (trimmed.startsWith("NODE=")) {
                        foundNode = trimmed.substringAfter("NODE=").trim()
                    }
                    if (trimmed.startsWith("STEALTH=")) {
                        isStealth = trimmed.substringAfter("STEALTH=").trim() == "1"
                    }
                    if (trimmed.startsWith("IN_USE=")) {
                        inUse = trimmed.substringAfter("IN_USE=").trim() == "1"
                    }
                    if (trimmed.startsWith("OWNER_PID=")) {
                        ownerPid = trimmed.substringAfter("OWNER_PID=").trim().toIntOrNull() ?: 0
                    }
                    onLine(trimmed)
                }
            }
            su("rm -rf $REMOTE 2>/dev/null") {}
            if (rc == 0) passed = true
            VerifyResult(ok = passed, node = foundNode, stealth = isStealth, inUse = inUse, ownerPid = ownerPid)
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
        private val verifyLock = Any()
    }
}
