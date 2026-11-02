package com.kernelloader.security

import android.content.Context
import android.os.Debug
import android.os.Process
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import kotlin.system.exitProcess

object SecurityShield {

    private const val XOR_KEY: Byte = 0x5A

    private val SNIFFER_PACKAGES = listOf(
        "com.guoshi.httpcanary",
        "com.reqable.android",
        "app.greyshirts.sslcapture",
        "com.minhui.networkcapture",
        "org.sandrop.webscarab"
    )

    fun protect(context: Context) {
        if (isDebuggerOrHookAttached()) {
            killSilently()
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
                            lower.contains("libxposed")
                        ) {
                            return true
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    fun isSnifferDetected(context: Context): Boolean {
        try {
            val httpHost = System.getProperty("http.proxyHost")
            val httpPort = System.getProperty("http.proxyPort")
            val httpsHost = System.getProperty("https.proxyHost")
            val httpsPort = System.getProperty("https.proxyPort")
            if (!httpHost.isNullOrBlank() || !httpPort.isNullOrBlank() ||
                !httpsHost.isNullOrBlank() || !httpsPort.isNullOrBlank()
            ) {
                return true
            }

            val pm = context.packageManager
            for (pkg in SNIFFER_PACKAGES) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    return true
                } catch (_: Exception) {}
            }
        } catch (_: Throwable) {}
        return false
    }

    fun openSecureConnection(urlStr: String, context: Context? = null): HttpURLConnection {
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

    fun decodeProtectedString(b64: String): String {
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    fun xorDecode(bytes: ByteArray, key: Byte = XOR_KEY): String {
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            out[i] = (bytes[i].toInt() xor key.toInt()).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    private fun killSilently() {
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
