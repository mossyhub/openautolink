package com.openautolink.app.drivecard

import com.openautolink.app.secondary.SecondaryLayoutBox
import kotlin.math.max

/** Card-only normalized physical-canvas bounds. Never changes the phone's Directions area.
 * Conservative right-side editing envelope preserves the left instruments, lower status strip
 * and far-right warning column. This is not a universal OEM occlusion-mask qualification. */
data class DriveCardLayout(val box: SecondaryLayoutBox, val slant: Double = .16) {
    init {
        require(box.left >= .5 && box.top >= .25 && box.right <= .94 && box.bottom <= .86)
        require(box.right-box.left >= .18-1e-9 && box.bottom-box.top >= .18-1e-9)
        require(slant.isFinite() && slant in .0.. .18)
    }
    fun move(dx: Double, dy: Double): DriveCardLayout {
        if (!dx.isFinite() || !dy.isFinite()) return this
        val x=dx.coerceIn(.5-box.left,.94-box.right)
        val y=dy.coerceIn(.25-box.top,.86-box.bottom)
        return copy(box=SecondaryLayoutBox(box.left+x,box.top+y,box.right+x,box.bottom+y))
    }
    fun resize(dx: Double, dy: Double): DriveCardLayout {
        if (!dx.isFinite() || !dy.isFinite()) return this
        return copy(box=SecondaryLayoutBox(box.left,box.top,
            (box.right+dx).coerceIn(max(.68,box.left+.18),.94),
            (box.bottom+dy).coerceIn(max(.43,box.top+.18),.86)))
    }
    companion object {
        val DEFAULT=DriveCardLayout(SecondaryLayoutBox(.67,.51,.94,.85))
    }
}
