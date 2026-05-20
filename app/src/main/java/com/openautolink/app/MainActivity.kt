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

        // Seed SessionManager with the current AAOS day/night state even when
        // no configuration change event has fired yet.
        publishCurrentUiNightMode("onCreate")

        // User tapped "Exit" in the AA app launcher on the phone. Background
        // our app fully — both the MainActivity task and the hidden
        // CarAppActivity task (templates host for cluster). User re-enters by
        // tapping our icon in the AAOS launcher, which starts a fresh session.
        lifecycleScope.launch {
            com.openautolink.app.session.SessionManager.instanceOrNull()
                ?.userExitEvents?.collect { handleUserExit() }
        }
    }

    private fun handleUserExit() {
        Log.i("MainActivity", "User exit from AA launcher — finishing all app tasks")

        // First: tell Templates Host the cluster trip is over. This calls
        // NavigationManager.navigationEnded() on the active cluster Session,
        // causing Templates Host (a separate process) to dismiss its
        // ClusterTurnCardActivity. Without this, the cluster display keeps
        // its last frame visible after our process is gone.
        try {
            com.openautolink.app.cluster.ClusterMainSession.endActiveNavigation()
        } catch (e: Exception) {
            Log.w("MainActivity", "endActiveNavigation() failed: ${e.message}")
        }

        // Tear down the SessionManager. This calls ClusterManager.release()
        // which disables the OalClusterService component — Templates Host
        // then unbinds, our Session.onDestroy runs, and the cluster Activity
        // is fully released.
        try {
            com.openautolink.app.session.SessionManager.instanceOrNull()?.stop()
        } catch (e: Exception) {
            Log.w("MainActivity", "SessionManager.stop() failed: ${e.message}")
        }

        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        // Finish every task this app owns (MainActivity + CarAppActivity live
        // in separate tasks because CarAppActivity has its own taskAffinity).
        // finishAndRemoveTask drops them from recents, so the AAOS launcher
        // reappears with no traces of our app.
        try {
            am.appTasks.forEach { task ->
                try { task.finishAndRemoveTask() } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "appTasks enumeration failed: ${e.message}")
        }
        if (!isFinishing) finishAndRemoveTask()
    }

    override fun onResume() {
        super.onResume()
        com.openautolink.app.diagnostics.DiagnosticLog.i("lifecycle", "MainActivity.onResume")
        publishCurrentUiNightMode("onResume")
        // On AAOS, resume after car sleep leaves TCP sockets dead.
        // SessionManager dedupes against the SCREEN_ON broadcast receiver
        // (which empirically does not fire on this car) and emits a wake
        // event observers can react to.
        com.openautolink.app.session.SessionManager.instanceOrNull()?.onSystemWake()
    }

    override fun onPause() {
        super.onPause()
        com.openautolink.app.diagnostics.DiagnosticLog.i("lifecycle", "MainActivity.onPause")
        // Mirror onResume on the sleep side. Lets the next markWake compute
        // gap from "going idle" rather than "last control message", which
        // matters when streaming keeps lastActiveTimestamp fresh right up
        // until the SoC suspends.
        com.openautolink.app.session.SessionManager.instanceOrNull()?.onActivityPaused()
    }

    override fun onStart() {
        super.onStart()
        com.openautolink.app.diagnostics.DiagnosticLog.i("lifecycle", "MainActivity.onStart")
    }

    override fun onStop() {
        super.onStop()
        com.openautolink.app.diagnostics.DiagnosticLog.i("lifecycle", "MainActivity.onStop")
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        com.openautolink.app.diagnostics.DiagnosticLog.i(
            "lifecycle",
            "MainActivity.onTopResumedActivityChanged: top=$isTopResumedActivity"
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        com.openautolink.app.diagnostics.DiagnosticLog.i(
            "lifecycle",
            "MainActivity.onWindowFocusChanged: focus=$hasFocus"
        )
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val nightMask = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val nightStr = when (nightMask) {
            android.content.res.Configuration.UI_MODE_NIGHT_YES -> "YES"
            android.content.res.Configuration.UI_MODE_NIGHT_NO -> "NO"
            android.content.res.Configuration.UI_MODE_NIGHT_UNDEFINED -> "UNDEFINED"
            else -> "0x${Integer.toHexString(nightMask)}"
        }
        com.openautolink.app.diagnostics.DiagnosticLog.i(
            "lifecycle",
            "MainActivity.onConfigurationChanged: nightMode=$nightStr uiMode=0x${Integer.toHexString(newConfig.uiMode)} orient=${newConfig.orientation} ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp"
        )
        if (nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES ||
            nightMask == android.content.res.Configuration.UI_MODE_NIGHT_NO) {
            val isNight = nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES
            com.openautolink.app.session.SessionManager.instanceOrNull()?.onUiNightModeChanged(isNight)
            com.openautolink.app.session.SessionManager.noteUiNightMode(isNight)
        }
    }

    private fun publishCurrentUiNightMode(reason: String) {
        val nightMask = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        if (nightMask != android.content.res.Configuration.UI_MODE_NIGHT_YES &&
            nightMask != android.content.res.Configuration.UI_MODE_NIGHT_NO
        ) {
            return
        }
        val isNight = nightMask == android.content.res.Configuration.UI_MODE_NIGHT_YES
        com.openautolink.app.session.SessionManager.noteUiNightMode(isNight)
        com.openautolink.app.session.SessionManager.instanceOrNull()?.onUiNightModeChanged(isNight)
        com.openautolink.app.diagnostics.DiagnosticLog.i(
            "lifecycle",
            "publishCurrentUiNightMode[$reason]: night=$isNight"
        )
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
