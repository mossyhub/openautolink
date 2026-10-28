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
    private val aaState = MutableStateFlow(SessionState.STREAMING)
    private val transport = MutableStateFlow("usb")
    private val session = mockk<SessionManager>(relaxed = true)
    private var closes = 0
    private var reconnects = 0
    private fun field(name: String): Any? = projection.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(projection)
    private fun setup(configuredAa: String = "usb", cp: CarPlaySettings = CarPlaySettings(enabled = false),
                      activeAa: SessionState = SessionState.STREAMING, actualAa: String = "usb",
                      override: UsbHotPlugSwitchPolicy.Protocol? = null, renderProjection: Boolean = false) = runBlocking {
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
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers { reconnects++ }
        val discovery = mockk<PhoneDiscovery>(relaxed = true) {
            every { phones } returns MutableStateFlow(emptyList())
            every { isSweeping } returns MutableStateFlow(false)
            every { sweepProgress } returns MutableStateFlow("")
        }
        every { PhoneDiscovery.getInstance(any()) } returns discovery
        projection = ProjectionViewModel(app)
        settings = SettingsViewModel(app)
        if (override != null) {
            val policy = field("usbSwitchPolicy") as UsbHotPlugSwitchPolicy
            check(policy.accept(550L, { override }, { true }, {}, {}, { true }, {}, {}))
        }
        clearMocks(session, answers = false, recordedCalls = true)
        compose.setContent { MaterialTheme {
            var open by remember { mutableStateOf(true) }
            val saveAction = rememberProductionSettingsSaveAction(settings, projection) { closes++; open = false }
            if (open || !renderProjection) SettingsScreen(viewModel = settings, onSaveAndConnect = saveAction)
            else ProjectionScreen(viewModel = projection)
        } }
        compose.waitUntil(5000) { settings.carPlaySettingsLoaded.value }
        compose.runOnIdle { settings.updateCarPlayDraft { it.copy(ssid = "Saved receiver") } }
    }
    private fun save() {
        compose.onNodeWithTag("saveAndConnectButton").performClick()
        compose.waitUntil(10000) { closes == 1 }
        // Drain Main and IO dispatch without advancing the periodic telemetry timer indefinitely.
        repeat(30) { compose.waitForIdle(); Thread.sleep(10) }
        assertEquals("Saved receiver", runBlocking { preferences.carPlaySettings.first().ssid })
    }
    private fun assertUsbUntouched() {
        assertEquals("USB Save must not initiate automatic reconnect", 0, reconnects)
        coVerify(exactly = 0) { session.stop() }
        verify(exactly = 0) { session.externalUsbTransport = any() }
        assertEquals(SessionState.STREAMING, aaState.value)
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
    @Test fun activeWirelessAndroidAutoKeepsAutomaticReconnectEvenWhenNewPreferenceIsUsb() {
        setup(configuredAa = "usb", actualAa = "wpp")
        save()
        assertEquals(1, reconnects)
    }
    @Test fun idleWirelessAndroidAutoKeepsAutomaticReconnect() {
        setup(configuredAa = "wpp", activeAa = SessionState.IDLE, actualAa = "hotspot")
        save()
        assertEquals(1, reconnects)
    }
    @Test fun invalidSaveRetainsDraftAndDoesNotCloseOrTouchTransport() {
        setup()
        compose.runOnIdle { settings.updateCarPlayDraft { it.copy(receiverAddress = "invalid") } }
        compose.onNodeWithTag("saveAndConnectButton").performClick()
        compose.waitUntil(5000) { settings.carPlayApplyError.value.isNotEmpty() }
        assertEquals(0, closes); assertEquals(0, reconnects)
        assertEquals("invalid", settings.carPlaySettings.value.receiverAddress)
        assertNotEquals("Saved receiver", runBlocking { preferences.carPlaySettings.first().ssid })
        coVerify(exactly = 0) { session.stop() }
        verify(exactly = 0) { session.externalUsbTransport = any() }
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
