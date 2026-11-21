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
/** null = metadata unavailable, false = known insufficient, true = reported instance allowance.
 * Neither true nor maxSupportedInstances is a physical configure/start or shared-hardware proof. */
fun interface VideoDecoderBudgetProbe {
    fun supports(codec: VideoCodec, mainDecoder: String, secondaryDecoder: String): Boolean?
}

object CarPlayVideoQualification {
    /** A measured alternate canvas, never an OEM display-ID catalog. Keep the output aspect and
     * bound axes/area; do not substitute a main-screen 720p receipt for the legacy 1280x480 fallback.
     * HEVC is an explicit experiment. Preflight its conservative 16-pixel padded buffer; the actual
     * SPS coded dimensions/profile are checked again by the exact named decoder before configure. */
    fun selectCluster(outputWidth: Int, outputHeight: Int, experimentalHevc: Boolean = false,
        probe: VideoDecoderProbe = androidProbe()): CarPlayVideoSelection {
        fun decoder(codec: VideoCodec, w: Int, h: Int) = runCatching {
            probe.decoderFor(VideoDecoderRequest(codec,w,h,30))?.takeIf { it.isNotBlank() }
        }.getOrNull()
        val candidates = linkedSetOf<Pair<Int,Int>>()
        if (outputWidth in 2..16384 && outputHeight in 2..16384) {
            val scale = minOf(1.0,4096.0/outputWidth,4096.0/outputHeight,
                kotlin.math.sqrt(2560.0*1440/(outputWidth.toDouble()*outputHeight)))
            for (s in listOf(scale, minOf(scale,1280.0/outputWidth))) {
                val w=(outputWidth*s/2).toInt()*2; val h=(outputHeight*s/2).toInt()*2
                if (w >= 2 && h >= 2) candidates += w to h
            }
        }
        candidates += 1280 to 480
        for ((w,h) in candidates) {
            val avc=decoder(VideoCodec.H264,(w+15)/16*16,(h+15)/16*16) ?: continue
            val hevc=if (experimentalHevc) decoder(VideoCodec.H265,(w+15)/16*16,(h+15)/16*16) else null
            return CarPlayVideoSelection(w,h,30,avc,hevc,
                usedFallback=w!=outputWidth || h!=outputHeight,
                selectionReason=if(w==outputWidth && h==outputHeight) "cluster_measured_exact" else "cluster_bounded_fallback")
        }
        throw IllegalStateException("No qualified optional cluster AVC decoder")
    }

    /** Read regular decoder instance metadata, including aliases of the same canonical component.
     * Android exposes no reliable global cross-codec hardware-pool/configure guarantee. Unknown
     * metadata therefore cannot enable experimental global HEVC. Runtime failure stays optional. */
    fun androidBudgetProbe(): VideoDecoderBudgetProbe {
        val codecs=runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }
            .getOrDefault(emptyList()).filter { !it.isEncoder }
        return VideoDecoderBudgetProbe { codec, main, secondary -> runCatching {
            val first=codecs.singleOrNull { it.name==main } ?: return@runCatching null
            val second=codecs.singleOrNull { it.name==secondary } ?: return@runCatching null
            val mime=mime(codec)
            val a=first.getCapabilitiesForType(mime).maxSupportedInstances
            val b=second.getCapabilitiesForType(mime).maxSupportedInstances
            val shared=first.name==second.name || (android.os.Build.VERSION.SDK_INT>=29 && first.canonicalName==second.canonicalName)
            if(shared) minOf(a,b)>=2 else a>=1 && b>=1
        }.getOrNull() }
    }
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

    fun supports(caps: MediaCodecInfo.CodecCapabilities, format: MediaFormat, fps: Int): Boolean {
        val video = caps.videoCapabilities ?: return false
        val w = format.getInteger(MediaFormat.KEY_WIDTH); val h = format.getInteger(MediaFormat.KEY_HEIGHT)
        return w % video.widthAlignment == 0 && h % video.heightAlignment == 0 &&
            video.areSizeAndRateSupported(w,h,fps.toDouble()) && caps.isFormatSupported(format)
    }
}

