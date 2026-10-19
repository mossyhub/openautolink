package com.openautolink.app.cluster

import android.app.Application
import androidx.car.app.CarContext
import androidx.car.app.Session
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.*
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

/** Exercises the actual private Trip builders and non-GM RoutingInfo consumer. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class DisplayDistanceConsumerTest {
    private val context = mockk<CarContext>(relaxed = true).also { c ->
        val app = RuntimeEnvironment.getApplication()
        every { c.resources } returns app.resources
        every { c.packageName } returns app.packageName
    }
    private fun state(value: String? = "0", unit: String? = "feet", raw: Int? = null) =
        ManeuverState(ManeuverType.UNKNOWN, raw, "", "Road", null,
            displayDistance = value, displayDistanceUnit = unit)

    private fun trips(state: ManeuverState): List<Trip> = listOf<Session>(
        spyk(ClusterMainSession()), spyk(OalClusterSession())
    ).map { session ->
        every { session.carContext } returns context
        session.javaClass.getDeclaredMethod("buildTrip", ManeuverState::class.java)
            .apply { isAccessible = true }.invoke(session, state) as Trip
    }
    private fun routing(state: ManeuverState): RoutingInfo? = OalClusterScreen(context).let {
        it.updateState(state)
        (it.onGetTemplate() as NavigationTemplate).navigationInfo as RoutingInfo?
    }
    private fun assertDistance(expected: Double?, unit: Int?, state: ManeuverState) {
        for (trip in trips(state)) {
            assertEquals("Road", trip.steps.single().road.toString())
            val estimate = trip.stepTravelEstimates.single()
            if (expected == null) assertNull(estimate.remainingDistance) else {
                assertNotNull("sourced distance must survive real Trip builder", estimate.remainingDistance)
                assertEquals(expected, estimate.remainingDistance!!.displayDistance, 0.0)
                assertEquals(unit, estimate.remainingDistance!!.displayUnit)
            }
            assertNull("no sourced time means no fabricated arrival-now", estimate.arrivalTimeAtDestination)
            assertEquals(TravelEstimate.REMAINING_TIME_UNKNOWN, estimate.remainingTimeSeconds)
        }
        val info = routing(state)
        if (expected == null) assertNull(info) else {
            assertNotNull("display-only distance must reach RoutingInfo", info)
            assertEquals(expected, info!!.currentDistance!!.displayDistance, 0.0)
            assertEquals(unit, info.currentDistance!!.displayUnit)
        }
    }
    @Test fun displayOnlyPositiveDecimalSurvivesBothTripsAndRouting() {
        assertDistance(12.5, Distance.UNIT_FEET, state("12.5"))
    }
    @Test fun everyRecognizedDisplayUnitPreservesSourcedDecimal() {
        for ((unit, model) in mapOf("meters" to Distance.UNIT_METERS,
            "kilometers" to Distance.UNIT_KILOMETERS, "kilometers_p1" to Distance.UNIT_KILOMETERS,
            "miles" to Distance.UNIT_MILES, "miles_p1" to Distance.UNIT_MILES,
            "feet" to Distance.UNIT_FEET, "yards" to Distance.UNIT_YARDS)) {
            assertDistance(1.5, model, state("1.5", unit, -1))
        }
    }
    private fun invalidPairs() = listOf(
        null to null, "0" to null, null to "feet", "" to "feet", "bad" to "feet",
        "NaN" to "feet", "Infinity" to "feet", "-Infinity" to "feet",
        "-0.1" to "feet", "2" to "unknown"
    )
    @Test fun invalidOrPartialDisplayWithoutRawStaysUnknownNeverZeroOrNow() {
        for ((value, unit) in invalidPairs()) for (raw in listOf(null, -1)) {
            assertDistance(null, null, state(value, unit, raw))
        }
    }
    @Test fun validRawFallbackDistinguishesZeroFromAbsentAndRejectsInvalidDisplay() {
        val previous = ClusterNavigationState.distanceUnits
        try {
            ClusterNavigationState.distanceUnits = "metric"
            for ((value, unit) in invalidPairs()) {
                assertDistance(0.0, Distance.UNIT_METERS, state(value, unit, 0))
                assertDistance(125.0, Distance.UNIT_METERS, state(value, unit, 125))
            }
            assertDistance(null, null, state(null, null, null))
        } finally { ClusterNavigationState.distanceUnits = previous }
    }
    @Test fun sourcedDisplayTakesPrecedenceOverConflictingRaw() {
        assertDistance(0.0, Distance.UNIT_FEET, state("0", "feet", 999))
    }
    @Test fun capturedFirstTripIsSafeForArchivedAaosSerializerDistanceDereference() {
        // Host-shaped boundary, not the full host converter: DistanceUtils.getMeters
        // dereferences displayUnit before its FEET branch rounds displayDistance*0.3048.
        for (trip in trips(state().copy(destination = "Destination", destDistanceMeters = 1500,
            timeToArrivalSeconds = 120))) {
            val estimate = trip.stepTravelEstimates.single()
            val distance = requireNotNull(estimate.remainingDistance)
            assertEquals(Distance.UNIT_FEET, distance.displayUnit)
            assertEquals(0, Math.round(distance.displayDistance * 0.3048).toInt())
            assertNull(estimate.arrivalTimeAtDestination)
            assertEquals(TravelEstimate.REMAINING_TIME_UNKNOWN, estimate.remainingTimeSeconds)
            assertNotNull(trip.destinationTravelEstimates.single().remainingDistance)
        }
    }
    @Test fun displayOnlyZeroFeetSurvivesBothTripsAndRoutingWithoutFakeEta() {
        assertDistance(0.0, Distance.UNIT_FEET, state())
    }
}
