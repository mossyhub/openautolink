package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.*
import com.openautolink.app.cluster.*
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1000dp-h360dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
class LiveGridEditorTest {
    @get:Rule val compose = createOalComposeRule()
    private val model = LiveGridGeometry(PixelSize(1280, 720), PixelRect(0, 0, 1280, 720),
        PixelRect(160, 90, 1120, 630), PixelRect(280, 160, 1000, 560))
    private var root: android.view.View? = null
    private fun mount(binding: LiveGridPreviewBinding? = null, stage: (LiveGridGeometry) -> Unit = {}) {
        compose.setContent {
            root = LocalView.current.rootView
            MaterialTheme(colorScheme = darkColorScheme()) {
                LiveGridEditor("Local grid editor", model, true, binding, stage, {}, {}, localOnly = binding == null)
            }
        }
    }
    @Test fun fullCanvasOutputCanResizeThenMoveWithoutSafeAreaRitual() {
        val full = LiveGridGeometry(model.panel, model.panel.bounds, model.panel.bounds, model.panel.bounds)
        val resized = full.adjustGrid(false, true, -100, -50)
        assertEquals(full.output.width - 100, resized.output.width)
        assertEquals(full.output.height - 50, resized.output.height)
        assertEquals(resized.output, resized.safe)
        val moved = resized.adjustGrid(false, false, 10, 10)
        assertEquals(resized.output.left + 10, moved.output.left)
        assertEquals(moved.output, moved.safe)
        assertTrue(moved.canvas.contains(moved.output))
    }
    @Test fun transparentPixelsRemainEmptyOutsideGridAndWithinCells() {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        drawLiveTransparentGrid(Canvas(bitmap), model)
        assertEquals(0, bitmap.getPixel(10, 10))
        assertEquals(0, bitmap.getPixel(1170, 680))
        assertEquals(0, bitmap.getPixel(205, 120))
        assertNotEquals(0, bitmap.getPixel(256, 200))
        val filled = IntArray(1280 * 720); bitmap.getPixels(filled, 0, 1280, 0, 0, 1280, 720)
        assertTrue("Sparse lines, not opaque map", filled.count { it != 0 } < filled.size / 10)
    }
    @Test fun viewActuallyDrawsTransparentGridWithoutEncodedVideo() {
        val view = LiveTransparentGridView(org.robolectric.RuntimeEnvironment.getApplication())
        view.layout(0, 0, 1280, 720); view.geometry = model; view.active = true
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        assertNotEquals(0, bitmap.getPixel(256, 200))
        assertEquals(0, bitmap.getPixel(10, 10))
        view.active = false
        val stopped = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888); view.draw(Canvas(stopped))
        assertEquals(0, stopped.getPixel(256, 200))
    }
    @Test fun updatesKeepSameLeaseAndDeadlineStaleStopCannotRetireSuccessor() {
        var now = 100L
        val deadlines = mutableListOf<Long>()
        val timers = mutableListOf<() -> Unit>()
        val run = LiveGridDraftRun({ now }, { delay, action -> deadlines += delay; timers += action; {} })
        val a = Any(); val b = Any(); val draws = mutableListOf<LiveGridGeometry?>()
        run.start(a, model, 15) { draws += it }
        val moved = model.adjustGrid(false, false, 30, 20)
        now += 12000
        assertTrue(run.update(a, moved)); assertEquals(moved, draws.last()); assertEquals(1, deadlines.size)
        now += 3000
        assertFalse(run.update(a, model)); assertNull(draws.last())
        run.start(b, model, 15) { draws += it }; run.stop(a); timers.first()()
        assertTrue(run.active(b)); run.stop(b); assertFalse(run.active(b))
    }
    @Test fun startThenDragAndResizeUpdatesExactRunningGridWithoutStopOrRestart() {
        val owner = Any(); var starts = 0; var stops = 0
        val updates = mutableListOf<LiveGridGeometry>(); val stages = mutableListOf<LiveGridGeometry>()
        mount(LiveGridPreviewBinding(owner, "Test direct window", { _, seconds -> assertEquals(15, seconds); starts++ },
            { updates += it; true }, { stops++ })) { stages += it }
        compose.onNodeWithTag("liveGridStart").performClick()
        capture("short-active-before")
        fullyVisible("liveGridBack"); fullyVisible("liveGridCanvas"); fullyVisible("liveGridStop"); fullyVisible("liveGridReset")
        val canvas = compose.onNodeWithTag("liveGridCanvas")
        canvas.performTouchInput { down(center); moveBy(Offset(30f, 0f)); moveBy(Offset(20f, 10f)); up() }
        compose.waitForIdle()
        assertTrue(stages.isNotEmpty()); assertEquals(stages.last(), updates.last()); assertEquals(1, starts); assertEquals(0, stops)
        assertTrue(stages.last().output.left > model.output.left)
        assertEquals(model.output.width, stages.last().output.width)
        compose.onNodeWithTag("liveGridSafe").performClick()
        compose.onNodeWithTag("liveGridResize").performClick()
        val previous = stages.last()
        canvas.performTouchInput { down(center); moveBy(Offset(30f, 0f)); moveBy(Offset(15f, 10f)); up() }
        compose.waitForIdle()
        assertTrue(stages.last().safe.width > previous.safe.width); assertEquals(1, starts); assertEquals(0, stops)
        capture("short-active-after")
        compose.onNodeWithTag("liveGridStop").performClick()
        assertEquals(1, stops); compose.onNodeWithTag("liveGridStart").assertExists()
    }
    @Test fun ownerReplacementThenDragUpdatesBAndDisposalStopsExactOwners() {
        val a = Any(); val b = Any(); val stops = mutableListOf<Any>(); val updates = mutableListOf<Any>()
        fun binding(owner: Any) = LiveGridPreviewBinding(owner, "Test", { _, _ -> }, { updates += owner; true }, { stops += owner })
        var current by mutableStateOf(binding(a)); var shown by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (shown) LiveGridEditor("Editor", model, true, current, {}, {}, {}) } }
        compose.onNodeWithTag("liveGridStart").performClick()
        compose.runOnIdle { current = binding(b) }; compose.waitForIdle()
        assertEquals(listOf(a), stops)
        compose.onNodeWithTag("liveGridStart").performClick()
        compose.onNodeWithTag("liveGridCanvas").performTouchInput { down(center); moveBy(Offset(30f, 0f)); up() }
        compose.waitForIdle(); assertEquals(b, updates.last()); assertEquals(listOf(a), stops)
        compose.runOnIdle { shown = false }; compose.waitForIdle(); assertEquals(listOf(a, b), stops)
    }
    @Test fun failureIsTruthfulAndLeavesEditorUsable() {
        mount(LiveGridPreviewBinding(Any(), "Denied window", { _, _ -> error("Android denied this grid window") }, { false }, {}))
        compose.onNodeWithTag("liveGridStart").performClick()
        compose.onNodeWithText("Android denied this grid window").assertExists()
        compose.onNodeWithTag("liveGridStart").assertIsEnabled(); compose.onNodeWithTag("liveGridStop").assertDoesNotExist()
    }
    @Test fun localGridStartsImmediatelyAndStopsAtDeadline() {
        mount(); compose.onNodeWithTag("liveGridStart").performClick()
        compose.onNodeWithTag("liveGridStatus").assertTextContains("Grid visible here")
        capture("local-tablet-active")
        compose.mainClock.advanceTimeBy(15100); compose.waitForIdle()
        compose.onNodeWithTag("liveGridStart").assertExists()
    }
    @Test @Config(qualifiers = "w840dp-h300dp-land-mdpi") fun shortViewportPinsActionsAndScrollsControls() {
        mount(); compose.onNodeWithTag("liveGridStart").performClick()
        fullyVisible("liveGridBack"); fullyVisible("liveGridCanvas"); fullyVisible("liveGridStop"); fullyVisible("liveGridReset")
        compose.onNodeWithTag("liveGrid_Height_plus").performScrollTo().assertIsDisplayed()
        capture("extra-short-active")
    }
    @Test @Config(qualifiers = "w360dp-h720dp-port-mdpi") fun narrowViewportHasBoundedGridAndReachableControls() {
        mount(); compose.onNodeWithTag("liveGridStart").performClick()
        fullyVisible("liveGridBack"); fullyVisible("liveGridStop")
        compose.onNodeWithTag("liveGrid_Height_plus").performScrollTo().assertIsDisplayed()
        capture("narrow-active")
    }
    private fun fullyVisible(tag: String) {
        val bounds = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$tag: $bounds / $root", bounds.left >= root.left && bounds.top >= root.top && bounds.right <= root.right && bounds.bottom <= root.bottom)
    }
    private fun capture(name: String) {
        compose.runOnIdle {
            val view = requireNotNull(root); val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File("build/live-grid-renders/sdk-${org.robolectric.RuntimeEnvironment.getApiLevel()}").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(dir, "$name.txt").writeText("Actual Android root render: ${view.width}x${view.height}, density=${view.resources.displayMetrics.density}. Host fixture, not a GM visibility proof.\n")
        }
    }
}
