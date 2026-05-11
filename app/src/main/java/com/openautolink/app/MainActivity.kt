package com.openautolink.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.ui.navigation.AppNavHost
import com.openautolink.app.ui.projection.ProjectionViewModel
import com.openautolink.app.ui.theme.OpenAutoLinkTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    private val requiredPermissions = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_SCAN,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Log.w("MainActivity", "Permissions denied: $denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Init version-prefixed logging (reads versionName from PackageInfo)
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            com.openautolink.app.diagnostics.OalLog.init(versionName)
        } catch (_: Exception) { /* OalLog falls back to [app ?] */ }

        // Request runtime permissions on first launch
        requestMissingPermissions()

        // Apply saved display mode (sync read for initial state)
        val prefs = AppPreferences.getInstance(this)
        val displayMode = runBlocking { prefs.displayMode.first() }
        applyDisplayMode(displayMode)

        // Observe display mode changes reactively — applies immediately when
        // the user changes the setting, no app restart needed.
        // Re-sends app_hello so the bridge can recompute pixel_aspect for the
        // new usable display area (system bars visible vs hidden).
        lifecycleScope.launch {
            prefs.displayMode.collectLatest { mode ->
                applyDisplayMode(mode)
            }
        }

        // Keep screen on during projection
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Hardcode landscape — AA is always landscape on car head units.
        // The manifest sets sensorLandscape, this is belt-and-suspenders.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        setContent {
            OpenAutoLinkTheme {
                AppNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // On AAOS, resume after car sleep leaves TCP sockets dead.
        // SessionManager dedupes against the SCREEN_ON broadcast receiver
        // (which empirically does not fire on this car) and emits a wake
        // event observers can react to.
        com.openautolink.app.session.SessionManager.instanceOrNull()?.onSystemWake()
    }

    override fun onPause() {
        super.onPause()
        // Mirror onResume on the sleep side. Lets the next markWake compute
        // gap from "going idle" rather than "last control message", which
        // matters when streaming keeps lastActiveTimestamp fresh right up
        // until the SoC suspends.
        com.openautolink.app.session.SessionManager.instanceOrNull()?.onActivityPaused()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Log every KeyEvent for voice button investigation
        if (event.action == KeyEvent.ACTION_DOWN) {
            com.openautolink.app.diagnostics.DiagnosticLog.i(
                "input",
                "dispatchKeyEvent: keycode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)}) action=DOWN source=0x${Integer.toHexString(event.source)}"
            )
        }
        // Settings "Assign key" capture takes priority — steering wheel keys
        // don't reach Compose focus inside an AlertDialog, so we intercept here.
        if (com.openautolink.app.input.KeyCaptureBus.handle(event)) return true
        val vm = ViewModelProvider(this)[ProjectionViewModel::class.java]
        if (vm.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        com.openautolink.app.diagnostics.DiagnosticLog.i(
            "input",
            "onKeyDown: keycode=$keyCode (${KeyEvent.keyCodeToString(keyCode)}) source=0x${Integer.toHexString(event.source)}"
        )
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i("MainActivity", "onNewIntent: action=${intent.action} extras=${intent.extras?.keySet()}")
        com.openautolink.app.diagnostics.DiagnosticLog.i(
            "input",
            "onNewIntent: action=${intent.action} extras=${intent.extras?.keySet()}"
        )
    }

    private fun applyDisplayMode(mode: String) {
        Log.i("MainActivity", "applyDisplayMode: $mode")
        val controller = WindowCompat.getInsetsController(window, window.decorView)

        when (mode) {
            "system_ui_visible" -> {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            "status_bar_hidden" -> {
                controller.hide(WindowInsetsCompat.Type.statusBars())
                controller.show(WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            "nav_bar_hidden" -> {
                controller.show(WindowInsetsCompat.Type.statusBars())
                controller.hide(WindowInsetsCompat.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            "fullscreen_immersive" -> {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private fun requestMissingPermissions() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Log.i("MainActivity", "Requesting permissions: $missing")
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
