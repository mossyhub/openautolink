package com.openautolink.app.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.data.CarPlaySettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real settings screen and LocalEchoTextField, not a visibility model. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk = [32], application = android.app.Application::class, qualifiers = "w1280dp-h960dp-land")
class LogUploadTokenVisibilityTest {
    @get:Rule val compose = createOalComposeRule()
    private val state = MutableStateFlow(SettingsUiState(directTransport = "usb", logUploadEnabled = true,
        logUploadToken = "synthetic-upload-value"))
    private val viewModel = mockk<SettingsViewModel>(relaxed = true).also { vm ->
        every { vm.settingsState } returns state
        every { vm.carPlaySettingsSaving } returns MutableStateFlow(false)
        every { vm.clusterSaving } returns MutableStateFlow(false)
        every { vm.clusterLoaded } returns MutableStateFlow(true)
        every { vm.clusterSelection } returns MutableStateFlow(com.openautolink.app.cluster.ClusterOutputSelection())
        every { vm.carPlaySettingsLoaded } returns MutableStateFlow(true)
        every { vm.carPlayApplyError } returns MutableStateFlow("")
        every { vm.carPlaySettings } returns MutableStateFlow(CarPlaySettings())
        every { vm.carPlayPhones } returns MutableStateFlow(emptyList())
        every { vm.carPlayIdentityReady } returns MutableStateFlow(false)
        every { vm.carPlayPasswordSaved } returns MutableStateFlow(false)
        every { vm.carPlaySettingsStatus } returns MutableStateFlow("")
        every { vm.carPlayCredentialImportBusy } returns MutableStateFlow(false)
        every { vm.carPlayCredentialImportStatus } returns MutableStateFlow("")
    }

    private fun openDiagnostics() {
        compose.setContent { MaterialTheme { SettingsScreen(clusterBindings = clusterSettingsFixture(), viewModel = viewModel) } }
        selectDiagnostics()
    }

    private fun selectDiagnostics() {
        compose.onNodeWithTag("settingsTab_DIAGNOSTICS").performScrollTo().performClick()
        tokenField().performScrollTo()
    }

    private fun tokenField() = compose.onNode(hasSetTextAction() and hasText("Upload token"))
    private fun assertMasked() = tokenField().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    private fun assertVisible(value: String) {
        tokenField().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Password))
        tokenField().assertTextContains(value)
    }

    @Test fun eyeRevealsAndHidesTokenWithoutEditingIt() {
        openDiagnostics()
        assertMasked()
        compose.onNodeWithContentDescription("Show upload token").performClick()
        assertVisible("synthetic-upload-value")
        compose.onNodeWithContentDescription("Hide upload token").performClick()
        assertMasked()
        compose.onNodeWithContentDescription("Show upload token").assertExists()
        verify(exactly = 0) { viewModel.updateLogUploadToken(any()) }
    }

    @Test fun screenReopenRemasksRetainedToken() {
        val open = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (open.value) SettingsScreen(clusterBindings = clusterSettingsFixture(), viewModel = viewModel) } }
        selectDiagnostics()
        compose.onNodeWithContentDescription("Show upload token").performClick()
        assertVisible("synthetic-upload-value")
        compose.runOnIdle { open.value = false }
        compose.waitForIdle()
        compose.runOnIdle { open.value = true }
        selectDiagnostics()
        assertMasked()
        compose.onNodeWithContentDescription("Show upload token").assertExists()
        verify(exactly = 0) { viewModel.updateLogUploadToken(any()) }
    }

    @Test fun savedStateRestorationDoesNotRestoreDisclosure() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { SettingsScreen(clusterBindings = clusterSettingsFixture(), viewModel = viewModel) } }
        selectDiagnostics()
        compose.onNodeWithContentDescription("Show upload token").performClick()
        assertVisible("synthetic-upload-value")
        restoration.emulateSavedInstanceStateRestore()
        selectDiagnostics()
        assertMasked()
        compose.onNodeWithContentDescription("Show upload token").assertExists()
    }

    @Test fun disablingAndReenablingUploadsRemasksToken() {
        openDiagnostics()
        compose.onNodeWithContentDescription("Show upload token").performClick()
        compose.runOnIdle { state.value = state.value.copy(logUploadEnabled = false) }
        tokenField().assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(logUploadEnabled = true) }
        tokenField().performScrollTo()
        assertMasked()
        compose.onNodeWithContentDescription("Show upload token").assertExists()
    }

    @Test fun editsStillReachOriginalCallbackWhileMaskedAndVisible() {
        openDiagnostics()
        tokenField().performTextReplacement("synthetic-masked-edit")
        verify(exactly = 1) { viewModel.updateLogUploadToken("synthetic-masked-edit") }
        compose.runOnIdle { state.value = state.value.copy(logUploadToken = "synthetic-masked-edit") }
        compose.onNodeWithContentDescription("Show upload token").performClick()
        assertVisible("synthetic-masked-edit")
        tokenField().performTextReplacement("synthetic-visible-edit")
        verify(exactly = 1) { viewModel.updateLogUploadToken("synthetic-visible-edit") }
        compose.runOnIdle { state.value = state.value.copy(logUploadToken = "synthetic-visible-edit") }
        compose.onNodeWithContentDescription("Hide upload token").performClick()
        assertMasked()
        compose.onNodeWithContentDescription("Show upload token").performClick()
        assertVisible("synthetic-visible-edit")
        verify(exactly = 2) { viewModel.updateLogUploadToken(any()) }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun visibilityTogglePreservesUnacknowledgedDraftAndSelection() {
        openDiagnostics()
        tokenField().performTextReplacement("synthetic-pending-edit")
        tokenField().performTextInputSelection(androidx.compose.ui.text.TextRange(2, 5))
        // Selection is also a TextFieldValue edit in the existing LocalEcho wrapper.
        verify(atLeast = 1) { viewModel.updateLogUploadToken("synthetic-pending-edit") }
        io.mockk.clearMocks(viewModel, answers = false)
        compose.onNodeWithContentDescription("Show upload token").performClick()
        assertVisible("synthetic-pending-edit")
        tokenField().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,
            androidx.compose.ui.text.TextRange(2, 5)))
        compose.onNodeWithContentDescription("Hide upload token").performClick()
        assertMasked()
        tokenField().assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange,
            androidx.compose.ui.text.TextRange(2, 5)))
        verify(exactly = 0) { viewModel.updateLogUploadToken(any()) }
    }

    @Test fun leavingDiagnosticsTabRemasksToken() {
        openDiagnostics()
        compose.onNodeWithContentDescription("Show upload token").performClick()
        compose.onNodeWithText("Input").performClick()
        selectDiagnostics()
        assertMasked()
        compose.onNodeWithContentDescription("Show upload token").assertExists()
    }
}
