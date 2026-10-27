package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class SaveChangesSettingsTest {
    @get:Rule val compose = createOalComposeRule()

    private val guidance = "Wireless: reconnects automatically. USB: unplug and reconnect."

    @Test fun saveChangesKeepsOriginalCallbackAndVisibleTransportGuidance() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        var saves = 0
        compose.setContent { MaterialTheme {
            SettingsScreen(viewModel = vm, onSaveAndConnect = { saves++ })
        } }
        compose.waitUntil(5000) { vm.carPlaySettingsLoaded.value }
        compose.onNodeWithTag("saveAndConnectButton").assertTextContains("Save changes")
            .assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onNodeWithText(guidance).assertIsDisplayed()
        compose.onNodeWithText("Save & Reconnect").assertDoesNotExist()
    }

    @Test fun guidanceStaysBesideGlobalSaveButNotOnAutosavedAudio() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { MaterialTheme { SettingsScreen(viewModel = vm) } }
        for (tab in listOf("CONNECTION", "VIDEO", "DISPLAY", "INPUT", "EV", "DIAGNOSTICS")) {
            compose.onNodeWithTag("settingsTab_$tab").performScrollTo().performClick()
            compose.onNodeWithTag("saveAndConnectButton").assertTextContains("Save changes").assertIsDisplayed()
            compose.onNodeWithText(guidance).assertIsDisplayed()
        }
        compose.onNodeWithTag("settingsTab_AUDIO").performScrollTo().performClick()
        compose.onNodeWithText("Save changes").assertDoesNotExist()
        compose.onNodeWithTag("saveAndConnectButton").assertDoesNotExist()
        compose.onNodeWithText(guidance).assertDoesNotExist()
    }
}
