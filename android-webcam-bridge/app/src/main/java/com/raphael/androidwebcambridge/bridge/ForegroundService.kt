package com.raphael.androidwebcambridge.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.drawable.Icon
import com.raphael.androidwebcambridge.MainActivity

// ponytail: foreground service with camera type keeps capture alive when the Activity stops
class ForegroundService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // ask the UI to finish so the ViewModel tears down the camera/stream, then drop the FGS
            sendBroadcast(Intent(ACTION_STOP_STREAM).setPackage(packageName))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Camera streaming", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the camera stream running in the background"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(): Notification {
        val openIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ForegroundService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopAction =
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                "Stop",
                stopIntent,
            ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamCam Pro")
            .setContentText("Camera is streaming in the background")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(openIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .addAction(stopAction)
            .build()
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        const val ACTION_STOP = "com.raphael.androidwebcambridge.action.STOP_STREAM"
        const val ACTION_STOP_STREAM = "com.raphael.androidwebcambridge.action.STOP_STREAM_REQUESTED"
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1
    }
}
