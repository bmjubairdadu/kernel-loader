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
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val version = reader.readLine()
            reader.close()
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
            exactMatch == true -> "OK: $v - exact driver available ($short) - AUTO LOAD works"
            exactMatch == false -> "UNIVERSAL: $v - no exact driver, best match + vermagic auto-patch + force-load"
            driverCount > 0 -> "UNIVERSAL: $v - $driverCount driver(s) bundled - AUTO LOAD auto-selects best match"
            else -> "UNIVERSAL: $v - pick any .ko (any device / any model / old & new kernels)"
        }
    }

    fun hasExactDriver(driverVersions: List<String>, kernelRelease: String = getKernelVersion()): Boolean {
        val short = kernelShortVersion(kernelRelease)
        return driverVersions.any { kernelShortVersion(it) == short }
    }
}
