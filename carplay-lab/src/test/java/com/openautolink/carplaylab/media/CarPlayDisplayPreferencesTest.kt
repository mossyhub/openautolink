package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.*
import org.junit.Test

class CarPlayDisplayPreferencesTest {
    @Test fun defaultPreservesProven720p30AndH264Only() {
        val config = benchReceiverConfig("00:11:22:33:44:55", 41234)
        assertEquals(1280, config.main.widthPixels)
        assertEquals(720, config.main.heightPixels)
        assertEquals(30, config.main.fps)
        assertFalse(config.hevc)
    }

    @Test fun selectedDisplayFlowsIntoInfoAndHidWithoutChangingAudioOrIdentity() {
        val display = CarPlayDisplayPreferences(CarPlayResolution.FULL_HD1080, 60, 250, true)
        val config = benchReceiverConfig("00:11:22:33:44:55", 41234, display = display)
        assertEquals(1920, config.main.widthPixels)
        assertEquals(1080, config.main.heightPixels)
        assertEquals(60, config.main.fps)
        assertEquals(250, config.main.widthPhysicalMm)
        assertEquals(141, config.main.heightPhysicalMm)
        assertTrue(config.rightHandDrive)
        assertFalse(config.hevc)
        assertEquals("00:11:22:33:44:55", config.deviceId)
        assertFalse(config.mainBufferedAudio)
        assertNull(config.cluster)
        val info = AirPlayInfoPlist.build(config, emptySet())
        val displays = info["displays"] as List<*>
        val main = displays.first() as Map<*, *>
        assertEquals(1920, main["widthPixels"])
        assertEquals(1080, main["heightPixels"])
        val hid = (info["hidDevices"] as List<*>).first() as Map<*, *>
        val descriptor = hid["hidDescriptor"] as ByteArray
        assertTrue(descriptor.toList().windowed(3).contains(listOf(0x26.toByte(), 0x80.toByte(), 0x07.toByte())))
        assertTrue(descriptor.toList().windowed(3).contains(listOf(0x26.toByte(), 0x38.toByte(), 0x04.toByte())))
    }

    @Test fun invalidSettingsAreRejectedAndStoredCorruptionUsesValidatedDefaults() {
        assertThrows(IllegalArgumentException::class.java) { CarPlayDisplayPreferences(fps = 0) }
        assertThrows(IllegalArgumentException::class.java) { CarPlayDisplayPreferences(fps = 61) }
        assertThrows(IllegalArgumentException::class.java) { CarPlayDisplayPreferences(widthPhysicalMm = -1) }
        assertEquals(225, CarPlayDisplayPreferences(widthPhysicalMm = 225).widthPhysicalMm)
        assertThrows(IllegalArgumentException::class.java) { CarPlayDisplayPreferences(widthPhysicalMm = 401) }
        assertEquals(CarPlayDisplayPreferences(), CarPlayDisplayPreferences.fromStored("unknown", -1, Int.MAX_VALUE, false))
        val selected = CarPlayDisplayPreferences(CarPlayResolution.FULL_HD1080, 60, 300, true)
        assertEquals(selected, CarPlayDisplayPreferences.fromStored(selected.resolution.id, selected.fps, selected.widthPhysicalMm, selected.rightHandDrive))
    }
}
