// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.VideoCodec
import java.io.ByteArrayOutputStream

/**
 * Pure byte helpers that convert the CarPlay screen/audio payloads into the
 * records Android MediaCodec and AudioTrack expect. Kept free of Android types
 * so they stay testable on the JVM.
 */
object MediaCodecSupport {
    private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    /** Splits an AVCDecoderConfigurationRecord into raw first SPS and first PPS. */
    fun avcParameterSets(codecData: ByteArray): Pair<ByteArray, ByteArray> {
        if (codecData.size !in 7..65536 || codecData[0] != 1.toByte()) return emptySet()
        var cursor = 6
        fun read(count: Int, type: Int): List<ByteArray>? {
            if (count == 0) return null
            val sets = mutableListOf<ByteArray>()
            repeat(count) {
                if (cursor + 2 > codecData.size) return null
                val length = readU16Be(codecData,cursor); cursor += 2
                if (length < 1 || length > codecData.size-cursor ||
                    codecData[cursor].toInt() and 128 != 0 || codecData[cursor].toInt() and 31 != type) return null
                sets.add(codecData.copyOfRange(cursor,cursor+length)); cursor += length
            }
            return sets
        }
        val sps = read(codecData[5].toInt() and 31,7) ?: return emptySet()
        if (cursor >= codecData.size) return emptySet()
        val pps = read(codecData[cursor++].toInt() and 255,8) ?: return emptySet()
        // High-profile avcC may append chroma/depth fields and SPS extensions.
        if (cursor != codecData.size) {
            if (codecData[1].toInt() and 255 !in listOf(100,110,122,144,244) || codecData.size-cursor < 4) return emptySet()
            cursor += 3
            val count = codecData[cursor++].toInt() and 255
            if (count > 0 && read(count,13) == null) return emptySet()
        }
        if (cursor != codecData.size) return emptySet()
        return sps.first() to pps.first()
    }

    /**
     * Converts an HEVCDecoderConfigurationRecord (hvcC) into Annex B VPS/SPS/PPS CSD.
     *
     * Android video decoders expect the initialization data as NAL units with start codes,
     * not as the raw ISO-BMFF hvcC record.
     */
    data class HevcConfiguration(val profileIdc: Int, val highTier: Boolean, val levelIdc: Int,
        val chromaFormat: Int, val bitDepthLuma: Int, val bitDepthChroma: Int,
        val nalLengthSize: Int, val csd: ByteArray, val sps: List<ByteArray>)

    /** ISO/IEC 14496-15 hvcC, bounded by the record size; never accept a partial chain. */
    fun hevcConfiguration(codecData: ByteArray): HevcConfiguration? {
        if (codecData.size !in HEVC_FIXED_RECORD_SIZE..65536 || codecData[0].toInt() != 1) return null
        var cursor = HEVC_ARRAY_COUNT_OFFSET
        val count = codecData[cursor++].toInt() and 255
        val sets = linkedMapOf<Int, MutableList<ByteArray>>()
        repeat(count) {
            if (cursor + 3 > codecData.size) return null
            val type = codecData[cursor++].toInt() and 63
            val units = readU16Be(codecData, cursor); cursor += 2
            if (units == 0 || units > (codecData.size - cursor) / 4) return null
            repeat(units) {
                if (cursor + 2 > codecData.size) return null
                val length = readU16Be(codecData, cursor); cursor += 2
                if (length < 2 || length > codecData.size - cursor) return null
                val first = codecData[cursor].toInt() and 255
                val second = codecData[cursor + 1].toInt() and 255
                if (first and 128 != 0 || (first shr 1) and 63 != type || second and 7 == 0) return null
                if (type in 32..34) sets.getOrPut(type) { mutableListOf() }
                    .add(codecData.copyOfRange(cursor, cursor + length))
                cursor += length
            }
        }
        if (cursor != codecData.size || (32..34).any { sets[it].isNullOrEmpty() }) return null
        val output = ByteArrayOutputStream()
        for (type in 32..34) for (nal in sets.getValue(type)) { output.write(START_CODE); output.write(nal) }
        return HevcConfiguration(codecData[1].toInt() and 31, codecData[1].toInt() and 32 != 0,
            codecData[12].toInt() and 255, codecData[16].toInt() and 3,
            8 + (codecData[17].toInt() and 7), 8 + (codecData[18].toInt() and 7),
            1 + (codecData[21].toInt() and 3), output.toByteArray(), sets.getValue(33).toList())
    }

    data class HevcSpsInfo(val width: Int, val height: Int, val visibleWidth: Int, val visibleHeight: Int,
        val profileIdc: Int, val highTier: Boolean, val levelIdc: Int,
        val chromaFormat: Int, val bitDepthLuma: Int, val bitDepthChroma: Int)

