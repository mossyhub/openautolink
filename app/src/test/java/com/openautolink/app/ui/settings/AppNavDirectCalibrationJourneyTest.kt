package com.openautolink.app.ui.settings
import android.app.Application
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.session.SessionState
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,qualifiers="w1000dp-h360dp-land-mdpi",
 shadows=[LiveGridWindowActivityTest.Admission::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppNavDirectCalibrationJourneyTest {
 @get:Rule val compose=createOalComposeRule()
 @Test fun realMenuSettingsStartBackSharedSaveWritesMigratedMaximum() = runBlocking<Unit> {
  val app=RuntimeEnvironment.getApplication()
  val product=Build.PRODUCT
  ReflectionHelpers.setStaticField(Build::class.java,"PRODUCT","aegean_orange")
  LiveGridWindowActivityTest.allowed=true
  val id=ShadowDisplayManager.addDisplay("w2000dp-h810dp-mdpi","Controlled public cluster")
  val physical=app.getSystemService(DisplayManager::class.java).getDisplay(id)
  shadowOf(physical).setFlags(Display.FLAG_PRESENTATION); shadowOf(physical).setState(Display.STATE_ON)
  assertEquals(PixelRect(0,135,2000,810),requireNotNull(ClusterProjectionOutput.qualifiedInitialDisplay(app)).host.visible)
  val storeJob=SupervisorJob()
  val file=File(app.filesDir,"nav-direct-${java.util.UUID.randomUUID()}.preferences_pb")
  val store=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=CoroutineScope(storeJob+Dispatchers.IO),produceFile={file})
  val preferences=AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java).apply{isAccessible=true}.newInstance(store)
  preferences.setCarPlaySettings(CarPlaySettings(enabled=false)); preferences.setDirectTransport("usb")
  val profile=ClusterDisplayProfiles.AEGEAN_ORANGE_239
  val scope=ClusterLayoutScope(profile.vehicle,profile.id,profile.role,profile.panel)
  val legacy=PixelRect(0,135,2000,810)
  val safe=PixelRect(636,150,1471,661)
  preferences.saveClusterLayout(SavedClusterLayout(scope,legacy,legacy,safe))
  val session=mockk<com.openautolink.app.session.SessionManager>(relaxed=true)
  val sessionState=MutableStateFlow(SessionState.CONNECTING)
  mockkObject(AppPreferences.Companion,com.openautolink.app.session.SessionManager.Companion,
    com.openautolink.app.transport.PhoneDiscovery.Companion,
    com.openautolink.app.carplay.CarPlaySessionLeases.Companion,com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.Companion)
  every{AppPreferences.getInstance(any())} returns preferences
        every { com.openautolink.app.carplay.CarPlaySessionLeases.forContext(any()) } returns
            com.openautolink.app.carplay.CarPlaySessionLeaseSource.NONE
        every { com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.forContext(any()) } returns
            com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.NONE
        every { com.openautolink.app.session.SessionManager.getInstance(any(), any(), any()) } returns session
        every { com.openautolink.app.session.SessionManager.instanceOrNull() } returns session
        every { session.sessionState } returns sessionState
        every { session.settingsSessionEpoch } returns MutableStateFlow(0L)
        every { session.clusterVideoStatus } returns MutableStateFlow(ClusterProjectionStatus())
        every { session.secondaryOutputStatuses } returns MutableStateFlow(emptyMap())
        every { session.transportMode } returns MutableStateFlow("usb")
        every { session.reconnectAttempt } returns MutableStateFlow(0)
        every { session.wakeEvents } returns kotlinx.coroutines.flow.MutableSharedFlow()
        every { session.videoStats } returns null
        every { session.audioStats } returns null
        every { session.callState } returns null
        every { session.videoDecoder } returns null
        every { session.statusMessage } returns MutableStateFlow("")
        every { session.currentManeuver } returns MutableStateFlow(null)
        every { session.vehicleEnergyForecast } returns MutableStateFlow(null)
        every { session.phoneBatteryLevel } returns MutableStateFlow(null)
        every { session.phoneBatteryCritical } returns MutableStateFlow(false)
        every { session.voiceSessionActive } returns MutableStateFlow(false)
        every { session.phoneSignalStrength } returns MutableStateFlow(null)
        every { session.wifiFrequencyMhz } returns MutableStateFlow(0)
        every { session.effectiveDpi } returns MutableStateFlow(160)
        val discovery = io.mockk.mockk<com.openautolink.app.transport.PhoneDiscovery>(relaxed = true) {
            every { phones } returns MutableStateFlow(emptyList())
            every { isSweeping } returns MutableStateFlow(false)
            every { sweepProgress } returns MutableStateFlow("")
        }
        every { com.openautolink.app.transport.PhoneDiscovery.getInstance(any()) } returns discovery

  var mounted by mutableStateOf(true)
  var created=false
  lateinit var settings:SettingsViewModel
  lateinit var projection:com.openautolink.app.ui.projection.ProjectionViewModel
  lateinit var navigation:androidx.navigation.NavHostController
  var grid:ActivityController<LiveGridWindowActivity>?=null
  val preview=ClusterCalibrationPreview(ClusterCalibrationPreview.Backend{_,_,_,_,_->error("No borrowed host for calibration")},{0L},{_,_-> {}})
  try {
   compose.setContent { if(mounted) {
    navigation=androidx.navigation.compose.rememberNavController()
    settings=androidx.lifecycle.viewmodel.compose.viewModel()
    projection=androidx.lifecycle.viewmodel.compose.viewModel(); created=true
    androidx.compose.material3.MaterialTheme { com.openautolink.app.ui.navigation.AppNavHost(clusterPreviewFactory={_,_->preview},navController=navigation) }
   } }
   compose.waitUntil(5000){shadowOf(Looper.getMainLooper()).idle(); created && settings.clusterLoaded.value && settings.carPlaySettingsLoaded.value}
   // Root owner initialized while projection was connecting. Production publishes idle before
   // the user opens Settings; no qualified host model is injected or preserved by the fixture.
   compose.runOnIdle { sessionState.value=SessionState.IDLE }
   compose.waitForIdle()
   compose.onNodeWithTag("settingsButton").performClick()
   compose.onNodeWithText("Settings").performClick()
   compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
   compose.waitUntil(5000){shadowOf(Looper.getMainLooper()).idle();compose.onAllNodesWithTag("adjustClusterLayout").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithTag("adjustClusterLayout").performScrollTo().performClick()
   compose.onNodeWithTag("liveGridStart").performClick()
   compose.runOnIdle {
    val intent=requireNotNull(shadowOf(app).nextStartedActivity)
    grid=ActivityController.of(LiveGridWindowActivity(),intent,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()).create().start().resume()
    val a=requireNotNull(grid).get().window.attributes
    assertEquals(98,a.x);assertEquals(65,a.y);assertEquals(1806,a.width);assertEquals(680,a.height)
   }
   compose.onNodeWithTag("liveGridBack").performClick()
   assertTrue(requireNotNull(grid).get().isFinishing)
   compose.onNodeWithTag("saveAndConnectButton").performClick()
   compose.waitUntil(10000){
    shadowOf(Looper.getMainLooper()).idle()
    // Settings is an overlay: PROJECTION was already the route before Save. Observe the
    // production DataStore acknowledgement, not that unrelated navigation value.
    runBlocking { preferences.getClusterLayout(scope,profile.output).custom != null }
   }
   val saved=requireNotNull(preferences.getClusterLayout(scope,profile.output).custom)
   assertEquals(profile.output,saved.canvas);assertEquals(profile.output,saved.output);assertEquals(safe,saved.safe)
  } finally {
   compose.runOnIdle { mounted=false;if(created){settings.viewModelScope.cancel();projection.viewModelScope.cancel()} };compose.waitForIdle()
   grid?.pause()?.stop()?.destroy()
   unmockkObject(AppPreferences.Companion,com.openautolink.app.session.SessionManager.Companion,
    com.openautolink.app.transport.PhoneDiscovery.Companion,
    com.openautolink.app.carplay.CarPlaySessionLeases.Companion,com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.Companion)
   storeJob.cancelAndJoin();file.delete();ReflectionHelpers.setStaticField(Build::class.java,"PRODUCT",product)
  }
 }
}
