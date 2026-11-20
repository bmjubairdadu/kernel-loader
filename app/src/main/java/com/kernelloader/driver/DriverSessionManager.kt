package com.kernelloader.driver

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.topjohnwu.superuser.Shell
import java.io.File
import kotlin.math.abs

object DriverSessionManager {
    private const val SESSION_FILE = "/dev/.kloder_session"
    private const val PREF_NAME = "kloder_driver_session"
    private const val KEY_BOOT_ID = "boot_id"
    private const val KEY_BOOT_COUNT = "boot_count"
    private const val KEY_BOOT_TIME = "boot_time"
    private const val KEY_NODE = "node"
    private const val KEY_MODULE = "module"
    private const val KEY_STEALTH = "stealth"
    private const val KEY_DRIVER_FILE = "driver_file"
    private const val KEY_DRIVER_SHA = "driver_sha"
    private const val KEY_DRIVER_LABEL = "driver_label"
    private const val KEY_IS_LOADED = "is_loaded"
    private const val KEY_ELAPSED_LOAD_TIME = "elapsed_load_time"

    fun getKernelBootId(): String = try {
        val f = File("/proc/sys/kernel/random/boot_id")
        if (f.canRead()) {
            f.readText().trim()
        } else {
            val out = Shell.cmd("cat /proc/sys/kernel/random/boot_id 2>/dev/null").exec().out
            out.firstOrNull()?.trim() ?: ""
        }
    } catch (_: Exception) {
        ""
    }

    fun getBootCount(context: Context): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }

    fun getBootTimeBucket(): Long = try {
        (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60000L
    } catch (_: Exception) {
        0L
    }

    private fun removeLegacySessionFile() {
        try {
            val p = ProcessBuilder("su", "-c", "rm -f $SESSION_FILE 2>/dev/null").start()
            p.waitFor()
        } catch (_: Exception) {
        }
    }

    fun markLoaded(
        context: Context,
        node: String,
        module: String,
        stealth: Boolean,
        driverFile: String = "",
        driverSha: String = "",
        driverLabel: String = ""
    ) {
        val bootId = getKernelBootId()
        val bootCount = getBootCount(context)
        val bootTime = getBootTimeBucket()
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val curFile = if (driverFile.isNotBlank()) driverFile else prefs.getString(KEY_DRIVER_FILE, "") ?: ""
        val curSha = if (driverSha.isNotBlank()) driverSha else prefs.getString(KEY_DRIVER_SHA, "") ?: ""
        val curLabel = if (driverLabel.isNotBlank()) driverLabel else prefs.getString(KEY_DRIVER_LABEL, "") ?: ""

        val editor = prefs.edit()
            .putBoolean(KEY_IS_LOADED, true)
            .putLong(KEY_ELAPSED_LOAD_TIME, SystemClock.elapsedRealtime())
            .putString(KEY_BOOT_ID, bootId)
            .putInt(KEY_BOOT_COUNT, bootCount)
            .putLong(KEY_BOOT_TIME, bootTime)
            .putString(KEY_NODE, node)
            .putString(KEY_MODULE, module)
            .putBoolean(KEY_STEALTH, stealth)
        if (curFile.isNotBlank()) editor.putString(KEY_DRIVER_FILE, curFile)
        if (curSha.isNotBlank()) editor.putString(KEY_DRIVER_SHA, curSha)
        if (curLabel.isNotBlank()) editor.putString(KEY_DRIVER_LABEL, curLabel)
        editor.apply()
        removeLegacySessionFile()
    }

    fun getLoadedDriverFile(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DRIVER_FILE, "") ?: ""
    }

    fun getLoadedDriverSha(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DRIVER_SHA, "") ?: ""
    }

    fun getLoadedDriverLabel(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DRIVER_LABEL, "") ?: ""
    }

    fun triggerDeviceReboot(context: Context, reason: String = "Driver revoked by controller") {
        try {
            clearSession(context)
            android.util.Log.w("KernelLoader", "[Reboot] Device reboot triggered! Reason: $reason")
            val rebootCmd = "sync; svc power reboot || setprop sys.powerctl reboot || reboot || /system/bin/reboot || (echo 1 > /proc/sys/kernel/sysrq && echo b > /proc/sysrq-trigger)"
            Shell.cmd(rebootCmd).submit { }
            Shell.cmd(rebootCmd).exec()
        } catch (_: Exception) { }

        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "sync; svc power reboot || setprop sys.powerctl reboot || reboot || echo b > /proc/sysrq-trigger"))
        } catch (_: Exception) { }
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        removeLegacySessionFile()
    }

    fun isSessionActive(context: Context): Pair<Boolean, Triple<String, String, Boolean>> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val isLoaded = prefs.getBoolean(KEY_IS_LOADED, false)
        val savedBootId = prefs.getString(KEY_BOOT_ID, "") ?: ""
        val savedBootCount = prefs.getInt(KEY_BOOT_COUNT, -1)
        val savedBootTime = prefs.getLong(KEY_BOOT_TIME, 0L)
        val savedElapsed = prefs.getLong(KEY_ELAPSED_LOAD_TIME, 0L)

        if (!isLoaded && savedBootId.isBlank() && savedBootCount <= 0 && savedBootTime <= 0L) {
            return Pair(false, Triple("", "", false))
        }

        val currentBootId = getKernelBootId()
        val currentBootCount = getBootCount(context)
        val currentElapsed = SystemClock.elapsedRealtime()

        var rebootOccurred = false
        if (savedBootId.isNotBlank() && currentBootId.isNotBlank()) {
            if (savedBootId != currentBootId) {
                rebootOccurred = true
            }
        } else if (savedBootCount > 0 && currentBootCount > 0) {
            if (savedBootCount != currentBootCount) {
                rebootOccurred = true
            }
        } else if (savedElapsed > 0L && currentElapsed < (savedElapsed - 10000L)) {
            rebootOccurred = true
        }

        if (rebootOccurred) {
            prefs.edit().clear().apply()
            return Pair(false, Triple("", "", false))
        }

        val node = prefs.getString(KEY_NODE, "") ?: ""
        val module = prefs.getString(KEY_MODULE, "") ?: ""
        val stealth = prefs.getBoolean(KEY_STEALTH, false)
        return Pair(true, Triple(node, module, stealth))
    }
}
