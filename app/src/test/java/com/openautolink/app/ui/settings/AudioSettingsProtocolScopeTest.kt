package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Rule
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
class AudioSettingsProtocolScopeTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun diagnosticsRetainsRepairWithoutDuplicatingProductionCallPreferences() {
        compose.setContent { MaterialTheme { ManagedTelecomPanel() } }
        compose.onNodeWithTag("telecomEnabled").assertDoesNotExist()
        compose.onNodeWithTag("telecomControlsEnabled").assertDoesNotExist()
        compose.onNodeWithTag("telecomRingtoneOnly").assertDoesNotExist()
        compose.onNodeWithTag("telecomInspect").assertExists()
        compose.onNodeWithTag("telecomCleanup").assertExists()
    }

    @Test fun failedAudioWriteIsVisibleInsteadOfReportedSaved() {
        val real = com.openautolink.app.data.AppPreferences.getInstance(RuntimeEnvironment.getApplication())
        val failing = io.mockk.spyk(real)
        io.mockk.coEvery { failing.setMicSource(any()) } throws java.io.IOException("private storage path")
        io.mockk.mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        io.mockk.every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns failing
        try {
            val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
            compose.setContent { MaterialTheme { SettingsScreen(viewModel = vm) } }
            compose.onNodeWithTag("settingsTab_AUDIO").performClick()
            compose.onNodeWithTag("micSource_phone").performScrollTo().performClick()
            compose.onNodeWithText("Audio settings could not be saved. Try again.").assertExists()
            compose.onNodeWithTag("micSource_car").assertIsSelected()
            compose.onNodeWithText("private storage path", substring = true).assertDoesNotExist()
        } finally { io.mockk.unmockkObject(com.openautolink.app.data.AppPreferences.Companion) }
    }

    @Test fun audioSaveNeverCommitsOrDiscardsAnInvalidVideoDraft() {
        val prefs = com.openautolink.app.data.AppPreferences.getInstance(RuntimeEnvironment.getApplication())
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { MaterialTheme { SettingsScreen(viewModel = vm) } }
        compose.waitUntil(5000) { vm.carPlaySettingsLoaded.value }
        val stored = kotlinx.coroutines.runBlocking { prefs.carPlaySettings.first() }
        compose.runOnIdle { vm.updateCarPlayDraft { it.copy(widthDraft = "invalid") } }
        compose.onNodeWithTag("settingsTab_AUDIO").performClick()
        compose.onNodeWithTag("micSource_phone").performScrollTo().performClick()
        // DataStore's edit transform resumes on its caller's Main dispatcher after IO.
        // Drain that actual paused Robolectric looper on each observation, not just before IO.
        compose.waitUntil(5000) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.runBlocking { prefs.micSource.first() } == "phone"
        }
        compose.onNodeWithTag("micSource_phone").assertIsSelected()
        org.junit.Assert.assertEquals("invalid", vm.carPlaySettings.value.widthDraft)
        org.junit.Assert.assertEquals(stored, kotlinx.coroutines.runBlocking { prefs.carPlaySettings.first() })
        compose.onNodeWithTag("settingsTab_VIDEO").performClick()
        compose.onNodeWithTag("saveAndConnectButton").assertExists()
        org.junit.Assert.assertEquals("invalid", vm.carPlaySettings.value.widthDraft)
        kotlinx.coroutines.runBlocking { prefs.setMicSource("car") }
    }

    @Test fun audioAutosavesWithoutReconnectButOtherPagesRetainTheirTransaction() {
        val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { MaterialTheme { SettingsScreen(viewModel = vm) } }
        compose.onNodeWithTag("saveAndConnectButton").assertExists()
        compose.onNodeWithTag("settingsTab_AUDIO").performClick()
        compose.onNodeWithTag("saveAndConnectButton").assertDoesNotExist()
        compose.onNodeWithText("Android Auto", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Assistant microphone").assertExists()
        compose.onAllNodesWithText("CarPlay", useUnmergedTree = true).assertCountEquals(2)
        compose.onNodeWithText("CarPlay calls").performScrollTo().assertExists()
        compose.onNodeWithTag("callHandler_CARPLAY").assertExists()
        compose.onNodeWithTag("settingsTab_VIDEO").performClick()
        compose.onNodeWithTag("saveAndConnectButton").assertExists()
    }
}
