package com.openautolink.app.carplay

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.carplaylab.intake.OalIap2CarDataReporter
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=android.app.Application::class)
class OalCarPlayExactStatusRead588Test {
    private var now=170000L
    private fun observation(id: Int, mode: Int?=2)=VehiclePropertyObservation(1_000_000_000L,now,0,
        registrationGeneration=7,propertyId=id,subscriptionActive=true,changeMode=mode,statusReadElapsedMs=now)
    private fun data()=ControlMessage.VehicleData(carMake="Chevrolet",carModel="actual-source",fuelTypes=listOf(10),
        evBatteryLevelWh=55430f,rangeKm=290.98438f,evBatteryCapacityWh=85990f,vhalRegistrationGeneration=7,
        evObservationMetadata=mapOf("EV_BATTERY_LEVEL" to observation(BATTERY),"RANGE_REMAINING" to observation(RANGE)))
    private fun config()=CarPlayEvPilotConfiguration(enabled=true,profileId=CarPlayEvPilotConfiguration.GM_SOURCE,
        modelCapacityKwh="85",averageChargeKw="90")
    @Test fun exactReadFreezesLiveOfferDeliversModelsAndContinuingAgeVetoPreventsFurtherReports() {
        val flow=MutableStateFlow(data());val diagnostics=mutableListOf<String>()
        val adapter=OalCarPlayVehicleSnapshotAdapter({flow},{now},{config()})
        val caps=adapter.capabilities(false,diagnostics::add)
        assertTrue("Actual exact read can confirm an unchanged continuous source",caps.currentEnergy)
        assertTrue(caps.range);assertFalse(caps.maximumEnergy);assertNotNull(caps.evRoutingModel)
        assertTrue(diagnostics.any {it.contains("carplay-ev-pilot readiness=ready")})
        val sent=mutableListOf<Iap2Frame>()
        val reporter=OalIap2CarDataReporter(caps,{now},{null},{adapter.snapshot()},{},sent::add,diagnostics::add)
        try {
            reporter.authenticate();reporter.handle(Iap2Messages.buildRaw(0xA100) {void(21)})
            val body=Iap2BodyReader.of(sent.single())
            assertEquals(setOf(22,26,27),body.list().map{it.id}.toSet());assertFalse(body.has(23))
            now+=31001;reporter.tick();assertEquals(1,sent.size)
            assertNull(adapter.snapshot())
        } finally {reporter.close()}
    }
    @Test fun onlyExactCurrentOwnerSuccessfulReadCanOverrideOldEventAge() {
        val d=data();val o=d.evObservationMetadata.getValue("EV_BATTERY_LEVEL")
        for (bad in listOf(o.copy(statusReadElapsedMs=null),o.copy(statusReadElapsedMs=now-1),
            o.copy(propertyId=RANGE),o.copy(subscriptionActive=false),o.copy(registrationGeneration=6),
            o.copy(status=1),o.copy(receivedElapsedMs=now-31000,statusReadElapsedMs=now-31000))) {
            val flow=MutableStateFlow(d.copy(evObservationMetadata=d.evObservationMetadata+("EV_BATTERY_LEVEL" to bad)))
            assertNull(OalCarPlayVehicleSnapshotAdapter({flow},{now}).snapshot()?.currentChargeWh)
        }
        val unknown=o.copy(changeMode=null)
        val flow=MutableStateFlow(d.copy(evObservationMetadata=d.evObservationMetadata+("EV_BATTERY_LEVEL" to unknown)))
        val confirmed=OalCarPlayVehicleSnapshotAdapter({flow},{now}).snapshot()
        assertNotNull("Exact current read of unknown mode remains usable",confirmed)
        assertEquals(55430L,confirmed!!.currentChargeWh)
    }
    @Test fun qualifiedReportedScaleBuildsModelAndWireMaximumAndItsRevocationVetoesFrozenModel() {
        val d=data().copy(evObservationMetadata=data().evObservationMetadata+("INFO_EV_BATTERY_CAPACITY" to observation(INFO,0)))
        val flow=MutableStateFlow(d);val lines=mutableListOf<String>()
        val adapter=OalCarPlayVehicleSnapshotAdapter({flow},{now},{config().copy(modelCapacityKwh="")})
        val caps=adapter.capabilities(false,lines::add)
        assertNotNull("Qualified reported capacity sources wire maximum and separate frozen model scale",caps.evRoutingModel)
        assertEquals(85990,caps.evRoutingModel!!.pilotCapacityWh)
        assertTrue(caps.maximumEnergy);assertEquals(85990L,adapter.snapshot()!!.maxChargeWh)
        assertTrue(lines.any {it.contains("basis=reported")})
        val o=d.evObservationMetadata.getValue("INFO_EV_BATTERY_CAPACITY")
        for (bad in listOf(o.copy(status=1),o.copy(source="history"),o.copy(registrationGeneration=6),
            o.copy(subscriptionActive=false),o.copy(propertyId=BATTERY),o.copy(receivedElapsedMs=now-31001))) {
            flow.value=d.copy(evObservationMetadata=d.evObservationMetadata+("INFO_EV_BATTERY_CAPACITY" to bad))
            assertNull(adapter.snapshot()!!.routingModel)
        }
        flow.value=d.copy(evBatteryCapacityWh=null);assertNull(adapter.snapshot()!!.routingModel)
        flow.value=d.copy(evBatteryCapacityWh=Float.NaN);assertNull(adapter.snapshot()!!.routingModel)
        flow.value=d;assertSame(caps.evRoutingModel,adapter.snapshot()!!.routingModel)
        val manual=OalCarPlayVehicleSnapshotAdapter({flow},{now},{config()}).capabilities(false).evRoutingModel
        assertEquals("Explicit advanced manual scale is preserved",85000,manual!!.pilotCapacityWh)
        val actual=d.copy(evCurrentBatteryCapacityWh=80000f,evObservationMetadata=d.evObservationMetadata+("EV_CURRENT_BATTERY_CAPACITY" to observation(CAPACITY,1)))
        flow.value=actual
        assertEquals(80000,OalCarPlayVehicleSnapshotAdapter({flow},{now},{config().copy(modelCapacityKwh="")}).capabilities(false).evRoutingModel!!.pilotCapacityWh)
    }
    @Test fun pilotOmissionDiagnosticsDistinguishSavedConfigurationFromMissingSourceAndNeverLogCalibration() {
        for ((config,expected) in listOf(CarPlayEvPilotConfiguration() to "disabled",
            config().copy(modelCapacityKwh="") to "capacity_required",
            config().copy(averageChargeKw="") to "charging_profile_required",
            config().copy(averageChargeKw="invalid-secret-marker") to "invalid_configuration")) {
            val lines=mutableListOf<String>();val flow=MutableStateFlow(data())
            val adapter=OalCarPlayVehicleSnapshotAdapter({flow},{now},{config})
            assertNull(adapter.capabilities(false,lines::add).evRoutingModel)
            assertTrue("Finite readiness reason $expected required",lines.any {it.contains("carplay-ev-pilot readiness=$expected ")})
            assertFalse(lines.joinToString().contains("model_id"));assertFalse(lines.joinToString().contains("invalid-secret-marker"))
        }
        val lines=mutableListOf<String>();val flow=MutableStateFlow(data())
        val failed=OalCarPlayVehicleSnapshotAdapter({flow},{now},{error("private-read-error")})
        failed.capabilities(false,lines::add)
        assertTrue(lines.any {it.contains("readiness=configuration_unavailable ")});assertFalse(lines.joinToString().contains("private-read-error"))
        val empty=MutableStateFlow(data().copy(evBatteryLevelWh=null))
        val frozen=OalCarPlayVehicleSnapshotAdapter({empty},{now},{config()})
        assertNull(frozen.capabilities(false).evRoutingModel)
        empty.value=data();assertNull(frozen.capabilities(false).evRoutingModel);assertNull(frozen.snapshot()!!.routingModel)
    }
    companion object {const val INFO=0x11600106;const val CAPACITY=0x1160030d;const val BATTERY=0x11600309;const val RANGE=0x11600308}
}