    /** Bounded H.265 SPS prefix through conformance window and bit depths (ITU-T H.265 7.3.2.2).
     * Decode RBSP rather than reading encoded positions; remove only valid emulation prevention. */
    fun hevcSpsInfo(nal: ByteArray): HevcSpsInfo? = runCatching {
        require(nal.size in 4..65536 && (nal[0].toInt() shr 1) and 63 == 33)
        val rbsp = ByteArrayOutputStream()
        var zeros = 0
        var i = 2
        while (i < nal.size) {
            val byte = nal[i].toInt() and 255
            if (zeros >= 2 && byte == 3) {
                require(i + 1 < nal.size && nal[i + 1].toInt() and 255 <= 3)
                zeros = 0; i++; continue
            }
            rbsp.write(byte); zeros = if (byte == 0) zeros + 1 else 0; i++
        }
        val bits = SpsBits(rbsp.toByteArray())
        bits.skip(4)
        val subLayers = bits.read(3); require(subLayers <= 6)
        bits.skip(1)
        bits.skip(2); val tier = bits.read(1) != 0; val profile = bits.read(5)
        bits.skip(32+48); val level = bits.read(8)
        val subProfile = BooleanArray(subLayers)
        val subLevel = BooleanArray(subLayers)
        repeat(subLayers) { subProfile[it] = bits.read(1) != 0; subLevel[it] = bits.read(1) != 0 }
        if (subLayers > 0) bits.skip((8-subLayers)*2)
        repeat(subLayers) { if (subProfile[it]) bits.skip(88); if (subLevel[it]) bits.skip(8) }
        require(bits.ue() <= 15)
        val chroma = bits.ue(); require(chroma in 0..3)
        val separate = chroma == 3 && bits.read(1) != 0
        val width = bits.ue(); val height = bits.ue(); require(width > 0 && height > 0)
        var left=0; var right=0; var top=0; var bottom=0
        if (bits.read(1) != 0) { left=bits.ue(); right=bits.ue(); top=bits.ue(); bottom=bits.ue() }
        val luma = bits.ue()+8; val chromaDepth = bits.ue()+8
        require(luma in 8..16 && chromaDepth in 8..16)
        val subWidth = if (!separate && chroma in 1..2) 2 else 1
        val subHeight = if (!separate && chroma == 1) 2 else 1
        val visibleWidth = width.toLong()-(left.toLong()+right)*subWidth
        val visibleHeight = height.toLong()-(top.toLong()+bottom)*subHeight
        require(visibleWidth in 1..width.toLong() && visibleHeight in 1..height.toLong())
        HevcSpsInfo(width,height,visibleWidth.toInt(),visibleHeight.toInt(),profile,tier,level,chroma,luma,chromaDepth)
    }.getOrNull()

    private class SpsBits(private val bytes: ByteArray) {
        private var cursor = 0
        fun skip(count: Int) { require(count >= 0 && count <= bytes.size*8-cursor); cursor += count }
        fun read(count: Int): Int {
            require(count in 0..30 && count <= bytes.size*8-cursor)
            var value = 0
            repeat(count) { value = (value shl 1) or ((bytes[cursor/8].toInt() ushr (7-cursor%8)) and 1); cursor++ }
            return value
        }
        fun ue(): Int {
            var zeros = 0
            while (read(1) == 0) { zeros++; require(zeros <= 30) }
            val value = (1L shl zeros)-1+read(zeros)
            require(value <= Int.MAX_VALUE)
            return value.toInt()
        }
    }

    fun hevcCodecSpecificData(codecData: ByteArray): ByteArray = hevcConfiguration(codecData)?.csd ?: ByteArray(0)

    /** Bounded length-prefix conversion. Sink accepts already-converted Annex B; transport does not. */
    fun toAnnexB(lengthPrefixed: ByteArray, lengthSize: Int = 4, allowAnnexB: Boolean = true): ByteArray {
        if (lengthSize !in listOf(1,2,4) || lengthPrefixed.isEmpty() || lengthPrefixed.size > 8*1024*1024) return ByteArray(0)
        if (allowAnnexB && lengthPrefixed.size >= 4 && lengthPrefixed[0] == 0.toByte() &&
            lengthPrefixed[1] == 0.toByte() && lengthPrefixed[2] == 0.toByte() && lengthPrefixed[3] == 1.toByte()) return lengthPrefixed
        fun lengthAt(offset: Int): Int {
            var value = 0L
            repeat(lengthSize) { value = (value shl 8) or (lengthPrefixed[offset+it].toLong() and 255) }
            return if (value in 1..Int.MAX_VALUE.toLong()) value.toInt() else -1
        }
        var cursor = 0
        var outputSize = 0L
        while (cursor < lengthPrefixed.size) {
            if (lengthPrefixed.size-cursor < lengthSize) return ByteArray(0)
            val length = lengthAt(cursor); cursor += lengthSize
            if (length <= 0 || length > lengthPrefixed.size-cursor) return ByteArray(0)
            outputSize += 4L+length
            if (outputSize > 8*1024*1024) return ByteArray(0)
            cursor += length
        }
        val output = ByteArray(outputSize.toInt())
        cursor = 0; var position = 0
        while (cursor < lengthPrefixed.size) {
            val length = lengthAt(cursor); cursor += lengthSize
            START_CODE.copyInto(output,position); position += 4
            lengthPrefixed.copyInto(output,position,cursor,cursor+length)
            position += length; cursor += length
        }
        return output
    }

