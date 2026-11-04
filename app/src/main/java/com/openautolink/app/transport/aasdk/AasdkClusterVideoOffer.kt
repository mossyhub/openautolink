package com.openautolink.app.transport.aasdk

import androidx.annotation.Keep
import com.openautolink.app.cluster.ClusterVideoMapping
import com.openautolink.app.cluster.PixelSize

/** Frozen, qualified AA-only second-sink offer. No Android physical-display IDs on the wire. */
@Keep // JNI reads this class name and its public field names, including in minified builds.
class AasdkClusterVideoOffer private constructor(
    val mapping: ClusterVideoMapping,
    @JvmField val density: Int,
) {
    @JvmField val cropLeft = mapping.codecCrop.left
    @JvmField val cropTop = mapping.codecCrop.top
    @JvmField val cropRight = mapping.codecCrop.right
    @JvmField val cropBottom = mapping.codecCrop.bottom
    @JvmField val safeLeft = mapping.phoneSafeInsets.left
    @JvmField val safeTop = mapping.phoneSafeInsets.top
    @JvmField val safeRight = mapping.phoneSafeInsets.right
    @JvmField val safeBottom = mapping.phoneSafeInsets.bottom
    @JvmField val stableLeft = mapping.stableContentInsets.left
    @JvmField val stableTop = mapping.stableContentInsets.top
    @JvmField val stableRight = mapping.stableContentInsets.right
    @JvmField val stableBottom = mapping.stableContentInsets.bottom

    init {
        require(density in 1..1000)
        require(mapping.codedSize == PixelSize(1280, 720) && mapping.framesPerSecond == 30)
        require(cropLeft == 1280 - cropRight && cropTop == 720 - cropBottom)
    }

    companion object {
        const val CHANNEL_ID = 3
        const val PROTOCOL_DISPLAY_ID = 1
        const val CODEC_TYPE = 3 // MEDIA_CODEC_VIDEO_H264_BP
        fun fromMapping(mapping: ClusterVideoMapping, density: Int = 160) = AasdkClusterVideoOffer(mapping, density)
    }
}
