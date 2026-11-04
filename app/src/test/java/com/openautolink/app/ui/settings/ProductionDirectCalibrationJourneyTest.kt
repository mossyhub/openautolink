package com.openautolink.app.ui.settings

import android.app.Application
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Looper
import android.view.Display
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.carplay.CarPlayUiState
import com.openautolink.app.session.SessionState
import com.openautolink.app.ui.testing.createOalComposeRule
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

/** Real settings binding + production initial qualifier + real DataStore + rendered editor and
 * direct Activity. OS public display/Build identity are controlled fixtures, not a GM device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1000dp-h360dp-land-mdpi",
    shadows = [LiveGridWindowActivityTest.Admission::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProductionDirectCalibrationJourneyTest {
    @get:Rule val compose = createOalComposeRule()
    private val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
    private val legacy = PixelRect(0,135,2000,810)
    private val scope = ClusterLayoutScope(profile.vehicle, profile.id, profile.role, profile.panel)

    private fun journey(output: PixelRect, expected: PixelRect) = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication()
        val oldProduct = Build.PRODUCT
        ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", "aegean_orange")
        LiveGridWindowActivityTest.allowed = true
        val id = ShadowDisplayManager.addDisplay("w2000dp-h810dp-mdpi", "Controlled physical cluster")
        val physical = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(physical).setFlags(Display.FLAG_PRESENTATION); shadowOf(physical).setState(Display.STATE_ON)
        val initial = requireNotNull(ClusterProjectionOutput.qualifiedInitialDisplay(app))
        assertEquals(legacy, initial.host.visible) // Real shipped initial qualifier, not an injected model.
        val job = SupervisorJob()
        val file = File(app.filesDir, "direct-journey-${java.util.UUID.randomUUID()}.preferences_pb")
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        val preferences = AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        val safe = PixelRect(636,150,1471,661) // Exact latest restored upload intent.
        val saved = SavedClusterLayout(scope, legacy, output, safe)
        preferences.saveClusterLayout(saved)
        val session = MutableStateFlow(SessionState.CONNECTING)
        val aa = MutableStateFlow(ClusterProjectionStatus())
        val cp = MutableStateFlow(CarPlayUiState())
        val preview = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _,_,_,_,_ ->
            error("Settings must not create a hosted phone surface") }, { 0L }, { _,_ -> {} })
        var mounted by mutableStateOf(true)
        var opened by mutableStateOf(false)
        var bound: ClusterSettingsBindings? = null
        var activity: ActivityController<LiveGridWindowActivity>? = null
        try {
            compose.setContent {
                if (mounted) {
                    val binding = rememberProductionClusterSettingsBinding(app, preferences, session, aa, cp, preview)
                    bound = binding
                    if (opened && binding != null) MaterialTheme { ClusterLayoutEditor(binding) { opened = false } }
                }
            }
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); bound?.liveGrid != null }
            // Projection ends before editor Start becomes permitted. No cached host survives.
            compose.runOnIdle { session.value = SessionState.IDLE; opened = true }
            compose.waitForIdle()
            assertEquals(expected, requireNotNull(bound?.layout).output)
            assertEquals(safe, bound?.layout?.safe)
            assertNull(bound?.actualSurface)
            compose.onNodeWithTag("liveGridStart").performClick()
            compose.runOnIdle {
                val intent = requireNotNull(shadowOf(app).nextStartedActivity)
                activity = ActivityController.of(LiveGridWindowActivity(), intent,
                    ActivityOptions.makeBasic().setLaunchDisplayId(id).toBundle()).create().start().resume()
                val attrs = requireNotNull(activity).get().window.attributes
                assertEquals(expected.left, attrs.x); assertEquals(expected.top, attrs.y)
                assertEquals(expected.width, attrs.width); assertEquals(expected.height, attrs.height)
            }
            compose.onNodeWithTag("liveGridResize").performClick()
            compose.onNodeWithTag("liveGridCanvas").performTouchInput {
                down(center); moveBy(Offset(-20f,0f)); moveBy(Offset(-10f,0f)); up()
            }
            compose.waitForIdle()
            compose.onNodeWithTag("liveGridMove").performClick()
            val before = requireNotNull(bound?.layout)
            compose.onNodeWithTag("liveGridCanvas").performTouchInput {
                down(center); moveBy(Offset(20f,0f)); moveBy(Offset(10f,0f)); up()
            }
            compose.waitForIdle()
            val after = requireNotNull(bound?.layout)
            assertNotEquals(before.output, after.output)
            compose.runOnIdle {
                val attrs = requireNotNull(activity).get().window.attributes
                assertEquals(after.output.left, attrs.x); assertEquals(after.output.top, attrs.y)
                assertEquals(after.output.width, attrs.width); assertEquals(after.output.height, attrs.height)
                assertFalse(requireNotNull(activity).get().isFinishing)
            }
            compose.onNodeWithTag("liveGridStop").performClick()
            assertTrue(requireNotNull(activity).get().isFinishing)
            assertTrue(requireNotNull(bound).commitLayoutDraft())
            val committed = requireNotNull(preferences.getClusterLayout(scope, after.canvas).custom)
            assertEquals(after.output, committed.output); assertEquals(after.safe, committed.safe)
            compose.runOnIdle { mounted = false }; compose.waitForIdle()
            compose.runOnIdle { bound = null; opened = false; mounted = true }
            compose.waitUntil(5000) { shadowOf(Looper.getMainLooper()).idle(); bound?.layout != null }
            assertEquals(after, bound?.layout)
            // A saved direct calibration is not startup phone authority. The exact tall output
            // remains preserved in storage while fresh hosted135 rejects it without clipping.
            val rejected = ClusterSavedLayoutResolver.resolve(initial, preferences::getClusterLayout)
            if (after.output.top < 135) assertEquals(ClusterLayoutFallbackReason.CANVAS_CHANGED, rejected.fallbackReason)
            val expanded = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
                listOf(ClusterDisplayBinding(profile.role,id)),
                ClusterHostGeometry(id, profile.panel, profile.panel.bounds, legacy)))
            val admitted = ClusterSavedLayoutResolver.resolve(expanded, preferences::getClusterLayout)
            assertNull(admitted.fallbackReason); assertEquals(after.output, admitted.display.effectiveOutput)
            assertEquals(after.safe, admitted.display.safe)
            ClusterVideoGeometry.androidAuto(admitted.display); ClusterVideoGeometry.carPlay(admitted.display)
        } finally {
            compose.runOnIdle { mounted = false }; compose.waitForIdle()
            activity?.pause()?.stop()?.destroy()
            job.cancelAndJoin(); file.delete()
            ReflectionHelpers.setStaticField(Build::class.java, "PRODUCT", oldProduct)
        }
    }
    @Test fun latestLegacyMaximumOpensMossySizedThenActualStartDragSaveRestores() = journey(legacy, profile.output)
    @Test fun arbitraryCustomOutputIsNeverSilentlyClipped() = journey(PixelRect(100,135,1900,760), PixelRect(100,135,1900,760))
}
