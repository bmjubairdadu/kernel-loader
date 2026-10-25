package com.kernelloader

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import kotlin.system.exitProcess

class CleanExitService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            stopSelf()
        } catch (_: Exception) {}
        Process.killProcess(Process.myPid())
        exitProcess(0)
    }
}
