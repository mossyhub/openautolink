package com.openautolink.app.transport.aasdk

import androidx.annotation.Keep

/** Immutable wire geometry only. Runtime owns its render mapping separately. */
@Keep
class AasdkSecondaryVideoOffer private constructor(
    @JvmField val channelId: Int,
    @JvmField val protocolDisplayId: Int,
    @JvmField val displayType: Int,
    @JvmField val density: Int,
    @JvmField val cropLeft: Int, @JvmField val cropTop: Int,
    @JvmField val cropRight: Int, @JvmField val cropBottom: Int,
    @JvmField val safeLeft: Int, @JvmField val safeTop: Int,
    @JvmField val safeRight: Int, @JvmField val safeBottom: Int,
    @JvmField val stableLeft: Int, @JvmField val stableTop: Int,
    @JvmField val stableRight: Int, @JvmField val stableBottom: Int,
    @JvmField val realDensity: Int,
    /** Advertised codec frame and wire MediaCodecType ([CODEC_TYPE] or [CODEC_TYPE_H265]). Read by JNI. */
    @JvmField val codedWidth: Int, @JvmField val codedHeight: Int, @JvmField val codecType: Int,
) {
    companion object {
        const val CODEC_TYPE = 3
        const val CODEC_TYPE_H265 = 7
        const val MAX_OUTPUTS = 8
        /** 720p is H.264 BP; larger sizes are H.265 only. Keep in sync with ClusterVideoOffer::valid(). */
        private fun validFormat(width: Int, height: Int, codecType: Int) = when (width to height) {
            1280 to 720 -> codecType == CODEC_TYPE
            1920 to 1080, 2560 to 1440, 3840 to 2160 -> codecType == CODEC_TYPE_H265
            else -> false
        }
        fun create(channelId: Int, protocolDisplayId: Int, displayType: Int, density: Int = 160,
            cropLeft: Int = 0, cropTop: Int = 0, cropRight: Int = 1280, cropBottom: Int = 720,
            safeLeft: Int = 0, safeTop: Int = 0, safeRight: Int = 0, safeBottom: Int = 0,
            stableLeft: Int = 0, stableTop: Int = 0, stableRight: Int = 0, stableBottom: Int = 0,
            realDensity: Int = density,
            codedWidth: Int = 1280, codedHeight: Int = 720, codecType: Int = CODEC_TYPE,
        ): AasdkSecondaryVideoOffer {
            require((channelId == 3 && protocolDisplayId == 1 && displayType in 1..2) ||
                (channelId in 19..254 && protocolDisplayId in 2..254 && displayType in 1..2))
            require(density in 1..1000)
            require(realDensity in 1..1000)
            require(validFormat(codedWidth, codedHeight, codecType))
            // GM's hosted cluster channel (3) keeps its qualified 720p H.264 contract.
            require(channelId != 3 || codecType == CODEC_TYPE)
            require(cropLeft in 0 until codedWidth/2 && cropTop in 0 until codedHeight/2 &&
                cropRight == codedWidth-cropLeft && cropBottom == codedHeight-cropTop)
            val width = cropRight-cropLeft
            val height = cropBottom-cropTop
            fun validInsets(l: Int,t: Int,r: Int,b: Int) =
                l >= 0 && t >= 0 && r >= 0 && b >= 0 && l < width && r < width-l && t < height && b < height-t
            require(validInsets(safeLeft,safeTop,safeRight,safeBottom))
            require(validInsets(stableLeft,stableTop,stableRight,stableBottom))
            return AasdkSecondaryVideoOffer(channelId,protocolDisplayId,displayType,density,
                cropLeft,cropTop,cropRight,cropBottom,safeLeft,safeTop,safeRight,safeBottom,
                stableLeft,stableTop,stableRight,stableBottom,realDensity,codedWidth,codedHeight,codecType)
        }
        fun fromLegacy(offer: AasdkClusterVideoOffer) = create(3,1,1,offer.density,
            offer.cropLeft,offer.cropTop,offer.cropRight,offer.cropBottom,
            offer.safeLeft,offer.safeTop,offer.safeRight,offer.safeBottom,
            offer.stableLeft,offer.stableTop,offer.stableRight,offer.stableBottom)
        /** Invalid complete sets never fall back to a partial/legacy advertisement. */
        internal fun validated(offers: List<AasdkSecondaryVideoOffer>): List<AasdkSecondaryVideoOffer> =
            if (offers.size <= MAX_OUTPUTS && offers.map { it.channelId }.distinct().size == offers.size &&
                offers.map { it.protocolDisplayId }.distinct().size == offers.size) offers.toList() else emptyList()
    }
}
