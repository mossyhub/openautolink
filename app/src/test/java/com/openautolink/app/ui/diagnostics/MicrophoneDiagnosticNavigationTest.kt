package com.openautolink.app.ui.diagnostics

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h600dp-mdpi")
class MicrophoneDiagnosticNavigationTest {
    @get:Rule val compose = createOalComposeRule()
    private lateinit var rootView: android.view.View
    @Test fun diagnosticTabReportsZeroSamplesTruthfullyAndCancelsOnExit() {
        val backend = FakeMicrophoneBackend()
        val controller = MicrophoneDiagnosticController(backend, automotive = false, eligibility = { true })
        val vm = mockk<DiagnosticsViewModel>()
        every { vm.uiState } returns MutableStateFlow(DiagnosticsUiState())
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            val view = androidx.compose.ui.platform.LocalView.current
            androidx.compose.runtime.SideEffect { rootView = view.rootView }
            DiagnosticsScreen(viewModel = vm, microphoneController = controller)
        } }
        compose.onNodeWithContentDescription("Microphone", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        savePreview("controls")
        compose.onNodeWithTag("mic-start-CHANNELS").performScrollTo().performClick()
        compose.runOnIdle {
            val shape = MicrophoneDiagnosticShape(6, 24000, 1, 16, 0)
            backend.state = MicrophoneDiagnosticState(phase = MicrophoneDiagnosticPhase.COMPLETE,
                result = MicrophoneDiagnosticResult(MicrophoneDiagnosticComparison.CHANNELS,
                    MicrophoneDiagnosticInventory(MicrophoneDiagnosticMatch.ABSENT, emptyList()),
                    listOf(MicrophoneDiagnosticArm(1, shape, null, false, null, null, null,
                        MicrophoneDiagnosticStatistics(0, 0, emptyList(), null, null, null))), MicrophoneDiagnosticReason.NONE))
            controller.refresh()
        }
        compose.onNodeWithText("No samples captured").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("mic-arm-1-actual").assertTextContains("unknown", substring = true)
        savePreview("zero-samples")
        compose.onNodeWithTag("mic-start-RATE").performScrollTo().performClick()
        compose.onNodeWithContentDescription("System", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, backend.cancellations) }
        compose.onNodeWithContentDescription("Microphone", useUnmergedTree = true).performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("mic-start-RATE").assertIsNotEnabled()
        compose.onNodeWithText("Stopping · waiting for microphone release").assertExists()
    }
    private fun savePreview(name: String) {
        // PixelCopy/forceRedraw has no real compositor in Robolectric; draw the real View tree.
        compose.runOnIdle {
            val image = android.graphics.Bitmap.createBitmap(rootView.width, rootView.height, android.graphics.Bitmap.Config.ARGB_8888)
            rootView.draw(android.graphics.Canvas(image))
            val file = java.io.File("build/reports/microphone-ui/$name.png")
            file.parentFile.mkdirs()
            file.outputStream().use { assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
