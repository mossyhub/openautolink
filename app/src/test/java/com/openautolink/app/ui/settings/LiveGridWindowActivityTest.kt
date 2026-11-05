package com.openautolink.app.ui.settings

import android.app.ActivityManager
import android.app.ActivityOptions
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Looper
import android.view.Display
import android.view.ViewGroup
import android.view.WindowManager
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.cluster.PixelSize
import com.openautolink.app.secondary.SecondaryDisplayPolicyInventory
import com.openautolink.app.secondary.SecondaryRoute
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowActivityManager
import org.robolectric.shadows.ShadowDisplayManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, shadows = [LiveGridWindowActivityTest.Admission::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class LiveGridWindowActivityTest {
    @Implements(ActivityManager::class)
    class Admission : ShadowActivityManager() {
        @Implementation fun isActivityStartAllowedOnDisplay(context: Context, display: Int, intent: Intent) = allowed
    }
    companion object { var allowed = true }
    private val app = RuntimeEnvironment.getApplication() as Application
    private var inventoryFixture: RawDisplayTestFixture? = null
    @org.junit.After fun closeInventory() { inventoryFixture?.close() }
    private fun target(): com.openautolink.app.secondary.SecondaryDisplayDescriptor {
        allowed = true
        val id = ShadowDisplayManager.addDisplay("w800dp-h480dp-mdpi", "Grid bench external")
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setFlags(Display.FLAG_PRESENTATION); shadowOf(display).setState(Display.STATE_ON)
        val fixture = RawDisplayTestFixture(app).also { inventoryFixture = it }
        return fixture.target(id, 800, 480, SecondaryRoute.PUBLIC_ACTIVITY)
    }
    private fun binding(display: com.openautolink.app.secondary.SecondaryDisplayDescriptor,
        directionsArea: Boolean = false, idle: () -> Boolean): LiveGridPreviewBinding {
        val real = LiveGridWindows.binding(app, display, directionsArea, idle)
        return real.copy(start = { geometry, seconds ->
            // Preserve the actual failure, with exact at-Start receipts (not a raw-row guess).
            // Intentional idle/stale/denied negatives still exercise production unchanged.
            try { real.start(geometry, seconds) } catch (failure: Exception) {
                val producer = SecondaryDisplayPolicyInventory.get(app)
                val current = producer.snapshots.value.displays.singleOrNull { it.id == display.id }
                val actual = app.getSystemService(DisplayManager::class.java).getDisplay(display.displayId)
                val metrics = android.util.DisplayMetrics().also { actual?.getRealMetrics(it) }
                failure.addSuppressed(AssertionError("AT_GRID_START expected=$display current=$current " +
                    "producer=${System.identityHashCode(producer)} raw=${producer.raw.displays.value} " +
                    "actualValid=${actual?.isValid} state=${actual?.state} flags=${actual?.flags} " +
                    "metrics=${metrics.widthPixels}x${metrics.heightPixels}"))
                throw failure
            }
        })
    }
    private val geometry = LiveGridGeometry(PixelSize(800, 480), PixelRect(0, 0, 800, 480),
        PixelRect(100, 50, 700, 430), PixelRect(200, 100, 600, 380))
    private fun launch(display: Int): ActivityController<LiveGridWindowActivity> {
        val intent = requireNotNull(shadowOf(app).nextStartedActivity)
        return ActivityController.of(LiveGridWindowActivity(), intent,
            ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle()).create()
    }
    @Test fun directActivityRendersTransparentGridUpdatesLiveAndStaleStopCannotRetireB() {
        val display = target(); val a = binding(display) { true }
        a.start(geometry, 15)
        val controller = launch(display.displayId); val activity = controller.get()
        assertFalse(activity.isFinishing)
        val attributes = activity.window.attributes
        assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(attributes.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertEquals(0f, attributes.dimAmount)
        assertEquals(Color.TRANSPARENT, (activity.window.decorView.background as ColorDrawable).color)
        val view = (activity.findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
        view.layout(0, 0, 800, 480)
        fun draw(): Bitmap = Bitmap.createBitmap(800, 480, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        val before = draw(); assertNotEquals(0, before.getPixel(160, 80)); assertEquals(0, before.getPixel(20, 20))
        val next = geometry.adjustGrid(false, false, 40, 20)
        assertTrue(a.update(next)); val after = draw(); assertEquals(0, after.getPixel(160, 80)); assertNotEquals(0, after.getPixel(200, 100))
        a.stop(); assertTrue(activity.isFinishing)
        val b = binding(display) { true }; b.start(geometry, 15)
        val successor = launch(display.displayId)
        a.stop(); controller.destroy()
        assertFalse(successor.get().isFinishing)
        b.stop(); assertTrue(successor.get().isFinishing); successor.destroy()
    }
    @Test fun sameBindingRestartSurvivesDelayedPredecessorActivityStop() {
        val display = target(); val binding = binding(display) { true }
        binding.start(geometry, 15)
        val predecessor = launch(display.displayId).start().resume()
        var successor: ActivityController<LiveGridWindowActivity>? = null
        try {
            binding.stop(); assertTrue(predecessor.get().isFinishing)
            binding.start(geometry, 15)
            successor = launch(display.displayId).start().resume()
            assertFalse(successor.get().isFinishing)
            // Android can deliver the finishing predecessor's lifecycle after the same editor restarts.
            predecessor.pause().stop()
            assertFalse("Predecessor onStop must not finish same-binding successor", successor.get().isFinishing)
            assertTrue("Successor remains current and accepts live edits", binding.update(geometry.adjustGrid(false, false, 1, 1)))
            predecessor.destroy()
            assertFalse(successor.get().isFinishing)
            successor.pause().stop()
            assertTrue("Current Activity onStop still retires its exact request", successor.get().isFinishing)
            assertFalse(binding.update(geometry))
        } finally {
            binding.stop()
            if (!predecessor.get().isDestroyed) predecessor.destroy()
            successor?.let { if (!it.get().isDestroyed) it.destroy() }
        }
    }
    @Implements(android.view.View::class)
    class Coordinates : org.robolectric.shadows.ShadowView() {
        val buffers = mutableListOf<IntArray>()
        var originX = 0
        @Implementation override fun getLocationOnScreen(location: IntArray) {
            buffers.add(location)
            location[0] = originX; location[1] = 0
        }
    }
    @Test
    @Config(shadows = [Admission::class, Coordinates::class])
    fun repeatedDrawReusesCoordinatesAndReadsActualOriginAgain() {
        val display = target(); val binding = binding(display) { true }
        binding.start(geometry, 15)
        val controller = launch(display.displayId)
        try {
            val view = (controller.get().findViewById<ViewGroup>(android.R.id.content)).getChildAt(0)
            view.layout(0, 0, 800, 480)
            val coordinates = org.robolectric.shadow.api.Shadow.extract<Coordinates>(view)
            fun draw(): Bitmap = Bitmap.createBitmap(800, 480, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
            coordinates.buffers.clear()
            val first = draw()
            coordinates.originX = 20
            val second = draw()
            assertEquals(2, coordinates.buffers.size)
            assertSame("Drawing must reuse the per-view coordinate buffer", coordinates.buffers[0], coordinates.buffers[1])
            assertNotEquals(0, first.getPixel(160, 80))
            assertEquals(0, second.getPixel(160, 80))
            assertNotEquals("Actual origin must be sampled on each draw", 0, second.getPixel(140, 80))
        } finally { binding.stop(); controller.destroy() }
    }
    @Test fun directWindowDeadlineCannotBeExtendedByGeometryEdits() {
        val display = target(); val binding = binding(display) { true }
        binding.start(geometry, 15); val controller = launch(display.displayId)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(12))
        assertTrue(binding.update(geometry.adjustGrid(false, false, 1, 1)))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertTrue(controller.get().isFinishing); assertFalse(binding.update(geometry)); controller.destroy()
    }
    @Test fun directionsPhysicalWindowHasThirtySecondOriginalDeadlineAndNoEditRenewal() {
        val display = target(); val binding = binding(display, directionsArea = true) { true }
        binding.start(geometry, 30); val controller = launch(display.displayId).start().resume()
        try {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
            assertFalse(controller.get().isFinishing)
            assertTrue(binding.update(geometry.adjustDirections(DirectionsHandle.MOVE, 1, 1)))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9))
            assertFalse("30s must be real, not a relabelled 15s cap", controller.get().isFinishing)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertTrue(controller.get().isFinishing); assertFalse(binding.update(geometry))
        } finally { binding.stop(); controller.pause().stop().destroy() }
    }
    @Test fun thirtySecondBudgetIsScopedAndStillBounded() {
        val display = target()
        val legacy = binding(display) { true }
        try { legacy.start(geometry, 30); fail("Legacy tools retain 15s budget") } catch (_: IllegalArgumentException) { }
        val directions = binding(display, directionsArea = true) { true }
        try { directions.start(geometry, 31); fail("Directions must remain bounded") } catch (_: IllegalArgumentException) { }
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun idleAndExactAndroidAdmissionAreRequiredNoFallbackWindowStarts() {
        val display = target()
        val notIdle = binding(display) { false }
        try { notIdle.start(geometry, 15); fail("Projection must not be disturbed") } catch (_: IllegalStateException) { }
        assertNull(shadowOf(app).nextStartedActivity)
        val denied = binding(display) { true }; allowed = false
        try { denied.start(geometry, 15); fail("Android admission must hold") } catch (_: IllegalStateException) { }
        assertNull(shadowOf(app).nextStartedActivity)
    }
    @Test fun staleDisplayEpochAndWrongActualDisplayFailClosed() {
        val display = target(); val binding = binding(display) { true }
        binding.start(geometry, 15)
        val wrong = launch(Display.DEFAULT_DISPLAY); assertTrue(wrong.get().isFinishing); wrong.destroy()
        assertFalse(binding.update(geometry))
        ShadowDisplayManager.changeDisplay(display.displayId, "w900dp-h480dp-mdpi")
        shadowOf(Looper.getMainLooper()).idle()
        try { binding.start(geometry, 15); fail("Old attachment must not launch") } catch (_: IllegalStateException) { }
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
