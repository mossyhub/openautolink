package com.openautolink.app.ui.settings.cimigration

import android.app.Application
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.lifecycle.viewModelScope
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.secondary.*
import com.openautolink.app.ui.settings.*
import com.openautolink.app.ui.testing.closeCarPlayCallControllerTestProcessAndAwait
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.session.SessionState
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Shared current UI fixture. No legacy button, fake Save callback, or process-fence reset.
 * Inventory is an explicit controlled OS boundary; preview still validates the real display.
 * Legacy resolver/adapter coverage is separate from the public safe-only editor journey. */
internal class DirectionsSettingsJourney(private val compose: ComposeContentTestRule) {
    fun run(destination: Boolean = false, reset: Boolean = false, edit: Boolean = false,
        hostLoss: Boolean = false, failure: String? = null, cancel: Boolean = false, staleTarget: Boolean = false) = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication() as Application
        val product = Build.PRODUCT
        val activityContexts = System.getProperty("robolectric.createActivityContexts")
        System.setProperty("robolectric.createActivityContexts", "true")
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "aegean_orange")
        val displayId = ShadowDisplayManager.addDisplay("w2000dp-h810dp-mdpi", "Directions fixture")
        val physical = app.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(physical).setFlags(Display.FLAG_PRESENTATION); shadowOf(physical).setState(Display.STATE_ON)
        val descriptor = SecondaryDisplayDescriptor(SecondaryOutputId("directions-fixture"), displayId, 1,
            "Directions fixture", 2000, 810, 160, SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Driver")
        val inventory = MutableStateFlow(SecondaryInventorySnapshot(1, listOf(descriptor)))
        val rawRows = MutableStateFlow(listOf(DiscoveredSecondaryDisplay(descriptor.id.value, displayId,
            descriptor.name, 2000, 810, true, true, false, null)))
        val producer = mockk<SecondaryDisplayPolicyInventory>()
        every { producer.snapshots } returns inventory
        val source = SecondaryDisplaysSource(inventory, rawRows, null)
        val protocols = DirectSavedDirectionsProtocols(compose, app, descriptor, inventory)
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val layoutScope = ClusterLayoutScope(profile.vehicle, profile.id, profile.role, profile.panel)
        val legacy = SavedClusterLayout(layoutScope, profile.output, profile.output, PixelRect(636,150,1471,661))
        val outputLayout = SecondaryLayoutPreference(requireNotNull(secondaryLayoutScope(descriptor)),
            SecondaryLayoutBox(.1,.1,.9,.9), SecondaryLayoutBox(.3,.3,.7,.7),
            directSafe = SecondaryLayoutBox(.31,.20,.73,.75))
        val file = File(app.filesDir, "directions-${java.util.UUID.randomUUID()}.preferences_pb")
        val storeJob = SupervisorJob()
        fun newPrefs(job: Job): AppPreferences {
            val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
                scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
            return AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
                .apply { isAccessible = true }.newInstance(store)
        }
        val preferences = spyk(newPrefs(storeJob))
        preferences.setCarPlaySettings(CarPlaySettings(enabled = false)); preferences.setDirectTransport("usb")
        preferences.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
        preferences.saveClusterLayout(legacy)
        val initialOutput = SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY, false, false, outputLayout)
        val initialCommit = preferences.updateSecondaryDisplayPreferences(0, mapOf(descriptor.id to initialOutput))
        assertEquals(1L, initialCommit.revision)
        assertEquals(initialOutput, initialCommit.outputs.getValue(descriptor.id))
        val before = preferences.getSecondaryDisplayPreferences()
        val session = mockk<com.openautolink.app.session.SessionManager>(relaxed = true)
        val sessionState = MutableStateFlow(SessionState.IDLE)
        mockkObject(AppPreferences.Companion, com.openautolink.app.session.SessionManager.Companion,
            com.openautolink.app.transport.PhoneDiscovery.Companion,
            com.openautolink.app.carplay.CarPlaySessionLeases.Companion,
            com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.Companion,
            com.openautolink.app.carplay.CarPlayCallController.Companion,
            SecondaryDisplayPolicyInventory.Companion, ClusterProjectionOutput.Companion, SecondaryOutputRuntime.Companion)
        every { SecondaryOutputRuntime.get(any()) } returns protocols.runtime
        every { AppPreferences.getInstance(any()) } returns preferences
        every { SecondaryDisplayPolicyInventory.get(any()) } returns producer
        every { ClusterProjectionOutput.qualifiedInitialDisplay(any()) } returns requireNotNull(
            ClusterDisplayProfiles.qualify(profile.vehicle, listOf(ClusterDisplayBinding(profile.role, displayId)),
                ClusterHostGeometry(displayId, profile.panel, profile.panel.bounds, profile.panel.bounds)))
        every { com.openautolink.app.carplay.CarPlaySessionLeases.forContext(any()) } returns com.openautolink.app.carplay.CarPlaySessionLeaseSource.NONE
        every { com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.forContext(any()) } returns com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.NONE
        val calls = mockk<com.openautolink.app.carplay.CarPlayCallController>(relaxed = true)
        every { calls.state } returns MutableStateFlow(com.openautolink.app.carplay.CarPlayCallController.State())
        every { com.openautolink.app.carplay.CarPlayCallController.get(any()) } returns calls
        every { com.openautolink.app.session.SessionManager.getInstance(any(), any(), any()) } returns session
        every { com.openautolink.app.session.SessionManager.instanceOrNull() } returns session
        every { session.sessionState } returns sessionState
        every { session.settingsSessionEpoch } returns MutableStateFlow(0L)
        every { session.clusterVideoStatus } returns MutableStateFlow(ClusterProjectionStatus())
        every { session.secondaryOutputStatuses } returns MutableStateFlow(emptyMap())
        every { session.transportMode } returns MutableStateFlow("usb")
        every { session.reconnectAttempt } returns MutableStateFlow(0)
        every { session.wakeEvents } returns kotlinx.coroutines.flow.MutableSharedFlow()
        every { session.videoStats } returns null; every { session.audioStats } returns null
        every { session.callState } returns null; every { session.videoDecoder } returns null
        every { session.statusMessage } returns MutableStateFlow("")
        every { session.currentManeuver } returns MutableStateFlow(null)
        every { session.vehicleEnergyForecast } returns MutableStateFlow(null)
        every { session.phoneBatteryLevel } returns MutableStateFlow(null)
        every { session.phoneBatteryCritical } returns MutableStateFlow(false)
        every { session.voiceSessionActive } returns MutableStateFlow(false)
        every { session.phoneSignalStrength } returns MutableStateFlow(null)
        every { session.wifiFrequencyMhz } returns MutableStateFlow(0)
        every { session.effectiveDpi } returns MutableStateFlow(160)
        val discovery = mockk<com.openautolink.app.transport.PhoneDiscovery>(relaxed = true) {
            every { phones } returns MutableStateFlow(emptyList())
            every { isSweeping } returns MutableStateFlow(false)
            every { sweepProgress } returns MutableStateFlow("")
        }
        every { com.openautolink.app.transport.PhoneDiscovery.getInstance(any()) } returns discovery
        val receipts = java.util.concurrent.CopyOnWriteArrayList<String>()
        mockkObject(com.openautolink.app.diagnostics.DiagnosticLog)
        every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(), any()) } answers { receipts.add(secondArg()); Unit }
        every { com.openautolink.app.diagnostics.DiagnosticLog.w(any(), any()) } just Runs
        var mounted by mutableStateOf(true)
        var created = false
        lateinit var settings: SettingsViewModel
        lateinit var projection: com.openautolink.app.ui.projection.ProjectionViewModel
        lateinit var navigation: androidx.navigation.NavHostController
        var bindings: ClusterSettingsBindings? = null
        val grids = mutableListOf<ActivityController<LiveGridWindowActivity>>()
        val preview = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _,_,_,_,_ ->
            error("Public editor must not borrow a hosted map") }, { 0L }, { _,_ -> {} })
        var primary: Throwable? = null
        var coldJob: Job? = null
        fun await(condition: () -> Boolean) = compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); condition() }
        fun openSettings() {
            if (destination) compose.runOnIdle { navigation.navigate(com.openautolink.app.ui.navigation.AppDestinations.SETTINGS) }
            else { compose.onNodeWithTag("settingsButton").performClick(); compose.onNodeWithText("Settings").performClick() }
            compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
        }
        fun openEditor() {
            compose.onNodeWithTag("secondaryImportantContentEditor").performScrollTo().performClick()
            compose.onNodeWithText("Directions area").assertExists()
            compose.onNodeWithTag("liveGridOutput").assertDoesNotExist()
            compose.onNodeWithTag("liveGridMove").assertDoesNotExist()
        }
        fun startGrid(): ActivityController<LiveGridWindowActivity> {
            compose.onNodeWithTag("liveGridStart").performClick()
            lateinit var controller: ActivityController<LiveGridWindowActivity>
            compose.runOnIdle {
                val intent = requireNotNull(shadowOf(app).nextStartedActivity)
                controller = ActivityController.of(LiveGridWindowActivity(), intent,
                    ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle()).create().start().resume()
                grids += controller
                assertFalse(controller.get().isFinishing)
                val a = controller.get().window.attributes
                assertEquals("Preview background remains full panel", PixelRect(0,0,2000,810), PixelRect(a.x,a.y,a.x+a.width,a.y+a.height))
            }
            return controller
        }
        try {
            compose.setContent { if (mounted) {
                navigation = androidx.navigation.compose.rememberNavController()
                settings = androidx.lifecycle.viewmodel.compose.viewModel()
                projection = androidx.lifecycle.viewmodel.compose.viewModel(); created = true
                bindings = rememberProductionClusterSettingsBinding(app, preferences, sessionState,
                    session.clusterVideoStatus, projection.carPlayState, preview)
                androidx.compose.material3.MaterialTheme {
                    com.openautolink.app.ui.navigation.AppNavHost(clusterBindings = bindings,
                        navController = navigation, secondarySource = source)
                }
            } }
            await { created && settings.clusterLoaded.value && settings.carPlaySettingsLoaded.value &&
                settings.secondaryPreferences.value != null && bindings?.layout != null }
            openSettings(); openEditor()
            val first = startGrid()
            if (reset) compose.onNodeWithTag("liveGridReset").performClick()
            if (edit) {
                compose.onNodeWithContentDescription("Move directions area").performTouchInput {
                    down(center); moveBy(Offset(20f,0f)); moveBy(Offset(10f,0f)); up()
                }
            }
            var currentGrid = first
            if (edit) {
                compose.onNodeWithTag("liveGridStop").performClick()
                assertTrue(first.get().isFinishing)
                currentGrid = startGrid()
                compose.runOnIdle { first.pause().stop().destroy(); grids.remove(first) }
                assertFalse("A stale Activity destructor cannot retire its successor",currentGrid.get().isFinishing)
            }
            compose.onNodeWithTag("liveGridBack").performClick() // Done retires only this preview, not Save.
            assertTrue(currentGrid.get().isFinishing)
            assertEquals("Done is draft-only", before, preferences.getSecondaryDisplayPreferences())
            assertEquals("Public editor cannot rewrite legacy store", legacy, preferences.getClusterLayout(layoutScope, legacy.canvas).custom)
            coVerify(exactly = 0) { session.stop() }
            val staged = requireNotNull(settings.secondaryPreferences.value).outputs.getValue(descriptor.id).layout
            if (edit) assertNotEquals(outputLayout.directSafe, staged?.directSafe)
            if (reset) { assertTrue(requireNotNull(staged).directSafeAutomatic); assertNull(staged.directSafe) }
            if (staged != null) { assertEquals(outputLayout.output, staged.output); assertEquals(outputLayout.safe, staged.safe) }
            if (cancel) {
                compose.runOnIdle { mounted = false }; compose.waitForIdle()
                assertEquals(before, preferences.getSecondaryDisplayPreferences())
                assertFalse(receipts.any { it.contains("stage=reconnect_dispatch") })
                return@runBlocking
            }
            if (staleTarget) {
                // Reopen the current owner; a stale descriptor cannot launch or stage another revision.
                openEditor()
                compose.runOnIdle { inventory.value = SecondaryInventorySnapshot(2, emptyList()); rawRows.value = emptyList() }
                compose.onNodeWithTag("liveGridStart").assertIsNotEnabled()
                compose.onNodeWithTag("liveGridReset").assertIsNotEnabled()
                compose.onNodeWithTag("liveGridBack").performClick()
                assertEquals(staged, settings.secondaryPreferences.value!!.outputs.getValue(descriptor.id).layout)
                assertNull(shadowOf(app).nextStartedActivity)
                assertEquals(before, preferences.getSecondaryDisplayPreferences())
                return@runBlocking
            }
            // Failure injection happens only after the actual current editor Start/Done callback.
            if (failure == "layout") {
                val draft = requireNotNull(bindings?.layout).copy(safe = PixelRect(630,170,1360,660))
                compose.runOnIdle { requireNotNull(bindings).onStageLayout(draft) }
                compose.waitUntil(5000) { bindings?.layout == draft }
                coEvery { preferences.saveClusterLayout(any()) } throws java.io.IOException("secret fixture")
            }
            if (failure == "cluster") coEvery { preferences.updateClusterOutputState(any()) } throws java.io.IOException("secret fixture")
            if (failure == "carplay") {
                compose.runOnIdle { settings.updateCarPlayDraft { it.copy(ssid = "edited") } }
                coEvery { preferences.updateCarPlaySettings(any(),any()) } throws java.io.IOException("secret fixture")
            }
            if (failure == "secondary") coEvery { preferences.updateSecondaryDisplayPreferences(any(),any()) } throws java.io.IOException("secret fixture")
            if (failure == "cancelled") coEvery { preferences.updateSecondaryDisplayPreferences(any(),any()) } throws CancellationException("controlled cancellation")
            if (failure == "revision") {
                // A real competing writer advances the saved revision after this editor staged its draft.
                val competingCommit = preferences.updateSecondaryDisplayPreferences(before.revision,
                    mapOf(descriptor.id to before.outputs.getValue(descriptor.id).copy(aaRole = AaSecondaryRole.CLUSTER)))
                assertEquals(before.revision + 1, competingCommit.revision)
                assertEquals(AaSecondaryRole.CLUSTER, competingCommit.outputs.getValue(descriptor.id).aaRole)
            }
            compose.onNodeWithTag("saveAndConnectButton").performClick()
            if (failure != null) {
                compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle();
                    settings.carPlayApplyError.value.isNotEmpty() && !settings.clusterSaving.value }
                compose.onNodeWithTag("settingsApplyError").assertExists()
                compose.onNodeWithTag("saveAndConnectButton").assertExists()
                coVerify(exactly = 0) { session.stop() }
                assertFalse(settings.carPlayApplyError.value.contains("secret"))
                val failedStage = if (failure == "revision" || failure == "cancelled") "secondary" else failure
                val failedResult = if (failure == "cancelled") "cancelled" else "failed"
                assertTrue(receipts.any { it.contains("stage=$failedStage") && it.contains("result=$failedResult") })
                assertFalse(receipts.any { it.contains("stage=reconnect_dispatch") || it.contains("stage=close") || it.contains("secret fixture") })
                assertEquals(legacy, preferences.getClusterLayout(layoutScope, legacy.canvas).custom)
                if (failure in listOf("carplay", "secondary", "cancelled")) assertEquals(before, preferences.getSecondaryDisplayPreferences())
                if (failure == "revision") {
                    assertEquals(AaSecondaryRole.CLUSTER, preferences.getSecondaryDisplayPreferences().outputs.getValue(descriptor.id).aaRole)
                    assertEquals(outputLayout, preferences.getSecondaryDisplayPreferences().outputs.getValue(descriptor.id).layout)
                }
                assertEquals("Failed transaction retains exact staged Directions edit",staged,
                    settings.secondaryPreferences.value!!.outputs.getValue(descriptor.id).layout)
                return@runBlocking
            }
            compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle();
                receipts.any { it.contains("stage=close") } && !settings.clusterSaving.value }
            val durable = preferences.getSecondaryDisplayPreferences()
            val saved = durable.outputs.getValue(descriptor.id).layout
            assertEquals(staged, saved)
            assertEquals(outputLayout.output, requireNotNull(saved).output)
            assertEquals(outputLayout.safe, saved.safe)
            assertEquals(legacy, preferences.getClusterLayout(layoutScope, legacy.canvas).custom)
            val click = receipts.first { it.contains("stage=click") }.substringAfter("click=").substringBefore(" ")
            val chain = receipts.filter { it.contains("SETTINGS_SAVE_V1 click=$click ") }
            var previousAck = -1
            for (stage in listOf("carplay", "secondary", "cluster", "layout")) {
                val ack = chain.indexOfFirst { it.contains("stage=$stage result=success") }
                assertTrue("Actual $stage acknowledgment must follow prior stores: $chain", ack > previousAck)
                previousAck = ack
            }
            val reconnectIndex = chain.indexOfFirst { it.contains("stage=reconnect_dispatch") }
            assertTrue("Reconnect dispatch must follow every store acknowledgment: $chain", reconnectIndex > previousAck)
            // USB Save persists/closes but never restarts the physical connection.
            coVerify(exactly = 0) { session.stop() }
            preferences.setDirectTransport("hotspot")
            every { session.transportMode } returns MutableStateFlow("hotspot")
            compose.runOnIdle { sessionState.value = SessionState.STREAMING }
            var reconnects = 0
            var reconnectSnapshot: SecondaryPreferencesSnapshot? = null
            coEvery { session.reconnect(codecPreference=any(),micSourcePreference=any(),scalingMode=any(),directTransport=any(),wppInterfaceName=any(),hotspotSsid=any(),hotspotPassword=any(),videoAutoNegotiate=any(),aaResolution=any(),aaDpi=any(),aaAutoDpi=any(),aaWidthMargin=any(),aaHeightMargin=any(),aaPixelAspect=any(),aaTargetLayoutWidthDp=any(),aaViewingDistanceMm=any(),aaDecoderAdditionalDepth=any(),aaAutoMargins=any(),videoFps=any(),driveSide=any(),hideClock=any(),hideSignal=any(),hideBattery=any(),volumeOffsetMedia=any(),volumeOffsetNavigation=any(),volumeOffsetAssistant=any(),manualIpAddress=any(),safeAreaTop=any(),safeAreaBottom=any(),safeAreaLeft=any(),safeAreaRight=any(),gpsForwarding=any(),galVersion=any(),seedIdrThresholds=any()) } coAnswers {
                reconnectSnapshot = preferences.getSecondaryDisplayPreferences()
                reconnects++
            }
            // Menu reconnect must consume saved intent, not the uncommitted Restore default draft.
            compose.runOnIdle { navigation.navigate(com.openautolink.app.ui.navigation.AppDestinations.SETTINGS) }
            compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
            openEditor()
            compose.onNodeWithTag("liveGridReset").performClick()
            compose.onNodeWithTag("liveGridBack").performClick()
            compose.runOnIdle { navigation.popBackStack() }
            compose.onNodeWithTag("settingsButton").performClick()
            compose.onNodeWithText("Reconnect",substring=false).performClick()
            compose.waitUntil(10000) { shadowOf(Looper.getMainLooper()).idle(); reconnects == 1 }
            assertEquals(1,reconnects)
            assertEquals(durable,reconnectSnapshot)
            assertEquals(durable,preferences.getSecondaryDisplayPreferences())
            assertTrue(receipts.any { it.contains("source=menu commitsDraft=false") })
            // Actual protocol builders, real direct host/TextureView readiness and exact retirement.
            protocols.verify(requireNotNull(reconnectSnapshot), hostLoss) { receipts.toList() }
            assertEquals("Host loss cannot rewrite persisted intent", durable, preferences.getSecondaryDisplayPreferences())
            compose.runOnIdle { mounted = false }; compose.waitForIdle()
            settings.viewModelScope.cancel(); projection.viewModelScope.cancel(); storeJob.cancelAndJoin()
            coldJob = SupervisorJob()
            val cold = newPrefs(requireNotNull(coldJob))
            assertEquals(durable, cold.getSecondaryDisplayPreferences())
            assertEquals(legacy, cold.getClusterLayout(layoutScope, legacy.canvas).custom)
        } catch (failureThrown: Throwable) { primary = failureThrown; throw failureThrown }
        finally {
            // Never replace a missing-entrypoint/Save exception with a cold-read or cleanup assertion.
            suspend fun cleanup(block: suspend () -> Unit) {
                try { block() } catch (secondary: Throwable) {
                    if (primary == null) primary = secondary else if (primary !== secondary) primary!!.addSuppressed(secondary)
                }
            }
            val original = primary
            cleanup { compose.runOnIdle { mounted = false; if (created) { settings.viewModelScope.cancel(); projection.viewModelScope.cancel() } }; compose.waitForIdle() }
            for (grid in grids) cleanup { grid.pause().stop().destroy() }
            cleanup { protocols.close() }
            cleanup { closeCarPlayCallControllerTestProcessAndAwait() }
            cleanup { storeJob.cancelAndJoin(); coldJob?.cancelAndJoin() }
            cleanup { unmockkAll(); file.delete(); ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", product) }
            cleanup { if (activityContexts == null) System.clearProperty("robolectric.createActivityContexts")
                else System.setProperty("robolectric.createActivityContexts", activityContexts) }
            if (original == null) primary?.let { throw it }
        }
    }
}
