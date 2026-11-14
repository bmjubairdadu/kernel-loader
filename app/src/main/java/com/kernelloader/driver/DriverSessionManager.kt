package com.kernelloader.driver

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
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

    fun getBootId(): String = getKernelBootId()

    fun getKernelBootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
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

    fun markLoaded(context: Context, node: String, module: String, stealth: Boolean) {
        val bootId = getKernelBootId()
        val bootCount = getBootCount(context)
        val bootTime = getBootTimeBucket()
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_BOOT_ID, bootId)
            .putInt(KEY_BOOT_COUNT, bootCount)
            .putLong(KEY_BOOT_TIME, bootTime)
            .putString(KEY_NODE, node)
            .putString(KEY_MODULE, module)
            .putBoolean(KEY_STEALTH, stealth)
            .apply()
        removeLegacySessionFile()
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        removeLegacySessionFile()
    }

    fun isSessionActive(context: Context): Pair<Boolean, Triple<String, String, Boolean>> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedBootId = prefs.getString(KEY_BOOT_ID, "") ?: ""
        val savedBootCount = prefs.getInt(KEY_BOOT_COUNT, -1)
        val savedBootTime = prefs.getLong(KEY_BOOT_TIME, 0L)

        if (savedBootId.isBlank() && savedBootCount <= 0 && savedBootTime <= 0L) {
            return Pair(false, Triple("", "", false))
        }

        val currentBootId = getKernelBootId()
        val currentBootCount = getBootCount(context)
        val currentBootTime = getBootTimeBucket()

        var matches = false
        if (savedBootId.isNotBlank() && currentBootId.isNotBlank()) {
            matches = (savedBootId == currentBootId)
        } else if (savedBootCount > 0 && currentBootCount > 0) {
            matches = (savedBootCount == currentBootCount)
        } else if (savedBootTime > 0L && currentBootTime > 0L) {
            matches = abs(currentBootTime - savedBootTime) <= 1L
        }

        if (matches) {
            val node = prefs.getString(KEY_NODE, "") ?: ""
            val module = prefs.getString(KEY_MODULE, "") ?: ""
            val stealth = prefs.getBoolean(KEY_STEALTH, false)
            return Pair(true, Triple(node, module, stealth))
        } else {
            prefs.edit().clear().apply()
        }

        return Pair(false, Triple("", "", false))
    }
}
