package com.kernelloader.root

import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileInputStream

object RootChecker {

    fun isRootAvailable(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (_: Exception) {
            false
        }
    }

    fun getKernelVersion(): String {
        return try {
            val process = Runtime.getRuntime().exec("uname -r")
            val version = process.inputStream.bufferedReader().use { it.readLine() }
            process.destroy()
            version?.trim() ?: "Unknown"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun getKernelVersionRoot(): String {
        return try {
            val res = Shell.cmd("uname -r").exec()
            if (res.isSuccess && res.out.isNotEmpty()) res.out[0].trim()
            else getKernelVersion()
        } catch (_: Exception) {
            getKernelVersion()
        }
    }

    fun getKernelRelease(): String? {
        return try {
            getKernelVersionRoot()
                .takeIf { it.isNotBlank() && !it.startsWith("Error") }
        } catch (_: Exception) {
            null
        }
    }

    fun getShortVermagic(vermagic: String = "", kernelRelease: String = ""): String {
        val fromVm = vermagic.trim().substringBefore(' ').trim()
        if (fromVm.isNotBlank() && !fromVm.startsWith("Error")) return fromVm
        val fromRel = kernelRelease.trim().substringBefore(' ').trim()
        if (fromRel.isNotBlank() && !fromRel.startsWith("Error")) return fromRel
        val fallback = (getKernelRelease() ?: getKernelVersion()).trim().substringBefore(' ').trim()
        return fallback.ifBlank { "Unknown" }
    }

    @Volatile
    private var cachedVermagic: String? = null

    fun getKernelVermagic(): String {
        cachedVermagic?.let { return it }
        val v = detectKernelVermagic()
        if (v.isNotBlank()) cachedVermagic = v
        return v.ifBlank { getKernelRelease().orEmpty() }
    }

    private fun detectKernelVermagic(): String {
        val candidateDirs = listOf(
            File("/vendor/lib/modules"),
            File("/vendor_dlkm/lib/modules"),
            File("/odm/lib/modules"),
            File("/system/lib/modules"),
            File("/system_dlkm/lib/modules")
        )
        for (dir in candidateDirs) {
            if (!dir.isDirectory) continue
            val names = dir.list { _, name -> name.endsWith(".ko") } ?: continue
            for (name in names) {
                val vm = readVermagicFast(File(dir, name))
                if (vm.isNotBlank()) return vm
            }
        }
        val globs = listOf(
            "/vendor/lib/modules/*.ko",
            "/vendor_dlkm/lib/modules/*.ko",
            "/odm/lib/modules/*.ko",
            "/system/lib/modules/*.ko",
            "/system_dlkm/lib/modules/*.ko"
        )
        for (candidate in globs) {
            val file = try {
                val res = Shell.cmd("ls $candidate 2>/dev/null | head -n 1").exec()
                if (res.isSuccess && res.out.isNotEmpty()) res.out[0].trim() else ""
            } catch (_: Exception) {
                ""
            }
            if (file.isNotBlank()) {
                val raw = try {
                    val res = Shell.cmd("cat \"$file\" 2>/dev/null | tr '\\0' '\\n' | grep -m1 '^vermagic='").exec()
                    if (res.isSuccess && res.out.isNotEmpty()) res.out[0].substringAfter("vermagic=").trim() else ""
                } catch (_: Exception) {
                    ""
                }
                if (raw.isNotBlank()) return raw
            }
        }
        return getKernelRelease().orEmpty()
    }

    private fun readVermagicFast(file: File): String {
        return try {
            val buf = ByteArray(131072)
            val n = FileInputStream(file).use { input ->
                var total = 0
                while (total < buf.size) {
                    val r = input.read(buf, total, buf.size - total)
                    if (r <= 0) break
                    total += r
                }
                total
            }
            if (n <= 0) return ""
            val tag = "vermagic=".toByteArray(Charsets.US_ASCII)
            var idx = -1
            outer@ for (i in 0 until n - tag.size) {
                for (j in tag.indices) {
                    if (buf[i + j] != tag[j]) continue@outer
                }
                idx = i
                break
            }
            if (idx < 0) return ""
            var end = idx + tag.size
            while (end < n && buf[end] != 0.toByte()) end++
            String(buf, idx + tag.size, end - (idx + tag.size), Charsets.US_ASCII).trim()
        } catch (_: Exception) {
            ""
        }
    }
}
