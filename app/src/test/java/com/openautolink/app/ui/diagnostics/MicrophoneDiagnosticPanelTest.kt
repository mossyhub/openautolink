package com.openautolink.app.ui.diagnostics

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32], qualifiers = "w800dp-h600dp-mdpi")
class MicrophoneDiagnosticPanelTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun localCaptureStartsWithoutConsentOrParkAndPermissionLossStillCancels() {
        val backend = FakeMicrophoneBackend()
        var allowed = true
        val controller = MicrophoneDiagnosticController(backend, automotive = true, eligibility = { allowed })
        compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
        compose.onNodeWithTag("mic-consent").assertDoesNotExist()
        compose.onNodeWithTag("mic-stationary").assertDoesNotExist()
        compose.onNodeWithTag("mic-start-SOURCES").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(MicrophoneDiagnosticComparison.SOURCES), backend.starts); allowed = false }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(1, backend.cancellations) }
        compose.onNodeWithText("Stopping · waiting for microphone release").assertExists()
    }

    @Test fun absentOrThrowingEligibilityAndBackendNeverAdmit() {
        val backend = FakeMicrophoneBackend()
        val variants = listOf(
            MicrophoneDiagnosticController(backend),
            MicrophoneDiagnosticController(backend, eligibility = { error("not available") }),
            MicrophoneDiagnosticController(eligibility = { true }),
        )
        compose.runOnIdle {
            variants.forEach {
                it.updateForeground(true)
                it.start(MicrophoneDiagnosticComparison.CHANNELS); it.inspect()
                assertFalse(it.canStart)
            }
            assertTrue(backend.starts.isEmpty()); assertEquals(0, backend.inspections)
        }
    }

    @Test fun eligibilityIsRecheckedAtClickAndDuringCapture() {
        val backend = FakeMicrophoneBackend()
        var allowed = true // deliberately not Compose State: rendered eligibility may be stale
        val controller = MicrophoneDiagnosticController(backend, automotive = false, eligibility = { allowed })
        compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
        compose.onNodeWithTag("mic-start-CHANNELS").performScrollTo().assertIsEnabled()
        compose.runOnIdle { allowed = false }
        compose.onNodeWithTag("mic-start-CHANNELS").performClick()
        compose.runOnIdle { assertTrue(backend.starts.isEmpty()); allowed = true; controller.refresh() }
        compose.onNodeWithTag("mic-start-CHANNELS").performClick()
        compose.runOnIdle { assertEquals(1, backend.starts.size); allowed = false }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(1, backend.cancellations) }
        compose.onNodeWithText("Stopping · waiting for microphone release").assertExists()
    }

    @Test fun backgroundAndDisposalCancelWithoutFalseReleaseOrExtraConsentOnReturn() {
        val backend = FakeMicrophoneBackend()
        val controller = MicrophoneDiagnosticController(backend, automotive = false, eligibility = { true })
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        var visible by androidx.compose.runtime.mutableStateOf(true)
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED }
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                if (visible) MaterialTheme { MicrophoneDiagnosticPanel(controller) }
            }
        }
        compose.onNodeWithTag("mic-start-CHANNELS").performScrollTo().performClick()
        compose.runOnIdle { owner.registry.currentState = androidx.lifecycle.Lifecycle.State.STARTED }
        compose.runOnIdle { assertEquals(1, backend.cancellations) }
        compose.onNodeWithText("Stopping · waiting for microphone release").assertExists()
        compose.runOnIdle {
            owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            backend.state = MicrophoneDiagnosticState(phase = MicrophoneDiagnosticPhase.COMPLETE)
            controller.refresh()
        }
        compose.onNodeWithTag("mic-start-CHANNELS").assertIsEnabled()
        compose.onNodeWithTag("mic-start-CHANNELS").performScrollTo().performClick()
        compose.runOnIdle { visible = false }
        compose.runOnIdle { assertEquals(2, backend.cancellations) }
    }

    @Test fun cancelRetainsBusyUntilReleaseAndAllActionsStayExclusive() {
        val backend = FakeMicrophoneBackend()
        val controller = MicrophoneDiagnosticController(backend, automotive = false, eligibility = { true })
        compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
        compose.onNodeWithTag("mic-start-RATE").performScrollTo().performClick()
        compose.onNodeWithTag("mic-start-CHANNELS").assertIsNotEnabled()
        compose.onNodeWithTag("mic-start-PREFERRED").assertIsNotEnabled()
        compose.onNodeWithTag("mic-inspect").assertIsNotEnabled()
        compose.onNodeWithTag("mic-cancel").performScrollTo().performClick()
        compose.onNodeWithText("Stopping · waiting for microphone release").assertExists()
        compose.onNodeWithTag("mic-cancel").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, backend.cancellations); controller.refresh() }
        compose.onNodeWithTag("mic-start-RATE").assertIsNotEnabled()
        compose.runOnIdle { backend.state = MicrophoneDiagnosticState(phase = MicrophoneDiagnosticPhase.COMPLETE); controller.refresh() }
        compose.onNodeWithTag("mic-start-PREFERRED").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(MicrophoneDiagnosticComparison.RATE, MicrophoneDiagnosticComparison.PREFERRED), backend.starts)
            backend.state = MicrophoneDiagnosticState(phase = MicrophoneDiagnosticPhase.COMPLETE)
            controller.refresh()
        }
        compose.onNodeWithTag("mic-inspect").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, backend.inspections); assertEquals(2, backend.starts.size) }
    }

    @Test fun directRunStartsSelectedComparison() {
        val backend = FakeMicrophoneBackend()
        val controller = MicrophoneDiagnosticController(backend, automotive = false, eligibility = { true })
        compose.setContent { MaterialTheme { MicrophoneDiagnosticPanel(controller) } }
        compose.onNodeWithTag("mic-start-CHANNELS").assertIsEnabled()
        compose.onNodeWithTag("mic-start-CHANNELS").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(MicrophoneDiagnosticComparison.CHANNELS), backend.starts) }
    }
}

internal class FakeMicrophoneBackend : MicrophoneDiagnosticBackend {
    override var state = MicrophoneDiagnosticState()
    val starts = mutableListOf<MicrophoneDiagnosticComparison>()
    val custom = mutableListOf<MicrophoneDiagnosticCustom>()
    override fun startCustom(config: MicrophoneDiagnosticCustom): Boolean {
        custom += config
        if (accept) state = state.copy(phase = MicrophoneDiagnosticPhase.RUNNING)
        return accept
    }
    var inspections = 0
    var cancellations = 0
    var accept = true
    override fun inspect(): Boolean { inspections++; if (accept) state = state.copy(phase = MicrophoneDiagnosticPhase.INSPECTING); return accept }
    override fun start(comparison: MicrophoneDiagnosticComparison): Boolean {
        starts += comparison
        if (accept) state = state.copy(phase = MicrophoneDiagnosticPhase.RUNNING, comparison = comparison)
        return accept
    }
    override fun cancel() { cancellations++ }
}
