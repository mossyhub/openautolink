package com.openautolink.app.data

import org.junit.Assert.*
import org.junit.Test

class CarPlayAutoScaleSettingsTest {
    @Test fun autoScaleIsAtomicDefaultsOnAndKeepsManualCalibrationThroughCanonicalRestore() {
        assertTrue(CarPlaySettings.encode(CarPlaySettings()).contains("\"autoScale\":true"))
        val manual = CarPlaySettings.decode("""{"autoScale":false,"widthPhysicalMm":350,"resolution":"1440p","fps":60}""")
        assertTrue(CarPlaySettings.encode(manual).contains("\"autoScale\":false"))
        assertEquals(350, manual.widthPhysicalMm)
        assertEquals("1440p", manual.resolution)
        assertEquals(manual, CarPlaySettings.decode(CarPlaySettings.encode(manual)))
        val legacy = CarPlaySettings.decode("""{"widthPhysicalMm":350}""")
        assertTrue(CarPlaySettings.encode(legacy).contains("\"autoScale\":true"))
        val invalid = CarPlaySettings.decode("""{"autoScale":false,"widthPhysicalMm":999,"resolution":"8k","fps":120}""")
        assertEquals(200, invalid.widthPhysicalMm)
        assertEquals("720p", invalid.resolution)
        assertEquals(30, invalid.fps)
        assertTrue(CarPlaySettings.encode(invalid).contains("\"autoScale\":false"))
    }
}
