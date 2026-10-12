package com.openautolink.carplaylab.media

import org.junit.Assert.*
import org.junit.Test

class CarPlayDisplayPolicyTest {
    @Test fun availableProfilesCheckEveryExactAvcSizeAndRate() {
        val checked = mutableListOf<Triple<Int, Int, Int>>()
        val probe = AvcDecoderProbe {
            checked += Triple(it.width, it.height, it.fps)
            if (it.width == 1280 && it.fps <= 60 || it.width == 1920 && it.fps <= 30) "exact-avc-decoder" else null
        }
        val supported = CarPlayDisplayPolicy.availableDisplays(probe = probe)
        assertEquals(CarPlayResolution.PRESETS.flatMap { resolution ->
            CarPlayDisplayPreferences.FRAME_RATES.map { Triple(resolution.width, resolution.height, it) } }.toSet(), checked.toSet())
        assertEquals(checked.filter { (w, _, rate) -> w == 1280 && rate <= 60 || w == 1920 && rate <= 30 }.size, supported.size)
        assertTrue(supported.all { it.codec == "H264" })
        assertTrue(supported.contains(CarPlayDisplayPreferences(CarPlayResolution.FULL_HD1080, 30)))
        assertFalse(supported.contains(CarPlayDisplayPreferences(CarPlayResolution.FULL_HD1080, 60)))
    }

    @Test fun unsupportedStoredProfileFallsBackToSafe720p30PreservingScale() {
        val requested = CarPlayDisplayPreferences(CarPlayResolution.FULL_HD1080, 60, 300, true)
        val selection = CarPlayDisplayPolicy.select(requested, AvcDecoderProbe {
            if (it.resolution == CarPlayResolution.HD720 && it.fps == 30) "avc-720-only" else null
        })
        assertEquals(requested.copy(resolution = CarPlayResolution.HD720, fps = 30), selection.display)
        assertEquals("avc-720-only", selection.decoderName)
        assertTrue(selection.usedFallback)
    }

    @Test fun supportedRequestRetainsTheExactDecoderRatherThanDefaultMimeSelection() {
        val requested = CarPlayDisplayPreferences(CarPlayResolution.FULL_HD1080, 60)
        val selection = CarPlayDisplayPolicy.select(requested, AvcDecoderProbe { "qualified-avc" })
        assertEquals(requested, selection.display)
        assertEquals("qualified-avc", selection.decoderName)
        assertFalse(selection.usedFallback)
    }

    @Test fun absentOrFailedInventoryCannotAdvertiseUnprovenFallback() {
        assertEquals(emptyList<CarPlayDisplayPreferences>(), CarPlayDisplayPolicy.availableDisplays(probe = AvcDecoderProbe { null }))
        assertThrows(IllegalStateException::class.java) {
            CarPlayDisplayPolicy.select(CarPlayDisplayPreferences(), AvcDecoderProbe { null })
        }
        assertThrows(IllegalStateException::class.java) {
            CarPlayDisplayPolicy.select(CarPlayDisplayPreferences(), AvcDecoderProbe { throw IllegalArgumentException() })
        }
    }
}
