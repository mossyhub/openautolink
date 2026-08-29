package com.openautolink.app.video

/** Per-codec cutoff below which a keyframe is treated as a startup placeholder. */
data class SeedIdrThresholds(
    val h264Bytes: Int = DEFAULT_H264_BYTES,
    val h265Bytes: Int = DEFAULT_H265_BYTES,
    val vp9Bytes: Int = DEFAULT_VP9_BYTES,
) {
    companion object {
        const val DEFAULT_H264_BYTES = 10_000
        const val DEFAULT_H265_BYTES = 4_096
        const val DEFAULT_VP9_BYTES = 0
    }
}

object SeedIdrPolicy {
    const val MAX_THRESHOLD_BYTES = 1_000_000

    /** AA protocol MediaCodecType values supported by the current decoder UI. */
    fun codecForAaType(aaCodecType: Int): String? = when (aaCodecType) {
        3 -> "h264"
        5 -> "vp9"
        7 -> "h265"
        else -> null
    }

    fun sanitizeThreshold(bytes: Int): Int = bytes.coerceIn(0, MAX_THRESHOLD_BYTES)

    fun thresholdBytes(codec: String, thresholds: SeedIdrThresholds): Int =
        sanitizeThreshold(
            when (codec.lowercase()) {
                "h265", "hevc" -> thresholds.h265Bytes
                "vp9" -> thresholds.vp9Bytes
                else -> thresholds.h264Bytes
            },
        )

    fun isSeed(codec: String, frameBytes: Int, thresholds: SeedIdrThresholds): Boolean {
        val threshold = thresholdBytes(codec, thresholds)
        return threshold > 0 && frameBytes < threshold
    }
}
