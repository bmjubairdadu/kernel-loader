package com.kernelloader.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.os.Debug
import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import kotlin.system.exitProcess

object SecurityShield {

    private const val EXPECTED_SIGNATURE_SHA256 = "abf2d7a42765af2a7edbb9633ea96092fb7228454b2ac3599e04daee611782cd"
    private const val EXPECTED_PACKAGE_NAME = "com.kernelloader"

    @Volatile
    private var watchdogStarted = false

    fun protect(context: Context) {
        verifyIntegrityAndEnvironment(context)

        if (!watchdogStarted) {
            watchdogStarted = true
            val watchdogThread = Thread({
                while (true) {
                    try {
                        Thread.sleep(1500)
                        verifyIntegrityAndEnvironment(context)
                    } catch (_: InterruptedException) {
                        break
                    } catch (_: Throwable) {
                    }
                }
            }, "KL-Watchdog")
            watchdogThread.isDaemon = true
            watchdogThread.priority = Thread.MAX_PRIORITY
            watchdogThread.start()
        }
    }

    fun verifyIntegrityAndEnvironment(context: Context) {
        if (!isSignatureValid(context)) {
            killSilently()
        }

        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            killSilently()
        }

        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            killSilently()
        }

        if (isDebuggerOrHookAttached()) {
            killSilently()
        }

        if (isVirtualAppEnvironment(context)) {
            killSilently()
        }
    }

    private fun isSignatureValid(context: Context): Boolean {
        try {
            if (context.packageName != EXPECTED_PACKAGE_NAME) return false
            val pm = context.packageManager
            val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                pi.signingInfo?.apkContentsSigners ?: pi.signingInfo?.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                val pi = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                pi.signatures
            }
            if (signatures.isNullOrEmpty()) return false
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(signatures[0].toByteArray())
            val actualHash = digest.joinToString("") { "%02x".format(it) }
            return actualHash.equals(EXPECTED_SIGNATURE_SHA256, ignoreCase = true)
        } catch (_: Throwable) {
            return false
        }
    }

    fun isDebuggerOrHookAttached(): Boolean {
        try {
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                return true
            }

            val statusFile = File("/proc/self/status")
            if (statusFile.exists()) {
                BufferedReader(FileReader(statusFile)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.startsWith("TracerPid:")) {
                            val pid = line.substringAfter(':').trim().toIntOrNull() ?: 0
                            if (pid > 0) return true
                        }
                        line = reader.readLine()
                    }
                }
            }

            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists()) {
                BufferedReader(FileReader(mapsFile)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val lower = line.lowercase()
                        if (lower.contains("frida-agent") ||
                            lower.contains("frida-gadget") ||
                            lower.contains("gadget.so") ||
                            lower.contains("libxposed") ||
                            lower.contains("lspd.so") ||
                            lower.contains("sandhook") ||
                            lower.contains("edxposed") ||
                            lower.contains("substrate") ||
                            lower.contains("gum-js-loop") ||
                            lower.contains("libpine.so") ||
                            lower.contains("libmembarrier")
                        ) {
                            return true
                        }
                        line = reader.readLine()
                    }
                }
            }

            val taskDir = File("/proc/self/task")
            if (taskDir.exists() && taskDir.isDirectory) {
                taskDir.listFiles()?.forEach { task ->
                    val commFile = File(task, "comm")
                    if (commFile.exists()) {
                        val comm = runCatching { commFile.readText().trim().lowercase() }.getOrNull() ?: ""
                        if (comm.contains("gmain") ||
                            comm.contains("gum-js-loop") ||
                            comm.contains("gdbus") ||
                            comm.contains("pool-frida")
                        ) {
                            return true
                        }
                    }
                }
            }

            val hookClasses = arrayOf(
                "de.robv.android.xposed.XposedBridge",
                "de.robv.android.xposed.XposedHelpers",
                "me.weishu.exposed.ExposedBridge",
                "top.canyie.pine.Pine"
            )
            for (cls in hookClasses) {
                try {
                    Class.forName(cls)
                    return true
                } catch (_: ClassNotFoundException) {
                }
            }

            val fridaPorts = intArrayOf(27042, 27043)
            for (port in fridaPorts) {
                try {
                    Socket().use { s ->
                        s.connect(InetSocketAddress("127.0.0.1", port), 20)
                        return true
                    }
                } catch (_: Throwable) {
                }
            }

        } catch (_: Throwable) {
        }
        return false
    }

    private fun isVirtualAppEnvironment(context: Context): Boolean {
        try {
            val path = context.filesDir.absolutePath.lowercase()
            val blacklistedContainers = arrayOf(
                "com.lbe.parallel",
                "com.excelliance.dualaid",
                "io.va.exposed",
                "com.vphonegaga.titan",
                "com.dual.dualspace",
                "com.cloneapp.parallelspace",
                "virtualapp"
            )
            for (c in blacklistedContainers) {
                if (path.contains(c)) return true
            }
        } catch (_: Throwable) {
        }
        return false
    }

    fun openSecureConnection(urlStr: String): HttpURLConnection {
        if (isDebuggerOrHookAttached()) {
            killSilently()
        }
        val url = URL(urlStr)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Cleartext HTTP is strictly forbidden by SecurityShield"
        }
        val conn = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.setRequestProperty("X-Security-Client", "Shielded-v2")
        return conn
    }

    private fun killSilently() {
        try {
            Process.killProcess(Process.myPid())
        } catch (_: Throwable) {
        }
        exitProcess(0)
    }
}
