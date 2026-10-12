package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import com.openautolink.carplaylab.media.MediaPolicy
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioOutputSupportTest {
    @Test fun reference48kEntertainmentCandidatesIntersectTheExactImplementationInventory() {
        // DiPlay 0.2.13 release AirPlayInfoPlist.audioFormats and cloned source agree.
        val expected = listOf(
            Triple(100,"compatibility",0xc3fcL), Triple(101,"compatibility",0xc3fcL),
            Triple(100,"default",0x7000c3fcL), Triple(100,"alert",0x7000c3fcL),
            Triple(100,"media",0xc3fcL), Triple(100,"telephony",0x70004154L),
            Triple(100,"speechRecognition",0x70004154L), Triple(101,"default",0x7000c3fcL),
            Triple(102,"media",0x800000L))
        val inventories = listOf(AudioOutputSupport.implementedBits,
            AudioOutputSupport.implementedPcmBits + setOf(0x20000000L,0x40000000L,0x800000L),
            setOf(0x400000L,0x1000000L,0x2000000L,0x4000000L,0x8000000L),
            setOf(0x40000000L,0x800000L,0x1000L,1L shl 32), emptySet())
        inventories.forEach { available ->
            val inventoryMask = available.intersect(AudioOutputSupport.implementedBits)
                .fold(0L) { mask,bit -> mask or bit }
            val rows = AudioOutputSupport.wirelessRows(available)
            assertEquals(expected.map { (type,kind,mask) -> Triple(type,kind,mask and inventoryMask) }
                .filter { it.third != 0L }, rows.map { Triple(it["type"],it["audioType"],it["audioOutputFormats"]) })
            rows.forEach { assertFalse(it.containsKey("audioInputFormats")) }
        }
    }
    @Test fun capturedDeviceInventoryProducesQualifiedReferenceRowsWithoutCopyingReferenceInputs() {
        // audio-boundary-20261009-140914.log:29; fixture only, not a new phone receipt.
        val available = setOf(4L,8L,16L,32L,64L,128L,256L,512L,1024L,2048L,16384L,32768L,
            4194304L,8388608L,16777216L,33554432L,67108864L,134217728L,
            268435456L,536870912L,1073741824L)
        val probed = MicrophoneCapability.of(mapOf(
            "default" to setOf(0x20000000L,0x40000000L),
            "telephony" to setOf(0x20000000L,0x40000000L),
            "speechrecognition" to setOf(0x20000000L)))
        val config = AirPlayConfig("Lab","id","00:11:22:33:44:55","1.0",AirPlayDisplayConfig(1280,720),microphone=true)
        val rows = (AirPlayInfoPlist.build(config,available,probed)["audioFormats"] as List<*>)
            .map { it as Map<*,*> }
        assertEquals(listOf(0xc3fcL,0xc3fcL,0x7000c3fcL,0x7000c3fcL,0xc3fcL,
            0x70004154L,0x70004154L,0x7000c3fcL,0x800000L),
            rows.map { it["audioOutputFormats"] })
        assertEquals(listOf(null,null,0x60000000L,null,null,0x60000000L,0x20000000L,null,null),
            rows.map { it["audioInputFormats"] })
        rows.forEach { row ->
            println("qualified-catalog-fixture type=${row["type"]} audioType=${row["audioType"]} " +
                "output=${row["audioOutputFormats"]} input=${row["audioInputFormats"] ?: "absent"}")
        }
    }
    @Test fun exactCompressedImplementationMappingIsSeparateFromTheReferenceOfferPolicy() {
        val expected = listOf(
            Triple(0x400000L, 44100, AudioCodecKind.AAC_LC),
            Triple(0x800000L, 48000, AudioCodecKind.AAC_LC),
            Triple(0x1000000L, 44100, AudioCodecKind.AAC_ELD),
            Triple(0x2000000L, 48000, AudioCodecKind.AAC_ELD),
            Triple(0x4000000L, 16000, AudioCodecKind.AAC_ELD),
            Triple(0x8000000L, 24000, AudioCodecKind.AAC_ELD))
        expected.forEach { (bits, rate, codec) ->
            val type = if (codec == AudioCodecKind.AAC_LC) 102 else 100
            val kind = if (codec == AudioCodecKind.AAC_LC) "media"
                else if (bits in setOf(0x1000000L,0x2000000L)) "alert" else "default"
            val format = checkNotNull(AudioOutputSupport.format(bits, type, kind))
            assertEquals(codec, format.codec); assertEquals(rate, format.sampleRate)
            assertEquals(if (bits >= 0x4000000) 1 else 2, format.channels)
            assertEquals(bits == 0x800000L, AudioOutputSupport.negotiate(bits,type,kind) != null)
        }
        assertNull(AudioOutputSupport.negotiate(0x70000000, 100))
        assertNull(AudioOutputSupport.negotiate(0x800000L or 0x8000L, 102))
        assertNull(AudioOutputSupport.negotiate(0x800000, 100))
        assertNull(AudioOutputSupport.negotiate(0x40000000, 102))
        assertNull(AudioOutputSupport.negotiate(0x8000, 103))
        assertNull(AudioOutputSupport.negotiate(1L shl 32, 100))
    }
    @Test fun opusDecodeRateIsSeparateFromNegotiatedRtpClock() {
        listOf(0x10000000L to 16000, 0x20000000L to 24000, 0x40000000L to 48000).forEach { (bits, clock) ->
            val format = checkNotNull(AudioOutputSupport.negotiate(bits, 101))
            assertEquals(AudioCodecKind.OPUS, format.codec)
            assertEquals(48000, format.sampleRate); assertEquals(clock, format.rtpClockRate)
            assertEquals(1, format.channels)
        }
    }
    @Test fun codecConfigurationUsesRawAacAscNotAdts() {
        val lc = AudioOutputSupport.configuration(checkNotNull(AudioOutputSupport.negotiate(0x800000, 102)))!!
        assertEquals("audio/mp4a-latm", lc.mime); assertEquals(2, lc.aacProfile)
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), lc.csd.single())
        val lc44 = AudioOutputSupport.configuration(checkNotNull(AudioOutputSupport.format(0x400000, 102)))!!
        assertArrayEquals(byteArrayOf(0x12, 0x10), lc44.csd.single())
        val eld = AudioOutputSupport.configuration(checkNotNull(AudioOutputSupport.format(0x4000000, 100)))!!
        assertEquals(39, eld.aacProfile)
        // escape AOT=31 + 7 (39-32), rate index=8, mono=1, 512 samples,
        // resilience/SBR off, ELD extension terminator=0, epConfig=0.
        assertArrayEquals(byteArrayOf(0xf8.toByte(), 0xf0.toByte(), 0x20, 0), eld.csd.single())
        assertNull(AudioOutputSupport.configuration(checkNotNull(AudioOutputSupport.negotiate(0x8000, 100))))
    }
    @Test fun opusCsdIsCompleteAndDoesNotInventContainerPreskip() {
        val config = AudioOutputSupport.configuration(checkNotNull(AudioOutputSupport.negotiate(0x20000000, 100)))!!
        assertEquals("audio/opus", config.mime); assertNull(config.aacProfile)
        assertEquals(3, config.csd.size)
        assertArrayEquals("OpusHead".toByteArray(Charsets.US_ASCII), config.csd[0].copyOfRange(0,8))
        assertEquals(19, config.csd[0].size); assertEquals(1, config.csd[0][9].toInt())
        assertEquals(0, config.csd[0][10].toInt()); assertEquals(0, config.csd[0][11].toInt())
        assertEquals(48000, ByteBuffer.wrap(config.csd[0], 12, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertEquals(0L, ByteBuffer.wrap(config.csd[1]).order(ByteOrder.nativeOrder()).long)
        assertEquals(80_000_000L, ByteBuffer.wrap(config.csd[2]).order(ByteOrder.nativeOrder()).long)
    }
    @Test fun authenticatedFixedHeaderPayloadIsOneRawAccessUnit() {
        val raw = byteArrayOf(1,2,3,4)
        val rtp = ByteArray(12).also { it[0] = 0x80.toByte(); it[1] = 0xE6.toByte() } + raw
        val aac = checkNotNull(AudioOutputSupport.negotiate(0x800000, 102))
        assertArrayEquals(raw, AudioOutputSupport.payload(aac, rtp))
        val opus = checkNotNull(AudioOutputSupport.negotiate(0x40000000, 100))
        assertArrayEquals(raw, AudioOutputSupport.payload(opus, rtp))
        assertNull(AudioOutputSupport.payload(aac, rtp.copyOf(12)))
        assertNull(AudioOutputSupport.payload(aac, rtp.copyOf(11)))
        assertNull(AudioOutputSupport.payload(aac, rtp.copyOf().also { it[0] = 0x90.toByte() }))
        assertNull(AudioOutputSupport.payload(aac, rtp.copyOf().also { it[0] = 0x81.toByte() }))
        val pcm = checkNotNull(AudioOutputSupport.negotiate(0x8000, 100))
        assertArrayEquals(byteArrayOf(2,1,4,3), AudioOutputSupport.payload(pcm, rtp))
        assertNull(AudioOutputSupport.payload(pcm, rtp.copyOf(15)))
        assertArrayEquals(raw, rtp.copyOfRange(12,16)) // no mutation
        assertNull(AudioOutputSupport.payload(aac, rtp.copyOf(4097)))
        assertArrayEquals(byteArrayOf(0), AudioOutputSupport.payload(opus,
            ByteArray(12).also { it[0] = 0x80.toByte() } + byteArrayOf(0))) // legal one-byte Opus TOC/DTX
    }
    @Test fun offerIntersectsExactDeviceFormatsAndNeverOffersMicrophoneOrBufferedStreams() {
        val available = setOf(0x10000000L, 0x40000000L, 0x4000000L, 0x2000000L, 0x800000L)
        val config = AirPlayConfig("Lab","id","00:11:22:33:44:55","1.0",AirPlayDisplayConfig(1280,720),microphone=true)
        val info = AirPlayInfoPlist.build(config, available, MicrophoneCapability.NONE)
        val rows = (info["audioFormats"] as List<*>).map { it as Map<*,*> }
        assertEquals(listOf(100 to "default",100 to "alert",100 to "telephony",100 to "speechRecognition",101 to "default",102 to "media"),
            rows.map { (it["type"] as Number).toInt() to it["audioType"] })
        assertEquals(listOf(0x50000000L,0x50000000L,0x50000000L,0x50000000L,0x50000000L,0x800000L),
            rows.map { (it["audioOutputFormats"] as Number).toLong() })
        rows.forEach { row ->
            assertFalse(row.containsKey("audioInputFormats"))
            val bits = (row["audioOutputFormats"] as Number).toLong()
            for (bit in 0..62) if (bits and (1L shl bit) != 0L) {
                assertTrue(available.contains(1L shl bit))
                assertTrue(MediaPolicy.supportsAudio(1L shl bit, (row["type"] as Number).toInt()))
            }
        }
        val latencyRows = (info["audioLatencies"] as List<*>).map { it as Map<*,*> }
        assertEquals(listOf(100 to null,100 to "default",100 to "media",100 to "telephony",
            100 to "speechRecognition",100 to "alert",101 to null,101 to "default",102 to "default"),
            latencyRows.map { it["type"] to it["audioType"] })
        latencyRows.forEach { assertEquals(0L,it["inputLatencyMicros"]); assertEquals(0L,it["outputLatencyMicros"]) }
        assertEquals(listOf("vocoderInfo","enhancedRequestCarUI"), info["extendedFeatures"])
        assertTrue((AirPlayInfoPlist.build(config, emptySet(), MicrophoneCapability.NONE)["audioFormats"] as List<*>).isEmpty())
    }
    @Test fun mixedWirelessCatalogRestoresOnlyImplemented48kPcmAlongsideCompressedFormats() {
        val pcm = setOf(0x4L,0x8L,0x10L,0x20L,0x40L,0x80L,0x100L,0x200L,
            0x400L,0x800L,0x4000L,0x8000L)
        assertEquals(pcm,AudioOutputSupport.implementedPcmBits)
        val available = pcm + setOf(0x20000000L,0x40000000L,0x4000000L,0x2000000L,0x800000L,
            0x1000L,0x2000L,1L shl 32) // unimplemented PCM widths/unknown formats must not leak
        val config = AirPlayConfig("Lab","id","00:11:22:33:44:55","1.0",AirPlayDisplayConfig(1280,720),microphone=true)
        val info = AirPlayInfoPlist.build(config,available,MicrophoneCapability.NONE)
        val rows = (info["audioFormats"] as List<*>).map { it as Map<*,*> }
        assertEquals(listOf(100 to "compatibility",101 to "compatibility",100 to "default",
            100 to "alert",100 to "media",100 to "telephony",100 to "speechRecognition",
            101 to "default",102 to "media"), rows.map { it["type"] to it["audioType"] })
        assertEquals(listOf(0xc3fcL,0xc3fcL,0x6000c3fcL,0x6000c3fcL,0xc3fcL,
            0x60004154L,0x60004154L,0x6000c3fcL,0x800000L),
            rows.map { (it["audioOutputFormats"] as Number).toLong() })
        rows.forEach { row ->
            assertFalse(row.containsKey("audioInputFormats"))
            val mask = (row["audioOutputFormats"] as Number).toLong()
            assertEquals(0L,mask and (0x1000L or 0x2000L or (1L shl 32)))
        }
        assertEquals(listOf("vocoderInfo","enhancedRequestCarUI"), info["extendedFeatures"])
        val probed = MicrophoneCapability.of(mapOf("default" to setOf(0x4L,0x4000L,0x40000000L),
            "telephony" to setOf(0x4000L,0x40000000L),
            "speechrecognition" to setOf(0x40L,0x20000000L)))
        val withInputs = MicrophoneSupport.addInputOffers(AudioOutputSupport.wirelessRows(available),probed)
        assertEquals(listOf(100 to "default",100 to "telephony",100 to "speechRecognition"),
            withInputs.filter { it.containsKey("audioInputFormats") }.map { it["type"] to it["audioType"] })
        withInputs.forEach { row ->
            val input = (row["audioInputFormats"] as? Number)?.toLong() ?: 0L
            assertEquals(0L,input and 0xffffL) // no PCM input, even if present in probed map
            assertEquals(0L,input and (row["audioOutputFormats"] as Long).inv())
        }
    }
    @Test fun everyImplementedBitAdmissionMatchesTheExactWirelessRowMask() {
        val rows = AudioOutputSupport.wirelessRows(AudioOutputSupport.implementedBits)
        listOf(100,101,102,103).forEach { type ->
            listOf("compatibility","default","alert","media","telephony","speechRecognition","unknown").forEach { kind ->
                AudioOutputSupport.implementedBits.forEach { bit ->
                    val offered = rows.any { row -> row["type"] == type && row["audioType"] == kind &&
                        ((row["audioOutputFormats"] as Long) and bit) != 0L }
                    assertEquals("type=$type kind=$kind bit=$bit",offered,
                        AudioOutputSupport.negotiate(bit,type,kind) != null)
                }
            }
        }
        assertNull(AudioOutputSupport.negotiate(0x8000L,100,"telephony"))
        assertNull(AudioOutputSupport.negotiate(0x400L,100,"default")) // 44.1k PCM not in 48k reference row
        assertNotNull(AudioOutputSupport.negotiate(0x4000L,100,"SPEECHRECOGNITION"))
        val pcmOnly = AudioOutputSupport.wirelessRows(setOf(0x8000L))
        assertEquals(6,pcmOnly.size)
        assertTrue(pcmOnly.all { it["audioOutputFormats"] == 0x8000L })
        assertTrue(AudioOutputSupport.wirelessRows(setOf(0x1000L,1L shl 32)).isEmpty())
    }
    @Test fun eldFrameLengthFollowsExactNegotiatedSpf() {
        val base = checkNotNull(AudioOutputSupport.format(0x4000000,100))
        val short = checkNotNull(AudioOutputSupport.withSamplesPerFrame(base,480L))
        assertArrayEquals(byteArrayOf(0xf8.toByte(),0xf0.toByte(),0x30,0),
            AudioOutputSupport.configuration(short)!!.csd.single())
        assertEquals(base, AudioOutputSupport.withSamplesPerFrame(base,null))
        assertNull(AudioOutputSupport.withSamplesPerFrame(base,1024))
        assertNull(AudioOutputSupport.withSamplesPerFrame(base,480.5))
        val lc = checkNotNull(AudioOutputSupport.negotiate(0x800000,102))
        assertNull(AudioOutputSupport.withSamplesPerFrame(lc,960))
    }
    @Test fun voiceAndMediaPoliciesCannotSelectWrongCodecFamily() {
        assertNull(AudioOutputSupport.negotiate(0x800000,102,"default"))
        assertNull(AudioOutputSupport.negotiate(0x40000000,100,"media"))
        assertNull(AudioOutputSupport.negotiate(0x4000000,100,"speechRecognition"))
        assertNull(AudioOutputSupport.negotiate(0x40000000,100,"compatibility"))
        assertNotNull(AudioOutputSupport.negotiate(0x10000000,100,"alert"))
        assertNotNull(AudioOutputSupport.negotiate(0x40000000,100,"speechRecognition"))
        assertNull(AudioOutputSupport.negotiate(0x2000000,100,"default"))
    }
}
