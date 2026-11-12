package com.kernelloader.driver

import android.content.Context
import java.io.File

object DriverSessionManager {
    private const val SESSION_FILE = "/dev/.kloder_session"
    private const val PREF_NAME = "kloder_driver_session"
    private const val KEY_BOOT_ID = "boot_id"
    private const val KEY_NODE = "node"
    private const val KEY_MODULE = "module"
    private const val KEY_STEALTH = "stealth"

    fun getBootId(): String = try {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
    } catch (_: Exception) {
        ""
    }

    fun markLoaded(context: Context, node: String, module: String, stealth: Boolean) {
        val bootId = getBootId()
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_BOOT_ID, bootId)
            .putString(KEY_NODE, node)
            .putString(KEY_MODULE, module)
            .putBoolean(KEY_STEALTH, stealth)
            .apply()

        try {
            val content = "$bootId|$node|$module|$stealth"
            val p = ProcessBuilder("su", "-c", "echo '$content' > $SESSION_FILE && chmod 644 $SESSION_FILE").start()
            p.waitFor()
        } catch (_: Exception) {
        }
    }

    fun clearSession(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        try {
            val p = ProcessBuilder("su", "-c", "rm -f $SESSION_FILE").start()
            p.waitFor()
        } catch (_: Exception) {
        }
    }

    fun isSessionActive(context: Context): Pair<Boolean, Triple<String, String, Boolean>> {
        val currentBoot = getBootId()
        if (currentBoot.isBlank()) return Pair(false, Triple("", "", false))

        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val savedBoot = prefs.getString(KEY_BOOT_ID, "") ?: ""
        if (savedBoot.isNotBlank() && savedBoot == currentBoot) {
            val node = prefs.getString(KEY_NODE, "") ?: ""
            val module = prefs.getString(KEY_MODULE, "") ?: ""
            val stealth = prefs.getBoolean(KEY_STEALTH, false)
            return Pair(true, Triple(node, module, stealth))
        }

        try {
            val file = File(SESSION_FILE)
            if (file.exists()) {
                val line = file.readText().trim()
                val parts = line.split("|")
                if (parts.size >= 4 && parts[0] == currentBoot) {
                    return Pair(true, Triple(parts[1], parts[2], parts[3].toBoolean()))
                }
            }
        } catch (_: Exception) {
        }

        return Pair(false, Triple("", "", false))
    }
}
