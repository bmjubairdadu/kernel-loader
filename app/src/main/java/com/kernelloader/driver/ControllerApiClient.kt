package com.kernelloader.driver

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ControllerApiClient {

    const val REVOKED_POPUP_MESSAGE = "Your driver access has been suspended. Please contact our support team."

    private const val PREF_NAME = "kloder_controller_config"
    private const val KEY_CONTROLLER_HOST = "controller_host"
    private const val KEY_LICENSE_KEY = "license_key"
    private const val KEY_ACCESS_REVOKED = "access_revoked"
    private const val KEY_REVOKED_REASON = "revoked_reason"

    private const val DEFAULT_HOST = "127.0.0.1"
    private const val DEFAULT_LICENSE_KEY = "LIC-TRIAL-0001"

    data class LicenseVerifyResult(
        val valid: Boolean,
        val status: String,
        val message: String,
        val downloadAuthorized: Boolean,
        val tier: String = "Pro"
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

    fun getControllerHost(context: Context): String {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CONTROLLER_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
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
        return ""
    }

    suspend fun verifyLicense(context: Context): LicenseVerifyResult = withContext(Dispatchers.IO) {
        LicenseVerifyResult(
            valid = !isAccessRevoked(context),
            status = if (isAccessRevoked(context)) "Revoked" else "Active",
            message = if (isAccessRevoked(context)) REVOKED_POPUP_MESSAGE else "Active",
            downloadAuthorized = !isAccessRevoked(context),
            tier = "Pro"
        )
    }

    suspend fun notifyLoad(context: Context, driverFile: String, success: Boolean) = withContext(Dispatchers.IO) {
    }

    suspend fun checkActiveDriverStatus(context: Context, loadedDriver: String, loadedSha: String) = withContext(Dispatchers.IO) {
    }
}
