// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import com.openautolink.carplaylab.media.CarPlayScaleReceipt.Mode
import com.openautolink.carplaylab.media.CarPlayScaleReceipt.Reason
import kotlin.math.abs
import kotlin.math.roundToInt

/** CarPlay advertises millimeters, never Android densityDpi or AA layout breakpoints. */
object CarPlayAutoScalePolicy {
    /** Pixel coordinates and x/y DPI must come from the SAME owning display and orientation.
     * densityDpi is only a synthetic-metric rejection hint, never a physical measurement.
     */
    data class Input(val viewportWidthPx: Int, val viewportHeightPx: Int,
        val displayWidthPx: Int, val displayHeightPx: Int,
        val xdpi: Double, val ydpi: Double, val densityDpi: Int)
    enum class Basis { MANUAL, HARDWARE_ESTIMATE, CALIBRATION_NEEDED }
    data class Size(val widthMm: Int, val heightMm: Int, val basis: Basis)

    /** Classify the first rejection, retaining the existing acceptance predicate and arithmetic. */
    fun resolveReceipt(videoWidthPx: Int, videoHeightPx: Int, referenceWidthMm: Int,
        mode: Mode, input: Input?): CarPlayScaleReceipt {
        require(videoWidthPx > 0 && videoHeightPx > 0)
        val geometryValid = input != null && input.displayWidthPx in 1..16384 && input.displayHeightPx in 1..16384 &&
            input.viewportWidthPx in 1..input.displayWidthPx && input.viewportHeightPx in 1..input.displayHeightPx
        val fit = if (geometryValid) minOf(input!!.viewportWidthPx.toDouble() / videoWidthPx,
            input.viewportHeightPx.toDouble() / videoHeightPx) else 0.0
        val fittedWidth = videoWidthPx * fit
        val reason = when {
            mode == Mode.MANUAL -> Reason.MANUAL
            input == null -> Reason.MISSING_VIEWPORT
            !geometryValid -> Reason.INVALID_GEOMETRY
            !input.xdpi.isFinite() || !input.ydpi.isFinite() -> Reason.NONFINITE_METRICS
            input.xdpi !in 40.0..1000.0 || input.ydpi !in 40.0..1000.0 -> Reason.METRIC_RANGE
            abs(input.xdpi - 160.0) <= 1.0 && abs(input.ydpi - 160.0) <= 1.0 -> Reason.BASELINE_ALIAS
            abs(input.xdpi - input.densityDpi) <= 1.0 && abs(input.ydpi - input.densityDpi) <= 1.0 -> Reason.LOGICAL_ALIAS
            abs(input.xdpi / input.ydpi - 1.0) > 0.10 -> Reason.AXIS_DISAGREEMENT
            input.displayWidthPx * 25.4 / input.xdpi !in 50.0..2000.0 ||
                input.displayHeightPx * 25.4 / input.ydpi !in 30.0..2000.0 -> Reason.PHYSICAL_BOUNDS
            else -> Reason.HARDWARE_ESTIMATE
        }
        val widthMm = when {
            reason == Reason.MANUAL -> referenceWidthMm.toDouble()
            reason == Reason.HARDWARE_ESTIMATE -> fittedWidth * 25.4 / input!!.xdpi
            // DiPlay WIDTH basis: current fitted viewport / maximum detected display.
            // Codec tiers must not change the millimeters for an unchanged physical viewport.
            geometryValid -> referenceWidthMm * fittedWidth / input!!.displayWidthPx
            else -> referenceWidthMm.toDouble()
        }
        // Preserve the legacy manual wrapper exactly, including its unclamped integer width.
        fun effective(value: Double) = if (mode == Mode.MANUAL) value.roundToInt()
            else value.roundToInt().coerceIn(1, 2000)
        return CarPlayScaleReceipt(mode, reason, referenceWidthMm, effective(widthMm),
            effective(widthMm * videoHeightPx / videoWidthPx),
            input?.viewportWidthPx ?: 0, input?.viewportHeightPx ?: 0,
            input?.displayWidthPx ?: 0, input?.displayHeightPx ?: 0,
            fittedWidth, videoHeightPx * fit, input?.densityDpi, input?.xdpi, input?.ydpi,
            videoWidthPx, videoHeightPx, input)
    }

    /** Compatibility entry point: null historically meant Manual, not an unmeasured Auto request. */
    fun resolve(videoWidthPx: Int, videoHeightPx: Int, referenceWidthMm: Int, input: Input?): Size =
        resolveReceipt(videoWidthPx, videoHeightPx, referenceWidthMm,
            if (input == null) Mode.MANUAL else Mode.AUTOMATIC, input).physicalSize
}
