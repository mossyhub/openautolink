package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.*
import org.junit.Test

class CarPlayContinuousScaleTest {
    @Test fun autoSnapshotSeparatesMeasuredViewportFromCodedCustomSizeAndReportsFinalPhysicalMetadata() {
        val method = CarPlayDisplayPolicy.javaClass.methods.firstOrNull { it.name == "scaleDiagnostic" }
        assertNotNull("Expose sanitized actual-offer scale diagnostic", method)
        val input = CarPlayAutoScalePolicy.Input(960, 540, 1920, 1080, 240.0, 240.0, 160)
        val wide = CarPlayDisplayPreferences.custom(2400, 960, 31).copy(autoScaleInput = input)
        val snapshot = method!!.invoke(CarPlayDisplayPolicy, wide) as String
        assertTrue(snapshot.contains("mode=auto"))
        assertTrue(snapshot.contains("viewport=960x540"))
        assertTrue(snapshot.contains("display=1920x1080"))
        assertTrue(snapshot.contains("coded=2400x960"))
        assertTrue(snapshot.contains("basis=hardware_estimate"))
        assertTrue(snapshot.contains("physical=${wide.physicalSize.widthMm}x${wide.physicalSize.heightMm}"))
        val uncertain = wide.copy(autoScaleInput = input.copy(xdpi = Double.NaN))
        val fallback = method.invoke(CarPlayDisplayPolicy, uncertain) as String
        assertTrue(fallback.contains("basis=calibration_needed"))
        assertFalse(fallback.contains("NaN"))
        val manual = wide.copy(widthPhysicalMm = 225, autoScaleInput = null)
        assertTrue((method.invoke(CarPlayDisplayPolicy, manual) as String).contains("physical=225x90"))
    }

    @Test fun everyIntegerManualWidthSurvivesStorageAndActualWireWithoutSnapping() {
        for (width in CarPlayDisplayPreferences.PHYSICAL_WIDTHS_MM) {
            val display = CarPlayDisplayPreferences.fromStored("720p", 31, width)
            assertEquals(width, display.widthPhysicalMm)
            assertEquals(width, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(width))
            val info = AirPlayInfoPlist.build(benchReceiverConfig("00:11:22:33:44:55", 41234, display = display), emptySet())
            val main = (info["displays"] as List<*>).first() as Map<*, *>
            assertEquals(width, main["widthPhysical"])
            assertEquals(Math.round(width * 720.0 / 1280).toInt(), main["heightPhysical"])
        }
    }
}
