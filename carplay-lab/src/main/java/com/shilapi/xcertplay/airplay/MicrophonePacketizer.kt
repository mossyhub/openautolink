// SPDX-License-Identifier: GPL-3.0-only
// Adapted from DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d, airplay/MicrophonePacketizer.kt.
package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One authenticated session's exact stream. Deliberately no generated key-bearing toString. */
class MicrophoneConfig(
    val audioType: String,
    val host: InetAddress,
    val port: Int,
    key: ByteArray,
    val formatBits: Long,
    val rtpSamplesPerPacket: Int,
    val bitrate: Int,
    val socketBinding: com.openautolink.carplaylab.network.CarPlaySocketBinding? = null,
) {
    internal val key = key.copyOf()
    val sampleRate: Int get() = 48000
    val channels: Int get() = 1
    val frameBytes: Int get() = 1920 // 960 samples, mono, PCM16, 48k capture, 20ms
    init {
        require(port in 1..65535 && key.size == 32)
        require(socketBinding?.allowsPeer(host) != false) { "Microphone destination differs from authenticated control" }
        require(formatBits in setOf(0x20000000L,0x40000000L))
        require(rtpSamplesPerPacket == if (formatBits == 0x20000000L) 480 else 960)
        require(bitrate == if (formatBits == 0x20000000L) 64000 else 96000)
        require(audioType in setOf("default", "telephony", "speechrecognition"))
        require(audioType != "speechrecognition" || formatBits == 0x20000000L)
    }
}
class MicrophoneCounters(var sequence: Int = 0, var timestamp: Int = 0, var nonce: Long = 0)

/** Fixed clear RTP header, timestamp+SSRC AAD, raw Opus ciphertext+tag, trailing LE nonce. */
object MicrophonePacketizer {
    fun inputKey(shared: ByteArray, connectionId: Any?): ByteArray? {
        val id = unsignedPlistDecimal(connectionId) ?: return null
        return AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt$id".toByteArray(Charsets.US_ASCII),
            "DataStream-Input-Encryption-Key".toByteArray(Charsets.US_ASCII), 32)
    }
    fun sealPacket(key: ByteArray, counters: MicrophoneCounters, body: ByteArray, samples: Int): ByteArray =
        sealPacket(key, 100, counters, body, samples)
    fun sealPacket(key: ByteArray, payloadType: Int, counters: MicrophoneCounters,
        body: ByteArray, samples: Int): ByteArray {
        require(key.size == 32 && payloadType == 100 && body.isNotEmpty() && body.size <= 1275)
        require(samples in setOf(480,960))
        check(counters.nonce != -1L) { "Microphone nonce exhausted" }
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
            .put(0x80.toByte()).put(payloadType.toByte()).putShort(counters.sequence.toShort())
            .putInt(counters.timestamp).putInt(0).array()
        val tail = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(counters.nonce).array()
        val sealed = AirPlayCrypto.chachaSeal(key,ByteArray(4)+tail,body,header.copyOfRange(4,12))
        counters.sequence = (counters.sequence+1) and 65535
        counters.timestamp += samples
        counters.nonce++
        return header+sealed+tail
    }
}
