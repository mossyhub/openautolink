package com.openautolink.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.openautolink.companion.CompanionPrefs
import com.openautolink.companion.MainActivity
import com.openautolink.companion.R
import com.openautolink.companion.diagnostics.CompanionFileLogger
import com.openautolink.companion.diagnostics.CompanionLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that manages the TCP advertising lifecycle.
 * Listens on port 5277 for the car app over the shared WiFi (Car Hotspot
 * or Phone Hotspot), registers an mDNS service for discovery, and bridges
 * incoming connections through AaProxy to the local Android Auto service.
 */
class CompanionService : Service(), TcpAdvertiser.StateListener {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var tcpAdvertiser: TcpAdvertiser? = null
    private var carWifiManager: com.openautolink.companion.wifi.CarWifiManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null
    private var fileLogger: CompanionFileLogger? = null
    private var fileLogIdleTimeoutJob: kotlinx.coroutines.Job? = null
    /** True once a connection has been observed during the current logging session. */
    @Volatile private var loggingSessionEverConnected: Boolean = false

    override fun onCreate() {
        super.onCreate()
        _instance = this
        CompanionLog.init(com.openautolink.companion.BuildConfig.VERSION_NAME)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))
        // Hold multicast lock for mDNS discovery (some OEMs filter multicast when screen off)
        try {
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as? android.net.wifi.WifiManager
            multicastLock = wm?.createMulticastLock("OalCompanion")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            CompanionLog.d(TAG, "MulticastLock acquired")
        } catch (e: Exception) {
            CompanionLog.w(TAG, "MulticastLock failed: ${e.message}")
        }
        // "Always log" mode: if the maintainer persisted logging on, start it as
        // soon as the service exists — independent of any connection. Resumes on
        // every service start (app open, BT/WiFi auto-start, OS sticky-restart);
        // a cold reboot resumes it once one of those triggers first starts the
        // service (there is no standalone BOOT_COMPLETED launcher).
        maybeStartPersistentLogging()
    }

    /** Start file logging if the persisted "always log" pref is enabled. */
    private fun maybeStartPersistentLogging() {
        val persist = getSharedPreferences(CompanionPrefs.NAME, MODE_PRIVATE)
            .getBoolean(CompanionPrefs.LOG_PERSIST_ENABLED, CompanionPrefs.DEFAULT_LOG_PERSIST_ENABLED)
        if (persist && fileLogger?.isActive?.value != true) {
            CompanionLog.i(TAG, "Always-log mode on — starting file logging")
            startFileLogging()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                CompanionLog.i(TAG, "Stop requested")
                _isRunning.value = false
                _isConnected.value = false
                _statusText.value = "Stopped"
                stopSelf()
            }

            ACTION_START -> {
                // If already connected (AA bridge active), ignore duplicate starts
                // (e.g. BT auto-start firing a few hundred ms after the car already
                // TCP-connected and we fired the AA trigger). Restarting here would
                // tear down the proxy mid-connection.
                if (_isConnected.value) {
                    CompanionLog.i(TAG, "Start requested but already connected — ignoring")
                    return START_STICKY
                }
                CompanionLog.i(TAG, "Start requested")
                _isRunning.value = true
                _isConnected.value = false
                _statusText.value = "Advertising..."
                startTcp()
                if (intent.getBooleanExtra(EXTRA_START_LOGGING, false)) {
                    startFileLogging()
                } else {
                    // Always-log mode: resume logging on any service start.
                    maybeStartPersistentLogging()
                }
            }

            ACTION_PREWARM -> {
                // Pre-warm path: car-presence signal (BT, scripted, etc.) tells
                // us a car connection is imminent. Start the AA pipeline now so
                // by the time the car's TCP arrives ~3–10s later, AA is warm
                // and the bridge lights up instantly. If the service wasn't
                // running yet, start it first.
                if (!_isRunning.value) {
                    CompanionLog.i(TAG, "Pre-warm requested but service not running — starting first")
                    _isRunning.value = true
                    _isConnected.value = false
                    _statusText.value = "Pre-warming..."
                    startTcp()
                } else {
                    CompanionLog.i(TAG, "Pre-warm requested while running")
                }
                tcpAdvertiser?.preWarmAaPipeline()
            }

            ACTION_UPLOAD_LOGS -> {
                CompanionLog.i(TAG, "Upload logs requested")
                uploadLogs()
                return START_STICKY
            }

            else -> {
                // System restart
                if (_isRunning.value) {
                    CompanionLog.i(TAG, "Service restarted by system, resuming")
                    startTcp()
                }
                // Resume always-log mode after an OS-initiated restart, even if
                // the service wasn't "running" before the kill — onCreate also
                // covers the fresh-process case, this covers sticky redelivery.
                maybeStartPersistentLogging()
            }
        }
        return START_STICKY
    }

    private fun startTcp() {
        acquireWakeLock()
        tcpAdvertiser?.stop()

        CompanionLog.i(TAG, "Transport: TCP on port ${TcpAdvertiser.PORT}")
        tcpAdvertiser = TcpAdvertiser(this, this)
        tcpAdvertiser?.start()
        updateNotification("TCP: waiting for car on port ${TcpAdvertiser.PORT}...")
        startCarWifiIfConfigured()
    }

    /**
     * Start [CarWifiManager] if car WiFi entries are configured.
     * Runs in parallel with TcpAdvertiser — purely additive, ensures the
     * phone joins the car's WiFi even when already connected to another network.
     */
    private fun startCarWifiIfConfigured() {
        carWifiManager?.stop()
        val prefs = getSharedPreferences(CompanionPrefs.NAME, MODE_PRIVATE)
        val entries = com.openautolink.companion.wifi.CarWifiEntry.loadAll(prefs)
        if (entries.isEmpty()) {
            CompanionLog.d(TAG, "No car WiFi entries configured, skipping CarWifiManager")
            return
        }
        val mgr = com.openautolink.companion.wifi.CarWifiManager(this)
        carWifiManager = mgr
        mgr.start(entries)
    }

    /**
     * Restart [CarWifiManager] with current prefs. Called when the user
     * adds/changes car WiFi entries while the service is already running.
     */
    fun restartCarWifi() {
        CompanionLog.i(TAG, "Restarting CarWifiManager (entries changed)")
        startCarWifiIfConfigured()
    }

    // ── TcpAdvertiser.StateListener ────────────────────────────────────

    override fun onConnecting() {
        _statusText.value = "Connecting..."
        updateNotification("Connecting to car...")
    }

    override fun onProxyConnected() {
        _isConnected.value = true
        _statusText.value = "Connected"
        acquireWakeLock()
        updateNotification("Connected — AA active")
        // Connection observed: cancel the idle-timeout for any active log session.
        if (_fileLoggingActive.value) {
            loggingSessionEverConnected = true
            fileLogIdleTimeoutJob?.cancel()
            fileLogIdleTimeoutJob = null
            CompanionLog.i(TAG, "File logging: connection established, idle timeout cleared")
        }
    }

    override fun onProxyDisconnected() {
        _isConnected.value = false
        _statusText.value = "Disconnected"
        CompanionLog.i(TAG, "AA proxy disconnected")
        updateNotification("Disconnected — tap Start to retry")
        // Auto-reconnect disabled during development to avoid interfering
        // with the phone's AA session lifecycle.
    }

    override fun onLaunchTimeout() {
        CompanionLog.i(TAG, "Launch timeout — waiting for AA to connect to proxy")
        // Don't restart — the proxy is still listening, AA may connect late.
    }

    // ── Wake lock ──────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        // Release existing lock before acquiring a new one to reset the timeout
        releaseWakeLock()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OalCompanion:WakeLock")
        wakeLock?.acquire(4 * 60 * 60 * 1000L) // 4 hours — covers long drives
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
    }

    // ── Notification ───────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "OpenAutoLink Companion",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(text: String): Notification {
        val stopIntent = Intent(this, CompanionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OpenAutoLink Companion")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)

        // Maintainer-only: show an "Upload Logs" action straight from the
        // notification, but ONLY when the feature is enabled — non-maintainer
        // users never see it.
        val uploadEnabled = getSharedPreferences(CompanionPrefs.NAME, MODE_PRIVATE)
            .getBoolean(CompanionPrefs.LOG_UPLOAD_ENABLED, CompanionPrefs.DEFAULT_LOG_UPLOAD_ENABLED)
        if (uploadEnabled) {
            val upIntent = Intent(this, CompanionService::class.java).apply {
                action = ACTION_UPLOAD_LOGS
            }
            val upPending = PendingIntent.getService(
                this, 1, upIntent, PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.stat_sys_upload, "Upload Logs", upPending)
        }

        return builder.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification(text))
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    override fun onDestroy() {
        _isRunning.value = false
        _isConnected.value = false
        _statusText.value = "Stopped"
        tcpAdvertiser?.stop()
        carWifiManager?.stop()
        stopFileLogging()
        releaseWakeLock()
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
            CompanionLog.d(TAG, "MulticastLock released")
        }
        serviceScope.cancel()
        _instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    // ── File logging ───────────────────────────────────────────────────

    fun startFileLogging() {
        if (fileLogger?.isActive?.value == true) return
        val logger = CompanionFileLogger(this)
        val path = logger.start()
        if (path != null) {
            fileLogger = logger
            CompanionLog.fileLogger = logger
            _fileLoggingActive.value = true
            _fileLoggingPath.value = path
            loggingSessionEverConnected = _isConnected.value
            CompanionLog.i(TAG, "File logging enabled: $path")
            // 10-minute idle timeout: if the AA proxy never connects while
            // logging is active, auto-disable to avoid runaway log files when
            // the user forgets the toggle on. Cleared on first connection.
            // SKIPPED entirely in "always log" mode — the maintainer wants
            // continuous capture regardless of connection state.
            fileLogIdleTimeoutJob?.cancel()
            val persist = getSharedPreferences(CompanionPrefs.NAME, MODE_PRIVATE)
                .getBoolean(CompanionPrefs.LOG_PERSIST_ENABLED, CompanionPrefs.DEFAULT_LOG_PERSIST_ENABLED)
            if (!persist && !loggingSessionEverConnected) {
                fileLogIdleTimeoutJob = serviceScope.launch {
                    delay(LOG_IDLE_TIMEOUT_MS)
                    if (_fileLoggingActive.value && !loggingSessionEverConnected) {
                        CompanionLog.w(TAG,
                            "File logging idle timeout (${LOG_IDLE_TIMEOUT_MS / 60_000} min, no connection) — auto-disabling")
                        stopFileLogging()
                    }
                }
            }
        }
    }

    fun stopFileLogging() {
        fileLogIdleTimeoutJob?.cancel()
        fileLogIdleTimeoutJob = null
        loggingSessionEverConnected = false
        CompanionLog.fileLogger = null
        fileLogger?.stop()
        fileLogger = null
        _fileLoggingActive.value = false
        _fileLoggingPath.value = null
    }

    // ── Log upload (maintainer) ────────────────────────────────────────

    /**
     * Zip recent logs and POST them to the configured endpoint. No-ops (with a
     * log line) if the feature is disabled or unconfigured. Updates
     * [uploadState] ("uploading" -> "success"/"error" -> "idle") and reflects
     * progress in the notification text. Runs on the service IO scope.
     */
    private fun uploadLogs() {
        if (_uploadState.value == "uploading") return
        val prefs = getSharedPreferences(CompanionPrefs.NAME, MODE_PRIVATE)
        if (!prefs.getBoolean(
                CompanionPrefs.LOG_UPLOAD_ENABLED,
                CompanionPrefs.DEFAULT_LOG_UPLOAD_ENABLED)) {
            CompanionLog.w(TAG, "Upload requested but feature disabled")
            return
        }
        val url = prefs.getString(CompanionPrefs.LOG_UPLOAD_URL, "") ?: ""
        val token = prefs.getString(CompanionPrefs.LOG_UPLOAD_TOKEN, "") ?: ""
        val label = prefs.getString(CompanionPrefs.LOG_UPLOAD_DEVICE_LABEL, "") ?: ""
        if (url.isBlank() || token.isBlank()) {
            CompanionLog.w(TAG, "Upload requested but URL/token not configured")
            _uploadState.value = "error"
            return
        }
        _uploadState.value = "uploading"
        updateNotification("Uploading logs…")
        serviceScope.launch {
            val result = com.openautolink.companion.diagnostics.LogUploader(this@CompanionService)
                .upload(url, token, label)
            val ok = result is com.openautolink.companion.diagnostics.UploadResult.Success
            _uploadState.value = if (ok) "success" else "error"
            updateNotification(if (ok) "Logs uploaded ✓" else "Log upload failed")
            delay(4000)
            _uploadState.value = "idle"
            updateNotification(
                if (_isConnected.value) "Connected — AA active" else "Advertising…")
        }
    }

    companion object {
        private const val TAG = "OAL_Service"
        private const val CHANNEL_ID = "oal_companion_channel"
        private const val NOTIFICATION_ID = 1
        /** Idle timeout for file logging when no AA connection is ever made. */
        private const val LOG_IDLE_TIMEOUT_MS = 10L * 60L * 1000L

        const val ACTION_START = "com.openautolink.companion.ACTION_START"
        const val ACTION_STOP = "com.openautolink.companion.ACTION_STOP"
        const val ACTION_PREWARM = "com.openautolink.companion.ACTION_PREWARM"
        /** Upload recent logs to the maintainer endpoint (off-by-default feature). */
        const val ACTION_UPLOAD_LOGS = "com.openautolink.companion.ACTION_UPLOAD_LOGS"
        /** Optional extra on ACTION_START: also start file logging once the service is up. */
        const val EXTRA_START_LOGGING = "com.openautolink.companion.EXTRA_START_LOGGING"

        /** Observable service state for UI. */
        private val _isRunning = MutableStateFlow(false)
        val isRunning: kotlinx.coroutines.flow.StateFlow<Boolean> = _isRunning

        private val _isConnected = MutableStateFlow(false)
        val isConnected: kotlinx.coroutines.flow.StateFlow<Boolean> = _isConnected

        private val _statusText = MutableStateFlow("Stopped")
        val statusText: kotlinx.coroutines.flow.StateFlow<String> = _statusText

        private val _fileLoggingActive = MutableStateFlow(false)
        val fileLoggingActive: kotlinx.coroutines.flow.StateFlow<Boolean> = _fileLoggingActive

        private val _fileLoggingPath = MutableStateFlow<String?>(null)
        val fileLoggingPath: kotlinx.coroutines.flow.StateFlow<String?> = _fileLoggingPath

        // Maintainer log-upload state: "idle" | "uploading" | "success" | "error".
        private val _uploadState = MutableStateFlow("idle")
        val uploadState: kotlinx.coroutines.flow.StateFlow<String> = _uploadState

        @Volatile
        private var _instance: CompanionService? = null
        val instance: CompanionService? get() = _instance
    }
}
