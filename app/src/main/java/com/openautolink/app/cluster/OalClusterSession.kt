package com.openautolink.app.cluster

import android.os.SystemClock
import android.content.Intent
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.openautolink.app.navigation.ManeuverIconRenderer
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import com.openautolink.app.navigation.VehicleEnergyForecastPolicy
import com.openautolink.app.navigation.LaneInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

/**
 * Standard AAOS cluster session — renders NavigationTemplate directly via onGetTemplate().
 *
 * Used on non-GM AAOS platforms (Volvo, Polestar, emulator) where Templates Host renders
 * the Screen's template directly on the cluster display. The NavigationTemplate shows
 * RoutingInfo with maneuver icon, distance, and road name.
 *
 * GM AAOS ignores this session's Screen — it uses OnStarTurnByTurnManager instead.
 * But both paths call NavigationManager.updateTrip() for compatibility.
 */
class OalClusterSession : Session() {

    companion object {
        private const val TAG = "OalClusterSession"
        private var currentSession: OalClusterSession? = null
    }

    private var screen: OalClusterScreen? = null
    private var navigationManager: NavigationManager? = null
    private var scope: CoroutineScope? = null
    private var isNavigating = false
    private var rejectedManeuver: ManeuverState? = null
    private var observedRoute: Long? = null
    private var retired = false
    private var sessionIdentity = Any()

    private fun isCurrentSession(): Boolean = !retired &&
        (currentSession == null || currentSession === this)

    override fun onCreateScreen(intent: Intent): Screen = synchronized(ClusterBindingLifecycle.lock) {
        currentSession?.retire()
        currentSession = this
        retired = false
        sessionIdentity = Any()
        createScreen(intent)
    }

    private fun createScreen(intent: Intent): Screen {
        val clusterScreen = OalClusterScreen(carContext)
        screen = clusterScreen

        try {
            navigationManager = carContext.getCarService(NavigationManager::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get NavigationManager: ${e.message}")
        }

        navigationManager?.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation(): Unit = synchronized(ClusterBindingLifecycle.lock) {
                if (!isCurrentSession() || observedRoute == null ||
                    observedRoute != ClusterNavigationState.recoveryDemand.value
                ) return@synchronized
                Log.i(TAG, "onStopNavigation callback from Templates Host")
                // Preserve source demand, but never let an old callback suppress a new route.
                isNavigating = false
                ClusterNavigationState.suppressCurrentRoute()
                screen?.updateState(null)
            }
            override fun onAutoDriveEnabled() {}
        })

