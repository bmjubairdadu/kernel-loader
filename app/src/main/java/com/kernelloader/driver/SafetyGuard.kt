package com.kernelloader.driver

import com.topjohnwu.superuser.Shell

/**
 * REBOOT GUARD
 * ============
 * Force-loading a wrong / mismatched kernel module causes a kernel panic -
 * the phone restarts by itself. This guard detects that risk in advance:
 *
 *  1. FORCE-LOAD only when the loader's major.minor matches the device kernel
 *     (5.4.147 -> 5.4.210 OK; 5.10 -> 5.4 NEVER - panic).
 *  2. No force-load when dmesg already shows oops/panic/BUG.
 *  3. After a load, if panic signatures appear, rmmod the module immediately
 *     (rescue), so no reboot happens.
 *  4. Every decision is shown in the console as a message.
 */
object SafetyGuard {

    /** Crash / instability signatures we never want to force-load on top of. */
    private val PANIC_MARKERS = listOf(
        "kernel panic", "panic occurred", "oops:", "bug: ", "unable to handle",
        "call trace", "softlockup", "hardware watchdog", "internal error: "
    )

    /** "5.4.210" -> "5.4" (major.minor, which is what matters for safety). */
    fun majorMinor(version: String): String {
        val p = OtaDriverStore.verParts(version) ?: return ""
        return "${p.first}.${p.second}"
    }

    /**
     * Is it safe to FORCE-load a module built for [target] on a device running
     * [device]? Only the same major.minor passes - anything else panics on the
     * majority of MediaTek / Qualcomm kernels.
     */
    fun canForceLoad(device: String, target: String): Boolean {
        val d = majorMinor(device)
        return d.isNotEmpty() && d == majorMinor(target)
    }

    /** True when dmesg already shows crash / instability signatures. */
    fun kernelLooksUnstable(): Boolean {
        val text = try {
            Shell.cmd("dmesg 2>/dev/null | tail -n 150").exec().out.joinToString("\n")
        } catch (e: Exception) {
            return false
        }
        val low = text.lowercase()
        return PANIC_MARKERS.any { low.contains(it) }
    }

    /** Names currently present in /proc/modules (baseline before a load). */
    fun loadedModuleNames(): Set<String> = try {
        Shell.cmd("cat /proc/modules 2>/dev/null").exec().out
            .mapNotNull { line ->
                line.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() }
            }
            .toSet()
    } catch (e: Exception) {
        emptySet()
    }

    /** Modules that appeared since [before] (i.e. the one we just loaded). */
    fun newlyLoaded(before: Set<String>): Set<String> =
        loadedModuleNames().filter { it.isNotBlank() && it !in before }.toSet()

    /** Is this exact module name currently in /proc/modules? */
    fun isLoaded(moduleName: String): Boolean {
        if (moduleName.isBlank() || moduleName == "Module") return false
        return moduleName in loadedModuleNames()
    }

    /**
     * RESCUE: unload a module that loaded but behaves badly, so the kernel does
     * not panic (=> phone does not reboot). Plain rmmod only - a forced unload
     * of a live module can itself panic the kernel. Returns true when removed.
     */
    fun rescueUnload(moduleName: String): Boolean {
        if (moduleName.isBlank()) return false
        return try {
            Shell.cmd("rmmod $moduleName 2>/dev/null")
                .exec().isSuccess
        } catch (e: Exception) {
            false
        }
    }

    /** Console lines shown when a force-load is refused for safety reasons. */
    fun refusalLines(device: String, target: String): List<Pair<String, String>> = listOf(
        "SAFETY: force-load REFUSED - phone restart risk" to "ERR",
        "SAFETY: device kernel $device vs nearest loader $target" to "WARN",
        "SAFETY: major.minor mismatch => kernel panic risk, so not loading" to "WARN",
        "SAFETY: request a custom loader from the WhatsApp button (kernel $device)" to "INFO"
    )

    /** Console lines shown when the guard stops a load because the kernel is sick. */
    fun unstableLines(): List<Pair<String, String>> = listOf(
        "SAFETY: kernel already unstable (dmesg shows oops/panic/call trace)" to "ERR",
        "SAFETY: force-load disabled - loading now would restart the phone" to "ERR",
        "SAFETY: reboot the phone normally once, then try again" to "WARN"
    )
}
