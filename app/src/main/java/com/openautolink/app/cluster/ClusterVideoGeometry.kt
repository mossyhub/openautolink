package com.openautolink.app.cluster

import com.openautolink.app.secondary.SecondaryOutputId
import com.openautolink.app.secondary.SecondaryRenderTarget
import com.openautolink.app.secondary.SecondaryVideoMapping
import com.openautolink.app.secondary.VideoMapping
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** Insets in the named coordinate space, never a stand-in for an OEM mask. */
data class PixelInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(listOf(left, top, right, bottom).all { it in 0..PixelRect.MAX_EDGE }) }
    fun applyTo(size: PixelSize): PixelRect = PixelRect(left, top, size.width - right, size.height - bottom)
    companion object {
        val ZERO = PixelInsets(0, 0, 0, 0)
        fun around(size: PixelSize, inner: PixelRect): PixelInsets {
            require(size.bounds.contains(inner))
            return PixelInsets(inner.left, inner.top, size.width - inner.right, size.height - inner.bottom)
        }
    }
}

data class VideoPoint(val x: Double, val y: Double) {
    init { require(x.isFinite() && y.isFinite()) }
}

/** Subpixel edge coordinates for an exact aspect-preserving fit. */
data class VideoRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    init {
        require(listOf(left, top, right, bottom).all { it.isFinite() && it >= 0.0 && it <= PixelRect.MAX_EDGE })
        require(right > left && bottom > top)
    }
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    fun inward(): PixelRect = PixelRect(ceil(left).toInt(), ceil(top).toInt(), floor(right).toInt(), floor(bottom).toInt())
    companion object {
        fun of(rect: PixelRect) = VideoRect(rect.left.toDouble(), rect.top.toDouble(), rect.right.toDouble(), rect.bottom.toDouble())
    }
}

/** Uniform scale with exact crop origin and fit offsets; crop is not a second safe inset. */
class ClusterVideoTransform private constructor(val source: PixelRect, val destination: VideoRect, val scale: Double) {
    fun forward(x: Double, y: Double): VideoPoint {
        require(x.isFinite() && y.isFinite())
        return VideoPoint(destination.left + (x - source.left) * scale, destination.top + (y - source.top) * scale)
    }
    fun inverse(x: Double, y: Double): VideoPoint {
        require(x.isFinite() && y.isFinite())
        return VideoPoint(source.left + (x - destination.left) / scale, source.top + (y - destination.top) / scale)
    }
    fun forward(rect: PixelRect): VideoRect {
        require(source.contains(rect))
        val start = forward(rect.left.toDouble(), rect.top.toDouble())
        val end = forward(rect.right.toDouble(), rect.bottom.toDouble())
        return VideoRect(start.x, start.y, end.x, end.y)
    }
    /** Essential safe region must fit in the actual video, not in letterbox bars. */
    fun inverseInward(rect: PixelRect): PixelRect {
        require(rect.left >= destination.left && rect.top >= destination.top &&
            rect.right <= destination.right && rect.bottom <= destination.bottom) { "Safe area outside fitted video" }
        val start = inverse(rect.left.toDouble(), rect.top.toDouble())
        val end = inverse(rect.right.toDouble(), rect.bottom.toDouble())
        return VideoRect(start.x, start.y, end.x, end.y).inward().also { require(source.contains(it)) }
    }
    companion object {
        fun aspectFit(source: PixelRect, output: PixelRect): ClusterVideoTransform {
            val scale = minOf(output.width.toDouble() / source.width, output.height.toDouble() / source.height)
            val width = source.width * scale
            val height = source.height * scale
            val left = output.left + (output.width - width) / 2.0
            val top = output.top + (output.height - height) / 2.0
            return ClusterVideoTransform(source, VideoRect(left, top, left + width, top + height), scale)
        }
    }
}

/** Frozen math receipt. No decoder/phone acceptance or public-insets mask detection is implied. */
class ClusterVideoMapping internal constructor(
    val qualifiedDisplay: QualifiedClusterDisplay,
    codedSize: PixelSize,
    codecCrop: PixelRect,
    framesPerSecond: Int,
) : VideoMapping by SecondaryVideoMapping(
    SecondaryRenderTarget.fromQualifiedCluster(qualifiedDisplay,
        SecondaryOutputId(qualifiedDisplay.profile.id), 0), codedSize, codecCrop, framesPerSecond,
) {
    override val gmReceipt: QualifiedClusterDisplay get() = qualifiedDisplay
}

object ClusterVideoGeometry {
    /** Proposed H.264@30. Alignment describes drawable SIZE, not the top padding (119 is valid). */
    fun androidAuto(display: QualifiedClusterDisplay): ClusterVideoMapping {
        val target = SecondaryRenderTarget.fromQualifiedCluster(display, SecondaryOutputId(display.profile.id), 0)
        val mapping = SecondaryVideoMapping.androidAuto(target)
        return ClusterVideoMapping(display, mapping.codedSize, mapping.codecCrop, mapping.framesPerSecond)
    }

    /** Proposed independent AVC secondary; no inherited center-screen/BYD offsets or HID geometry. */
    fun carPlay(display: QualifiedClusterDisplay): ClusterVideoMapping {
        val coded = PixelSize(1280, 480)
        return ClusterVideoMapping(display, coded, coded.bounds, 30)
    }

    /** Nearest aligned size, bounded by the coded frame; fit remains exact via letterboxing. */
    fun nearestAligned(value: Double, alignment: Int, maximum: Int): Int {
        require(value.isFinite() && value > 0.0 && value <= PixelRect.MAX_EDGE)
        require(alignment in 1..PixelRect.MAX_EDGE && maximum in alignment..PixelRect.MAX_EDGE)
        require(maximum % alignment == 0)
        return ((value / alignment).roundToInt() * alignment).coerceIn(alignment, maximum)
    }
}
