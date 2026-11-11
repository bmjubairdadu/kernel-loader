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

    fun protect(context: Context) {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            killSilently()
        }
        Thread {
            if (isDebuggerOrHookAttached()) {
                killSilently()
            }
        }.start()
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
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
