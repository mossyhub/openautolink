package com.openautolink.app.transport.bluetooth

/** Side effects deliberately exclude connect, enumeration, ownership, and projection routing. */
internal interface CarBluetoothObservationEffects {
    fun availability(): CarBluetoothUnknown?
    fun register(generation: Long)
    fun unregister()
    fun forgetDevices()
}

/** Main-thread confined; generation fences callbacks from retired registrations. */
internal class CarBluetoothObservationSession(private val effects: CarBluetoothObservationEffects) {
    var state = CarBluetoothProfileState()
        private set
    var generation = 0L
        private set
    private var listening = false

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            if (!state.enabled) return
            generation++
            if (listening) runCatching { effects.unregister() }
            listening = false
            effects.forgetDevices()
            state = CarBluetoothProfileState(epoch = state.epoch + 1)
            return
        }
        if (listening) return
        generation++
        state = CarBluetoothProfileState(enabled = true, epoch = state.epoch + 1,
            unknown = CarBluetoothUnknown.WAITING_FOR_EVENTS)
        try {
            val problem = effects.availability()
            if (problem != null) {
                state = state.copy(unknown = problem)
                return
            }
            effects.register(generation)
            listening = true
        } catch (_: SecurityException) {
            invalidate(CarBluetoothUnknown.PERMISSION_MISSING)
        } catch (_: RuntimeException) {
            invalidate(CarBluetoothUnknown.UNAVAILABLE)
        }
    }

    fun profile(id: String, profile: CarBluetoothProfile, connection: CarBluetoothProfileConnection,
        previous: CarBluetoothProfileConnection, callbackGeneration: Long) {
        if (!listening || generation != callbackGeneration) return
        state = state.profile(id, profile, connection, previous, state.epoch)
    }

    fun unknown(reason: CarBluetoothUnknown, callbackGeneration: Long) {
        if (!listening || generation != callbackGeneration) return
        effects.forgetDevices()
        state = state.copy(epoch = state.epoch + 1, devices = emptyMap(), unknown = reason)
    }

    fun power(on: Boolean?, callbackGeneration: Long) {
        if (!listening || generation != callbackGeneration) return
        val next = state.power(on)
        if (next.epoch != state.epoch) effects.forgetDevices()
        state = next
    }

    fun invalidate(reason: CarBluetoothUnknown) {
        if (!state.enabled) return
        generation++
        runCatching { effects.unregister() }
        listening = false
        effects.forgetDevices()
        state = CarBluetoothProfileState(enabled = true, epoch = state.epoch + 1, unknown = reason)
    }

    fun close() = setEnabled(false)
}
