package com.openautolink.app.data

import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [32, 34], application = android.app.Application::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class CarPlayAutoScaleSettingsTest {
    @Test fun nullViewportMatchesOldTimeoutOfferButRetainsMissingAutoProvenance() {
        val probe = com.shilapi.xcertplay.media.VideoDecoderProbe { "exact" }
        val saved = CarPlaySettings(autoScale = true)
        val sentinel = com.openautolink.carplaylab.media.CarPlayAutoScalePolicy.Input(
            0, 0, 0, 0, Double.NaN, Double.NaN, 0)
        val old = saved.qualifiedDisplay(sentinel, probe)
        val actual = saved.qualifiedDisplay(null, probe)
        assertEquals(old.toAirPlayDisplay(), actual.toAirPlayDisplay())
        assertEquals(old.qualifiedVideoSelection, actual.qualifiedVideoSelection)
        assertEquals(com.openautolink.carplaylab.media.CarPlayScaleReceipt.Reason.MISSING_VIEWPORT,
            actual.resolvedScale!!.reason)
        assertSame(actual.resolvedScale, actual.scaleReceipt)
        assertEquals(com.openautolink.carplaylab.media.CarPlayScaleReceipt.Mode.MANUAL,
            saved.copy(autoScale = false).qualifiedDisplay(null, probe).resolvedScale!!.mode)
    }

    @Test fun legacyManualIntegersAndInvalidSavedWidthsKeepTheRollbackPolicy() {
        val probe = com.shilapi.xcertplay.media.VideoDecoderProbe { "exact" }
        for (width in 100..400) {
            val restored = CarPlaySettings.decode("""{"autoScale":false,"autoResolution":false,"fitToWindow":false,"widthPhysicalMm":$width,"resolution":"1080p"}""")
            assertEquals(width, restored.widthPhysicalMm)
            assertEquals(width, restored.qualifiedDisplay(null, probe).resolvedScale!!.effectiveWidthMm)
            assertEquals(restored, CarPlaySettings.decode(CarPlaySettings.encode(restored)))
        }
        for (invalid in listOf(1178, 422)) {
            assertEquals(200, CarPlaySettings.decode("""{"widthPhysicalMm":$invalid}""").widthPhysicalMm)
        }
    }

    @Test fun missingViewportRemainsExplicitAutoWithLegacyTimeoutNumbers() {
        val selected = CarPlaySettings(autoScale = true, autoResolution = false, fitToWindow = false,
            resolution = "custom", widthPixels = 3076, heightPixels = 1196)
            .qualifiedDisplay(null, com.shilapi.xcertplay.media.VideoDecoderProbe { "exact" })
        assertEquals(200, selected.toAirPlayDisplay().widthPhysicalMm)
        assertEquals(78, selected.toAirPlayDisplay().heightPhysicalMm)
        assertTrue("Missing viewport must not silently become Manual",
            com.openautolink.carplaylab.media.CarPlayDisplayPolicy.scaleDiagnostic(selected)
                .contains("mode=auto basis=calibration_needed"))
        val getter = selected.javaClass.methods.firstOrNull { it.name == "getResolvedScale" }
        assertNotNull("Qualified display must freeze a scale receipt", getter)
        assertNotNull(getter!!.invoke(selected))
    }

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
