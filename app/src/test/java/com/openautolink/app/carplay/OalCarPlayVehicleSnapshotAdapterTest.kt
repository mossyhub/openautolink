package com.openautolink.app.carplay

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class OalCarPlayVehicleSnapshotAdapterTest {
    @Test fun carPlaySnapshotReadsDoNotTakeOverAaCoordinatorConsumer() {
        val coordinator = com.openautolink.app.input.ProcessVehicleDataCoordinator({_,_ ->},{}, {1000})
        val delivered = mutableListOf<ControlMessage.VehicleData>()
        val aa: (ControlMessage.VehicleData) -> Unit = {delivered += it}
        coordinator.attachSessionConsumer(aa)
        val adapter=OalCarPlayVehicleSnapshotAdapter({coordinator.latestVehicleData},{1000})
        val first=ControlMessage.VehicleData(speedKmh=0f)
        coordinator.onRawBatch(first); adapter.snapshot(); adapter.snapshot()
        val second=first.copy(speedKmh=10f)
        coordinator.onRawBatch(second); adapter.snapshot()
        assertSame(aa,coordinator.sessionConsumer())
        assertEquals(listOf(first,second),delivered)
        assertSame(second,coordinator.latestVehicleData.value)
    }

    @Test fun staleUnavailableUnregisteredUnknownAndNominalOnlyInputsAreOmitted() {
        var now = 60000L
        val observation = VehiclePropertyObservation(59_000_000_000,59000,0,registrationGeneration=3,subscriptionActive=true)
        val fresh = ControlMessage.VehicleData(fuelTypes=listOf(10),evBatteryLevelWh=0f,evCurrentBatteryCapacityWh=60000f,
            vhalRegistrationGeneration=3,evObservationMetadata=mapOf("EV_BATTERY_LEVEL" to observation,"EV_CURRENT_BATTERY_CAPACITY" to observation))
        val flow = MutableStateFlow(fresh)
        val adapter = OalCarPlayVehicleSnapshotAdapter({flow},{now})
        val invalid = listOf(
            observation.copy(status=1), observation.copy(status=null), observation.copy(subscriptionActive=false),
            observation.copy(registrationGeneration=2), observation.copy(timestampElapsedNanos=1_000_000),
            observation.copy(timestampElapsedNanos=null), observation.copy(receivedElapsedMs=60001),
            observation.copy(source="gm-history"),
        )
        invalid.forEach { bad ->
            flow.value = fresh.copy(evObservationMetadata=mapOf("EV_BATTERY_LEVEL" to bad,"EV_CURRENT_BATTERY_CAPACITY" to bad))
            assertNull(adapter.snapshot())
        }
        flow.value = fresh.copy(evObservationMetadata=emptyMap()); assertNull(adapter.snapshot())
        flow.value = fresh.copy(evBatteryLevelWh=Float.NaN,evCurrentBatteryCapacityWh=Float.POSITIVE_INFINITY)
        assertNull(adapter.snapshot())
        flow.value = fresh.copy(fuelTypes=listOf(1,10)); assertNull(adapter.snapshot())
        flow.value = fresh.copy(fuelTypes=null); assertNull(adapter.snapshot())
        flow.value = fresh.copy(evBatteryLevelWh=null,evCurrentBatteryCapacityWh=null,evBatteryCapacityWh=70000f,batteryPct=75)
        assertNull(adapter.snapshot())
        flow.value = fresh; now=90001; assertNull(adapter.snapshot())
        now=60000; flow.value = fresh.copy(evBatteryLevelWh=65000f)
        assertNull(adapter.snapshot()!!.currentChargeWh) // inconsistent energy is not clamped to capacity
        assertEquals(60000L,adapter.snapshot()!!.maxChargeWh)
    }

    @Test fun freshAvailableEnergyPreservesZeroAndReadsProcessStateWithoutConsumingIt() {
        val observation = VehiclePropertyObservation(9_000_000_000,9000,0,registrationGeneration=3,subscriptionActive=true)
        val flow = MutableStateFlow(ControlMessage.VehicleData(fuelTypes=listOf(10),rangeKm=123.9f,
            evBatteryLevelWh=0f,evCurrentBatteryCapacityWh=60000f,evBatteryCapacityWh=70000f,batteryPct=77,
            evChargeState=2,vhalRegistrationGeneration=3,
            evObservationMetadata=mapOf("RANGE_REMAINING" to observation,"EV_BATTERY_LEVEL" to observation,"EV_CURRENT_BATTERY_CAPACITY" to observation)))
        val original = flow.value
        val adapter = OalCarPlayVehicleSnapshotAdapter({flow}, {10000})
        val snapshot = adapter.snapshot()!!
        assertEquals(123,snapshot.rangeKm)
        assertEquals(0L,snapshot.currentChargeWh)
        assertEquals(60000L,snapshot.maxChargeWh) // actual usable capacity, NOT nominal/new-car capacity
        assertSame(original,flow.value)
        flow.value = original.copy(evBatteryLevelWh=12f)
        assertEquals(12L,adapter.snapshot()!!.currentChargeWh)
    }
}
