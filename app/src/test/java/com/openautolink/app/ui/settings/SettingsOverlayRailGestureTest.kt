package com.openautolink.app.ui.settings

import com.openautolink.app.announcements.*

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
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1000dp-h552dp-land-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SettingsOverlayRailGestureTest {
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
    private var root: android.view.View? = null
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
                com.openautolink.app.ui.theme.OpenAutoLinkTheme(oalUiScalePercent = 125) {
                    root = androidx.compose.ui.platform.LocalView.current.rootView
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

    private fun capture(stage: String) {
        val dir = File(System.getProperty("settings.grouping.render.dir", "build/settings-rail-gesture")); dir.mkdirs()
        val name = "overlay-${RuntimeEnvironment.getApplication().resources.displayMetrics.densityDpi}-${RuntimeEnvironment.getApplication().resources.configuration.fontScale}-$stage-sdk${RuntimeEnvironment.getApiLevel()}"
        compose.runOnIdle {
            val view = requireNotNull(root)
            val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(image))
            File(dir, "$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
        }
        File(dir, "$name.txt").writeText(compose.onRoot().printToString())
    }

    private fun railGestures(font: Float) {
        mount(fontScale = font); awaitDialog()
        compose.onNodeWithTag("releaseAnnouncementOpenSettings").performScrollTo().performTouchInput { click() }
        compose.onNodeWithTag("settingsScreen").assertIsDisplayed()
        compose.onNodeWithTag("settingsTab_INSTRUMENT_CLUSTER").assertIsSelected()
        compose.runOnIdle {
            assertEquals(2000, requireNotNull(root).width)
            assertTrue(requireNotNull(root).height in 1104..1105)
        }
        capture("initial")
        repeat(8) {
            compose.onNodeWithTag("settingsTabRail").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(centerX, height * .9f),
                    androidx.compose.ui.geometry.Offset(centerX, height * .1f), 650)
            }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        }
        capture("after-eight-swipes")
        compose.onNodeWithTag("settingsTab_DIAGNOSTICS").assertIsDisplayed().performTouchInput { click() }
        compose.onNodeWithTag("settingsTab_DIAGNOSTICS").assertIsSelected()
        compose.onNodeWithTag("openDiagnosticsButton").assertExists()
        compose.onNodeWithContentDescription("Back").performTouchInput { click() }
        compose.onNodeWithTag("settingsScreen").assertDoesNotExist()
        assertEquals("projection", nav.currentDestination?.route)
        coVerify(exactly = 0) { session.stop() }
    }

    @Test fun physical320DefaultFontOverlayRailGestures() = railGestures(1f)
    @Test fun physical320LargeFontOverlayRailGestures() = railGestures(1.3f)
    @Test @Config(qualifiers = "w800dp-h442dp-land-400dpi")
    fun physical400LargeFontOverlayRailGestures() = railGestures(1.3f)

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
