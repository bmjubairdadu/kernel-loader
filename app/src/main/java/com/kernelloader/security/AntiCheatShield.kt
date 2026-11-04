package com.kernelloader.security

import android.content.Context
import android.util.Log
import com.topjohnwu.superuser.Shell

object AntiCheatShield {

    private const val TAG = "AntiCheatShield"

    fun enforceDenyList() {
        try {
            Log.d(TAG, "Enforcing Magisk DenyList for Free Fire...")
            Shell.cmd(
                "magisk --denylist enable 2>/dev/null",
                "magisk --denylist add com.dts.freefiremax com.dts.freefiremax 2>/dev/null",
                "magisk --denylist add com.dts.freefireth com.dts.freefireth 2>/dev/null"
            ).exec()
        } catch (_: Exception) {}
    }

    fun deepCleanDevice(context: Context, activeNode: String = "") {
        try {
            Log.d(TAG, "Performing deep device clean of staging & test files...")
            Shell.cmd(
                "rm -rf /data/local/tmp/*.ko* /data/local/tmp/*.sh /data/local/tmp/*.log " +
                        "/data/local/tmp/*.bak /data/local/tmp/*.so /data/local/tmp/v3* " +
                        "/data/local/tmp/*daemon* /data/local/tmp/*probe* /data/local/tmp/*test* " +
                        "/data/local/tmp/*target* /data/local/tmp/kloader_* /data/local/tmp/ui*.xml " +
                        "/data/local/tmp/scr*.png /data/local/tmp/a*.png /data/local/tmp/g*.png " +
                        "/data/local/tmp/w*.png /data/local/tmp/s*.png /data/local/tmp/live_screen* " +
                        "/data/local/tmp/ff_screen* /data/local/tmp/dump.xml /data/local/tmp/report.txt 2>/dev/null",
                "dmesg -c >/dev/null 2>&1 || dmesg -C 2>/dev/null",
                "logcat -c >/dev/null 2>&1"
            ).exec()

            if (activeNode.isNotBlank()) {
                val cleanNode = activeNode.removePrefix("/dev/").trim()
                Shell.cmd(
                    "for node in /dev/[A-Z][A-Z][A-Z][A-Z][A-Z][A-Z]; do " +
                            "if [ \"\$node\" != \"/dev/$cleanNode\" ] && [ -c \"\$node\" ]; then " +
                            "rm -f \"\$node\"; fi; done 2>/dev/null"
                ).exec()
            }

            runCatching {
                context.cacheDir.listFiles()?.forEach { file ->
                    val n = file.name.lowercase()
                    if (n.endsWith(".ko") || n.endsWith(".sh") || n.startsWith("kloader_") || n.contains("driver")) {
                        file.delete()
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
