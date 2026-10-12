// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat

/** Exact size/rate/profile request. Android capability evidence is not configure/start proof. */
data class VideoDecoderRequest(val codec: VideoCodec, val width: Int, val height: Int, val fps: Int)
fun interface VideoDecoderProbe { fun decoderFor(request: VideoDecoderRequest): String? }

object CarPlayVideoQualification {
    /** Local bounded ranking, never a protocol display catalog. Prefer 1440-class detail even
     * when Android lays the app out below native panel resolution. Exact AVC remains mandatory.
     */
    fun selectAutomatic(viewportWidth: Int, viewportHeight: Int, panelWidth: Int, panelHeight: Int,
        fps: Int, preferHevc: Boolean, probe: VideoDecoderProbe = androidProbe(),
        detailPixels: Long = 2560L * 1440): CarPlayVideoSelection {
        require(fps in 1..60)
        fun decoder(request: VideoDecoderRequest) = runCatching {
            probe.decoderFor(request)?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val receipts = mutableMapOf<VideoDecoderRequest, String?>()
        fun qualified(request: VideoDecoderRequest): String? {
            if (!receipts.containsKey(request)) receipts[request] = decoder(request)
            return receipts[request]
        }
        val candidates = linkedSetOf<Pair<Int, Int>>()
        if (viewportWidth in 2..16384 && viewportHeight in 2..16384) {
            val ratio = viewportWidth.toDouble() / viewportHeight
            // 2560x1440 area at the measured aspect. Bound axes and area as product policy,
            // not an Apple maximum. Portrait and ultrawide retain the viewport aspect.
            for (area in (listOf(detailPixels.toDouble()) + listOf(2560.0 * 1440, 1920.0 * 1080, 1280.0 * 720)
                .filter { it < detailPixels }).distinct()) {
                val w = kotlin.math.sqrt(area * ratio)
                val h = w / ratio
                val bound = minOf(1.0, 4096.0 / w, 4096.0 / h)
                for (alignment in listOf(2, 8, 16)) {
                    val width = (w * bound / alignment).toInt() * alignment
                    val height = (h * bound / alignment).toInt() * alignment
                    if (width >= 2 && height >= 2 && kotlin.math.abs(width.toDouble()/height/ratio - 1) <= .02)
                        candidates += width to height
                }
            }
        }
        for ((width,height) in candidates) {
            val avc = qualified(VideoDecoderRequest(VideoCodec.H264,width,height,fps)) ?: continue
            val hevc = if (preferHevc) decoder(VideoDecoderRequest(VideoCodec.H265,width,height,fps)) else null
            return CarPlayVideoSelection(width,height,fps,avc,hevc,
                selectionReason = if (width.toLong()*height >= 3_500_000) "automatic_1440_class" else "automatic_lower_qualified")
        }
        val baseline = qualified(VideoDecoderRequest(VideoCodec.H264,1280,720,30))
            ?: throw IllegalStateException("No qualified baseline AVC decoder")
        return CarPlayVideoSelection(1280,720,30,baseline,usedFallback=true,
            selectionReason = if (candidates.isEmpty()) "automatic_viewport_unavailable_baseline" else "automatic_unqualified_baseline")
    }

    fun select(width: Int, height: Int, fps: Int, preferHevc: Boolean = false,
        probe: VideoDecoderProbe = androidProbe()): CarPlayVideoSelection {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 && fps in 1..60)
        fun decoder(request: VideoDecoderRequest) = runCatching {
            probe.decoderFor(request)?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val avc = decoder(VideoDecoderRequest(VideoCodec.H264,width,height,fps))
        if (avc != null) {
            val hevc = if (preferHevc) decoder(VideoDecoderRequest(VideoCodec.H265,width,height,fps)) else null
            return CarPlayVideoSelection(width,height,fps,avc,hevc)
        }
        val baseline = decoder(VideoDecoderRequest(VideoCodec.H264,1280,720,30))
            ?: throw IllegalStateException("No qualified baseline AVC decoder")
        return CarPlayVideoSelection(1280,720,30,baseline,usedFallback=true)
    }

    fun mime(codec: VideoCodec): String = if (codec == VideoCodec.H264)
        MediaFormat.MIMETYPE_VIDEO_AVC else MediaFormat.MIMETYPE_VIDEO_HEVC

    fun androidProbe(): VideoDecoderProbe {
        val codecs = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }
            .getOrDefault(emptyList()).filter { !it.isEncoder }
        return VideoDecoderProbe { request -> codecs.firstNotNullOfOrNull { info ->
            runCatching {
                val mime = mime(request.codec)
                if (info.supportedTypes.none { it.equals(mime,true) }) return@runCatching null
                val caps = info.getCapabilitiesForType(mime)
                val format = MediaFormat.createVideoFormat(mime,request.width,request.height).apply {
                    setInteger(MediaFormat.KEY_FRAME_RATE,request.fps)
                    setInteger(MediaFormat.KEY_PROFILE,if(request.codec==VideoCodec.H264)
                        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh else MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
                }
                if (supports(caps,format,request.fps)) info.name else null
            }.getOrNull()
        } }
    }

    /** Build actual incoming format, not the preferred codec. Admit standard Main/Main10 4:2:0 only
     * when the exact decoder reports the profile/level; range-extension profiles remain unsupported. */
    fun mediaFormat(codec: VideoCodec, bytes: ByteArray, selection: CarPlayVideoSelection): MediaFormat {
        val format = MediaFormat.createVideoFormat(mime(codec),selection.width,selection.height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE,selection.fps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,8*1024*1024)
        }
        if (codec == VideoCodec.H264) {
            val (sps,pps) = MediaCodecSupport.avcParameterSets(bytes)
            require(sps.isNotEmpty() && pps.isNotEmpty() && bytes.size >= 4 && bytes[0] == 1.toByte())
            val profile = when(bytes[1].toInt() and 255) {
                66 -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                77 -> MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                88 -> MediaCodecInfo.CodecProfileLevel.AVCProfileExtended
                100 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                110 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10
                122 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422
                244 -> MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444
                else -> throw IllegalArgumentException("Unsupported AVC profile")
            }
            format.setInteger(MediaFormat.KEY_PROFILE,profile)
            val levels = listOf(10,9,11,12,13,20,21,22,30,31,32,40,41,42,50,51,52,60,61,62)
            val level = levels.indexOf(bytes[3].toInt() and 255)
            require(level >= 0) { "Unsupported AVC level" }
            format.setInteger(MediaFormat.KEY_LEVEL,1 shl level)
            format.setByteBuffer("csd-0",java.nio.ByteBuffer.wrap(byteArrayOf(0,0,0,1)+sps))
            format.setByteBuffer("csd-1",java.nio.ByteBuffer.wrap(byteArrayOf(0,0,0,1)+pps))
        } else {
            val config = requireNotNull(MediaCodecSupport.hevcConfiguration(bytes))
            val sps = config.sps.map { requireNotNull(MediaCodecSupport.hevcSpsInfo(it)) }
            require(sps.all { it.profileIdc == config.profileIdc && it.highTier == config.highTier &&
                it.levelIdc == config.levelIdc && it.chromaFormat == config.chromaFormat &&
                it.bitDepthLuma == config.bitDepthLuma && it.bitDepthChroma == config.bitDepthChroma &&
                it.visibleWidth == selection.width && it.visibleHeight == selection.height &&
                it.width == sps.first().width && it.height == sps.first().height }) { "HEVC SPS/offer mismatch" }
            format.setInteger(MediaFormat.KEY_WIDTH,sps.first().width)
            format.setInteger(MediaFormat.KEY_HEIGHT,sps.first().height)
            require(config.chromaFormat == 1 && config.bitDepthLuma == config.bitDepthChroma)
            val profile = when(config.profileIdc) {
                1 -> { require(config.bitDepthLuma == 8); MediaCodecInfo.CodecProfileLevel.HEVCProfileMain }
                2 -> { require(config.bitDepthLuma in listOf(8,10)); MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
                3 -> { require(config.bitDepthLuma == 8); MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill }
                else -> throw IllegalArgumentException("Unsupported HEVC profile")
            }
            val level = listOf(30,60,63,90,93,120,123,150,153,156,180,183,186).indexOf(config.levelIdc)
            require(level >= 0) { "Unsupported HEVC level" }
            format.setInteger(MediaFormat.KEY_PROFILE,profile)
            format.setInteger(MediaFormat.KEY_LEVEL,1 shl (level*2 + if(config.highTier) 1 else 0))
            format.setByteBuffer("csd-0",java.nio.ByteBuffer.wrap(config.csd))
        }
        return format
    }

    internal fun supports(caps: MediaCodecInfo.CodecCapabilities, format: MediaFormat, fps: Int): Boolean {
        val video = caps.videoCapabilities ?: return false
        val w = format.getInteger(MediaFormat.KEY_WIDTH); val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        return w % video.widthAlignment == 0 && h % video.heightAlignment == 0 &&
            video.areSizeAndRateSupported(w,h,fps.toDouble()) && caps.isFormatSupported(format)
    }
}

/** Immutable capability receipt, not evidence of configure/start, phone acceptance or rendering. */
data class CarPlayVideoSelection(val width: Int, val height: Int, val fps: Int,
    val avcDecoderName: String, val hevcDecoderName: String? = null, val usedFallback: Boolean = false,
    val selectionReason: String = if (usedFallback) "manual_unqualified_baseline" else "manual_exact") {
    init { require(width > 0 && height > 0 && fps > 0 && avcDecoderName.isNotBlank())
        require(hevcDecoderName == null || hevcDecoderName.isNotBlank()) }
    val hevcOffered: Boolean get() = hevcDecoderName != null
    fun matches(config: AirPlayConfig): Boolean = width == config.main.widthPixels &&
        height == config.main.heightPixels && fps == config.main.fps && config.cluster == null
    fun decoderName(codec: VideoCodec): String? = if (codec == VideoCodec.H264) avcDecoderName else hevcDecoderName
}
