package com.openautolink.app.cluster

import android.content.Intent
import android.util.Log
import com.openautolink.app.diagnostics.DiagnosticLog
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.openautolink.app.navigation.ManeuverState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime

/**
 * GM AAOS cluster session — relays Trip data via NavigationManager.updateTrip().
 *
 * GM has an internal cluster manager (OnStarTurnByTurnManager) that consumes
 * NavigationManager data and renders turn-by-turn on the instrument cluster.
 * The [RelayScreen] is never visible — GM's system ignores it.
 *
 * Primary/secondary multiplexing handles Templates Host creating multiple sessions:
 * - AAOS emulator: creates DISPLAY_TYPE_MAIN + DISPLAY_TYPE_CLUSTER (two sessions)
 * - GM AAOS: creates only DISPLAY_TYPE_MAIN (one session)
 * Only the first (primary) owns NavigationManager. Subsequent sessions are passive.
 */
class ClusterMainSession : Session() {

    companion object {
        private const val TAG = "ClusterMain"

        /** Terminal CPManeuverType values that indicate navigation is complete. */
        private val TERMINAL_TYPES = setOf(
            "destination", "destination_left", "destination_right", "arrive"
        )

        private const val ARRIVAL_TIMEOUT_MS = 10_000L

        @Volatile
        private var primarySession: ClusterMainSession? = null
    }

    private var navigationManager: NavigationManager? = null
    private var scope: CoroutineScope? = null
    private var isNavigating = false
    private var isPrimary = false
    private var hasSeenActiveNav = false
    private var arrivalTimeoutJob: Job? = null

