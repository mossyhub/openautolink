package com.openautolink.app.secondary

import com.openautolink.app.cluster.*

/** Geometry-only contract consumed by each independent decoder/EGL owner. */
interface VideoMapping {
    val target: SecondaryRenderTarget
    val gmReceipt: QualifiedClusterDisplay? get() = null
    val codedSize: PixelSize
    val codecCrop: PixelRect
    val framesPerSecond: Int
    val drawableSize: PixelSize
    val codecMargins: PixelInsets
    val encodedToHost: ClusterVideoTransform
    val drawableToHost: ClusterVideoTransform
    val destination: VideoRect
    val destinationPixels: PixelRect
    val safeInDrawable: PixelRect
    val phoneSafeInsets: PixelInsets
    val stableContentInsets: PixelInsets
}

/** Local coordinates of the actual owned Surface; no physical-panel/profile transform. */
internal class RetainedWindowMapping(mapping: VideoMapping) : VideoMapping by mapping

/** Frozen aspect/crop receipt; construction fails rather than moving essential content into bars. */
class SecondaryVideoMapping(
    override val target: SecondaryRenderTarget,
    override val codedSize: PixelSize,
    override val codecCrop: PixelRect,
    override val framesPerSecond: Int,
) : VideoMapping {
    init {
        require(codedSize.bounds.contains(codecCrop))
        require(framesPerSecond in 1..30)
    }
    override val drawableSize = PixelSize(codecCrop.width, codecCrop.height)
    override val codecMargins = PixelInsets.around(codedSize, codecCrop)
    override val encodedToHost = ClusterVideoTransform.aspectFit(codecCrop, target.output)
    override val drawableToHost = ClusterVideoTransform.aspectFit(drawableSize.bounds, target.output)
    override val destination: VideoRect get() = drawableToHost.destination
    override val destinationPixels = destination.inward()
    override val safeInDrawable = drawableToHost.inverseInward(target.safe)
    override val phoneSafeInsets = PixelInsets.around(drawableSize, safeInDrawable)
    override val stableContentInsets: PixelInsets get() = phoneSafeInsets
    init { require(target.output.contains(destinationPixels)) }

    companion object {
        private val CARPLAY_CODED_SIZE = PixelSize(1280, 480)

        /** Exact current alternate fit, rounded inward so essential pixels never occupy bars. */
        internal fun carPlayDestination(output: PixelRect): PixelRect =
            ClusterVideoTransform.aspectFit(CARPLAY_CODED_SIZE.bounds, output).destination.inward()

        fun androidAuto(target: SecondaryRenderTarget): SecondaryVideoMapping {
            val coded = PixelSize(1280, 720)
            val output = target.output
            val width: Int
            val height: Int
            if (output.width.toDouble() / output.height >= coded.width.toDouble() / coded.height) {
                width = coded.width
                height = ClusterVideoGeometry.nearestAligned(coded.width.toDouble() * output.height / output.width, 2, coded.height)
            } else {
                height = coded.height
                width = ClusterVideoGeometry.nearestAligned(coded.height.toDouble() * output.width / output.height, 2, coded.width)
            }
            val left = (coded.width - width) / 2
            val top = (coded.height - height) / 2
            return SecondaryVideoMapping(target, coded, PixelRect(left, top, left + width, top + height), 30)
        }

        /** Current qualified alternate AVC format only; caller supplies exact named decoder receipt. */
        fun carPlay(target: SecondaryRenderTarget): SecondaryVideoMapping {
            val coded = CARPLAY_CODED_SIZE
            return SecondaryVideoMapping(target, coded, coded.bounds, 30)
        }
    }
}
