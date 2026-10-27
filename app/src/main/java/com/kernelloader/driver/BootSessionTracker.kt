package com.kernelloader.driver

import android.content.Context
import android.os.SystemClock
import com.topjohnwu.superuser.Shell
import java.io.File

object BootSessionTracker {
    private const val PREFS_NAME = "driver_boot_session_prefs"
    private const val KEY_DRIVER_LOADED = "driver_loaded"
    private const val KEY_BOOT_ID = "boot_id"
    private const val KEY_MODULE_NAME = "loaded_module_name"
    private const val KEY_DEV_NODE = "loaded_dev_node"
    private const val KEY_HIDDEN = "is_hidden"
    private const val KEY_LOAD_TIME = "load_timestamp"

    fun getBootId(): String = try {
        val f = File("/proc/sys/kernel/random/boot_id")
        if (f.exists()) f.readText().trim() else ""
    } catch (_: Exception) {
        ""
    }.ifBlank {
        try {
            val rootBoot = Shell.cmd("cat /proc/sys/kernel/random/boot_id 2>/dev/null").exec().out.firstOrNull()?.trim() ?: ""
            if (rootBoot.isNotBlank()) rootBoot else {
                val uptime = SystemClock.elapsedRealtime()
                val bootTime = System.currentTimeMillis() - uptime
                "boot_${bootTime / 60000}"
            }
        } catch (_: Exception) {
            val uptime = SystemClock.elapsedRealtime()
            val bootTime = System.currentTimeMillis() - uptime
            "boot_${bootTime / 60000}"
        }
    }

    fun recordLoaded(context: Context, moduleName: String, devNode: String, hidden: Boolean) {
        try {
            val cleanNode = devNode.removePrefix("/dev/").trim()
            if (cleanNode.isNotBlank() && SafetyGuard.isSystemNode(cleanNode)) return
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val bootId = getBootId()
            p.edit()
                .putBoolean(KEY_DRIVER_LOADED, true)
                .putString(KEY_BOOT_ID, bootId)
                .putString(KEY_MODULE_NAME, moduleName)
                .putString(KEY_DEV_NODE, cleanNode)
                .putBoolean(KEY_HIDDEN, hidden)
                .putLong(KEY_LOAD_TIME, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {}
    }

    fun clear(context: Context) {
        try {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            p.edit().clear().apply()
        } catch (_: Exception) {}
    }

    data class SessionState(
        val isLoaded: Boolean,
        val moduleName: String,
        val devNode: String,
        val isHidden: Boolean
    )

    fun checkSession(context: Context): SessionState {
        try {
            val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val isLoaded = p.getBoolean(KEY_DRIVER_LOADED, false)
            if (!isLoaded) return SessionState(false, "", "", false)

            val devNode = p.getString(KEY_DEV_NODE, "") ?: ""
            if (devNode.isNotBlank() && SafetyGuard.isSystemNode(devNode)) {
                clear(context)
                return SessionState(false, "", "", false)
            }

            val loadTime = p.getLong(KEY_LOAD_TIME, 0L)
            val bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            if (loadTime > 0L && bootTime > loadTime + 3000L) {
                clear(context)
                return SessionState(false, "", "", false)
            }

            val savedBootId = p.getString(KEY_BOOT_ID, "") ?: ""
            val currentBootId = getBootId()

            if (savedBootId.isNotBlank() && currentBootId.isNotBlank() && savedBootId != currentBootId) {
                clear(context)
                return SessionState(false, "", "", false)
            }

            val moduleName = p.getString(KEY_MODULE_NAME, "") ?: ""
            val isHidden = p.getBoolean(KEY_HIDDEN, false)
            return SessionState(true, moduleName, devNode, isHidden)
        } catch (_: Exception) {
            return SessionState(false, "", "", false)
        }
    }
}

