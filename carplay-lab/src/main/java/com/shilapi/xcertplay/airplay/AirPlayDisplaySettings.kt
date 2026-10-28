// SPDX-License-Identifier: GPL-3.0-only
// From DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d, shared/src/main/java/
// com/shilapi/xcertplay/airplay/; upstream shilapi/xcertplay. See PROVENANCE.md.
package com.shilapi.xcertplay.airplay

enum class AirPlayPhysicalSizeBasis {
    WIDTH,
    HEIGHT,
}

data class AirPlayPhysicalSizeMm(
    val widthMm: Int,
    val heightMm: Int,
)

/** Exact integer encoder caps and continuous physical-width calibration used by Settings. */
object AirPlayDisplaySettings {
    const val MIN_FPS = 1
    /** Product ceiling until higher-rate phone/render evidence exists; not a protocol limit. */
    const val MAX_FPS = 60
    const val FPS_STEP = 1
    const val DEFAULT_FPS = 30

    const val MIN_WIDTH_PHYSICAL_MM = 100
    // Manual calibration may use the existing reported-mm envelope. This is local resource
    // policy, not an Apple maximum or a guarantee of continuous phone-side layout changes.
    const val MAX_WIDTH_PHYSICAL_MM = 2_000
    const val WIDTH_PHYSICAL_MM_STEP = 1
    const val DEFAULT_WIDTH_PHYSICAL_MM = 200
    val DEFAULT_PHYSICAL_SIZE_BASIS = AirPlayPhysicalSizeBasis.WIDTH
    const val MIN_REPORTED_PHYSICAL_MM = 1
    const val MAX_REPORTED_PHYSICAL_MM = 2_000

    fun sanitizeFps(value: Int): Int {
        require(value in MIN_FPS..MAX_FPS) { "Maximum FPS must be an integer from 1 to 60" }
        return value
    }

    fun sanitizeWidthPhysicalMm(value: Int): Int =
        snapToStep(
            value,
            MIN_WIDTH_PHYSICAL_MM,
            MAX_WIDTH_PHYSICAL_MM,
            WIDTH_PHYSICAL_MM_STEP,
        )

    fun sanitizeReportedPhysicalMm(value: Int): Int =
        value.coerceIn(MIN_REPORTED_PHYSICAL_MM, MAX_REPORTED_PHYSICAL_MM)

    fun resolvePhysicalSizeMm(
        currentWidthPixels: Int,
        currentHeightPixels: Int,
        maximumWidthPixels: Int,
        maximumHeightPixels: Int,
        referenceMillimeters: Int,
        basis: AirPlayPhysicalSizeBasis,
    ): AirPlayPhysicalSizeMm {
        require(currentWidthPixels > 0 && currentHeightPixels > 0) {
            "Current display dimensions must be positive"
        }
        require(maximumWidthPixels > 0 && maximumHeightPixels > 0) {
            "Maximum display dimensions must be positive"
        }
        val referencePixels = when (basis) {
            AirPlayPhysicalSizeBasis.WIDTH ->
                currentWidthPixels.toDouble() / maximumWidthPixels
            AirPlayPhysicalSizeBasis.HEIGHT ->
                currentHeightPixels.toDouble() / maximumHeightPixels
        }
        val referenceMm = referenceMillimeters.coerceAtLeast(1) * referencePixels
        val widthMm: Double
        val heightMm: Double
        when (basis) {
            AirPlayPhysicalSizeBasis.WIDTH -> {
                widthMm = referenceMm
                heightMm = widthMm * currentHeightPixels / currentWidthPixels
            }
            AirPlayPhysicalSizeBasis.HEIGHT -> {
                heightMm = referenceMm
                widthMm = heightMm * currentWidthPixels / currentHeightPixels
            }
        }
        return AirPlayPhysicalSizeMm(
            widthMm = sanitizeReportedPhysicalMm(Math.round(widthMm).toInt()),
            heightMm = sanitizeReportedPhysicalMm(Math.round(heightMm).toInt()),
        )
    }

    fun fpsProgress(value: Int): Int =
        (sanitizeFps(value) - MIN_FPS) / FPS_STEP

    fun widthPhysicalMmProgress(value: Int): Int =
        (sanitizeWidthPhysicalMm(value) - MIN_WIDTH_PHYSICAL_MM) / WIDTH_PHYSICAL_MM_STEP

    private fun snapToStep(value: Int, minimum: Int, maximum: Int, step: Int): Int {
        val clamped = value.coerceIn(minimum, maximum)
        return (((clamped - minimum) + step / 2) / step) * step + minimum
    }
}