    override fun onCreateScreen(intent: Intent): Screen {
        ClusterBindingState.sessionAlive = true

        isPrimary = primarySession == null
        if (isPrimary) {
            primarySession = this
            Log.i(TAG, "Primary session created — owns NavigationManager")
            DiagnosticLog.i("cluster", "ClusterMainSession created (primary)")
        } else {
            Log.i(TAG, "Secondary session created — passive")
            return RelayScreen(carContext)
        }

        try {
            navigationManager = carContext.getCarService(NavigationManager::class.java)
            Log.i(TAG, "NavigationManager obtained")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get NavigationManager: ${e.message}")
        }

        navigationManager?.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() {
                Log.i(TAG, "onStopNavigation callback from Templates Host")
                // Do NOT set isNavigating = false — GM's Templates Host may call this
                // spuriously. We only end navigation on explicit nav_state_clear or
                // arrival timeout. Re-calling navigationStarted() to re-assert nav.
                try {
                    navigationManager?.navigationStarted()
                    Log.i(TAG, "Re-asserted navigationStarted() after onStopNavigation")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to re-assert navigationStarted(): ${e.message}")
                    isNavigating = false
                }
            }

            override fun onAutoDriveEnabled() {
                Log.d(TAG, "Auto drive enabled")
            }
        })

        // Call navigationStarted() IMMEDIATELY — this is the trigger that causes
        // Templates Host to create ClusterTurnCardActivity on the cluster display.
        try {
            navigationManager?.navigationStarted()
            isNavigating = true
            Log.i(TAG, "navigationStarted() called")
        } catch (e: Exception) {
            Log.w(TAG, "navigationStarted() failed: ${e.message}")
        }

        val sessionScope = CoroutineScope(Dispatchers.Main)
        scope = sessionScope

        sessionScope.launch {
            collectNavigationState()
        }

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                if (isPrimary) {
                    primarySession = null
                    Log.i(TAG, "Primary session destroyed")
                    DiagnosticLog.i("cluster", "ClusterMainSession destroyed")
                    arrivalTimeoutJob?.cancel()
                    if (isNavigating) {
                        try {
                            navigationManager?.navigationEnded()
                        } catch (e: Exception) {
                            Log.e(TAG, "navigationEnded() failed on destroy: ${e.message}")
                        }
                        isNavigating = false
                    }
                    scope?.cancel()
                    scope = null
                    navigationManager = null
                }
                ClusterBindingState.sessionAlive = false
            }
        })

        return RelayScreen(carContext)
    }

    private suspend fun collectNavigationState() {
        var debounceJob: Job? = null

        ClusterNavigationState.state.collectLatest { maneuver ->
            debounceJob?.cancel()
            debounceJob = scope?.launch {
                delay(200)
                processStateUpdate(maneuver)
            }
        }
    }

    private fun processStateUpdate(maneuver: ManeuverState?) {
        val navManager = navigationManager ?: return

        if (maneuver != null) {
            hasSeenActiveNav = true

            if (!isNavigating) {
                try {
                    navManager.navigationStarted()
                    isNavigating = true
                    Log.i(TAG, "navigationStarted() (re-start)")
                } catch (e: Exception) {
                    Log.e(TAG, "navigationStarted() failed: ${e.message}")
                    return
                }
            }

            try {
                val trip = buildTrip(maneuver)
                navManager.updateTrip(trip)
                Log.d(TAG, "Trip relayed: ${maneuver.type}, dist=${maneuver.distanceMeters}m, road=${maneuver.roadName}")
            } catch (e: Exception) {
                Log.e(TAG, "updateTrip() failed: ${e.message}")
            }

            // Arrival timeout for terminal maneuver types
            val maneuverName = maneuver.type.name.lowercase()
            if (TERMINAL_TYPES.any { maneuverName.contains(it) }) {
                if (arrivalTimeoutJob?.isActive != true) {
                    arrivalTimeoutJob = scope?.launch {
                        delay(ARRIVAL_TIMEOUT_MS)
                        if (isNavigating) {
                            Log.i(TAG, "Arrival timeout — ending navigation")
                            try { navManager.navigationEnded() } catch (_: Exception) {}
                            isNavigating = false
                        }
                    }
                }
            } else {
                arrivalTimeoutJob?.cancel()
                arrivalTimeoutJob = null
            }
        } else if (isNavigating && hasSeenActiveNav) {
            arrivalTimeoutJob?.cancel()
            arrivalTimeoutJob = null
            Log.i(TAG, "navigationEnded() — nav cleared")
            try {
                navManager.navigationEnded()
            } catch (e: Exception) {
                Log.e(TAG, "navigationEnded() failed: ${e.message}")
            }
            isNavigating = false
        }
    }

    private fun buildTrip(maneuver: ManeuverState): Trip {
        val tripBuilder = Trip.Builder()
        val eta = ZonedDateTime.now().plus(
            Duration.ofSeconds((maneuver.etaSeconds ?: 0).toLong())
        )

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

        val distance = toDistance(
            maneuver.distanceMeters ?: 0,
            ClusterNavigationState.distanceUnits,
            maneuver.displayDistance,
            maneuver.displayDistanceUnit
        )
        val stepEstimate = TravelEstimate.Builder(distance, eta).build()
        tripBuilder.addStep(stepBuilder.build(), stepEstimate)

        // Add destination info when available (address + arrival ETA + remaining distance)
        maneuver.destination?.let { destAddress ->
            val destBuilder = Destination.Builder()
            destBuilder.setName(destAddress)
            destBuilder.setAddress(destAddress)

            val destDistance = toDistance(
                maneuver.destDistanceMeters ?: maneuver.distanceMeters ?: 0,
                ClusterNavigationState.distanceUnits,
                maneuver.destDistanceDisplay,
                maneuver.destDistanceUnit
            )
            val destEta = if (maneuver.timeToArrivalSeconds != null && maneuver.timeToArrivalSeconds > 0) {
                ZonedDateTime.now().plus(Duration.ofSeconds(maneuver.timeToArrivalSeconds))
            } else {
                eta
            }
            val destEstimate = TravelEstimate.Builder(destDistance, destEta).build()
            tripBuilder.addDestination(destBuilder.build(), destEstimate)
        }

        tripBuilder.setLoading(false)

        return tripBuilder.build()
    }

    private class RelayScreen(carContext: CarContext) : Screen(carContext) {
        override fun onGetTemplate(): Template =
            NavigationTemplate.Builder()
                .setNavigationInfo(
                    MessageInfo.Builder("OpenAutoLink — Cluster Navigation")
                        .setText("Cluster navigation service active.")
                        .build()
                )
                .setActionStrip(
                    ActionStrip.Builder().addAction(Action.APP_ICON).build()
                )
                .build()
    }
}