/** Immutable capability receipt, not evidence of configure/start, phone acceptance or rendering. */
data class CarPlayVideoSelection(val width: Int, val height: Int, val fps: Int,
    val avcDecoderName: String, val hevcDecoderName: String? = null, val usedFallback: Boolean = false,
    val selectionReason: String = if (usedFallback) "manual_unqualified_baseline" else "manual_exact",
    val clusterQualification: CarPlayClusterVideoQualification? = null) {
    init { require(width > 0 && height > 0 && fps > 0 && avcDecoderName.isNotBlank())
        require(hevcDecoderName == null || hevcDecoderName.isNotBlank()) }
    val hevcOffered: Boolean get() = hevcDecoderName != null
    fun matches(config: AirPlayConfig): Boolean = width == config.main.widthPixels &&
        height == config.main.heightPixels && fps == config.main.fps && (!config.hevc || hevcOffered) &&
        (clusterQualification?.let { it.matches(config.cluster) } ?: (config.cluster == null))
    /** Global HEVC is an intersection, never a per-screen wish. Bound aggregate pixel-rate policy
     * as well as reported instance metadata; neither establishes physical simultaneous decoding. */
    fun withCluster(display: com.shilapi.xcertplay.airplay.AirPlayDisplayConfig,
        selection: CarPlayVideoSelection): CarPlayVideoSelection =
        withCluster(display,selection,CarPlayVideoQualification.androidBudgetProbe())
    fun withCluster(display: com.shilapi.xcertplay.airplay.AirPlayDisplayConfig,
        selection: CarPlayVideoSelection, budget: VideoDecoderBudgetProbe): CarPlayVideoSelection {
        require(clusterQualification == null) { "Main receipt already bound" }
        val aggregate = width.toLong()*height*fps + selection.width.toLong()*selection.height*selection.fps
        val hevc = hevcDecoderName?.takeIf { main -> selection.hevcDecoderName?.let { alternate ->
            aggregate <= 2560L*1440*60 && runCatching { budget.supports(VideoCodec.H265,main,alternate) == true &&
                budget.supports(VideoCodec.H264,avcDecoderName,selection.avcDecoderName) != false }.getOrDefault(false)
        } == true }
        val alternate = if(hevc == null) selection.copy(hevcDecoderName=null) else selection
        return copy(hevcDecoderName = hevc,
            clusterQualification = CarPlayClusterVideoQualification.qualify(display, alternate))
    }
    fun decoderName(codec: VideoCodec): String? = if (codec == VideoCodec.H264) avcDecoderName else hevcDecoderName
}

/** Exact per-display capability plus immutable encoded geometry, not simultaneous-decoder proof. */
class CarPlayClusterVideoQualification private constructor(
    val display: com.shilapi.xcertplay.airplay.AirPlayDisplayConfig,
    val videoSelection: CarPlayVideoSelection,
) {
    fun matches(candidate: com.shilapi.xcertplay.airplay.AirPlayDisplayConfig?): Boolean = candidate == display

    companion object {
        /** The iPhone's cluster `altScreenURLs`: map only, turn card only, and map with turn card. */
        val CLUSTER_URLS = setOf("maps:/car/instrumentcluster/map",
            "maps:/car/instrumentcluster/instructioncard", "maps:/car/instrumentcluster")

        fun qualify(display: com.shilapi.xcertplay.airplay.AirPlayDisplayConfig,
            selection: CarPlayVideoSelection): CarPlayClusterVideoQualification {
            require(display.widthPixels in 2..4096 && display.heightPixels in 2..4096 &&
                display.widthPixels % 2 == 0 && display.heightPixels % 2 == 0 &&
                display.widthPixels.toLong() * display.heightPixels <= 2560L * 1440 && display.fps == 30)
            require(selection.width == display.widthPixels && selection.height == display.heightPixels &&
                selection.fps == display.fps && selection.clusterQualification == null)
            require(display.primaryInputDevice == 0 && display.features == 0 &&
                display.initialUrl in CLUSTER_URLS &&
                display.safeAreaDrawOutside == false && display.viewAreas == null && display.initialViewArea == 0)
            val view = requireNotNull(display.viewArea)
            val safe = requireNotNull(display.safeArea)
            for (insets in listOf(view, safe)) {
                require(listOf(insets.top, insets.bottom, insets.left, insets.right).all { it >= 0 })
                require(insets.left.toLong() + insets.right < display.widthPixels &&
                    insets.top.toLong() + insets.bottom < display.heightPixels)
            }
            require(safe.top >= view.top && safe.bottom >= view.bottom &&
                safe.left >= view.left && safe.right >= view.right)
            // No mutable list, alternate URL, or inferred physical size enters this receipt.
            require(display.widthPhysicalMm == null && display.heightPhysicalMm == null)
            return CarPlayClusterVideoQualification(display, selection)
        }
    }
}
