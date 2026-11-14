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
        return runDirectDriverScript(safeName, onLine)
    }

    fun installFile(sourceFile: File, onLine: (String) -> Unit = {}): Result {
        val safeName = sourceFile.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        copyToRemote(sourceFile, safeName)
        return runDirectDriverScript(safeName, onLine)
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

    private fun runDirectDriverScript(scriptName: String, onLine: (String) -> Unit): Result {
        val kv = HashMap<String, String>()
        val raw = StringBuilder()
        var detectedNode = ""
        val cmd = "sh '$REMOTE/$scriptName'"
        val rc = su(cmd) { line ->
            raw.appendLine(line)
            val clean = line.trim()
            if (clean.isNotBlank()) {
                onLine(clean)
                val eq = clean.indexOf('=')
                if (clean.startsWith("FTL_") && eq > 0) {
                    kv[clean.substring(0, eq)] = clean.substring(eq + 1).trim()
                }
                if (clean.contains("Active node:")) {
                    val part = clean.substringAfter("Active node:").trim()
                    val candidate = part.split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
                    if (candidate.startsWith("/dev/")) {
                        detectedNode = candidate
                    }
                }
            }
        }
        su("rm -rf $REMOTE 2>/dev/null") {}

        val finalNode = kv["FTL_NODE"]?.takeIf { it.isNotBlank() } ?: detectedNode
        val ok = kv["FTL_OK"] == "1" || (rc == 0 && finalNode.isNotBlank())
        return Result(
            ok = ok,
            module = kv["FTL_MODULE"].orEmpty().ifBlank { MODULE },
            devName = kv["FTL_DEVNAME"].orEmpty().ifBlank { finalNode.substringAfterLast('/') },
            major = kv["FTL_MAJOR"]?.toIntOrNull() ?: 0,
            node = finalNode,
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

    fun findCandidateNodes(): List<String> {
        val candidates = LinkedHashSet<String>()
        val sessionNode = DriverSessionManager.isSessionActive(ctx).second.first.trim()
        if (sessionNode.isNotBlank() && sessionNode.startsWith("/dev/") && isCharDevice(sessionNode)) {
            candidates.add(sessionNode)
        }

        val cmd = "for d in /dev/[0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9]; do " +
            "[ -c \"\$d\" ] && echo \"\$d\"; " +
            "done; " +
            "for d in /sys/class/[0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9][A-Za-z0-9]/; do " +
            "[ -d \"\$d\" ] || continue; " +
            "c=\$(basename \"\$d\"); " +
            "[ -c \"/dev/\$c\" ] && echo \"/dev/\$c\"; " +
            "done; " +
            "ls -l /proc/[0-9]*/fd 2>/dev/null | awk '/ -> \\/dev\\/[0-9][A-Za-z0-9]{5} \\(deleted\\)\$/ { dev=\$(NF-1); sub(/^\\/dev\\//,\"\",dev); if (dev !~ /^(binder|common|ashmem|random)\$/) { print \"/dev/\" dev } }'"
        su(cmd) { line ->
            val s = line.trim()
            if (s.startsWith("/dev/")) {
                val nodeName = s.substringAfterLast('/')
                if (nodeName.length == 6 && nodeName[0].isDigit()) {
                    candidates.add(s)
                }
            }
        }
        return candidates.toList()
    }

    fun findNode(): String? = findCandidateNodes().firstOrNull()

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
            val candidates = findCandidateNodes()
            if (candidates.isEmpty()) {
                onLine("- Verification: No candidate driver nodes detected in /dev")
                DriverSessionManager.clearSession(ctx)
                return VerifyResult(ok = false, stealth = true)
            }

            copyAssetToRemote("bin/khack_client_arm64", "khack_client_arm64")
            val myPid = android.os.Process.myPid()

            try {
                for (cand in candidates) {
                    onLine("- Probing node candidate: $cand (0x803 IOCTL)...")
                    val cmd = "$REMOTE/khack_client_arm64 '$cand' base $myPid libc.so 2>&1"
                    var baseAddr = ""
                    var errorMsg = ""
                    val rc = su(cmd) { line ->
                        val t = line.trim()
                        if (t.isNotBlank()) {
                            if (t.startsWith("0x") && t != "0x0") {
                                baseAddr = t
                            } else if (t.contains("failed") || t.contains("errno") || t.contains("EBUSY") || t.contains("Device or resource busy")) {
                                errorMsg = t
                            }
                        }
                    }

                    if (rc == 0 && baseAddr.isNotBlank()) {
                        onLine("- Handshake (0x803): SUCCESS on $cand")
                        onLine("- Kernel Memory Engine: ACTIVE (libc.so base: $baseAddr)")
                        onLine("- Driver status: 100% OPERATIONAL")
                        DriverSessionManager.markLoaded(ctx, cand, module, true)
                        return VerifyResult(ok = true, node = cand, stealth = true, inUse = false)
                    } else if (errorMsg.contains("errno=16") || errorMsg.contains("EBUSY") || errorMsg.contains("Device or resource busy")) {
                        onLine("- Handshake (0x803): Node $cand is in exclusive use by client (EBUSY)")
                        onLine("- Driver status: ACTIVE & ENGAGED")
                        DriverSessionManager.markLoaded(ctx, cand, module, true)
                        return VerifyResult(ok = true, node = cand, stealth = true, inUse = true)
                    } else {
                        onLine("- Probe $cand: No response to 0x803 IOCTL (${errorMsg.ifBlank { "rc=$rc" }})")
                    }
                }

                onLine("- Verification: None of the candidate nodes responded to 0x803 IOCTL")
                DriverSessionManager.clearSession(ctx)
                VerifyResult(ok = false, stealth = true)
            } finally {
                su("rm -rf $REMOTE 2>/dev/null") {}
            }
        }

    private fun isCharDevice(path: String): Boolean {
        if (!path.startsWith("/dev/")) return false
        var exists = false
        su("if [ -c '$path' ]; then echo YES; fi") { line ->
            if (line.trim() == "YES") exists = true
        }
        return exists
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
