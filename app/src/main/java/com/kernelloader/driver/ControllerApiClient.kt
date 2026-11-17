package com.kernelloader.driver

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ControllerApiClient {

    const val REVOKED_POPUP_MESSAGE = "Your driver access has been suspended. Please contact our support team."

    private const val PREF_NAME = "kloder_controller_config"
    private const val KEY_CONTROLLER_HOST = "controller_host"
    private const val KEY_LICENSE_KEY = "license_key"
    private const val KEY_ACCESS_REVOKED = "access_revoked"
    private const val KEY_REVOKED_REASON = "revoked_reason"

    private const val DEFAULT_HOST = "127.0.0.1:8088"
    private const val DEFAULT_LICENSE_KEY = "LIC-TRIAL-0001"

    data class LicenseVerifyResult(
        val valid: Boolean,
        val status: String,
        val message: String,
        val downloadAuthorized: Boolean,
        val tier: String = "Free"
    )

    fun getDeviceId(context: Context): String {
        return runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: "DEV_${Build.BOARD}_${Build.HARDWARE}_${Build.MODEL.replace(" ", "_")}"
    }

    fun getLicenseKey(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LICENSE_KEY, DEFAULT_LICENSE_KEY) ?: DEFAULT_LICENSE_KEY
    }

    fun setLicenseKey(context: Context, key: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LICENSE_KEY, key.trim()).apply()
    }

    fun getControllerHost(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val h = prefs.getString(KEY_CONTROLLER_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        return if (h == "10.0.2.2:8088") DEFAULT_HOST else h
    }

    fun setControllerHost(context: Context, host: String) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CONTROLLER_HOST, host.trim()).apply()
    }

    fun isAccessRevoked(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ACCESS_REVOKED, false)
    }

    fun setAccessRevoked(context: Context, revoked: Boolean, reason: String = "") {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ACCESS_REVOKED, revoked)
            .putString(KEY_REVOKED_REASON, reason)
            .apply()
    }

    fun getBaseApiUrl(context: Context): String {
        val host = getControllerHost(context)
        val cleanHost = host.removePrefix("http://").removePrefix("https://").trimEnd('/')
        return "http://$cleanHost/api/v1"
    }

    suspend fun verifyLicense(context: Context): LicenseVerifyResult = withContext(Dispatchers.IO) {
        val deviceId = getDeviceId(context)
        val licenseKey = getLicenseKey(context)
        val kernelRelease = Build.VERSION.RELEASE

        try {
            val url = URL("${getBaseApiUrl(context)}/auth/verify")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 3000
                readTimeout = 4000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "KernelLoader/6.1")
                setRequestProperty("X-App-Package", context.packageName)
                setRequestProperty("X-App-Signature", DriverCrypto.getAppSignatureHash(context))
            }

            val body = JSONObject().apply {
                put("device_id", deviceId)
                put("license_key", licenseKey)
                put("kernel_release", kernelRelease)
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val code = conn.responseCode
            val responseText = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }

            if (code == 403) {
                setAccessRevoked(context, true, "Revoked by controller")
                return@withContext LicenseVerifyResult(
                    valid = false,
                    status = "Revoked",
                    message = REVOKED_POPUP_MESSAGE,
                    downloadAuthorized = false
                )
            }

            val json = JSONObject(responseText.ifBlank { "{}" })
            val status = json.optString("status", if (code == 200) "Active" else "Unknown")
            val auth = json.optBoolean("download_authorized", code == 200)
            val msg = json.optString("message", "")
            val tier = json.optString("tier", "Free")

            if (status.equals("Suspended", ignoreCase = true) ||
                status.equals("Revoked", ignoreCase = true) ||
                status.equals("Expired", ignoreCase = true)
            ) {
                setAccessRevoked(context, true, status)
                return@withContext LicenseVerifyResult(
                    valid = false,
                    status = status,
                    message = REVOKED_POPUP_MESSAGE,
                    downloadAuthorized = false,
                    tier = tier
                )
            }

            if (code == 200 && auth) {
                setAccessRevoked(context, false)
            }

            LicenseVerifyResult(
                valid = code == 200 && auth,
                status = status,
                message = msg,
                downloadAuthorized = auth,
                tier = tier
            )
        } catch (_: Exception) {
            LicenseVerifyResult(
                valid = !isAccessRevoked(context),
                status = if (isAccessRevoked(context)) "Revoked" else "Offline",
                message = if (isAccessRevoked(context)) REVOKED_POPUP_MESSAGE else "Operating in offline mode",
                downloadAuthorized = !isAccessRevoked(context)
            )
        }
    }

    suspend fun notifyLoad(context: Context, driverFile: String, success: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${getBaseApiUrl(context)}/telemetry/load")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "KernelLoader/6.1")
                setRequestProperty("X-App-Package", context.packageName)
                setRequestProperty("X-App-Signature", DriverCrypto.getAppSignatureHash(context))
            }
            val body = JSONObject().apply {
                put("device_id", getDeviceId(context))
                put("license_key", getLicenseKey(context))
                put("driver_file", driverFile)
                put("success", success)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            conn.responseCode
        }
    }

    suspend fun notifyUnload(context: Context, moduleName: String, graceful: Boolean) = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL("${getBaseApiUrl(context)}/telemetry/unload")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 2500
                readTimeout = 3000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("User-Agent", "KernelLoader/6.1")
                setRequestProperty("X-App-Package", context.packageName)
                setRequestProperty("X-App-Signature", DriverCrypto.getAppSignatureHash(context))
            }
            val body = JSONObject().apply {
                put("device_id", getDeviceId(context))
                put("license_key", getLicenseKey(context))
                put("module_name", moduleName)
                put("graceful", graceful)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            conn.responseCode
        }
    }

    suspend fun checkActiveDriverStatus(context: Context, loadedDriver: String, loadedSha: String) = withContext(Dispatchers.IO) {
        val candidateHosts = listOf(
            getControllerHost(context),
            DEFAULT_HOST,
            "192.168.0.100:8088"
        ).distinct()

        for (host in candidateHosts) {
            val cleanHost = host.removePrefix("http://").removePrefix("https://").trimEnd('/')
            val endpoint = "http://$cleanHost/api/v1/telemetry/heartbeat"
            val success = runCatching {
                val url = URL(endpoint)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 2500
                    readTimeout = 3000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", "KernelLoader/6.1")
                    setRequestProperty("X-App-Package", context.packageName)
                    setRequestProperty("X-App-Signature", DriverCrypto.getAppSignatureHash(context))
                }
                val body = JSONObject().apply {
                    put("device_id", getDeviceId(context))
                    put("license_key", getLicenseKey(context))
                    put("loaded_driver", loadedDriver)
                    put("loaded_sha", loadedSha)
                }
                OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                val code = conn.responseCode
                if (code == 200) {
                    val resp = conn.inputStream.bufferedReader().use { it.readText() }
                    android.util.Log.i("KernelLoader", "[Heartbeat] Success from $cleanHost: $resp")
                    val json = JSONObject(resp)
                    val rebootReq = json.optBoolean("reboot_required", false)
                    if (rebootReq) {
                        android.util.Log.w("KernelLoader", "[Heartbeat] REBOOT REQUIRED! Rebooting device immediately...")
                        DriverSessionManager.triggerDeviceReboot(context, "Driver was frozen/deleted with restart directive by administrator.")
                    }
                    true
                } else false
            }.getOrDefault(false)

            if (success) {
                setControllerHost(context, cleanHost)
                break
            }
        }
    }
}
