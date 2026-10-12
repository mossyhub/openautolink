package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class MicrophoneSupportTest {
    private val peer = InetAddress.getLoopbackAddress()
    private val capable = MicrophoneCapability.of(mapOf("default" to setOf(0x20000000L, 0x40000000L),
        "telephony" to setOf(0x20000000L, 0x40000000L), "speechrecognition" to setOf(0x20000000L)))
    private fun config(bits: Long = 0x20000000L, type: Int = 100, kind: String = "default", port: Any? = 6000, spf: Any? = null) =
        MicrophoneSupport.negotiate(capable, type, kind, bits, port, spf, peer, ByteArray(32))

    @Test fun captureAlwaysUses48kMono20msButNegotiatedClockIsPreserved() {
        val siri = config(kind = "speechrecognition")!!
        assertEquals(48000, siri.sampleRate)
        assertEquals(1, siri.channels)
        assertEquals(1920, siri.frameBytes)
        assertEquals(480, siri.rtpSamplesPerPacket)
        assertEquals(64000, siri.bitrate)
        val call = config(0x40000000L, kind = "telephony")!!
        assertEquals(960, call.rtpSamplesPerPacket)
        assertEquals(96000, call.bitrate)
        assertNotNull(config()) // default MIC is implemented, not just advertised
    }

    @Test fun admissionRejectsUnobservedClockCombinedBitsOtherTypesAndMalformedPortOrFrameSize() {
        assertNull(config(0x10000000L))
        assertNull(config(0x60000000L))
        assertNull(config(type = 101))
        assertNull(config(type = 102))
        assertNull(config(kind = "media"))
        assertNull(config(port = 6000.5))
        assertNull(config(port = 65536))
        assertNull(config(port = "6000"))
        assertNull(config(spf = 960)) // 24 kHz RTP clock requires 480 samples/20ms
        assertNotNull(config(spf = 480))
    }

    @Test fun offersAreExactPermissionGatedInputFormatsWithoutVocoderOverclaim() {
        val cfg = AirPlayConfig("Lab", "id", "00:11:22:33:44:55", "950.7.1", AirPlayDisplayConfig(1280,720), microphone = true)
        val output = setOf(0x20000000L, 0x40000000L, 0x800000L)
        fun rows(cap: MicrophoneCapability) = AirPlayInfoPlist.build(cfg, output, cap)["audioFormats"] as List<*>
        assertTrue(rows(MicrophoneCapability.NONE).none { (it as Map<*, *>).containsKey("audioInputFormats") })
        val info = AirPlayInfoPlist.build(cfg, output, capable)
        val offered = (info["audioFormats"] as List<*>).map { it as Map<*, *> }.filter { it.containsKey("audioInputFormats") }
        assertEquals(setOf("default", "telephony", "speechRecognition"), offered.map { it["audioType"] }.toSet())
        offered.forEach {
            assertEquals(100, it["type"])
            assertEquals(0L, (it["audioInputFormats"] as Long) and 0x10000000L)
        }
        assertEquals(listOf("vocoderInfo","enhancedRequestCarUI"), info["extendedFeatures"])
        val disabled = AirPlayInfoPlist.build(cfg.copy(microphone = false), output, capable)["audioFormats"] as List<*>
        assertTrue(disabled.none { (it as Map<*, *>).containsKey("audioInputFormats") })
    }

    @Test fun capabilityDoesNotPromoteDecoderInventoryOrUnknownKindsToInput() {
        val cap = MicrophoneCapability.of(mapOf("media" to setOf(0x40000000L), "default" to setOf(0x10000000L)))
        assertFalse(cap.available)
    }

    @Test fun sourceSpecificFailureNeverPromotesAnotherSourceIntoAnInputOffer() {
        val cap = MicrophoneCapability.of(mapOf("default" to setOf(0x40000000L)))
        val rows = MicrophoneSupport.addInputOffers(AudioOutputSupport.wirelessRows(setOf(0x20000000L, 0x40000000L)), cap)
        val input = rows.filter { it.containsKey("audioInputFormats") }
        assertEquals(1, input.size)
        assertEquals("default", input.single()["audioType"])
        assertEquals(0x40000000L, input.single()["audioInputFormats"])
        val unsupportedOutput = MicrophoneSupport.addInputOffers(AudioOutputSupport.wirelessRows(setOf(0x8000000L)), cap)
        assertTrue(unsupportedOutput.none { it.containsKey("audioInputFormats") })
    }

    @Test fun opusAccessUnitDurationIsCheckedBeforeTimestampAdvance() {
        assertEquals(960, MicrophoneSupport.opusSamples(byteArrayOf(0x78, 1)))
        assertEquals(480, MicrophoneSupport.opusSamples(byteArrayOf(0x70, 1)))
        assertEquals(1920, MicrophoneSupport.opusSamples(byteArrayOf(0x79, 1, 2)))
        assertNull(MicrophoneSupport.opusSamples(byteArrayOf()))
        assertNull(MicrophoneSupport.opusSamples(byteArrayOf(0x7b)))
        assertNull(MicrophoneSupport.opusSamples(byteArrayOf(0x7b, 0)))
    }
}
