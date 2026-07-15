package com.openautolink.companion.wifi

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.openautolink.companion.diagnostics.CompanionLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages WiFi connectivity to the car's hotspot using a two-layer approach:
 *
 * 1. **WifiNetworkSuggestion**: Persistent background preference — Android
 *    auto-connects on future scans without any UI.
 *
 * 2. **WifiNetworkSpecifier** + **requestNetwork**: Forces an *immediate*
 *    connection attempt so the car can TCP-connect within seconds of BT
 *    pairing, without waiting for the OS to action the suggestion.
 *    The callback is **unregistered immediately after onAvailable** so the
 *    "Searching for device / Stay connected?" system dialog dismisses as soon
 *    as the network is found. If onUnavailable fires (SSID not in range yet),
 *    we retry after a short delay.
 *
 * Retry strategy: each requestNetwork() scans ~30s. On failure, wait 5s
 * and retry, up to [MAX_ATTEMPTS] times.
 */
class CarWifiManager(private val context: Context) {

    sealed class State {
        data object Idle : State()
        data class Scanning(val attempt: Int, val maxAttempts: Int) : State()
        data class Connected(val ssid: String) : State()
        data class Failed(val reason: String) : State()
    }

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var entries: List<CarWifiEntry> = emptyList()
    private var currentCallback: ConnectivityManager.NetworkCallback? = null
    private var attempt = 0
    private var running = false
    private var retryRunnable: Runnable? = null
    private var activeSuggestions: List<WifiNetworkSuggestion> = emptyList()

    fun start(carWifiEntries: List<CarWifiEntry>) {
        if (carWifiEntries.isEmpty()) {
            CompanionLog.w(TAG, "No car WiFi entries configured, skipping")
            return
        }
        entries = carWifiEntries
        running = true
        attempt = 0
        CompanionLog.i(TAG, "Starting car WiFi manager for ${entries.size} SSID(s)")
        registerSuggestions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tryConnect()
    }

    fun stop() {
        running = false
        cancelRetry()
        releaseCallback()
        removeSuggestions()
        _state.value = State.Idle
        CompanionLog.i(TAG, "Stopped")
    }

    private fun registerSuggestions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val suggestions = entries.map { entry ->
                val builder = WifiNetworkSuggestion.Builder()
                    .setSsid(entry.ssid)
                    .setWpa2Passphrase(entry.password)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    builder.setIsInitialAutojoinEnabled(true)
                }
                builder.build()
            }
            if (activeSuggestions.isNotEmpty()) {
                wifiManager.removeNetworkSuggestions(activeSuggestions)
            }
            val status = wifiManager.addNetworkSuggestions(suggestions)
            activeSuggestions = if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                CompanionLog.i(TAG, "Registered ${suggestions.size} WiFi suggestion(s) for auto-connect")
                suggestions
            } else {
                CompanionLog.w(TAG, "WifiNetworkSuggestion registration failed (status=$status) — specifier-only mode")
                emptyList()
            }
        } catch (e: Exception) {
            CompanionLog.w(TAG, "WifiNetworkSuggestion error: ${e.message}")
        }
    }

    private fun removeSuggestions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (activeSuggestions.isEmpty()) return
        try { wifiManager.removeNetworkSuggestions(activeSuggestions) } catch (_: Exception) {}
        CompanionLog.d(TAG, "Removed ${activeSuggestions.size} WiFi suggestion(s)")
        activeSuggestions = emptyList()
    }

    @SuppressLint("NewApi")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun tryConnect() {
        if (!running) return
        if (attempt >= MAX_ATTEMPTS) {
            val msg = "Gave up after $MAX_ATTEMPTS attempts"
            CompanionLog.w(TAG, msg)
            _state.value = State.Failed(msg)
            return
        }

        attempt++
        _state.value = State.Scanning(attempt, MAX_ATTEMPTS)

        val entry = entries.first()
        CompanionLog.i(TAG, "Attempt $attempt/$MAX_ATTEMPTS: requesting \"${entry.ssid}\"")

        releaseCallback()

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(entry.ssid)
            .setWpa2Passphrase(entry.password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!running) return
                CompanionLog.i(TAG, "Connected to \"${entry.ssid}\" on attempt $attempt")
                _state.value = State.Connected(entry.ssid)
                attempt = 0
                // Keep the callback registered — unregistering here would tear down
                // the secondary WiFi network, removing the phone's IP on the car's
                // subnet and making the car unable to reach our server ports.
                // The callback is released in stop().
            }

            override fun onUnavailable() {
                if (!running) return
                CompanionLog.w(TAG, "Attempt $attempt failed (SSID not in range yet)")
                scheduleRetry()
            }

            override fun onLost(network: Network) {
                if (!running) return
                CompanionLog.w(TAG, "Car WiFi \"${entry.ssid}\" lost")
                _state.value = State.Scanning(attempt, MAX_ATTEMPTS)
                scheduleRetry(LOST_RETRY_DELAY_MS)
            }
        }

        currentCallback = callback
        connectivityManager.requestNetwork(request, callback)
    }

    private fun scheduleRetry(delayMs: Long = RETRY_DELAY_MS) {
        cancelRetry()
        val r = Runnable { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tryConnect() }
        retryRunnable = r
        handler.postDelayed(r, delayMs)
    }

    private fun cancelRetry() {
        retryRunnable?.let { handler.removeCallbacks(it) }
        retryRunnable = null
    }

    private fun releaseCallback() {
        currentCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        currentCallback = null
    }

    companion object {
        private const val TAG = "OAL_CarWifi"
        private const val MAX_ATTEMPTS = 12
        private const val RETRY_DELAY_MS = 5_000L
        private const val LOST_RETRY_DELAY_MS = 2_000L
    }
}

/**
 * A car WiFi network entry (SSID + password).
 */
data class CarWifiEntry(val ssid: String, val password: String) {
    /** Serialize to prefs format: "ssid\tpassword" */
    fun toPrefsString(): String = "$ssid\t$password"

    companion object {
        /** Parse from prefs format: "ssid\tpassword" */
        fun fromPrefsString(s: String): CarWifiEntry? {
            val parts = s.split('\t', limit = 2)
            if (parts.size != 2 || parts[0].isBlank()) return null
            return CarWifiEntry(parts[0], parts[1])
        }

        /** Load all entries from SharedPreferences */
        fun loadAll(prefs: android.content.SharedPreferences): List<CarWifiEntry> {
            val raw = prefs.getStringSet(
                com.openautolink.companion.CompanionPrefs.CAR_WIFI_ENTRIES,
                emptySet()
            ) ?: emptySet()
            return raw.mapNotNull { fromPrefsString(it) }
        }

        /** Save all entries to SharedPreferences */
        fun saveAll(prefs: android.content.SharedPreferences, entries: List<CarWifiEntry>) {
            prefs.edit()
                .putStringSet(
                    com.openautolink.companion.CompanionPrefs.CAR_WIFI_ENTRIES,
                    entries.map { it.toPrefsString() }.toSet()
                )
                .apply()
        }
    }
}
