package com.openautolink.app.drivecard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.secondary.SecondaryHostGeometry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Real renderer + native pixels; recordings observe text only, never replace product drawing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardComposition589Test {
    private data class Text(val value: String, val x: Float, val y: Float, val size: Float)
    private class RecordedCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val words = mutableListOf<Text>()
        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            words += Text(text, x, y, paint.textSize)
            super.drawText(text, x, y, paint)
        }
    }
    private data class Render(val image: Bitmap, val words: List<Text>)
    private fun render(state: DriveCardState, safe: PixelRect = PixelRect(558, 70, 1694, 668)): Render {
        val image = Bitmap.createBitmap(2000, 810, Bitmap.Config.ARGB_8888)
        val canvas = RecordedCanvas(image)
        DriveCardRenderer().draw(canvas, SecondaryHostGeometry(2000, 810, safe), state, imperial = true)
        return Render(image, canvas.words)
    }
    private fun modelState(steps: Int, rate: Float? = null): DriveCardState {
        val model = DriveCardModel()
        for (i in 0..steps) {
            val t = DriveCardBatteryHistory589Test.NOW + i * 1000L
            model.update(DriveCardBatteryHistory589Test.sample(t, 50_000f - i * 5).copy(
                speedKmh = 90f, evChargeRateW = rate), t)
        }
        return model.snapshot(DriveCardBatteryHistory589Test.NOW + steps * 1000L)
    }

    @Test fun earlyDirectDischargeShowsLabelledUsefulBatteryPowerNotGiantEmptyDash() {
        val state = modelState(0, -21_000f)
        assertNull(state.recentKmPerKwh); assertTrue(state.trace.isEmpty())
        val rendered = render(state)
        try {
            val words = rendered.words.map { it.value }
            assertTrue("Direct pack flow must disclose Battery basis: $words", words.any { it.contains("Battery", true) })
            assertTrue("Instantaneous efficiency alone earns NOW: $words", words.any { it == "NOW" })
            assertTrue("Display useful available 21 kW, not only an efficiency dash: $words",
                words.any { it.matches(Regex("[+]?21([.]0)?")) || it.contains("21 kW") })
            assertFalse(words.any { it.contains("Regen", true) || it.contains("usual", true) })
            assertEquals("An empty history must not get decorative orange graph samples", 0, orangeGraphPixels(rendered.image))
        } finally { rendered.image.recycle() }
    }

    @Test fun learningKeepsActualSingleHistoryPointEvenWithoutReadyHeadline() {
        val state = modelState(11) // cross 250 m despite binary floating-point accumulation
        assertEquals(DriveCardAvailability.LEARNING, state.availability)
        assertNull(state.nowKmPerKwh); assertNull(state.recentKmPerKwh)
        assertEquals(1, state.trace.size)
        val rendered = render(state)
        try {
            assertTrue("The one real completed point must remain visible during learning; do not invent a curve",
                orangeGraphPixels(rendered.image) > 0)
            assertFalse(rendered.words.any { it.value == "NOW" || it.value.contains("usual", true) })
        } finally { rendered.image.recycle() }
    }

    @Test fun recentWindowHasSemanticLabelBlueAreaOrangeEndpointAndSeparateArrivalRow() {
        val state = modelState(80)
        assertNull(state.nowKmPerKwh); assertNotNull(state.recentKmPerKwh)
        // Renderer-only approximate-arrival fixture; not a claim about a phone forecast or actual drive.
        val rendered = render(state.copy(arrival = DriveCardArrival(63, true)))
        try {
            val label = rendered.words.firstOrNull { it.value == "RECENT" }
            assertNotNull("A distance-window average must not masquerade as NOW", label)
            val figure = rendered.words.firstOrNull { it.value.matches(Regex("[0-9]+[.][0-9]")) }
            assertNotNull(figure)
            assertTrue(requireNotNull(label).y < requireNotNull(figure).y)
            val arrival = rendered.words.firstOrNull { it.value.contains("arrival", true) }
            assertNotNull(arrival); assertTrue(requireNotNull(arrival).value.contains("≈"))
            assertTrue("Arrival is a separate lower row", arrival.y > figure.y)
            assertFalse(rendered.words.any { it.value == "NOW" || it.value.contains("usual", true) })
            assertTrue("Real latest point receives an orange endpoint", orangeGraphPixels(rendered.image) > 0)
            var blueArea = 0
            for (y in 450 until 530) for (x in 1670 until 1840) {
                val c = rendered.image.getPixel(x, y)
                if (Color.alpha(c) > 0 && Color.blue(c) > Color.red(c) + 20 && Color.blue(c) > Color.green(c)) blueArea++
            }
            assertTrue("A filled trace has an area, not only a thin pale polyline", blueArea > 600)
        } finally { rendered.image.recycle() }
    }

    @Test fun emptyAndLearningDockStayInsideOwnedClipIndependentOfPhoneDirections() {
        for (state in listOf(DriveCardState(), modelState(0))) {
            val a = render(state)
            val b = render(state, PixelRect(191, 70, 1788, 660))
            try {
                assertTrue("Phone Directions is not card ownership", a.image.sameAs(b.image))
                // Continuing black backing is separate from unchanged content placement.
                assertEquals("Black dock reaches the owned lower/right region", Color.BLACK, a.image.getPixel(1980, 790))
                for (y in 0 until 810) for (x in 0 until 2000) {
                    if (x < 1000 || x >= 2000 || y < 202 || y >= 810)
                        assertEquals("Outside admitted right-side window region at $x,$y", 0, Color.alpha(a.image.getPixel(x, y)))
                }
                assertEquals("No fabricated history in empty/zero-distance learning", 0, orangeGraphPixels(a.image))
                assertFalse(a.words.any { it.value == "NOW" || it.value.contains("usual", true) })
            } finally { a.image.recycle(); b.image.recycle() }
        }
    }

    @Test fun parkedPositive500WNeverBecomesRegenCopy() {
        val model = DriveCardModel(); val t = DriveCardBatteryHistory589Test.NOW
        model.update(DriveCardBatteryHistory589Test.sample().copy(speedKmh = 0f, gear = "P", evChargeRateW = 500f), t)
        val rendered = render(model.snapshot(t))
        try { assertFalse("Parked apparent inflow is not braking", rendered.words.any { it.value.contains("Regen", true) }) }
        finally { rendered.image.recycle() }
    }

    private fun orangeGraphPixels(image: Bitmap): Int {
        var count = 0
        // Right graph column only, excluding the existing orange power sign tick below it.
        for (y in 420 until 545) for (x in 1650 until 1870) {
            val c = image.getPixel(x, y)
            if (Color.alpha(c) > 0 && Color.red(c) > 180 && Color.green(c) in 40..170 && Color.blue(c) < 100) count++
        }
        return count
    }
}
