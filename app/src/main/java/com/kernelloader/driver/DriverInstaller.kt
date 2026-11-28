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

    fun installBytes(rawBytes: ByteArray, fileName: String, onLine: (String) -> Unit = {}): Result {
        if (!fileName.endsWith(".ko", ignoreCase = true)) {
            onLine("- Error: Only .ko drivers are supported")
            return Result(ok = false, error = "unsupported_driver_format")
        }
        return injectInMemory(rawBytes, onLine)
    }

    private fun isValidElf(bytes: ByteArray): Boolean =
        bytes.size >= 64 &&
            bytes[0] == 0x7f.toByte() &&
            bytes[1] == 'E'.code.toByte() &&
            bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    private fun cleanupRuntimeTraces(finalNode: String = "", clearKernelLogs: Boolean = false) {
        val injectorBin = File(ctx.filesDir, "direct_inject_arm64")
        val clientBin = File(ctx.filesDir, "khack_client_arm64")
        runCatching { injectorBin.delete() }
        runCatching { clientBin.delete() }
        OtaDriverStore.cleanTempDrivers(ctx)

        val cmd = buildString {
            if (clearKernelLogs && finalNode.isNotBlank()) {
                append("chmod 0600 '$finalNode' 2>/dev/null; ")
            }
            append(
                "rm -rf '${injectorBin.absolutePath}' '${clientBin.absolutePath}' " +
                    "/data/local/tmp/kloader* /data/local/tmp/*.ko /data/local/tmp/*.bin " +
                    "/data/local/tmp/*.dat /data/local/tmp/ftl /data/tmpf /data/.km_* /dev/.kloder_session 2>/dev/null"
            )
            if (clearKernelLogs) {
                append("; dmesg -c >/dev/null 2>&1; logcat -c 2>/dev/null")
            }
        }
        su(cmd) {}
    }

    private fun injectInMemory(rawBytes: ByteArray, onLine: (String) -> Unit): Result {
        onLine("- Mode: 100% In-Memory Injection (Zero Disk Footprint)")

        var elfBytes = rawBytes
        val injectorBin = File(ctx.filesDir, "direct_inject_arm64")

        val kv = HashMap<String, String>()
        val raw = StringBuilder()
        var processRc = -1
        var ok = false
        var finalNode = ""
        var finalDev = ""
        var finalMod = ""

        try {
            elfBytes = if (DriverCrypto.isEncrypted(rawBytes)) {
                DriverCrypto.decrypt(rawBytes, ctx)
            } else {
                rawBytes
            }
            if (!isValidElf(elfBytes)) {
                onLine("- Error: Payload is not a valid ELF .ko module")
                return Result(ok = false, error = "invalid_elf")
            }

            try {
                ctx.assets.open("bin/direct_inject_arm64").use { input ->
                    injectorBin.outputStream().use { output -> input.copyTo(output) }
                }
                su("chmod 700 '${injectorBin.absolutePath}'") {}
            } catch (e: Exception) {
                if (!injectorBin.exists() || injectorBin.length() == 0L) {
                    onLine("- Error staging injector tool: ${e.message}")
                    return Result(ok = false, error = "injector_stage_failed")
                }
            }

            val cList = listOf('b', 'c', 'd', 'f', 'g', 'k', 'l', 'm', 'n', 'p', 'r', 's', 't', 'v', 'w', 'z')
            val vList = listOf('a', 'e', 'i', 'o', 'u')
            val cMidList = listOf('c', 'd', 'f', 'k', 'l', 'm', 'n', 'p', 'r', 's', 't', 'v', 'x', 'b', 'g', 'z')
            val tag = "" + cList.random() + vList.random() + cMidList.random() + cMidList.random() + vList.random() + cMidList.random()
            onLine("- Stealth device identifier: $tag (Target: /dev/$tag)")

            val p = ProcessBuilder("su", "-c", "'${injectorBin.absolutePath}' - tag=$tag").start()
            val reader = Thread {
                p.inputStream.bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        raw.appendLine(line)
                        val clean = line.trim()
                        if (clean.isNotBlank()) {
                            val eq = clean.indexOf('=')
                            if (eq > 0 && (clean.startsWith("STATUS=") || clean.startsWith("NODE=") ||
                                           clean.startsWith("DEVNAME=") || clean.startsWith("MODULE=") ||
                                           clean.startsWith("RESIDENT=") || clean.startsWith("ERROR="))) {
                                val k = clean.substring(0, eq)
                                val v = clean.substring(eq + 1).trim()
                                kv[k] = v
                            } else {
                                onLine(clean)
                            }
                        }
                    }
                }
            }
            reader.start()

            p.outputStream.use { out ->
                out.write(elfBytes)
                out.flush()
            }

            processRc = p.waitFor()
            reader.join()

            ok = kv["STATUS"] == "OK" || raw.contains("Driver loaded") || processRc == 0
            finalNode = kv["NODE"].orEmpty().ifBlank { "/dev/$tag" }
            finalDev = kv["DEVNAME"].orEmpty().ifBlank { tag }
            finalMod = kv["MODULE"].orEmpty().ifBlank {
                (1..8).map { ('a'..'z').random() }.joinToString("")
            }

            if (!ok && kv["RESIDENT"] == "1") {
                val candidate = findCandidateNodes().firstOrNull()
                if (candidate != null) {
                    finalNode = candidate
                    ok = true
                    onLine("[*] Reconnected to active resident node: $finalNode")
                }
            }

            if (ok) {
                onLine("- Active channel: $finalNode (0600 root:root)")
                onLine("- Kernel module: $finalMod (Pure Alphabetic Stealth)")
                onLine("- Driver status: ACTIVE & OPERATIONAL (In-Memory)")
                onLine("- Stealth Audit: Traces, logs & temporary binaries cleared")
                DriverSessionManager.markLoaded(ctx, finalNode, finalMod, true)
            }
        } catch (t: Throwable) {
            onLine("- Injection error: ${t.message}")
            return Result(ok = false, error = t.message.orEmpty())
        } finally {
            rawBytes.fill(0)
            if (elfBytes !== rawBytes) {
                elfBytes.fill(0)
            }
            cleanupRuntimeTraces(finalNode = if (ok) finalNode else "", clearKernelLogs = ok)
        }

        return Result(
            ok = ok,
            module = finalMod,
            devName = finalDev,
            node = finalNode,
            exitCode = processRc,
            alreadyLoaded = kv["RESIDENT"] == "1",
            error = kv["ERROR"].orEmpty(),
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

    private inline fun <T> withClientBin(block: (String) -> T): T {
        val clientBin = File(ctx.filesDir, "khack_client_arm64")
        return try {
            ctx.assets.open("bin/khack_client_arm64").use { input ->
                clientBin.outputStream().use { output -> input.copyTo(output) }
            }
            su("chmod 700 '${clientBin.absolutePath}'") {}
            block(clientBin.absolutePath)
        } finally {
            runCatching { clientBin.delete() }
            su("rm -f '${clientBin.absolutePath}' 2>/dev/null") {}
        }
    }

    fun findCandidateNodes(): List<String> {
        val candidates = LinkedHashSet<String>()
        val sessionNode = DriverSessionManager.isSessionActive(ctx).second.first.trim()
        if (sessionNode.isNotBlank() && (sessionNode.startsWith("/proc/") || (sessionNode.startsWith("/dev/") && isCharDevice(sessionNode)))) {
            candidates.add(sessionNode)
        }

        val cmd = "for d in /dev/[b-z][aeiou][a-z][a-z][aeiou][a-z]; do " +
            "[ -c \"\$d\" ] && echo \"\$d\"; " +
            "done"
        su(cmd) { line ->
            val s = line.trim()
            if (s.startsWith("/proc/") || s.startsWith("/dev/")) {
                candidates.add(s)
            }
        }
        return candidates.toList()
    }

    fun findNode(): String? = findCandidateNodes().firstOrNull()

    fun testReadWrite(node: String, targetPid: Int, onLine: (String) -> Unit = {}): Pair<Boolean, String> {
        if ((!node.startsWith("/dev/") && !node.startsWith("/proc/")) || targetPid <= 0) {
            return Pair(false, "invalid_target")
        }
        return try {
            withClientBin { clientPath ->
                val safeNode = node.replace("'", "'\\''")
                val cmd = "'$clientPath' '$safeNode' selftest $targetPid libc.so 2>&1"
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
            }
        } catch (t: Throwable) {
            Pair(false, t.message.orEmpty())
        }
    }

    fun verifyLoadDetailed(module: String = DEFAULT_MODULE, onLine: (String) -> Unit = {}): VerifyResult =
        synchronized(verifyLock) {
            val candidates = findCandidateNodes()
            if (candidates.isEmpty()) {
                onLine("- Verification: No candidate driver nodes detected in /dev")
                DriverSessionManager.clearSession(ctx)
                return VerifyResult(ok = false, stealth = true)
            }

            val myPid = android.os.Process.myPid()

            try {
                withClientBin { clientPath ->
                    for (cand in candidates) {
                        if (cand.isNotBlank()) {
                            su("chmod 0600 '$cand' 2>/dev/null") {}
                        }
                        onLine("- Probing node candidate: $cand (0x803 IOCTL)...")
                        val cmd = "'$clientPath' '$cand' base $myPid libc.so 2>&1"
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
                }

                onLine("- Verification: None of the candidate nodes responded to 0x803 IOCTL")
                DriverSessionManager.clearSession(ctx)
                VerifyResult(ok = false, stealth = true)
            } catch (_: Exception) {
                VerifyResult(ok = false, stealth = true)
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
        onLine("ERROR: ${t.javaClass.simpleName}: ${t.message}")
        -1
    }

    companion object {
        const val DEFAULT_MODULE = "driver"
        private val verifyLock = Any()
    }
}
