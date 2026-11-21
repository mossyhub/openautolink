package com.openautolink.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import com.openautolink.app.cluster.ClusterOutputSelection
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [32], application = android.app.Application::class, qualifiers = "w1280dp-h960dp-land")
class EvContributionOptInComposeTest {
    @get:Rule val compose = createOalComposeRule()
    private val saved = MutableStateFlow(SettingsUiState(directTransport = "usb"))
    private val vm = mockk<SettingsViewModel>(relaxed = true).also {
        every { it.settingsState } returns saved
        every { it.carPlaySettingsSaving } returns MutableStateFlow(false)
        every { it.clusterSaving } returns MutableStateFlow(false)
        every { it.clusterLoaded } returns MutableStateFlow(true)
        every { it.clusterSelection } returns MutableStateFlow(ClusterOutputSelection())
        every { it.carPlaySettingsLoaded } returns MutableStateFlow(true)
        every { it.carPlayApplyError } returns MutableStateFlow("")
        every { it.wiredOnly } returns MutableStateFlow(false)
        every { it.carPlaySettings } returns MutableStateFlow(CarPlaySettings())
        every { it.carPlayPhones } returns MutableStateFlow(emptyList())
        every { it.carPlayIdentityReady } returns MutableStateFlow(false)
        every { it.carPlayPasswordSaved } returns MutableStateFlow(false)
        every { it.carPlaySettingsStatus } returns MutableStateFlow("")
        every { it.carPlayCredentialImportBusy } returns MutableStateFlow(false)
        every { it.carPlayCredentialImportStatus } returns MutableStateFlow("")
        coEvery { it.updateEvContributionConsent() } returns EvRenewalResult.RECEIVER_UNVERIFIED
    }

    @Test fun offSwitchOpensDisclosureWithoutCheckingUntilConfirmed() {
        openDiagnostics()
        toggle().assertIsOff().performClick()
        compose.onNodeWithText("Share expanded EV observations?").assertExists()
        coVerify(exactly = 0) { vm.updateEvContributionConsent() }
        compose.onNodeWithText("Cancel").performScrollTo().performClick()
        toggle().assertIsOff()
        coVerify(exactly = 0) { vm.updateEvContributionConsent() }
        toggle().performClick()
        confirm()
        toggle().assertIsOff()
    }

    @Test fun existingOptInCanReviewExpandedSharingOrTurnOffDirectly() {
        saved.value = saved.value.copy(evContributionConsent = true)
        openDiagnostics()
        compose.onNodeWithText("Review expanded sharing").performScrollTo().performClick()
        coVerify(exactly = 0) { vm.updateEvContributionConsent() }
        confirm()
        toggle().assertIsOn().performClick()
        verify(exactly = 1) { vm.disableEvContributionConsent() }
        coVerify(exactly = 1) { vm.updateEvContributionConsent() }
    }

    private fun openDiagnostics() {
        compose.setContent { MaterialTheme { SettingsScreen(clusterBindings = clusterSettingsFixture(), viewModel = vm) } }
        compose.onNodeWithTag("settingsTab_DIAGNOSTICS").performScrollTo().performClick()
    }

    private fun toggle() = compose.onNodeWithTag("evContributionConsentToggle").performScrollTo()

    private fun confirm() {
        compose.onNodeWithText("Agree and check receiver").performScrollTo().performClick()
        val refused = compose.onNodeWithText(EvRenewalResult.RECEIVER_UNVERIFIED.message)
        refused.performScrollTo().assertIsDisplayed()
        coVerify(exactly = 1) { vm.updateEvContributionConsent() }
    }
}
