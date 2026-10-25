package com.kernelloader.driver

import android.content.Context
import android.content.SharedPreferences
import com.kernelloader.root.RootChecker
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object DriverRevocationManager {

    private const val PREFS_NAME = "driver_security_prefs"
    private const val KEY_LOADED_KERNEL = "loaded_kernel_release"
    private const val KEY_LOADED_SHORT = "loaded_short_version"
    private const val KEY_LOADED_VARIANT = "loaded_variant"
    private const val KEY_LOADED_FILE = "loaded_file"
    private const val KEY_LOADED_SHA256 = "loaded_sha256"
    private const val KEY_LOADED_VERSION = "loaded_version_str"
    private const val KEY_LOADED_TIME = "loaded_time_ms"
    private const val KEY_LOADED_FROM_OTA = "loaded_from_ota"
    private const val KEY_IS_REVOKED = "is_revoked"
    private const val KEY_REVOKED_REASON = "revoked_reason"

    data class LoadedRecord(
        val kernelRelease: String,
        val shortVersion: String,
        val variant: String,
        val file: String,
        val sha256: String,
        val versionStr: String,
        val loadedTimeMs: Long,
        val loadedFromOta: Boolean
    )

    sealed interface RevocationVerdict {
        data object NotLoaded : RevocationVerdict
        data object Offline : RevocationVerdict
        data object NotManagedByDb : RevocationVerdict
        data class Valid(val entry: OtaDriverStore.DriverEntry) : RevocationVerdict
        data class Upgraded(val currentFile: String, val newEntry: OtaDriverStore.DriverEntry) : RevocationVerdict
        data class Revoked(val reason: String) : RevocationVerdict
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun recordLoadedDriver(
        context: Context,
        entry: OtaDriverStore.DriverEntry?,
        kernelRelease: String,
        variant: String,
        isOta: Boolean
    ) {
        val short = RootChecker.kernelShortVersion(kernelRelease)
        val file = entry?.file ?: ""
        prefs(context).edit().apply {
            putString(KEY_LOADED_KERNEL, kernelRelease)
            putString(KEY_LOADED_SHORT, short)
            putString(KEY_LOADED_VARIANT, variant.lowercase())
            putString(KEY_LOADED_FILE, file)
            putString(KEY_LOADED_SHA256, entry?.sha256 ?: "")
            putString(KEY_LOADED_VERSION, entry?.version ?: "")
            putLong(KEY_LOADED_TIME, System.currentTimeMillis())
            putBoolean(KEY_LOADED_FROM_OTA, isOta)
            putBoolean(KEY_IS_REVOKED, false)
            putString(KEY_REVOKED_REASON, "")
            apply()
        }

        // Daemon disabled to ensure zero-footprint stealth
        // startRootDaemon(context, kernelRelease, variant, file, isOta)
    }

    fun clearLoadedDriver(context: Context) {
        prefs(context).edit().apply {
            remove(KEY_LOADED_KERNEL)
            remove(KEY_LOADED_SHORT)
            remove(KEY_LOADED_VARIANT)
            remove(KEY_LOADED_FILE)
            remove(KEY_LOADED_SHA256)
            remove(KEY_LOADED_VERSION)
            remove(KEY_LOADED_TIME)
            remove(KEY_LOADED_FROM_OTA)
            apply()
        }
        stopRootDaemon()
    }

    fun isRevoked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IS_REVOKED, false)

    fun getRevokedReason(context: Context): String =
        prefs(context).getString(KEY_REVOKED_REASON, "") ?: ""

    fun setRevoked(context: Context, revoked: Boolean, reason: String = "") {
        prefs(context).edit().apply {
            putBoolean(KEY_IS_REVOKED, revoked)
            putString(KEY_REVOKED_REASON, reason)
            apply()
        }
    }

    fun getLoadedRecord(context: Context): LoadedRecord? {
        val p = prefs(context)
        val kernel = p.getString(KEY_LOADED_KERNEL, null) ?: return null
        return LoadedRecord(
            kernelRelease = kernel,
            shortVersion = p.getString(KEY_LOADED_SHORT, "") ?: "",
            variant = p.getString(KEY_LOADED_VARIANT, "") ?: "",
            file = p.getString(KEY_LOADED_FILE, "") ?: "",
            sha256 = p.getString(KEY_LOADED_SHA256, "") ?: "",
            versionStr = p.getString(KEY_LOADED_VERSION, "") ?: "",
            loadedTimeMs = p.getLong(KEY_LOADED_TIME, 0L),
            loadedFromOta = p.getBoolean(KEY_LOADED_FROM_OTA, true)
        )
    }

    fun isDriverResident(): Boolean {
        return try {
            val loaded = SafetyGuard.loadedModuleNames()
            val hasModule = loaded.any {
                it.contains("memacc", ignoreCase = true) ||
                it.contains("kmem", ignoreCase = true) ||
                it.contains("dmsync", ignoreCase = true)
            }
            if (hasModule) return true
            val hidden = SafetyGuard.hiddenDriverClass() != null
            if (hidden) return true
            false
        } catch (_: Exception) {
            false
        }
    }

    fun startRootDaemon(
        context: Context,
        kernelRelease: String,
        variant: String,
        file: String,
        isOta: Boolean
    ) {
        // Disabled for anti-cheat stealth (no background shell processes)
        stopRootDaemon()
    }

    fun stopRootDaemon() {
        // Disabled for anti-cheat stealth
    }

    fun evaluate(
        context: Context,
        manifest: OtaDriverStore.Manifest?,
        isCurrentlyLoaded: Boolean,
        currentKernelRelease: String = RootChecker.getKernelRelease() ?: ""
    ): RevocationVerdict {
        if (!isCurrentlyLoaded) {
            return RevocationVerdict.NotLoaded
        }

        if (manifest == null || manifest.drivers.isEmpty()) {
            return RevocationVerdict.Offline
        }

        val record = getLoadedRecord(context)
        val kernel = if (currentKernelRelease.isNotBlank()) currentKernelRelease else record?.kernelRelease ?: ""
        val short = RootChecker.kernelShortVersion(kernel)
        val variant = record?.variant ?: ""

        val matching = manifest.drivers.filter { entry ->
            val vMatch = variant.isBlank() || OtaDriverStore.variantMatches(entry, variant)
            val kShortMatch = RootChecker.kernelShortVersion(entry.version) == short
            val kFullMatch = entry.version.contains(kernel, ignoreCase = true) ||
                             kernel.contains(entry.file.removeSuffix(".ko").removePrefix("rt_").removePrefix("qx_"), ignoreCase = true) ||
                             (record != null && record.file.isNotBlank() && entry.file.equals(record.file, ignoreCase = true))
            vMatch && (kShortMatch || kFullMatch)
        }

        if (matching.isNotEmpty()) {
            val best = matching.first()
            if (isRevoked(context)) {
                setRevoked(context, false)
            }
            if (record != null && record.sha256.isNotBlank() && best.sha256.isNotBlank()) {
                if (!record.sha256.equals(best.sha256, ignoreCase = true) || !record.file.equals(best.file, ignoreCase = true)) {
                    return RevocationVerdict.Upgraded(record.file, best)
                }
            }
            return RevocationVerdict.Valid(best)
        }

        if (record != null && record.loadedFromOta) {
            val label = if (variant.isNotBlank()) "${variant.uppercase()} driver" else "driver"
            return RevocationVerdict.Revoked("$label for kernel $kernel deleted from DB")
        }

        return RevocationVerdict.NotManagedByDb
    }

    suspend fun enforceRevocation(
        context: Context,
        reason: String,
        onLog: (String, String) -> Unit = { _, _ -> }
    ) = withContext(Dispatchers.IO) {
        setRevoked(context, true, reason)
        stopRootDaemon()
        clearLoadedDriver(context)

        onLog("[SECURITY] Driver deleted from DB: $reason", "ERR")
        onLog("[SECURITY] Purging cached driver files...", "WARN")

        try {
            OtaDriverStore.cacheDir(context).listFiles()?.forEach { it.delete() }
            File(context.filesDir, "drivers").listFiles()?.forEach { it.delete() }
        } catch (_: Exception) {}

        Shell.cmd(
            "rm -f /data/local/tmp/kloader_ota.ko 2>/dev/null",
            "rm -f /data/local/tmp/kloader_local.ko 2>/dev/null",
            "rm -f /data/local/tmp/kmem*.ko 2>/dev/null"
        ).exec()

        onLog("[SECURITY] Device restarting automatically to unload driver...", "ERR")

        try {
            kotlinx.coroutines.delay(1500L)
            val res = Shell.cmd(
                "reboot 2>/dev/null || svc power reboot 2>/dev/null || /system/bin/reboot 2>/dev/null"
            ).exec()
            if (!res.isSuccess) {
                onLog("[ERR] Auto-restart rejected - reboot manually NOW", "ERR")
            }
        } catch (e: Exception) {
            onLog("[ERR] Auto-restart failed: ${e.message} - reboot manually", "ERR")
        }
    }
}
