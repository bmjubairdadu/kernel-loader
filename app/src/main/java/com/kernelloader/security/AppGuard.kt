package com.kernelloader.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.security.MessageDigest

object AppGuard {

    private const val TRUSTED_SIG_SHA256 =
        "4B:B1:54:42:21:42:81:10:A1:D5:68:03:77:1B:BF:53:4D:BD:4D:18:C9:C2:05:39:52:2A:E1:ED:CB:FB:C8:8A"

    fun isDebuggerAttached(): Boolean =
        Debug.isDebuggerConnected() || Debug.waitingForDebugger()

    fun isXposedHooked(): Boolean = try {
        Class.forName("de.robv.android.xposed.XposedBridge")
        true
    } catch (_: Throwable) {
        false
    }

    fun isSignatureValid(context: Context): Boolean = try {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNING_CERTIFICATES
            )
            info.signingInfo?.apkContentsSigners ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(
                context.packageName, PackageManager.GET_SIGNATURES
            )
            info.signatures ?: emptyArray()
        }
        sigs.any { sha256Hex(it.toByteArray()) == TRUSTED_SIG_SHA256 }
    } catch (_: Throwable) {
        false
    }

    fun isTampered(context: Context): Boolean =
        isDebuggerAttached() || isXposedHooked() || !isSignatureValid(context)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(":") { String.format("%02X", it) }
}
