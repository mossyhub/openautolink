package com.openautolink.app.carplay

import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.carplaylab.intake.*
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=android.app.Application::class)
class OalCarPlayEvPilotTest {
    private var now=1000L
    private val observation=VehiclePropertyObservation(1_000_000_000,1000,0,registrationGeneration=7,subscriptionActive=true)
    private val fresh=ControlMessage.VehicleData(carMake="Owner make",carModel="Exact model",fuelTypes=listOf(10),
        evBatteryLevelWh=20000f,evBatteryCapacityWh=85000f,vhalRegistrationGeneration=7,
        evObservationMetadata=mapOf("EV_BATTERY_LEVEL" to observation))
    @Test fun pilotIsOffByDefaultAndOnlyExplicitBoundedCalibrationIsRoundTripped() {
        assertFalse(CarPlaySettings.decode(null).evRoutingPilot.enabled)
        assertFalse(CarPlaySettings.decode("{}").evRoutingPilot.enabled)
        val config=configuration()
        val saved=CarPlaySettings(evRoutingPilot=config)
        assertEquals(saved,CarPlaySettings.decode(CarPlaySettings.encode(saved)))
        assertNull(saved.evRoutingPilot.validationError())
        assertNotNull(config.copy(pilotCapacityWh="060000").validationError())
        val corrupt=CarPlaySettings.decode("""{"ssid":"preserve","evRoutingPilot":{"enabled":"true"}}""")
        assertFalse(corrupt.evRoutingPilot.enabled); assertEquals("preserve",corrupt.ssid)
        assertFalse(config.toString().contains("model_id"))
        assertNotNull(config.copy(sourceModel="").validationError())
        assertNotNull(config.copy(chargingJson="{}").validationError())
        assertNull(config.copy(enabled=false,chargingJson="{}").modelOrNull())
    }
    @Test fun gmSourceEstimateUsesRealEnergyRangeAndOwnerPowerWithoutEquinoxOrUsableCapacityFallback() {
        val config=CarPlaySettings.decode("""{"evRoutingPilot":{"enabled":true,"profileId":"gm-source-estimate","modelCapacityKwh":"60","averageChargeKw":"90"}}""").evRoutingPilot
        val source=fresh.copy(carMake="Chevrolet",carModel="C234",rangeKm=100f,
            evObservationMetadata=mapOf("EV_BATTERY_LEVEL" to observation,"RANGE_REMAINING" to observation))
        val flow=MutableStateFlow(source)
        val adapter=OalCarPlayVehicleSnapshotAdapter({flow},{now},{config})
        val model=adapter.capabilities(false).evRoutingModel
        assertNotNull("Selected GM estimate must build directly from current source, not internal-name/JSON entry",model)
        assertEquals(60000,model!!.pilotCapacityWh)
        assertNull(adapter.snapshot()!!.maxChargeWh)
        val consumption=org.json.JSONObject(model.consumptionJson).getJSONObject("vehicle_parameters")
        val values=consumption.getJSONArray("vehicle_consumption_values_wh_per_m")
        for(i in 0 until 10) assertEquals(20000.0/(100.0*1000),values.getDouble(i),0.0000001)
        assertEquals(0.0,consumption.getDouble("vehicle_auxiliary_power_w"),0.0)
        val charging=org.json.JSONObject(model.chargingJson).getJSONObject("vehicle_parameters")
        for(i in 0 until 11) assertEquals((90*1000).toDouble(),charging.getJSONArray("vehicle_charge_axis_w").getDouble(i),0.0)
        flow.value=source.copy(evBatteryLevelWh=19000f,rangeKm=90f)
        assertSame(model,adapter.snapshot()!!.routingModel)
        flow.value=source.copy(evObservationMetadata=mapOf("EV_BATTERY_LEVEL" to observation,"RANGE_REMAINING" to observation.copy(status=1)))
        assertNull(adapter.snapshot()!!.routingModel)
        val actual=source.copy(evCurrentBatteryCapacityWh=65000f,evObservationMetadata=source.evObservationMetadata+("EV_CURRENT_BATTERY_CAPACITY" to observation))
        val actualAdapter=OalCarPlayVehicleSnapshotAdapter({MutableStateFlow(actual)},{now},{config})
        assertEquals(65000,actualAdapter.capabilities(false).evRoutingModel!!.pilotCapacityWh)
        assertEquals(65000L,actualAdapter.snapshot()!!.maxChargeWh)
        val foreign=OalCarPlayVehicleSnapshotAdapter({MutableStateFlow(source.copy(carMake="Other"))},{now},{config})
        assertNull(foreign.capabilities(false).evRoutingModel)
        val absentScale=CarPlaySettings.decode("""{"evRoutingPilot":{"enabled":true,"profileId":"gm-source-estimate","averageChargeKw":"90"}}""").evRoutingPilot
        assertNull(OalCarPlayVehicleSnapshotAdapter({MutableStateFlow(source)},{now},{absentScale}).capabilities(false).evRoutingModel)
    }

