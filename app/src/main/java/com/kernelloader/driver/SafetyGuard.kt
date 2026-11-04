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
        "cpu_dma_latency", "network_latency", "network_throughput", "memory_bandwidth",
        "msm_rng", "msm-rng", "msm_rotator", "msm_vidc", "msm_audio", "msm_ipc_router", "msm_subsys", "msm_sps",
        "qcom_rx_mclk", "hw_random", "ipa", "adsprpc-smd", "smd_cntl",
        "avtimer", "fastrpc", "fastrng", "ssc", "subsys", "wwan_ioctl", "icesdcc",
        "rfkill", "btpower", "sensors", "frandom", "erandom", "dts_eagle", "uio0", "uio", "vsock", "vhost-vsock",
        "devfreq", "extcon", "hidraw", "ramdump", "spidev", "usbrndis", "rotator", "smdpkt"
    )

    fun isSystemNode(name: String): Boolean {
        val clean = name.removePrefix("/dev/").trim().lowercase()
        return clean.isBlank() ||
                clean in SYSTEM_DEV_NODES ||
                clean.startsWith("msm_") ||
                clean.startsWith("msm-") ||
                clean.startsWith("qcom_") ||
                clean.startsWith("hw_") ||
                clean.startsWith("diag") ||
                clean.startsWith("kgsl") ||
                clean.startsWith("qsee") ||
                clean.startsWith("ipa") ||
                clean.startsWith("adsprpc") ||
                clean.startsWith("smd") ||
                clean.startsWith("avtimer") ||
                clean.startsWith("fast") ||
                clean.startsWith("subsys") ||
                clean.startsWith("rfkill") ||
                clean.startsWith("btpower") ||
                clean.startsWith("sensor") ||
                clean.startsWith("uio") ||
                clean.startsWith("vsock") ||
                clean.startsWith("tty") ||
                clean.startsWith("loop") ||
                clean.startsWith("ram") ||
                clean.startsWith("dm-") ||
                clean.startsWith("video") ||
                clean.startsWith("media") ||
                clean.matches(Regex("""^sd[a-z][0-9]*$"""))
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
                (cls.startsWith("dmsync") || cls.startsWith("sysync") || cls.matches(Regex("^[A-Z]{6,8}$")) || cls.matches(Regex("^[A-GNYB]{6,8}$")))
            }
        candidates.firstOrNull { cls ->
            val devFileExists = Shell.cmd("test -f /sys/class/$cls/dev || test -f /sys/class/$cls/$cls/dev").exec().isSuccess
            val charNodeExists = Shell.cmd("test -c /dev/$cls").exec().isSuccess
            devFileExists || charNodeExists
        }
    } catch (e: Exception) {
        null
    }
}
