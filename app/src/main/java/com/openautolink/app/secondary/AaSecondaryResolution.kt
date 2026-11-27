package com.openautolink.app.secondary

import com.openautolink.app.cluster.PixelSize
import com.openautolink.app.transport.aasdk.AasdkSecondaryVideoOffer
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.VideoDecoderProbe
import com.shilapi.xcertplay.media.VideoDecoderRequest

/**
 * Video sizes this sink can advertise for an Android Auto secondary display. Android Auto only
 * accepts these fixed sizes; it cannot be asked for the panel's own. One is advertised per
 * session, because the render mapping is frozen before the phone answers.
 *
 * Sizes above 720p are H.265 only: they need no macroblock padding, and the phone already
 * encodes H.265 for the main display. Entry names are the saved tokens.
 */
enum class AaSecondaryResolution(val codedSize: PixelSize, val codec: VideoCodec) {
    HD(PixelSize(1280, 720), VideoCodec.H264),
    FULL_HD(PixelSize(1920, 1080), VideoCodec.H265),
    QUAD_HD(PixelSize(2560, 1440), VideoCodec.H265),
    ULTRA_HD(PixelSize(3840, 2160), VideoCodec.H265);

    /** Wire MediaCodecType. */
    val codecType: Int get() =
        if (codec == VideoCodec.H264) AasdkSecondaryVideoOffer.CODEC_TYPE else AasdkSecondaryVideoOffer.CODEC_TYPE_H265

    /** The saved density is calibrated at 720p. Android Auto lays out in dp, so a larger frame
     * needs a proportionally larger density or cards and text would shrink instead of sharpen. */
    fun density(dpiAt720p: Int): Int = (dpiAt720p * codedSize.height / 720).coerceAtMost(1000)

    /** How far the picture is stretched to fill [output]; 1.0 is pixel-for-pixel. Only the
     * centred band of the frame with the output's aspect ratio is shown. */
    fun stretchFor(output: PixelSize): Double =
        output.width / minOf(codedSize.width.toDouble(), codedSize.height.toDouble() * output.width / output.height)

    /** A decoder that reports this size at 30 fps, or null. A failing probe counts as none. */
    private fun decoderName(probe: VideoDecoderProbe): String? = runCatching {
        probe.decoderFor(VideoDecoderRequest(codec, codedSize.width, codedSize.height, 30))
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** This size, or the largest one below it with a decoder; 720p H.264 needs no named decoder. */
    fun select(probe: VideoDecoderProbe): AaSecondaryVideoSelection =
        entries.subList(1, ordinal + 1).asReversed().firstNotNullOfOrNull { size ->
            size.decoderName(probe)?.let { AaSecondaryVideoSelection(size, it) }
        } ?: AaSecondaryVideoSelection(HD, null)
}

/** What one output advertises. [hevcDecoderName] is null exactly for 720p H.264. */
data class AaSecondaryVideoSelection(val resolution: AaSecondaryResolution, val hevcDecoderName: String?)
