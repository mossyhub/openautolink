package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import com.openautolink.app.carplay.CarPlayEvPilotConfiguration
import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.data.EvLearnedRateEstimator
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.ui.theme.OalUiScale
import com.openautolink.app.ui.theme.OpenAutoLinkTheme
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Actual page + existing draft callback; persistence remains covered by the real-store fixture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,qualifiers="w1024dp-h480dp-land-mdpi")
@ConscryptMode(ConscryptMode.Mode.OFF)
class EvRoutingDraftStatusTest {
    @get:Rule val compose=createOalComposeRule()
    private fun aa()=mockk<EvEnergyModelViewModel>(relaxed=true).apply {
        every {state} returns MutableStateFlow(EvEnergyModelViewModel.UiState())
        every {readout} returns MutableStateFlow(EvEnergyModelViewModel.LiveReadout(
            hasData=true,batteryWh=20000,capacityWh=60000,rangeM=100000,socPct=100f/3))
        every {profileMatch} returns MutableStateFlow(EvEnergyModelViewModel.ProfileMatch())
        every {learned} returns MutableStateFlow(EvLearnedRateEstimator.Snapshot())
        every {events} returns MutableStateFlow(null)
    }
    private fun cp(flow:MutableStateFlow<CarPlaySettings>,loaded:Boolean)=mockk<SettingsViewModel>(relaxed=true).apply {
        every {carPlaySettings} returns flow
        every {carPlaySettingsLoaded} returns MutableStateFlow(loaded)
        every {carPlaySettingsSaving} returns MutableStateFlow(false)
        every {updateCarPlayDraft(any())} answers {
            flow.value=firstArg<(CarPlaySettings)->CarPlaySettings>().invoke(flow.value)
        }
    }
    private fun mount(a:EvEnergyModelViewModel,c:SettingsViewModel) {
        compose.setContent {OpenAutoLinkTheme(oalUiScalePercent=100) {OalUiScale {
            Surface(Modifier.fillMaxSize()) {EvEnergyModelTab(a,carPlaySettingsViewModel=c)}
        }}}
    }
    @Test fun pendingCarPlayLoadDoesNotClaimUnconfiguredOrBlockAutomaticAaReadout() {
        val a=aa();val c=cp(MutableStateFlow(CarPlaySettings()),false);mount(a,c)
        compose.onNodeWithTag("evAndroidAutoStatus").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Current battery and range are available to share when connected.").assertExists()
        compose.onNodeWithTag("evAppleMapsStatus").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Loading routing settings…").assertExists()
        compose.onNodeWithTag("carPlayEvData").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("carPlayEvChargeKw").assertDoesNotExist()
        verify(exactly=0) {c.updateCarPlayDraft(any())}
    }
    @Test fun saveInProgressDisablesSwitchWithoutHidingVehicleReadings() {
        val c = cp(MutableStateFlow(CarPlaySettings.decode("""{"evDataEnabled":true}""")), true)
        every { c.carPlaySettingsSaving } returns MutableStateFlow(true)
        mount(aa(), c)
        compose.onNodeWithTag("carPlayEvData").performScrollTo().assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("Current battery and range are available to share when connected.").assertExists()
        verify(exactly = 0) { c.updateCarPlayDraft(any()) }
    }

    @Test fun legacyCalibrationDoesNotOptInAndSingleSwitchEditsOnlyNewDraftFlag() {
        val before=CarPlaySettings(ssid="preserved-network",fps=47,evDataEnabled=false,evRoutingPilot=CarPlayEvPilotConfiguration(
            enabled=true,profileId=CarPlayEvPilotConfiguration.GM_SOURCE,averageChargeKw="90",modelCapacityKwh="60"))
        val flow=MutableStateFlow(before);val a=aa();val c=cp(flow,true);mount(a,c)
        compose.onNodeWithTag("carPlayEvChargeKw").assertDoesNotExist()
        compose.onNodeWithTag("evMasterToggle").assertDoesNotExist()
        compose.onNodeWithTag("carPlayEvData").performScrollTo().assertIsOff()
        verify(exactly=0) {c.updateCarPlayDraft(any())}
        compose.onNodeWithTag("carPlayEvData").performClick().assertIsOn()
        compose.onNodeWithTag("carPlayEvChargeKw").assertDoesNotExist()
        compose.onNodeWithTag("carPlayEvCapacity").assertDoesNotExist()
        compose.runOnIdle {
            val stored = Json.parseToJsonElement(CarPlaySettings.encode(before)).jsonObject
            val staged = Json.parseToJsonElement(CarPlaySettings.encode(flow.value)).jsonObject
            assertEquals(stored + ("evDataEnabled" to JsonPrimitive(true)), staged.toMap())
        }
        compose.onNodeWithTag("carPlayEvData").performClick().assertIsOff()
        compose.runOnIdle { assertEquals(before, flow.value) }
        verify(exactly=2) {c.updateCarPlayDraft(any())}
        verify(exactly=0) {a.setTuningEnabled(any())}
        verify(exactly=0) {a.sendNow()}
    }
}
