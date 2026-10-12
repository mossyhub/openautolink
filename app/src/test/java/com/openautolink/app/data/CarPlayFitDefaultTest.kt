package com.openautolink.app.data

import org.junit.Assert.*
import org.junit.Test

class CarPlayFitDefaultTest {
    @Test fun freshAndLegacyStandardTiersFitTheMeasuredWindowWithUsbSupport() {
        assertTrue("Owner now requests fit by default", CarPlaySettings().fitToWindow)
        assertTrue(CarPlaySettings().enabled)
        for (id in listOf("720p", "1080p", "1440p", "4k")) {
            val migrated = CarPlaySettings.decode("""{"resolution":"$id","fps":59}""")
            assertTrue(migrated.fitToWindow)
            assertTrue(migrated.windowDetail)
            assertTrue(migrated.enabled)
            assertEquals(id, migrated.resolution)
            assertEquals(59, migrated.fps)
        }
    }

    @Test fun explicitFalseIncluding513IsPreservedAndCustomAlwaysStaysExact() {
        val saved513 = CarPlaySettings.decode("""{"resolution":"1440p","fitToWindow":false}""")
        assertFalse(saved513.fitToWindow)
        assertFalse(saved513.windowDetail)
        assertEquals(saved513, CarPlaySettings.decode(CarPlaySettings.encode(saved513)))
        for (id in listOf("custom", "1920x720")) {
            val saved = CarPlaySettings.decode("""{"resolution":"$id","widthPixels":2400,"heightPixels":960}""")
            assertFalse(saved.windowDetail)
            val display = saved.qualifiedDisplay(null, com.shilapi.xcertplay.media.VideoDecoderProbe { "qualified" })
            assertEquals(saved.displayRequest().width, display.width)
            assertEquals(saved.displayRequest().height, display.height)
        }
    }
}
