package com.openautolink.companion.autostart

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.openautolink.companion.CompanionPrefs
import com.openautolink.companion.diagnostics.CompanionLog
import com.openautolink.companion.service.CompanionService

/**
 * Broadcast receiver for Bluetooth ACL connect/disconnect events.
 * When a target BT device connects and auto-start mode is BT,
 * starts the CompanionService to begin TCP advertising.
 */
class AutoStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences(CompanionPrefs.NAME, Context.MODE_PRIVATE)
        val autoStartMode = prefs.getInt(CompanionPrefs.AUTO_START_MODE, 0)
        if (autoStartMode != CompanionPrefs.AUTO_START_BT &&
            autoStartMode != CompanionPrefs.AUTO_START_BT_AND_WIFI) return

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        } ?: return

        val deviceAddress = device.address
        val targetMacs = prefs.getStringSet(CompanionPrefs.AUTO_START_BT_MACS, emptySet())
            ?: emptySet()
        if (!targetMacs.contains(deviceAddress)) return

        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                CompanionLog.i(TAG, "Target BT connected: $deviceAddress")

                // Check if device is fully connected (not just ACL)
                val isFullyConnected = try {
                    val method = device.javaClass.getMethod("isConnected")
                    method.invoke(device) as? Boolean ?: true
                } catch (_: Exception) {
                    true
                }

                if (!isFullyConnected) {
                    CompanionLog.w(TAG, "ACL up but isConnected()=false, skipping")
                    return
                }

                CompanionLog.i(TAG, "Starting CompanionService via BT auto-start")

                // Cancel any pending stop from a previous BT disconnect flap.
                cancelPendingStop()

                val serviceIntent = Intent(context, CompanionService::class.java).apply {
                    action = CompanionService.ACTION_START
                }
                try {
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (e: Exception) {
                    CompanionLog.e(TAG, "Failed to start service: ${e.message}")
                }
            }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                CompanionLog.i(TAG, "Target BT disconnected: $deviceAddress")
                val stopOnDisconnect = prefs.getBoolean(CompanionPrefs.BT_DISCONNECT_STOP, false)
                if (stopOnDisconnect) {
                    scheduleBtStop(context)
                }
            }
        }
    }

    companion object {
        private const val TAG = "OAL_BtAutoStart"

        /** Grace period before honoring BT-disconnect-stop. BT drops immediately
         *  after WiFi handoff on most cars — we must not stop during active AA. */
        private const val BT_STOP_GRACE_MS = 60_000L

        private val mainHandler = Handler(Looper.getMainLooper())
        @Volatile private var pendingBtStop: Runnable? = null

        private fun cancelBtStop() {
            pendingBtStop?.let {
                mainHandler.removeCallbacks(it)
                pendingBtStop = null
                CompanionLog.d(TAG, "BT disconnect stop cancelled (BT reconnected or AA active)")
            }
        }

        private fun scheduleBtStop(context: Context) {
            cancelBtStop()
            val app = context.applicationContext
            val r = Runnable {
                pendingBtStop = null
                if (!CompanionService.isRunning.value) return@Runnable
                // Don't stop if AA is actively connected.
                if (CompanionService.isConnected.value) {
                    CompanionLog.i(TAG, "BT stop grace expired but AA is connected — not stopping")
                    return@Runnable
                }
                CompanionLog.i(TAG, "BT stop grace expired, stopping service")
                val stopIntent = Intent(app, CompanionService::class.java).apply {
                    action = CompanionService.ACTION_STOP
                }
                try { app.startService(stopIntent) } catch (e: Exception) {
                    CompanionLog.e(TAG, "BT stop failed: ${e.message}")
                }
            }
            pendingBtStop = r
            mainHandler.postDelayed(r, BT_STOP_GRACE_MS)
            CompanionLog.i(TAG, "BT disconnect stop armed (${BT_STOP_GRACE_MS / 1000}s grace)")
        }

        /**
         * Cancel any pending BT stop. Call on BT reconnect so a brief BT flap
         * during WiFi handoff doesn't tear down the session.
         */
        fun cancelPendingStop() = cancelBtStop()
    }
}
