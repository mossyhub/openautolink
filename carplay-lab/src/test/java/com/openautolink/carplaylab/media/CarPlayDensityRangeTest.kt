package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.*
import org.junit.Test

class CarPlayDensityRangeTest {
    @Test fun portraitCalibrationBoundsBothMillimeterAxesTogetherBeforeSerialization() {
        val request = CarPlayDisplayPreferences(CarPlayResolution.PORTRAIT1080, widthPhysicalMm = 2000)
        val size = request.physicalSize
        assertEquals(1125, size.widthMm)
        assertEquals(2000, size.heightMm)
        val info = AirPlayInfoPlist.build(benchReceiverConfig("00:11:22:33:44:55", 41234, display = request), emptySet())
        val wire = (info["displays"] as List<*>).single() as Map<*, *>
        assertEquals(size.widthMm, wire["widthPhysical"])
        assertEquals(size.heightMm, wire["heightPhysical"])
        assertEquals(2000, request.widthPhysicalMm) // Retain saved calibration intent, not the bounded offer.
    }

    @Test fun smallerUiCalibrationReachesExistingReportedWidthEnvelopeWithoutChangingCanvas() {
        // Existing serializer envelope is local policy, not a recovered Apple maximum.
        for (width in listOf(401, 600, 800, 1200, 1600, 2000)) {
            val request = CarPlayDisplayPreferences.fromStored("custom", 60, width,
                customWidth = 3076, customHeight = 1196)
            assertEquals("Saved smaller-UI calibration must not silently restore 200mm", width, request.widthPhysicalMm)
            assertEquals(width, AirPlayDisplaySettings.sanitizeWidthPhysicalMm(width))
            val info = AirPlayInfoPlist.build(benchReceiverConfig("00:11:22:33:44:55", 41234, display = request), emptySet())
            val display = (info["displays"] as List<*>).single() as Map<*, *>
            assertEquals(width, display["widthPhysical"])
            assertEquals(Math.round(width * 1196.0 / 3076).toInt(), display["heightPhysical"])
            assertEquals(3076, display["widthPixels"])
            assertEquals(1196, display["heightPixels"])
            assertEquals(60, display["maxFPS"])
            val bytes = com.shilapi.xcertplay.airplay.BplistCodec.encode(info)
            val reparsed = com.shilapi.xcertplay.airplay.BplistCodec.decode(bytes) as Map<*, *>
            val wire = (reparsed["displays"] as List<*>).single() as Map<*, *>
            assertEquals(width, (wire["widthPhysical"] as Number).toInt())
            assertEquals(Math.round(width * 1196.0 / 3076).toInt(), (wire["heightPhysical"] as Number).toInt())
            assertEquals(3076, (wire["widthPixels"] as Number).toInt())
        }
    }

    @Test fun ownerAutoAliasFallbackExactlyExplainsIdenticalManualOffer() {
        val input = CarPlayAutoScalePolicy.Input(2914, 1134, 2914, 1134,
            200.04200744628906, 200.02499389648438, 200)
        val manual = CarPlayDisplayPreferences.custom(3076, 1196, 60).copy(widthPhysicalMm = 400)
        val automatic = manual.copy(autoScaleInput = input)
        assertEquals(CarPlayAutoScalePolicy.Basis.CALIBRATION_NEEDED, automatic.physicalSize.basis)
        assertEquals(manual.toAirPlayDisplay(), automatic.toAirPlayDisplay())
        // Wider millimeters, not more coded pixels, are the requested smaller-UI lever.
        assertEquals(1600, manual.copy(widthPhysicalMm = 1600).toAirPlayDisplay().widthPhysicalMm)
        assertEquals(3076, manual.copy(widthPhysicalMm = 1600).toAirPlayDisplay().widthPixels)
    }
}
