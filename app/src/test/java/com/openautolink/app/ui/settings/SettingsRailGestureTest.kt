package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.ui.theme.OpenAutoLinkTheme
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File

/** Physical TCL content geometry, real theme/scale/root and pointer gestures, not ScrollTo. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1000dp-h552dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsRailGestureTest {
    @get:Rule val compose = createOalComposeRule()
    private var root: View? = null
    private var model: SettingsViewModel? = null
    private var density = 0f
    private var osFont = 0f
    private var backs = 0
    private var diagnostics = 0

    private fun mount(font: Float) {
        RuntimeEnvironment.setFontScale(font)
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication() as Application)
        model = vm
        compose.setContent {
            OpenAutoLinkTheme(oalUiScalePercent = 125) {
                Surface(Modifier.fillMaxSize()) {
                    root = LocalView.current.rootView
                    density = LocalDensity.current.density
                    osFont = LocalDensity.current.fontScale
                    SettingsScreen(clusterBindings = null, viewModel = vm,
                        onBack = { backs++ }, onNavigateToDiagnostics = { diagnostics++ })
                }
            }
        }
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); vm.carPlaySettingsLoaded.value && vm.clusterLoaded.value }
        compose.runOnIdle {
            assertEquals(2000, requireNotNull(root).width)
            assertTrue("Physical content height", requireNotNull(root).height in 1104..1105)
            assertEquals(font, osFont, 0.001f)
        }
        val rail = compose.onNodeWithTag("settingsTabRail").fetchSemanticsNode().boundsInRoot
        assertEquals(112f * density * 1.25f, rail.width, 1f)
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        capture("initial")
    }

    private fun capture(stage: String) {
        val dir = File(System.getProperty("settings.grouping.render.dir", "build/settings-rail-gesture")); dir.mkdirs()
        val name = "rail-${density}-${osFont}-$stage-sdk${RuntimeEnvironment.getApiLevel()}"
        compose.runOnIdle {
            val view = requireNotNull(root)
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        File(dir, "$name.txt").writeText(compose.onRoot().printToString())
    }

    private fun reachDiagnostics(font: Float) {
        try {
            mount(font)
            // Fixed bounded swipes in the rail, corresponding to the parent's repeated real drags.
            repeat(8) {
                compose.onNodeWithTag("settingsTabRail").performTouchInput {
                    swipe(Offset(centerX, height * .90f), Offset(centerX, height * .10f), 650)
                }
                compose.waitForIdle()
                compose.onNodeWithContentDescription("Back").assertIsDisplayed()
            }
            capture("after-eight-swipes")
            compose.onNodeWithTag("settingsTab_DIAGNOSTICS").assertIsDisplayed().performTouchInput { click() }
            compose.onNodeWithTag("openDiagnosticsButton").performScrollTo().assertIsDisplayed().performTouchInput { click() }
            compose.runOnIdle { assertEquals("Real Diagnostics callback", 1, diagnostics) }
            compose.onNodeWithContentDescription("Back").performTouchInput { click() }
            compose.runOnIdle { assertEquals("Pinned Back callback", 1, backs) }
            // Return to the top with touch only and check the complete Secondary label and target.
            repeat(8) { compose.onNodeWithTag("settingsTabRail").performTouchInput { swipeDown(durationMillis = 650) } }
            val secondary = compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").assertIsDisplayed()
            val target = secondary.fetchSemanticsNode().boundsInRoot
            assertTrue("48dp target: $target", target.height >= 48f * density * 1.25f)
            val label = compose.onNode(hasText("Secondary displays") and hasAnyAncestor(hasTestTag("settingsTabRail")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val rail = compose.onNodeWithTag("settingsTabRail").fetchSemanticsNode().boundsInRoot
            assertTrue("Complete label stays inside rail with margins: $label / $rail", label.left > rail.left && label.right < rail.right)
            assertTrue("Complete wrapped label: $label", label.height > 15f * density * 1.25f)
            secondary.performTouchInput { click() }
            compose.onNodeWithTag("secondaryInventoryUnavailable").assertIsDisplayed()
            capture("secondary-after-return")
        } finally { model?.viewModelScope?.cancel(); RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun native320DefaultFontDiagnosticsAndBackReachableByGestures() = reachDiagnostics(1f)
    @Test fun native320LargeFontDiagnosticsAndBackReachableByGestures() = reachDiagnostics(1.3f)
    @Test @Config(qualifiers = "w800dp-h442dp-land-400dpi")
    fun density400LargeFontDiagnosticsAndBackReachableByGestures() = reachDiagnostics(1.3f)
}
