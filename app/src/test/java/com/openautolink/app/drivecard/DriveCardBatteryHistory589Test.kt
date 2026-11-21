package com.openautolink.app.drivecard

import com.openautolink.app.navigation.EnergyAtDistance
import com.openautolink.app.navigation.VehicleEnergyForecast
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import org.junit.Assert.*
import org.junit.Test

/** Tests-only packet: existing public model/state APIs; parent owns compilation and RED. */
class DriveCardBatteryHistory589Test {
    @Test fun directQualifiedBatteryDischargeWorksBeforeAnyDistanceWindow() {
        val state = snapshot(sample())
        assertNull("No completed distance window exists", state.recentKmPerKwh)
        assertTrue(state.trace.isEmpty())
        assertNotNull("Qualified direct discharge is currently ignored", state.powerKw)
        assertEquals("-21000 W charge rate is +21 kW NET BATTERY use, not motor power", 21.0,
            state.powerKw!!, 0.0001)
        assertEquals("Meaningful 84 km/h / 21 kW supports instantaneous battery-use efficiency", 4.0,
            requireNotNull(state.nowKmPerKwh), 0.0001)
        assertEquals(DriveCardAvailability.READY, state.availability)
    }

    @Test fun exactCompletedReadCanQualifyOldContinuousEventButCachedReceiptCannot() {
        val data = sample()
        val rate = data.evObservationMetadata.getValue(RATE)
        val read = rate.copy(timestampElapsedNanos = 1000L * 1_000_000,
            receivedElapsedMs = NOW, statusReadElapsedMs = NOW)
        assertEquals(21.0, requireNotNull(snapshot(withRate(data, read)).powerKw), 0.0001)
        assertNull(snapshot(withRate(data, read.copy(statusReadElapsedMs = NOW - 1))).powerKw)
        assertNull(snapshot(withRate(data, read.copy(statusReadElapsedMs = null))).powerKw)
    }

    @Test fun invalidRateMetadataAndNonfiniteValuesNeverBecomeBatteryPower() {
        val data = sample()
        val rate = data.evObservationMetadata.getValue(RATE)
        val invalid = listOf(rate.copy(status = 1), rate.copy(subscriptionActive = false),
            rate.copy(registrationGeneration = 6), rate.copy(propertyId = BATTERY_ID),
            rate.copy(source = "history"), rate.copy(timestampElapsedNanos = (NOW + 1) * 1_000_000),
            rate.copy(timestampElapsedNanos = 0), rate.copy(receivedElapsedMs = NOW - 16_000),
            rate.copy(timestampElapsedNanos = (NOW - 16_000) * 1_000_000))
        invalid.forEach { assertNull("Reject $it", snapshot(withRate(data, it)).powerKw) }
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach {
            assertNull(snapshot(data.copy(evChargeRateW = it)).powerKw)
        }
        assertNull(snapshot(data.copy(vhalRegistrationGeneration = 0)).powerKw)
        assertNull(snapshot(data.copy(evObservationMetadata = data.evObservationMetadata - RATE)).powerKw)
        val model = DriveCardModel(); model.update(data, NOW)
        assertNull("Timer snapshots do not refresh receipt age", model.snapshot(NOW + 16_000).powerKw)
    }

    @Test fun explicitChargingOneSuppressesDrivingWhileParkedInflowIsNotCharging() {
        val charging = snapshot(sample().copy(evChargeState = 1))
        assertEquals(DriveCardAvailability.CHARGING, charging.availability)
        assertNull(charging.powerKw); assertNull(charging.nowKmPerKwh)
        val parked = snapshot(sample().copy(speedKmh = 0f, gear = "P", evChargeRateW = 500f))
        assertNotEquals("State 3/disconnected +500 W is not external charging", DriveCardAvailability.CHARGING, parked.availability)
        assertNull("Stationary inflow is not positive instantaneous efficiency", parked.nowKmPerKwh)
        // Regen terminology is a renderer/meaning contract, not inferable from a power sign alone.
    }

