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

        startRootDaemon(context, kernelRelease, variant, file, isOta)
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

    fun isRevoked(context: Context): Boolean {
        if (prefs(context).getBoolean(KEY_IS_REVOKED, false)) return true
        return try {
            Shell.cmd("test -f /data/adb/kloder/kloader_revoked || test -f /data/local/tmp/kloader_revoked").exec().isSuccess
        } catch (_: Exception) {
            false
        }
    }

    fun getRevokedReason(context: Context): String =
        prefs(context).getString(KEY_REVOKED_REASON, "") ?: ""

    fun setRevoked(context: Context, revoked: Boolean, reason: String = "") {
        prefs(context).edit().apply {
            putBoolean(KEY_IS_REVOKED, revoked)
            putString(KEY_REVOKED_REASON, reason)
            apply()
        }
        if (!revoked) {
            try {
                Shell.cmd(
                    "rm -f /data/adb/kloder/kloader_revoked 2>/dev/null",
                    "rm -f /data/local/tmp/kloader_revoked 2>/dev/null"
                ).exec()
            } catch (_: Exception) {}
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
                it.contains("entryi", ignoreCase = true) ||
                it.contains("daisy", ignoreCase = true) ||
                it.contains("wanbai", ignoreCase = true) ||
                it.contains("kloader", ignoreCase = true)
            }
            if (hasModule) return true
            val hidden = SafetyGuard.hiddenDriverClass() != null
            if (hidden) return true
            val node = Shell.cmd(
                "test -e /dev/kloaderctl || test -e /dev/daisyctl || test -e /dev/entryi || test -e /dev/kmem_337 || test -e /dev/wanbai"
            ).exec().isSuccess
            node
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
        val short = RootChecker.kernelShortVersion(kernelRelease)
        val scriptContent = """
#!/system/bin/sh
DAEMON_PID_FILE="/data/adb/kloder/kloader_daemon.pid"
REVOKED_MARKER="/data/adb/kloder/kloader_revoked"
TMP_MANIFEST="/data/adb/kloder/tmp_manifest.json"

mkdir -p /data/adb/kloder 2>/dev/null
echo $$ > "${'$'}DAEMON_PID_FILE"

URL1="https://raw.githubusercontent.com/bmjubairdadu/kernel-loader/drivers/drivers.json"
URL2="https://cdn.jsdelivr.net/gh/bmjubairdadu/kernel-loader@drivers/drivers.json"
URL3="https://github.com/bmjubairdadu/kernel-loader/releases/latest/download/drivers.json"

KERNEL_RELEASE="$kernelRelease"
KERNEL_SHORT="$short"
LOADED_FILE="$file"
LOADED_VARIANT="$variant"
IS_OTA="${if (isOta) 1 else 0}"

check_resident() {
    if grep -qE "memacc|kmem|entryi|daisy|wanbai|kloader" /proc/modules 2>/dev/null; then
        return 0
    fi
    if ls /sys/class 2>/dev/null | grep -qE "dmsync|kmem|memacc"; then
        return 0
    fi
    if [ -e /dev/kloaderctl ] || [ -e /dev/daisyctl ] || [ -e /dev/entryi ] || [ -e /dev/kmem_337 ] || [ -e /dev/wanbai ]; then
        return 0
    fi
    return 1
}

fetch_manifest() {
    rm -f "${'$'}TMP_MANIFEST"
    CURL_BIN=${'$'}(which curl 2>/dev/null || echo "/system/bin/curl")
    if [ ! -x "${'$'}CURL_BIN" ] && [ ! -f "${'$'}CURL_BIN" ]; then
        return 1
    fi

    "${'$'}CURL_BIN" -s --connect-timeout 10 -m 20 "${'$'}URL1" > "${'$'}TMP_MANIFEST" 2>/dev/null
    if [ -s "${'$'}TMP_MANIFEST" ] && grep -q '"drivers"' "${'$'}TMP_MANIFEST" 2>/dev/null; then
        return 0
    fi

    "${'$'}CURL_BIN" -s --connect-timeout 10 -m 20 "${'$'}URL2" > "${'$'}TMP_MANIFEST" 2>/dev/null
    if [ -s "${'$'}TMP_MANIFEST" ] && grep -q '"drivers"' "${'$'}TMP_MANIFEST" 2>/dev/null; then
        return 0
    fi

    "${'$'}CURL_BIN" -s --connect-timeout 10 -m 20 "${'$'}URL3" > "${'$'}TMP_MANIFEST" 2>/dev/null
    if [ -s "${'$'}TMP_MANIFEST" ] && grep -q '"drivers"' "${'$'}TMP_MANIFEST" 2>/dev/null; then
        return 0
    fi

    rm -f "${'$'}TMP_MANIFEST"
    return 1
}

while true; do
    sleep 35

    if ! check_resident; then
        rm -f "${'$'}DAEMON_PID_FILE"
        exit 0
    fi

    if [ "${'$'}IS_OTA" != "1" ]; then
        continue
    fi

    if ! fetch_manifest; then
        continue
    fi

    FOUND=0
    if [ -n "${'$'}LOADED_FILE" ] && grep -q "\"${'$'}LOADED_FILE\"" "${'$'}TMP_MANIFEST" 2>/dev/null; then
        FOUND=1
    elif [ -n "${'$'}KERNEL_RELEASE" ] && grep -qi "${'$'}KERNEL_RELEASE" "${'$'}TMP_MANIFEST" 2>/dev/null; then
        FOUND=1
    elif [ -n "${'$'}KERNEL_SHORT" ]; then
        if grep -q "\"${'$'}KERNEL_SHORT" "${'$'}TMP_MANIFEST" 2>/dev/null || grep -q -- "-${'$'}KERNEL_SHORT" "${'$'}TMP_MANIFEST" 2>/dev/null || grep -q "${'$'}KERNEL_SHORT" "${'$'}TMP_MANIFEST" 2>/dev/null; then
            FOUND=1
        fi
    fi

    rm -f "${'$'}TMP_MANIFEST"

    if [ "${'$'}FOUND" -eq 1 ]; then
        continue
    fi

    # DRIVER REMOVED FROM DB! ENFORCE REVOCATION AND REBOOT
    echo "REVOKED_DELETED_BY_ADMIN" > "${'$'}REVOKED_MARKER"
    echo "REVOKED_DELETED_BY_ADMIN" > /data/local/tmp/kloader_revoked 2>/dev/null

    rm -f /data/local/tmp/*.ko 2>/dev/null
    rm -f /data/adb/kloder/*.ko 2>/dev/null
    rm -rf /data/data/com.kernelloader/files/ota_drivers 2>/dev/null

    sync
    sleep 1
    reboot 2>/dev/null || svc power reboot 2>/dev/null || /system/bin/reboot
    exit 0
done
        """.trimIndent()

        try {
            val localScript = File(context.filesDir, "kloader_daemon.sh")
            localScript.writeText(scriptContent)

            Shell.cmd(
                "mkdir -p /data/adb/kloder 2>/dev/null",
                "if [ -f /data/adb/kloder/kloader_daemon.pid ]; then kill -9 \$(cat /data/adb/kloder/kloader_daemon.pid 2>/dev/null) 2>/dev/null; fi",
                "cp \"${localScript.absolutePath}\" /data/adb/kloder/kloader_daemon.sh 2>/dev/null",
                "cp \"${localScript.absolutePath}\" /data/local/tmp/kloader_daemon.sh 2>/dev/null",
                "chmod 755 /data/adb/kloder/kloader_daemon.sh 2>/dev/null",
                "chmod 755 /data/local/tmp/kloader_daemon.sh 2>/dev/null",
                "nohup /data/adb/kloder/kloader_daemon.sh >/dev/null 2>&1 &"
            ).exec()
        } catch (_: Exception) {}
    }

    fun stopRootDaemon() {
        try {
            Shell.cmd(
                "if [ -f /data/adb/kloder/kloader_daemon.pid ]; then kill -9 \$(cat /data/adb/kloder/kloader_daemon.pid 2>/dev/null) 2>/dev/null; fi",
                "rm -f /data/adb/kloder/kloader_daemon.pid 2>/dev/null"
            ).exec()
        } catch (_: Exception) {}
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

        if (record?.loadedFromOta == true || record != null) {
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
            "echo 'REVOKED_DELETED_BY_ADMIN' > /data/adb/kloder/kloader_revoked 2>/dev/null",
            "echo 'REVOKED_DELETED_BY_ADMIN' > /data/local/tmp/kloader_revoked 2>/dev/null",
            "rm -f /data/local/tmp/kloader_ota.ko 2>/dev/null",
            "rm -f /data/local/tmp/kloader_local.ko 2>/dev/null",
            "rm -f /data/local/tmp/kmem*.ko 2>/dev/null",
            "rm -f /data/adb/kloder/*.ko 2>/dev/null"
        ).exec()

        onLog("[SECURITY] Device restarting automatically to unload driver...", "ERR")

        try {
            kotlinx.coroutines.delay(1500L)
            Shell.cmd(
                "reboot 2>/dev/null || svc power reboot 2>/dev/null || /system/bin/reboot 2>/dev/null"
            ).exec()
        } catch (e: Exception) {
            onLog("[ERR] Auto-restart failed: ${e.message} - reboot manually", "ERR")
        }
    }
}
