package com.feiyang.smssync

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import android.app.NotificationManager
import android.content.Context

class ForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "sms_sync_channel"
        const val NOTIFY_ID = 1

        // 提供一个静态接口供外部更新统计
        fun updateNotification(context: Context, count: Int) {
            val notification = buildNotification(context, count)
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFY_ID, notification)
        }

        private fun buildNotification(context: Context, count: Int): Notification {
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("短信同步服务运行中")
                .setContentText("已收信 $count 条")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = buildNotification(this, 0)
        startForeground(NOTIFY_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "短信同步服务",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
