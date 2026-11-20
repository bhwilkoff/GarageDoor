package org.humanshaped.garageagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/** Keeps the agent's process alive; Android restarts a sticky service after a crash. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("agent", "Garage Agent", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, "agent")
            .setContentTitle("Watching the garage door for Apple Home")
            .setContentText("Tap to see the door and how everything is doing.")
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE))
            .setSmallIcon(R.drawable.ic_garage)
            .setGroup("garage_agent")
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Agent.start(this)
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, KeepAliveService::class.java)) }
                .onFailure { Agent.log("Could not start keep-alive service: ${it.message}") }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            KeepAliveService.start(context)
        }
    }
}
