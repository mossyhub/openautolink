package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures

import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.platform.LocalView
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w900dp-h480dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class ControlAreaSettingsUiTest {
    @get:Rule val compose = createOalComposeRule()

    private var root: View? = null
    private fun capture(name: String) {
        compose.runOnIdle {
            val view = requireNotNull(root)
            val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
            val dir = File("build/control-area-renders").apply { mkdirs() }
            File(dir, "$name-sdk${RuntimeEnvironment.getApiLevel()}.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            image.recycle()
        }
    }

    @Test fun editorDoneReturnsToDisplayWithoutCallingSave() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        var saves = 0
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { Surface {
                root = LocalView.current.rootView
                SettingsScreen(clusterBindings = null, viewModel = vm, onSaveAndConnect = { saves++ })
            } }
        }
        compose.waitUntil(5000) { vm.carPlaySettingsLoaded.value }
        compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
        compose.onNodeWithText("Display coverage").assertExists()
        compose.onNodeWithTag("editSafeAreaButton").performScrollTo().assertIsDisplayed()
        capture("display-control-area")
        compose.onNodeWithTag("editSafeAreaButton").performClick()
        capture("main-control-editor")
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.onNodeWithTag("editSafeAreaButton").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { org.junit.Assert.assertEquals(0, saves) }
    }

    @Test fun unchangedEditorPreservesLegacyValuesOutsideSliderRange() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface {
            root = LocalView.current.rootView
            SettingsScreen(clusterBindings = null, viewModel = vm)
        } } }
        compose.waitUntil(5000) { vm.carPlaySettingsLoaded.value }
        compose.runOnIdle { vm.updateSafeAreaInsets(-7, 1001, 2003, 4007) }
        compose.waitUntil(5000) { vm.settingsState.value.safeAreaRight == 4007 }
        compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
        compose.onNodeWithTag("editSafeAreaButton").performScrollTo().performClick()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle {
            val state = vm.settingsState.value
            org.junit.Assert.assertEquals(listOf(-7, 1001, 2003, 4007),
                listOf(state.safeAreaTop, state.safeAreaBottom, state.safeAreaLeft, state.safeAreaRight))
        }
    }

    @Test fun retainedEditorShieldsUnderlyingProjectionControls() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        var underlyingTaps = 0
        var underlyingDrags = 0
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { underlyingTaps++ }
                    .pointerInput(Unit) { detectDragGestures { change, _ ->
                        change.consume(); underlyingDrags++
                    } })
                SettingsScreen(clusterBindings = null, viewModel = vm)
            }
        } }
        compose.waitUntil(5000) { vm.carPlaySettingsLoaded.value }
        compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
        compose.onNodeWithTag("editSafeAreaButton").performScrollTo().performClick()
        compose.onNodeWithTag("safeAreaEditorScreen").performTouchInput {
            click(center)
            swipe(center, center + androidx.compose.ui.geometry.Offset(80f, 20f))
        }
        compose.runOnIdle {
            org.junit.Assert.assertEquals("Hidden projection control received a tap", 0, underlyingTaps)
            org.junit.Assert.assertEquals("Hidden projection control received a drag", 0, underlyingDrags)
        }
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settingsTab_DISPLAY").assertIsSelected()
    }

    @Test fun enlargedTextKeepsEditorDoneAndBackReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        try {
            val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
            compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface {
                root = LocalView.current.rootView
                SettingsScreen(clusterBindings = null, viewModel = vm)
            } } }
            compose.waitUntil(5000) { vm.carPlaySettingsLoaded.value }
            compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
            compose.onNodeWithTag("editSafeAreaButton").performScrollTo().performClick()
            compose.onNodeWithText("Cancel").assertIsDisplayed()
            compose.onNodeWithText("Clear").assertIsDisplayed()
            compose.onNodeWithText("Done").assertIsDisplayed()
            capture("editor-large-text")
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithTag("settingsTab_DISPLAY").assertIsSelected()
        } finally { RuntimeEnvironment.setFontScale(1f) }
    }

    @Test fun bluetoothOverrideIsAvailableOnlyInAdvancedConnection() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface {
            root = LocalView.current.rootView
            SettingsScreen(clusterBindings = null, viewModel = vm)
        } } }
        compose.onNodeWithText("Android Auto connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("btMacOverride").assertDoesNotExist()
        capture("connection-normal")
        compose.onNodeWithTag("advancedConnection").performScrollTo().performClick()
        compose.onNodeWithTag("btMacOverride").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("advancedConnection").performScrollTo().performClick()
        compose.onNodeWithTag("btMacOverride").assertDoesNotExist()
    }
}
