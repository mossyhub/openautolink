package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.media.MediaCodecSupport
import org.junit.Assert.*
import org.junit.Test

class LabMediaFormatsTest {
    @Test fun pcmOnlyInfoNeverOffersCompressedOrInputAudio() {
        val info = AirPlayInfoPlist.build(AirPlayConfig("Lab", "00:11:22:33:44:55", "00:11:22:33:44:55",
            "1.0", AirPlayDisplayConfig(1280, 720, fps = 30), lpcmOnly = true))
        val entries = info["audioFormats"] as List<*>
        assertEquals(8, entries.size)
        entries.forEach { item ->
            val entry = item as Map<*, *>
            val bits = (entry["audioOutputFormats"] as Number).toLong()
            assertEquals(0L, bits and 0x70c00000L)
            assertFalse(entry.containsKey("audioInputFormats"))
            assertTrue((entry["type"] as Number).toInt() in setOf(100, 101))
        }
        assertFalse(info.containsKey("hevcInfo"))
        assertFalse(info.containsKey("mainBufferedInfo"))
        assertEquals(1, (info["displays"] as List<*>).size)
        assertEquals(listOf("iAPChannel", "viewAreas"), setupEnabledFeatures(
            AirPlayConfig("Lab", "id", "00:11:22:33:44:55", "1.0", AirPlayDisplayConfig(1280,720)),
            listOf("hevc", "videoPlayback", "mainBuffered")))
    }
    @Test fun lengthPrefixedAvcConvertsWithoutChangingNalBytes() {
        assertArrayEquals(byteArrayOf(0,0,0,1,0x65,1,2),
            ScreenCodec.lengthPrefixedToAnnexB(byteArrayOf(0,0,0,3,0x65,1,2)))
    }
    @Test fun h264ReferenceChainRequiresActualIdr() {
        assertTrue(MediaCodecSupport.isRandomAccess(byteArrayOf(0,0,0,1,0x65,1), VideoCodec.H264))
        assertFalse(MediaCodecSupport.isRandomAccess(byteArrayOf(0,0,0,1,0x41,1), VideoCodec.H264))
    }
    @Test fun unsignedConnectionIdPreservedInKeySalt() {
        assertEquals("18446744073709551615", unsignedPlistDecimal(-1L))
        assertEquals(java.math.BigInteger("18446744073709551615"), unsignedPlistInteger(-1L))
    }
    @Test fun negotiatedPcmMatchesRenderer() {
        assertEquals(AudioFormat(AudioCodecKind.LPCM,48000,2,100,"media"), AudioStreamCodec.fromFormatBits(0x8000,100))
    }
}
