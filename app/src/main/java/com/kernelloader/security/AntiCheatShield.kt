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

            // Auto-heal critical system nodes in case a previous buggy run or tool wiped them
            Shell.cmd(
                "if [ ! -c /dev/binder ]; then " +
                        "BM=\$(grep -w binder /proc/misc 2>/dev/null | awk '{print \$1}'); " +
                        "[ -n \"\$BM\" ] && mknod -m 666 /dev/binder c 10 \$BM 2>/dev/null; " +
                        "restorecon -F /dev/binder 2>/dev/null; fi",
                "if [ ! -c /dev/ashmem ]; then " +
                        "AM=\$(grep -w ashmem /proc/misc 2>/dev/null | awk '{print \$1}'); " +
                        "[ -n \"\$AM\" ] && mknod -m 666 /dev/ashmem c 10 \$AM 2>/dev/null; " +
                        "restorecon -F /dev/ashmem 2>/dev/null; fi",
                "if [ ! -c /dev/random ]; then " +
                        "mknod -m 666 /dev/random c 1 8 2>/dev/null; " +
                        "restorecon -F /dev/random 2>/dev/null; fi"
            ).exec()

            runCatching {
                context.cacheDir.listFiles()?.forEach { file ->
                    val n = file.name.lowercase()
                    if (n.endsWith(".ko") || n.endsWith(".sh") || n.startsWith("kloader_") || n.contains("driver")) {
                        file.delete()
                    }
                }
            }
            runCatching {
                com.kernelloader.driver.OtaDriverStore.cacheDir(context).listFiles()?.forEach { file ->
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }
}
