// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import java.net.InetAddress
import java.util.Locale

enum class MicrophoneSource { MIC, VOICE_COMMUNICATION, VOICE_RECOGNITION }
/** Pure input policy. Decoder inventory must never be used as encoder evidence. */
object MicrophoneSupport {
    fun negotiate(capability: MicrophoneCapability, type: Int, kind: String, bits: Long,
        port: Any?, spf: Any?, host: InetAddress, key: ByteArray): MicrophoneConfig? {
        val audioType = kind.lowercase(Locale.ROOT)
        if (type != 100 || bits !in capability.bits(audioType) || key.size != 32) return null
        val number = port as? Number ?: return null
        val destination = number.toInt()
        if (number.toDouble() != destination.toDouble() || destination !in 1..65535) return null
        val samples = rtpSamples(bits) ?: return null
        if (spf != null && (spf !is Number || spf.toDouble() != samples.toDouble())) return null
        return MicrophoneConfig(audioType, host, destination, key, bits, samples,
            if (bits == 0x20000000L) 64000 else 96000)
    }

    fun addInputOffers(rows: List<Map<String, Any?>>, capability: MicrophoneCapability): List<Map<String, Any?>> =
        rows.map { row ->
            val output = (row["audioOutputFormats"] as? Number)?.toLong() ?: 0L
            val mask = if (row["type"] == 100) capability.bits(row["audioType"] as? String ?: "")
                .fold(0L) { a, b -> a or (b and output) } else 0L
            if (mask == 0L) row else LinkedHashMap(row).apply { this["audioInputFormats"] = mask }
        }
    val implementedBits = setOf(0x20000000L,0x40000000L)
    fun source(kind: String): MicrophoneSource? = when (kind.lowercase(Locale.ROOT)) {
        "default" -> MicrophoneSource.MIC
        "telephony" -> MicrophoneSource.VOICE_COMMUNICATION
        "speechrecognition" -> MicrophoneSource.VOICE_RECOGNITION
        else -> null
    }
    fun rtpSamples(bits: Long): Int? = when (bits) {
        0x20000000L -> 480
        0x40000000L -> 960
        else -> null
    }
    /** The predicate must successfully configure/start/release the actual encoder at that bitrate. */
    fun available(permission: Boolean, encoders: List<String>, configure: (String, Int) -> Boolean): Set<Long> {
        if (!permission) return emptySet()
        return implementedBits.filter { bit ->
            encoders.any { configure(it,if (bit == 0x20000000L) 64000 else 96000) }
        }.toSet()
    }
    /** RFC 6716 section 3.1 TOC; packet duration is checked before assigning a 20ms RTP step. */
    fun opusSamples(packet: ByteArray): Int? {
        if (packet.isEmpty() || packet.size > 1275) return null
        val toc = packet[0].toInt() and 255
        val config = toc ushr 3
        val samples = when {
            config < 12 -> listOf(480,960,1920,2880)[config and 3]
            config < 16 -> 480 shl (config and 1)
            else -> 120 shl (config and 3)
        }
        val frames = when (toc and 3) {
            0 -> 1
            1,2 -> 2
            else -> if (packet.size >= 2) packet[1].toInt() and 63 else return null
        }
        return (samples * frames).takeIf { frames > 0 && it <= 5760 }
    }
    fun bits(format: AudioFormat): Long? = if (format.codec != AudioCodecKind.OPUS ||
        format.sampleRate != 48000 || format.channels != 1) null else when (format.rtpClockRate) {
        24000 -> 0x20000000L
        48000 -> 0x40000000L
        else -> null
    }
    fun admits(format: AudioFormat, type: Int, port: Int, spf: Any?): Boolean {
        val bit = bits(format) ?: return false
        if (type != 100 || port !in 1..65535 || source(format.audioType) == null) return false
        if (format.audioType.lowercase(Locale.ROOT) == "speechrecognition" && bit != 0x20000000L) return false
        val expected = rtpSamples(bit) ?: return false
        return spf == null || (spf is Number && spf.toDouble() == expected.toDouble())
    }
    fun addInputs(rows: List<Map<String,Any?>>, available: Set<Long>): List<Map<String,Any?>> = rows.map { row ->
        val output = (row["audioOutputFormats"] as? Number)?.toLong() ?: 0L
        val kind = row["audioType"] as? String ?: ""
        val mask = if (row["type"] == 100 && source(kind) != null)
            available.intersect(implementedBits).fold(0L) { acc, bit -> acc or (bit and output) } else 0L
        if (mask == 0L) row else LinkedHashMap(row).apply { put("audioInputFormats",mask) }
    }
}
