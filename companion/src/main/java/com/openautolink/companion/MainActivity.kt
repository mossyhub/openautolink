package com.openautolink.companion

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.openautolink.companion.service.CompanionService
import com.openautolink.companion.ui.MainScreen
import com.openautolink.companion.ui.theme.OalCompanionTheme

class MainActivity : ComponentActivity() {

    private val requiredPermissions = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* permissions granted/denied — UI will reflect state */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMissingPermissions()
        checkBatteryOptimization()
        handleIntent(intent)

        val prefs = getSharedPreferences(CompanionPrefs.NAME, MODE_PRIVATE)
        val autoStartMode = prefs.getInt(CompanionPrefs.AUTO_START_MODE, 0)
        if (autoStartMode == CompanionPrefs.AUTO_START_APP_OPEN && !CompanionService.isRunning.value) {
            startCompanionService()
        }

        setContent {
            OalCompanionTheme {
                MainScreen(
                    onStart = { startCompanionService() },
                    onStop = { stopCompanionService() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "oalcompanion") return
        when (uri.host) {
            "start" -> startCompanionService()
            "stop" -> stopCompanionService()
        }
    }

    private fun startCompanionService() {
        val serviceIntent = Intent(this, CompanionService::class.java).apply {
            action = CompanionService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopCompanionService() {
        val serviceIntent = Intent(this, CompanionService::class.java).apply {
            action = CompanionService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Battery Optimization")
                .setMessage(
                    "OpenAutoLink Companion needs to run in the background to maintain " +
                    "the connection to your car. Please disable battery optimization for this app."
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(
                        Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                        }
                    )
                }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

/** SharedPreferences keys for the companion app. */
object CompanionPrefs {
    const val NAME = "OalCompanionPrefs"

    const val AUTO_START_MODE = "auto_start_mode"
    const val AUTO_START_BT_MACS = "auto_start_bt_macs"
    const val BT_DISCONNECT_STOP = "bt_disconnect_stop"
    const val AUTO_START_WIFI_SSIDS = "auto_start_wifi_ssids"
    const val WIFI_DISCONNECT_STOP = "wifi_disconnect_stop"

    const val AUTO_START_OFF = 0
    const val AUTO_START_BT = 1
    const val AUTO_START_WIFI = 2
    const val AUTO_START_APP_OPEN = 3
    const val AUTO_START_BT_AND_WIFI = 4

    // Car Hotspot WiFi credentials for WifiNetworkSpecifier auto-connect.
    // Stored as Set<String>, each entry formatted as "ssid\tpassword".
    const val CAR_WIFI_ENTRIES = "car_wifi_entries"

    // Connection mode — distinguishes which side hosts the WiFi network.
    // PHONE_HOTSPOT: phone is the AP, car is the client (current default).
    // CAR_HOTSPOT:   car AP is the network, phone is one of N clients.
    //                Multi-phone discovery via mDNS happens in this mode.
    const val CONNECTION_MODE = "connection_mode"
    const val MODE_PHONE_HOTSPOT = "phone_hotspot"
    const val MODE_CAR_HOTSPOT = "car_hotspot"
    const val DEFAULT_CONNECTION_MODE = MODE_CAR_HOTSPOT

    // Phone identity — published in mDNS TXT records so the car can tell
    // phones apart and remember a preferred default.
    const val PHONE_ID = "phone_id"             // stable UUID, generated on first launch
    const val PHONE_FRIENDLY_NAME = "phone_friendly_name"  // user-editable, defaults to Build.MODEL

    // Maintainer-only diagnostic log upload. OFF by default. The user supplies
    // their own endpoint URL + bearer token + device label; the in-app button
    // and the notification action both zip recent logs and POST them. Never
    // imposed on other users — everything is gated behind LOG_UPLOAD_ENABLED.
    const val LOG_UPLOAD_ENABLED = "log_upload_enabled"
    const val LOG_UPLOAD_URL = "log_upload_url"
    const val LOG_UPLOAD_TOKEN = "log_upload_token"
    const val LOG_UPLOAD_DEVICE_LABEL = "log_upload_device_label"
    const val DEFAULT_LOG_UPLOAD_ENABLED = false

    // Maintainer-only "always log" mode. When enabled, file logging is started
    // automatically whenever the service comes up (boot, app open, auto-start,
    // OS-restart) and the 10-minute no-connection idle timeout is skipped, so
    // logging stays on continuously across drives/disconnects until the user
    // turns it off. Unlike the transient fileLoggingActive switch (which is
    // service-scoped, connection-gated, and not persisted), this flag IS
    // persisted in SharedPreferences and survives restarts.
    const val LOG_PERSIST_ENABLED = "log_persist_enabled"
    const val DEFAULT_LOG_PERSIST_ENABLED = false

    // Maintainer-only "verbose logcat capture" mode. OFF by default. When OFF,
    // logcat capture is filtered to OAL/WiFi tags (`*:S`), which is compact but
    // CANNOT see why Google's AA app (gearhead) drops a session. When ON, the
    // capture is UNFILTERED (all tags, all processes) so gearhead's own teardown
    // reason is recorded — used to diagnose the mid-drive freeze root cause. The
    // log is larger, so this is a deliberate maintainer opt-in, not a default.
    const val LOG_VERBOSE_CAPTURE = "log_verbose_capture"
    const val DEFAULT_LOG_VERBOSE_CAPTURE = false

    /**
     * Returns the persistent phone UUID, generating one on first call.
     */
    fun getOrCreatePhoneId(prefs: android.content.SharedPreferences): String {
        val existing = prefs.getString(PHONE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val fresh = java.util.UUID.randomUUID().toString()
        prefs.edit().putString(PHONE_ID, fresh).apply()
        return fresh
    }

    /**
     * Returns the user-friendly phone name. Defaults to the user-set device
     * name (Settings → About phone → Device name, also used as the BT name)
     * on first read, falling back to Build.MODEL if unavailable.
     */
    fun getFriendlyName(prefs: android.content.SharedPreferences): String {
        val existing = prefs.getString(PHONE_FRIENDLY_NAME, null)
        if (!existing.isNullOrBlank()) return existing
        return resolveDefaultDeviceName() ?: (android.os.Build.MODEL ?: "Phone")
    }

    private fun resolveDefaultDeviceName(): String? {
        // Best-effort, no exceptions: try multiple known sources for the
        // user-customized device name.
        return try {
            // No Context here — caller's prefs already came from one. Use the
            // application context indirectly via reflection on ActivityThread,
            // which is reliable inside Android process. Falls back to null on
            // any failure.
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context ?: return null
            val cr = app.contentResolver
            val candidates = listOf(
                android.provider.Settings.Global.DEVICE_NAME,
                "device_name",
                "bluetooth_name",
            )
            for (key in candidates) {
                val v = runCatching { android.provider.Settings.Global.getString(cr, key) }.getOrNull()
                if (!v.isNullOrBlank()) return v
                val s = runCatching { android.provider.Settings.Secure.getString(cr, key) }.getOrNull()
                if (!s.isNullOrBlank()) return s
            }
            null
        } catch (_: Throwable) {
            null
        }
    }
}
