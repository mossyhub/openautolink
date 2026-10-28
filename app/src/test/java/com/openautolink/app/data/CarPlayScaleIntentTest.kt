package com.openautolink.app.data

import com.openautolink.carplaylab.media.CarPlayAutoScalePolicy
import com.shilapi.xcertplay.media.VideoDecoderProbe
import org.junit.Assert.*
import org.junit.Test

class CarPlayScaleIntentTest {
    @Test fun autoWithoutViewportMustNotMasqueradeAsManual() {
        val settings = CarPlaySettings(autoScale = true, autoResolution = false, fitToWindow = false,
            widthPhysicalMm = 400)
        val display = settings.qualifiedDisplay(null, VideoDecoderProbe { "fixture.decoder" })
        assertEquals(CarPlayAutoScalePolicy.Basis.CALIBRATION_NEEDED, display.physicalSize.basis)
        assertEquals(400, display.physicalSize.widthMm)
    }

    @Test fun expandedManualCalibrationRoundTripsWhileOldIntentRemainsExact() {
        for (width in listOf(100, 225, 399, 400, 401, 800, 1200, 1600, 2000)) {
            val original = CarPlaySettings(autoScale = false, widthPhysicalMm = width)
            assertEquals(original, CarPlaySettings.decode(CarPlaySettings.encode(original)))
            assertEquals(width, original.displayRequest().widthPhysicalMm)
        }
    }
}
