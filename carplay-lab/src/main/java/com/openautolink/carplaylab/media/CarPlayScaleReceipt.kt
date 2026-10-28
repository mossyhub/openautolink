// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

/** Frozen numeric physical-scale decision, not a physical-panel measurement or phone acceptance. */
data class CarPlayScaleReceipt internal constructor(
    val mode: Mode,
    val reason: Reason,
    val referenceWidthMm: Int,
    val effectiveWidthMm: Int,
    val effectiveHeightMm: Int,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val displayWidthPx: Int,
    val displayHeightPx: Int,
    val fittedWidthPx: Double,
    val fittedHeightPx: Double,
    val logicalDensityDpi: Int?,
    val xdpi: Double?,
    val ydpi: Double?,
    internal val videoWidthPx: Int,
    internal val videoHeightPx: Int,
    internal val input: CarPlayAutoScalePolicy.Input?,
) {
    enum class Mode { MANUAL, AUTOMATIC }
    enum class Reason { MANUAL, HARDWARE_ESTIMATE, MISSING_VIEWPORT, INVALID_GEOMETRY,
        NONFINITE_METRICS, METRIC_RANGE, BASELINE_ALIAS, LOGICAL_ALIAS, AXIS_DISAGREEMENT, PHYSICAL_BOUNDS }

    internal fun matches(width: Int, height: Int, reference: Int, mode: Mode,
        input: CarPlayAutoScalePolicy.Input?) = videoWidthPx == width && videoHeightPx == height &&
        referenceWidthMm == reference && this.mode == mode && this.input == input

    val physicalSize: CarPlayAutoScalePolicy.Size get() = CarPlayAutoScalePolicy.Size(
        effectiveWidthMm, effectiveHeightMm, when (reason) {
            Reason.MANUAL -> CarPlayAutoScalePolicy.Basis.MANUAL
            Reason.HARDWARE_ESTIMATE -> CarPlayAutoScalePolicy.Basis.HARDWARE_ESTIMATE
            else -> CarPlayAutoScalePolicy.Basis.CALIBRATION_NEEDED
        })
}
