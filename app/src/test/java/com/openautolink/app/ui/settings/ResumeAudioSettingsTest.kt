package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.media.OalMediaSessionManager
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34], application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ResumeAudioSettingsTest {
    @get:Rule val compose=createOalComposeRule()
    @After fun cleanup() { OalMediaSessionManager.instanceOrNull()?.release() }
    @Test fun audioUsesOrdinaryPlayWithoutSeparateResumeButtonOrVacantExplanation() {
        val vm=SettingsViewModel(RuntimeEnvironment.getApplication())
        compose.setContent { MaterialTheme { SettingsScreen(viewModel=vm) } }
        compose.onNodeWithTag("settingsTab_AUDIO").performClick()
        compose.onNodeWithTag("resumeCarPlayAudio").assertDoesNotExist()
        compose.onNodeWithText("Resume audio").assertDoesNotExist()
        compose.onNodeWithText("After returning from another source, resume music and navigation audio.").assertDoesNotExist()
        compose.onNodeWithText("Calls and Siri").performScrollTo().assertExists()
    }
}
