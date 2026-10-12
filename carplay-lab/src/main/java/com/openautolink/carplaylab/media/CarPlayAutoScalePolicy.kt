// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

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

    fun resolve(videoWidthPx: Int, videoHeightPx: Int, referenceWidthMm: Int, input: Input?): Size {
        require(videoWidthPx > 0 && videoHeightPx > 0)
        if (input == null) return Size(referenceWidthMm,
            (referenceWidthMm.toDouble() * videoHeightPx / videoWidthPx).roundToInt(), Basis.MANUAL)
        val geometryValid = input.displayWidthPx in 1..16384 && input.displayHeightPx in 1..16384 &&
            input.viewportWidthPx in 1..input.displayWidthPx && input.viewportHeightPx in 1..input.displayHeightPx
        val fittedWidth = if (geometryValid) videoWidthPx * minOf(
            input.viewportWidthPx.toDouble() / videoWidthPx,
            input.viewportHeightPx.toDouble() / videoHeightPx) else 0.0
        // Android/vendor values can be absent, defaulted, or logical-density aliases.
        // Bounds are conservative metric sanity checks, NOT CarPlay layout rules.
        val metricsValid = geometryValid && input.xdpi.isFinite() && input.ydpi.isFinite() &&
            input.xdpi in 40.0..1000.0 && input.ydpi in 40.0..1000.0 &&
            kotlin.math.abs(input.xdpi / input.ydpi - 1.0) <= 0.10 &&
            // Conservatively reject the common baseline-density pair even after logical overrides.
            !(kotlin.math.abs(input.xdpi - 160.0) <= 1.0 && kotlin.math.abs(input.ydpi - 160.0) <= 1.0) &&
            !(kotlin.math.abs(input.xdpi - input.densityDpi) <= 1.0 &&
                kotlin.math.abs(input.ydpi - input.densityDpi) <= 1.0) &&
            input.displayWidthPx * 25.4 / input.xdpi in 50.0..2000.0 &&
            input.displayHeightPx * 25.4 / input.ydpi in 30.0..2000.0
        val widthMm = if (metricsValid) fittedWidth * 25.4 / input.xdpi else {
            // DiPlay AirPlayDisplaySettings.resolvePhysicalSizeMm WIDTH basis:
            // current ACTIVITY/viewport pixels / maximum DETECTED display pixels.
            // Codec pixels are not that reference; changing 720p→1440p must not double mm.
            if (geometryValid) referenceWidthMm * fittedWidth / input.displayWidthPx else referenceWidthMm.toDouble()
        }
        return Size(widthMm.roundToInt().coerceIn(1, 2000),
            (widthMm * videoHeightPx / videoWidthPx).roundToInt().coerceIn(1, 2000),
            if (metricsValid) Basis.HARDWARE_ESTIMATE else Basis.CALIBRATION_NEEDED)
    }
}
