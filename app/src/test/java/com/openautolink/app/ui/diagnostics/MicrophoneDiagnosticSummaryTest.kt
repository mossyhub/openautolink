package com.openautolink.app.ui.diagnostics

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h600dp-mdpi")
class MicrophoneDiagnosticSummaryTest {
    @get:Rule val compose = createOalComposeRule()
    @Test fun completedSummaryShowsBoundedNumericEvidenceNotSpeechProof() {
        val shape = MicrophoneDiagnosticShape(7, 24000, 2, 12, 0)
        val level = MicrophoneDiagnosticLevel(4, 3, 32768, 123.5)
        val arm = MicrophoneDiagnosticArm(1, shape, shape, true, true, false, true,
            MicrophoneDiagnosticStatistics(4, 3, listOf(level, level, level), level, level, level),
            client = shape.copy(source = 6), device = shape.copy(source = 1, sampleRate = 48000), routeStable = false)
        val caps = MicrophoneDiagnosticCapabilities(emptyList(), (1..40).toList(), emptyList(), true, false, true, true)
        val backend = FakeMicrophoneBackend().apply {
            state = MicrophoneDiagnosticState(phase = MicrophoneDiagnosticPhase.COMPLETE,
                result = MicrophoneDiagnosticResult(MicrophoneDiagnosticComparison.CHANNELS,
                    MicrophoneDiagnosticInventory(MicrophoneDiagnosticMatch.UNIQUE, List(20) { caps }),
                    List(4) { arm.copy(ordinal = it + 1) }, MicrophoneDiagnosticReason.ROUTE_CHANGED))
        }
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { MicrophoneDiagnosticPanel(MicrophoneDiagnosticController(backend)) } }
        compose.onNodeWithText("Signal is not proof of speech or phone acceptance.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Setter accepted is not proof of the selected route.").assertExists()
        compose.onNodeWithTag("mic-inventory").assertTextContains("UNIQUE", substring = true)
        compose.onNodeWithTag("mic-capabilities-1").assertTextContains("Rates: unconstrained", substring = true)
        compose.onNodeWithTag("mic-capabilities-17").assertDoesNotExist()
        compose.onNodeWithTag("mic-arm-4").assertDoesNotExist()
        compose.onNodeWithTag("mic-arm-1-requested").assertTextContains("24000 Hz", substring = true)
        compose.onNodeWithTag("mic-arm-1-requested").assertTextContains("source 7", substring = true)
        compose.onNodeWithTag("mic-arm-1-actual").assertTextContains("source 7", substring = true)
        compose.onNodeWithTag("mic-arm-1-client").assertTextContains("source 6", substring = true)
        compose.onNodeWithTag("mic-arm-1-device").assertTextContains("source 1", substring = true)
        compose.onNodeWithText("Up to 12 completed numeric comparisons are retained for manual Upload Logs; ordinary upload age/size limits apply.").assertExists()
        compose.onNodeWithTag("mic-arm-1-client").assertTextContains("mask 12", substring = true)
        compose.onNodeWithTag("mic-arm-1-device").assertTextContains("48000 Hz", substring = true)
        compose.onNodeWithTag("mic-arm-1-route").assertTextContains("stable: no", substring = true)
        compose.onNodeWithTag("mic-arm-1-frames").assertTextContains("Frames: 4 · nonzero: 3")
        for (metric in listOf("channel-1", "channel-2", "sum", "difference", "mean")) {
            compose.onNodeWithTag("mic-arm-1-$metric").assertTextContains("peak 32768", substring = true)
        }
        compose.onNodeWithTag("mic-arm-1-channel-3").assertDoesNotExist()
        compose.onNodeWithTag("mic-outcome").assertTextContains("ROUTE_CHANGED", substring = true)
    }
}
