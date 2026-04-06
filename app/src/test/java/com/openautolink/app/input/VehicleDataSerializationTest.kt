package com.openautolink.app.input

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.ControlMessageSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleDataSerializationTest {

    @Test
    fun `serialize vehicle data with all fields`() {
        val data = ControlMessage.VehicleData(
            speedKmh = 65.0f,
            gear = "D",
            gearRaw = 8,           // GEAR_DRIVE — gearRaw is what gets serialized
            batteryPct = 72,
            parkingBrake = false,
            nightMode = false,
            fuelLevelPct = 80,
            rangeKm = 350.5f,
            lowFuel = false,
            odometerKm = 12345.6f,
            ambientTempC = 22.5f,
            headlight = 2,
            hazardLights = false,
            driving = true
        )

        val json = ControlMessageSerializer.serialize(data)

        assertTrue(json.contains(""""type":"vehicle_data""""))
        // Bridge wire format: speed in mm/s (65 km/h ≈ 18055 mm/s)
        assertTrue(json.contains(""""speed_mm_s":18055"""))
        // gear serializes gearRaw (int), not display string
        assertTrue(json.contains(""""gear":8"""))
        // batteryPct wins over fuelLevelPct → fuel_level_pct
        assertTrue(json.contains(""""fuel_level_pct":72"""))
        assertTrue(json.contains(""""parking_brake":false"""))
        assertTrue(json.contains(""""night_mode":false"""))
        assertTrue(json.contains(""""low_fuel":false"""))
        assertTrue(json.contains(""""headlight":2"""))
        assertTrue(json.contains(""""hazard":false"""))
        assertTrue(json.contains(""""driving":true"""))
        // range in meters
        assertTrue(json.contains(""""range_m":350500"""))
        // odometer in km × 10
        assertTrue(json.contains(""""odometer_km_e1":123456"""))
        // temp in millidegrees
        assertTrue(json.contains(""""temp_e3":22500"""))
    }

    @Test
    fun `serialize vehicle data with only speed`() {
        val data = ControlMessage.VehicleData(speedKmh = 100.0f)

        val json = ControlMessageSerializer.serialize(data)

        assertTrue(json.contains(""""type":"vehicle_data""""))
        // Bridge wire format: speed_mm_s (100 km/h ≈ 27777 mm/s)
        assertTrue(json.contains(""""speed_mm_s":27777"""))
        // Should not contain null fields
        assert(!json.contains("gear"))
        assert(!json.contains("fuel_level_pct"))
    }

    @Test
    fun `serialize vehicle data with no fields`() {
        val data = ControlMessage.VehicleData()

        val json = ControlMessageSerializer.serialize(data)

        assertTrue(json.contains(""""type":"vehicle_data""""))
        // Only type field, no null fields serialized
        assertEquals("""{"type":"vehicle_data"}""", json)
    }

    @Test
    fun `serialize gnss message`() {
        val nmea = "\$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A"
        val msg = ControlMessage.Gnss(nmea)

        val json = ControlMessageSerializer.serialize(msg)

        assertTrue(json.contains(""""type":"gnss""""))
        assertTrue(json.contains(""""nmea""""))
        assertTrue(json.contains("GPRMC"))
    }

    @Test
    fun `serialize gnss with special characters`() {
        val nmea = "\$GPGGA,092750.000,5321.6802,N,00630.3372,W,1,8,1.03,61.7,M,55.2,M,,*76"
        val msg = ControlMessage.Gnss(nmea)

        val json = ControlMessageSerializer.serialize(msg)

        assertTrue(json.contains("GPGGA"))
    }
}
