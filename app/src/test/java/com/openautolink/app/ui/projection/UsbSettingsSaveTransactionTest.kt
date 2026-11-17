package com.openautolink.app.ui.projection

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import com.openautolink.app.ui.navigation.rememberProductionSettingsSaveAction
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.carplay.*
import com.openautolink.app.data.*
import com.openautolink.app.session.*
import com.openautolink.app.transport.PhoneDiscovery
import com.openautolink.app.ui.settings.*
import com.openautolink.app.secondary.*
import com.openautolink.app.displays.DiscoveredSecondaryDisplay
import com.openautolink.app.ui.testing.createOalComposeRule
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Real Settings button -> awaited DataStore/draft transaction -> production transport dispatch.
 * SessionManager is the downstream transport boundary; no USB/native device is opened by this gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class UsbSettingsSaveTransactionTest {
    @get:Rule val compose = createOalComposeRule()
    @get:Rule val temp = org.junit.rules.TemporaryFolder()
    private val storeJob = SupervisorJob()
    private lateinit var preferences: AppPreferences
    private lateinit var settings: SettingsViewModel
    private lateinit var projection: ProjectionViewModel
    private val secondarySource by lazy {
        val id = SecondaryOutputId("usb-save-ui-display")
        val display = SecondaryDisplayDescriptor(id, 2, 1, "Driver display", 2000, 810, 160,
            SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Driver")
        SecondaryDisplaysSource(MutableStateFlow(SecondaryInventorySnapshot(1, listOf(display))),
            MutableStateFlow(listOf(DiscoveredSecondaryDisplay(id.value, 2, display.name, 2000, 810,
                true, true, false, null))), null)
    }
    private val aaState = MutableStateFlow(SessionState.STREAMING)
    private val transport = MutableStateFlow("usb")
    private val sessionEpoch = MutableStateFlow(1L)
    private val session = mockk<SessionManager>(relaxed = true)
    private var closes = 0
    private var projectionRoot: android.view.View? = null
    private fun captureProjection(name: String) {
        val dir = System.getProperty("banner.render.dir").orEmpty()
        if (dir.isEmpty()) return
        compose.runOnIdle {
            val root = checkNotNull(projectionRoot)
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            java.io.File(dir).mkdirs()
            java.io.File(dir, "sdk${android.os.Build.VERSION.SDK_INT}-$name").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    private val startCount = java.util.concurrent.atomic.AtomicInteger()
    private val reconnectCount = java.util.concurrent.atomic.AtomicInteger()
    private val reconnects: Int get() = reconnectCount.get()
    private var layoutSaveSuccessful = true
    private val layoutCommitCount = java.util.concurrent.atomic.AtomicInteger()
    private fun field(name: String): Any? = projection.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(projection)
    private fun setup(configuredAa: String = "usb", cp: CarPlaySettings = CarPlaySettings(enabled = false),
                      activeAa: SessionState = SessionState.STREAMING, actualAa: String = "usb",
                      override: UsbHotPlugSwitchPolicy.Protocol? = null, renderProjection: Boolean = false, renderReconnect: Boolean = false) = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + storeJob),
            produceFile = { java.io.File(temp.root, "settings.preferences_pb") })
        preferences = AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        preferences.setDirectTransport(configuredAa)
        preferences.setCarPlaySettings(cp)
        mockkObject(AppPreferences.Companion, SessionManager.Companion, PhoneDiscovery.Companion,
            CarPlaySessionLeases.Companion, CarPlayHostMediaLeaseSource.Companion)
        every { CarPlaySessionLeases.forContext(any()) } returns CarPlaySessionLeaseSource.NONE
        every { CarPlayHostMediaLeaseSource.forContext(any()) } returns CarPlayHostMediaLeaseSource.NONE
        every { AppPreferences.getInstance(any()) } returns preferences
        aaState.value = activeAa; transport.value = actualAa
        every { session.sessionState } returns aaState
        every { session.settingsSessionEpoch } returns sessionEpoch
        every { session.transportMode } returns transport
        every { session.reconnectAttempt } returns MutableStateFlow(0)
        every { session.wakeEvents } returns MutableSharedFlow()
        every { session.videoStats } returns null
        every { session.audioStats } returns null
        every { session.callState } returns null
        every { SessionManager.getInstance(any(), any(), any()) } returns session
        every { SessionManager.instanceOrNull() } returns session
        every { session.videoDecoder } returns null
        every { session.statusMessage } returns MutableStateFlow("")
        every { session.currentManeuver } returns MutableStateFlow(null)
        every { session.phoneBatteryLevel } returns MutableStateFlow(null)
        every { session.phoneBatteryCritical } returns MutableStateFlow(false)
        every { session.voiceSessionActive } returns MutableStateFlow(false)
        every { session.phoneSignalStrength } returns MutableStateFlow(null)
        every { session.wifiFrequencyMhz } returns MutableStateFlow(0)
        every { session.effectiveDpi } returns MutableStateFlow(160)
        coEvery { session.reconnect(any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers { reconnectCount.incrementAndGet(); Unit }
        coEvery { session.start(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            if (arg<() -> Boolean>(35).invoke()) startCount.incrementAndGet()
            Unit
        }
        val discovery = mockk<PhoneDiscovery>(relaxed = true) {
            every { phones } returns MutableStateFlow(emptyList())
            every { isSweeping } returns MutableStateFlow(false)
            every { sweepProgress } returns MutableStateFlow("")
        }
        every { PhoneDiscovery.getInstance(any()) } returns discovery
        projection = ProjectionViewModel(app)
        // Attach recording to this actual instance; do not copy eager coroutine writers,
        // or use an overloaded-constructor prototype as the receiver verification identity.
        settings = SettingsViewModel(app)
        mockkObject(settings)
        if (override != null) {
            val policy = field("usbSwitchPolicy") as UsbHotPlugSwitchPolicy
            check(policy.accept(550L, { override }, { true }, {}, {}, { true }, {}, {}))
        }
        clearMocks(session, answers = false, recordedCalls = true)
        if (renderProjection) {
            // Match the admitted USB session fixture at the OS inventory boundary; otherwise the
            // real disconnected picker covers the notice and correctly intercepts its touch.
            mockkObject(com.openautolink.app.transport.usb.UsbConnectionManager.Companion)
            every { com.openautolink.app.transport.usb.UsbConnectionManager.connectionState } returns
                MutableStateFlow(com.openautolink.app.transport.usb.UsbConnectionState.CONNECTED)
            every { com.openautolink.app.transport.usb.UsbConnectionManager.availableDevices } returns MutableStateFlow(emptyList())
            every { com.openautolink.app.transport.usb.UsbConnectionManager.status } returns MutableStateFlow("Connected")
        }
        compose.setContent { MaterialTheme {
            projectionRoot = androidx.compose.ui.platform.LocalView.current.rootView
            var open by remember { mutableStateOf(true) }
            val saveAction = rememberProductionSettingsSaveAction(settings, projection, {
                layoutCommitCount.incrementAndGet()
                layoutSaveSuccessful
            }) { closes++; open = false }
            if (renderReconnect) com.openautolink.app.ui.components.ProjectionOverlayControls(
                ProjectionUiState(), {}, {}, com.openautolink.app.ui.navigation.rememberProductionProjectionReconnectAction(settings, projection))
            else if (open || !renderProjection) SettingsScreen(clusterBindings = com.openautolink.app.ui.settings.clusterSettingsFixture(), viewModel = settings, secondarySource = secondarySource, onSaveAndConnect = saveAction)
            else ProjectionScreen(viewModel = projection)
        } }
        awaitMainOutcome(5000) { settings.carPlaySettingsLoaded.value && settings.clusterLoaded.value && settings.secondaryPreferences.value != null }
        if (renderReconnect) return@runBlocking
        compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").performScrollTo().performClick()
        compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").performScrollTo().performClick()
        assertEquals(com.openautolink.app.cluster.ClusterDisplayMode.NATIVE_ICONS,
            runBlocking { preferences.getClusterOutputSelection().mode })
        compose.runOnIdle { settings.updateCarPlayDraft { it.copy(ssid = "Saved receiver") } }
    }
    private fun save(expectReconnect: Boolean = false) {
        compose.onNodeWithTag("saveAndConnectButton").performClick()
        awaitSave(expectReconnect)
    }
    private fun awaitMainOutcome(timeoutMillis: Long, condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis) {
            // DataStore/render-worker completion can leave the production continuation queued on Main.
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            compose.waitForIdle()
            condition()
        }
    }
    private fun awaitSave(expectReconnect: Boolean) {
        // Closing Settings acknowledges persistence, not the IO retirement or its Main callback.
        awaitMainOutcome(10000) {
            closes == 1 && if (expectReconnect) reconnects == 1
            else projection.settingsSaveMessage.value != null
        }
        assertEquals(1, layoutCommitCount.get())
        verify(exactly = if (expectReconnect) 1 else 0) { settings.saveAndReconnect() }
        assertEquals("Saved receiver", runBlocking { preferences.carPlaySettings.first().ssid })
        assertEquals(com.openautolink.app.cluster.ClusterDisplayMode.MAP_VIDEO,
            runBlocking { preferences.getClusterOutputSelection().mode })
    }
    private fun assertUsbUntouched() {
        assertEquals("USB Save must not initiate automatic reconnect", 0, reconnects)
        coVerify(exactly = 0) { session.stop() }
        verify(exactly = 0) { session.externalUsbTransport = any() }
        assertEquals(SessionState.STREAMING, aaState.value)
    }
    private suspend fun reconcilePolicy(): Boolean = kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn { continuation ->
        val method = ProjectionViewModel::class.java.getDeclaredMethod("reconcileWiredOnly", kotlin.coroutines.Continuation::class.java)
            .apply { isAccessible = true }
        method.invoke(projection, continuation)
    }

    @Test fun wiredOnlyFailedExactWirelessRetirementSurvivesIdleAndOffUntilRetryAcknowledges() = runBlocking {
        setup(configuredAa = "wpp", actualAa = "wpp", activeAa = SessionState.IDLE)
        val ownerScope = CoroutineScope(SupervisorJob())
        val real = SessionManager(ownerScope)
        fun nativeField(name: String) = SessionManager::class.java.getDeclaredField(name).apply { isAccessible = true }
        val native = mockk<com.openautolink.app.transport.aasdk.AasdkSession>(relaxed = true)
        every { native.transportMode } returns "wpp"
        var fail = true
        var stops = 0
        every { native.stop() } answers { stops++; if (fail) error("held exact native retirement") }
        nativeField("aasdkSession").set(real, native)
        SessionManager::class.java.getDeclaredMethod("installWirelessSessionAdmission", String::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(real, "wpp", "fixture-interface")
        assertTrue("fixture must enter with the real process WPP owner token", real.hasCurrentWppOwner())
        assertNotNull(nativeField("wirelessSessionAdmission").get(real))
        @Suppress("UNCHECKED_CAST")
        (nativeField("_transportMode").get(real) as MutableStateFlow<String>).value = "wpp"
        @Suppress("UNCHECKED_CAST")
        (nativeField("_sessionState").get(real) as MutableStateFlow<SessionState>).value = SessionState.STREAMING
        ProjectionViewModel::class.java.getDeclaredField("sessionManager").apply { isAccessible = true }.set(projection, real)
        val policy = preferences.projectionConnectionPolicy
        try {
            preferences.setWiredOnly(true)
            assertFalse("first live retirement must reach the exact failing native stop: stops=$stops state=${real.sessionState.value} policy=${policy.state.value}", reconcilePolicy())
            assertEquals(SessionState.IDLE, real.sessionState.value)
            assertFalse(real.hasCurrentWppOwner())
            assertNull(nativeField("wirelessSessionAdmission").get(real))
            assertSame(native, nativeField("retiringAasdkSession").get(real))
            preferences.setWiredOnly(false)
            assertFalse("OFF must retry, not forgive an IDLE owner whose native stop failed", reconcilePolicy())
            assertEquals(2, stops)
            assertTrue(policy.state.value.retirementRequired)
            assertFalse(policy.wirelessAllowed)
            fail = false
            assertTrue(reconcilePolicy())
            assertEquals(3, stops)
            assertNull(nativeField("retiringAasdkSession").get(real))
            assertTrue(policy.wirelessAllowed)
            val usb = mockk<com.openautolink.app.transport.aasdk.AasdkSession>(relaxed = true)
            every { usb.transportMode } returns "usb"
            nativeField("aasdkSession").set(real, usb)
            real.externalUsbTransport = true
            @Suppress("UNCHECKED_CAST")
            (nativeField("_transportMode").get(real) as MutableStateFlow<String>).value = "usb"
            @Suppress("UNCHECKED_CAST")
            (nativeField("_sessionState").get(real) as MutableStateFlow<SessionState>).value = SessionState.STREAMING
            preferences.setWiredOnly(true)
            assertTrue(reconcilePolicy())
            assertSame("actual current USB native owner must survive reconciliation", usb, nativeField("aasdkSession").get(real))
            assertEquals(SessionState.STREAMING, real.sessionState.value)
            verify(exactly = 0) { usb.stop() }
        } finally {
            fail = false; real.stop(); ownerScope.cancel()
            (nativeField("scope").get(real) as CoroutineScope).cancel()
        }
    }

    @Test fun wiredOnlyRevocationBeforeListenerStartRejectsActualNewWirelessResources() {
        setup(configuredAa = "wpp", actualAa = "wpp", activeAa = SessionState.IDLE)
        WirelessRuntimePolicyFixture(RuntimeEnvironment.getApplication()).use { fixture ->
            every { fixture.receiver.authorizeControllerEnrollment(any()) } answers {
                preferences.projectionConnectionPolicy.observe(true)
            }
            val result = runCatching { fixture.runtime.run() }
            assertTrue("new listener must fail policy admission", result.isFailure)
            verify(exactly = 0) { fixture.receiver.start(any<com.openautolink.carplaylab.network.CarPlayNetworkSelection>()) }
        }
    }

    @Test fun wiredOnlyKeepsActualWirelessRuntimeLoopDuringCallAndIdleOwnerRetiresIt() {
        setup(configuredAa = "wpp", actualAa = "wpp", activeAa = SessionState.IDLE)
        val call = MutableStateFlow(com.openautolink.app.audio.CallState.IN_CALL)
        every { session.callState } returns call
        val policy = preferences.projectionConnectionPolicy
        policy.observe(false)
        WirelessRuntimePolicyFixture(RuntimeEnvironment.getApplication()).use { fixture ->
            val coordinator = field("carPlayCoordinator") as CarPlayCoordinator
            coordinator.attachSurface(mockk())
            try {
                assertTrue(coordinator.connect(true) { fixture.runtime })
                awaitMainOutcome(10000) { fixture.entered.count == 0L || fixture.failure.get() != null }
                assertTrue("actual runConnection reaches runtime loop: ${projection.carPlayState.value.status}; ${fixture.failure.get()}", fixture.entered.count == 0L)
                runBlocking { preferences.setWiredOnly(true) }
                runBlocking { assertFalse(reconcilePolicy()) }
                assertTrue(projection.carPlayState.value.selected)
                val before = fixture.iterations.get()
                every { fixture.receiver.bluetoothHandoffRequested } returns true
                fixture.resume.countDown()
                awaitMainOutcome(5000) { fixture.iterations.get() >= before + 4 || fixture.failure.get() != null }
                assertNull("policy revocation must not terminate the actual admitted runtime loop", fixture.failure.get())
                assertTrue("actual Bluetooth bootstrap handoff must retire while wireless runtime continues", fixture.bootstrapRetired.get())
                assertTrue("policy revocation must not independently finish an admitted call runtime", projection.carPlayState.value.selected)
                assertTrue("the real loop must continue, not merely leave selected UI stale", fixture.iterations.get() >= before + 4)
                call.value = com.openautolink.app.audio.CallState.IDLE
                val retired = CoroutineScope(Dispatchers.IO).async { reconcilePolicy() }
                awaitMainOutcome(10000) { retired.isCompleted }
                assertTrue(runBlocking { retired.await() })
                assertFalse(projection.carPlayState.value.selected)
                verify(exactly = 1) { fixture.receiver.close() }
            } finally { fixture.resume.countDown(); coordinator.close() }
        }
    }

    @Test fun wiredOnlyRenderedSavePersistsWithoutChangingSavedNormalModesOrLiveUsb() {
        setup(configuredAa = "wpp", actualAa = "usb", cp = CarPlaySettings(enabled = false, connectionMode = "wireless"))
        compose.onNodeWithTag("settingsTab_CONNECTION").performScrollTo().performClick()
        compose.onNodeWithTag("wiredOnlyProjection").performScrollTo().assertIsOff().performClick()
        val store = preferences.javaClass.getDeclaredField("dataStore").apply { isAccessible = true }
            .get(preferences) as androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
        assertNull(runBlocking { store.data.first()[androidx.datastore.preferences.core.booleanPreferencesKey("wired_only")] })
        save()
        assertEquals(true, runBlocking { store.data.first()[androidx.datastore.preferences.core.booleanPreferencesKey("wired_only")] })
        assertEquals("wpp", runBlocking { preferences.directTransport.first() })
        assertFalse(runBlocking { preferences.carPlaySettings.first().enabled })
        assertEquals("wireless", runBlocking { preferences.carPlaySettings.first().connectionMode })
        assertUsbUntouched()
    }

    @Test fun wiredOnlySaveRetiresLiveWirelessBeforeEnabledUsbCarPlayDeferral() {
        setup(configuredAa = "wpp", actualAa = "wpp", cp = CarPlaySettings(enabled = true, connectionMode = "usb"))
        coEvery { session.stop() } coAnswers { aaState.value = SessionState.IDLE }
        compose.onNodeWithTag("settingsTab_CONNECTION").performScrollTo().performClick()
        compose.onNodeWithTag("wiredOnlyProjection").performScrollTo().performClick()
        save()
        coVerify(exactly = 1) { session.stop() }
        coVerify(exactly = 1) { session.stopWirelessPresence() }
        assertFalse(preferences.projectionConnectionPolicy.wirelessAllowed)
        assertEquals("wpp", runBlocking { preferences.directTransport.first() })
        assertEquals(0, reconnects)
    }

    @Test fun wiredOnlySaveRetiresIdleButStillOwnedWppListener() {
        setup(configuredAa = "wpp", actualAa = "wpp", activeAa = SessionState.IDLE,
            cp = CarPlaySettings(enabled = true, connectionMode = "usb"))
        every { session.hasCurrentWppOwner() } returns true
        compose.onNodeWithTag("settingsTab_CONNECTION").performScrollTo().performClick()
        compose.onNodeWithTag("wiredOnlyProjection").performScrollTo().performClick()
        save()
        coVerify(exactly = 1) { session.stop() }
        assertFalse(preferences.projectionConnectionPolicy.wirelessAllowed)
        assertEquals(0, reconnects)
    }

    @Test fun wiredOnlyOffSaveRestoresUnchangedSavedWppOnce() {
        setup(configuredAa = "wpp", actualAa = "wpp", activeAa = SessionState.IDLE)
        compose.onNodeWithTag("settingsTab_CONNECTION").performScrollTo().performClick()
        compose.onNodeWithTag("wiredOnlyProjection").performScrollTo().performClick()
        save()
        startCount.set(0)
        compose.onNodeWithTag("wiredOnlyProjection").performScrollTo().assertIsOn().performClick()
        compose.onNodeWithTag("saveAndConnectButton").performClick()
        awaitMainOutcome(10000) { closes == 2 && startCount.get() == 1 }
        assertEquals(1, startCount.get())
        assertEquals(0, reconnects)
        verify(exactly = 0) { settings.saveAndReconnect() }
        assertEquals("wpp", runBlocking { preferences.directTransport.first() })
        assertFalse(runBlocking { preferences.wiredOnly.first() })
        assertTrue(preferences.projectionConnectionPolicy.wirelessAllowed)
    }

    @Test fun actualRenderedAaReconnectDoesNotRequireRetiredAndroidxMapRetention() {
        setup(configuredAa="wpp",actualAa="wpp",renderReconnect=true)
        val before=runBlocking { preferences.getSecondaryDisplayPreferences() }
        val runtime=mockk<com.openautolink.app.secondary.SecondaryOutputRuntime>(relaxed=true)
        mockkObject(com.openautolink.app.secondary.SecondaryOutputRuntime.Companion)
        every { com.openautolink.app.secondary.SecondaryOutputRuntime.get(any()) } returns runtime
        // Current direct-video owners reject AndroidX retained-map handoff. The real direct
        // retirement/fresh-offer boundary is covered by ClusterDirectVideoRuntimeTest in this gate.
        every { runtime.retainForExplicitReconnect() } answers {
            assertEquals(0,layoutCommitCount.get())
            assertEquals(before,runBlocking { preferences.getSecondaryDisplayPreferences() })
            false
        }
        compose.onNodeWithTag("settingsButton").assertIsDisplayed().performClick()
        compose.onNodeWithText("Reconnect").assertIsDisplayed().performClick()
        awaitMainOutcome(10000) { reconnects==1 }
        verify(exactly=1) { runtime.retainForExplicitReconnect() }
        assertEquals(1,reconnects)
        assertEquals(0,layoutCommitCount.get())
        assertEquals(before,runBlocking { preferences.getSecondaryDisplayPreferences() })
    }

    @Test fun globalWirelessAaSaveReachesOrdinaryReconnectBeforeSessionRetirement() = assertLiveAaSave(false)
    @Test fun globalWirelessAaSaveRetainsOptionalHostWithoutRetiringAa() = assertLiveAaSave(true)

    private fun assertLiveAaSave(retainHost: Boolean) {
        setup(configuredAa = "wpp", actualAa = "wpp")
        val available = java.util.concurrent.atomic.AtomicBoolean(true)
        val availableAtReconnect = java.util.concurrent.atomic.AtomicBoolean(false)
        val durableAtReconnect = java.util.concurrent.atomic.AtomicBoolean(false)
        coEvery { session.stop() } coAnswers { available.set(false); aaState.value = SessionState.IDLE }
        coEvery { session.stopForExplicitReconnect() } coAnswers { available.set(false); aaState.value = SessionState.IDLE }
        val runtime = mockk<SecondaryOutputRuntime>(relaxed = true)
        mockkObject(SecondaryOutputRuntime.Companion)
        every { SecondaryOutputRuntime.get(any()) } returns runtime
        every { runtime.retainForExplicitReconnect() } returns retainHost
        coEvery { session.reconnect(any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            availableAtReconnect.set(available.get() && aaState.value == SessionState.STREAMING)
            durableAtReconnect.set(preferences.carPlaySettings.first().ssid == "Saved receiver" &&
                preferences.getClusterOutputSelection().mode == com.openautolink.app.cluster.ClusterDisplayMode.MAP_VIDEO &&
                layoutCommitCount.get() == 1)
            reconnectCount.incrementAndGet()
            Unit
        }
        save(expectReconnect = true)
        assertTrue("Global Save must reach ordinary AA reconnect with the existing session available for graceful ByeBye/minimal restart", availableAtReconnect.get())
        assertTrue("All saved stores and optional layout commit must precede ordinary reconnect", durableAtReconnect.get())
        coVerify(exactly = 0) { session.stop() }
        coVerify(exactly = 0) { session.stopForExplicitReconnect() }
        verify(exactly = 1) { runtime.retainForExplicitReconnect() }
    }

    @Test fun activeWirelessAaSaveSwitchingToCarPlayStillWaitsForRetirement() {
        setup(configuredAa = "wpp", actualAa = "wpp", cp = CarPlaySettings(enabled = true, connectionMode = "wireless"))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val runtime = mockk<SecondaryOutputRuntime>(relaxed = true)
        mockkObject(SecondaryOutputRuntime.Companion)
        every { SecondaryOutputRuntime.get(any()) } returns runtime
        every { runtime.retainForExplicitReconnect() } returns false
        coEvery { session.stop() } coAnswers { entered.complete(Unit); release.await() }
        try {
            compose.onNodeWithTag("saveAndConnectButton").performClick()
            awaitMainOutcome(10000) { closes == 1 && entered.isCompleted }
            assertTrue(runBlocking { preferences.carPlaySettings.first().enabled })
            assertFalse("CarPlay must not start before AA retirement acknowledges", projection.carPlayState.value.selected)
            assertEquals(0, reconnects)
            release.complete(Unit)
            // No Bluetooth consent in this fixture: the real post-retirement CarPlay admission
            // reports guidance instead of opening native/network resources.
            awaitMainOutcome(10000) { projection.carPlayStartupGuidance.value != null }
            coVerify(exactly = 1) { session.stop() }
            verify(exactly = 0) { settings.saveAndReconnect() }
            assertEquals(0, reconnects)
        } finally { release.complete(Unit) }
    }

    @Test fun wirelessSaveAdmitsRetainedHostAfterPersistenceBeforeTransportRestart() {
        setup(configuredAa = "wpp", actualAa = "wpp")
        val retained = java.util.concurrent.atomic.AtomicInteger()
        val runtime = mockk<com.openautolink.app.secondary.SecondaryOutputRuntime>(relaxed = true)
        mockkObject(com.openautolink.app.secondary.SecondaryOutputRuntime.Companion)
        every { com.openautolink.app.secondary.SecondaryOutputRuntime.get(any()) } returns runtime
        every { runtime.retainForExplicitReconnect() } answers {
            assertEquals(1, layoutCommitCount.get())
            assertEquals("Saved receiver", runBlocking { preferences.carPlaySettings.first().ssid })
            assertEquals(0, reconnects)
            retained.incrementAndGet()
            true
        }
        save(expectReconnect = true)
        assertEquals("The real Settings Save must explicitly retain the live host before restarting", 1, retained.get())
    }

    @Test fun wiredIconsOnlySaveKeepsCableSessionUntilPhysicalReconnect() = savePresentation(false, false)
    @Test fun wirelessIconsOnlySaveAutomaticallyStartsFreshConnection() = savePresentation(true, false)
    @Test fun usbCarPlayIconsOnlySaveDoesNotRetireAdmittedCableOwner() = savePresentation(false, true)
    @Test fun wiredVideoOffSaveKeepsCableSessionUntilPhysicalReconnect() = savePresentation(false, false, off = true)

    private fun savePresentation(wireless: Boolean, carPlayUsb: Boolean, off: Boolean = false) {
        setup(configuredAa = if (wireless || carPlayUsb) "wpp" else "usb",
            actualAa = if (wireless) "wpp" else "usb",
            cp = CarPlaySettings(enabled = carPlayUsb, connectionMode = if (carPlayUsb) "usb" else "wireless"),
            override = if (carPlayUsb) UsbHotPlugSwitchPolicy.Protocol.CARPLAY else null)
        compose.onNodeWithTag("clusterDisplay_MAP_VIDEO").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("saveAndConnectButton").performClick()
        awaitMainOutcome(10000) { closes == 1 && if (wireless) reconnects == 1 else projection.settingsSaveMessage.value != null }
        val expected = com.openautolink.app.cluster.ClusterDisplayMode.NATIVE_ICONS
        assertEquals(expected, runBlocking { preferences.getClusterOutputSelection() }.mode)
        assertEquals(1, layoutCommitCount.get())
        if (wireless) assertEquals(1, reconnects) else {
            assertUsbUntouched()
            assertEquals("Saved. Unplug and reconnect USB to apply.", projection.settingsSaveMessage.value)
        }
    }

    @Test fun wiredAndroidAutoSavePersistsWithoutRetiringTransport() {
        setup(); save(); assertUsbUntouched()
    }
    @Test fun temporaryUsbAndroidAutoOverridesWirelessConfiguredMode() {
        setup(configuredAa = "wpp", override = UsbHotPlugSwitchPolicy.Protocol.ANDROID_AUTO)
        save(); assertUsbUntouched()
        assertEquals(550L, (field("usbSwitchPolicy") as UsbHotPlugSwitchPolicy).generation)
        assertEquals("wpp", runBlocking { preferences.directTransport.first() })
    }
    @Test fun temporaryUsbCarPlayOverridesBothWirelessPreferences() {
        setup(configuredAa = "wpp", cp = CarPlaySettings(enabled = true, connectionMode = "wireless"),
            override = UsbHotPlugSwitchPolicy.Protocol.CARPLAY)
        save(); assertUsbUntouched()
        assertEquals(550L, (field("usbSwitchPolicy") as UsbHotPlugSwitchPolicy).generation)
    }
    @Test fun idleWiredCarPlaySaveDoesNotStartUsbOrAndroidAuto() {
        setup(configuredAa = "wpp", cp = CarPlaySettings(enabled = true, connectionMode = "usb"),
            activeAa = SessionState.IDLE, actualAa = "hotspot")
        save()
        assertEquals(0, reconnects)
        coVerify(exactly = 0) { session.stop() }
        verify(exactly = 0) { session.externalUsbTransport = any() }
    }
    @Test fun eagerSecondarySnapshotMustBelongToTheActualSaveReceiver() {
        setup()
        val writer = SettingsViewModel::class.java.getDeclaredField("latestSecondarySaved").apply { isAccessible = true }
        assertNotNull("public ready snapshot=${settings.secondaryPreferences.value != null}; actual Save receiver's eager writer must be ready", writer.get(settings))
    }

    @Test fun activeWirelessAndroidAutoKeepsAutomaticReconnectEvenWhenNewPreferenceIsUsb() {
        setup(configuredAa = "usb", actualAa = "wpp")
        save(expectReconnect = true)
        assertEquals("message=${projection.settingsSaveMessage.value}; state=${aaState.value}; transport=${transport.value}; external=${session.externalUsbTransport}; call=${session.callState?.value}", 1, reconnects)
    }
    @Test fun idleWirelessAndroidAutoKeepsAutomaticReconnect() {
        setup(configuredAa = "wpp", activeAa = SessionState.IDLE, actualAa = "hotspot")
        save(expectReconnect = true)
        assertEquals(1, reconnects)
    }
    @Test fun idleWirelessSaveCloseDoesNotAcknowledgeTransportRetirement() {
        setup(configuredAa = "wpp", activeAa = SessionState.IDLE, actualAa = "hotspot")
        val stopEntered = CompletableDeferred<Unit>()
        val releaseStop = CompletableDeferred<Unit>()
        coEvery { session.stop() } coAnswers {
            stopEntered.complete(Unit)
            releaseStop.await()
        }
        try {
            compose.onNodeWithTag("saveAndConnectButton").performClick()
            awaitMainOutcome(10000) { closes == 1 && stopEntered.isCompleted }
            assertEquals(1, layoutCommitCount.get())
            // Same production dispatch that exposed the old helper's premature restart-count RED.
            assertEquals("Saved receiver", runBlocking { preferences.carPlaySettings.first().ssid })
            assertEquals("wpp", runBlocking { preferences.directTransport.first() })
            assertEquals(SessionState.IDLE, aaState.value)
            assertEquals("hotspot", transport.value)
            assertFalse(session.externalUsbTransport)
            assertNull(session.callState)
            assertNull((field("usbSwitchPolicy") as UsbHotPlugSwitchPolicy).generation)
            assertEquals(0, reconnects)
            verify(exactly = 0) { settings.saveAndReconnect() }
            releaseStop.complete(Unit)
            awaitSave(expectReconnect = true)
            assertEquals(1, reconnects)
            coVerify(exactly = 1) { session.stop() }
        } finally { releaseStop.complete(Unit) }
    }
    @Test fun invalidSaveRetainsDraftAndDoesNotCloseOrTouchTransport() {
        setup()
        compose.runOnIdle { settings.updateCarPlayDraft { it.copy(receiverAddress = "invalid") } }
        compose.onNodeWithTag("saveAndConnectButton").performClick()
        compose.waitUntil(5000) { settings.carPlayApplyError.value.isNotEmpty() }
        assertEquals(0, closes); assertEquals(0, reconnects)
        assertEquals("invalid", settings.carPlaySettings.value.receiverAddress)
        assertNotEquals("Saved receiver", runBlocking { preferences.carPlaySettings.first().ssid })
        assertEquals(com.openautolink.app.cluster.ClusterDisplayMode.NATIVE_ICONS,
            runBlocking { preferences.getClusterOutputSelection().mode })
        assertEquals(com.openautolink.app.cluster.ClusterDisplayMode.MAP_VIDEO, settings.clusterSelection.value.mode)
        coVerify(exactly = 0) { session.stop() }
        verify(exactly = 0) { session.externalUsbTransport = any() }
    }
    @Test fun failedLayoutPersistencePreventsWirelessRestartAndKeepsSettingsOpen() {
        setup(configuredAa = "wpp", actualAa = "wpp")
        layoutSaveSuccessful = false
        compose.onNodeWithTag("saveAndConnectButton").performClick()
        awaitMainOutcome(5000) { settings.carPlayApplyError.value == "Cluster layout could not be saved. Try again." }
        assertEquals(1, layoutCommitCount.get())
        assertEquals(0, closes)
        assertEquals(0, reconnects)
        compose.onNodeWithTag("saveAndConnectButton").assertExists()
        assertEquals(com.openautolink.app.cluster.ClusterDisplayMode.MAP_VIDEO, settings.clusterSelection.value.mode)
        coVerify(exactly = 0) { session.stop() }
    }
    @Test fun usbSaveGuidanceRemainsOnProjectionAfterSettingsClosesWithoutGenericVideoWait() {
        setup(renderProjection = true)
        save()
        compose.onNodeWithTag("saveAndConnectButton").assertDoesNotExist()
        compose.onNodeWithText("Saved. Unplug and reconnect USB to apply.").assertIsDisplayed()
        @Suppress("UNCHECKED_CAST")
        val stats = field("_videoStats") as MutableStateFlow<com.openautolink.app.video.VideoStats>
        compose.runOnIdle { stats.value = com.openautolink.app.video.VideoStats(waitingForKeyframe = true) }
        compose.onNodeWithTag("keyframeWaitIndicator").assertDoesNotExist()
        assertUsbUntouched()
    }
    @Test fun wirelessSaveDuringCallPersistsWithoutRetiringCallOrAutomaticallyReconnecting() {
        setup(configuredAa = "wpp", actualAa = "wpp")
        every { session.callState } returns MutableStateFlow(com.openautolink.app.audio.CallState.IN_CALL)
        save()
        assertEquals(0, reconnects)
        coVerify(exactly = 0) { session.stop() }
    }
    @Test fun activeUsbCarPlaySaveDisableKeepsAdmittedRuntimeUntilNextConnection() {
        setup(configuredAa = "wpp", cp = CarPlaySettings(enabled = true), activeAa = SessionState.IDLE,
            override = UsbHotPlugSwitchPolicy.Protocol.CARPLAY)
        val coordinator = field("carPlayCoordinator") as CarPlayCoordinator
        val running = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.atomic.AtomicInteger()
        val runtime = object : CarPlayRuntime {
            override fun run() { running.countDown(); release.await(15, java.util.concurrent.TimeUnit.SECONDS) }
            override fun cancel() { release.countDown() }
            override fun close() { closed.incrementAndGet(); release.countDown() }
        }
        try {
            assertTrue(coordinator.connect(true) { runtime })
            assertTrue(running.await(5, java.util.concurrent.TimeUnit.SECONDS))
            compose.runOnIdle { settings.updateCarPlayDraft { it.copy(enabled = false) } }
            save()
            assertEquals("Saving USB next-session disable must not close active media", 0, closed.get())
            assertTrue(projection.carPlayState.value.selected)
            assertFalse(runBlocking { preferences.carPlaySettings.first().enabled })
        } finally {
            val retired = java.util.concurrent.CountDownLatch(1)
            coordinator.returnToAndroidAuto { retired.countDown() }
            release.countDown()
            assertTrue(retired.await(15, java.util.concurrent.TimeUnit.SECONDS))
        }
    }
    @Test fun newSessionConsumesNoticeEvenWithoutIdleStateEmission() {
        setup(); save()
        compose.runOnIdle { sessionEpoch.value = 2L }
        awaitMainOutcome(5000) { projection.settingsSaveMessage.value == null }
        assertUsbUntouched()
    }
    @Test fun sameSessionStateChangesDoNotConsumeUnappliedNotice() {
        setup(); save()
        compose.runOnIdle { aaState.value = SessionState.CONNECTED }
        compose.waitForIdle()
        assertNotNull(projection.settingsSaveMessage.value)
        compose.runOnIdle { aaState.value = SessionState.STREAMING }
        assertUsbUntouched()
    }
    @Test fun failedSecondSaveRetainsEarlierPendingNotice() {
        setup(); save()
        compose.runOnIdle { settings.updateCarPlayDraft { it.copy(receiverAddress = "invalid") } }
        compose.onNodeWithTag("saveAndConnectButton").performClick()
        awaitMainOutcome(5000) { settings.carPlayApplyError.value.isNotEmpty() }
        assertEquals("Saved. Unplug and reconnect USB to apply.", projection.settingsSaveMessage.value)
        assertUsbUntouched()
    }
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    @Config(qualifiers = "w640dp-h240dp-mdpi")
    @Test fun pendingNoticeCanCollapseAndReopenWithoutDiscardingSavedIntent() {
        setup(renderProjection = true); save()
        compose.onNodeWithTag("overlayDragHintDismiss").performClick()
        awaitMainOutcome(5000) { compose.onAllNodesWithTag("overlayDragHintDismiss").fetchSemanticsNodes().isEmpty() }
        val bounds = compose.onNodeWithTag("settingsSaveNotice").fetchSemanticsNode().boundsInRoot
        assertTrue("Actual projection notice must be bounded: $bounds", bounds.height <= 96f && bounds.width <= 448f)
        captureProjection("projection-expanded.png")
        compose.onNodeWithContentDescription("Hide reconnect guidance").performClick()
        awaitMainOutcome(5000) {
            !projection.settingsSaveNoticeExpanded.value &&
                compose.onAllNodesWithText("Saved. Unplug and reconnect USB to apply.").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Saved. Unplug and reconnect USB to apply.").assertDoesNotExist()
        compose.onNodeWithText("Changes pending").assertIsDisplayed()
        captureProjection("projection-collapsed.png")
        compose.onNodeWithText("Changes pending").performClick()
        awaitMainOutcome(5000) {
            projection.settingsSaveNoticeExpanded.value &&
                compose.onAllNodesWithText("Saved. Unplug and reconnect USB to apply.").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Saved. Unplug and reconnect USB to apply.").assertIsDisplayed()
        assertNotNull(projection.settingsSaveMessage.value)
        assertUsbUntouched()
    }
    private fun awaitFixtureJobs(jobs: List<Job>, timeoutMillis: Long = 10000): Boolean {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (jobs.any { !it.isCompleted } && System.nanoTime() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(1)
        }
        return jobs.all { it.isCompleted && it.children.none() }
    }

    @Test fun fixtureCleanupDrainsMainButDoesNotAcknowledgeAHeldCancellation() {
        val root = SupervisorJob()
        val release = CompletableDeferred<Unit>()
        val done = java.util.concurrent.atomic.AtomicBoolean()
        CoroutineScope(root + Dispatchers.IO).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { withContext(NonCancellable) {
                release.await()
                withContext(Dispatchers.Main) { done.set(true) }
            } }
        }
        try {
            root.cancel()
            assertFalse(awaitFixtureJobs(listOf(root), 50))
            assertFalse(done.get())
            release.complete(Unit)
            assertTrue(awaitFixtureJobs(listOf(root)))
            assertTrue(done.get())
        } finally { release.complete(Unit); root.cancel(); assertTrue(awaitFixtureJobs(listOf(root))) }
    }

    @After fun cleanup() {
        val roots = listOfNotNull(
            if (::settings.isInitialized) settings.viewModelScope.coroutineContext[Job] else null,
            if (::projection.isInitialized) projection.viewModelScope.coroutineContext[Job] else null,
        )
        roots.forEach { it.cancel() }
        if (::projection.isInitialized) {
            (field("unifiedUsbManager") as? com.openautolink.app.transport.usb.UsbConnectionManager)?.stop()
            (field("carPlayCoordinator") as CarPlayCoordinator).close()
        }
        val viewModelsRetired = awaitFixtureJobs(roots)
        storeJob.cancel()
        val storeRetired = awaitFixtureJobs(listOf(storeJob))
        if (viewModelsRetired && storeRetired) unmockkAll()
        assertTrue("Exact Settings/Projection roots must finish before unmock", viewModelsRetired)
        assertTrue("Exact DataStore job must finish before unmock", storeRetired)
    }
}