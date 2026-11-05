package com.openautolink.app.announcements

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.lifecycle.viewModelScope
import com.openautolink.app.audio.CallState
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.session.*
import com.openautolink.app.ui.navigation.AppNavHost
import com.openautolink.app.ui.settings.*
import com.openautolink.app.ui.testing.createOalComposeRule
import com.openautolink.app.ui.testing.closeCarPlayCallControllerTestProcessAndAwait
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import java.io.File

/** Actual AppNavHost -> production ProjectionScreen -> unchanged live Settings overlay.
 * Only downstream transport/account boundaries are mocked. No native session/device is started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1024dp-h400dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ReleaseAnnouncementRootTest {
    @get:Rule val compose = createOalComposeRule()
    private val job = SupervisorJob()
    private lateinit var data: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var preferences: AppPreferences
    private lateinit var settings: SettingsViewModel
    private lateinit var projection: com.openautolink.app.ui.projection.ProjectionViewModel
    private val session = mockk<SessionManager>(relaxed = true)
    private val sessionState = MutableStateFlow(SessionState.STREAMING)
    private val callState = MutableStateFlow(CallState.IDLE)
    private var mounted by mutableStateOf(true)
    private var version by mutableIntStateOf(575)
    private var created = false
    private lateinit var file: File
    private lateinit var nav: androidx.navigation.NavHostController
    private val id = com.openautolink.app.secondary.SecondaryOutputId("announcement-driver-display")
    private val source by lazy {
        val d = com.openautolink.app.secondary.SecondaryDisplayDescriptor(id, 2, 1, "Driver display", 2000, 810, 160,
            com.openautolink.app.secondary.SecondaryRoute.PUBLIC_ACTIVITY, com.openautolink.app.secondary.SecondaryReason.NONE, "Driver")
        SecondaryDisplaysSource(MutableStateFlow(com.openautolink.app.secondary.SecondaryInventorySnapshot(1, listOf(d))),
            MutableStateFlow(listOf(com.openautolink.app.displays.DiscoveredSecondaryDisplay(id.value, 2, d.name,
                2000, 810, true, true, false, null))), null)
    }

    @Before fun setup() = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication() as Application
        // This is an upgrade announcement, independent from the older nonmodal OAL-logo hint.
        // Seed that unrelated coachmark before mounting, never click through the new dialog.
        assertTrue(app.getSharedPreferences("overlay_onboarding", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("menu_drag_hint_dismissed", true).commit())
        file = File(app.filesDir, "announcement-root-${java.util.UUID.randomUUID()}.preferences_pb")
        data = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        preferences = AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
            .apply { isAccessible = true }.newInstance(data)
        preferences.setCarPlaySettings(CarPlaySettings(enabled = false))
        preferences.setDirectTransport("hotspot")
        preferences.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.NATIVE_ICONS))
        mockkObject(AppPreferences.Companion, SessionManager.Companion,
            com.openautolink.app.transport.PhoneDiscovery.Companion,
            com.openautolink.app.carplay.CarPlaySessionLeases.Companion,
            com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.Companion,
            com.openautolink.app.carplay.CarPlayCallController.Companion)
        every { AppPreferences.getInstance(any()) } returns preferences
        every { com.openautolink.app.carplay.CarPlaySessionLeases.forContext(any()) } returns com.openautolink.app.carplay.CarPlaySessionLeaseSource.NONE
        every { com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.forContext(any()) } returns com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.NONE
        val calls = mockk<com.openautolink.app.carplay.CarPlayCallController>(relaxed = true)
        every { calls.state } returns MutableStateFlow(com.openautolink.app.carplay.CarPlayCallController.State())
        every { com.openautolink.app.carplay.CarPlayCallController.get(any()) } returns calls
        every { SessionManager.getInstance(any(), any(), any()) } returns session
        every { SessionManager.instanceOrNull() } returns session
        every { session.sessionState } returns sessionState
        every { session.settingsSessionEpoch } returns MutableStateFlow(0L)
        every { session.clusterVideoStatus } returns MutableStateFlow(ClusterProjectionStatus())
        every { session.secondaryOutputStatuses } returns MutableStateFlow(emptyMap())
        every { session.transportMode } returns MutableStateFlow("hotspot")
        every { session.reconnectAttempt } returns MutableStateFlow(0)
        every { session.wakeEvents } returns MutableSharedFlow()
        every { session.videoStats } returns null
        every { session.audioStats } returns null
        every { session.callState } returns callState
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
        val discovery = mockk<com.openautolink.app.transport.PhoneDiscovery>(relaxed = true) {
            every { phones } returns MutableStateFlow(emptyList())
            every { isSweeping } returns MutableStateFlow(false)
            every { sweepProgress } returns MutableStateFlow("")
        }
        every { com.openautolink.app.transport.PhoneDiscovery.getInstance(any()) } returns discovery
    }

    private fun mount(store: ReleaseAnnouncementStore? = preferences.releaseAnnouncements, fontScale: Float = 1.3f) {
        RuntimeEnvironment.setFontScale(fontScale)
        compose.setContent {
            if (mounted) {
                nav = androidx.navigation.compose.rememberNavController()
                settings = androidx.lifecycle.viewmodel.compose.viewModel()
                projection = androidx.lifecycle.viewmodel.compose.viewModel()
                created = true
                com.openautolink.app.ui.theme.OpenAutoLinkTheme {
                    AppNavHost(clusterBindings = null, secondarySource = source, navController = nav,
                        announcementStore = store, announcementVersionCode = version)
                }
            }
        }
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); created && settings.carPlaySettingsLoaded.value && settings.clusterLoaded.value }
        compose.onNodeWithTag("projectionScreen").assertExists()
    }

    private fun awaitDialog() {
        compose.waitUntil(5000) { compose.onAllNodesWithTag("releaseAnnouncement").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Maps on your driver display").assertExists()
        compose.onNodeWithText(requireNotNull(ReleaseAnnouncements.forVersion(575)).body).assertExists()
        compose.onNodeWithTag("releaseAnnouncementGotIt").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("releaseAnnouncementOpenSettings").performScrollTo().assertIsDisplayed()
        for ((label, tag) in listOf("Got it" to "releaseAnnouncementGotIt", "Open settings" to "releaseAnnouncementOpenSettings")) {
            compose.onNodeWithTag(tag).performScrollTo().assertTextEquals(label)
            assertTrue("$label must retain a 48dp action target", compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height >= 48f)
        }
    }
    private fun awaitAcknowledgement() = compose.waitUntil(5000) {
        shadowOf(Looper.getMainLooper()).idle()
        runBlocking { requireNotNull(ReleaseAnnouncements.forVersion(575)).acknowledgementKey in
            data.data.first()[androidx.datastore.preferences.core.stringSetPreferencesKey("release_announcements_acknowledged_v1")].orEmpty() }
    }

    @Test fun openSettingsTargetsRealSecondaryPaneWithoutSaveEnableReconnectOrLostDraft() = runBlocking<Unit> {
        callState.value = CallState.IN_CALL
        mount()
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
        compose.runOnIdle { settings.updateCarPlayDraft { it.copy(ssid = "Unsaved draft survives") } }
        val before = preferences.getSecondaryDisplayPreferences()
        compose.runOnIdle { callState.value = CallState.IDLE }
        awaitDialog()
        capture("wide-1024x400-font1.3")
        compose.onNodeWithTag("releaseAnnouncementOpenSettings").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
        compose.onNodeWithTag("settingsScreen").assertIsDisplayed()
        compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").assertIsSelected()
        compose.onNodeWithContentDescription("Driver-display video").assertIsOff()
        assertEquals("projection", nav.currentDestination?.route) // overlay, NOT a new Surface/navigation owner
        assertEquals("Unsaved draft survives", settings.carPlaySettings.value.ssid)
        assertEquals(before, preferences.getSecondaryDisplayPreferences())
        assertEquals(ClusterDisplayMode.NATIVE_ICONS, preferences.getClusterOutputSelection().mode)
        assertNotEquals("Unsaved draft survives", preferences.carPlaySettings.first().ssid)
        awaitAcknowledgement()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
        coVerify(exactly = 0) { session.stop() }
        coVerify(exactly = 0) { session.reconnect(codecPreference=any(),micSourcePreference=any(),scalingMode=any(),directTransport=any(),wppInterfaceName=any(),hotspotSsid=any(),hotspotPassword=any(),videoAutoNegotiate=any(),aaResolution=any(),aaDpi=any(),aaAutoDpi=any(),aaWidthMargin=any(),aaHeightMargin=any(),aaPixelAspect=any(),aaTargetLayoutWidthDp=any(),aaViewingDistanceMm=any(),aaDecoderAdditionalDepth=any(),aaAutoMargins=any(),videoFps=any(),driveSide=any(),hideClock=any(),hideSignal=any(),hideBattery=any(),volumeOffsetMedia=any(),volumeOffsetNavigation=any(),volumeOffsetAssistant=any(),manualIpAddress=any(),safeAreaTop=any(),safeAreaBottom=any(),safeAreaLeft=any(),safeAreaRight=any(),gpsForwarding=any(),galVersion=any(),seedIdrThresholds=any()) }
    }

    @Test fun connectedUsbAnnouncementUsesSameOverlayAndPreservesPendingSaveGuidance() = runBlocking<Unit> {
        preferences.setDirectTransport("usb")
        every { session.transportMode } returns MutableStateFlow("usb")
        mockkObject(com.openautolink.app.transport.usb.UsbConnectionManager)
        every { com.openautolink.app.transport.usb.UsbConnectionManager.connectionState } returns
            MutableStateFlow(com.openautolink.app.transport.usb.UsbConnectionState.CONNECTED)
        every { com.openautolink.app.transport.usb.UsbConnectionManager.availableDevices } returns MutableStateFlow(emptyList())
        callState.value = CallState.IN_CALL
        try {
            mount()
            compose.runOnIdle { projection.reconnectAfterSettingsSave { error("Announcement must not reconnect") } }
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); projection.settingsSaveMessage.value != null }
            val pending = projection.settingsSaveMessage.value
            compose.runOnIdle { callState.value = CallState.IDLE }
            awaitDialog()
            compose.onNodeWithTag("releaseAnnouncementOpenSettings").performScrollTo().performTouchInput { click() }
            compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").assertIsSelected()
            compose.onNodeWithContentDescription("Back").performClick()
            assertEquals(pending, projection.settingsSaveMessage.value)
            compose.onNodeWithTag("settingsSaveNotice").assertExists()
            awaitAcknowledgement()
            coVerify(exactly = 0) { session.stop() }
        } finally { unmockkObject(com.openautolink.app.transport.usb.UsbConnectionManager) }
    }

    @Test fun gotItDismissesOnceWithoutOpeningSettingsOrChangingVideo() = runBlocking<Unit> {
        mount(); awaitDialog()
        compose.onNodeWithTag("releaseAnnouncementGotIt").performScrollTo().performTouchInput { click() }
        awaitAcknowledgement()
        compose.onNodeWithTag("settingsScreen").assertDoesNotExist()
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
        compose.runOnIdle { sessionState.value = SessionState.IDLE }
        compose.runOnIdle { sessionState.value = SessionState.STREAMING }
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
        assertEquals(ClusterDisplayMode.NATIVE_ICONS, preferences.getClusterOutputSelection().mode)
    }

    @Test @Config(qualifiers = "w480dp-h900dp-port-mdpi")
    fun narrowLargeTextButtonsRemainReachableAndBackAcknowledges() {
        mount(fontScale = 1.5f); awaitDialog(); capture("narrow-480x900-font1.5")
        compose.runOnIdle { requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).onBackPressed() }
        awaitAcknowledgement()
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
    }

    @Test fun outsideDismissalAcknowledges() {
        mount(); awaitDialog()
        compose.runOnIdle {
            val event = android.view.MotionEvent.obtain(0, 0, android.view.MotionEvent.ACTION_OUTSIDE, -20f, -20f, 0)
            try { requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).onTouchEvent(event) }
            finally { event.recycle() }
        }
        awaitAcknowledgement()
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
    }

    @Test fun unlisted573IsQuietEvenWhen572WasNeverDismissed() {
        val unused = mockk<ReleaseAnnouncementStore>()
        version = 573; mount(unused)
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
        coVerify(exactly = 0) { session.stop() }
        coVerify(exactly = 0) { unused.isAcknowledged(any()) }
    }

    @Test fun acknowledged575IsQuietWithFreshMemoryStore() = runBlocking<Unit> {
        val a = requireNotNull(ReleaseAnnouncements.forVersion(575))
        val store = preferences.releaseAnnouncements
        val owner = Any()
        assertTrue(store.claim(a, owner)); store.dismiss(a, owner).join()
        val fresh = ReleaseAnnouncementStore(data, CoroutineScope(job + Dispatchers.IO))
        mount(fresh)
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
        compose.onNodeWithTag("projectionScreen").assertExists()
    }

    @Test fun historical572AcknowledgementDoesNotSuppressExplicit575() = runBlocking<Unit> {
        val historical = requireNotNull(ReleaseAnnouncements.forVersion(572))
        val store = preferences.releaseAnnouncements
        val owner = Any()
        assertTrue(store.claim(historical, owner)); store.dismiss(historical, owner).join()
        mount(); awaitDialog()
        assertEquals(ClusterDisplayMode.NATIVE_ICONS, preferences.getClusterOutputSelection().mode)
    }

    @Test fun futureUnlisted576IsQuietEvenWithEarlierUndismissedAnnouncements() {
        val unused = mockk<ReleaseAnnouncementStore>()
        version = 576; mount(unused)
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
        coVerify(exactly = 0) { unused.isAcknowledged(any()) }
    }

    @Test fun otherEntrypointsWithoutMainOptInNeverAnnounce() {
        mount(null)
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
        compose.onNodeWithTag("projectionScreen").assertExists()
    }

    @Test fun staleReadCannotClaimAfterSettingsTakesForeground() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = java.util.concurrent.atomic.AtomicBoolean()
        val store = mockk<ReleaseAnnouncementStore>()
        coEvery { store.isAcknowledged(any()) } coAnswers {
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            returned.set(true)
            false
        }
        try {
            mount(store)
            compose.waitUntil(5000) { entered.isCompleted }
            compose.onNodeWithTag("settingsButton").performClick()
            compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithTag("settingsScreen").assertIsDisplayed()
            release.complete(Unit)
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); returned.get() }
            compose.waitForIdle()
            compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
            verify(exactly = 0) { store.claim(any(), any()) }
        } finally { release.complete(Unit) }
    }

    @Test fun pendingThenFailedAnnouncementReadDoesNotGateOrdinarySettingsOrProjection() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failed = java.util.concurrent.atomic.AtomicBoolean()
        val store = mockk<ReleaseAnnouncementStore>()
        coEvery { store.isAcknowledged(any()) } coAnswers {
            entered.complete(Unit); release.await(); failed.set(true); throw java.io.IOException("optional metadata failure")
        }
        mount(store)
        compose.waitUntil(5000) { entered.isCompleted }
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
        compose.onNodeWithTag("settingsButton").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithTag("settingsScreen").assertIsDisplayed()
        compose.onNodeWithTag("settingsTab_DISPLAY").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        release.complete(Unit)
        compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); failed.get() }
        compose.waitForIdle()
        compose.onNodeWithTag("projectionScreen").assertExists()
        compose.onNodeWithTag("releaseAnnouncement").assertDoesNotExist()
        verify(exactly = 0) { store.claim(any(), any()) }
    }

    private fun capture(name: String) {
        val directory = System.getProperty("announcement.render.dir").orEmpty()
        if (directory.isEmpty()) return
        compose.runOnIdle {
            val dialog = requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            val root = requireNotNull(dialog.window).decorView
            val bitmap = android.graphics.Bitmap.createBitmap(root.width, root.height, android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            File(directory).mkdirs()
            File(directory, "sdk${android.os.Build.VERSION.SDK_INT}-$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }

    @After fun cleanup() = runBlocking<Unit> {
        compose.runOnIdle {
            mounted = false
            if (created) { settings.viewModelScope.cancel(); projection.viewModelScope.cancel() }
        }
        closeCarPlayCallControllerTestProcessAndAwait()
        unmockkObject(AppPreferences.Companion, SessionManager.Companion,
            com.openautolink.app.transport.PhoneDiscovery.Companion,
            com.openautolink.app.carplay.CarPlaySessionLeases.Companion,
            com.openautolink.app.carplay.CarPlayHostMediaLeaseSource.Companion,
            com.openautolink.app.carplay.CarPlayCallController.Companion)
        job.cancelAndJoin()
        if (::file.isInitialized) file.delete()
        RuntimeEnvironment.setFontScale(1f)
    }
}
