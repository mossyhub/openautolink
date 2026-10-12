package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Contract from pinned DiPlay MicrophonePacketizer and observed 24k Siri/48k call RTP. */
class MicrophoneContractTest {
    @Test fun sealedOpusDatagramRoundTripsWithClearHeaderAndTrailingLittleEndianNonce() {
        val key = ByteArray(32) { it.toByte() }
        val body = byteArrayOf(0x78, 1, 2, 3)
        val counters = MicrophoneCounters(65535, -480, 0x0102030405060708L)
        val packet = MicrophonePacketizer.sealPacket(key,100,counters,body,480)
        assertEquals(12 + body.size + 16 + 8, packet.size)
        assertEquals(0x80, packet[0].toInt() and 255); assertEquals(100, packet[1].toInt())
        assertEquals(65535, ByteBuffer.wrap(packet,2,2).short.toInt() and 65535)
        assertEquals(-480, ByteBuffer.wrap(packet,4,4).int)
        assertEquals(0x0102030405060708L, ByteBuffer.wrap(packet,packet.size-8,8).order(ByteOrder.LITTLE_ENDIAN).long)
        val nonce = ByteArray(4) + packet.takeLast(8).toByteArray()
        assertArrayEquals(body, AirPlayCrypto.chachaOpen(key,nonce,packet.copyOfRange(12,packet.size-8),packet.copyOfRange(4,12)))
        assertEquals(0,counters.sequence); assertEquals(0,counters.timestamp)
        assertEquals(0x0102030405060709L,counters.nonce)
        val bad = packet.copyOf(); bad[4] = (bad[4].toInt() xor 1).toByte()
        try { AirPlayCrypto.chachaOpen(key,nonce,bad.copyOfRange(12,bad.size-8),bad.copyOfRange(4,12)); fail("modified timestamp authenticated") }
        catch (failure: Exception) {
            assertTrue(failure is javax.crypto.AEADBadTagException || failure is org.bouncycastle.crypto.InvalidCipherTextException)
        }
    }
    @Test fun realLoopbackUdpCarriesSealedRawAccessUnit() {
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { receiver ->
            receiver.soTimeout = 1000
            val key = ByteArray(32) { 7 }
            val body = byteArrayOf(0x78,4,5)
            val wire = MicrophonePacketizer.sealPacket(key,100,MicrophoneCounters(),body,960)
            DatagramSocket().use { it.send(DatagramPacket(wire,wire.size,receiver.localAddress,receiver.localPort)) }
            val packet = DatagramPacket(ByteArray(4096),4096); receiver.receive(packet)
            assertArrayEquals(wire,packet.data.copyOf(packet.length))
            assertArrayEquals(body,AirPlayCrypto.chachaOpen(key,ByteArray(12),wire.copyOfRange(12,wire.size-8),wire.copyOfRange(4,12)))
        }
    }
    @Test fun encodedPacketDurationMustActuallyBeTwentyMilliseconds() {
        assertEquals(960,MicrophoneSupport.opusSamples(byteArrayOf(0x78)))
        assertEquals(480,MicrophoneSupport.opusSamples(byteArrayOf(0x70)))
        assertEquals(1920,MicrophoneSupport.opusSamples(byteArrayOf(0x79)))
        assertNull(MicrophoneSupport.opusSamples(byteArrayOf(0x7b)))
        assertNull(MicrophoneSupport.opusSamples(byteArrayOf()))
    }
    @Test fun defaultCallAndSiriUseGenuineDistinctCaptureSources() {
        assertEquals(MicrophoneSource.MIC,MicrophoneSupport.source("default"))
        assertEquals(MicrophoneSource.VOICE_COMMUNICATION,MicrophoneSupport.source("telephony"))
        assertEquals(MicrophoneSource.VOICE_RECOGNITION,MicrophoneSupport.source("speechRecognition"))
        assertNull(MicrophoneSupport.source("alert"))
        assertNull(MicrophoneSupport.source("media"))
        assertEquals(960,MicrophoneSupport.rtpSamples(0x40000000))
        assertEquals(480,MicrophoneSupport.rtpSamples(0x20000000))
        assertNull(MicrophoneSupport.rtpSamples(0x10000000))
        assertNull(MicrophoneSupport.rtpSamples(0x60000000))
    }
    @Test fun inputOffersRequireConsentExactConfiguredEncoderAndOutputIntersection() {
        val config = AirPlayConfig("Lab","id","00:11:22:33:44:55","950.7.1",AirPlayDisplayConfig(1280,720),microphone=true)
        val output = setOf(0x40000000L,0x20000000L,0x800000L)
        val empty = AirPlayInfoPlist.build(config,output,MicrophoneCapability.NONE)
        assertEquals(listOf("vocoderInfo","enhancedRequestCarUI"), empty["extendedFeatures"])
        val capability = MicrophoneCapability.of(listOf("default","telephony","speechrecognition")
            .associateWith { setOf(0x40000000L,0x20000000L,0x10000000L) })
        val info = AirPlayInfoPlist.build(config,output,capability)
        val rows = (info["audioFormats"] as List<*>).map { it as Map<*,*> }
        val input = rows.filter { it.containsKey("audioInputFormats") }
        assertEquals(listOf("default","telephony","speechRecognition"),input.map { it["audioType"] })
        assertEquals(listOf(0x60000000L,0x60000000L,0x20000000L),input.map { it["audioInputFormats"] })
        assertEquals(listOf("vocoderInfo","enhancedRequestCarUI"), info["extendedFeatures"])
        val denied = AirPlayInfoPlist.build(config.copy(microphone=false),output,capability)
        assertFalse((denied["audioFormats"] as List<*>).any { (it as Map<*,*>).containsKey("audioInputFormats") })
        assertEquals(emptySet<Long>(),MicrophoneSupport.available(false,listOf("encoder")) { _, _ -> true })
        assertEquals(emptySet<Long>(),MicrophoneSupport.available(true,listOf("encoder")) { _, _ -> false })
        assertEquals(emptySet<Long>(),MicrophoneSupport.available(true,emptyList()) { _, _ -> true })
        assertEquals(setOf(0x20000000L,0x40000000L),MicrophoneSupport.available(true,listOf("encoder")) { _, _ -> true })
    }
    @Test fun admissionRejectsInvalidPortCodecClockAndPacketDuration() {
        val format = AudioOutputSupport.negotiate(0x20000000,100,"speechRecognition")!!
        assertTrue(MicrophoneSupport.admits(format,100,65535,null))
        assertTrue(MicrophoneSupport.admits(format,100,1,480L))
        assertFalse(MicrophoneSupport.admits(format,100,65536,null))
        assertFalse(MicrophoneSupport.admits(format,100,0,null))
        assertFalse(MicrophoneSupport.admits(format,101,12,null))
        assertFalse(MicrophoneSupport.admits(format,100,12,960L))
        assertFalse(MicrophoneSupport.admits(format,100,12,480.5))
        assertFalse(MicrophoneSupport.admits(format.copy(codec=AudioCodecKind.AAC_ELD),100,12,null))
    }
}
