package com.openautolink.app.ui.diagnostics

import android.Manifest
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [32, 34], qualifiers = "w1000dp-h700dp-mdpi")
class NativeTelecomNavigationTest {
    @get:Rule val compose = createOalComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val experiment get() = ManagedTelecomExperiment.get(app)
    @Before fun setup() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app); shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS) }
    @After fun cleanup() { com.openautolink.app.ui.testing.resetNativeTelecomTestProcess(app) }
    @Test fun leavingNativeTabInvalidatesPendingPermissionCallback() {
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        var requestCode = -1
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) {
                assertEquals(Manifest.permission.READ_PHONE_NUMBERS, input); requestCode = code
            }
        }
        val vm = mockk<DiagnosticsViewModel>()
        every { vm.uiState } returns MutableStateFlow(DiagnosticsUiState())
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides object : androidx.activity.result.ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) { MaterialTheme { DiagnosticsScreen(viewModel = vm) } }
        }
        assertEquals(-1, requestCode)
        compose.onNodeWithContentDescription("Native CarPlay calls", useUnmergedTree = true).performScrollTo().performClick()
        assertEquals(-1, requestCode)
        compose.onNodeWithTag("telecomEnabled").performScrollTo().performClick()
        assertTrue(requestCode >= 0)
        compose.onNodeWithContentDescription("System", useUnmergedTree = true).performScrollTo().performClick()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_NUMBERS)
        compose.runOnIdle { registry.dispatchResult(requestCode, true) }
        assertFalse(experiment.enabledForProcess)
        compose.onNodeWithContentDescription("Native CarPlay calls", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("telecomEnabled").performScrollTo().assertIsOff()
        assertFalse(experiment.enabledForProcess)
    }
    @Test fun dashboardNativeCallsTestSectionIsReachableAndSelectionSurvivesTabChange() {
        val vm = mockk<DiagnosticsViewModel>()
        every { vm.uiState } returns MutableStateFlow(DiagnosticsUiState())
        compose.setContent { MaterialTheme { DiagnosticsScreen(viewModel = vm) } }
        compose.onNodeWithTag("telecomEnabled").assertDoesNotExist()
        compose.onNodeWithContentDescription("Native CarPlay calls", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("telecomEnabled").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(10000) { experiment.enabledForProcess }
        compose.onNodeWithContentDescription("System", useUnmergedTree = true).performScrollTo().performClick()
        assertTrue(experiment.enabledForProcess)
        compose.onNodeWithContentDescription("Native CarPlay calls", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("telecomEnabled").performScrollTo().assertIsOn()
        compose.onNodeWithTag("telecomInspect").performScrollTo().assertIsEnabled()
        compose.onNodeWithContentDescription("Microphone", useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithTag("telecomEnabled").assertDoesNotExist()
    }
}