        val sessionScope = CoroutineScope(Dispatchers.Main)
        scope = sessionScope
        sessionScope.launch { collectNavigationState() }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner): Unit = synchronized(ClusterBindingLifecycle.lock) {
                retire()
            }
        })

        return clusterScreen
    }

    private fun retire() {
        val manager = navigationManager
        val endNavigation = isNavigating
        retired = true
        sessionIdentity = Any()
        isNavigating = false
        if (currentSession === this) currentSession = null
        scope?.cancel()
        scope = null
        screen?.updateState(null)
        screen = null
        navigationManager = null
        if (endNavigation) try { manager?.navigationEnded() } catch (_: Exception) {}
    }

    private data class StateUpdate(
        val maneuver: ManeuverState?, val route: Long?, val active: Boolean,
        val terminalValidity: Long, val session: Any,
    )

    private fun snapshot(maneuver: ManeuverState? = ClusterNavigationState.state.value) = StateUpdate(
        maneuver, ClusterNavigationState.recoveryDemand.value, ClusterNavigationState.isActive,
        ClusterNavigationState.terminalValidity.value, sessionIdentity,
    )

    private suspend fun collectNavigationState() {
        var debounceJob: Job? = null
        combine(
            ClusterNavigationState.state,
            ClusterNavigationState.vehicleEnergyForecast,
            ClusterNavigationState.routeActive,
            ClusterNavigationState.recoveryDemand,
            ClusterNavigationState.terminalValidity,
        ) { _, _, _, _, _ ->
            synchronized(ClusterBindingLifecycle.lock) { snapshot() }
        }.collectLatest { update ->
            debounceJob?.cancel()
            debounceJob = scope?.launch {
                delay(200)
                processStateUpdate(update)
            }
        }
    }

    private fun withdrawGuidance(navManager: NavigationManager) {
        val endNavigation = isNavigating
        isNavigating = false
        screen?.updateState(null)
        if (endNavigation) {
            try { navManager.navigationEnded() } catch (e: Exception) {
                Log.w(TAG, "navigationEnded() failed: ${e.message}")
            }
        }
    }

    // Preserve the direct integration-test entry point, capturing the same guarded snapshot.
    private fun processStateUpdate(maneuver: ManeuverState?) = synchronized(ClusterBindingLifecycle.lock) {
        processStateUpdate(snapshot(maneuver))
    }

    private fun processStateUpdate(update: StateUpdate): Unit = synchronized(ClusterBindingLifecycle.lock) {
        val (maneuver, route, active, terminalValidity, session) = update
        fun isSnapshotCurrent(): Boolean = isCurrentSession() && session === sessionIdentity &&
            route == ClusterNavigationState.recoveryDemand.value && active == ClusterNavigationState.isActive &&
            terminalValidity == ClusterNavigationState.terminalValidity.value &&
            maneuver === ClusterNavigationState.state.value
        if (!isSnapshotCurrent()) return@synchronized
        val navManager = navigationManager ?: return@synchronized
        if (observedRoute != route) {
            rejectedManeuver = null
            withdrawGuidance(navManager)
            if (!isSnapshotCurrent()) return@synchronized
            observedRoute = route
        }
        if (!active || maneuver == null || ClusterNavigationState.isRouteSuppressed) {
            rejectedManeuver = null
            withdrawGuidance(navManager)
            return@synchronized
        }
        if (maneuver == rejectedManeuver) return@synchronized
        val trip = try {
            buildTrip(maneuver)
        } catch (e: Exception) {
            if (!isSnapshotCurrent()) return@synchronized
            rejectedManeuver = maneuver
            Log.e(TAG, "buildTrip() failed: ${e.message}")
            withdrawGuidance(navManager)
            return@synchronized
        }
        if (!isSnapshotCurrent()) return@synchronized
        if (!isNavigating) {
            try {
                // Reentrant stop must be able to revoke ownership during the call.
                isNavigating = true
                navManager.navigationStarted()
                if (!isSnapshotCurrent()) return@synchronized
            } catch (e: Exception) {
                if (!isSnapshotCurrent()) return@synchronized
                isNavigating = false
                Log.e(TAG, "navigationStarted() failed: ${e.message}")
                screen?.updateState(null)
                return@synchronized
            }
        }
        try {
            if (!isSnapshotCurrent()) return@synchronized
            navManager.updateTrip(trip)
            if (!isSnapshotCurrent()) return@synchronized
            rejectedManeuver = null
            screen?.updateState(maneuver)
        } catch (e: Exception) {
            if (!isSnapshotCurrent()) return@synchronized
            rejectedManeuver = maneuver
            Log.e(TAG, "updateTrip() failed: ${e.message}")
            withdrawGuidance(navManager)
        }
    }

    private fun buildTrip(maneuver: ManeuverState): Trip {
        val tripBuilder = Trip.Builder()
        val now = ZonedDateTime.now()

        val maneuverObj = buildManeuver(maneuver, carContext)
        val stepBuilder = Step.Builder()
        stepBuilder.setManeuver(maneuverObj)
        // Use cue text if available (richer instruction from modern proto), else road name
        val cueText = maneuver.cue ?: maneuver.roadName
        cueText?.let { stepBuilder.setCue(it) }
        maneuver.roadName?.let { stepBuilder.setRoad(it) }

        // Add lane guidance from modern NavigationState
        maneuver.lanes?.let { laneInfoList ->
            if (laneInfoList.isNotEmpty()) {
                for (lane in buildLanes(laneInfoList)) {
                    stepBuilder.addLane(lane)
                }
            }
        }

        val distance = toOptionalDistance(
            maneuver.distanceMeters,
            ClusterNavigationState.distanceUnits,
            maneuver.displayDistance,
            maneuver.displayDistanceUnit
        )
        val stepEstimate = ClusterTravelEstimate.buildDistance(distance, maneuver.etaSeconds?.toLong(), now)
        tripBuilder.addStep(stepBuilder.build(), stepEstimate)

        // Add destination info when available (address + arrival ETA + remaining distance)
        maneuver.destination?.let { destAddress ->
            val destBuilder = Destination.Builder()
            destBuilder.setName(destAddress)
            destBuilder.setAddress(destAddress)

            val destDistance = maneuver.destDistanceMeters?.let { meters -> toDistance(
                meters,
                ClusterNavigationState.distanceUnits,
                maneuver.destDistanceDisplay,
                maneuver.destDistanceUnit
            ) }
            val destEta = maneuver.timeToArrivalSeconds?.takeIf { it >= 0 }?.let { now.plusSeconds(it) }
            val destEstimateBuilder = if (destDistance != null && destEta != null)
                TravelEstimate.Builder(destDistance, destEta) else null
            val forecast = ClusterNavigationState.vehicleEnergyForecast.value
            if (VehicleEnergyForecastPolicy.isFresh(forecast, SystemClock.elapsedRealtime())) {
                VehicleEnergyForecastPolicy.tripText(
                    forecast,
                    ClusterNavigationState.batteryCapacityWh,
                )?.let { destEstimateBuilder?.setTripText(CarText.create(it)) }
            }
            val destEstimate = destEstimateBuilder?.build() ?: ClusterTravelEstimate.buildDistance(
                destDistance, maneuver.timeToArrivalSeconds, now)
            tripBuilder.addDestination(destBuilder.build(), destEstimate)
        }

        tripBuilder.setLoading(false)
        return tripBuilder.build()
    }
}

