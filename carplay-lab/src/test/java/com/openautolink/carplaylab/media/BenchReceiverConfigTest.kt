package com.openautolink.carplaylab.media

import com.openautolink.carplaylab.EndpointOffer
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class BenchReceiverConfigTest {
    @Test fun infoUsesProvenProtocolVersionNotApplicationVersion() {
        val config = benchReceiverConfig("00:11:22:33:44:55", 41234)
        assertEquals("950.7.1", config.sourceVersion)
        assertEquals("950.7.1", AirPlayInfoPlist.build(config, emptySet())["sourceVersion"])
        assertEquals(41234, config.port)
        assertFalse(config.microphone)
        assertFalse(config.lpcmOnly)
    }
    @Test fun configuredMicrophoneCapabilityEnablesOnlyTheOptionalInputContract() {
        val capability = com.shilapi.xcertplay.media.MicrophoneCapability.of(
            mapOf("default" to setOf(0x40000000L)))
        assertTrue(benchReceiverConfig("00:11:22:33:44:55", 41234, capability).microphone)
        assertFalse(benchReceiverConfig("00:11:22:33:44:55", 41234,
            com.shilapi.xcertplay.media.MicrophoneCapability.NONE).microphone)
    }
    @Test fun bluetoothEndpointMatchesInfoProtocolVersion() {
        val offer = EndpointOffer("Lab", "test-private-only", 36, 2, listOf("192.168.1.2"), 41234, "test", "ab".repeat(32))
        val reply = offer.replyTo(Iap2Messages.buildRaw(0x4300) {})!!
        assertEquals(benchReceiverConfig("00:11:22:33:44:55", 41234).sourceVersion,
            Iap2Messages.reader(reply).string(5))
    }
}
