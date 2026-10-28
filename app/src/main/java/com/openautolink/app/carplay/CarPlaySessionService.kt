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
    private val tokenHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var observedToken: android.media.session.MediaSession.Token? = null
    private var tokenWatchScheduled=false
    private var protectionActive=false
    private val tokenWatch=object : Runnable {
        override fun run() {
            tokenWatchScheduled=false
            if (!protectionActive) return
            val token=currentToken()
            if (token != null && token != observedToken) refreshNotification()
            // Process token is stable once published; never initialize its manager to observe it.
            if (token == null) scheduleTokenWatch()
        }
    }
    private fun currentToken() = runCatching {
        com.openautolink.app.media.OalMediaSessionManager.instanceOrNull()?.getSessionToken()?.token as? android.media.session.MediaSession.Token
    }.getOrNull()
    private fun scheduleTokenWatch() {
        if (!tokenWatchScheduled) {
            tokenWatchScheduled=tokenHandler.postDelayed(tokenWatch,1_000)
        }
    }
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
        val stage=if (mask and CarPlayForegroundPolicy.MIC != 0) CarPlayForegroundStage.MICROPHONE else CarPlayForegroundStage.BASE
        try {
            startForeground(NOTIFICATION, notification(mask), mask)
            check(foregroundServiceType == mask) { "CarPlay foreground type not acknowledged" }
            protectionActive=true
            CarPlayForegroundReceipts.emit(stage,CarPlayForegroundStatus.ACKNOWLEDGED,foregroundServiceType)
            if (observedToken == null) scheduleTokenWatch()
        } catch (failure: Exception) {
            CarPlayForegroundReceipts.emit(stage,CarPlayForegroundStatus.DENIED,runCatching { foregroundServiceType }.getOrNull())
            throw failure
        }
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
        val token = currentToken()
        observedToken=token
        if (token != null) builder.setStyle(Notification.MediaStyle().setMediaSession(token))
        return builder.build()
    }
    internal fun refreshNotification() {
        if (!protectionActive) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(foregroundServiceType))
        CarPlayForegroundReceipts.emit(CarPlayForegroundStage.NOTIFICATION,CarPlayForegroundStatus.ACKNOWLEDGED,foregroundServiceType)
    }
    internal fun removeProtection() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        protectionActive=false
        tokenHandler.removeCallbacks(tokenWatch)
        tokenWatchScheduled=false
    }
    override fun onDestroy() {
        protectionActive=false
        tokenHandler.removeCallbacks(tokenWatch)
        tokenWatchScheduled=false
        super.onDestroy()
        CarPlaySessionLeases.Registry.destroyed(this)
    }
    companion object {
        private const val CHANNEL = "oal_carplay_session"
        private const val NOTIFICATION = 160
    }
}
