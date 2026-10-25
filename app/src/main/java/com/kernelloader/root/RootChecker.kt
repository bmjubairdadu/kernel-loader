package com.kernelloader.root

import com.topjohnwu.superuser.Shell
import java.io.BufferedReader
import java.io.InputStreamReader

object RootChecker {

    private val KERNEL_RE = Regex("""(\d+)\.(\d+)\.(\d+)""")

    fun isRootAvailable(): Boolean {
        return try {
            Shell.getShell().isRoot
        } catch (e: Exception) {
            false
        }
    }

    fun getKernelVersion(): String {
        return try {
            val process = Runtime.getRuntime().exec("uname -r")
            val version = process.inputStream.bufferedReader().use { it.readLine() }
            process.destroy()
            version ?: "Unknown"
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun getKernelVersionRoot(): String {
        return try {
            val res = Shell.cmd("uname -r").exec()
            if (res.isSuccess && res.out.isNotEmpty()) res.out[0].trim()
            else getKernelVersion()
        } catch (e: Exception) {
            getKernelVersion()
        }
    }

    fun getKernelRelease(): String? {
        return try {
            getKernelVersionRoot()
                .takeIf { it.isNotBlank() && !it.startsWith("Error") }
        } catch (e: Exception) {
            null
        }
    }

    fun kernelShortVersion(kernelRelease: String = getKernelVersion()): String {
        val m = KERNEL_RE.find(kernelRelease) ?: return kernelRelease.trim()
        return "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}"
    }

    fun kernelMajorMinor(kernelRelease: String = getKernelVersion()): String {
        val m = KERNEL_RE.find(kernelRelease) ?: return ""
        return "${m.groupValues[1]}.${m.groupValues[2]}"
    }

    fun isKernelCompatible(): Boolean = getKernelVersion().isNotBlank()

    fun getCompatibilityMessage(driverCount: Int = -1, exactMatch: Boolean? = null): String {
        val v = getKernelVersion()
        val short = kernelShortVersion(v)
        return when {
            exactMatch == true -> "OK: $v - exact driver available ($short) - ready to load"
            exactMatch == false -> "UNIVERSAL: $v - no exact driver, best match + vermagic auto-patch + force-load"
            driverCount > 0 -> "UNIVERSAL: $v - $driverCount driver(s) bundled - auto-selects best match"
            else -> "UNIVERSAL: $v - pick any .ko (any device / any model / old & new kernels)"
        }
    }

    fun hasExactDriver(driverVersions: List<String>, kernelRelease: String = getKernelVersion()): Boolean {
        val short = kernelShortVersion(kernelRelease)
        return driverVersions.any { kernelShortVersion(it) == short }
    }

    @Volatile
    private var cachedVermagic: String? = null

    fun getKernelVermagic(): String {
        cachedVermagic?.let { return it }
        val v = detectKernelVermagic()
        if (v.isNotBlank()) cachedVermagic = v
        return v
    }

    private fun detectKernelVermagic(): String {
        val candidateDirs = listOf(
            java.io.File("/vendor/lib/modules"),
            java.io.File("/odm/lib/modules"),
            java.io.File("/system/lib/modules")
        )
        for (dir in candidateDirs) {
            if (!dir.isDirectory) continue
            val names = dir.list { _, name -> name.endsWith(".ko") } ?: continue
            for (name in names) {
                val vm = readVermagicFast(java.io.File(dir, name))
                if (vm.isNotBlank()) return vm
            }
        }
        for (candidate in listOf("/vendor/lib/modules/*.ko", "/odm/lib/modules/*.ko", "/system/lib/modules/*.ko")) {
            val file = try {
                val res = Shell.cmd("ls $candidate 2>/dev/null | head -n 1").exec()
                if (res.isSuccess && res.out.isNotEmpty()) res.out[0].trim() else ""
            } catch (e: Exception) { "" }
            if (file.isNotBlank()) {
                val raw = try {
                    val res = Shell.cmd("cat \"$file\" 2>/dev/null | tr '\\0' '\\n' | grep -m1 '^vermagic='").exec()
                    if (res.isSuccess && res.out.isNotEmpty()) res.out[0].substringAfter("vermagic=").trim() else ""
                } catch (e: Exception) { "" }
                if (raw.isNotBlank()) return raw
            }
        }
        return ""
    }

    private fun readVermagicFast(file: java.io.File): String {
        return try {
            val buf = ByteArray(131072)
            val n = java.io.FileInputStream(file).use { it.read(buf) }
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
        } catch (e: Exception) {
            ""
        }
    }
}