/**
 * Screen rendered on the cluster for standard AAOS platforms.
 * Returns NavigationTemplate with RoutingInfo showing maneuver, distance, road.
 */
class OalClusterScreen(carContext: CarContext) : Screen(carContext) {

    private var currentManeuver: ManeuverState? = null

    private val actionStrip = ActionStrip.Builder()
        .addAction(Action.APP_ICON)
        .build()

    fun updateState(maneuver: ManeuverState?) {
        currentManeuver = maneuver
        invalidate()
    }

    override fun onGetTemplate(): Template {
        // RoutingInfo requires a real distance. Trip still carries the retained step/cue
        // when an estimate expires; no model-supported unknown distance exists here.
        val maneuver = currentManeuver
        val distance = maneuver?.let {
            toOptionalDistance(it.distanceMeters, ClusterNavigationState.distanceUnits,
                it.displayDistance, it.displayDistanceUnit)
        }
        if (maneuver == null || distance == null) return NavigationTemplate.Builder()
            .setActionStrip(actionStrip)
            .build()

        return try {
            val maneuverObj = buildManeuver(maneuver, carContext)
            val stepBuilder = Step.Builder()
            stepBuilder.setManeuver(maneuverObj)
            val cueText = maneuver.cue ?: maneuver.roadName
            cueText?.let { stepBuilder.setCue(it) }
            maneuver.roadName?.let { stepBuilder.setRoad(it) }

            // RoutingInfo requires a lanes image whenever lane primitives are
            // supplied. Our transport carries no lanes image; do not let optional
            // lanes blank otherwise usable maneuver/road/distance guidance here.
            // Trips retain the primitives for hosts that can consume them.
            val routingInfo = RoutingInfo.Builder()
                .setCurrentStep(stepBuilder.build(), distance)
                .build()

            NavigationTemplate.Builder()
                .setActionStrip(actionStrip)
                .setNavigationInfo(routingInfo)
                .build()
        } catch (e: Exception) {
            Log.e("OalClusterScreen", "Failed to build template: ${e.message}")
            NavigationTemplate.Builder()
                .setActionStrip(actionStrip)
                .build()
        }
    }
}

