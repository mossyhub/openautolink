package com.openautolink.app.drivecard

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.app.navigation.*
import org.junit.Assert.*
import org.junit.Test

class DriveCardModelTest {
    private fun tick(t: Long, wh: Float = 50000f, speed: Float = 90f, generation: Long = 1,
        charging: Boolean = false, power: Float? = null, status: Int = 0): ControlMessage.VehicleData {
        fun observation() = VehiclePropertyObservation(t * 1000000, t, status, "vhal", generation, subscriptionActive = true)
        return ControlMessage.VehicleData(speedKmh = speed, evBatteryLevelWh = wh,
            evCurrentBatteryCapacityWh = 70000f, chargePortConnected = charging, evMotorPowerW = power,
            vhalRegistrationGeneration = generation, evObservationMetadata = listOf("PERF_VEHICLE_SPEED",
                "EV_BATTERY_LEVEL", "EV_CURRENT_BATTERY_CAPACITY", "EV_MOTOR_POWER", "EV_CHARGE_STATE",
                "EV_CHARGE_PORT_CONNECTED", "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE").associateWith { observation() })
    }
    @Test fun disconnectedNotChargingStateOutranksPositiveChargeRate587Capture() {
        val model=DriveCardModel()
        for (i in 0..40) {
            val t=1000L+i*1000
            model.update(tick(t,55430f-i*2.75f).copy(chargePortConnected=false,evChargeState=3,evChargeRateW=1000f),t)
        }
        assertNotNull("Falling pack energy in state NOT_CHARGING is driving data, not a charging hold",model.snapshot(41000).recentKmPerKwh)
    }
    @Test fun aospChargingIsOneNotFullyChargedTwo() {
        val model=DriveCardModel()
        model.update(tick(1000,power=12000f).copy(evChargeState=1),1000)
        assertNull("AOSP CHARGING=1 must suppress driving power",model.snapshot(1000).powerKw)
        model.update(tick(2000,power=12000f).copy(evChargeState=2),2000)
        assertEquals(12.0,model.snapshot(2000).powerKw!!,0.001)
    }
    @Test fun rollingRateUsesActualDistanceAndEnergy() {
        val model = DriveCardModel()
        for (i in 0..40) model.update(tick(1000L + i * 1000, 50000f - i * 5), 1000L + i * 1000)
        val state = model.snapshot(41000)
        assertEquals(5.0, state.recentKmPerKwh!!, 0.02)
        assertTrue(state.trace.isNotEmpty())
        assertNull("Battery delta is not instantaneous motor power", state.powerKw)
    }
    @Test fun staleInvalidAndChargingDataCannotInventEfficiency() {
        for (data in listOf(tick(1000, status = 1), tick(1000, charging = true), tick(1000))) {
            val model = DriveCardModel()
            model.update(data, 50000)
            assertNull(model.snapshot(50000).recentKmPerKwh)
            assertNull(model.snapshot(50000).powerKw)
        }
    }
    @Test fun generationGapAndReverseTimeClearHistory() {
        val model = DriveCardModel()
        for (i in 0..40) model.update(tick(1000L + i * 1000, 50000f - i * 5), 1000L + i * 1000)
        assertNotNull(model.snapshot(41000).recentKmPerKwh)
        model.update(tick(42000, generation = 2), 42000)
        assertTrue(model.snapshot(42000).trace.isEmpty())
        model.update(tick(1000), 1000)
        assertTrue(model.snapshot(1000).trace.isEmpty())
    }
    @Test fun directPowerCanShowRegenButNotNegativeEfficiency() {
        val model = DriveCardModel()
        model.update(tick(1000, power = -12000f), 1000)
        val state = model.snapshot(1000)
        assertEquals(-12.0, state.powerKw!!, 0.001)
        assertNull(state.nowKmPerKwh)
        assertNull(model.snapshot(20000).powerKw)
    }
    @Test fun localArrivalRequiresLearnedRateFreshRouteAndUsableCapacity() {
        val model = DriveCardModel()
        for (i in 0..80) model.update(tick(1000L + i * 1000, 50000f - i * 5), 1000L + i * 1000)
        model.route(DriveCardRoute("routeA", 20000, 1200, 81000))
        val arrival = model.snapshot(81000).arrival!!
        assertTrue(arrival.approximate)
        assertEquals(65, arrival.percent)
        assertNull(model.snapshot(250000).arrival)
        val trace = model.snapshot(81000).trace
        val recent = model.snapshot(81000).recentKmPerKwh
        model.route(DriveCardRoute("routeB", 20000, 1200, 81000))
        assertEquals("Route replacement retains vehicle-only history", trace, model.snapshot(81000).trace)
        assertEquals(recent, model.snapshot(81000).recentKmPerKwh)
        assertEquals("Fresh replacement may recompute a local estimate", arrival, model.snapshot(81000).arrival)
    }
    @Test fun freshAaForecastWinsButIsNeverReusedForCarPlay() {
        val model = DriveCardModel()
        model.update(tick(1000), 1000)
        model.route(DriveCardRoute("A", 10000, 500, 1000))
        val forecast = VehicleEnergyForecast(EnergyAtDistance(10000, 35000, 500), forecastQuality = 2, receivedAtElapsedMs = 1000)
        model.forecast(forecast, carPlay = false)
        assertEquals(50, model.snapshot(1000).arrival!!.percent)
        model.forecast(forecast, carPlay = true)
        assertNull(model.snapshot(1000).arrival)
        model.clear()
        assertEquals(DriveCardState(), model.snapshot(1000))
    }
    @Test fun aaNextChargingStopMustNotMasqueradeAsDestinationArrival() {
        val model = DriveCardModel()
        model.update(tick(1000), 1000)
        model.route(DriveCardRoute("destination", 20000, 1200, 1000))
        model.forecast(VehicleEnergyForecast(EnergyAtDistance(1000, 35000, 60), forecastQuality = 2,
            receivedAtElapsedMs = 1000), carPlay = false)
        assertNull("An intermediate charging stop is not destination arrival", model.snapshot(1000).arrival)
    }
    @Test fun sourceGenerationReplacementRejectsCachedHistoryProviderPower() {
        val model = DriveCardModel()
        val initial = tick(1000, power = 12000f)
        val observation = initial.evObservationMetadata.getValue("EV_MOTOR_POWER").copy(
            source = "history-provider-coherent", registrationGeneration = null, subscriptionActive = false)
        model.update(initial.copy(evObservationMetadata = initial.evObservationMetadata + ("EV_MOTOR_POWER" to observation)), 1000)
        assertEquals(12.0, model.snapshot(1000).powerKw!!, 0.001)
        val successor = tick(2000, generation = 2, power = 12000f)
        model.update(successor.copy(evObservationMetadata = successor.evObservationMetadata + ("EV_MOTOR_POWER" to observation)), 2000)
        assertNull("Previous source generation must not carry cached power", model.snapshot(2000).powerKw)
    }
    @Test fun regenerativePackWindowsAreGapsNotFictitiousEfficiency() {
        val model = DriveCardModel()
        for (i in 0..40) model.update(tick(1000L+i*1000,50000f+i*5),1000L+i*1000)
        val state=model.snapshot(41000)
        assertNull(state.recentKmPerKwh)
        assertTrue(state.trace.isNotEmpty())
        assertTrue(state.trace.all { it.isNaN() })
    }
    @Test fun historyIsBoundedAndDuplicateTicksDoNotAccumulateDistance() {
        val model = DriveCardModel()
        for(i in 0..10000) model.update(tick(1000L + i * 1000, 50000f - i), 1000L + i * 1000)
        assertTrue(model.snapshot(10001000).trace.size <= 128)
        val before = model.snapshot(10001000)
        repeat(100) { model.update(tick(10001000, 40000f), 10001000) }
        assertEquals(before, model.snapshot(10001000))
    }
}
