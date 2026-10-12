// SPDX-License-Identifier: GPL-3.0-only
// Wire/CSD adaptation from DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d.
// Exact masks/rows corroborated by pinned Showcase and OCBM; see AUDIO-OUTPUT.md.
package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/** Pure negotiated format, payload and decoder configuration contract. No Android/JNI calls. */
object AudioOutputSupport {
    private data class Spec(val codec: AudioCodecKind, val rate: Int, val channels: Int, val clock: Int = rate)
    private val specs = linkedMapOf(
        0x4L to Spec(AudioCodecKind.LPCM,8000,1), 0x8L to Spec(AudioCodecKind.LPCM,8000,2),
        0x10L to Spec(AudioCodecKind.LPCM,16000,1), 0x20L to Spec(AudioCodecKind.LPCM,16000,2),
        0x40L to Spec(AudioCodecKind.LPCM,24000,1), 0x80L to Spec(AudioCodecKind.LPCM,24000,2),
        0x100L to Spec(AudioCodecKind.LPCM,32000,1), 0x200L to Spec(AudioCodecKind.LPCM,32000,2),
        0x400L to Spec(AudioCodecKind.LPCM,44100,1), 0x800L to Spec(AudioCodecKind.LPCM,44100,2),
        0x4000L to Spec(AudioCodecKind.LPCM,48000,1), 0x8000L to Spec(AudioCodecKind.LPCM,48000,2),
        0x400000L to Spec(AudioCodecKind.AAC_LC,44100,2), 0x800000L to Spec(AudioCodecKind.AAC_LC,48000,2),
        0x1000000L to Spec(AudioCodecKind.AAC_ELD,44100,2), 0x2000000L to Spec(AudioCodecKind.AAC_ELD,48000,2),
        0x4000000L to Spec(AudioCodecKind.AAC_ELD,16000,1), 0x8000000L to Spec(AudioCodecKind.AAC_ELD,24000,1),
        0x10000000L to Spec(AudioCodecKind.OPUS,48000,1,16000),
        0x20000000L to Spec(AudioCodecKind.OPUS,48000,1,24000),
        0x40000000L to Spec(AudioCodecKind.OPUS,48000,1,48000),
    )
    val implementedBits: Set<Long> get() = specs.keys.toSet()
    val implementedPcmBits: Set<Long> get() = specs.filterValues { it.codec == AudioCodecKind.LPCM }.keys.toSet()
    fun format(bits: Long, type: Int, audioType: String = "media"): AudioFormat? {
        val spec = specs[bits] ?: return null // one exact bit; never a fallback
        if (type !in setOf(100,101,102)) return null
        if ((type == 102) != (spec.codec == AudioCodecKind.AAC_LC)) return null
        return AudioFormat(spec.codec,spec.rate,spec.channels,type,audioType,spec.clock)
    }
    fun negotiate(bits: Long, type: Int, audioType: String = if (type == 102) "media" else "default"): AudioFormat? {
        val kind = audioType.lowercase(Locale.ROOT)
        val format = format(bits,type,kind) ?: return null
        // PCM uses the same exact type/kind masks as compressed output; no admission-only fallback.
        if (wirelessRows(implementedBits).none { row ->
            row["type"] == type && (row["audioType"] as String).lowercase(Locale.ROOT) == kind &&
                ((row["audioOutputFormats"] as Long) and bits) != 0L
        }) return null
        return format
    }
    fun withSamplesPerFrame(format: AudioFormat, spf: Any?): AudioFormat? {
        if (spf == null || format.codec !in setOf(AudioCodecKind.AAC_LC,AudioCodecKind.AAC_ELD)) return format
        val number = spf as? Number ?: return null
        val samples = number.toInt()
        if (number.toDouble() != samples.toDouble()) return null
        val allowed = if (format.codec == AudioCodecKind.AAC_ELD) setOf(480,512) else setOf(1024)
        return if (samples in allowed) format.copy(samplesPerFrame=samples) else null
    }
    data class Configuration(val mime: String, val aacProfile: Int?, val csd: List<ByteArray>)
    fun configuration(format: AudioFormat): Configuration? = when (format.codec) {
        AudioCodecKind.LPCM -> null
        AudioCodecKind.AAC_LC, AudioCodecKind.AAC_ELD -> {
            require(format.channels in 1..2)
            val index = listOf(96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000).indexOf(format.sampleRate)
            require(index >= 0)
            val eld = format.codec == AudioCodecKind.AAC_ELD
            require(format.samplesPerFrame in if (eld) setOf(480,512) else setOf(1024))
            val fields = if (eld) listOf(31 to 5, 7 to 6, index to 4, format.channels to 4,
                (if (format.samplesPerFrame == 480) 1 else 0) to 1,
                0 to 3, 0 to 1, 0 to 4, 0 to 2) // no resilience/SBR/extensions, epConfig=0
                else listOf(2 to 5, index to 4, format.channels to 4, 0 to 3)
            val bits = fields.flatMap { (value,width) -> (width-1 downTo 0).map { (value ushr it) and 1 } }
            val asc = ByteArray((bits.size+7)/8)
            bits.forEachIndexed { i,b -> asc[i/8] = (asc[i/8].toInt() or (b shl (7-i%8))).toByte() }
            Configuration("audio/mp4a-latm", if (eld) 39 else 2, listOf(asc))
        }
        AudioCodecKind.OPUS -> {
            require(format.sampleRate == 48000 && format.channels == 1)
            val head = ByteBuffer.allocate(19).order(ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".toByteArray(Charsets.US_ASCII)).put(1.toByte()).put(1.toByte())
                .putShort(0.toShort()).putInt(48000).putShort(0.toShort()).put(0.toByte()).array()
            fun nativeLong(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(value).array()
            Configuration("audio/opus",null,listOf(head,nativeLong(0),nativeLong(80_000_000)))
        }
    }
    /** AudioStream authenticates a fixed 12-byte RTP header; no AU-size headers or ADTS wrapper. */
    fun payload(format: AudioFormat, rtp: ByteArray): ByteArray? {
        if (rtp.size <= 12 || rtp.size > 4096 || (rtp[0].toInt() and 0xff) != 0x80) return null
        val bytes = rtp.copyOfRange(12,rtp.size)
        if (format.codec == AudioCodecKind.LPCM) {
            if (bytes.size % (format.channels*2) != 0) return null
            for (i in bytes.indices step 2) { val b = bytes[i]; bytes[i] = bytes[i+1]; bytes[i+1] = b }
        }
        return bytes
    }
    /** Output-only DiPlay 0.2.13 48k entertainment candidate rows, intersected with the
     * exact decoder/AudioTrack inventory. Release AirPlayInfoPlist.audioFormats matches
     * research/DiPlay/shared/src/main/java/com/shilapi/xcertplay/airplay/AirPlayInfoPlist.kt:136-154.
     * This bounded catalog discriminator omits implemented ELD/44.1k AAC candidates;
     * implementation support is not an offer. No reference microphone claims are copied.
     * negotiate() shares these masks, so suppressed candidates are also inadmissible.
     */
    fun wirelessRows(available: Set<Long>): List<Map<String,Any?>> {
        fun mask(candidates: Long) = available.intersect(implementedBits).fold(0L) { result,bit -> result or (bit and candidates) }
        fun row(type: Int,kind: String,bits: Long): Map<String,Any?> = linkedMapOf(
            "type" to type,"audioType" to kind,"audioOutputFormats" to mask(bits))
        val pcm = 0xc3fcL
        val pcmMono = 0x4154L
        val opus = 0x70000000L
        return listOf(
            row(100,"compatibility",pcm),
            row(101,"compatibility",pcm),
            row(100,"default",pcm or opus),
            row(100,"alert",pcm or opus),
            row(100,"media",pcm),
            row(100,"telephony",pcmMono or opus),
            row(100,"speechRecognition",pcmMono or opus),
            row(101,"default",pcm or opus),
            row(102,"media",0x800000L),
        ).filter { (it["audioOutputFormats"] as Long) != 0L }
    }
}
