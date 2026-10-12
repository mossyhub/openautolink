package com.openautolink.app.transport.bluetooth

import org.junit.Assert.*
import org.junit.Test

class CarBluetoothProfileStateTest {
    @Test fun exactDeviceAndProfileEvidenceIsIdempotent() {
        val initial = CarBluetoothProfileState(enabled = true, unknown = CarBluetoothUnknown.WAITING_FOR_EVENTS)
        val connected = initial.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.DISCONNECTED, initial.epoch)
        assertEquals(CarBluetoothProfileConnection.CONNECTED, connected.devices["local-1"]?.get(CarBluetoothProfile.HFP_CLIENT))
        assertNull(connected.devices["local-1"]?.get(CarBluetoothProfile.A2DP_SINK))
        assertEquals(connected, connected.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.DISCONNECTED, connected.epoch))
    }

    @Test fun inconsistentPreviousStateRevokesRatherThanReordersOwnership() {
        val initial = CarBluetoothProfileState(enabled = true)
        val connected = initial.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.CONNECTING, initial.epoch)
        val disconnected = connected.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.DISCONNECTED, CarBluetoothProfileConnection.CONNECTED, initial.epoch)
        val stale = disconnected.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.CONNECTING, initial.epoch)
        assertEquals(CarBluetoothProfileConnection.UNKNOWN, stale.devices["local-1"]?.get(CarBluetoothProfile.HFP_CLIENT))
        assertEquals(CarBluetoothUnknown.OUT_OF_ORDER, stale.unknown)
    }

    @Test fun powerEpochClearsEvidenceAndFencesOldEvents() {
        val initial = CarBluetoothProfileState(enabled = true)
        val connected = initial.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.CONNECTING, initial.epoch)
        val off = connected.power(false)
        assertTrue(off.devices.isEmpty())
        assertTrue(off.epoch > connected.epoch)
        assertEquals(CarBluetoothUnknown.ADAPTER_OFF, off.unknown)
        assertEquals(off, off.power(false))
        assertEquals(off, off.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.CONNECTING, off.epoch))
        val on = off.power(true)
        assertEquals(on, on.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.CONNECTING, connected.epoch))
        assertEquals(CarBluetoothUnknown.WAITING_FOR_EVENTS, on.unknown)
    }

    @Test fun multipleObservedDevicesRemainAmbiguousAcrossProfiles() {
        var state = CarBluetoothProfileState(enabled = true)
        state = state.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.CONNECTING, state.epoch)
        assertEquals(setOf("local-1"), state.observedConnectedDevices)
        assertFalse(state.ambiguous)
        state = state.profile("local-2", CarBluetoothProfile.A2DP_SINK,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.CONNECTING, state.epoch)
        assertTrue(state.ambiguous)
        state = state.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.DISCONNECTED, CarBluetoothProfileConnection.CONNECTED, state.epoch)
        assertEquals(setOf("local-2"), state.observedConnectedDevices)
        assertFalse(state.ambiguous)
    }

    @Test fun malformedTransitionCannotSupplyConnectedEvidence() {
        val state = CarBluetoothProfileState(enabled = true)
        val malformed = state.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.UNKNOWN, state.epoch)
        assertEquals(CarBluetoothUnknown.MALFORMED_EVENT, malformed.unknown)
        assertTrue(malformed.observedConnectedDevices.isEmpty())
    }

    @Test fun noisyDeviceInventoryIsBoundedAndExplicitlyUnknown() {
        var state = CarBluetoothProfileState(enabled = true)
        repeat(CarBluetoothProfileState.MAX_DEVICES + 10) {
            state = state.profile("local-$it", CarBluetoothProfile.HFP_CLIENT,
                CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.CONNECTING, state.epoch)
        }
        assertTrue(state.devices.size <= CarBluetoothProfileState.MAX_DEVICES)
        assertEquals(CarBluetoothUnknown.CAPACITY, state.unknown)
    }

    @Test fun malformedDuplicateCannotPreserveConnectedEvidence() {
        val state = CarBluetoothProfileState(enabled = true).profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.CONNECTING, 0)
        val malformed = state.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.UNKNOWN, state.epoch)
        assertTrue(malformed.observedConnectedDevices.isEmpty())
        assertEquals(CarBluetoothUnknown.MALFORMED_EVENT, malformed.unknown)
    }

    @Test fun unavailableAdapterPowerRevokesEvenBeforeFirstPowerBroadcast() {
        val state = CarBluetoothProfileState(enabled = true).profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.CONNECTING, 0)
        val unknown = state.power(null)
        assertTrue(unknown.devices.isEmpty())
        assertTrue(unknown.epoch > state.epoch)
        assertEquals(CarBluetoothUnknown.UNAVAILABLE, unknown.unknown)
    }

    @Test fun unknownAdapterEventIsExplicitEvenWithNoPriorEvidence() {
        val state = CarBluetoothProfileState(enabled = true, unknown = CarBluetoothUnknown.WAITING_FOR_EVENTS)
        val unknown = state.power(null)
        assertEquals(CarBluetoothUnknown.UNAVAILABLE, unknown.unknown)
        assertEquals(unknown, unknown.power(null))
    }

    @Test fun disabledDoesNotAcceptProfileEvidence() {
        val state = CarBluetoothProfileState()
        assertEquals(state, state.profile("local-1", CarBluetoothProfile.HFP_CLIENT,
            CarBluetoothProfileConnection.CONNECTED, CarBluetoothProfileConnection.DISCONNECTED, state.epoch))
        assertEquals(CarBluetoothUnknown.DISABLED, state.unknown)
        assertTrue(state.devices.isEmpty())
    }
}
