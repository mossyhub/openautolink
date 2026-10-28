package com.openautolink.carplaylab.media

import org.junit.Assert.*
import org.junit.Test

class CarPlayHigherDisplayTest {
    @Test fun unsupportedRequestNeverFallsForwardToAnotherExperimentalProfile() {
        val requested = CarPlayDisplayPreferences(CarPlayResolution.UHD4K, 60, 400)
        assertThrows(IllegalStateException::class.java) {
            CarPlayDisplayPolicy.select(requested, AvcDecoderProbe {
                if (it.resolution == CarPlayResolution.QHD1440 && it.fps == 30) "only-qhd" else null
            })
        }
    }

    @Test fun qualified720FallbackPreservesScaleAndSharesAdvertisedSinkDimensions() {
        for (resolution in listOf(CarPlayResolution.QHD1440, CarPlayResolution.UHD4K)) {
            val requested = CarPlayDisplayPreferences(resolution, 60, 350, true)
            val seen = mutableListOf<CarPlayDisplayPreferences>()
            val selection = CarPlayDisplayPolicy.select(requested, AvcDecoderProbe {
                seen += it
                if (it.resolution == CarPlayResolution.HD720 && it.fps == 30) "exact-720" else null
            })
            assertEquals(listOf(requested, requested.copy(resolution = CarPlayResolution.HD720, fps = 30)), seen)
            assertEquals("exact-720", selection.decoderName)
            assertTrue(selection.usedFallback)
            val config = benchReceiverConfig("00:11:22:33:44:55", 41234, display = selection.display)
            assertEquals(selection.display.width, config.main.widthPixels)
            assertEquals(selection.display.height, config.main.heightPixels)
            assertEquals(350, config.main.widthPhysicalMm)
            assertEquals(197, config.main.heightPhysicalMm)
            assertTrue(config.rightHandDrive)
        }
    }

    @Test fun everyQualifiedCandidatePreservesPhysicalAspectRatioInActualInfoFields() {
        for (resolution in CarPlayResolution.PRESETS) {
            for (widthMm in CarPlayDisplayPreferences.PHYSICAL_WIDTHS_MM) {
                val requested = CarPlayDisplayPreferences.fromStored(resolution.id, 45, widthMm)
                val selected = CarPlayDisplayPolicy.select(requested, AvcDecoderProbe { "qualified-${it.width}-${it.fps}" })
                assertEquals(requested, selected.display)
                assertFalse(selected.usedFallback)
                val config = benchReceiverConfig("00:11:22:33:44:55", 41234, display = selected.display)
                val info = com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(config, emptySet())
                val main = (info["displays"] as List<*>).first() as Map<*, *>
                assertEquals(resolution.width, main["widthPixels"])
                assertEquals(resolution.height, main["heightPixels"])
                assertEquals(widthMm, main["widthPhysical"])
                assertEquals(Math.round(widthMm * resolution.height.toDouble() / resolution.width).toInt(), main["heightPhysical"])
                assertFalse(main.keys.any { it.toString().contains("dpi", ignoreCase = true) })
            }
        }
    }

    @Test fun presetIdsRemainStableWithoutImplyingPhoneAcceptance() {
        assertEquals(listOf("800x480", "960x540", "720p", "1920x720", "1080p", "1080x1920", "1440p", "4k"),
            CarPlayResolution.PRESETS.map { it.id })
        assertFalse(CarPlayResolution.PRESETS.contains(CarPlayResolution.CUSTOM))
        for (preset in CarPlayResolution.PRESETS) {
            assertThrows(IllegalStateException::class.java) {
                CarPlayDisplayPolicy.select(CarPlayDisplayPreferences(preset), AvcDecoderProbe { null })
            }
        }
    }

    @Test fun candidatesIncludeExact1440pAnd4kAvcSizesWithoutRateSubstitution() {
        val checked = mutableListOf<Triple<Int, Int, Int>>()
        val available = CarPlayDisplayPolicy.availableDisplays(
            CarPlayDisplayPreferences(widthPhysicalMm = 350), AvcDecoderProbe {
                checked += Triple(it.width, it.height, it.fps)
                when (Triple(it.width, it.height, it.fps)) {
                    Triple(2560, 1440, 45), Triple(3840, 2160, 30) -> "qualified-avc"
                    else -> null
                }
            })
        assertEquals(CarPlayResolution.PRESETS.size * CarPlayDisplayPreferences.FRAME_RATES.size, checked.distinct().size)
        assertEquals(listOf(Triple(2560, 1440, 45), Triple(3840, 2160, 30)),
            available.map { Triple(it.width, it.height, it.fps) })
        assertTrue(available.all { it.widthPhysicalMm == 350 && it.codec == "H264" })
    }
}
