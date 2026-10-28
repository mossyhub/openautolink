package com.openautolink.app.ui.settings

import android.app.Application
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32, 34], qualifiers = "w1000dp-h1000dp-mdpi")
class CarPlayDockComposeTest {
    @get:Rule val compose = createOalComposeRule()
    @org.junit.Before fun recordActualSdk() { println("PACKET4_COMPOSE_SDK=${RuntimeEnvironment.getApiLevel()}") }
    private fun dock(settings: CarPlaySettings): String = settings.javaClass.getMethod("getDockPosition").invoke(settings) as String

    @Test fun realDisplayCallbacksOnlyStageUntilExistingGlobalSaveAndKeepOtherDraftEdits() {
        val prefs = AppPreferences.getInstance(RuntimeEnvironment.getApplication())
        runBlocking { prefs.updateCarPlaySettings({ CarPlaySettings() }) {} }
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        var saves = 0
        try {
            compose.setContent { MaterialTheme {
                val scope = rememberCoroutineScope()
                SettingsScreen(viewModel = vm, onSaveAndConnect = {
                    scope.launch { if (vm.commitCarPlaySettings()) saves++ }
                })
            } }
            compose.waitUntil(5000) { vm.carPlaySettingsLoaded.value }
            compose.runOnIdle { vm.updateCarPlayDraft { it.copy(widthPhysicalMm = 225) } }
            compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
            compose.onNodeWithText("CarPlay status bar").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("carPlayDock_bottom").performScrollTo().assertIsEnabled().performClick().assertIsSelected()
            compose.onNodeWithText("Applies on the next CarPlay connection; does not change icon or text size.")
                .performScrollTo().assertIsDisplayed()
            assertEquals("automatic", dock(runBlocking { prefs.carPlaySettings.first() }))
            assertEquals("bottom", dock(vm.carPlaySettings.value))
            compose.onNodeWithTag("settingsTab_VIDEO").performScrollTo().performClick()
            compose.onNodeWithTag("carPlayDock_bottom").assertDoesNotExist()
            compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
            compose.onNodeWithTag("carPlayDock_driver_side").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("saveAndConnectButton").assertTextContains("Save changes").performClick()
            compose.waitUntil(5000) {
                shadowOf(Looper.getMainLooper()).idle()
                saves == 1 && dock(runBlocking { prefs.carPlaySettings.first() }) == "driver_side"
            }
            assertEquals(225, runBlocking { prefs.carPlaySettings.first() }.widthPhysicalMm)
            compose.onNodeWithTag("carPlayDock_driver_side").performScrollTo().assertIsSelected()
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun controlsWaitForLoadedSettingsAndAreDisabledThroughoutSaving() {
        val settings = MutableStateFlow(CarPlaySettings())
        val saving = MutableStateFlow(false)
        val loaded = MutableStateFlow(false)
        val vm = mockk<SettingsViewModel>(relaxed = true)
        every { vm.carPlaySettings } returns settings
        every { vm.carPlaySettingsSaving } returns saving
        every { vm.carPlaySettingsLoaded } returns loaded
        every { vm.carPlaySettingsStatus } returns MutableStateFlow("")
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) { CarPlayDisplaySettingsSection(vm, {}) }
        } }
        for (id in listOf("automatic", "bottom", "driver_side"))
            compose.onNodeWithTag("carPlayDock_$id").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { loaded.value = true }
        compose.onNodeWithTag("carPlayDock_bottom").assertIsEnabled()
        compose.runOnIdle { saving.value = true }
        for (id in listOf("automatic", "bottom", "driver_side"))
            compose.onNodeWithTag("carPlayDock_$id").performScrollTo().assertIsNotEnabled()
        verify(exactly = 0) { vm.updateCarPlayDraft(any()) }
        compose.runOnIdle { saving.value = false }
        compose.onNodeWithTag("carPlayDock_bottom").assertIsEnabled().performClick()
        val edit = slot<(CarPlaySettings) -> CarPlaySettings>()
        verify(exactly = 1) { vm.updateCarPlayDraft(capture(edit)) }
        assertEquals("bottom", dock(edit.captured(settings.value)))
        verify(exactly = 0) { vm.updateCarPlayEnabled(any()) }
    }
}
