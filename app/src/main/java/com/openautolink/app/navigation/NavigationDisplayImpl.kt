package com.openautolink.app.navigation

import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Processes nav_state control messages from the bridge and maintains
 * the current maneuver state for display in UI or cluster service.
 */
class NavigationDisplayImpl : NavigationDisplay {

    private val _currentManeuver = MutableStateFlow<ManeuverState?>(null)
    override val currentManeuver: StateFlow<ManeuverState?> = _currentManeuver.asStateFlow()

    override fun onNavState(state: ControlMessage.NavState) {
        val type = ManeuverMapper.fromWire(state.maneuver)
        // Use pre-formatted distance from bridge if available, else format locally
        val formattedDist = if (!state.displayDistance.isNullOrEmpty() && !state.displayDistanceUnit.isNullOrEmpty()) {
            "${state.displayDistance} ${DistanceFormatter.unitLabel(state.displayDistanceUnit)}"
        } else {
            DistanceFormatter.format(state.distanceMeters)
        }

        val lanes = state.lanes?.map { lane ->
            LaneInfo(lane.directions.map { dir ->
                LaneDirectionInfo(dir.shape, dir.highlighted)
            })
        }

        _currentManeuver.value = ManeuverState(
            type = type,
            distanceMeters = state.distanceMeters,
            formattedDistance = formattedDist,
            roadName = state.road,
            etaSeconds = state.etaSeconds,
            navImageBase64 = state.navImageBase64,
            lanes = lanes,
            cue = state.cue,
            roundaboutExitNumber = state.roundaboutExitNumber,
            currentRoad = state.currentRoad,
            destination = state.destination,
            etaFormatted = state.etaFormatted,
            displayDistance = state.displayDistance,
            displayDistanceUnit = state.displayDistanceUnit,
            timeToArrivalSeconds = state.timeToArrivalSeconds,
            destDistanceMeters = state.destDistanceMeters,
            destDistanceDisplay = state.destDistanceDisplay,
            destDistanceUnit = state.destDistanceUnit
        )
    }

    override fun clear() {
        _currentManeuver.value = null
    }
}
