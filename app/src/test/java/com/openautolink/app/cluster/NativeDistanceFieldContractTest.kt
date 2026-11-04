package com.openautolink.app.cluster

import android.app.Application
import androidx.car.app.CarContext
import androidx.car.app.Session
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Trip
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Actual AndroidX Trip producers, not a reconstruction of GM's renderer.
 * These tests guard the producer boundary; they do not prove physical HUD output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeDistanceFieldContractTest {
    private val context = mockk<CarContext>(relaxed = true).also { c ->
        val app = RuntimeEnvironment.getApplication()
        every { c.resources } returns app.resources
        every { c.packageName } returns app.packageName
    }

    private fun trips(value: String?, wireUnit: String?, raw: Int?): List<Trip> {
        val state = ManeuverState(ManeuverType.UNKNOWN, raw, "", "Test road", null,
            cue = "Test instruction", displayDistance = value, displayDistanceUnit = wireUnit)
        return listOf<Session>(spyk(ClusterMainSession()), spyk(OalClusterSession())).map { session ->
            every { session.carContext } returns context
            session.javaClass.getDeclaredMethod("buildTrip", ManeuverState::class.java)
                .apply { isAccessible = true }.invoke(session, state) as Trip
        }
    }

    private fun assertFields(value: String?, wireUnit: String?, raw: Int?, expected: Double, unit: Int) {
        for (trip in trips(value, wireUnit, raw)) {
            assertEquals(1, trip.steps.size)
            assertEquals(1, trip.stepTravelEstimates.size)
            val distance = requireNotNull(trip.stepTravelEstimates.single().remainingDistance)
            assertEquals(expected, distance.displayDistance, 0.0000001)
            assertEquals(unit, distance.displayUnit)
            // Distance is not prepended to or embedded in either instruction field.
            assertEquals("Test instruction", trip.steps.single().cue.toString())
            assertEquals("Test road", trip.steps.single().road.toString())
        }
    }

    @Test fun captured658MeterPointFourMilesIsOneNumericFieldNotZeroPlusLabel() {
        // Real AA NavigationPosition step-distance tuple, archived logcat line 15468.
        assertFields("0.4", "miles_p1", 658, 0.4, Distance.UNIT_MILES)
    }

    @Test fun belowAndAboveOneMileDoNotTruncateNumericFieldOrModifyCue() {
        for (value in listOf("0.4", "1.0", "2.0")) {
            assertFields(value, "miles_p1", null, value.toDouble(), Distance.UNIT_MILES)
        }
    }

    @Test fun genuineZeroFeetAndMetersRemainZeroNotAbsent() {
        assertFields("0", "feet", null, 0.0, Distance.UNIT_FEET)
        assertFields("0", "meters", null, 0.0, Distance.UNIT_METERS)
    }

    @Test fun kilometerAndYardDecimalsRemainNumericAndSeparateFromCue() {
        assertFields("0.4", "kilometers_p1", null, 0.4, Distance.UNIT_KILOMETERS)
        assertFields("12.5", "yards", null, 12.5, Distance.UNIT_YARDS)
    }

    @Test fun rawOnlyFallbackHonorsUnitSettingWithoutDuplicatingInstruction() {
        val previous = ClusterNavigationState.distanceUnits
        try {
            ClusterNavigationState.distanceUnits = "metric"
            assertFields(null, null, 658, 658.0, Distance.UNIT_METERS)
            assertFields(null, null, 1500, 1.5, Distance.UNIT_KILOMETERS)
            ClusterNavigationState.distanceUnits = "imperial"
            assertFields(null, null, 658, 658 / 1609.344, Distance.UNIT_MILES)
            assertFields(null, null, 0, 0.0, Distance.UNIT_FEET)
        } finally {
            ClusterNavigationState.distanceUnits = previous
        }
    }
}
