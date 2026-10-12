package com.openautolink.app.carplay

import com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType

/** Provider-independent vocabulary. Does not equate trip remaining time with time to next turn. */
internal object CarPlayClusterMetadataAdapter {
    fun maneuver(value: CarPlayNavigationMetadata): ManeuverState? {
        val distance = value.distanceMeters
        val type = when (value.maneuverType) {
            1,20 -> ManeuverType.TURN_LEFT
            2,21 -> ManeuverType.TURN_RIGHT
            47 -> ManeuverType.TURN_SHARP_LEFT
            48 -> ManeuverType.TURN_SHARP_RIGHT
            13,22,49,52 -> ManeuverType.TURN_SLIGHT_LEFT
            14,23,50,53 -> ManeuverType.TURN_SLIGHT_RIGHT
            3,5,8,9,11,51 -> ManeuverType.STRAIGHT
            6 -> ManeuverType.ROUNDABOUT_ENTER
            7 -> ManeuverType.ROUNDABOUT_EXIT
            10,12,24,25,27 -> ManeuverType.DESTINATION
            else -> ManeuverType.UNKNOWN
        }
        return ManeuverState(type,distance,"",value.road,null,
            timeToArrivalSeconds = value.remainingSeconds, destDistanceMeters = value.remainingMeters)
    }
}
