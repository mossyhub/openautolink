package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.*
import org.junit.Test

class CarPlayCustomVideoContractTest {
    @Test fun sourceBackedPresetsIncludeWideAndPortraitNotJustHdTiers() {
        val sizes = CarPlayResolution.entries.map { it.width to it.height }.toSet()
        assertTrue(sizes.containsAll(listOf(800 to 480, 960 to 540, 1280 to 720,
            1920 to 720, 1920 to 1080, 1080 to 1920, 2560 to 1440, 3840 to 2160)))
    }

    private fun custom(width: Int, height: Int, fps: Int): CarPlayDisplayPreferences {
        val factory = CarPlayDisplayPreferences.Companion.javaClass.methods.firstOrNull {
            it.name == "custom" && it.parameterCount == 3
        }
        assertNotNull("Model must expose independently validated custom dimensions", factory)
        return factory!!.invoke(CarPlayDisplayPreferences.Companion, width, height, fps) as CarPlayDisplayPreferences
    }

    @Test fun customWideAndPortraitKeepExactQualificationWireHidAspectAndPhysicalSpace() {
        for ((w, h) in listOf(2400 to 960, 1080 to 1920, 1000 to 1000, 800 to 480)) {
            val display = custom(w, h, 31)
            val seen = mutableListOf<Triple<Int, Int, Int>>()
            val selected = CarPlayDisplayPolicy.select(display, AvcDecoderProbe {
                seen += Triple(it.width, it.height, it.fps)
                if (it.width == w && it.height == h && it.fps == 31) "exact-custom-decoder" else null
            })
            assertEquals(listOf(Triple(w, h, 31)), seen)
            assertFalse(selected.usedFallback)
            val config = benchReceiverConfig("00:11:22:33:44:55", 41234, display = selected.display)
            val info = AirPlayInfoPlist.build(config, emptySet())
            val main = (info["displays"] as List<*>).first() as Map<*, *>
            assertEquals(w, main["widthPixels"]); assertEquals(h, main["heightPixels"])
            assertEquals(31, main["maxFPS"])
            assertEquals(Math.round(200.0 * h / w).toInt(), main["heightPhysical"])
            val hid = (info["hidDevices"] as List<*>).first() as Map<*, *>
            assertEquals(main["uuid"], hid["displayUUID"])
            val bytes = (hid["hidDescriptor"] as ByteArray).toList().windowed(3)
            for (axis in listOf(w, h)) assertTrue(bytes.contains(listOf(0x26.toByte(), axis.toByte(), (axis shr 8).toByte())))
            val rect = CarPlayDisplayPolicy.fitRect(display, 1200, 800)!!
            assertEquals(w.toFloat() / h, rect.width / rect.height, 0.001f)
        }
    }

    @Test fun unsupportedCustomFallsBackOnlyToQualifiedBaselineNotRoundedDimensionsOrRates() {
        val display = custom(2400, 960, 59)
        val selected = CarPlayDisplayPolicy.select(display, AvcDecoderProbe {
            if (Triple(it.width, it.height, it.fps) == Triple(1280, 720, 30)) "baseline" else null
        })
        assertTrue(selected.usedFallback)
        assertEquals(Triple(1280, 720, 30), Triple(selected.display.width, selected.display.height, selected.display.fps))
    }

    @Test fun localHidRepresentationNeverQualifiesDimensionsThatWouldTruncateAtU16() {
        val display = custom(65536, 960, 31)
        assertFalse(CarPlayDisplayPolicy.isQualified(display, AvcDecoderProbe { "decoder-receipt" }))
        val selected = CarPlayDisplayPolicy.select(display, AvcDecoderProbe { "decoder-receipt" })
        assertTrue(selected.usedFallback)
        assertEquals(1280, selected.display.width)
        // This is the implemented HID representation bound, not a universal phone size maximum.
        assertTrue(CarPlayDisplayPreferences.validDimensions(65536, 960))
    }

    @Test fun nonpositiveOddAndOverflowDimensionsAreRejectedNotCorrected() {
        for ((w, h) in listOf(0 to 720, -2 to 720, 2401 to 960, 2400 to 961, Int.MAX_VALUE to 720)) {
            try { custom(w, h, 30); fail("Invalid custom dimension accepted: $w x $h") }
            catch (e: java.lang.reflect.InvocationTargetException) { assertTrue(e.cause is IllegalArgumentException) }
        }
    }
}
