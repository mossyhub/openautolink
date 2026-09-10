package com.openautolink.app.cluster

import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.VehicleEnergyForecast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared navigation state for cluster sessions.
 *
 * Singleton — populated by SessionManager when nav_state arrives from the bridge.
 * Consumed by [ClusterMainSession] to relay Trip data via NavigationManager.
 *
 * Separate from NavigationDisplay (which is per-session) because the cluster service
 * runs in its own process/session lifecycle independent of the main activity.
 */
object ClusterNavigationState {

    private val _state = MutableStateFlow<ManeuverState?>(null)
    val state: StateFlow<ManeuverState?> = _state.asStateFlow()
    private var routeEpoch = 0L
    private val _recoveryDemand = MutableStateFlow<Long?>(null)
    internal val recoveryDemand: StateFlow<Long?> = _recoveryDemand.asStateFlow()

    internal val isRouteSuppressed: Boolean
        get() = synchronized(ClusterBindingLifecycle.lock) {
            _state.value != null && _recoveryDemand.value == null
        }

    /** Host stop belongs to the route, not to the replaceable consumer. */
    internal fun suppressCurrentRoute() {
        synchronized(ClusterBindingLifecycle.lock) {
            _recoveryDemand.value = null
        }
    }

    /** Latest route-aware result from Maps and the VEM capacity snapshot used with it. */
    val vehicleEnergyForecast = MutableStateFlow<VehicleEnergyForecast?>(null)
    @Volatile var batteryCapacityWh: Int = 0

    /** Distance unit preference: "auto", "metric", or "imperial". */
    @Volatile
    var distanceUnits: String = "auto"

    /** True when navigation is actively routing. */
    val isActive: Boolean get() = _state.value != null

    fun update(maneuver: ManeuverState) {
        synchronized(ClusterBindingLifecycle.lock) {
            if (_state.value == null) _recoveryDemand.value = ++routeEpoch
            _state.value = maneuver
        }
    }

    fun clear() {
        synchronized(ClusterBindingLifecycle.lock) {
            _state.value = null
            _recoveryDemand.value = null
            vehicleEnergyForecast.value = null
        }
    }
}
