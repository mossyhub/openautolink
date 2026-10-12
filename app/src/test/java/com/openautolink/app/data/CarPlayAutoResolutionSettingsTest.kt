package com.openautolink.app.data

import org.junit.Assert.*
import org.junit.Test

class CarPlayAutoResolutionSettingsTest {
    @Test fun automaticResolutionRoundTripsWithoutChangingManualGeometryOrScaling() {
        val stored = """{"autoResolution":true,"resolution":"custom","widthPixels":2400,"heightPixels":960,"fps":31,"autoScale":false,"widthPhysicalMm":225,"videoCodec":"hevc"}"""
        val restored = CarPlaySettings.decode(stored)
        assertTrue("Automatic resolution must survive atomic persistence", CarPlaySettings.encode(restored).contains("\"autoResolution\":true"))
        assertEquals(2400, restored.widthPixels)
        assertEquals(960, restored.heightPixels)
        assertEquals(31, restored.fps)
        assertFalse(restored.autoScale)
        assertEquals(225, restored.widthPhysicalMm)
        assertTrue(restored.displayRequest().preferHevc)
    }
}
