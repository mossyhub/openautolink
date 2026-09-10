package com.openautolink.app.cluster

import android.os.SystemClock
import android.content.Intent
import android.util.Log
import com.openautolink.app.diagnostics.DiagnosticLog
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.CarText
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.VehicleEnergyForecastPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.ZonedDateTime

/**
 * GM AAOS cluster session — relays Trip data via NavigationManager.updateTrip().
 *
 * GM has an internal cluster manager (OnStarTurnByTurnManager) that consumes
 * NavigationManager data and renders turn-by-turn on the instrument cluster.
 * [RelayScreen] exists only to satisfy the main-display host contract while the
 * bootstrap activity is transparent. Once the primary session owns a
 * [NavigationManager], the activity stays alive as the renderer owner but its
 * task is moved behind the existing foreground task.
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

        private val primarySessions = GenerationOwnedSlot<ClusterMainSession>()

        /**
         * Proactively end any active cluster navigation. Called from
         * MainActivity.handleUserExit so Templates Host dismisses the cluster
         * Activity (ClusterTurnCardActivity) before our app tasks are killed.
         * Without this, Templates Host keeps the cluster UI on its last frame
         * because our process going away doesn't synchronously notify it.
         */
        fun endActiveNavigation() = synchronized(ClusterBindingLifecycle.lock) {
            val session = primarySessions.currentOwner() ?: return@synchronized
            if (session.navigationLifecycle.onRouteTerminated() ==
                ClusterNavigationLifecyclePolicy.Action.END
            ) {
                try {
                    session.navigationManager?.navigationEnded()
                    Log.i(TAG, "navigationEnded() forced on user exit")
                    DiagnosticLog.i("cluster", "navigationEnded() forced on user exit")
                } catch (e: Exception) {
                    Log.w(TAG, "endActiveNavigation() failed: ${e.message}")
                }
            }
        }

        /** Retire only the primary owned by [generation]. A late callback from
         * that session cannot clear a replacement generation's owner. */
        fun invalidateBindingGeneration(generation: Long, reason: String) = synchronized(ClusterBindingLifecycle.lock) {
            primarySessions.invalidate(generation)?.retirePrimary(reason, endNavigation = true)
        }
    }

    private var navigationManager: NavigationManager? = null
    private var scope: CoroutineScope? = null
    private val navigationLifecycle = ClusterNavigationLifecyclePolicy()
    private var arrivalTimeoutJob: Job? = null
    private var bindingLease: ClusterBindingRegistry.SessionLease? = null
    private var tripOutcomeLogged = false
    private var observedRoute: Long? = null

    override fun onCreateScreen(intent: Intent): Screen =
        synchronized(ClusterBindingLifecycle.lock) { createScreen(intent) }

    private fun createScreen(intent: Intent): Screen {
        val expectedGeneration = if (intent.hasExtra(CLUSTER_BINDING_GENERATION_EXTRA)) {
            intent.getLongExtra(CLUSTER_BINDING_GENERATION_EXTRA, Long.MIN_VALUE)
        } else {
            null
        }
        val lease = ClusterBindingState.registerSession(expectedGeneration)
        if (lease == null) {
            Log.w(TAG, "Session rejected for stale or missing cluster manager generation")
            DiagnosticLog.w(
                "cluster",
                "Cluster session rejected: expectedGeneration=$expectedGeneration",
            )
            return RelayScreen(carContext)
        }
        bindingLease = lease

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = synchronized(ClusterBindingLifecycle.lock) {
                val ownedLease = bindingLease
                if (ownedLease != null) {
                    if (primarySessions.release(ownedLease.generation, this@ClusterMainSession)) {
                        retirePrimary("session destroy", endNavigation = true)
                        DiagnosticLog.i(
                            "cluster",
                            "ClusterMainSession destroyed generation=${ownedLease.generation}",
                        )
                    }
                    ClusterBindingState.unregisterSession(ownedLease)
                }
                bindingLease = null
            }
        })

        val claim = primarySessions.claim(lease.generation, this)
        val primaryAccepted = claim.accepted && ClusterBindingState.markPrimary(lease)
        claim.displaced?.retirePrimary(
            "superseded by generation ${lease.generation}",
            endNavigation = true,
        )
        if (claim.accepted && !primaryAccepted) {
            primarySessions.release(lease.generation, this)
            Log.w(TAG, "Primary claim lost because generation ${lease.generation} retired")
            return RelayScreen(carContext)
        }
        if (primaryAccepted) {
            Log.i(TAG, "Primary session created — owns NavigationManager (generation=${lease.generation})")
            DiagnosticLog.i(
                "cluster",
                "ClusterMainSession created (primary) generation=${lease.generation}",
            )
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
            override fun onStopNavigation() = synchronized(ClusterBindingLifecycle.lock) {
                if (!isCurrentPrimary() || observedRoute == null ||
                    observedRoute != ClusterNavigationState.recoveryDemand.value
                ) return@synchronized
                Log.i(TAG, "onStopNavigation callback from Templates Host")
                arrivalTimeoutJob?.cancel()
                arrivalTimeoutJob = null
                navigationLifecycle.onHostStop()
                ClusterNavigationState.suppressCurrentRoute()
                DiagnosticLog.i(
                    "cluster",
                    "Host stopped navigation; suppressing current route until clear",
                )
            }

            override fun onAutoDriveEnabled() {
                Log.d(TAG, "Auto drive enabled")
            }
        })

        val sessionScope = CoroutineScope(Dispatchers.Main)
        scope = sessionScope

        sessionScope.launch {
            collectNavigationState()
        }

        if (navigationManager != null) {
            if (ClusterBindingState.markReady(lease)) {
                DiagnosticLog.i(
                    "cluster",
                    "Cluster session ready generation=${lease.generation}; collector and NavigationManager callback installed",
                )
                ClusterBootstrapWindowProtector.moveTaskToBack(lease.generation)
            } else {
                DiagnosticLog.w(
                    "cluster",
                    "Cluster session ready rejected for retired generation=${lease.generation}",
                )
            }
        }

        return RelayScreen(carContext)
    }

    private fun retirePrimary(reason: String, endNavigation: Boolean) {
        if (endNavigation && navigationLifecycle.onRouteTerminated() ==
            ClusterNavigationLifecyclePolicy.Action.END
        ) {
            try {
                navigationManager?.navigationEnded()
            } catch (e: Exception) {
                Log.e(TAG, "navigationEnded() failed during $reason: ${e.message}")
            }
        }
        arrivalTimeoutJob?.cancel()
        arrivalTimeoutJob = null
        scope?.cancel()
        scope = null
        navigationManager = null
        Log.i(TAG, "Primary session retired: $reason")
    }

    private fun isCurrentPrimary(): Boolean {
        val lease = bindingLease ?: return false
        return ClusterBindingState.isSessionCurrent(lease) &&
            primarySessions.isOwner(lease.generation, this)
    }

    private suspend fun collectNavigationState() {
        var debounceJob: Job? = null

        combine(
            ClusterNavigationState.state,
            ClusterNavigationState.vehicleEnergyForecast,
            ClusterNavigationState.recoveryDemand,
        ) { _, _, _ ->
            synchronized(ClusterBindingLifecycle.lock) {
                ClusterNavigationState.state.value to ClusterNavigationState.recoveryDemand.value
            }
        }.collectLatest { (maneuver, route) ->
            debounceJob?.cancel()
            debounceJob = scope?.launch {
                delay(200)
                synchronized(ClusterBindingLifecycle.lock) {
                    processStateUpdate(maneuver, route)
                }
            }
        }
    }

    /** Caller owns the lifecycle lock from snapshot validation through effects. */
    private fun processStateUpdate(maneuver: ManeuverState?, route: Long?) {
        fun isSnapshotCurrent(): Boolean = isCurrentPrimary() &&
            route == ClusterNavigationState.recoveryDemand.value &&
            maneuver === ClusterNavigationState.state.value
        if (!isSnapshotCurrent()) return
        val navManager = navigationManager ?: return

        if (maneuver != null) {
            if (ClusterNavigationState.isRouteSuppressed) return
            if (observedRoute != route) {
                arrivalTimeoutJob?.cancel()
                arrivalTimeoutJob = null
                if (navigationLifecycle.onRouteCleared() == ClusterNavigationLifecyclePolicy.Action.END) {
                    try { navManager.navigationEnded() } catch (_: Exception) {}
                    if (!isSnapshotCurrent()) return
                }
                observedRoute = route
                tripOutcomeLogged = false
            }
            when (navigationLifecycle.onRouteAvailable()) {
                ClusterNavigationLifecyclePolicy.Action.NONE -> return
                ClusterNavigationLifecyclePolicy.Action.START_AND_UPDATE -> {
                    try {
                        navManager.navigationStarted()
                        Log.i(TAG, "navigationStarted() — first maneuver in route epoch")
                        DiagnosticLog.i(
                            "cluster",
                            "Navigation ownership outcome=started generation=${bindingLease?.generation}",
                        )
                    } catch (e: Exception) {
                        navigationLifecycle.onNavigationStartFailed()
                        Log.e(TAG, "navigationStarted() failed: ${e.message}")
                        DiagnosticLog.e(
                            "cluster",
                            "Navigation ownership outcome=start-failed generation=${bindingLease?.generation} error=${e.message}",
                        )
                        return
                    }
                }
                ClusterNavigationLifecyclePolicy.Action.UPDATE -> Unit
                ClusterNavigationLifecyclePolicy.Action.END -> return
            }

            try {
                if (!isSnapshotCurrent()) return
                val trip = buildTrip(maneuver)
                if (!isSnapshotCurrent()) return
                navManager.updateTrip(trip)
                if (!tripOutcomeLogged) {
                    tripOutcomeLogged = true
                    DiagnosticLog.i(
                        "cluster",
                        "Trip update outcome=sent generation=${bindingLease?.generation} " +
                            "maneuver=${maneuver.type} road=${maneuver.roadName}",
                    )
                }
                DiagnosticLog.d("cluster", "Trip: ${maneuver.type} dist=${maneuver.distanceMeters}m road=${maneuver.roadName} lanes=${maneuver.lanes?.size ?: 0}")
            } catch (e: Exception) {
                Log.e(TAG, "updateTrip() failed: ${e.message}")
                DiagnosticLog.e(
                    "cluster",
                    "Trip update outcome=failed generation=${bindingLease?.generation} error=${e.message}",
                )
            }

            if (!isSnapshotCurrent()) return

            // Arrival timeout for terminal maneuver types
            val maneuverName = maneuver.type.name.lowercase()
            if (TERMINAL_TYPES.any { maneuverName.contains(it) }) {
                if (arrivalTimeoutJob?.isActive != true) {
                    arrivalTimeoutJob = scope?.launch {
                        delay(ARRIVAL_TIMEOUT_MS)
                        synchronized(ClusterBindingLifecycle.lock) {
                            if (!isCurrentPrimary() || route != ClusterNavigationState.recoveryDemand.value) return@synchronized
                            if (navigationLifecycle.onRouteTerminated() ==
                                ClusterNavigationLifecyclePolicy.Action.END
                            ) {
                                ClusterNavigationState.suppressCurrentRoute()
                                Log.i(TAG, "Arrival timeout — ending navigation")
                                try { navManager.navigationEnded() } catch (_: Exception) {}
                            }
                        }
                    }
                }
            } else {
                arrivalTimeoutJob?.cancel()
                arrivalTimeoutJob = null
            }
        } else {
            arrivalTimeoutJob?.cancel()
            arrivalTimeoutJob = null
            if (navigationLifecycle.onRouteCleared() ==
                ClusterNavigationLifecyclePolicy.Action.END
            ) {
                Log.i(TAG, "navigationEnded() — nav cleared")
                try {
                    navManager.navigationEnded()
                } catch (e: Exception) {
                    Log.e(TAG, "navigationEnded() failed: ${e.message}")
                }
            }
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
            val destEstimateBuilder = TravelEstimate.Builder(destDistance, destEta)
            val forecast = ClusterNavigationState.vehicleEnergyForecast.value
            if (VehicleEnergyForecastPolicy.isFresh(forecast, SystemClock.elapsedRealtime())) {
                VehicleEnergyForecastPolicy.tripText(
                    forecast,
                    ClusterNavigationState.batteryCapacityWh,
                )?.let { destEstimateBuilder.setTripText(CarText.create(it)) }
            }
            val destEstimate = destEstimateBuilder.build()
            tripBuilder.addDestination(destBuilder.build(), destEstimate)
        }

        tripBuilder.setLoading(false)

        return tripBuilder.build()
    }

    private class RelayScreen(carContext: CarContext) : Screen(carContext) {
        override fun onGetTemplate(): Template = ClusterRelayTemplateFactory.build()
    }
}
