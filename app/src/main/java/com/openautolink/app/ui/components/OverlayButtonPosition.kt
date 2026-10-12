package com.openautolink.app.ui.components

import android.content.SharedPreferences

internal data class OverlayButtonPoint(val x: Float, val y: Float)

private fun Float.finiteOrZero() = if (isFinite()) this else 0f

/** The chrome-free projection parent, in root pixels (not video or column bounds). */
internal data class OverlayButtonViewport(val left: Float, val top: Float, val width: Float, val height: Float)

internal data class OverlayButtonGeometry(
    val parentWidth: Float,
    val parentHeight: Float,
    val anchorX: Float,
    val anchorY: Float,
    val buttonWidth: Float,
    val buttonHeight: Float,
) {
    private val travelX get() = (parentWidth - buttonWidth).coerceAtLeast(0f)
    private val travelY get() = (parentHeight - buttonHeight).coerceAtLeast(0f)

    fun clampOffset(offset: OverlayButtonPoint) = OverlayButtonPoint(
        offset.x.finiteOrZero().coerceIn(-anchorX, travelX - anchorX),
        offset.y.finiteOrZero().coerceIn(-anchorY, travelY - anchorY),
    )

    fun fractionFromOffset(offset: OverlayButtonPoint): OverlayButtonPoint {
        val clamped = clampOffset(offset)
        return OverlayButtonPoint(
            if (travelX > 0f) (anchorX + clamped.x) / travelX else 0f,
            if (travelY > 0f) (anchorY + clamped.y) / travelY else 0f,
        )
    }

    fun offsetFromFraction(fraction: OverlayButtonPoint) = clampOffset(OverlayButtonPoint(
        fraction.x.finiteOrZero().coerceIn(0f, 1f) * travelX - anchorX,
        fraction.y.finiteOrZero().coerceIn(0f, 1f) * travelY - anchorY,
    ))
}

/**
 * V2 stores fractions of available top-left travel in the projection parent.
 * Legacy pixel deltas were relative to a moving bottom-end column anchor. Recover
 * them once, on a measured layout, without resetting other preferences or controls.
 */
internal class OverlayButtonPositionStore(private val prefs: SharedPreferences, private val key: String) {
    fun readOffset(geometry: OverlayButtonGeometry, defaultOffset: OverlayButtonPoint): OverlayButtonPoint {
        if (prefs.contains("${key}_parent_x") && prefs.contains("${key}_parent_y")) {
            return geometry.offsetFromFraction(OverlayButtonPoint(
                prefs.getFloat("${key}_parent_x", 0f), prefs.getFloat("${key}_parent_y", 0f),
            ))
        }
        // An untouched default belongs to its current stack slot, not to a
        // fixed parent position. Clamp for safety, but persist only a real drag
        // (or an explicit legacy saved placement) so optional controls can reflow.
        if (!prefs.contains("${key}_x") && !prefs.contains("${key}_y")) {
            return geometry.clampOffset(defaultOffset)
        }
        val legacy = OverlayButtonPoint(
            prefs.getFloat("${key}_x", 0f), prefs.getFloat("${key}_y", 0f),
        )
        val recovered = geometry.clampOffset(legacy)
        saveOffset(geometry, recovered)
        return recovered
    }

    fun saveOffset(geometry: OverlayButtonGeometry, offset: OverlayButtonPoint) {
        val fraction = geometry.fractionFromOffset(offset)
        prefs.edit().putFloat("${key}_parent_x", fraction.x).putFloat("${key}_parent_y", fraction.y).apply()
    }
}
