package com.openautolink.app.carplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.openautolink.app.MainActivity

/** Process priority only: protocol/resource ownership stays in the coordinator. */
class CarPlaySessionService : Service() {
    override fun onCreate() {
        super.onCreate()
        CarPlaySessionLeases.Registry.created(this)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CarPlaySessionLeases.Registry.started(this, startId, intent?.getLongExtra("attempt", -1L) ?: -1L,
            intent?.getLongExtra("request", -1L) ?: -1L)
        return START_NOT_STICKY
    }
    internal fun promote(mask: Int = CarPlayForegroundPolicy.BASE) {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "CarPlay connection", NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION, notification(mask), mask)
        check(foregroundServiceType == mask) { "CarPlay foreground type not acknowledged" }
    }
    private fun notification(mask: Int): Notification {
        val openApp = PendingIntent.getActivity(this, NOTIFICATION, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL)
            // Platform small icon: non-adaptive, no companion resource dependency.
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("OpenAutoLink CarPlay")
            .setContentText(if (mask and CarPlayForegroundPolicy.MIC != 0) "CarPlay connected · microphone available" else "CarPlay connection and playback active")
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        // Snapshot only: never initialize or replace the process-stable session for an FGS.
        val token = runCatching { com.openautolink.app.media.OalMediaSessionManager.instanceOrNull()?.getSessionToken()?.token as? android.media.session.MediaSession.Token }.getOrNull()
        if (token != null) builder.setStyle(Notification.MediaStyle().setMediaSession(token))
        return builder.build()
    }
    internal fun refreshNotification() {
        if (foregroundServiceType and CarPlayForegroundPolicy.BASE != CarPlayForegroundPolicy.BASE) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(foregroundServiceType))
    }
    override fun onDestroy() {
        CarPlaySessionLeases.Registry.destroyed(this)
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "oal_carplay_session"
        private const val NOTIFICATION = 160
    }
}
