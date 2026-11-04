package com.openautolink.companion.autostart

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.openautolink.companion.CompanionPrefs
import com.openautolink.companion.diagnostics.CompanionLog
import com.openautolink.companion.service.CompanionService

/**
 * JobService for WiFi-based auto-start. Runs periodically to check if
 * the phone has connected to a target WiFi SSID. Also serves as a
 * reboot-survival mechanism alongside the ConnectivityManager callback.
 */
class WifiJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        CompanionLog.i(TAG, "WiFi job triggered by OS")
        Thread {
            try {
                checkWifiAndStart(this)
            } catch (e: Exception) {
                CompanionLog.e(TAG, "WiFi check failed: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val TAG = "OAL_WifiJob"
        private const val JOB_ID = 2001

        /** Delay before honoring `stop on disconnect`, to ride out
         *  brief WiFi flaps when the car/AP hiccups. */
        private const val STOP_GRACE_MS = 30_000L

        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var pendingStop: Runnable? = null

        private fun cancelPendingStop() {
            pendingStop?.let {
                mainHandler.removeCallbacks(it)
                pendingStop = null
                CompanionLog.d(TAG, "Pending WiFi stop cancelled (SSID came back)")
            }
        }

        @Suppress("DEPRECATION")
        private fun schedulePendingStop(context: Context) {
            cancelPendingStop()
            val app = context.applicationContext
            val r = Runnable {
                pendingStop = null
                val prefs = app.getSharedPreferences(CompanionPrefs.NAME, Context.MODE_PRIVATE)
                if (prefs.getInt(CompanionPrefs.AUTO_START_MODE, 0) != CompanionPrefs.AUTO_START_WIFI) return@Runnable
                if (!prefs.getBoolean(CompanionPrefs.WIFI_DISCONNECT_STOP, false)) return@Runnable
                if (!CompanionService.isRunning.value) return@Runnable
                // Never stop while AA is actively connected.
                if (CompanionService.isConnected.value) {
                    CompanionLog.i(TAG, "Grace expired but AA is connected — not stopping")
                    return@Runnable
                }

                val targetSsids = prefs.getStringSet(CompanionPrefs.AUTO_START_WIFI_SSIDS, emptySet())
                    ?: emptySet()
                val wm = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val raw = wm.connectionInfo?.ssid?.replace("\"", "") ?: ""
                val current = if (raw == "<unknown ssid>") "" else raw
                val matched = current.isNotBlank() &&
                    targetSsids.any { it.equals(current, ignoreCase = true) }
                if (matched) {
                    CompanionLog.i(TAG, "Grace expired but SSID '$current' matched again; not stopping")
                    return@Runnable
                }
                CompanionLog.i(TAG, "Grace expired, off target SSID — stopping service")
                val stopIntent = Intent(app, CompanionService::class.java).apply {
                    action = CompanionService.ACTION_STOP
                }
                try {
                    app.startService(stopIntent)
                } catch (e: Exception) {
                    CompanionLog.e(TAG, "Stop failed: ${e.message}")
                }
            }
            pendingStop = r
            mainHandler.postDelayed(r, STOP_GRACE_MS)
            CompanionLog.i(TAG, "Stop on disconnect armed (${STOP_GRACE_MS / 1000}s grace)")
        }

        /**
         * Re-check WiFi state. Used on each broadcast from [WifiReceiver]
         * (PendingIntent NetworkCallback) and from the periodic JobScheduler
         * tick. Idempotent — calling repeatedly is safe.
         *
         * Decisions:
         *   - SSID matches a target → start the foreground service (no-op if
         *     it's already running). Cancels any pending stop.
         *   - SSID does NOT match (or no WiFi) AND user has enabled
         *     [CompanionPrefs.WIFI_DISCONNECT_STOP] AND the service is
         *     running → schedule a 30s deferred stop (rides out brief flaps).
         */
        @Suppress("DEPRECATION")
        fun checkWifiAndStart(context: Context): Boolean {
            val prefs = context.getSharedPreferences(CompanionPrefs.NAME, Context.MODE_PRIVATE)
            val mode = prefs.getInt(CompanionPrefs.AUTO_START_MODE, 0)

            // BT+WiFi mode: check if any configured car WiFi SSID is visible
            // in scan results (phone doesn't need to be connected to it).
            if (mode == CompanionPrefs.AUTO_START_BT_AND_WIFI) {
                return checkCarWifiScanAndStart(context, prefs)
            }

            if (mode != CompanionPrefs.AUTO_START_WIFI) return false

            val targetSsids = prefs.getStringSet(CompanionPrefs.AUTO_START_WIFI_SSIDS, emptySet())
                ?: emptySet()
            if (targetSsids.isEmpty()) return false

            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ssid = wifiManager.connectionInfo?.ssid?.replace("\"", "") ?: ""
            val currentSsid = if (ssid == "<unknown ssid>") "" else ssid
            val matched = currentSsid.isNotBlank() &&
                targetSsids.any { it.equals(currentSsid, ignoreCase = true) }

            CompanionLog.d(TAG, "Current SSID='$currentSsid', targets=$targetSsids, match=$matched")

            if (matched) {
                cancelPendingStop()
                CompanionLog.i(TAG, "SSID match — ensuring service is running")
                val serviceIntent = Intent(context, CompanionService::class.java).apply {
                    action = CompanionService.ACTION_START
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    return true
                } catch (e: Exception) {
                    CompanionLog.e(TAG, "Start failed: ${e.message}")
                }
                return false
            }

            val stopOnDisconnect = prefs.getBoolean(CompanionPrefs.WIFI_DISCONNECT_STOP, false)
            if (stopOnDisconnect && CompanionService.isRunning.value) {
                schedulePendingStop(context)
                return true
            }
            return false
        }

        /**
         * For BT+WiFi mode: check if any configured car WiFi SSID is visible
         * in the latest scan results. Unlike the normal WiFi trigger, the phone
         * does NOT need to be connected — just seeing the SSID in scan results
         * is enough to start the service (CarWifiManager will handle connecting).
         */
        @Suppress("DEPRECATION")
        private fun checkCarWifiScanAndStart(
            context: Context,
            prefs: android.content.SharedPreferences,
        ): Boolean {
            if (CompanionService.isRunning.value) return false // already running

            val entries = com.openautolink.companion.wifi.CarWifiEntry.loadAll(prefs)
            if (entries.isEmpty()) return false

            val carSsids = entries.map { it.ssid.lowercase() }.toSet()

            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val scanResults = try {
                wifiManager.scanResults ?: emptyList()
            } catch (_: SecurityException) {
                // Revoked scan permission is an unavailable scan, not an auto-start signal.
                emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            val visible = scanResults.any { result ->
                val ssid = result.SSID?.takeIf { it.isNotBlank() } ?: return@any false
                carSsids.contains(ssid.lowercase())
            }

            if (visible) {
                CompanionLog.i(TAG, "Car WiFi SSID detected in scan — starting service")
                val serviceIntent = Intent(context, CompanionService::class.java).apply {
                    action = CompanionService.ACTION_START
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                    return true
                } catch (e: Exception) {
                    CompanionLog.e(TAG, "Start failed: ${e.message}")
                }
            }
            return false
        }

        fun schedule(context: Context) {
            CompanionLog.i(TAG, "Scheduling WiFi auto-start")

            // Modern: ConnectivityManager callback via PendingIntent. Fires
            // on every WiFi network change (connect, disconnect, validate).
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val intent = Intent(context, WifiReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
            try {
                cm.registerNetworkCallback(request, pending)
                CompanionLog.i(TAG, "NetworkCallback registered")
            } catch (e: Exception) {
                CompanionLog.e(TAG, "NetworkCallback registration failed: ${e.message}")
            }

            // Fallback: periodic JobScheduler. Android forces a 15-min
            // minimum on periodic jobs, so this is the floor. The
            // NetworkCallback above handles real-time events; this only
            // rescues us from missed callbacks (process killed, reboot).
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val jobInfo = JobInfo.Builder(JOB_ID, ComponentName(context, WifiJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .setPeriodic(15 * 60 * 1000L)
                .build()
            scheduler.schedule(jobInfo)
        }

        fun cancel(context: Context) {
            CompanionLog.i(TAG, "Cancelling WiFi auto-start triggers")
            cancelPendingStop()
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.cancel(JOB_ID)

            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val intent = Intent(context, WifiReceiver::class.java)
            val flags = PendingIntent.FLAG_NO_CREATE or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
            if (pending != null) {
                cm.unregisterNetworkCallback(pending)
            }
        }
    }
}
