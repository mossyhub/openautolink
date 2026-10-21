package com.openautolink.app.carplay

import com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType

/** Provider-independent vocabulary. Does not equate trip remaining time with time to next turn. */
internal object CarPlayClusterMetadataAdapter {
    fun maneuver(value: CarPlayNavigationMetadata): ManeuverState? {
        val distance = value.distanceMeters
        // accNav enum names/sign from Maps 23G71 binary reconstruction; accessoryd
        // 24A446 corroborates the wire enum/angle widths. Not owner-phone acceptance.
        val goesLeft = value.junctionExitAngleDegrees?.takeIf { it != 0 }?.let { it < 0 }
        val uTurnLeft = goesLeft ?: when (value.drivingSide) { 0 -> true; 1 -> false; else -> null }
        val rampLeft = goesLeft ?: when (value.drivingSide) { 0 -> false; 1 -> true; else -> null }
        val roundabout = when (value.drivingSide) {
            0 -> ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW
            1 -> ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW
            else -> ManeuverType.UNKNOWN
        }
        val type = when (value.maneuverType) {
            1,20 -> ManeuverType.TURN_LEFT
            2,21 -> ManeuverType.TURN_RIGHT
            4,18,26 -> when (uTurnLeft) { true -> ManeuverType.U_TURN_LEFT; false -> ManeuverType.U_TURN_RIGHT; null -> ManeuverType.UNKNOWN }
            8 -> when (rampLeft) { true -> ManeuverType.OFF_RAMP_SLIGHT_LEFT; false -> ManeuverType.OFF_RAMP_SLIGHT_RIGHT; null -> ManeuverType.UNKNOWN }
            9 -> when (rampLeft) { true -> ManeuverType.ON_RAMP_SLIGHT_LEFT; false -> ManeuverType.ON_RAMP_SLIGHT_RIGHT; null -> ManeuverType.UNKNOWN }
            13 -> ManeuverType.KEEP_LEFT
            14 -> ManeuverType.KEEP_RIGHT
            22 -> ManeuverType.OFF_RAMP_SLIGHT_LEFT
            23 -> ManeuverType.OFF_RAMP_SLIGHT_RIGHT
            in 28..46 -> roundabout
            47 -> ManeuverType.TURN_SHARP_LEFT
            48 -> ManeuverType.TURN_SHARP_RIGHT
            49,52 -> ManeuverType.TURN_SLIGHT_LEFT
            50,53 -> ManeuverType.TURN_SLIGHT_RIGHT
            3,5 -> ManeuverType.STRAIGHT
            11 -> ManeuverType.DEPART
            6 -> when (value.drivingSide) {
                0 -> ManeuverType.ROUNDABOUT_ENTER_CCW
                1 -> ManeuverType.ROUNDABOUT_ENTER_CW
                else -> ManeuverType.ROUNDABOUT_ENTER
            }
            7 -> when (value.drivingSide) {
                0 -> ManeuverType.ROUNDABOUT_EXIT_CCW
                1 -> ManeuverType.ROUNDABOUT_EXIT_CW
                else -> ManeuverType.ROUNDABOUT_EXIT
            }
            10,12,27 -> ManeuverType.DESTINATION
            24 -> ManeuverType.DESTINATION_LEFT
            25 -> ManeuverType.DESTINATION_RIGHT
            15,16,17 -> ManeuverType.FERRY
            // 19 is a roundabout U-turn, but no exit ordinal is sourced for the
            // host's mandatory ordinal. 51 is an unspecified highway change:
            // neither traffic side nor an absent angle proves straight/left/right.
            // Leave unsupported instructions unknown instead of inventing geometry.
            0,19,51 -> ManeuverType.UNKNOWN
            else -> ManeuverType.UNKNOWN
        }
        val exit = (value.maneuverType - 27).takeIf { value.maneuverType in 28..46 && type != ManeuverType.UNKNOWN }
        return ManeuverState(type,distance,"",value.road,null,
            roundaboutExitNumber = exit,
            timeToArrivalSeconds = value.remainingSeconds, destDistanceMeters = value.remainingMeters,
            displayDistance = value.displayDistance?.value, displayDistanceUnit = value.displayDistance?.unit,
            destDistanceDisplay = value.remainingDisplayDistance?.value, destDistanceUnit = value.remainingDisplayDistance?.unit)
    }
}
