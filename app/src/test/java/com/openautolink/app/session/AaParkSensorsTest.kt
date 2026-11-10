package com.openautolink.app.session

import com.openautolink.app.transport.ControlMessage
import org.junit.Assert.*
import org.junit.Test

class AaParkSensorsTest {
    @Test fun explicitOverrideAlwaysResolvesTheSameFourSensors() {
        val expected = AaParkSensors(4, 0f, true, false)
        for (raw in listOf(null, ControlMessage.VehicleData(),
            ControlMessage.VehicleData(gearRaw = 8, speedKmh = 72f, parkingBrake = false, driving = true))) {
            assertEquals(expected, AaParkSensors.resolve(true, raw))
        }
    }

    @Test fun offReturnsExactRawValuesWithoutFillingMissingSensors() {
        assertEquals(AaParkSensors(null, null, null, null), AaParkSensors.resolve(false, null))
        val raw = ControlMessage.VehicleData(gearRaw = 8, speedKmh = 72f, parkingBrake = false, driving = true)
        assertEquals(AaParkSensors(8, 72f, false, true), AaParkSensors.resolve(false, raw))
        assertEquals(AaParkSensors(null, 0f, null, false),
            AaParkSensors.resolve(false, ControlMessage.VehicleData(speedKmh = 0f, driving = false)))
    }

    @Test fun resolutionNeverMutatesRawTelemetryOrOtherFields() {
        val raw = ControlMessage.VehicleData(gearRaw = 8, speedKmh = 72f, parkingBrake = false,
            driving = true, rangeKm = 200f, evBatteryLevelWh = 50_000f, nightMode = true)
        val before = raw.copy()
        AaParkSensors.resolve(true, raw)
        assertEquals(before, raw)
    }
}