    @Test fun routeReplacementRemovalAndRerouteRetainVehicleHistoryButRetireOldForecast() {
        for (replacement in listOf<DriveCardRoute?>(null,
            DriveCardRoute("B", 20_000, 900, END), DriveCardRoute("A", 30_000, 1500, END))) {
            val model = learned()
            model.route(DriveCardRoute("A", 20_000, 1000, END))
            model.forecast(VehicleEnergyForecast(EnergyAtDistance(20_000, 35_000, 1000),
                forecastQuality = 2, receivedAtElapsedMs = END), carPlay = false)
            val before = model.snapshot(END)
            assertEquals(50, requireNotNull(before.arrival).percent)
            assertFalse(requireNotNull(before.arrival).approximate)
            assertEquals(5.0, requireNotNull(before.recentKmPerKwh), 0.001)
            assertTrue(before.trace.isNotEmpty())
            model.route(replacement)
            val after = model.snapshot(END)
            assertEquals("Route lifetime must not erase valid vehicle-only efficiency", before.recentKmPerKwh, after.recentKmPerKwh)
            assertEquals("Route lifetime must not erase graph", before.trace, after.trace)
            if (replacement == null) assertNull(after.arrival)
            else assertTrue("Only a newly calculated local approximate arrival may remain", requireNotNull(after.arrival).approximate)
        }
    }

    @Test fun realGenerationChangeGapAndInvalidBatteryStillEraseHistory() {
        for (kind in listOf("generation", "gap", "invalid")) {
            val model = learned(); assertNotNull(model.snapshot(END).recentKmPerKwh)
            val at = if (kind == "gap") END + 16_000 else END + 1000
            var data = sample(at, 49_595f).copy(evChargeRateW = null)
            if (kind == "generation") data = data.copy(vhalRegistrationGeneration = 8,
                evObservationMetadata = data.evObservationMetadata.mapValues { (_, o) -> o.copy(registrationGeneration = 8) })
            if (kind == "invalid") data = data.copy(evBatteryLevelWh = Float.NaN)
            model.update(data, at)
            assertTrue("$kind must remain a genuine reset", model.snapshot(at).trace.isEmpty())
            assertNull(model.snapshot(at).recentKmPerKwh)
        }
    }

    private fun learned() = DriveCardModel().also { model ->
        for (i in 0..80) {
            val t = NOW + i * 1000L
            model.update(sample(t, 50_000f - i * 5).copy(speedKmh = 90f, evChargeRateW = null), t)
        }
    }
    private fun snapshot(data: ControlMessage.VehicleData) = DriveCardModel().run { update(data, NOW); snapshot(NOW) }
    private fun withRate(data: ControlMessage.VehicleData, rate: VehiclePropertyObservation) =
        data.copy(evObservationMetadata = data.evObservationMetadata + (RATE to rate))

    companion object {
        const val NOW = 170_000L
        const val END = NOW + 80_000L
        const val RATE = "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE"
        const val BATTERY_ID = 0x11600309
        fun sample(t: Long = NOW, wh: Float = 50_000f): ControlMessage.VehicleData {
            fun obs(id: Int) = VehiclePropertyObservation(t * 1_000_000, t, 0,
                source = "vhal", registrationGeneration = 7, propertyId = id,
                subscriptionActive = true, changeMode = 2)
            return ControlMessage.VehicleData(speedKmh = 84f, evBatteryLevelWh = wh,
                evCurrentBatteryCapacityWh = 70_000f, evMotorPowerW = null,
                evChargeRateW = -21_000f, evChargeState = 3, chargePortConnected = false,
                vhalRegistrationGeneration = 7, evObservationMetadata = mapOf(
                    "PERF_VEHICLE_SPEED" to obs(0x11600207), "EV_BATTERY_LEVEL" to obs(BATTERY_ID),
                    "EV_CURRENT_BATTERY_CAPACITY" to obs(0x1160030d),
                    "EV_CHARGE_STATE" to obs(0x11400f41), "EV_CHARGE_PORT_CONNECTED" to obs(0x1120030b),
                    RATE to obs(0x1160030c)))
        }
    }
}
