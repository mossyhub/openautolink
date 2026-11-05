package com.openautolink.app.ui.settings

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.first
import com.openautolink.app.cluster.*
import com.openautolink.app.carplay.CarPlayUiState
import com.openautolink.app.carplay.CarPlayClusterStatus
import com.openautolink.app.session.SessionState
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CompletableFuture

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class ProductionClusterSettingsBindingTest {
    @get:Rule val compose = createOalComposeRule()

    @Test fun pendingThenFailedOptionalLayoutDoesNotGateProductionNavigationOrOrdinarySave() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val storeJob = SupervisorJob()
        val file = File(app.filesDir, "isolated-nav-${java.util.UUID.randomUUID()}.preferences_pb")
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = CoroutineScope(storeJob + Dispatchers.IO), produceFile = { file })
        val preferences = io.mockk.spyk(AppPreferences::class.java
            .getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store))
        preferences.setCarPlaySettings(com.openautolink.app.data.CarPlaySettings(enabled = false))
        preferences.setDirectTransport("usb")
        val readEntered = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val readFailed = CompletableDeferred<Unit>()
        val session = io.mockk.mockk<com.openautolink.app.session.SessionManager>(relaxed = true)
        val sessionState = MutableStateFlow(SessionState.IDLE)
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val display = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role, 7)),
            ClusterHostGeometry(7, profile.panel, profile.panel.bounds, profile.panel.bounds)))
        mockkObject(AppPreferences.Companion, com.openautolink.app.session.SessionManager.Companion,
            com.openautolink.app.transport.PhoneDiscovery.Companion, ClusterProjectionOutput.Companion,
            com.openautolink.app.carplay.CarPlaySessionLeases.Companion,
            com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        every { ClusterProjectionOutput.qualifiedInitialDisplay(any()) } returns display
        io.mockk.coEvery { preferences.getClusterLayout(any(), any()) } coAnswers {
            readEntered.complete(Unit)
            releaseRead.await()
            readFailed.complete(Unit)
            throw java.io.IOException("optional layout read failed")
        }
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
        var viewModelsCreated = false
        lateinit var navigation: androidx.navigation.NavHostController
        lateinit var settings: SettingsViewModel
        lateinit var projection: com.openautolink.app.ui.projection.ProjectionViewModel
        val preview = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ ->
            starts.incrementAndGet()
            error("Optional initialization must not start a preview")
        }, { 0L }, { _, _ -> {} })
        try {
            compose.setContent {
                if (mounted) {
                    navigation = androidx.navigation.compose.rememberNavController()
                    settings = androidx.lifecycle.viewmodel.compose.viewModel()
                    projection = androidx.lifecycle.viewmodel.compose.viewModel()
                    viewModelsCreated = true
                    androidx.compose.material3.MaterialTheme {
                        com.openautolink.app.ui.navigation.AppNavHost(
                            clusterPreviewFactory = { _, _ -> preview }, navController = navigation)
                    }
                }
            }
            compose.waitUntil(5000) { readEntered.isCompleted }
            // RED: the old top-level entrypoint renders only a loading label, not this real UI.
            compose.onNodeWithTag("projectionScreen").assertExists()
            compose.waitUntil(5000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                settings.carPlaySettingsLoaded.value && settings.clusterLoaded.value
            }
            // Exercise the real projection overlay while the optional preference read is held.
            compose.onNodeWithTag("settingsButton").performClick()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settingsScreen").assertIsDisplayed()
            compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
            compose.onNodeWithTag("clusterSettingsUnavailable").assertDoesNotExist()
            compose.onNodeWithTag("secondaryImportantContentEditor").assertDoesNotExist()
            compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertExists()
            compose.onNodeWithTag("adjustClusterLayout").assertDoesNotExist()
            compose.onNodeWithTag("clusterDisplay_NATIVE_ICONS").assertDoesNotExist()
            compose.onNodeWithTag("settingsTab_DIAGNOSTICS").performScrollTo().performClick()
            compose.onNodeWithTag("openDiagnosticsButton").performScrollTo().performClick()
            compose.onNodeWithTag("diagnosticsScreen").assertIsDisplayed()
            compose.onNode(hasContentDescription("Back") and
                hasAnyAncestor(hasTestTag("diagnosticsScreen"))).performClick()
            compose.onNodeWithTag("projectionScreen").assertExists()
            // The standalone route shares the same still-pending owner.
            compose.runOnIdle { navigation.navigate(com.openautolink.app.ui.navigation.AppDestinations.SETTINGS) }
            compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
            compose.runOnIdle { settings.updateCarPlayDraft { it.copy(ssid = "Pending save survives") } }
            compose.onNodeWithTag("saveAndConnectButton").assertIsEnabled().performClick()
            compose.waitUntil(10000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                navigation.currentDestination?.route == com.openautolink.app.ui.navigation.AppDestinations.PROJECTION
            }
            assertEquals("Pending save survives", preferences.carPlaySettings.first().ssid)
            assertFalse(releaseRead.isCompleted)
            compose.runOnIdle { navigation.navigate(com.openautolink.app.ui.navigation.AppDestinations.SETTINGS) }
            releaseRead.complete(Unit)
            compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
            // Optional legacy adapter failure is not rendered as the current per-output pane.
            compose.waitUntil(5000) { shadowOf(android.os.Looper.getMainLooper()).idle();
                readFailed.isCompleted && compose.onAllNodesWithTag("clusterDisplay_MAP_VIDEO").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("clusterSettingsUnavailable").assertDoesNotExist()
            compose.onNodeWithTag("secondaryImportantContentEditor").assertDoesNotExist()
            compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").assertExists()
            compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
            compose.runOnIdle { settings.updateCarPlayDraft { it.copy(ssid = "Ordinary save survives") } }
            compose.onNodeWithTag("saveAndConnectButton").assertIsEnabled().performClick()
            compose.waitUntil(10000) {
                shadowOf(android.os.Looper.getMainLooper()).idle()
                navigation.currentDestination?.route == com.openautolink.app.ui.navigation.AppDestinations.PROJECTION
            }
            assertEquals("Ordinary save survives", preferences.carPlaySettings.first().ssid)
            compose.onNodeWithTag("projectionScreen").assertExists()
            compose.runOnIdle { navigation.navigate(com.openautolink.app.ui.navigation.AppDestinations.SETTINGS) }
            compose.onNodeWithTag("settingsTab_DIAGNOSTICS").performScrollTo().performClick()
            compose.onNodeWithTag("openDiagnosticsButton").performScrollTo().performClick()
            compose.onNodeWithTag("diagnosticsScreen").assertIsDisplayed()
            assertEquals(0, starts.get())
            assertEquals(SessionState.IDLE, sessionState.value)
            io.mockk.coVerify(exactly = 1) { preferences.getClusterLayout(any(), any()) }
            io.mockk.coVerify(exactly = 0) { session.stop() }
            io.mockk.coVerify(exactly = 0) { session.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            io.mockk.coVerify(exactly = 0) { session.reconnect(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        } finally {
            releaseRead.complete(Unit)
            compose.runOnIdle {
                mounted = false
                if (viewModelsCreated) {
                    settings.viewModelScope.cancel()
                    projection.viewModelScope.cancel()
                }
            }
            unmockkObject(AppPreferences.Companion, com.openautolink.app.session.SessionManager.Companion,
                com.openautolink.app.transport.PhoneDiscovery.Companion, ClusterProjectionOutput.Companion,
                com.openautolink.app.carplay.CarPlaySessionLeases.Companion,
                com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.Companion)
            storeJob.cancelAndJoin()
            file.delete()
        }
    }

    @Test fun productionEntrypointsSelectTheRuntimeFactoryRatherThanUnavailableBindings() {
        val root = File(System.getProperty("user.dir"))
        val main = File(root, "src/main/java/com/openautolink/app/MainActivity.kt").readText()
        val nav = File(root, "src/main/java/com/openautolink/app/ui/navigation/AppNavHost.kt").readText()
        assertFalse(main.contains("unavailableClusterSettingsBindings"))
        assertTrue(main.contains("ClusterCalibrationPreview.forProduction(context, projectionIdle)"))
        assertTrue(nav.contains("rememberProductionClusterSettingsBinding("))
        assertTrue(nav.contains("manager.clusterVideoStatus"))
        assertTrue(nav.contains("projectionViewModel.carPlayState, preview"))
    }

    @Test fun unqualifiedProductionDisplayNeverInventsLayoutHostOrSurface() {
        val app = RuntimeEnvironment.getApplication()
        mockkObject(ClusterProjectionOutput.Companion)
        every { ClusterProjectionOutput.qualifiedInitialDisplay(app) } returns null
        val preview = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ ->
            error("An unqualified display must not start a preview")
        }, { 0L }, { _, _ -> {} })
        val session = MutableStateFlow(SessionState.IDLE)
        val aa = MutableStateFlow(ClusterProjectionStatus())
        val cp = MutableStateFlow(CarPlayUiState())
        var mounted by mutableStateOf(true)
        var bound: ClusterSettingsBindings? = null
        try {
            compose.setContent {
                if (mounted) bound = rememberProductionClusterSettingsBinding(
                    app, AppPreferences.getInstance(app), session, aa, cp, preview)
            }
            compose.waitUntil(5000) { shadowOf(android.os.Looper.getMainLooper()).idle(); bound != null }
            assertNull(bound?.layout)
            assertNull(bound?.automaticLayout)
            assertNull(bound?.status?.profile)
            assertNull(bound?.status?.host)
            assertNull(bound?.actualSurface)
        } finally {
            compose.runOnIdle { mounted = false }
            unmockkObject(ClusterProjectionOutput.Companion)
        }
    }

    @Test @Config(shadows = [LiveGridWindowActivityTest.Admission::class])
    fun productionFlowOwnerReplacementRetiresOldGridAndUsesCurrentIdlePredicate() {
        val app = RuntimeEnvironment.getApplication()
        val id = org.robolectric.shadows.ShadowDisplayManager.addDisplay("w2000dp-h810dp-mdpi", "Grid factory fixture")
        val physical = app.getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(id)
        shadowOf(physical).setFlags(android.view.Display.FLAG_PRESENTATION)
        shadowOf(physical).setState(android.view.Display.STATE_ON)
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val display = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role, id)), ClusterHostGeometry(id, profile.panel, profile.panel.bounds, profile.panel.bounds)))
        val descriptor = com.openautolink.app.secondary.SecondaryDisplayDescriptor(
            com.openautolink.app.secondary.SecondaryOutputId("factory-fixture"), id, 1, "Grid factory fixture", 2000, 810, 160,
            com.openautolink.app.secondary.SecondaryRoute.GM_HOSTED, com.openautolink.app.secondary.SecondaryReason.NONE, "Fixture only")
        val producer = io.mockk.mockk<com.openautolink.app.secondary.SecondaryDisplayPolicyInventory>()
        every { producer.snapshots } returns MutableStateFlow(com.openautolink.app.secondary.SecondaryInventorySnapshot(1, listOf(descriptor)))
        mockkObject(ClusterProjectionOutput.Companion, com.openautolink.app.secondary.SecondaryDisplayPolicyInventory.Companion)
        every { ClusterProjectionOutput.qualifiedInitialDisplay(any()) } returns display
        every { com.openautolink.app.secondary.SecondaryDisplayPolicyInventory.get(any()) } returns producer
        var session by mutableStateOf<kotlinx.coroutines.flow.StateFlow<SessionState>>(MutableStateFlow(SessionState.IDLE))
        val aa = MutableStateFlow(ClusterProjectionStatus())
        val cp = MutableStateFlow(CarPlayUiState())
        val preview = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ -> error("Hosted path must remain unused") }, { 0L }, { _, _ -> {} })
        var bound: ClusterSettingsBindings? = null
        var mounted by mutableStateOf(true)
        try {
            compose.setContent { if (mounted) bound = rememberProductionClusterSettingsBinding(app,
                AppPreferences.getInstance(app), session, aa, cp, preview) }
            compose.waitUntil(5000) { shadowOf(android.os.Looper.getMainLooper()).idle(); bound?.liveGrid != null }
            val original = requireNotNull(bound?.liveGrid)
            val geometry = requireNotNull(bound?.layout).gridGeometry()
            compose.runOnIdle { original.start(geometry, 15) }
            assertNotNull(shadowOf(app).nextStartedActivity)
            compose.runOnIdle { session = MutableStateFlow(SessionState.CONNECTING) }
            compose.waitUntil(5000) { shadowOf(android.os.Looper.getMainLooper()).idle(); bound?.liveGrid?.owner !== original.owner && bound?.liveGrid != null }
            compose.runOnIdle {
                assertFalse(original.update(geometry))
                try { requireNotNull(bound?.liveGrid).start(geometry, 15); fail("New active session must withhold the grid") }
                catch (failure: IllegalStateException) { assertTrue(requireNotNull(failure.message).contains("Disconnect your phone to preview on driver display.")) }
            }
            assertNull(shadowOf(app).nextStartedActivity)
        } finally {
            compose.runOnIdle { mounted = false }; compose.waitForIdle()
            unmockkObject(ClusterProjectionOutput.Companion, com.openautolink.app.secondary.SecondaryDisplayPolicyInventory.Companion)
        }
    }

    @Test fun productionBindingRestoresRealPreferencesTracksReceiptsAndRetiresPreviewOnExit() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val file = File(app.filesDir, "production-cluster-${java.util.UUID.randomUUID()}.preferences_pb")
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file })
        val preferences = AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val display = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role, 7)), ClusterHostGeometry(7, profile.panel, profile.panel.bounds, profile.panel.bounds)))
        mockkObject(ClusterProjectionOutput.Companion)
        every { ClusterProjectionOutput.qualifiedInitialDisplay(app) } returns display
        val aa = MutableStateFlow(ClusterProjectionStatus())
        val session = MutableStateFlow(SessionState.IDLE)
        val cp = MutableStateFlow(CarPlayUiState())
        var starts = 0
        var stops = 0
        val preview = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ ->
            starts++
            object : ClusterCalibrationPreview.Session {
                override fun stop() = CompletableFuture.completedFuture(true).also { stops++ }
            }
        }, { 0L }, { _, _ -> {} })
        var mounted by mutableStateOf(true)
        var bound: ClusterSettingsBindings? = null
        try {
            compose.setContent {
                if (mounted) bound = rememberProductionClusterSettingsBinding(app, preferences, session, aa, cp, preview)
            }
            compose.waitUntil(5000) { shadowOf(android.os.Looper.getMainLooper()).idle(); bound?.layout != null }
            val automatic = requireNotNull(bound?.layout)
            assertNull(bound?.status?.source)
            assertNull(bound?.status?.host)
            assertNull(bound?.actualSurface)
            assertEquals(0, starts)
            val draft = automatic.copy(safe = PixelRect(630, 170, 1360, 660))
            compose.runOnIdle { requireNotNull(bound).onStageLayout(draft) }
            compose.waitUntil(5000) { bound?.layout == draft }
            assertTrue(requireNotNull(bound).commitLayoutDraft())
            aa.value = ClusterProjectionStatus(received = 7, decoded = 3, presented = 2)
            compose.waitUntil(5000) { bound?.status?.phoneStream?.contains("7 packet") == true }
            assertNull(bound?.status?.source) // saved map mode or old packet counts do not activate a source
            cp.value = CarPlayUiState(selected = true, cluster = CarPlayClusterStatus(
                phoneStreamActive = false, output = ClusterProjectionStatus(received = 11, presented = 4)))
            compose.waitUntil(5000) { bound?.status?.phoneStream?.contains("11 packet") == true }
            assertTrue(requireNotNull(bound?.status?.source).contains("not active"))
            assertNull(bound?.actualSurface)
            compose.runOnIdle { requireNotNull(bound).onStartPreview(draft, 15) }
            compose.waitUntil(5000) { bound?.previewActive == true }
            compose.runOnIdle { mounted = false }
            compose.waitUntil(5000) { compose.waitForIdle(); stops == 1 }
            compose.runOnIdle { mounted = true; bound = null }
            compose.waitUntil(5000) { shadowOf(android.os.Looper.getMainLooper()).idle(); bound?.layout == draft }
            assertTrue(requireNotNull(bound).isCustomLayout)
            assertEquals(1, starts) // restoration never starts a preview
        } finally {
            compose.runOnIdle { mounted = false }
            unmockkObject(ClusterProjectionOutput.Companion)
            storeScope.cancel(); storeScope.coroutineContext[Job]?.join(); file.delete()
        }
    }
}