    @Test fun qualifiedDriverDisplayHevcDefaultsOnAndLegacyVetoIsRetiredOnColdRestore() {
        val getter=CarPlaySettings::class.java.methods.firstOrNull {it.name=="getClusterHevcEnabled"}
        assertNotNull("Automatic codec selection must retain its compatibility getter",getter)
        fun enabled(s: CarPlaySettings)=getter!!.invoke(s) as Boolean
        assertTrue(enabled(CarPlaySettings()))
        assertTrue(enabled(CarPlaySettings.decode("{}")))
        val selected=CarPlaySettings.decode("""{"clusterHevcEnabled":true,"ssid":"preserve"}""")
        assertTrue(enabled(selected))
        val cold=CarPlaySettings.decode(CarPlaySettings.encode(selected))
        assertTrue(enabled(cold)); assertEquals("preserve",cold.ssid)
        assertTrue(enabled(CarPlaySettings.decode("""{"clusterHevcEnabled":false}""")))
        val h264=CarPlaySettings.decode("""{"clusterHevcEnabled":false,"videoCodec":"h264"}""")
        assertTrue(enabled(h264)); assertEquals("h264",h264.videoCodec)
        assertEquals("h264",CarPlaySettings.decode(CarPlaySettings.encode(h264)).videoCodec)
    }

    @Test fun missingInitialEnergyAndPreferenceFailuresCannotExpandFrozenModelOffer() {
        val flow=MutableStateFlow(fresh.copy(evBatteryLevelWh=null))
        var reads=0
        val adapter=OalCarPlayVehicleSnapshotAdapter({flow},{now},{reads++;configuration()})
        assertNull(adapter.capabilities(false).evRoutingModel)
        flow.value=fresh
        assertNull(adapter.snapshot()!!.routingModel)
        assertNull(adapter.capabilities(false).evRoutingModel)
        assertEquals(1,reads)
        val unavailable=OalCarPlayVehicleSnapshotAdapter({flow},{now},{throw java.io.IOException("not logged")})
        assertNull(unavailable.capabilities(false).evRoutingModel)
        assertEquals(20000L,unavailable.snapshot()!!.currentChargeWh)
    }