// ── Shared helpers for cluster sessions ──

internal fun buildManeuver(maneuver: ManeuverState, carContext: CarContext): Maneuver {
    var type = mapManeuverTypeToCarApp(maneuver.type)
    // Named semantics remain authoritative when AndroidX can represent them.
    // A bitmap never waives the required positive ordinal on combined types.
    if (isRoundaboutManeuverType(type) && (maneuver.roundaboutExitNumber ?: 0) <= 0) {
        type = Maneuver.TYPE_UNKNOWN
    }
    val builder = Maneuver.Builder(type)
    if (isRoundaboutManeuverType(type)) {
        builder.setRoundaboutExitNumber(requireNotNull(maneuver.roundaboutExitNumber))
    }
    // NavigationDisplayImpl scopes artwork to the current instruction: explicit
    // turns clear absent artwork; distance-only deltas retain the association.
    val bitmap = decodeCurrentManeuverArtwork(maneuver.navImageBase64)
    if (bitmap != null) {
        builder.setIcon(CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build())
    } else if (maneuver.type != ManeuverType.UNKNOWN) {
        // Generic roundabouts have known identity, but no sourced circulation.
        // Missing required geometry also uses neutral, never a fictional exit.
        val resId = if (type == Maneuver.TYPE_UNKNOWN) {
            com.openautolink.app.R.drawable.nav_roundabout_neutral
        } else ManeuverIconRenderer.drawableForManeuver(maneuver.type)
        builder.setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build())
    }
    return builder.build()
}

/** Bound encoded bytes, decoded bytes, dimensions and pixels before allocation. */
private fun decodeCurrentManeuverArtwork(encoded: String?): android.graphics.Bitmap? {
    if (encoded == null || encoded.length > 1_398_104) return null // 1 MiB base64 ceiling
    return try {
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        if (bytes.isEmpty() || bytes.size > 1_048_576) return null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..1024 || bounds.outHeight !in 1..1024 ||
            bounds.outWidth.toLong() * bounds.outHeight > 1_048_576L) return null
        val options = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            inScaled = false
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.takeIf {
            it.width == bounds.outWidth && it.height == bounds.outHeight
        }
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: java.lang.RuntimeException) {
        null
    }
}

/** The ordinal-only combined types require a positive, sourced exit number. */
internal fun isRoundaboutManeuverType(type: Int): Boolean =
    type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW ||
    type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW

