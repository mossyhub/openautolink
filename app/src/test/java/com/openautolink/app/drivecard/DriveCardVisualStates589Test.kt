package com.openautolink.app.drivecard

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.secondary.SecondaryHostGeometry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Parent-owned native render capture. All displayed states are production-model outputs,
 * except the explicitly named approximate-arrival row fixture. Never a real-car render claim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardVisualStates589Test {
    private fun learned(steps: Int, rate: Float? = null): DriveCardState {
        val model = DriveCardModel()
        for (i in 0..steps) {
            val t = DriveCardBatteryHistory589Test.NOW + i * 1000L
            model.update(DriveCardBatteryHistory589Test.sample(t, 50_000f - i * 5).copy(speedKmh = 90f, evChargeRateW = rate), t)
        }
        return model.snapshot(DriveCardBatteryHistory589Test.NOW + steps * 1000L)
    }
    private fun single(charging: Boolean = false, parked: Boolean = false): DriveCardState {
        val now = DriveCardBatteryHistory589Test.NOW
        val model = DriveCardModel()
        model.update(DriveCardBatteryHistory589Test.sample().copy(evChargeState = if (charging) 1 else 3,
            speedKmh = if (parked) 0f else 84f, gear = if (parked) "P" else "D",
            evChargeRateW = if (parked) 500f else -21_000f), now)
        return model.snapshot(now)
    }
    private fun allData(steps: Int, gap: Boolean = false): DriveCardState {
        val model = DriveCardModel()
        var energy = 50_000f
        for (i in 0..steps) {
            val now = DriveCardBatteryHistory589Test.NOW + i * 1000L
            val delta = if (gap && i in 33..44) -4f else listOf(5f, 9f, 3f, 7f)[i / 11 % 4]
            if (i > 0) energy -= delta
            val base = DriveCardBatteryHistory589Test.sample(now, energy)
            val range = DriveCardArrivalFallback589Test.sample(now).evObservationMetadata.getValue("RANGE_REMAINING")
            model.update(base.copy(speedKmh = 90f, rangeKm = 250f, evChargeRateW = -delta * 3600f,
                evObservationMetadata = base.evObservationMetadata + ("RANGE_REMAINING" to range)), now)
        }
        val now = DriveCardBatteryHistory589Test.NOW + steps * 1000L
        model.route(DriveCardArrivalFallback589Test.route(now))
        return model.snapshot(now).also {
            assertNotNull("Actual model arrival, not a copied renderer row", it.arrival)
            assertNotNull(it.powerKw)
            if (gap) assertTrue("Actual negative-energy window stays a history gap", it.trace.any { value -> value.isNaN() })
        }
    }
    private fun states(): List<Pair<String, DriveCardState>> = listOf(
        "unavailable" to DriveCardModel().snapshot(DriveCardBatteryHistory589Test.NOW),
        "zero-window-learning" to learned(0),
        "one-real-window-learning" to learned(11),
        "early-battery-use" to single(),
        "parked-battery-inflow" to single(parked = true),
        "charging" to single(charging = true),
        "recent-real-windows" to learned(80),
        "recent-approx-arrival-fixture" to learned(80).copy(arrival = DriveCardArrival(63, true)),
        "early-power-local-arrival" to allData(0),
        "mature-power-local-arrival" to allData(80),
        "genuine-gap-power-local-arrival" to allData(80, gap = true),
    )

    @Test fun captureRealEmptyLearningPowerAndRecentStatesWithinExistingCallerClip() {
        for ((w, h) in listOf(2000 to 810, 1280 to 480)) for ((name, state) in states()) {
            val image = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val clipped = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                val geometry = SecondaryHostGeometry(w, h, PixelRect(w / 4, 70, w * 9 / 10, h * 4 / 5))
                DriveCardRenderer().draw(Canvas(image), geometry, state, imperial = true)
                val canvas = Canvas(clipped)
                val right = w * 9 / 10
                val bottom = h * 4 / 5
                canvas.clipRect(0, 0, right, bottom)
                DriveCardRenderer().draw(canvas, geometry, state, imperial = true)
                assertEquals("An existing caller clip cannot be expanded at right", 0, clipped.getPixel(right, h * 7 / 10))
                assertEquals("An existing caller clip cannot be expanded at bottom", 0, clipped.getPixel(w * 4 / 5, bottom))
                val output = System.getProperty("drivecard.render.dir").orEmpty()
                if (output.isNotEmpty()) {
                    File(output).mkdirs()
                    File(output, "drivecard-589-$name-${w}x${h}-sdk${android.os.Build.VERSION.SDK_INT}.png")
                        .outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            } finally { image.recycle(); clipped.recycle() }
        }
    }
}
