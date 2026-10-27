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
        val candidates = Shell.cmd("ls /sys/class 2>/dev/null").exec().out
            .map { it.trim() }
            .filter { it.startsWith("dmsync") || it.startsWith("kmem") || it.startsWith("memacc") || it.matches(Regex("^[A-Za-z0-9]{6,8}$")) || it.matches(Regex("^[A-GNYB]{6,8}$")) }
        candidates.firstOrNull { cls ->
            val devFileExists = Shell.cmd("test -f /sys/class/$cls/dev").exec().isSuccess
            val charNodeExists = Shell.cmd("test -c /dev/$cls").exec().isSuccess
            devFileExists && charNodeExists
        }
    } catch (e: Exception) {
        null
    }
}
