package com.kernelloader

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import kotlin.system.exitProcess

/**
 * CleanExitService monitors the application task state.
 * When the user clears the app from background / recents (swipes it away),
 * onTaskRemoved() is triggered by the Android framework.
 * This service ensures the entire process is terminated cleanly and immediately,
 * leaving zero residual background memory, cached threads, or processes.
 */
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
