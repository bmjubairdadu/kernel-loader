package com.kernelloader.driver

import com.topjohnwu.superuser.Shell

object SafetyGuard {

    private val PANIC_MARKERS = listOf(
        "kernel panic", "panic occurred", "oops:", "bug: ", "unable to handle",
        "call trace", "softlockup", "hardware watchdog", "internal error: "
    )

    fun majorMinor(version: String): String {
        val p = OtaDriverStore.verParts(version) ?: return ""
        return "${p.first}.${p.second}"
    }

    fun canForceLoad(device: String, target: String): Boolean {
        val d = majorMinor(device)
        return d.isNotEmpty() && d == majorMinor(target)
    }

    fun kernelLooksUnstable(): Boolean {
        val text = try {
            Shell.cmd("dmesg 2>/dev/null | tail -n 150").exec().out.joinToString("\n")
        } catch (e: Exception) {
            return false
        }
        val low = text.lowercase()
        return PANIC_MARKERS.any { low.contains(it) }
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

    fun isLoaded(moduleName: String): Boolean {
        if (moduleName.isBlank() || moduleName == "Module") return false
        return moduleName in loadedModuleNames()
    }

    fun rescueUnload(moduleName: String): Boolean {
        if (moduleName.isBlank()) return false
        return try {
            Shell.cmd("rmmod $moduleName 2>/dev/null")
                .exec().isSuccess
        } catch (e: Exception) {
            false
        }
    }

    fun refusalLines(device: String, target: String): List<Pair<String, String>> = listOf(
        "SAFETY: force-load REFUSED - phone restart risk" to "ERR",
        "SAFETY: device kernel $device vs nearest loader $target" to "WARN",
        "SAFETY: major.minor mismatch => kernel panic risk, so not loading" to "WARN",
        "SAFETY: request a custom loader from the WhatsApp button (kernel $device)" to "INFO"
    )

    fun unstableLines(): List<Pair<String, String>> = listOf(
        "SAFETY: kernel already unstable (dmesg shows oops/panic/call trace)" to "ERR",
        "SAFETY: force-load disabled - loading now would restart the phone" to "ERR",
        "SAFETY: reboot the phone normally once, then try again" to "WARN"
    )
}
