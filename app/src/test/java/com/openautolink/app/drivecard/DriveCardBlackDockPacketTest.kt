package com.openautolink.app.drivecard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Region
import android.graphics.Shader
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.secondary.SecondaryHostGeometry
import com.openautolink.app.secondary.SecondaryLayoutBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot
import kotlin.math.min

/** Private tests-only packet. Constructed states, not measured vehicle/OEM geometry.
 * Existing renderer/layout/state API only. Native runtime deliberately left to parent.
 * Recordings always delegate to Canvas and snapshot its actual current matrix. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardBlackDockPacketTest {
    private val units = Color.rgb(173, 194, 221)
    private val normal = Color.rgb(233, 234, 238)
    private val blue = Color.rgb(82, 133, 197)
    private val orange = Color.rgb(255, 167, 40)
    private val primary = DriveCardLayout(SecondaryLayoutBox(.5, .25, .9375, .85), .16)
    private val ready = DriveCardState(nowKmPerKwh = 4.3, powerKw = 21.0,
        trace = listOf(3.0, Double.NaN, 4.0), arrival = DriveCardArrival(63, true),
        availability = DriveCardAvailability.READY, powerBasis = DriveCardPowerBasis.BATTERY)

    private data class Word(val value: String, val x: Float, val y: Float,
        val sizeX: Float, val sizeY: Float, val color: Int, val shader: Shader?)
    private data class Circle(val x: Float, val y: Float, val color: Int)
    private data class Shape(val color: Int, val style: Paint.Style, val shader: Shader?)
    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val words = mutableListOf<Word>()
        val circles = mutableListOf<Circle>()
        val shapes = mutableListOf<Shape>()
        @Suppress("DEPRECATION")
        private fun actualMatrix() = Matrix().also { getMatrix(it) }
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            val m = actualMatrix()
            val point = floatArrayOf(x, y).also { m.mapPoints(it) }
            val vectors = floatArrayOf(paint.textSize, 0f, 0f, paint.textSize)
                .also { m.mapVectors(it) }
            words += Word(text, point[0], point[1], hypot(vectors[0], vectors[1]),
                hypot(vectors[2], vectors[3]), paint.color, paint.shader)
            super.drawText(text, x, y, paint)
        }
        override fun drawCircle(cx: Float, cy: Float, radius: Float, paint: Paint) {
            val p = floatArrayOf(cx, cy).also { actualMatrix().mapPoints(it) }
            circles += Circle(p[0], p[1], paint.color)
            super.drawCircle(cx, cy, radius, paint)
        }
        override fun drawPath(path: Path, paint: Paint) {
            shapes += Shape(paint.color, paint.style, paint.shader)
            super.drawPath(path, paint)
        }
    }
    private fun fixtureIdentity() {
        assertEquals("No manifest Application/services", Application::class.java,
            RuntimeEnvironment.getApplication().javaClass)
    }
    private fun geometry(w: Int = 1280, h: Int = 800,
        safe: PixelRect = PixelRect(0, 0, w, h)) = SecondaryHostGeometry(w, h, safe)
    private fun image(w: Int = 1280, h: Int = 800) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private fun draw(c: Canvas, state: DriveCardState = ready,
        g: SecondaryHostGeometry = geometry(), layout: DriveCardLayout = primary) =
        DriveCardRenderer().draw(c, g, state, layout, imperial = false)
    private fun exactBand(b: Bitmap, r: Rect, expected: Int, label: String) {
        for (y in r.top until r.bottom) for (x in r.left until r.right)
            assertEquals("$label at $x,$y", expected, b.getPixel(x, y))
    }
    private fun word(c: RecordingCanvas, value: String) = c.words.single { it.value == value }
    private fun semanticColors(c: RecordingCanvas, expected: Map<String, Int>) {
        assertEquals("Exact foreground copy inventory", expected.keys, c.words.map { it.value }.toSet())
        assertEquals("No duplicated/invented foreground copy", expected.size, c.words.size)
        for (w in c.words) {
            assertEquals("Semantic foreground paint ${w.value}", expected.getValue(w.value), w.color)
            assertNull("Material shader cannot leak into ${w.value}", w.shader)
        }
    }
    private fun readyColors(label: String = "NOW", figure: String = "4.3") = linkedMapOf(
        label to units, figure to Color.WHITE, "km/kWh" to units, "Battery" to normal,
        "21" to Color.WHITE, "kW" to units, "≈63% on arrival" to normal)

    @Test fun opaqueBlackBandsPrecedeBothContentEdgesAndContinueWithoutHostBleed() {
        fixtureIdentity()
        // Controls run before the material regression so RED cannot conceal fake history.
        val states = listOf(ready, ready.copy(trace = emptyList()),
            ready.copy(trace = listOf(3.0, 4.0)),
            ready.copy(nowKmPerKwh = null, recentKmPerKwh = 4.3),
            DriveCardState(), DriveCardState(availability = DriveCardAvailability.LEARNING),
            DriveCardState(availability = DriveCardAvailability.CHARGING))
        for ((index, state) in states.withIndex()) {
            val b = image()
            try {
                val c = RecordingCanvas(b)
                draw(c, state)
                when (index) {
                    0, 1, 2 -> semanticColors(c, readyColors())
                    3 -> semanticColors(c, readyColors("RECENT"))
                    4 -> semanticColors(c, linkedMapOf("EFFICIENCY" to units, "—" to Color.WHITE,
                        "km/kWh" to units, "Vehicle data" to normal, "unavailable" to normal))
                    5 -> semanticColors(c, linkedMapOf("EFFICIENCY" to units, "—" to Color.WHITE,
                        "km/kWh" to units, "Collecting" to normal, "drive data" to normal))
                    6 -> semanticColors(c, linkedMapOf("Battery" to units, "Charging" to Color.WHITE,
                        "Efficiency resumes while driving" to units))
                }
                val graphStrokes = c.shapes.filter { it.color == blue && it.style == Paint.Style.STROKE }
                if (index in listOf(0, 3)) {
                    assertEquals("Two real isolated samples; never bridge NaN", 2, c.circles.count { it.color == blue })
                    assertEquals(1, c.circles.count { it.color == orange })
                    assertTrue("Gap cannot get a graph stroke", graphStrokes.isEmpty())
                    assertTrue("Gap cannot get a graph area", c.shapes.none {
                        it.style == Paint.Style.FILL && it.shader is android.graphics.LinearGradient })
                    val samples = c.circles.filter { it.color == blue }
                    assertEquals(964f, samples[0].x, .02f)
                    assertEquals(1171f, samples[1].x, .02f)
                } else if (index == 2) {
                    assertEquals("Positive connected-history control", 1, graphStrokes.size)
                    assertEquals(1, c.circles.count { it.color == orange })
                    assertEquals(1, c.shapes.count {
                        it.style == Paint.Style.FILL && it.shader is android.graphics.LinearGradient })
                } else {
                    assertTrue("True empty/charging history gets no fake dots", c.circles.isEmpty())
                    assertTrue("True empty/charging history gets no fake curve", graphStrokes.isEmpty())
                }
            } finally { b.recycle() }
        }
        val clear = image()
        val host = image()
        try {
            host.eraseColor(Color.MAGENTA) // Deliberately loud synthetic host, not a map capture.
            draw(Canvas(clear)); draw(Canvas(host))
            val lit = clear.getPixel(790, 208)
            assertEquals(255, Color.alpha(lit))
            val channels = listOf(Color.red(lit), Color.green(lit), Color.blue(lit))
            assertTrue("Localized top-left neutral illumination", channels.minOrNull()!! > 10)
            assertTrue("No colored light sweep", channels.maxOrNull()!! - channels.minOrNull()!! <= 5)
            assertEquals("Opaque illumination, not host bleed", lit, host.getPixel(790, 208))
            val bands = listOf(
                "black BEFORE selected bottom 680" to Rect(800, 670, 1180, 676),
                "black BEFORE selected right 1200" to Rect(1188, 380, 1196, 590),
                "localized light ends before top-right content edge" to Rect(1188, 208, 1196, 216),
                "black AFTER selected right toward owned edge" to Rect(1208, 380, 1280, 800),
                "black AFTER selected bottom toward owned edge" to Rect(800, 688, 1280, 800))
            for ((label, r) in bands) {
                exactBand(clear, r, Color.BLACK, label)
                exactBand(host, r, Color.BLACK, "$label independent of host")
            }
        } finally { clear.recycle(); host.recycle() }
    }

    @Test fun backingContinuationDoesNotRewriteCustomBoxSlantOrPhysicalGlyphAnchors() {
        fixtureIdentity()
        val fixtures = listOf(
            geometry() to primary,
            geometry() to primary.copy(slant = .07),
            geometry(2000, 810) to DriveCardLayout(SecondaryLayoutBox(.60, .30, .88, .80), .12),
            geometry() to DriveCardLayout(SecondaryLayoutBox(.5, .30, .71875, .70), .09))
        // Finish ALL physical anchor/scale controls before testing continuation.
        for ((g, selected) in fixtures) {
            val b = image(g.width, g.height)
            val snapshot = selected.copy(box = selected.box.copy())
            try {
                val c = RecordingCanvas(b)
                draw(c, g = g, layout = selected)
                assertEquals("Caller custom content intent remains exact", snapshot, selected)
                val width = ((selected.box.right - selected.box.left) * g.width).toFloat()
                val height = ((selected.box.bottom - selected.box.top) * g.height).toFloat()
                val scale = min(width / 560f, height / 250f)
                val left = (selected.box.left * g.width).toFloat()
                val top = (selected.box.top * g.height).toFloat()
                val contentX = left + width - 560f * scale
                // Widths use the same public Android face, not renderer-private helpers.
                val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                    textSize = 25f
                }
                val unitX = 530f - measure.measureText("kW")
                measure.textSize = 38f
                val numberX = unitX - 8f - measure.measureText("21")
                // Expected contract frozen from 589, not untransformed drawText arguments.
                val anchors = listOf(
                    Triple("NOW", 94f, 28f), Triple("4.3", 94f, 119f),
                    Triple("km/kWh", 94f, 146f),
                    Triple("Battery", 98f, height / scale - 72f),

                    Triple("21", numberX, height / scale - 72f),
                    Triple("kW", unitX, height / scale - 72f),
                    Triple("≈63% on arrival", 145f, height / scale - 24f))
                val sizes = mapOf("NOW" to 22f, "4.3" to 92f, "km/kWh" to 25f,
                    "Battery" to 23f, "21" to 38f, "kW" to 25f,
                    "≈63% on arrival" to 28f)
                for ((value, x, y) in anchors) {
                    val actual = word(c, value)
                    assertEquals("Physical x $value", contentX + x * scale, actual.x, .03f)
                    assertEquals("Physical y $value", top + y * scale, actual.y, .03f)
                    assertEquals("Physical text width-scale $value", sizes.getValue(value) * scale, actual.sizeX, .03f)
                    assertEquals("Physical text height-scale $value", sizes.getValue(value) * scale, actual.sizeY, .03f)
                }
                semanticColors(c, readyColors())
                val outsideX = (left + width * selected.slant * .35).toInt()
                assertEquals("Original custom diagonal stays transparent near its top", 0,
                    Color.alpha(b.getPixel(outsideX, top.toInt() + 4)))
                val insideX = (left + width * selected.slant + 20).toInt()
                assertEquals("Custom slant is not replaced by a larger default", 255,
                    Color.alpha(b.getPixel(insideX, top.toInt() + 8)))
            } finally { b.recycle() }
        }
        for ((g, selected) in fixtures) {
            val b = image(g.width, g.height)
            try {
                draw(Canvas(b), g = g, layout = selected)
                exactBand(b, Rect(g.width - 12, g.height - 12, g.width, g.height),
                    Color.BLACK, "Backing continues independently of explicit content box")
            } finally { b.recycle() }
        }
    }

    @Test fun continuationRespectsInheritedRectNonRectActualWindowAndLeftOwnershipRegardlessOfSafeArea() {
        fixtureIdentity()
        val w = 1280
        val h = 800
        val safeAreas = listOf(PixelRect(0, 0, w, h), PixelRect(0, 0, 500, 700),
            PixelRect(1100, 600, 1270, 790))
        val inherited = listOf<Path?>(null,
            Path().apply { addRect(760f, 280f, 1260f, 780f, Path.Direction.CW) },
            Path().apply {
                // Integer-edged concave L: its rectangular bounds do not authorize the notch.
                moveTo(760f, 280f); lineTo(1260f, 280f); lineTo(1260f, 520f)
                lineTo(1080f, 520f); lineTo(1080f, 780f); lineTo(760f, 780f); close()
            })
        for ((clipIndex, clip) in inherited.withIndex()) {
            var reference: Bitmap? = null
            var referenceWords: List<Word>? = null
            try {
                for (safe in safeAreas) {
                    // Extra bitmap space detects escaping the actual owned geometry window.
                    val b = image(w + 64, h + 40)
                    try {
                        val c = RecordingCanvas(b)
                        if (clipIndex == 1) c.clipRect(760, 280, 1260, 780)
                        else if (clip != null) c.clipPath(clip)
                        val boundsBefore = Rect(c.clipBounds)
                        val countBefore = c.saveCount
                        @Suppress("DEPRECATION")
                        val matrixBefore = Matrix().also { c.getMatrix(it) }
                        draw(c, g = geometry(w, h, safe))
                        assertEquals("Save stack restored", countBefore, c.saveCount)
                        assertEquals("Caller clip bounds restored", boundsBefore, c.clipBounds)
                        @Suppress("DEPRECATION")
                        val matrixAfter = Matrix().also { c.getMatrix(it) }
                        assertEquals("Caller matrix restored", matrixBefore, matrixAfter)
                        val allowed = Region(0, 0, w, h)
                        if (clip != null) {
                            val clipRegion = Region().apply { setPath(clip, Region(0, 0, w, h)) }
                            allowed.op(clipRegion, Region.Op.INTERSECT)
                        }
                        for (y in 0 until b.height) for (x in 0 until b.width) {
                            if (x < w / 2 || !allowed.contains(x, y))
                                assertEquals("No authority outside left/window/inherited clip $clipIndex at $x,$y", 0,
                                    Color.alpha(b.getPixel(x, y)))
                        }
                        if (reference == null) {
                            reference = b.copy(Bitmap.Config.ARGB_8888, false)
                            referenceWords = c.words.toList()
                        } else {
                            // Same native clip: safe-area independence permits full equality.
                            // Never compare whole RGB images across DIFFERENT native clips.
                            assertTrue("Directions safe area is not card geometry/authority", reference!!.sameAs(b))
                            assertEquals("Safe area cannot move/scale/recolor glyphs", referenceWords, c.words)
                        }
                        semanticColors(c, readyColors())
                        assertEquals("Known interior remains opaque", 255, Color.alpha(b.getPixel(900, 400)))
                        // Behavioral readback of restored NONRECT clip, beyond draw's geometry.
                        if (clipIndex == 2) {
                            val probe = Paint().apply { color = Color.CYAN }
                            c.drawRect(1150f, 650f, 1160f, 660f, probe)
                            exactBand(b, Rect(1150, 650, 1160, 660), Color.TRANSPARENT,
                                "Concave caller notch stays excluded after renderer returns")
                        }
                    } finally { b.recycle() }
                }
            } finally { reference?.recycle() }
        }
        // Separate continuation assertion after all preservation controls above.
        val b = image(w + 64, h + 40)
        try {
            val c = Canvas(b)
            c.clipRect(760, 280, 1260, 780)
            draw(c)
            exactBand(b, Rect(1210, 710, 1250, 770), Color.BLACK,
                "Positive continuation within inherited clip, beyond both selected content edges")
        } finally { b.recycle() }
    }
}
