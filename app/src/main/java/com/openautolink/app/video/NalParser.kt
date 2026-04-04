package com.openautolink.app.video

/**
 * NAL unit parser for H.264 and H.265 bitstreams.
 * Identifies NAL types from raw codec data to detect SPS/PPS/VPS and IDR frames.
 */
object NalParser {

    // H.264 NAL types
    const val H264_NAL_SLICE = 1
    const val H264_NAL_IDR = 5
    const val H264_NAL_SEI = 6
    const val H264_NAL_SPS = 7
    const val H264_NAL_PPS = 8

    // H.265 NAL types
    const val H265_NAL_IDR_W_RADL = 19
    const val H265_NAL_IDR_N_LP = 20
    const val H265_NAL_VPS = 32
    const val H265_NAL_SPS = 33
    const val H265_NAL_PPS = 34

    /**
     * Extract the H.264 NAL type from the first byte after a start code.
     * NAL type = byte & 0x1F
     */
    fun h264NalType(nalByte: Byte): Int = nalByte.toInt() and 0x1F

    /**
     * Extract the H.265 NAL type from the first byte after a start code.
     * NAL type = (byte >> 1) & 0x3F
     */
    fun h265NalType(nalByte: Byte): Int = (nalByte.toInt() shr 1) and 0x3F

    /**
     * Find the first NAL unit start code in the data and return the NAL type.
     * Start codes: 0x00 0x00 0x00 0x01 or 0x00 0x00 0x01
     * Returns -1 if no start code found.
     */
    fun findFirstH264NalType(data: ByteArray, offset: Int = 0): Int {
        val pos = findStartCode(data, offset)
        if (pos < 0) return -1
        val nalBytePos = pos + startCodeLength(data, pos)
        if (nalBytePos >= data.size) return -1
        return h264NalType(data[nalBytePos])
    }

    /**
     * Find the first NAL unit start code in H.265 data and return the NAL type.
     * Returns -1 if no start code found.
     */
    fun findFirstH265NalType(data: ByteArray, offset: Int = 0): Int {
        val pos = findStartCode(data, offset)
        if (pos < 0) return -1
        val nalBytePos = pos + startCodeLength(data, pos)
        if (nalBytePos >= data.size) return -1
        return h265NalType(data[nalBytePos])
    }

    /**
     * Check if H.264 data contains codec config (SPS or PPS).
     */
    fun isH264CodecConfig(data: ByteArray): Boolean {
        val nalType = findFirstH264NalType(data)
        return nalType == H264_NAL_SPS || nalType == H264_NAL_PPS
    }

    /**
     * Check if H.264 data is an IDR (keyframe).
     */
    fun isH264Idr(data: ByteArray): Boolean {
        val nalType = findFirstH264NalType(data)
        return nalType == H264_NAL_IDR
    }

    /**
     * Check if H.265 data contains codec config (VPS, SPS, or PPS).
     */
    fun isH265CodecConfig(data: ByteArray): Boolean {
        val nalType = findFirstH265NalType(data)
        return nalType == H265_NAL_VPS || nalType == H265_NAL_SPS || nalType == H265_NAL_PPS
    }

    /**
     * Check if H.265 data is an IDR (keyframe).
     */
    fun isH265Idr(data: ByteArray): Boolean {
        val nalType = findFirstH265NalType(data)
        return nalType == H265_NAL_IDR_W_RADL || nalType == H265_NAL_IDR_N_LP
    }

    /**
     * Collect all NAL types present in the data (H.264).
     * Useful for determining if a buffer contains both SPS and PPS.
     */
    fun collectH264NalTypes(data: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        var offset = 0
        while (offset < data.size) {
            val pos = findStartCode(data, offset)
            if (pos < 0) break
            val nalBytePos = pos + startCodeLength(data, pos)
            if (nalBytePos >= data.size) break
            types.add(h264NalType(data[nalBytePos]))
            offset = nalBytePos + 1
        }
        return types
    }

    /**
     * Collect all NAL types present in the data (H.265).
     */
    fun collectH265NalTypes(data: ByteArray): List<Int> {
        val types = mutableListOf<Int>()
        var offset = 0
        while (offset < data.size) {
            val pos = findStartCode(data, offset)
            if (pos < 0) break
            val nalBytePos = pos + startCodeLength(data, pos)
            if (nalBytePos >= data.size) break
            types.add(h265NalType(data[nalBytePos]))
            offset = nalBytePos + 1
        }
        return types
    }

    /**
     * Check if data contains an IDR NAL unit (H.264 type 5, or H.265 types 19/20).
     * Scans all NAL units in the buffer — handles combined SPS+PPS+IDR frames.
     */
    fun containsIdr(data: ByteArray): Boolean {
        var offset = 0
        while (offset < data.size) {
            val pos = findStartCode(data, offset)
            if (pos < 0) break
            val nalBytePos = pos + startCodeLength(data, pos)
            if (nalBytePos >= data.size) break
            val h264Type = h264NalType(data[nalBytePos])
            if (h264Type == H264_NAL_IDR) return true
            // Also check H.265 IDR types
            val h265Type = h265NalType(data[nalBytePos])
            if (h265Type == H265_NAL_IDR_W_RADL || h265Type == H265_NAL_IDR_N_LP) return true
            offset = nalBytePos + 1
        }
        return false
    }

