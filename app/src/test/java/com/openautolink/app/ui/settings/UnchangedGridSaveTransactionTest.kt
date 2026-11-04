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
class UnchangedGridSaveTransactionTest {
 @get:Rule val compose=createOalComposeRule()
 @Test fun unreadySaveReportsVisibleFailureInsteadOfSilentFalse() = runBlocking {
  val app=RuntimeEnvironment.getApplication()
  val vm=SettingsViewModel(app)
  try {
   assertFalse(vm.clusterLoaded.value)
   assertFalse(vm.commitSettings(null))
   assertTrue("Blocked Save needs an actionable visible error",vm.carPlayApplyError.value.isNotEmpty())
  } finally { vm.viewModelScope.cancel() }
 }
 @Test fun overlayUnchangedMigrationSaveThenRenderedReconnect() = journey(false, false)
 @Test fun destinationUnchangedMigrationSaveThenRenderedReconnect() = journey(false, true)
 @Test fun overlayResetStartBackSaveRemovesLegacy() = journey(true, false)
 @Test fun destinationResetStartBackSaveRemovesLegacy() = journey(true, true)
 @Test fun overlayLayoutFailureVisibleNoCloseNoReconnect() = journey(false, false, "layout")
 @Test fun destinationClusterStoreFailureVisibleNoCloseNoReconnect() = journey(false, true, "cluster")
 @Test fun overlayCarPlayStoreFailureVisibleNoCloseNoReconnect() = journey(false, false, "carplay")
 @Test fun destinationSecondaryStoreFailureVisibleNoCloseNoReconnect() = journey(false, true, "secondary")
 private fun journey(reset: Boolean, destination: Boolean, failure: String? = null) = runBlocking<Unit> {
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
  val realPreferences=AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java).apply{isAccessible=true}.newInstance(store)
  val preferences=spyk(realPreferences)
  preferences.setCarPlaySettings(CarPlaySettings(enabled=false)); preferences.setDirectTransport("usb")
  val profile=ClusterDisplayProfiles.AEGEAN_ORANGE_239
  val scope=ClusterLayoutScope(profile.vehicle,profile.id,profile.role,profile.panel)
  val legacy=PixelRect(0,135,2000,810)
  val safe=PixelRect(617,135,1452,611)
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