internal fun mapManeuverTypeToCarApp(type: ManeuverType): Int = when (type) {
    ManeuverType.DEPART -> Maneuver.TYPE_DEPART
    ManeuverType.STRAIGHT -> Maneuver.TYPE_STRAIGHT
    ManeuverType.TURN_SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
    ManeuverType.TURN_LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
    ManeuverType.TURN_SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
    ManeuverType.TURN_SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
    ManeuverType.TURN_RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
    ManeuverType.TURN_SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
    ManeuverType.U_TURN_LEFT -> Maneuver.TYPE_U_TURN_LEFT
    ManeuverType.U_TURN_RIGHT -> Maneuver.TYPE_U_TURN_RIGHT
    ManeuverType.KEEP_LEFT -> Maneuver.TYPE_KEEP_LEFT
    ManeuverType.KEEP_RIGHT -> Maneuver.TYPE_KEEP_RIGHT
    ManeuverType.MERGE_LEFT -> Maneuver.TYPE_MERGE_LEFT
    ManeuverType.MERGE_RIGHT -> Maneuver.TYPE_MERGE_RIGHT
    ManeuverType.MERGE_UNSPECIFIED -> Maneuver.TYPE_MERGE_SIDE_UNSPECIFIED
    ManeuverType.FORK_LEFT -> Maneuver.TYPE_FORK_LEFT
    ManeuverType.FORK_RIGHT -> Maneuver.TYPE_FORK_RIGHT
    ManeuverType.ON_RAMP_LEFT -> Maneuver.TYPE_ON_RAMP_NORMAL_LEFT
    ManeuverType.ON_RAMP_RIGHT -> Maneuver.TYPE_ON_RAMP_NORMAL_RIGHT
    ManeuverType.ON_RAMP_SLIGHT_LEFT -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT
    ManeuverType.ON_RAMP_SLIGHT_RIGHT -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT
    ManeuverType.ON_RAMP_SHARP_LEFT -> Maneuver.TYPE_ON_RAMP_SHARP_LEFT
    ManeuverType.ON_RAMP_SHARP_RIGHT -> Maneuver.TYPE_ON_RAMP_SHARP_RIGHT
    ManeuverType.ON_RAMP_U_TURN_LEFT -> Maneuver.TYPE_ON_RAMP_U_TURN_LEFT
    ManeuverType.ON_RAMP_U_TURN_RIGHT -> Maneuver.TYPE_ON_RAMP_U_TURN_RIGHT
    ManeuverType.OFF_RAMP_LEFT -> Maneuver.TYPE_OFF_RAMP_NORMAL_LEFT
    ManeuverType.OFF_RAMP_RIGHT -> Maneuver.TYPE_OFF_RAMP_NORMAL_RIGHT
    ManeuverType.OFF_RAMP_SLIGHT_LEFT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT
    ManeuverType.OFF_RAMP_SLIGHT_RIGHT -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT
    ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT,
    ManeuverType.ROUNDABOUT_ENTER_AND_EXIT -> Maneuver.TYPE_UNKNOWN
    ManeuverType.ROUNDABOUT_ENTER_CW -> Maneuver.TYPE_ROUNDABOUT_ENTER_CW
    ManeuverType.ROUNDABOUT_ENTER_CCW -> Maneuver.TYPE_ROUNDABOUT_ENTER_CCW
    ManeuverType.ROUNDABOUT_EXIT_CW -> Maneuver.TYPE_ROUNDABOUT_EXIT_CW
    ManeuverType.ROUNDABOUT_EXIT_CCW -> Maneuver.TYPE_ROUNDABOUT_EXIT_CCW
    ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CW -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
    ManeuverType.ROUNDABOUT_ENTER_AND_EXIT_CCW -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
    ManeuverType.DESTINATION -> Maneuver.TYPE_DESTINATION
    ManeuverType.DESTINATION_STRAIGHT -> Maneuver.TYPE_DESTINATION_STRAIGHT
    ManeuverType.DESTINATION_LEFT -> Maneuver.TYPE_DESTINATION_LEFT
    ManeuverType.DESTINATION_RIGHT -> Maneuver.TYPE_DESTINATION_RIGHT
    ManeuverType.FERRY -> Maneuver.TYPE_FERRY_BOAT
    ManeuverType.FERRY_TRAIN -> Maneuver.TYPE_FERRY_TRAIN
    ManeuverType.NAME_CHANGE -> Maneuver.TYPE_NAME_CHANGE
    ManeuverType.UNKNOWN -> Maneuver.TYPE_UNKNOWN
}

/**
 * Map AA proto lane direction shape string to Car App LaneDirection.SHAPE_* constant.
 */
internal fun mapLaneShape(shape: String): Int = when (shape) {
    "straight" -> LaneDirection.SHAPE_STRAIGHT
    "normal_left" -> LaneDirection.SHAPE_NORMAL_LEFT
    "normal_right" -> LaneDirection.SHAPE_NORMAL_RIGHT
    "slight_left" -> LaneDirection.SHAPE_SLIGHT_LEFT
    "slight_right" -> LaneDirection.SHAPE_SLIGHT_RIGHT
    "sharp_left" -> LaneDirection.SHAPE_SHARP_LEFT
    "sharp_right" -> LaneDirection.SHAPE_SHARP_RIGHT
    "u_turn_left" -> LaneDirection.SHAPE_U_TURN_LEFT
    "u_turn_right" -> LaneDirection.SHAPE_U_TURN_RIGHT
    else -> LaneDirection.SHAPE_UNKNOWN
}

