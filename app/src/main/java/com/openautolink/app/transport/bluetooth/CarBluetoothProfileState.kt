package com.openautolink.app.transport.bluetooth

/** Observation only: these are vehicle-side profiles, never a selected/projection phone. */
enum class CarBluetoothProfile { HFP_CLIENT, A2DP_SINK }
enum class CarBluetoothProfileConnection { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, UNKNOWN }
enum class CarBluetoothUnknown { DISABLED, WAITING_FOR_EVENTS, PERMISSION_MISSING, UNAVAILABLE, ADAPTER_OFF, MALFORMED_EVENT, OUT_OF_ORDER, CAPACITY }

/** Positive observations only. unknown never implies a complete phone inventory or selection. */
data class CarBluetoothProfileState(
    val enabled: Boolean = false,
    val adapterOn: Boolean? = null,
    val epoch: Long = 0,
    val unknown: CarBluetoothUnknown = CarBluetoothUnknown.DISABLED,
    val devices: Map<String, Map<CarBluetoothProfile, CarBluetoothProfileConnection>> = emptyMap(),
) {
    companion object { const val MAX_DEVICES = 32 }

    // Singleton evidence is NOT ownership: broadcasts are not a complete inventory.
    val observedConnectedDevices: Set<String> get() = devices.filterValues {
        CarBluetoothProfileConnection.CONNECTED in it.values
    }.keys
    val ambiguous: Boolean get() = observedConnectedDevices.size > 1

    fun power(on: Boolean?): CarBluetoothProfileState {
        if (!enabled) return this
        if (adapterOn == on && on != null) return this
        if (adapterOn == null && on == null && devices.isEmpty() && unknown == CarBluetoothUnknown.UNAVAILABLE) return this
        return copy(adapterOn = on, epoch = epoch + 1, devices = emptyMap(),
            unknown = when (on) {
                false -> CarBluetoothUnknown.ADAPTER_OFF
                true -> CarBluetoothUnknown.WAITING_FOR_EVENTS
                null -> CarBluetoothUnknown.UNAVAILABLE
            })
    }

    fun profile(id: String, profile: CarBluetoothProfile, state: CarBluetoothProfileConnection,
        previous: CarBluetoothProfileConnection, eventEpoch: Long): CarBluetoothProfileState {
        if (!enabled || eventEpoch != epoch || adapterOn == false) return this
        if (id !in devices && devices.size >= MAX_DEVICES) return copy(unknown = CarBluetoothUnknown.CAPACITY)
        if (devices[id]?.get(profile) == state && previous != CarBluetoothProfileConnection.UNKNOWN) return this
        val known = devices[id]?.get(profile)
        val conflict = known != null && known != CarBluetoothProfileConnection.UNKNOWN && known != previous
        val malformed = state == CarBluetoothProfileConnection.UNKNOWN || previous == CarBluetoothProfileConnection.UNKNOWN
        val next = if (conflict || malformed) CarBluetoothProfileConnection.UNKNOWN else state
        return copy(unknown = when {
            malformed -> CarBluetoothUnknown.MALFORMED_EVENT
            conflict -> CarBluetoothUnknown.OUT_OF_ORDER
            else -> unknown
        },
            devices = devices + (id to (devices[id].orEmpty() + (profile to next))))
    }
}
