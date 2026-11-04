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
import androidx.compose.ui.geometry.Offset
import com.openautolink.app.secondary.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,qualifiers="w1000dp-h360dp-land-mdpi",
 shadows=[LiveGridWindowActivityTest.Admission::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CombinedAppNavSavedLayoutApplyTest {
 @get:Rule val compose=createOalComposeRule()
 @Test fun actualAppNavEditorDragRestartSharedSaveColdRestoreExpandedExplicitSaveReconnect() = journey(false)
 @Test fun pendingLayoutGuidanceClearsOnExactHostLossWithoutRewritingSave() = journey(true)
 private fun journey(hostLoss: Boolean) = runBlocking<Unit> {
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
  // This journey edits/reconnects Video; do not combine fresh Icons-only intent with a Video fixture.
  preferences.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
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
  mockkObject(com.openautolink.app.carplay.CarPlayCallController.Companion)
  val calls=mockk<com.openautolink.app.carplay.CarPlayCallController>(relaxed=true)
  every { calls.state } returns MutableStateFlow(com.openautolink.app.carplay.CarPlayCallController.State())
  every { com.openautolink.app.carplay.CarPlayCallController.get(any()) } returns calls
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
  var oldGrid:ActivityController<LiveGridWindowActivity>?=null
  var fixture:RetainedHostRuntimeFixture?=null
  var edited:PixelRect?=null
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
   compose.onNodeWithTag("liveGridResize").performClick()
   compose.onNodeWithTag("liveGridCanvas").performTouchInput { down(center);moveBy(Offset(-20f,0f));moveBy(Offset(-10f,0f));up() }
   compose.waitForIdle()
   compose.onNodeWithTag("liveGridMove").performClick()
   compose.onNodeWithTag("liveGridCanvas").performTouchInput { down(center);moveBy(Offset(20f,0f));moveBy(Offset(10f,0f));up() }
   compose.waitForIdle()
   compose.runOnIdle { val a=grid!!.get().window.attributes;edited=PixelRect(a.x,a.y,a.x+a.width,a.y+a.height);assertNotEquals(profile.output,edited) }
   compose.onNodeWithTag("liveGridStop").performClick();oldGrid=grid
   compose.onNodeWithTag("liveGridStart").performClick()
   compose.runOnIdle {
    val intent=requireNotNull(shadowOf(app).nextStartedActivity)
    grid=ActivityController.of(LiveGridWindowActivity(),intent,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()).create().start().resume()
    oldGrid!!.pause().stop().destroy();assertFalse(grid!!.get().isFinishing)
    val a=grid!!.get().window.attributes;assertEquals(edited,PixelRect(a.x,a.y,a.x+a.width,a.y+a.height))
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
   assertEquals(profile.output,saved.canvas);assertEquals(edited,saved.output);assertTrue(legacy.contains(saved.safe))
   val f=RetainedHostRuntimeFixture(preferences).also { fixture=it }
   mockkObject(SecondaryOutputRuntime.Companion);every { SecondaryOutputRuntime.get(any()) } returns f.runtime
   val first=f.prepared(false,601);assertTrue(f.offered(first));f.captureManager(first)
   compose.runOnIdle { mounted=false };compose.waitForIdle()
   compose.runOnIdle { mounted=true };compose.waitForIdle()
   compose.runOnIdle { navigation.navigate(com.openautolink.app.ui.navigation.AppDestinations.SETTINGS) }
   compose.waitForIdle()
   compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
   val frozen=f.runtime.mapping(f.descriptor.id) as ClusterVideoMapping
   assertNotEquals(edited,frozen.qualifiedDisplay.effectiveOutput)
   compose.onNodeWithText("Saved cluster layout is pending",substring=true).assertExists()
   f.expand();assertSame(frozen,f.runtime.mapping(f.descriptor.id));f.expand();assertEquals(1,f.bridges.size)
   compose.waitForIdle()
   compose.onNodeWithText("Saved cluster layout is pending",substring=true).assertExists()
   if(hostLoss) {
    f.ignition(false);f.pump { f.runtime.currentLiveHostedDisplay()==null }
    // Runtime host loss is not acknowledgement that the asynchronous binding/composition
    // has consumed it. Keep the original predicate; boundedly observe its rendered outcome.
    compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle();
     compose.onAllNodesWithText("Saved cluster layout is pending",substring=true).fetchSemanticsNodes().isEmpty()
    }
    compose.onNodeWithText("Saved cluster layout is pending",substring=true).assertDoesNotExist()
    assertEquals(saved,preferences.getClusterLayout(scope,profile.output).custom)
    return@runBlocking
   }
   // Real cold editor owner restores the complete same file-backed model.
   val observations=MutableStateFlow(ClusterSettingsObservation())
   val adapter=ClusterSettingsRuntimeAdapter.create(app,observations,preferences,preview,CoroutineScope(storeJob+Dispatchers.Main.immediate))
   try { assertEquals(saved.output,adapter.bindings.value.layout!!.output);assertEquals(saved.safe,adapter.bindings.value.layout!!.safe) } finally { adapter.close() }
   preferences.setDirectTransport("hotspot")
   every { session.transportMode } returns MutableStateFlow("hotspot")
   sessionState.value=SessionState.STREAMING
   var next:Deferred<PreparedAaSecondaryOutputs>?=null
   var reconnects=0
   coEvery { session.reconnect(codecPreference=any(),micSourcePreference=any(),scalingMode=any(),directTransport=any(),wppInterfaceName=any(),hotspotSsid=any(),hotspotPassword=any(),videoAutoNegotiate=any(),aaResolution=any(),aaDpi=any(),aaAutoDpi=any(),aaWidthMargin=any(),aaHeightMargin=any(),aaPixelAspect=any(),aaTargetLayoutWidthDp=any(),aaViewingDistanceMm=any(),aaDecoderAdditionalDepth=any(),aaAutoMargins=any(),videoFps=any(),driveSide=any(),hideClock=any(),hideSignal=any(),hideBattery=any(),volumeOffsetMedia=any(),volumeOffsetNavigation=any(),volumeOffsetAssistant=any(),manualIpAddress=any(),safeAreaTop=any(),safeAreaBottom=any(),safeAreaLeft=any(),safeAreaRight=any(),gpsForwarding=any(),galVersion=any(),seedIdrThresholds=any()) } coAnswers {
    reconnects++;val retired=f.retire(first);while(!retired.isDone) {delay(2)};assertTrue(retired.get());next=f.scope.async {f.runtime.prepareAndroidAuto(602,f.frozen)}
   }
   compose.onNodeWithTag("saveAndConnectButton").performClick()
   compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle();next?.isCompleted==true }
   val result=next!!.await();assertEquals(1,reconnects)
   val mapping=f.runtime.mapping(f.descriptor.id) as ClusterVideoMapping
   assertEquals(saved.output,mapping.qualifiedDisplay.effectiveOutput);assertEquals(saved.safe,mapping.qualifiedDisplay.safe)
   assertEquals(2,f.bridges.size);assertEquals(1,f.physical.size);assertEquals(1,f.detached.get())
   assertEquals(saved,preferences.getClusterLayout(scope,profile.output).custom)
   assertEquals(mapping.codecCrop.top,result.offers.single().cropTop)
   compose.runOnIdle { navigation.navigate(com.openautolink.app.ui.navigation.AppDestinations.SETTINGS) }
   compose.waitForIdle();compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
   compose.onNodeWithText("Saved cluster layout is pending",substring=true).assertDoesNotExist()

  } finally {
   compose.runOnIdle { mounted=false;if(created){settings.viewModelScope.cancel();projection.viewModelScope.cancel()} };compose.waitForIdle()
   fixture?.close()
   grid?.pause()?.stop()?.destroy()
   unmockkObject(AppPreferences.Companion,com.openautolink.app.session.SessionManager.Companion,
    com.openautolink.app.transport.PhoneDiscovery.Companion,
    com.openautolink.app.carplay.CarPlaySessionLeases.Companion,com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.Companion)
   unmockkAll()
   storeJob.cancelAndJoin();file.delete();ReflectionHelpers.setStaticField(Build::class.java,"PRODUCT",product)
  }
 }
}