    /** IDR (AVC) or IRAP (HEVC) starts an independently decodable reference chain. */
    fun isRandomAccess(annexB: ByteArray, codec: VideoCodec): Boolean {
        var cursor = 0
        while (cursor + 3 < annexB.size) {
            val prefix = when {
                annexB[cursor] != 0.toByte() || annexB[cursor + 1] != 0.toByte() -> 0
                annexB[cursor + 2] == 1.toByte() -> 3
                annexB[cursor + 2] == 0.toByte() && annexB[cursor + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (prefix > 0 && cursor + prefix < annexB.size) {
                val header = annexB[cursor + prefix].toInt() and 255
                if (codec == VideoCodec.H264 && header and 31 == 5) return true
                if (codec == VideoCodec.H265 && header and 128 == 0 && cursor + prefix + 1 < annexB.size &&
                    annexB[cursor + prefix + 1].toInt() and 7 != 0 && (header shr 1) and 63 in 16..21) return true
                cursor += prefix
            } else cursor++
        }
        return false
    }

    /** Wraps one raw AAC-LC access unit in an MPEG-4 ADTS frame. */
    fun adtsFrame(accessUnit: ByteArray, sampleRate: Int, channels: Int): ByteArray {
        val frequencyIndex = aacFrequencyIndex(sampleRate)
        val channelConfig = channels.coerceIn(1, 7)
        val frameLength = accessUnit.size + 7
        val header = ByteArray(7)
        header[0] = 0xff.toByte()
        header[1] = 0xf1.toByte()
        header[2] = ((1 shl 6) or (frequencyIndex shl 2) or (channelConfig ushr 2)).toByte()
        header[3] = (((channelConfig and 0x3) shl 6) or (frameLength ushr 11)).toByte()
        header[4] = ((frameLength ushr 3) and 0xff).toByte()
        header[5] = (((frameLength and 0x7) shl 5) or 0x1f).toByte()
        header[6] = 0xfc.toByte()
        return header + accessUnit
    }

    /** Extracts one RFC 3640 AAC access unit from an RTP payload. */
    fun aacAccessUnit(rtpPayload: ByteArray): ByteArray {
        if (rtpPayload.size < 4) return ByteArray(0)
        val headerBits = readU16Be(rtpPayload, 0)
        if (headerBits < 16 || headerBits % 16 != 0) return ByteArray(0)
        val headerBytes = headerBits / 8
        if (2 + headerBytes > rtpPayload.size) return ByteArray(0)
        val auSize = (readU16Be(rtpPayload, 2) shr 3) and 0x1fff
        val start = 2 + headerBytes
        val end = minOf(start + auSize, rtpPayload.size)
        return if (end <= start) ByteArray(0) else rtpPayload.copyOfRange(start, end)
    }

    /** MPEG-4 sampling frequency index used by both ADTS and AudioSpecificConfig. */
    fun aacFrequencyIndex(sampleRate: Int): Int = when (sampleRate) {
        96_000 -> 0
        88_200 -> 1
        64_000 -> 2
        48_000 -> 3
        44_100 -> 4
        32_000 -> 5
        24_000 -> 6
        22_050 -> 7
        16_000 -> 8
        12_000 -> 9
        11_025 -> 10
        8_000 -> 11
        7_350 -> 12
        else -> 3
    }

    private fun emptySet(): Pair<ByteArray, ByteArray> = ByteArray(0) to ByteArray(0)

    private fun readParameterSets(source: ByteArray, offset: Int, count: Int): List<ByteArray> {
        val sets = ArrayList<ByteArray>(count)
        var cursor = offset
        var index = 0
        while (index < count && cursor + 2 <= source.size) {
            index++
            val length = readU16Be(source, cursor)
            cursor += 2
            if (cursor + length > source.size) break
            sets.add(source.copyOfRange(cursor, cursor + length))
            cursor += length
        }
        return sets
    }

    private fun readU16Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private const val HEVC_FIXED_RECORD_SIZE = 23
    private const val HEVC_ARRAY_COUNT_OFFSET = 22
}