/**
 * Build Car App Lane objects from ManeuverState lane data.
 */
internal fun buildLanes(lanes: List<LaneInfo>): List<Lane> {
    return lanes.map { laneInfo ->
        val builder = Lane.Builder()
        for (dir in laneInfo.directions) {
            builder.addDirection(
                LaneDirection.create(mapLaneShape(dir.shape), dir.highlighted)
            )
        }
        builder.build()
    }
}

/** Admit sourced display distance independently of optional raw meters; unknown stays unknown. */
internal fun toOptionalDistance(
    meters: Int?,
    distanceUnits: String = "auto",
    displayValue: String? = null,
    displayUnit: String? = null
): androidx.car.app.model.Distance? {
    val value = displayValue?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
    val unit = when (displayUnit) {
        "meters" -> androidx.car.app.model.Distance.UNIT_METERS
        "kilometers", "kilometers_p1" -> androidx.car.app.model.Distance.UNIT_KILOMETERS
        "miles", "miles_p1" -> androidx.car.app.model.Distance.UNIT_MILES
        "feet" -> androidx.car.app.model.Distance.UNIT_FEET
        "yards" -> androidx.car.app.model.Distance.UNIT_YARDS
        else -> null
    }
    if (value != null && unit != null) return androidx.car.app.model.Distance.create(value, unit)
    return meters?.takeIf { it >= 0 }?.let { toDistance(it, distanceUnits) }
}

/** Convert known raw meters, retaining the existing locale and display-unit behavior. */
internal fun toDistance(
    meters: Int,
    distanceUnits: String = "auto",
    displayValue: String? = null,
    displayUnit: String? = null
): androidx.car.app.model.Distance {
    // Prefer pre-formatted values from the phone
    if (!displayValue.isNullOrEmpty() && !displayUnit.isNullOrEmpty()) {
        val value = displayValue.toDoubleOrNull()
        if (value != null) {
            val unit = when (displayUnit) {
                "meters" -> androidx.car.app.model.Distance.UNIT_METERS
                "kilometers", "kilometers_p1" -> androidx.car.app.model.Distance.UNIT_KILOMETERS
                "miles", "miles_p1" -> androidx.car.app.model.Distance.UNIT_MILES
                "feet" -> androidx.car.app.model.Distance.UNIT_FEET
                "yards" -> androidx.car.app.model.Distance.UNIT_YARDS
                else -> null
            }
            if (unit != null) {
                return androidx.car.app.model.Distance.create(value, unit)
            }
        }
    }

    // Fallback: convert raw meters
    val useImperial = when (distanceUnits) {
        "imperial" -> true
        "metric" -> false
        else -> java.util.Locale.getDefault().country in setOf("US", "LR", "MM")
    }
    return if (useImperial) {
        val miles = meters / 1609.344
        if (miles >= 0.2) {
            androidx.car.app.model.Distance.create(
                miles, androidx.car.app.model.Distance.UNIT_MILES
            )
        } else {
            val feet = (meters / 0.3048).toInt()
            androidx.car.app.model.Distance.create(
                feet.toDouble(), androidx.car.app.model.Distance.UNIT_FEET
            )
        }
    } else {
        if (meters >= 1000) {
            androidx.car.app.model.Distance.create(
                meters / 1000.0, androidx.car.app.model.Distance.UNIT_KILOMETERS
            )
        } else {
            androidx.car.app.model.Distance.create(
                meters.toDouble(), androidx.car.app.model.Distance.UNIT_METERS
            )
        }
    }
}
