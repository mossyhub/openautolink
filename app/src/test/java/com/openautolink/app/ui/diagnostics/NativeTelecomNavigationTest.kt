package com.openautolink.app.ui.diagnostics

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.carplaylab.telecom.ManagedTelecomExperiment
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [32, 34], qualifiers = "w1000dp-h700dp-mdpi")
class NativeTelecomNavigationTest {
    @get:Rule val compose = createOalComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    @Before fun setup() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app) }
    @After fun cleanup() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app) }

    @Test fun diagnosticsNavigationNeverPromptsOrChangesProductionCallIntent() {
        var launches = 0
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) { launches++ }
        }
        val vm = mockk<DiagnosticsViewModel>()
        every { vm.uiState } returns MutableStateFlow(DiagnosticsUiState())
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides object : androidx.activity.result.ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) { MaterialTheme { DiagnosticsScreen(viewModel = vm) } }
        }
        compose.onNodeWithContentDescription("Native CarPlay calls", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("telecomEnabled").assertDoesNotExist()
        compose.onNodeWithTag("telecomControlsEnabled").assertDoesNotExist()
        compose.onNodeWithTag("telecomRingtoneOnly").assertDoesNotExist()
        compose.onNodeWithTag("telecomInspect").assertIsEnabled()
        compose.onNodeWithTag("telecomCleanup").assertIsEnabled()
        compose.onNodeWithContentDescription("System", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithContentDescription("Native CarPlay calls", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(0, launches)
        assertFalse(ManagedTelecomExperiment.get(app).enabledForProcess)
        compose.onNodeWithText("Call handling, setup and ringtone are in Settings → Audio.").assertExists()
    }
}
