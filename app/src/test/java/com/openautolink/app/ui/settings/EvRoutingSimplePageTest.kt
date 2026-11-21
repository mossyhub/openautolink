package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import com.openautolink.app.data.EvLearnedRateEstimator
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.ui.theme.OpenAutoLinkTheme
import com.openautolink.app.ui.theme.OalUiScale
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,qualifiers="w1024dp-h480dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class EvRoutingSimplePageTest {
    @get:Rule val compose=createOalComposeRule()
    private fun model(custom:Boolean=false)=mockk<EvEnergyModelViewModel>(relaxed=true).apply {
        every {state} returns MutableStateFlow(EvEnergyModelViewModel.UiState(tuningEnabled=custom))
        every {readout} returns MutableStateFlow(EvEnergyModelViewModel.LiveReadout())
        every {profileMatch} returns MutableStateFlow(EvEnergyModelViewModel.ProfileMatch())
        every {learned} returns MutableStateFlow(EvLearnedRateEstimator.Snapshot())
        every {events} returns MutableStateFlow(null)
        every {learnEvaluationStatus} returns MutableStateFlow(null)
    }
    private fun mount(vm:EvEnergyModelViewModel) {
        compose.setContent {OpenAutoLinkTheme(oalUiScalePercent=100) {OalUiScale {Surface(Modifier.fillMaxSize()) {EvEnergyModelTab(vm)}}}}
    }
    @Test fun ordinaryPageExplainsBothProtocolsWithoutAskingForPhysics() {
        val vm=model();mount(vm)
        compose.onNodeWithText("EV & routing").assertIsDisplayed()
        compose.onNodeWithTag("evAndroidAutoStatus").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("evAppleMapsStatus").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("evMasterToggle").assertDoesNotExist()
        compose.onNodeWithText("Average DC charge (kW)").assertDoesNotExist()
        compose.onNodeWithTag("evSendNow").assertDoesNotExist()
        verify(exactly=0) {vm.setTuningEnabled(any())}
        verify(exactly=0) {vm.sendNow()}
    }
    @Test fun availableBatteryEnergyDoesNotDependOnRangeOrReportedCapacity() {
        val vm = model()
        every { vm.readout } returns MutableStateFlow(EvEnergyModelViewModel.LiveReadout(
            batteryAvailable = true, batteryWh = 24000,
        ))
        mount(vm)
        compose.onNodeWithText("Battery: 24.0 kWh").assertIsDisplayed()
        compose.onNodeWithText("Range: Unavailable").assertIsDisplayed()
    }

    @Test fun learnModeIsOffAndUsesOnlyTheLocalEvaluationControl() {
        val vm = model(custom = true)
        every { vm.state } returns MutableStateFlow(EvEnergyModelViewModel.UiState(
            tuningEnabled = true, learnEvaluationLoaded = true,
        ))
        mount(vm)
        compose.onNodeWithTag("evLearnEvaluation").performScrollTo().assertIsOff().performClick()
        verify(exactly = 1) { vm.setLearnEvaluationEnabled(true) }
        verify(exactly = 0) { vm.setTuningEnabled(any()) }
        verify(exactly = 0) { vm.sendNow() }
    }

    @Test fun oldPreviewSettingsDoNotExposeInactiveControlsOrResetSavedIntent() {
        val vm=model(true);mount(vm)
        compose.onNodeWithTag("evMasterToggle").assertDoesNotExist()
        compose.onNodeWithTag("evAdvanced").assertDoesNotExist()
        compose.onNodeWithTag("evUseEpaBaseline").assertDoesNotExist()
        compose.onNodeWithTag("evSendNow").assertDoesNotExist()
        verify(exactly=0) {vm.setTuningEnabled(any())}
        verify(exactly=0) {vm.resetToDefaults()}
    }
    @Test @Config(qualifiers="w480dp-h800dp-port-mdpi")
    fun narrowLargeTextShowsSourceVersusRoutingReadiness() {
        val old=org.robolectric.RuntimeEnvironment.getApplication().resources.configuration.fontScale
        try {
            org.robolectric.RuntimeEnvironment.setFontScale(1.3f)
            val vm=model();var root:android.view.View?=null
            compose.setContent {OpenAutoLinkTheme(oalUiScalePercent=100) {OalUiScale {Surface(Modifier.fillMaxSize()) {
                root=LocalView.current.rootView;EvEnergyModelTab(vm)
            }}}}
            compose.onNodeWithTag("evAppleMapsStatus").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Automatic compact EV contribution is a separate choice in Diagnostics.")
                .performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                val view=requireNotNull(root);val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                System.getProperty("ev588.render.dir")?.let {dir ->File(dir).mkdirs();File(dir,"ev-${view.width}x${view.height}-sdk${android.os.Build.VERSION.SDK_INT}.png").outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
                bitmap.recycle()
            }
        } finally {org.robolectric.RuntimeEnvironment.setFontScale(old)}
    }
}
