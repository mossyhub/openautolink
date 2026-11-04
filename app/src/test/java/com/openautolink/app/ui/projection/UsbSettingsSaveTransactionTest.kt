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
        compose.waitUntil(5000) { settings.carPlaySettingsLoaded.value && settings.clusterLoaded.value && settings.secondaryPreferences.value != null }
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
    @After fun cleanup() {
        if (::settings.isInitialized) settings.viewModelScope.cancel()
        if (::projection.isInitialized) {
            projection.viewModelScope.cancel()
            (field("unifiedUsbManager") as? com.openautolink.app.transport.usb.UsbConnectionManager)?.stop()
            (field("carPlayCoordinator") as CarPlayCoordinator).close()
        }
        runBlocking { storeJob.cancelAndJoin() }
        unmockkAll()
    }
}