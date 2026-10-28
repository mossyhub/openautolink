package com.openautolink.carplaylab.media

import org.junit.Assert.*
import org.junit.Test

class CarPlayAutoScalePolicyTest {
    @Test fun shrinkingViewportScalesReferenceCalibrationWithoutCodecResolutionContamination() {
        val untrusted = CarPlayAutoScalePolicy.Input(2914, 919, 2914, 1134, 160.0, 160.0, 160)
        val fullscreen = untrusted.copy(viewportHeightPx = 1134)
        for (tier in CarPlayResolution.PRESETS) {
            val full = CarPlayAutoScalePolicy.resolve(tier.width, tier.height, 200, fullscreen)
            val visible = CarPlayAutoScalePolicy.resolve(tier.width, tier.height, 200, untrusted)
            val fullFittedWidth = minOf(2914.0, 1134.0 * tier.width / tier.height)
            val visibleFittedWidth = minOf(2914.0, 919.0 * tier.width / tier.height)
            assertEquals(Math.round(200 * fullFittedWidth / 2914).toInt(), full.widthMm)
            assertEquals(Math.round(200 * fullFittedWidth / 2914 * tier.height / tier.width).toInt(), full.heightMm)
            assertEquals(Math.round(200 * visibleFittedWidth / 2914).toInt(), visible.widthMm)
            assertEquals(Math.round(200 * visibleFittedWidth / 2914 * tier.height / tier.width).toInt(), visible.heightMm)
            assertEquals(CarPlayAutoScalePolicy.Basis.CALIBRATION_NEEDED, visible.basis)
        }
    }

    @Test fun missingViewportHasStableFallbackWhileManualOverrideIsExact() {
        val missing = CarPlayAutoScalePolicy.Input(0, 0, 0, 0, Double.NaN, Double.NaN, 0)
        for (tier in CarPlayResolution.PRESETS) {
            assertEquals(CarPlayAutoScalePolicy.Size(350, Math.round(350.0 * tier.height / tier.width).toInt(), CarPlayAutoScalePolicy.Basis.MANUAL),
                CarPlayAutoScalePolicy.resolve(tier.width, tier.height, 350, null))
            assertEquals(CarPlayAutoScalePolicy.Size(350, Math.round(350.0 * tier.height / tier.width).toInt(), CarPlayAutoScalePolicy.Basis.CALIBRATION_NEEDED),
                CarPlayAutoScalePolicy.resolve(tier.width, tier.height, 350, missing))
        }
    }

    @Test fun qualifiedFallbackCarriesTheViewportAndAdvertisesResolvedMillimeters() {
        val input = CarPlayAutoScalePolicy.Input(1920, 1080, 1920, 1080, 240.0, 240.0, 160)
        val requested = CarPlayDisplayPreferences(resolution = CarPlayResolution.QHD1440,
            widthPhysicalMm = 350, autoScaleInput = input)
        val selected = CarPlayDisplayPolicy.select(requested, AvcDecoderProbe {
            if (it.resolution == CarPlayResolution.HD720) "exact.baseline" else null
        })
        assertTrue(selected.usedFallback)
        assertEquals("exact.baseline", selected.decoderName)
        val display = selected.display.toAirPlayDisplay()
        assertEquals(1280, display.widthPixels)
        assertEquals(203, display.widthPhysicalMm)
        assertEquals(114, display.heightPhysicalMm)
        val manual = requested.copy(autoScaleInput = null, scaleMode = CarPlayScaleReceipt.Mode.MANUAL).toAirPlayDisplay()
        assertEquals(350, manual.widthPhysicalMm)
        assertEquals(197, manual.heightPhysicalMm)
    }