    /**
     * Find the position of the next start code (0x000001 or 0x00000001) starting from offset.
     * Returns -1 if not found.
     */
    fun findStartCode(data: ByteArray, offset: Int = 0): Int {
        if (data.size < offset + 3) return -1
        var i = offset
        while (i < data.size - 2) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i // 3-byte start code
                if (i < data.size - 3 && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    return i // 4-byte start code
                }
            }
            i++
        }
        return -1
    }

    /**
     * Returns the length of the start code at the given position (3 or 4 bytes).
     */
    fun startCodeLength(data: ByteArray, pos: Int): Int {
        if (pos + 3 < data.size &&
            data[pos] == 0.toByte() && data[pos + 1] == 0.toByte() &&
            data[pos + 2] == 0.toByte() && data[pos + 3] == 1.toByte()
        ) return 4
        return 3
    }

    /**
     * Parse H.264 SPS NAL unit to extract video resolution (width x height).
     * The data may contain multiple NALs (SPS + PPS). Finds the SPS and reads
     * pic_width_in_mbs_minus1 and pic_height_in_map_units_minus1 fields.
     *
     * Returns Pair(width, height) or null if SPS cannot be parsed.
     */
    fun parseSpsResolution(data: ByteArray): Pair<Int, Int>? {
        // Find SPS NAL
        var offset = 0
        while (offset < data.size) {
            val pos = findStartCode(data, offset)
            if (pos < 0) break
            val scLen = startCodeLength(data, pos)
            val nalBytePos = pos + scLen
            if (nalBytePos >= data.size) break
            val nalType = h264NalType(data[nalBytePos])
            if (nalType == H264_NAL_SPS) {
                return parseSpsNal(data, nalBytePos)
            }
            offset = nalBytePos + 1
        }
        return null
    }

    private fun parseSpsNal(data: ByteArray, nalStart: Int): Pair<Int, Int>? {
        // Minimal SPS parsing using a bit reader
        // SPS layout: nal_unit_type(8) profile_idc(8) constraint_flags(8) level_idc(8) seq_parameter_set_id(ue)
        // Then depending on profile: chroma_format_idc(ue) etc.
        // Then: log2_max_frame_num(ue), pic_order_cnt_type(ue), ...
        // Then: max_num_ref_frames(ue), gaps_in_frame_num_value_allowed_flag(1)
        // Finally: pic_width_in_mbs_minus1(ue), pic_height_in_map_units_minus1(ue)
        try {
            val reader = BitReader(data, nalStart + 1) // skip NAL byte
            val profileIdc = reader.readBits(8)
            reader.readBits(8) // constraint_set_flags
            reader.readBits(8) // level_idc
            reader.readUe() // seq_parameter_set_id

            // High profiles have extra fields
            if (profileIdc in intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134)) {
                val chromaFormatIdc = reader.readUe()
                if (chromaFormatIdc == 3) reader.readBits(1) // separate_colour_plane_flag
                reader.readUe() // bit_depth_luma_minus8
                reader.readUe() // bit_depth_chroma_minus8
                reader.readBits(1) // qpprime_y_zero_transform_bypass_flag
                val seqScalingMatrixPresent = reader.readBits(1)
                if (seqScalingMatrixPresent == 1) {
                    val count = if (chromaFormatIdc != 3) 8 else 12
                    for (i in 0 until count) {
                        if (reader.readBits(1) == 1) { // scaling_list_present_flag
                            skipScalingList(reader, if (i < 6) 16 else 64)
                        }
                    }
                }
            }

            reader.readUe() // log2_max_frame_num_minus4
            val picOrderCntType = reader.readUe()
            if (picOrderCntType == 0) {
                reader.readUe() // log2_max_pic_order_cnt_lsb_minus4
            } else if (picOrderCntType == 1) {
                reader.readBits(1) // delta_pic_order_always_zero_flag
                reader.readSe() // offset_for_non_ref_pic
                reader.readSe() // offset_for_top_to_bottom_field
                val numRefFrames = reader.readUe()
                for (i in 0 until numRefFrames) reader.readSe()
            }

            reader.readUe() // max_num_ref_frames
            reader.readBits(1) // gaps_in_frame_num_value_allowed_flag

            val picWidthInMbsMinus1 = reader.readUe()
            val picHeightInMapUnitsMinus1 = reader.readUe()

            val width = (picWidthInMbsMinus1 + 1) * 16
            val height = (picHeightInMapUnitsMinus1 + 1) * 16

            return Pair(width, height)
        } catch (e: Exception) {
            return null
        }
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        for (j in 0 until size) {
            if (nextScale != 0) {
                val deltaScale = reader.readSe()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            lastScale = if (nextScale == 0) lastScale else nextScale
        }
    }

    /** Simple Exp-Golomb / bit reader for SPS parsing. */
    private class BitReader(private val data: ByteArray, private var bytePos: Int) {
        private var bitPos: Int = 0

        fun readBits(n: Int): Int {
            var value = 0
            for (i in 0 until n) {
                if (bytePos >= data.size) throw IndexOutOfBoundsException()
                value = (value shl 1) or ((data[bytePos].toInt() shr (7 - bitPos)) and 1)
                bitPos++
                if (bitPos == 8) {
                    bitPos = 0
                    bytePos++
                }
            }
            return value
        }

        fun readUe(): Int {
            var leadingZeros = 0
            while (readBits(1) == 0) {
                leadingZeros++
                if (leadingZeros > 31) throw IllegalStateException("Invalid exp-golomb")
            }
            if (leadingZeros == 0) return 0
            return (1 shl leadingZeros) - 1 + readBits(leadingZeros)
        }

        fun readSe(): Int {
            val ue = readUe()
            return if (ue % 2 == 0) -(ue / 2) else (ue + 1) / 2
        }
    }
}
