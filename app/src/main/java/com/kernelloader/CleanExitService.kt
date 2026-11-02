package com.kernelloader

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import com.topjohnwu.superuser.Shell
import kotlin.system.exitProcess

class CleanExitService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            // Anti-Cheat Hardening: Wipe dmesg ring buffer & clear all staging traces on exit
            Shell.cmd(
                "dmesg -c >/dev/null 2>&1 || dmesg -C 2>/dev/null",
                "rm -f /data/local/tmp/kloader_* /data/local/tmp/Driver.sh /data/local/tmp/*.ko /data/local/tmp/*.sh /data/local/tmp/ui.xml /data/local/tmp/sig* /data/local/tmp/magiskboot 2>/dev/null"
            ).exec()
            stopSelf()
        } catch (_: Exception) {}
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
