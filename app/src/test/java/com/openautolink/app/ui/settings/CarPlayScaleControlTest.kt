package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32, 34], qualifiers = "w1000dp-h900dp-mdpi")
class CarPlayScaleControlTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun smallerEndpointStagesWiderMillimetersWithoutChangingSavedVideoOrSafeEdges() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            CarPlayDisplaySettingsSection(vm, {})
        } } }
        compose.waitUntil(5000) { vm.carPlaySettingsLoaded.value }
        compose.runOnIdle { vm.updateCarPlayDraft { it.copy(enabled = true, autoScale = false,
            widthPhysicalMm = 225, safeLeft = 100, resolution = "1440p", fps = 45) } }
        val slider = compose.onNodeWithTag("carPlayUiScale").performScrollTo().assertIsEnabled()
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(100f) }
        compose.waitUntil(5000) { vm.carPlaySettings.value.widthPhysicalMm != 225 }
        compose.runOnIdle {
            val next = vm.carPlaySettings.value
            assertEquals(2000, next.widthPhysicalMm)
            assertEquals("1440p", next.resolution); assertEquals(45, next.fps); assertEquals(100, next.safeLeft)
        }
        compose.onNodeWithText("Auto uses display estimates when reliable; otherwise it uses your saved manual calibration.")
            .assertExists()
    }

    @Test fun autoDisablesManualControlWithoutReplacingExistingSavedIntent() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            CarPlayDisplaySettingsSection(vm, {})
        } } }
        compose.waitUntil(5000) { vm.carPlaySettingsLoaded.value }
        compose.runOnIdle { vm.updateCarPlayDraft { it.copy(enabled = true, autoScale = true, widthPhysicalMm = 399) } }
        compose.onNodeWithTag("carPlayUiScale").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(399, vm.carPlaySettings.value.widthPhysicalMm) }
        compose.onNodeWithTag("carPlayAutoScale").performScrollTo().performClick()
        compose.onNodeWithTag("carPlayUiScale").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(399, vm.carPlaySettings.value.widthPhysicalMm) }
    }
}
