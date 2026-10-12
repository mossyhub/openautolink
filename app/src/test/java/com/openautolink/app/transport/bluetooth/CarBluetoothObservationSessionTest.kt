package com.openautolink.app.transport.bluetooth

import org.junit.Assert.*
import org.junit.Test

class CarBluetoothObservationSessionTest {
    private class Effects : CarBluetoothObservationEffects {
        var checks = 0
        var registrations = 0
        var removals = 0
        var forgotten = 0
        var problem: CarBluetoothUnknown? = null
        var registerFailure: RuntimeException? = null
        var availabilityFailure: RuntimeException? = null
        var unregisterFailure: RuntimeException? = null
        override fun availability(): CarBluetoothUnknown? { checks++; availabilityFailure?.let { throw it }; return problem }
        override fun register(generation: Long) { registrations++; registerFailure?.let { throw it } }
        override fun unregister() { removals++; unregisterFailure?.let { throw it } }
        override fun forgetDevices() { forgotten++ }
    }

    @Test fun failingPermissionCheckCannotEscapeProcessObserver() {
        val effects = Effects()
        effects.availabilityFailure = SecurityException("no permission")
        val session = CarBluetoothObservationSession(effects)
        session.setEnabled(true)
        assertEquals(CarBluetoothUnknown.PERMISSION_MISSING, session.state.unknown)
        assertEquals(0, effects.registrations)
        assertTrue(session.state.devices.isEmpty())
    }

    @Test fun adapterResetForgetsPrivateIdentityAndRetiredCallbackCannotReset() {
        val effects = Effects()
        val session = CarBluetoothObservationSession(effects)
        session.setEnabled(true)
        val generation = session.generation
        session.profile("local-1", CarBluetoothProfile.HFP_CLIENT, CarBluetoothProfileConnection.CONNECTED,
            CarBluetoothProfileConnection.CONNECTING, generation)
        val epoch = session.state.epoch
        session.power(false, generation)
        assertTrue(session.state.epoch > epoch)
        assertEquals(1, effects.forgotten)
        assertTrue(session.state.devices.isEmpty())
        session.power(false, generation)
        assertEquals(1, effects.forgotten)
        session.invalidate(CarBluetoothUnknown.PERMISSION_MISSING)
        session.setEnabled(true)
        val fresh = session.state
        session.power(false, generation)
        assertEquals(fresh, session.state)
    }

    @Test fun listenerFailureRevokesAndDoesNotEscape() {
        val effects = Effects()
        val session = CarBluetoothObservationSession(effects)
        effects.registerFailure = SecurityException("private error must not escape")
        session.setEnabled(true)
        assertEquals(CarBluetoothUnknown.PERMISSION_MISSING, session.state.unknown)
        assertTrue(session.state.devices.isEmpty())
        effects.registerFailure = null
        session.setEnabled(true)
        val generation = session.generation
        session.profile("local-1", CarBluetoothProfile.HFP_CLIENT, CarBluetoothProfileConnection.CONNECTED,
            CarBluetoothProfileConnection.CONNECTING, generation)
        effects.unregisterFailure = IllegalArgumentException("already removed")
        session.close()
        assertEquals(CarBluetoothUnknown.DISABLED, session.state.unknown)
        assertTrue(session.state.devices.isEmpty())
    }

    @Test fun missingPermissionOrUnavailableHasNoRegistrationAndCanRetry() {
        val effects = Effects()
        val session = CarBluetoothObservationSession(effects)
        effects.problem = CarBluetoothUnknown.PERMISSION_MISSING
        session.setEnabled(true)
        assertEquals(CarBluetoothUnknown.PERMISSION_MISSING, session.state.unknown)
        assertEquals(0, effects.registrations)
        effects.problem = CarBluetoothUnknown.UNAVAILABLE
        session.setEnabled(true)
        assertEquals(CarBluetoothUnknown.UNAVAILABLE, session.state.unknown)
        assertEquals(0, effects.registrations)
        effects.problem = null
        session.setEnabled(true)
        assertEquals(1, effects.registrations)
        assertEquals(CarBluetoothUnknown.WAITING_FOR_EVENTS, session.state.unknown)
    }

    @Test fun defaultOffHasNoListenerActivityAndDisableRevokes() {
        val effects = Effects()
        val session = CarBluetoothObservationSession(effects)
        session.setEnabled(false)
        assertEquals(0, effects.checks)
        assertEquals(0, effects.registrations)
        assertEquals(0, effects.removals)
        session.setEnabled(true)
        val generation = session.generation
        session.profile("local-1", CarBluetoothProfile.HFP_CLIENT, CarBluetoothProfileConnection.CONNECTED,
            CarBluetoothProfileConnection.CONNECTING, generation)
        assertEquals(setOf("local-1"), session.state.observedConnectedDevices)
        session.setEnabled(true)
        assertEquals(1, effects.registrations)
        session.setEnabled(false)
        assertEquals(1, effects.removals)
        assertEquals(1, effects.forgotten)
        assertEquals(CarBluetoothUnknown.DISABLED, session.state.unknown)
        assertTrue(session.state.devices.isEmpty())
        session.setEnabled(true)
        session.profile("local-1", CarBluetoothProfile.HFP_CLIENT, CarBluetoothProfileConnection.CONNECTED,
            CarBluetoothProfileConnection.CONNECTING, generation)
        assertTrue(session.state.devices.isEmpty())
        session.close()
        session.close()
        assertEquals(2, effects.removals)
    }
}
