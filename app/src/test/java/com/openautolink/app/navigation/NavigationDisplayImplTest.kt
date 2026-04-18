package com.openautolink.app.navigation

import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationDisplayImplTest {

    @Test
    fun `initial state is null`() {
        val display = NavigationDisplayImpl()
        assertNull(display.currentManeuver.value)
    }

    @Test
    fun `onNavState updates current maneuver`() {
        val display = NavigationDisplayImpl()

        display.onNavState(
            ControlMessage.NavState(
                maneuver = "turn_right",
                distanceMeters = 150,
                road = "Main St",
                etaSeconds = 420
            )
        )

        val state = display.currentManeuver.value
        assertNotNull(state)
        assertEquals(ManeuverType.TURN_RIGHT, state!!.type)
        assertEquals(150, state.distanceMeters)
        assertEquals("Main St", state.roadName)
        assertEquals(420, state.etaSeconds)
        assert(state.formattedDistance.isNotEmpty()) { "Distance should be formatted" }
    }

    @Test
    fun `onNavState with null maneuver maps to UNKNOWN`() {
        val display = NavigationDisplayImpl()

        display.onNavState(
            ControlMessage.NavState(
                maneuver = null,
                distanceMeters = 100,
                road = "Oak Ave",
                etaSeconds = null
            )
        )

        val state = display.currentManeuver.value
        assertNotNull(state)
        assertEquals(ManeuverType.UNKNOWN, state!!.type)
        assertEquals("Oak Ave", state.roadName)
    }

    @Test
    fun `onNavState with all null fields`() {
        val display = NavigationDisplayImpl()

        display.onNavState(
            ControlMessage.NavState(
                maneuver = null,
                distanceMeters = null,
                road = null,
                etaSeconds = null
            )
        )

        val state = display.currentManeuver.value
        assertNotNull(state)
        assertEquals(ManeuverType.UNKNOWN, state!!.type)
        assertNull(state.distanceMeters)
        assertNull(state.roadName)
        assertEquals("", state.formattedDistance)
    }

    @Test
    fun `clear removes maneuver state`() {
        val display = NavigationDisplayImpl()

        display.onNavState(
            ControlMessage.NavState("turn_left", 200, "Elm St", 60)
        )
        assertNotNull(display.currentManeuver.value)

        display.clear()
        assertNull(display.currentManeuver.value)
    }

    @Test
    fun `onNavState updates replace previous state`() {
        val display = NavigationDisplayImpl()

        display.onNavState(
            ControlMessage.NavState("turn_left", 500, "First St", 120)
        )
        assertEquals(ManeuverType.TURN_LEFT, display.currentManeuver.value!!.type)

        display.onNavState(
            ControlMessage.NavState("turn_right", 100, "Second St", 30)
        )
        val state = display.currentManeuver.value
        assertEquals(ManeuverType.TURN_RIGHT, state!!.type)
        assertEquals(100, state.distanceMeters)
        assertEquals("Second St", state.roadName)
    }

    @Test
    fun `onNavState uses bridge display distance for decimal miles`() {
        val display = NavigationDisplayImpl()

        display.onNavState(
            ControlMessage.NavState(
                maneuver = "turn_right",
                distanceMeters = 4506,
                road = "NE 40th St W",
                etaSeconds = 480,
                displayDistance = "2.8",
                displayDistanceUnit = "miles_p1"
            )
        )

        val state = display.currentManeuver.value
        assertNotNull(state)
        assertEquals("2.8 mi", state!!.formattedDistance)
        assertEquals("2.8", state.displayDistance)
        assertEquals("miles_p1", state.displayDistanceUnit)
    }

    @Test
    fun `onNavState with destination details populates all fields`() {
        val display = NavigationDisplayImpl()

        display.onNavState(
            ControlMessage.NavState(
                maneuver = "turn_right",
                distanceMeters = 150,
                road = "Main St",
                etaSeconds = 30,
                destination = "123 Elm St",
                etaFormatted = "2:45 PM",
                timeToArrivalSeconds = 1800,
                destDistanceMeters = 25000,
                destDistanceDisplay = "15.5",
                destDistanceUnit = "miles"
            )
        )

        val state = display.currentManeuver.value
        assertNotNull(state)
        assertEquals("123 Elm St", state!!.destination)
        assertEquals("2:45 PM", state.etaFormatted)
        assertEquals(1800L, state.timeToArrivalSeconds)
        assertEquals(25000, state.destDistanceMeters)
        assertEquals("15.5", state.destDistanceDisplay)
        assertEquals("miles", state.destDistanceUnit)
    }

    @Test
    fun `onNavState without destination details has null destination fields`() {
        val display = NavigationDisplayImpl()

        display.onNavState(
            ControlMessage.NavState(
                maneuver = "straight",
                distanceMeters = 500,
                road = "Highway 101",
                etaSeconds = 60
            )
        )

        val state = display.currentManeuver.value
        assertNotNull(state)
        assertNull(state!!.destination)
        assertNull(state.timeToArrivalSeconds)
        assertNull(state.destDistanceMeters)
        assertNull(state.destDistanceDisplay)
        assertNull(state.destDistanceUnit)
    }
}
