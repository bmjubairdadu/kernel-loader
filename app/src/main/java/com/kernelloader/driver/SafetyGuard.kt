package com.kernelloader.driver

import com.topjohnwu.superuser.Shell

object SafetyGuard {

    fun loadedModuleNames(): Set<String> = try {
        Shell.cmd("cat /proc/modules 2>/dev/null").exec().out
            .mapNotNull { line ->
                line.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
            }
            .toSet()
    } catch (e: Exception) {
        emptySet()
    }

    fun newlyLoaded(before: Set<String>): Set<String> =
        loadedModuleNames().filter { it.isNotBlank() && it !in before }.toSet()

    /**
     * Our 3.2+ driver unlinks itself from /proc/modules on load but its
     * /sys/class entry stays. Class present = our module resident, even
     * though lsmod is empty. insmod would fail with "File exists".
     */
    fun hiddenDriverClass(): String? = try {
        Shell.cmd("ls /sys/class 2>/dev/null").exec().out
            .map { it.trim() }
            .firstOrNull { it.startsWith("dmsync") || it.startsWith("kmem") || it.startsWith("memacc") }
    } catch (e: Exception) {
        null
    }
}
