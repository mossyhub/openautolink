package com.openautolink.app.drivecard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.secondary.SecondaryHostGeometry
import com.openautolink.app.secondary.SecondaryLayoutBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/** Existing public drawing API only; constructed renderer states, not vehicle measurements.
 * Fixed 560 x 480 backing gives unit-scale content at physical origin (640, 200).
 * All material samples avoid glyphs, graph, battery and antialiased path edges. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardTopLeftLighting589Test {
    private val geometry = SecondaryHostGeometry(1280, 800, PixelRect(0, 0, 1280, 800))
    private val layout = DriveCardLayout(SecondaryLayoutBox(.5, .25, .9375, .85))
    private val units = Color.rgb(173, 194, 221)
    private val normal = Color.rgb(233, 234, 238)
    private val orange = Color.rgb(255, 167, 40)
    private val ready = DriveCardState(nowKmPerKwh = 4.3, powerKw = 21.0,
        powerBasis = DriveCardPowerBasis.BATTERY, availability = DriveCardAvailability.READY,
        trace = listOf(3.0, 4.3, 3.8), arrival = DriveCardArrival(63, true))

    private data class Word(val value: String, val color: Int, val shader: Shader?)
    private class Recording(bitmap: Bitmap) : Canvas(bitmap) {
        val words = mutableListOf<Word>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            words += Word(text, paint.color, paint.shader)
            super.drawText(text, x, y, paint)
        }
    }
    private fun image() = Bitmap.createBitmap(1280, 800, Bitmap.Config.ARGB_8888)
    private fun pixel(image: Bitmap, x: Int, y: Int) = image.getPixel(640 + x, 200 + y)
    private fun channels(color: Int) = listOf(Color.red(color), Color.green(color), Color.blue(color))
    private fun draw(canvas: Canvas, state: DriveCardState = ready,
        renderer: DriveCardRenderer = DriveCardRenderer()) =
        renderer.draw(canvas, geometry, state, layout, imperial = true)

    @Test fun fixedCharcoalIsIlluminatedFromTopLeftWithoutHueSweep() {
        val image = image()
        try {
            draw(Canvas(image))
            val upperLeft = pixel(image, 150, 8)
            val upperRight = pixel(image, 540, 8)
            val lowerLeft = pixel(image, 150, 470)
            assertTrue("Intended UI light originates top-left", Color.red(upperLeft) > Color.red(upperRight) + 10)
            assertTrue("Light falls away down the panel", Color.red(upperLeft) > Color.red(lowerLeft) + 10)
            assertEquals("Illumination is extinguished before the right edge", Color.BLACK, upperRight)
            assertEquals("Illumination is extinguished before the lower edge", Color.BLACK, lowerLeft)
            for (x in 110..545 step 5) {
                val c = pixel(image, x, 170)
                assertEquals(255, Color.alpha(c))
                assertTrue("Neutral brightness, no colored lighting stops", Color.green(c)-Color.red(c) in 0..2)
                assertTrue("Neutral material includes its pure-black terminal band", Color.blue(c)-Color.red(c) in 0..5)
            }
        } finally { image.recycle() }
    }

    @Test fun backgroundLightingFallsSmoothlyAwayFromTopLeft() {
        val image = image()
        try {
            draw(Canvas(image))
            val samples = (110..545).map { pixel(image, it, 170) }
            assertTrue("Flat material lacks intended illumination", samples.toSet().size > 12)
            for ((a,b) in samples.zipWithNext()) {
                assertTrue("Falloff cannot reverse into a colored glow", Color.red(b) <= Color.red(a)+1)
                assertTrue(channels(a).zip(channels(b)).all { (x,y) -> abs(x-y)<=2 })
            }
        } finally { image.recycle() }
    }

    @Test fun backingIsOpaqueIndependentOfUnderlyingHostColor() {
        val transparent = image()
        val host = image()
        try {
            host.eraseColor(Color.MAGENTA)
            draw(Canvas(transparent)); draw(Canvas(host))
            // Background-only interior rows plus right-hand margin, far from path AA.
            val points = (110..545 step 5).map { it to 170 } +
                (8..470 step 7).map { 548 to it }
            for ((x, y) in points) {
                assertEquals("Opaque backing at $x,$y", 255, Color.alpha(pixel(transparent, x, y)))
                assertEquals("No glass/host bleed at $x,$y", pixel(transparent, x, y), pixel(host, x, y))
            }
        } finally { transparent.recycle(); host.recycle() }
    }

    @Test fun slantedOutsideAndExistingCallerClipRemainTransparent() {
        val full = image()
        val clipped = image()
        try {
            draw(Canvas(full))
            val canvas = Canvas(clipped)
            val caller = Rect(800, 230, 1100, 600)
            canvas.clipRect(caller)
            val saveCount = canvas.saveCount
            draw(canvas)
            assertEquals(saveCount, canvas.saveCount)
            assertEquals(caller, canvas.clipBounds)
            var visible = 0
            for (y in 0 until 800) for (x in 0 until 1280) {
                val c = clipped.getPixel(x, y)
                if (!caller.contains(x, y)) assertEquals("Caller clip at $x,$y", 0, Color.alpha(c))
                else {
                    val original = full.getPixel(x, y)
                    assertEquals("Exact backing coverage inside caller clip", Color.alpha(original), Color.alpha(c))
                    // A tighter native clip can change trace/glyph edge rasterization even
                    // on the unchanged renderer (observed at local 437,42). Ownership here
                    // is exact alpha; semantic paint colors are checked exactly below.
                    if (Color.alpha(c) > 0) visible++
                }
                if (x < 640 || x >= 1280 || y < 200 || y >= 800)
                    assertEquals("Outside owned dock region at $x,$y", 0, Color.alpha(full.getPixel(x, y)))
            }
            assertTrue(visible > 1000)
            // Inside box but outside its unchanged diagonal shoulder, away from antialiasing.
            assertEquals(0, Color.alpha(pixel(full, 5, 20)))
            assertEquals(0, Color.alpha(pixel(full, 20, 200)))
        } finally { full.recycle(); clipped.recycle() }
    }

    @Test fun textRetainsExactSemanticColorsAndNoBackgroundShaderAcrossRepeatedDraws() {
        val renderer = DriveCardRenderer()
        for (state in listOf(ready, DriveCardState(),
            DriveCardState(availability = DriveCardAvailability.CHARGING),
            ready.copy(trace = listOf(4.3)))) {
            val image = image()
            try {
                val canvas = Recording(image)
                draw(canvas, state, renderer)
                assertTrue(canvas.words.isNotEmpty())
                for (word in canvas.words) {
                    val expected = when (word.value) {
                        "NOW", "EFFICIENCY", "mi/kWh", "kW",
                        "Efficiency resumes while driving" -> units
                        "Battery" -> if (state.availability == DriveCardAvailability.CHARGING) units else normal
                        "4.3", "2.7", "21", "—", "Charging" -> Color.WHITE
                        "Use" -> orange
                        "Vehicle data", "unavailable", "≈63% on arrival" -> normal
                        else -> throw AssertionError("Unexpected renderer copy: ${word.value}")
                    }
                    assertEquals("Unchanged semantic text color: ${word.value}", expected, word.color)
                    assertNull("Background shader must be cleared before simple text: ${word.value}", word.shader)
                }
                if (state.availability == DriveCardAvailability.CHARGING) {
                    var white = 0
                    // Actual native Charging glyph raster, not merely the recording paint.
                    for (y in 255 until 307) for (x in 734 until 1170)
                        if (image.getPixel(x, y) == Color.WHITE) white++
                    assertTrue("Simple text remains white in actual native pixels", white > 50)
                }
            } finally { image.recycle() }
        }
    }
}
