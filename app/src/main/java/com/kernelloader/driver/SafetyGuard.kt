package com.kernelloader.driver

import com.topjohnwu.superuser.Shell

object SafetyGuard {

    val SYSTEM_DEV_NODES = setOf(
        "ashmem", "binder", "hwbinder", "vndbinder", "random", "urandom",
        "null", "zero", "full", "ptmx", "tty", "console", "kmsg",
        "loop-control", "fuse", "ion", "pmsg0", "alarm", "rtc0",
        "device-mapper", "uhid", "uinput", "tun", "vhost-net", "port", "mem", "kmem",
        "diag", "kgsl-3d0", "qseecom", "socket", "block", "input", "snd", "graphics",
        "thermal", "camera", "display", "power_supply", "net", "sound", "misc", "video",
        "cpu_dma_latency", "network_latency", "network_throughput", "memory_bandwidth"
    )

    fun isSystemNode(name: String): Boolean {
        val clean = name.removePrefix("/dev/").trim().lowercase()
        return clean in SYSTEM_DEV_NODES ||
                clean.startsWith("tty") ||
                clean.startsWith("loop") ||
                clean.startsWith("ram") ||
                clean.startsWith("dm-") ||
                clean.startsWith("video") ||
                clean.startsWith("media") ||
                clean.startsWith("sd")
    }

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
            .filter { cls ->
                !isSystemNode(cls) &&
                (cls.startsWith("dmsync") || cls.startsWith("sysync") || cls.matches(Regex("^[A-Za-z0-9]{6,8}$")) || cls.matches(Regex("^[A-GNYB]{6,8}$")))
            }
        candidates.firstOrNull { cls ->
            val devFileExists = Shell.cmd("test -f /sys/class/$cls/dev").exec().isSuccess
            val charNodeExists = Shell.cmd("test -c /dev/$cls").exec().isSuccess
            devFileExists && charNodeExists
        }
    } catch (e: Exception) {
        null
    }
}