    @Test fun customWidePortraitAndSquareUseActualViewportNotCodedResolution() {
        val visible = CarPlayAutoScalePolicy.Input(960, 540, 1920, 1080, 240.0, 240.0, 160)
        val full = visible.copy(viewportWidthPx = 1920, viewportHeightPx = 1080)
        for ((width, height) in listOf(2400 to 960, 1080 to 1920, 1000 to 1000)) {
            val coded = CarPlayDisplayPreferences.custom(width, height, 59)
            val halfSize = coded.copy(autoScaleInput = visible, scaleMode = CarPlayScaleReceipt.Mode.AUTOMATIC).physicalSize
            val fullSize = coded.copy(autoScaleInput = full, scaleMode = CarPlayScaleReceipt.Mode.AUTOMATIC).physicalSize
            val fittedWidth = minOf(960.0, 540.0 * width / height)
            assertEquals(Math.round(fittedWidth * 25.4 / 240).toInt(), halfSize.widthMm)
            assertEquals(Math.round(fittedWidth * 25.4 / 240 * height / width).toInt(), halfSize.heightMm)
            assertTrue(kotlin.math.abs(fullSize.widthMm - 2 * halfSize.widthMm) <= 1)
            assertTrue(kotlin.math.abs(fullSize.heightMm - 2 * halfSize.heightMm) <= 1)
            // A change only in coded resolution must not change the physical metadata.
            assertEquals(halfSize, CarPlayDisplayPreferences.custom(width * 2, height * 2, 31)
                .copy(autoScaleInput = visible, scaleMode = CarPlayScaleReceipt.Mode.AUTOMATIC).physicalSize)
            val manual = coded.copy(widthPhysicalMm = 225, autoScaleInput = null, scaleMode = CarPlayScaleReceipt.Mode.MANUAL).physicalSize
            assertEquals(225, manual.widthMm)
            assertEquals(Math.round(225.0 * height / width).toInt(), manual.heightMm)
            assertEquals(CarPlayAutoScalePolicy.Basis.MANUAL, manual.basis)
        }
        val portraitViewport = CarPlayAutoScalePolicy.Input(540, 960, 1080, 1920, 240.0, 240.0, 160)
        val portrait = CarPlayDisplayPreferences.custom(1080, 1920, 31).copy(autoScaleInput = portraitViewport, scaleMode = CarPlayScaleReceipt.Mode.AUTOMATIC).physicalSize
        assertEquals(Math.round(540 * 25.4 / 240).toInt(), portrait.widthMm)
        assertEquals(Math.round(960 * 25.4 / 240).toInt(), portrait.heightMm)
    }

    @Test fun unreliableMetricsUseStableReferenceCalibrationNotLogicalDensity() {
        val good = CarPlayAutoScalePolicy.Input(960, 540, 1920, 1080, 240.0, 240.0, 160)
        val suspect = listOf(good.copy(xdpi = Double.NaN), good.copy(ydpi = Double.POSITIVE_INFINITY),
            good.copy(xdpi = 0.0), good.copy(xdpi = 160.0, ydpi = 160.0),
            good.copy(xdpi = 160.0, ydpi = 160.0, densityDpi = 320),
            good.copy(xdpi = 240.0, ydpi = 480.0), good.copy(xdpi = 1001.0),
            good.copy(displayWidthPx = 0), good.copy(viewportWidthPx = 1921),
            good.copy(viewportHeightPx = 0))
        for (input in suspect) {
            val size = CarPlayAutoScalePolicy.resolve(2560, 1440, 200, input)
            assertEquals(CarPlayAutoScalePolicy.Basis.CALIBRATION_NEEDED, size.basis)
            // Valid viewport/display geometry follows DiPlay current/reference WIDTH basis.
            assertEquals(if (input.displayWidthPx > 0 && input.viewportWidthPx in 1..input.displayWidthPx && input.viewportHeightPx in 1..input.displayHeightPx) 100 else 200, size.widthMm)
        }
    }

    @Test fun samePhysicalViewportKeepsMillimetersAcrossQualifiedVideoTiers() {
        val input = CarPlayAutoScalePolicy.Input(1920, 1080, 1920, 1080, 240.0, 240.0, 160)
        for (tier in CarPlayResolution.PRESETS) {
            val size = CarPlayAutoScalePolicy.resolve(tier.width, tier.height, 200, input)
            val fittedWidth = minOf(1920.0, 1080.0 * tier.width / tier.height)
            assertEquals(Math.round(fittedWidth * 25.4 / 240).toInt(), size.widthMm)
            assertEquals(Math.round(fittedWidth * 25.4 / 240 * tier.height / tier.width).toInt(), size.heightMm)
            assertEquals(CarPlayAutoScalePolicy.Basis.HARDWARE_ESTIMATE, size.basis)
        }
    }
}
