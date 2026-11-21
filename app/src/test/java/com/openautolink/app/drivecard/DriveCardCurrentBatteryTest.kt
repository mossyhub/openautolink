package com.openautolink.app.drivecard

import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.*
import org.junit.Test

class DriveCardCurrentBatteryTest {
    private val now = DriveCardBatteryHistory589Test.NOW
    private fun data(wh: Float = 50_000f) = DriveCardBatteryHistory589Test.sample(wh = wh)
    private fun snapshot(d: ControlMessage.VehicleData, at: Long = now): DriveCardState = DriveCardModel().run { update(d, now); snapshot(at) }
    // Existing-API behavioral baseline: no compile dependency on the not-yet-present field.
    private fun percent(s: DriveCardState): Int? = s.javaClass.methods.firstOrNull { it.name == "getBatteryPercent" }?.invoke(s) as? Int
    private fun approximate(s: DriveCardState): Boolean? = s.javaClass.methods.firstOrNull { it.name == "getBatteryApproximate" }?.invoke(s) as? Boolean

    @Test fun currentBatteryWorksWithoutSpeedRouteOrEfficiencyHistory() {
        val d = data().copy(speedKmh = null, evChargeRateW = null, evObservationMetadata = data().evObservationMetadata - "PERF_VEHICLE_SPEED")
        val s = snapshot(d)
        assertEquals("Current battery must not depend on driving/history/route", 71, percent(s))
        assertEquals(false, approximate(s))
        assertNull(s.arrival); assertTrue(s.trace.isEmpty())
    }
    @Test fun emptyAndFullAreRealReadingsAndChargingRetainsCurrentLevel() {
        assertEquals(0, percent(snapshot(data(0f))))
        assertEquals(100, percent(snapshot(data(70_000f))))
        val s = snapshot(data().copy(evChargeState = 1))
        assertEquals(DriveCardAvailability.CHARGING, s.availability)
        assertEquals(71, percent(s)); assertNull(s.powerKw)
    }
    @Test fun invalidMissingStaleAndForeignSourcesDoNotBecomeBatteryPercent() {
        val d = data();val o = d.evObservationMetadata.getValue("EV_BATTERY_LEVEL")
        for (bad in listOf(o.copy(status = 1), o.copy(subscriptionActive = false), o.copy(registrationGeneration = 9), o.copy(source = "history"), o.copy(receivedElapsedMs = now - 16_000), o.copy(timestampElapsedNanos = (now + 1) * 1_000_000))) {
            assertNull(percent(snapshot(d.copy(evObservationMetadata = d.evObservationMetadata + ("EV_BATTERY_LEVEL" to bad)))))
        }
        for (wh in listOf(-1f, 70_001f, Float.NaN, Float.POSITIVE_INFINITY)) assertNull(percent(snapshot(data(wh))))
        assertNull(percent(snapshot(d.copy(evCurrentBatteryCapacityWh = null))))
        assertNull(percent(snapshot(d, now + 16_000)))
    }
    @Test fun reportedCapacityFallbackIsExplicitlyApproximateAndNotSavedModelScale() {
        val d = data();val cap = d.evObservationMetadata.getValue("EV_CURRENT_BATTERY_CAPACITY").copy(propertyId = 0x11600106)
        val s = snapshot(d.copy(evCurrentBatteryCapacityWh = null, evBatteryCapacityWh = 80_000f,
            evObservationMetadata = d.evObservationMetadata + ("INFO_EV_BATTERY_CAPACITY" to cap)))
        assertEquals(63, percent(s)); assertEquals(true, approximate(s))
    }
}
