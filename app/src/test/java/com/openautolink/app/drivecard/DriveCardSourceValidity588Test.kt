package com.openautolink.app.drivecard

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import org.junit.Assert.*
import org.junit.Test

class DriveCardSourceValidity588Test {
    private val now=170000L
    private fun observation(id: Int, mode: Int?=2, read: Boolean=true, at: Long=1000L) =
        VehiclePropertyObservation(at*1000000,now,0,registrationGeneration=7,propertyId=id,
            subscriptionActive=true,changeMode=mode,statusReadElapsedMs=if(read)now else null)
    private fun data(mode: Int?=2, read: Boolean=true) = ControlMessage.VehicleData(speedKmh=0f,
        evBatteryLevelWh=55430f,evBatteryCapacityWh=85990f,evCurrentBatteryCapacityWh=null,
        chargePortConnected=false,evChargeState=3,evChargeRateW=null,vhalRegistrationGeneration=7,
        evObservationMetadata=mapOf("EV_BATTERY_LEVEL" to observation(BATTERY,mode,read),
            "PERF_VEHICLE_SPEED" to observation(SPEED,2,true,now),
            "EV_CHARGE_STATE" to observation(STATE,1), "EV_CHARGE_PORT_CONNECTED" to observation(PORT,1),
            "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to observation(RATE,2)))
    private fun availability(state: DriveCardState): String {
        val getter=state.javaClass.methods.firstOrNull {it.name=="getAvailability"}
        assertNotNull("Shared card API must distinguish learning from missing vehicle data",getter)
        return getter!!.invoke(state).toString()
    }
    private fun snapshot(d: ControlMessage.VehicleData, at:Long=now):DriveCardState = DriveCardModel().run {update(d,at);snapshot(at)}
    @Test fun exactReadOrKnownStableModeProvidesFreshLearningNotMissingData() {
        for (mode in listOf(0,1,2,null)) {
            val state=snapshot(data(mode,mode !in listOf(0,1)))
            assertEquals("LEARNING",availability(state))
            assertNull(state.arrival);assertNull(state.powerKw);assertNull(state.recentKmPerKwh)
        }
        assertEquals("UNAVAILABLE",availability(snapshot(data(2,false))))
        assertEquals("UNAVAILABLE",availability(snapshot(data(null,false))))
    }
    @Test fun failedInactiveWrongGenerationWrongPropertyAndStaleReadsStayUnavailable() {
        val d=data();val o=d.evObservationMetadata.getValue("EV_BATTERY_LEVEL")
        for (bad in listOf(o.copy(status=1),o.copy(subscriptionActive=false),o.copy(registrationGeneration=6),
            o.copy(propertyId=RANGE),o.copy(receivedElapsedMs=now-16000,statusReadElapsedMs=now-16000),
            o.copy(statusReadElapsedMs=now-1),o.copy(timestampElapsedNanos=(now+1)*1000000),o.copy(source="history"))) {
            assertEquals("UNAVAILABLE",availability(snapshot(d.copy(evObservationMetadata=d.evObservationMetadata+("EV_BATTERY_LEVEL" to bad)))))
        }
        assertEquals("UNAVAILABLE",availability(snapshot(d,now+16000)))
        assertEquals("UNAVAILABLE",availability(snapshot(d.copy(evBatteryLevelWh=Float.NaN))))
    }
    @Test fun freshExplicitStateOutranksPortAndRateAndStaleChargingCannotHideLearning() {
        // Keep a real qualified flow in this charging-precedence scenario. Other source/history
        // fixtures intentionally omit the now independently supported battery-power input.
        val d=data().copy(evChargeRateW=1000f)
        assertEquals("READY",availability(snapshot(d)))
        assertEquals("CHARGING",availability(snapshot(d.copy(evChargeState=1))))
        assertEquals("READY",availability(snapshot(d.copy(evChargeState=2,chargePortConnected=true))))
        assertEquals("READY",availability(snapshot(d.copy(evChargeState=4,chargePortConnected=true))))
        val stale=d.evObservationMetadata.getValue("EV_CHARGE_STATE").copy(receivedElapsedMs=now-16000,statusReadElapsedMs=now-16000)
        assertEquals("READY",availability(snapshot(d.copy(evChargeState=1,evObservationMetadata=d.evObservationMetadata+("EV_CHARGE_STATE" to stale)))))
        assertEquals("UNAVAILABLE",availability(snapshot(ControlMessage.VehicleData(evChargeState=1,chargePortConnected=true,evChargeRateW=1000f))))
    }
    @Test fun genuineAaForecastMustNotUseReportedNominalAsItsSourceUsableDenominator() {
        val d=data().copy(evObservationMetadata=data().evObservationMetadata+("INFO_EV_BATTERY_CAPACITY" to observation(INFO,0)))
        val m=DriveCardModel();m.update(d,now);m.route(DriveCardRoute("current",10000,500,now))
        m.forecast(com.openautolink.app.navigation.VehicleEnergyForecast(
            com.openautolink.app.navigation.EnergyAtDistance(10000,35000,500),forecastQuality=2,receivedAtElapsedMs=now),false)
        assertNull("Only LOCAL learned arrival may use explicitly approximate reported scale",m.snapshot(now).arrival)
    }
    @Test fun reportedCapacityMayScaleOnlyLocalApproximateArrivalAndMustRemainQualified() {
        fun learned(bad: VehiclePropertyObservation? = null): DriveCardState {
            val m=DriveCardModel()
            for(i in 0..80) {
                val t=now+i*1000
                val d=data().copy(speedKmh=90f,evBatteryLevelWh=55430f-i*5)
                val metadata=d.evObservationMetadata.mapValues {(_,o)->o.copy(receivedElapsedMs=t,statusReadElapsedMs=t)} +
                    ("INFO_EV_BATTERY_CAPACITY" to (bad ?: observation(INFO,0)).copy(receivedElapsedMs=t,statusReadElapsedMs=t))
                m.update(d.copy(evObservationMetadata=metadata),t)
            }
            m.route(DriveCardRoute("current",10000,500,now+80000))
            return m.snapshot(now+80000)
        }
        val valid=learned();assertNotNull(valid.arrival);assertTrue(valid.arrival!!.approximate)
        assertEquals(kotlin.math.round((55030.0-2000.0)/85990.0*100).toInt(),valid.arrival!!.percent)
        for (bad in listOf(observation(INFO).copy(status=1),observation(INFO).copy(registrationGeneration=6),
            observation(INFO).copy(subscriptionActive=false),observation(INFO).copy(propertyId=BATTERY),
            observation(INFO).copy(source="history"))) assertNull(learned(bad).arrival)
    }
    @Test fun repeatedSameStatusReadDoesNotInventDistanceOrPowerAndLearningTransitionsToReady() {
        val m=DriveCardModel();val d=data().copy(speedKmh=90f)
        repeat(100) {m.update(d,now)}
        assertEquals("LEARNING",availability(m.snapshot(now)));assertNull(m.snapshot(now).powerKw)
        for(i in 1..40) {
            val t=now+i*1000
            val metadata=d.evObservationMetadata.mapValues {(_,o)->o.copy(receivedElapsedMs=t,statusReadElapsedMs=t)}
            m.update(d.copy(evBatteryLevelWh=55430f-i*5,evObservationMetadata=metadata),t)
        }
        assertEquals("READY",availability(m.snapshot(now+40000)))
        assertEquals(5.0,m.snapshot(now+40000).recentKmPerKwh!!,0.02)
        assertNull("Design85990Wh is not usable capacity",m.snapshot(now+40000).arrival)
        assertNull(m.snapshot(now+40000).powerKw)
    }
    companion object {const val INFO=0x11600106;const val BATTERY=0x11600309;const val SPEED=0x11600207;const val STATE=0x11400f41;const val PORT=0x1120030b;const val RATE=0x1160030c;const val RANGE=0x11600308}
}
