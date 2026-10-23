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

    fun hiddenDriverClass(): String? = try {
        Shell.cmd("ls /sys/class 2>/dev/null").exec().out
            .map { it.trim() }
            .firstOrNull { it.startsWith("dmsync") || it.startsWith("kmem") || it.startsWith("memacc") }
    } catch (e: Exception) {
        null
    }
}
