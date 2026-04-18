package com.openautolink.app.navigation

import com.openautolink.app.transport.ControlMessage
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages navigation state received from the bridge (originally from Android Auto).
 * Provides maneuver information for cluster display and nav overlay.
 */
interface NavigationDisplay {
    /** Current maneuver state, null when no navigation active. */
    val currentManeuver: StateFlow<ManeuverState?>

    /** Process a nav_state control message from the bridge. */
    fun onNavState(state: ControlMessage.NavState)

    /** Clear navigation state (e.g., on phone disconnect). */
    fun clear()
}

/**
 * Navigation maneuver state for display in cluster or nav overlay.
 */
data class ManeuverState(
    val type: ManeuverType,
    val distanceMeters: Int?,
    val formattedDistance: String,
    val roadName: String?,
    val etaSeconds: Int?,
    val navImageBase64: String? = null,
    val lanes: List<LaneInfo>? = null,
    val cue: String? = null,
    val roundaboutExitNumber: Int? = null,
    val currentRoad: String? = null,
    val destination: String? = null,
    val etaFormatted: String? = null,
    val displayDistance: String? = null,
    val displayDistanceUnit: String? = null,
    val timeToArrivalSeconds: Long? = null,
    val destDistanceMeters: Int? = null,
    val destDistanceDisplay: String? = null,
    val destDistanceUnit: String? = null
)

data class LaneInfo(val directions: List<LaneDirectionInfo>)
data class LaneDirectionInfo(val shape: String, val highlighted: Boolean)

/**
 * Maneuver types mapped from bridge nav_state maneuver strings.
 * These align with Android Auto navigation turn types.
 */
enum class ManeuverType {
    UNKNOWN,
    DEPART,
    STRAIGHT,
    TURN_SLIGHT_LEFT,
    TURN_LEFT,
    TURN_SHARP_LEFT,
    TURN_SLIGHT_RIGHT,
    TURN_RIGHT,
    TURN_SHARP_RIGHT,
    U_TURN_LEFT,
    U_TURN_RIGHT,
    KEEP_LEFT,
    KEEP_RIGHT,
    MERGE_LEFT,
    MERGE_RIGHT,
    MERGE_UNSPECIFIED,
    FORK_LEFT,
    FORK_RIGHT,
    ON_RAMP_LEFT,
    ON_RAMP_RIGHT,
    ON_RAMP_SLIGHT_LEFT,
    ON_RAMP_SLIGHT_RIGHT,
    ON_RAMP_SHARP_LEFT,
    ON_RAMP_SHARP_RIGHT,
    ON_RAMP_U_TURN_LEFT,
    ON_RAMP_U_TURN_RIGHT,
    OFF_RAMP_LEFT,
    OFF_RAMP_RIGHT,
    OFF_RAMP_SLIGHT_LEFT,
    OFF_RAMP_SLIGHT_RIGHT,
    ROUNDABOUT_ENTER,
    ROUNDABOUT_EXIT,
    ROUNDABOUT_ENTER_AND_EXIT_CW,
    ROUNDABOUT_ENTER_AND_EXIT_CCW,
    DESTINATION,
    DESTINATION_STRAIGHT,
    DESTINATION_LEFT,
    DESTINATION_RIGHT,
    FERRY,
    FERRY_TRAIN,
    NAME_CHANGE
}
