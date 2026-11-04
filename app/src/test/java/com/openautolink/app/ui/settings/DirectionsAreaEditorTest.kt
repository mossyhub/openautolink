package com.openautolink.app.ui.settings

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.Density
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.cluster.PixelSize
import com.openautolink.app.ui.testing.createOalComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import java.io.File
import kotlin.math.roundToInt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class, qualifiers = "w1024dp-h400dp-land-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DirectionsAreaEditorTest {
    @get:Rule val compose = createOalComposeRule()
    private val model = LiveGridGeometry(PixelSize(2000, 810), PixelRect(0, 0, 2000, 810),
        PixelRect(0, 0, 2000, 810), PixelRect(558, 70, 1694, 668))
    private var root: android.view.View? = null
    private var backDispatcher: OnBackPressedDispatcher? = null
    private fun mount(binding: LiveGridPreviewBinding? = fake(), stage: (LiveGridGeometry) -> Unit = {},
        reset: () -> Unit = {}, done: () -> Unit = {}, font: Float = 1.3f, uiScale: Float = 1f) {
        compose.setContent {
            root = LocalView.current.rootView
            backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * uiScale, font)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    LiveGridEditor("Directions area", model, true, binding, stage, reset, done,
                        automatic = model, safeOnly = true)
                }
            }
        }
    }
    private fun fake() = LiveGridPreviewBinding(Any(), "Not ordinary UI", { _, seconds -> assertEquals(30, seconds) }, { true }, {})
    private fun touch(handle: DirectionsHandle, dx: Float, dy: Float, geometry: LiveGridGeometry = model) {
        compose.onNodeWithTag("liveGridCanvas").performTouchInput {
            val p = handle.corner(geometry.safe)
            down(Offset(p.x * width / geometry.panel.width, p.y * height / geometry.panel.height))
            moveBy(Offset(dx, dy)); up()
        }
        compose.waitForIdle()
    }
    private fun expected(handle: DirectionsHandle, dx: Float, dy: Float): LiveGridGeometry {
        val bounds = compose.onNodeWithTag("liveGridCanvas").fetchSemanticsNode().boundsInRoot
        return model.adjustDirections(handle, (dx * model.panel.width / bounds.width).roundToInt(),
            (dy * model.panel.height / bounds.height).roundToInt())
    }
    @Test fun bodyDragUsesExactAspectFitMappingAndDoesNotTouchFullVideo() {
        val stages = mutableListOf<LiveGridGeometry>(); mount(stage = { stages += it })
        val expected = expected(DirectionsHandle.MOVE, 20f, 10f)
        touch(DirectionsHandle.MOVE, 20f, 10f)
        assertEquals(expected, stages.last())
        assertEquals(model.output, stages.last().output); assertEquals(model.canvas, stages.last().canvas)
    }
    @Test fun allFourCornersResizeIndependentlyAndClampInsideActualWindow() {
        DirectionsHandle.entries.filter { it != DirectionsHandle.MOVE }.forEach { handle ->
            val changed = model.adjustDirections(handle, -40, 35)
            assertEquals(model.output, changed.output); assertEquals(model.canvas, changed.canvas)
            assertEquals(model.essentialCanvas, changed.essentialCanvas)
            assertTrue(changed.output.contains(changed.safe)); assertNotEquals(model.safe, changed.safe)
            val extreme = changed.adjustDirections(handle, 100000, -100000)
            assertTrue(extreme.output.contains(extreme.safe)); assertTrue(extreme.safe.width >= 1 && extreme.safe.height >= 1)
        }
        assertEquals(PixelRect(518, 105, 1694, 668), model.adjustDirections(DirectionsHandle.TOP_LEFT, -40, 35).safe)
        assertEquals(PixelRect(558, 105, 1654, 668), model.adjustDirections(DirectionsHandle.TOP_RIGHT, -40, 35).safe)
        assertEquals(PixelRect(518, 70, 1694, 703), model.adjustDirections(DirectionsHandle.BOTTOM_LEFT, -40, 35).safe)
        assertEquals(PixelRect(558, 70, 1654, 703), model.adjustDirections(DirectionsHandle.BOTTOM_RIGHT, -40, 35).safe)
    }
    @Test fun realTopLeftCornerGestureResizesWithOppositeCornerFixed() = cornerGesture(DirectionsHandle.TOP_LEFT)
    @Test fun realTopRightCornerGestureResizesWithOppositeCornerFixed() = cornerGesture(DirectionsHandle.TOP_RIGHT)
    @Test fun realBottomLeftCornerGestureResizesWithOppositeCornerFixed() = cornerGesture(DirectionsHandle.BOTTOM_LEFT)
    @Test fun realBottomRightCornerGestureResizesWithOppositeCornerFixed() = cornerGesture(DirectionsHandle.BOTTOM_RIGHT)
    private fun cornerGesture(handle: DirectionsHandle) {
        val stages = mutableListOf<LiveGridGeometry>(); mount(stage = { stages += it })
        val expected = expected(handle, 12f, 8f)
        touch(handle, 12f, 8f); assertEquals(expected, stages.last()); assertEquals(model.output, stages.last().output)
    }
    @Test fun draggingOutsideGreenBoxDoesNothingAndTinyBoxUsesNearestCorner() {
        val stages = mutableListOf<LiveGridGeometry>(); mount(stage = { stages += it })
        compose.onNodeWithTag("liveGridCanvas").performTouchInput { down(Offset(4f, height - 4f)); moveBy(Offset(30f, -10f)); up() }
        compose.waitForIdle(); assertTrue(stages.isEmpty())
        val tiny = model.copy(safe = PixelRect(900, 400, 901, 401))
        assertEquals(DirectionsHandle.BOTTOM_RIGHT, directionsHit(tiny, Offset(90.1f, 40.1f), Size(200f, 81f), 48f))
    }
    @Test fun onlyApprovedControlsAndClearDoneSaveUsbGuidanceAreVisible() {
        mount()
        compose.onNodeWithText("Directions area").assertIsDisplayed()
        compose.onNodeWithText("Move and resize the green box to keep directions visible.").assertIsDisplayed()
        compose.onNodeWithText("After Done, tap Save changes. USB: unplug and reconnect to apply.").assertIsDisplayed()
        for (text in listOf("Restore default", "Show on driver display · 30s", "Done")) compose.onNodeWithText(text).assertIsDisplayed()
        for (tag in listOf("liveGridOutput", "liveGridSafe", "liveGridMove", "liveGridResize", "liveGrid_X_plus", "liveGridStatus"))
            compose.onNodeWithTag(tag).assertDoesNotExist()
        for (handle in DirectionsHandle.entries) {
            val bounds = compose.onNodeWithContentDescription(handle.label).assertExists().fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.width >= 48 && bounds.height >= 48)
        }
        capture("wide-font130")
    }
    @Test @Config(qualifiers = "w480dp-h900dp-port-mdpi")
    fun narrowLargeTextKeepsAllFooterActionsAndGuidanceReachable() {
        mount(font = 1.5f)
        for (tag in listOf("liveGridReset", "liveGridStart", "liveGridBack", "directionsSaveGuidance")) fullyVisible(tag)
        compose.onNodeWithTag("liveGridCanvas").performScrollTo().assertIsDisplayed()
        capture("narrow-font150")
    }
    @Test @Config(qualifiers = "w2331dp-h907dp-land-200dpi")
    fun driverSizedDensity200With125PercentUiKeepsCompletionVisible() {
        mount(font = 1.3f, uiScale = 1.25f)
        for (tag in listOf("liveGridCanvas", "liveGridReset", "liveGridStart", "liveGridBack", "directionsSaveGuidance")) fullyVisible(tag)
        capture("driver-density200-ui125-font130")
    }
    @Test fun activePreviewEditsUpdateSameThirtySecondLeaseAndDoneOnlyReturns() {
        var starts = 0; var stops = 0; var done = 0; var reset = 0
        val updates = mutableListOf<LiveGridGeometry>(); val stages = mutableListOf<LiveGridGeometry>()
        mount(LiveGridPreviewBinding(Any(), "Hidden", { _, seconds -> assertEquals(30, seconds); starts++ },
            { updates += it; true }, { stops++ }), { stages += it }, { reset++ }, { done++ })
        compose.onNodeWithTag("liveGridStart").performClick()
        compose.onNodeWithText("Stop preview").assertIsDisplayed()
        touch(DirectionsHandle.MOVE, 20f, 4f)
        assertEquals(stages.last(), updates.last()); assertEquals(1, starts); assertEquals(0, stops)
        compose.onNodeWithTag("liveGridReset").performClick(); assertEquals(1, reset); assertEquals(model, updates.last())
        compose.onNodeWithTag("liveGridBack").performClick(); assertEquals(1, done); assertEquals(1, stops)
    }
    @Test fun ownerReplacementWithSameCanvasRoutesOldGestureClosureToExactB() {
        val a = Any(); val b = Any(); val stops = mutableListOf<Any>(); val updates = mutableListOf<Any>()
        fun binding(owner: Any) = LiveGridPreviewBinding(owner, "Hidden", { _, seconds -> assertEquals(30, seconds) },
            { updates += owner; true }, { stops += owner })
        var current by mutableStateOf(binding(a)); var shown by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (shown) LiveGridEditor("Directions area", model, true, current, {}, {}, {}, safeOnly = true) } }
        compose.onNodeWithTag("liveGridStart").performClick()
        // Initialize the long-lived pointer coroutine before binding replacement.
        touch(DirectionsHandle.MOVE, 1f, 0f)
        compose.runOnIdle { current = binding(b) }; compose.waitForIdle(); assertEquals(listOf(a), stops)
        compose.onNodeWithTag("liveGridStart").performClick(); touch(DirectionsHandle.MOVE, 12f, 3f)
        assertEquals(b, updates.last()); assertEquals(listOf(a), stops)
        compose.runOnIdle { shown = false }; compose.waitForIdle(); assertEquals(listOf(a, b), stops)
    }
    @Test fun systemBackStopsPreviewAndReturnsWithoutResetOrSave() {
        var stops = 0; var returns = 0
        mount(LiveGridPreviewBinding(Any(), "Hidden", { _, _ -> }, { true }, { stops++ }), done = { returns++ })
        compose.onNodeWithTag("liveGridStart").performClick()
        compose.runOnIdle { requireNotNull(backDispatcher).onBackPressed() }
        compose.waitForIdle(); assertEquals(1, stops); assertEquals(1, returns)
    }
    @Test fun missingPhysicalBindingNeverPretendsToStartLocalPreview() {
        mount(binding = null)
        compose.onNodeWithTag("liveGridStart").assertIsNotEnabled()
        compose.onNodeWithText("Connect a driver display to preview.").assertIsDisplayed()
        compose.onNodeWithTag("liveGridStop").assertDoesNotExist()
    }
    @Test fun stopPreviewRetiresExactLeaseAndFailureRemainsPlainAndRecoverable() {
        var stops = 0; var attempts = 0
        mount(LiveGridPreviewBinding(Any(), "Hidden", { _, _ ->
            attempts++; if (attempts == 2) error("Display disconnected. Return to settings and choose a display.")
        }, { true }, { stops++ }))
        compose.onNodeWithTag("liveGridStart").performClick()
        compose.onNodeWithText("Stop preview").performClick(); assertEquals(1, stops)
        compose.onNodeWithTag("liveGridStart").performClick()
        compose.onNodeWithText("Display disconnected. Return to settings and choose a display.").assertIsDisplayed()
        compose.onNodeWithTag("liveGridStart").assertIsEnabled(); compose.onNodeWithTag("liveGridStop").assertDoesNotExist()
    }
    @Test fun thirtySecondLocalLeaseKeepsOriginalDeadlineAndLegacyLimit() {
        var now = 0L; val timers = mutableListOf<Pair<Long, () -> Unit>>()
        val run = LiveGridDraftRun({ now }, { delay, fire -> timers += delay to fire; {} }, maxSeconds = 30)
        val owner = Any(); var drawn: LiveGridGeometry? = null
        run.start(owner, model, 30) { drawn = it }
        now = 20000; assertTrue(run.update(owner, model)); assertEquals(listOf(30000L), timers.map { it.first })
        now = 29000; assertTrue(run.active(owner)); assertNotNull(drawn)
        now = 30000; assertFalse(run.update(owner, model)); assertNull(drawn)
        run.start(owner, model, 30) { drawn = it }; timers.first().second()
        assertTrue("Old same-owner deadline cannot cancel successor", run.active(owner)); run.stop(owner)
        try { LiveGridDraftRun().start(Any(), model, 30) {}; fail("Legacy budget stays 15") } catch (_: IllegalArgumentException) { }
    }
    @Test fun accessibilityMovesAndResizesWithoutRawCoordinateControls() {
        val stages = mutableListOf<LiveGridGeometry>(); mount(stage = { stages += it })
        val actions = compose.onNodeWithContentDescription("Move directions area").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle { assertTrue(actions.first { it.label == "Move right" }.action()) }
        assertEquals(model.adjustDirections(DirectionsHandle.MOVE, 1, 0), stages.last())
    }
    private fun fullyVisible(tag: String) {
        val b = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$tag: $b / $root", b.left >= root.left && b.top >= root.top && b.right <= root.right && b.bottom <= root.bottom)
    }
    private fun capture(name: String) = compose.runOnIdle {
        val view = requireNotNull(root); val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val dir = File("build/directions-area-renders/sdk-${RuntimeEnvironment.getApiLevel()}").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        File(dir, "$name.txt").writeText("Actual Compose Android root: ${view.width}x${view.height}, density=${view.resources.displayMetrics.density}. Controlled host fixture; not vehicle visibility evidence.\n")
    }
}
