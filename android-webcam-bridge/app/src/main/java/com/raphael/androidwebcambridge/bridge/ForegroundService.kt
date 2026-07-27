package com.raphael.androidwebcambridge.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

// ponytail: foreground service prevents OxygenOS from killing the streaming process
class ForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channel = NotificationChannel("stream", "Stream", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        startForeground(1, Notification.Builder(this, "stream")
            .setContentTitle("StreamCam")
            .setContentText("Camera bridge is running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build())
        return START_STICKY
    }
    override fun onBind(intent: Intent?) = null
}