    @Test fun frozenCalibrationNeverPromotesNominalCapacityAndEverySourceDenialVetoesModels() {
        val flow=MutableStateFlow(fresh)
        var config=configuration()
        val adapter=OalCarPlayVehicleSnapshotAdapter({flow},{now},{config})
        val caps=adapter.capabilities(false)
        val frozen=caps.evRoutingModel!!
        assertNull(adapter.snapshot()!!.maxChargeWh)
        assertSame(frozen,adapter.snapshot()!!.routingModel)
        flow.value=fresh.copy(evBatteryLevelWh=0f)
        assertSame(frozen,adapter.snapshot()!!.routingModel)
        flow.value=fresh
        config=config.copy(chargingJson="{}") // changes are next-link only
        flow.value=fresh.copy(evBatteryLevelWh=19000f)
        assertSame(frozen,adapter.snapshot()!!.routingModel)
        val denied=listOf(
            fresh.copy(carModel="Different model"),fresh.copy(carMake=null),fresh.copy(fuelTypes=listOf(1,10)),
            fresh.copy(vhalRegistrationGeneration=8),fresh.copy(evBatteryLevelWh=61000f),
            fresh.copy(evObservationMetadata=emptyMap()),
        ) + listOf(observation.copy(status=1),observation.copy(subscriptionActive=false),observation.copy(source="history"),
            observation.copy(registrationGeneration=6),observation.copy(status=null),
            observation.copy(receivedElapsedMs=1001),observation.copy(timestampElapsedNanos=null))
            .map { fresh.copy(evObservationMetadata=mapOf("EV_BATTERY_LEVEL" to it)) }
        denied.forEach { bad -> flow.value=bad; assertNull(adapter.snapshot()?.routingModel) }
        flow.value=fresh; now=31001; assertNull(adapter.snapshot()?.routingModel)
        now=1000; assertSame(frozen,adapter.snapshot()!!.routingModel)
        assertNull(OalCarPlayVehicleSnapshotAdapter({flow},{now}).capabilities(false).evRoutingModel)
        assertNull(OalCarPlayVehicleSnapshotAdapter({flow},{now},{configuration().copy(sourceModel="Other")}).capabilities(false).evRoutingModel)
    }
    @Test fun usbBootstrapAndWirelessActualIntakeDeliverAuthenticatedModelsThenRetire() {
        for (wired in listOf(false,true)) {
            now=1000
            val flow=MutableStateFlow(fresh)
            val adapter=OalCarPlayVehicleSnapshotAdapter({flow},{now},{configuration()})
            val caps=if(wired) adapter.capabilities(false) else null
            val bootstrap=caps?.let {OalCarDataBootstrap(it,{now},{})}
            bootstrap?.handle(Iap2Messages.buildRaw(0xA100) {void(21)})
            val sent=mutableListOf<Iap2Frame>()
            val gnss=OalCarPlayGnssSnapshotAdapter({now}) { object: GnssForwarder {
                override val isActive=false
                override fun start() {fail("no GPS offer")}
                override fun stop() {}
            }}
            val target=OalCarPlayIntakeLink(false,adapter,gnss,{now},CarPlayAppearanceBridge(flow,Dispatchers.IO,{now},5000),{false},sent::add,{},frozenCapabilities=caps)
            val exact=bootstrap?.wrap(target) ?: target
            val start=Iap2Messages.buildRaw(0xA100) {void(21)}
            val base=Iap2Messages.buildRaw(0x1D01) {u16List(6,emptyList());u16List(7,emptyList())}
            try {
                val bytes=exact.identification(base).encodedFrame()
                if(wired) assertArrayEquals(bootstrap!!.identification(base).encodedFrame(),bytes)
                exact.handle(start); assertTrue(sent.isEmpty())
                exact.authenticate(); if(!wired) exact.handle(start)
                val body=Iap2BodyReader.of(sent.single())
                assertEquals(setOf(22,26,27),body.list().map {it.id}.toSet())
                assertFalse(body.has(23)); assertEquals(0,body.raw(26).last().toInt())
                val first=body.raw(26)
                now+=30000; flow.value=fresh.copy(evBatteryLevelWh=19000f,evObservationMetadata=mapOf("EV_BATTERY_LEVEL" to observation.copy(receivedElapsedMs=now,timestampElapsedNanos=now*1000000)))
                exact.tick(true); assertArrayEquals(first,Iap2BodyReader.of(sent.last()).raw(26))
                val count=sent.size
                now+=30001; exact.tick(true); assertEquals(count,sent.size)
                exact.revoke(); now+=30000; exact.authenticate(); exact.handle(start); exact.tick(true); assertEquals(count,sent.size)
            } finally {exact.close();bootstrap?.close()}
        }
    }
    companion object {
        fun configuration(): CarPlayEvPilotConfiguration {
            val energies=(0..10).joinToString(",") {(it*6000).toString()}
            val powers=(0..10).joinToString(",") {"50000"}
            val steps=(0..20).joinToString(",") {(it*3000).toString()}
            return CarPlayEvPilotConfiguration(true,"Owner make","Exact model","60000",
                """{"model_id":12582922,"vehicle_parameters":{"vehicle_energy_axis_wh":[$energies],"vehicle_charge_axis_w":[$powers],"energy_w_per_h":[$steps],"efficiency_factor":0.95}}""",
                """{"model_id":12582921,"vehicle_parameters":{"vehicle_auxiliary_power_w":400,"vehicle_consumption_values_wh_per_m":[0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2,0.2],"vehicle_altitude_gain_consumption_wh_per_m":6.4,"vehicle_altitude_loss_consumption_wh_per_m":4.3}}""")
        }
    }
}
