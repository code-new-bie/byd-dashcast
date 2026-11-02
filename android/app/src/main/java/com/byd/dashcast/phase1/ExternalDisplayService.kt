package com.byd.dashcast.phase1

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder

/** User-controlled foreground lifetime for application-name discovery; not a projection session. */
class ExternalDisplayService : Service() {
    private var generation = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val enabled = intent?.getBooleanExtra("enabled",false) == true
        val expected = ++generation
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("dashcast-external","应用接入",NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this,91,Intent(this,ProductActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(91,Notification.Builder(this,"dashcast-external").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("应用接入待命").setContentText("等待应用发送画面，一次显示一个应用。")
            .setContentIntent(open).setOngoing(true).build())
        ExternalDisplayAccess.setEnabled(this,enabled) { Handler(mainLooper).post {
            if (generation == expected) { stopSelf() }
        } }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        ExternalDisplayAccess.serviceDestroyed()
        super.onDestroy()
    }
    companion object {
        fun setEnabled(context: Context, enabled: Boolean) {
            context.startForegroundService(Intent(context,ExternalDisplayService::class.java).putExtra("enabled",enabled))
        }
    }
}
