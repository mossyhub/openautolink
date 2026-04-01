package com.openautolink.app

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.ui.navigation.AppNavHost
import com.openautolink.app.ui.projection.ProjectionViewModel
import com.openautolink.app.ui.theme.OpenAutoLinkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Apply saved display mode (sync read — instant from DataStore cache)
        val prefs = AppPreferences.getInstance(this)
        val displayMode = runBlocking { prefs.displayMode.first() }
        applyDisplayMode(displayMode)

        window.attributes.layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        setContent {
            OpenAutoLinkTheme {
                AppNavHost()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = AppPreferences.getInstance(this)
        val displayMode = runBlocking { prefs.displayMode.first() }
        applyDisplayMode(displayMode)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val vm = ViewModelProvider(this)[ProjectionViewModel::class.java]
        if (vm.onKeyEvent(event)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun applyDisplayMode(mode: String) {
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
}
