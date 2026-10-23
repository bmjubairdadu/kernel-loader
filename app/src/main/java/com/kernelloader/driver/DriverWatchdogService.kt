package com.kernelloader.driver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DriverWatchdogService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "kloader_watchdog"
        private const val NOTIF_ID = 2001

        fun start(context: Context) {
            try {
                val intent = Intent(context, DriverWatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {}
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, DriverWatchdogService::class.java))
            } catch (_: Exception) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val notif = createNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startLoop()
        return START_STICKY
    }

    private fun startLoop() {
        if (loopJob?.isActive == true) return
        loopJob = serviceScope.launch {
            while (isActive) {
                delay(45_000L)
                try {
                    val isLoaded = DriverRevocationManager.isDriverResident()
                    if (!isLoaded) {

                        stopSelf()
                        break
                    }
                    val (manifest, _) = OtaDriverStore.fetchDetailed()
                    if (manifest != null && manifest.drivers.isNotEmpty()) {
                        val verdict = DriverRevocationManager.evaluate(this@DriverWatchdogService, manifest, isLoaded)
                        if (verdict is DriverRevocationManager.RevocationVerdict.Revoked) {
                            DriverRevocationManager.enforceRevocation(this@DriverWatchdogService, verdict.reason)
                            break
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Driver Security Watchdog",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps driver synced with remote database"
                setShowBadge(false)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Kernel Loader")
            .setContentText("Driver Active")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }
}
