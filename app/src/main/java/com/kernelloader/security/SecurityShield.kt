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

    // Known sniffing / MITM proxy capture packages
    private val SNIFFER_PACKAGES = listOf(
        "com.guoshi.httpcanary",
        "com.reqable.android",
        "app.greyshirts.sslcapture",
        "com.minhui.networkcapture",
        "org.sandrop.webscarab"
    )

    /**
     * Initializes all security barriers on application start.
     * Terminates process immediately if a debugger or Frida hook is detected.
     */
    fun protect(context: Context) {
        if (isDebuggerOrHookAttached()) {
            killSilently()
        }
    }

    /**
     * Detects if an active debugger, ptrace tracer, or Frida hook is attached.
     */
    fun isDebuggerOrHookAttached(): Boolean {
        try {
            // 1. Android runtime debugger check
            if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
                return true
            }

            // 2. Linux ptrace / TracerPid check from /proc/self/status
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

            // 3. Frida / Substrate / Xposed in-memory mapping check (/proc/self/maps)
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

    /**
     * Checks if an HTTP/HTTPS proxy or known packet sniffing application is configured.
     */
    fun isSnifferDetected(context: Context): Boolean {
        try {
            // 1. Check system HTTP/HTTPS proxy properties (used by Charles, Burp, Fiddler)
            val httpHost = System.getProperty("http.proxyHost")
            val httpPort = System.getProperty("http.proxyPort")
            val httpsHost = System.getProperty("https.proxyHost")
            val httpsPort = System.getProperty("https.proxyPort")
            if (!httpHost.isNullOrBlank() || !httpPort.isNullOrBlank() ||
                !httpsHost.isNullOrBlank() || !httpsPort.isNullOrBlank()
            ) {
                return true
            }

            // 2. Check for installed sniffing app packages
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

    /**
     * Opens a secure HttpURLConnection that forces Proxy.NO_PROXY.
     * This bypasses any local WiFi proxy / MITM capture tools completely.
     */
    fun openSecureConnection(urlStr: String, context: Context? = null): HttpURLConnection {
        if (isDebuggerOrHookAttached()) {
            killSilently()
        }

        val url = URL(urlStr)
        require(url.protocol.equals("https", ignoreCase = true)) {
            "Cleartext HTTP is strictly forbidden by SecurityShield"
        }

        // Always force NO_PROXY to completely bypass user Wi-Fi proxies (Charles / Burp / Fiddler)
        val conn = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
        conn.setRequestProperty("X-Security-Client", "Shielded-v2")
        return conn
    }

    /**
     * Protected string decoder for endpoints and sensitive keys.
     */
    fun decodeProtectedString(b64: String): String {
        return try {
            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    /**
     * Runtime XOR string deobfuscator for endpoints.
     */
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
