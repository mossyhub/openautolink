package com.openautolink.app.ui.diagnostics

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import com.shilapi.xcertplay.media.CarPlayMicrophoneExperiments
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h600dp-mdpi")
class MicrophoneDiagnosticConsoleTest {
    @get:Rule val compose = createOalComposeRule()
    @Test fun liveToggleAppliesWithoutParkOrConsentAndRetainsUntilOffAcrossPageExit() {
        CarPlayMicrophoneExperiments.disarm()
        val controller = MicrophoneDiagnosticController(FakeMicrophoneBackend(), eligibility = { true })
        try {
            compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
            compose.onNodeWithTag("mic-live-consent").assertDoesNotExist()
            compose.onNodeWithTag("mic-live-enabled").performScrollTo().assertIsOff().performClick()
            compose.runOnIdle {
                assertTrue(CarPlayMicrophoneExperiments.status().armed)
                controller.updateForeground(false); controller.updateForeground(true)
            }
            compose.onNodeWithTag("mic-live-enabled").assertIsOn()
            compose.onNodeWithTag("mic-live-status").assertTextContains("until Off or app restart", substring = true)
            compose.onNodeWithTag("mic-live-enabled").performScrollTo().performClick()
            compose.runOnIdle { assertFalse(CarPlayMicrophoneExperiments.status().armed) }
        } finally { CarPlayMicrophoneExperiments.disarm() }
    }
    @Test fun unavailableEligibilityWithholdsEnableAndBusyBlocksProfileEdits() {
        val backend = FakeMicrophoneBackend()
        var allowed = false
        val controller = MicrophoneDiagnosticController(backend, eligibility = { allowed })
        compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
        compose.onNodeWithTag("mic-live-enabled").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { allowed = true; controller.refresh() }
        compose.onNodeWithTag("mic-custom-source-1").performScrollTo().performClick()
        compose.onNodeWithTag("mic-custom-run").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(MicrophoneDiagnosticPhase.RUNNING, controller.state.phase) }
        for (tag in listOf("mic-live-source-MIC", "mic-live-arm", "mic-live-enabled", "mic-custom-source-6"))
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        compose.onNodeWithTag("mic-cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, backend.cancellations) }
    }
    @Test fun customSelectionsReachBackendUnchangedAndBusyBlocksEdits() {
        var state = MicrophoneDiagnosticState()
        val configs = mutableListOf<Any>()
        val backend = java.lang.reflect.Proxy.newProxyInstance(MicrophoneDiagnosticBackend::class.java.classLoader,
            arrayOf(MicrophoneDiagnosticBackend::class.java)) { _, method, args ->
            when (method.name) {
                "getState" -> state
                "startCustom" -> { configs.add(args!![0]); state = state.copy(phase = MicrophoneDiagnosticPhase.RUNNING); true }
                "inspect", "start" -> false
                "cancel" -> null
                else -> null
            }
        } as MicrophoneDiagnosticBackend
        val controller = MicrophoneDiagnosticController(backend, automotive = true, eligibility = { true })
        compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
        compose.onNodeWithTag("mic-custom-run").assertExists().assertIsEnabled()
        for (tag in listOf("mic-custom-source-1", "mic-custom-rate-16000", "mic-custom-channels-2", "mic-custom-input-named")) {
            compose.onNodeWithTag(tag).performScrollTo().performClick()
        }
        compose.onNodeWithTag("mic-custom-run").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, configs.size)
            val config = configs.single()
            assertEquals(1, config.javaClass.getMethod("getSource").invoke(config))
            assertEquals(16000, config.javaClass.getMethod("getSampleRate").invoke(config))
            assertEquals(2, config.javaClass.getMethod("getChannelCount").invoke(config))
            assertEquals(true, config.javaClass.getMethod("getPreferredNamedInput").invoke(config))
        }
        for (tag in listOf("mic-custom-source-6", "mic-custom-rate-48000", "mic-custom-channels-1", "mic-custom-input-default", "mic-custom-run")) {
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
        for (tag in listOf("mic-live-source-MIC", "mic-live-rate-HZ_48000", "mic-live-channels-TWO",
            "mic-live-selection-SECOND", "mic-live-codec-OPUS", "mic-live-arm", "mic-live-reset")) {
            compose.onNodeWithTag(tag).assertIsNotEnabled()
        }
        compose.onNodeWithTag("mic-start-SOURCES").assertIsNotEnabled()
        compose.onNodeWithTag("mic-cancel").performScrollTo().assertIsEnabled()
    }
}
