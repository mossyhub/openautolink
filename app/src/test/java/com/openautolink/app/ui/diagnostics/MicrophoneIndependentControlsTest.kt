package com.openautolink.app.ui.diagnostics

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32],qualifiers="w800dp-h600dp-mdpi")
class MicrophoneIndependentControlsTest {
    @get:Rule val compose=createOalComposeRule()
    @Test fun independentArmRechecksParkAndSharesLegacyExpiryDisarmWithoutChangingDraft() {
        CarPlayMicrophoneExperiments.disarm()
        var park=true
        var eligible=true
        val controller=MicrophoneDiagnosticController(FakeMicrophoneBackend(),automotive=false,
            eligibility={eligible},freshPark={park})
        try {
            compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
            compose.onNodeWithTag("mic-independent-history-NORMAL").assertExists().assertIsSelected()
            compose.onNodeWithTag("mic-independent-live-VR48_TWO").assertIsSelected()
            compose.onNodeWithTag("mic-independent-arm").assertIsNotEnabled()
            compose.onNodeWithTag("mic-live-consent").performScrollTo().performClick()
            compose.onNodeWithTag("mic-independent-arm").performScrollTo().assertIsEnabled()
            compose.runOnIdle { park=false }
            compose.onNodeWithTag("mic-independent-arm").performClick()
            compose.runOnIdle { assertFalse(CarPlayMicrophoneExperiments.status().armed); park=true;controller.refresh() }
            compose.onNodeWithTag("mic-independent-arm").performClick()
            compose.runOnIdle {
                val snapshot=CarPlayMicrophoneExperiments.snapshot()!!
                assertEquals(CarPlayMicrophoneQualificationHistory.NORMAL,snapshot.independent!!.history)
                assertEquals(CarPlayMicrophoneSpeechCapture.VR48_TWO,snapshot.independent!!.speechCapture)
                assertEquals(CarPlayMicrophoneExperimentProfile(),controller.liveProfile)
                controller.updateForeground(false)
                assertFalse(controller.liveConsent)
                assertTrue(CarPlayMicrophoneExperiments.status().armed)
                controller.updateForeground(true)
            }
            compose.onNodeWithTag("mic-independent-history-MANUAL_VR48_TWO").performScrollTo().performClick()
            compose.onNodeWithTag("mic-independent-live-VR24_MONO").performScrollTo().performClick()
            compose.onNodeWithTag("mic-independent-arm").assertIsNotEnabled()
            compose.onNodeWithTag("mic-live-disarm").performScrollTo().performClick()
            compose.runOnIdle { assertFalse(CarPlayMicrophoneExperiments.status().armed);eligible=false;controller.refresh() }
            compose.onNodeWithTag("mic-independent-arm").assertIsNotEnabled()
        } finally { CarPlayMicrophoneExperiments.disarm() }
    }
}
