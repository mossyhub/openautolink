package com.openautolink.app.drivecard

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import com.openautolink.app.secondary.SecondaryHostGeometry
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Actual View collectors, not a second model test. Prepared before View correction.
 * Parent must execute unchanged baseline RED and candidate GREEN on both SDKs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardViewHistory589Test {
    private val nav = ManeuverState(ManeuverType.STRAIGHT, 100, "100 m", "Fixture road", 100,
        destination = "fixture-A", destDistanceMeters = 20_000, timeToArrivalSeconds = 1200)

    @Test fun destinationReplacementRetainsActualViewHistoryWithoutVehicleReemission() = withLearnedView { view ->
        val before = state(view)
        ClusterNavigationState.updateHost(nav.copy(destination = "fixture-B"))
        idle()
        assertRetained(before, state(view))
        assertNull("Replacement needs independently renewed route inputs", state(view).arrival)
        ClusterNavigationState.updateHost(nav.copy(destination = "fixture-B", roadName = "New road"))
        idle()
        assertNull("Road-only metadata must not refresh replacement distance/time", state(view).arrival)
        ClusterNavigationState.updateHost(nav.copy(destination = "fixture-B", destDistanceMeters = 19_000, timeToArrivalSeconds = 1100))
        idle()
        assertTrue("New changed distance/time may use retained history", requireNotNull(state(view).arrival).approximate)
    }

    @Test fun terminalOnlyValidityBoundaryRetainsHistoryButRevokesArrivalAndCachedRouteReceipts() = withLearnedView { view ->
        val before = state(view)
        // Real host clear/restore without yielding: StateFlow may conflate back to the same
        // route, but its terminal-validity epoch MUST survive. No vehicle re-emission.
        val active = requireNotNull(ClusterNavigationState.state.value)
        val epoch = ClusterNavigationState.terminalValidity.value
        ClusterNavigationState.clearHost()
        ClusterNavigationState.updateHost(active)
        assertEquals(active, ClusterNavigationState.state.value)
        assertTrue(ClusterNavigationState.terminalValidity.value > epoch)
        idle()
        assertRetained(before, state(view))
        assertNull("Terminal validity revokes arrival even when route StateFlow does not change", state(view).arrival)
        ClusterNavigationState.updateHost(nav.copy(destDistanceMeters = 19_500, timeToArrivalSeconds = 1170, roadName = "Another road"))
        idle()
        assertNull("Old distance/time cannot survive validity via a road-only update", state(view).arrival)
        ClusterNavigationState.updateHost(nav.copy(destDistanceMeters = 19_000, timeToArrivalSeconds = 1100))
        idle()
        assertNotNull(state(view).arrival)
    }

    @Test fun routeRemovalRetainsHistoryAndActualViewStopStillClearsIt() = withLearnedView { view ->
        val before = state(view)
        ClusterNavigationState.clearHost()
        idle()
        assertRetained(before, state(view))
        assertNull(state(view).arrival)
        view.stop()
        assertEquals(DriveCardState(), state(view))
    }

    private fun assertRetained(before: DriveCardState, after: DriveCardState) {
        assertEquals("Actual View navigation must not clear vehicle efficiency", before.recentKmPerKwh, after.recentKmPerKwh)
        assertEquals("Actual View navigation must not clear vehicle graph", before.trace, after.trace)
        assertEquals("Actual View keeps live qualified vehicle power", before.powerKw, after.powerKw)
    }
    private fun state(view: DriveCardView) = ReflectionHelpers.getField<DriveCardState>(view, "state")
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun withLearnedView(test: (DriveCardView) -> Unit) {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(170))
        ClusterNavigationState.clearHost()
        ClusterNavigationState.updateHost(nav)
        val start = SystemClock.elapsedRealtime()
        val vehicles = MutableStateFlow(DriveCardBatteryHistory589Test.sample(start))
        mockkObject(ProcessVehicleDataRuntime)
        every { ProcessVehicleDataRuntime.latestVehicleData() } returns vehicles
        every { ProcessVehicleDataRuntime.isActive() } returns true
        val view = DriveCardView(RuntimeEnvironment.getApplication(),
            SecondaryHostGeometry(2000, 810, PixelRect(558, 70, 1694, 668)), true, { true }, {})
        try {
            view.start(); idle()
            for (i in 1..80) {
                ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
                vehicles.value = DriveCardBatteryHistory589Test.sample(SystemClock.elapsedRealtime(), 50_000f - i * 5).copy(speedKmh = 90f)
                idle()
            }
            ClusterNavigationState.updateHost(nav.copy(destDistanceMeters = 19_500, timeToArrivalSeconds = 1170))
            idle()
            assertNotNull("Positive control: actual View learned history", state(view).recentKmPerKwh)
            assertTrue(state(view).trace.isNotEmpty())
            assertNotNull("Positive control: actual View qualified a local arrival", state(view).arrival)
            test(view)
        } finally {
            view.stop(); ClusterNavigationState.clearHost(); unmockkObject(ProcessVehicleDataRuntime)
        }
    }
}