  val receipts=java.util.concurrent.CopyOnWriteArrayList<String>()
  mockkObject(com.openautolink.app.diagnostics.DiagnosticLog)
  every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(),any()) } answers { receipts.add(secondArg()); Unit }
  every { com.openautolink.app.diagnostics.DiagnosticLog.w(any(),any()) } just Runs
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
   if(destination) { compose.runOnIdle { navigation.navigate(com.openautolink.app.ui.navigation.AppDestinations.SETTINGS) }; compose.waitForIdle() }
   else { compose.onNodeWithTag("settingsButton").performClick(); compose.onNodeWithText("Settings").performClick() }
   compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
   compose.waitUntil(5000){shadowOf(Looper.getMainLooper()).idle();compose.onAllNodesWithTag("adjustClusterLayout").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithTag("adjustClusterLayout").performScrollTo().performClick()
   if(reset) compose.onNodeWithTag("liveGridReset").performClick()
   compose.onNodeWithTag("liveGridStart").performClick()
   compose.runOnIdle {
    val intent=requireNotNull(shadowOf(app).nextStartedActivity)
    grid=ActivityController.of(LiveGridWindowActivity(),intent,ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()).create().start().resume()
    val a=requireNotNull(grid).get().window.attributes
    assertEquals(98,a.x);assertEquals(65,a.y);assertEquals(1806,a.width);assertEquals(680,a.height)
   }
   edited=profile.output // No drag, numeric edit or synthetic staging callback.
   compose.onNodeWithTag("liveGridBack").performClick()
   assertTrue(requireNotNull(grid).get().isFinishing)
   if(failure=="layout") coEvery { preferences.saveClusterLayout(any()) } throws java.io.IOException("secret fixture must never escape")
   if(failure=="cluster") {
    compose.runOnIdle { settings.setClusterEnabled(true) }
    coEvery { preferences.updateClusterOutputState(any()) } throws java.io.IOException("secret fixture must never escape")
   }
   if(failure=="carplay") {
    compose.runOnIdle { settings.updateCarPlayDraft { it.copy(enabled=false) } }
    coEvery { preferences.updateCarPlaySettings(any(),any()) } throws java.io.IOException("secret fixture must never escape")
   }
   if(failure=="secondary") {
    compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); settings.secondaryPreferences.value!=null }
    compose.runOnIdle { settings.updateSecondaryOutput(SecondaryOutputId("save-fixture")) { it.copy(enabled=true) } }
    coEvery { preferences.updateSecondaryDisplayPreferences(any(),any()) } throws java.io.IOException("secret fixture must never escape")
   }
   compose.onNodeWithTag("saveAndConnectButton").performClick()
   if(failure!=null) {
    compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); settings.carPlayApplyError.value.isNotEmpty() && !settings.clusterSaving.value }
    compose.onNodeWithTag("settingsApplyError").assertExists()
    compose.onNodeWithTag("saveAndConnectButton").assertExists()
    assertEquals(SavedClusterLayout(scope,legacy,legacy,safe),preferences.getClusterLayout(scope,legacy).custom)
    coVerify(exactly=0) { session.stop() }
    assertFalse(settings.carPlayApplyError.value.contains("secret"))
    assertTrue("Per-click transaction receipt must locate failed store",receipts.any { it.contains("SETTINGS_SAVE_V1") && it.contains("stage=$failure") && it.contains("result=failed") })
    assertFalse(receipts.any { it.contains("secret fixture") })
    assertFalse(receipts.any { it.contains("stage=reconnect_dispatch") || it.contains("stage=close") })
    return@runBlocking
   }
   compose.waitUntil(10000){ shadowOf(Looper.getMainLooper()).idle(); !settings.clusterSaving.value && runBlocking {
    if(reset) preferences.getClusterLayout(scope,legacy).custom==null
    else preferences.getClusterLayout(scope,profile.output).custom!=null
   } }
   assertTrue("Rendered Save must have a correlated click receipt",receipts.any { it.contains("SETTINGS_SAVE_V1") && it.contains("stage=click") })
   assertTrue("Durable geometry acknowledgement must precede reconnect",receipts.any { it.contains("SETTINGS_SAVE_V1") && it.contains("stage=layout") && it.contains("result=success") })
   if(reset) {
    assertNull(preferences.getClusterLayout(scope,legacy).custom)
    assertNull(preferences.getClusterLayout(scope,profile.output).custom)
    return@runBlocking
   }
   val saved=requireNotNull(preferences.getClusterLayout(scope,profile.output).custom)
   assertEquals(profile.output,saved.canvas);assertEquals(profile.output,saved.output);assertEquals(safe,saved.safe)
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
   // Menu Reconnect is deliberately not Save. Leave a Reset draft in the settings owner.
   compose.onNodeWithTag("adjustClusterLayout").performScrollTo().performClick()
   compose.onNodeWithTag("liveGridReset").performClick()
   compose.onNodeWithTag("liveGridBack").performClick()
   compose.runOnIdle { navigation.popBackStack() }; compose.waitForIdle()
   compose.onNodeWithTag("settingsButton").performClick()
   compose.onNodeWithText("Reconnect",substring=false).performClick()
   compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle();next?.isCompleted==true }
   val result=next!!.await();assertEquals(1,reconnects)
   assertTrue(receipts.any { it.contains("source=menu commitsDraft=false") })
   val transaction=receipts.first { it.contains("stage=click") }.substringAfter("click=").substringBefore(" ")
   val chain=receipts.filter { it.contains("SETTINGS_SAVE_V1 click=$transaction ") }
   assertTrue(chain.indexOfFirst { it.contains("stage=durable result=success") } in 0 until chain.indexOfFirst { it.contains("stage=reconnect_dispatch") })
   System.getProperty("handoff.receipt.dir")?.let { dir ->
    val target=File(dir).also { it.mkdirs() }
    File(target,"unchanged-${if(destination) "destination" else "overlay"}-receipts.txt").writeText(receipts.joinToString("\n"))
    val fields=listOf("channelId","protocolDisplayId","displayType","density","cropLeft","cropTop","cropRight","cropBottom","safeLeft","safeTop","safeRight","safeBottom","stableLeft","stableTop","stableRight","stableBottom")
    fun offerJson(p:Any)=org.json.JSONObject().also { obj -> val o=(p as PreparedAaSecondaryOutputs).offers.single();fields.forEach { field -> obj.put(field,o.javaClass.getField(field).getInt(o)) } }
    File(target,"unchanged-aa-${if(destination) "destination" else "overlay"}.json").writeText(org.json.JSONObject().put("before",offerJson(first)).put("after",offerJson(result)).toString(2))
   }
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
   storeJob.cancelAndJoin()
   val coldJob=SupervisorJob()
   val coldStore=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=CoroutineScope(coldJob+Dispatchers.IO),produceFile={file})
   val cold=AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java).apply{isAccessible=true}.newInstance(coldStore)
   try {
    if(failure==null) { if(reset) assertNull(cold.getClusterLayout(scope,legacy).custom)
     else assertEquals(SavedClusterLayout(scope,profile.output,profile.output,safe),cold.getClusterLayout(scope,profile.output).custom) }
   } finally { coldJob.cancelAndJoin();file.delete();ReflectionHelpers.setStaticField(Build::class.java,"PRODUCT",product) }
  }
 }
}
