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
class ClusterLayoutViewportTest {
    @get:Rule val compose = createOalComposeRule()
    private val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
    private val canvasBounds = PixelRect(0, 135, 2000, 810)
    private val model = ClusterLayoutModel(ClusterLayoutKey(profile.vehicle, profile.role, profile.panel),
        canvasBounds, requireNotNull(profile.output.intersect(canvasBounds)), profile.safe)
    private var root: android.view.View? = null
    private fun mount(onStage: (ClusterLayoutModel) -> Unit = {}) {
        compose.setContent {
            root = LocalView.current.rootView
            MaterialTheme(colorScheme = darkColorScheme()) {
                ClusterLayoutEditor(clusterSettingsFixture().copy(layout = model, automaticLayout = model,
                    onStageLayout = onStage, onResetLayout = {}, onStartPreview = { _, _ -> }), {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Surface: Not reported by editor").assertExists()
    }
    @Test fun shortCarViewportShowsWholePreviewAndPrecisionControlsWithoutScrolling() {
        mount()
        capture("short-car")
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val canvas = compose.onNodeWithTag("clusterLayoutCanvas").fetchSemanticsNode().boundsInRoot
        assertTrue("Preview must leave room for controls: $canvas in $viewport", canvas.height <= viewport.height * .7f)
        listOf("clusterEditorBack", "clusterLayoutCanvas", "clusterDragMove", "clusterDragResize",
            "clusterResetAutomatic", "clusterPreviewStart").forEach { fullyVisible(it) }
        for (axis in listOf("X", "Y", "W", "H")) for (step in listOf("minus", "plus")) {
            val node = fullyVisible("clusterNudge_SAFE_${axis}_$step")
            assertTrue("48dp touch width", node.width >= 48f)
            assertTrue("48dp touch height", node.height >= 48f)
        }
    }
    @Test fun scaledPreviewDragUsesOneAspectFitScaleForMoveAndResize() {
        val stages = mutableListOf<ClusterLayoutModel>()
        mount { stages += it }
        val canvas = compose.onNodeWithTag("clusterLayoutCanvas")
        canvas.revealClusterEditorControl()
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val scale = minOf(bounds.width / model.key.panel.width, bounds.height / model.key.panel.height)
        // Explicit pointer deltas after crossing slop make the delivered delta deterministic.
        canvas.performTouchInput {
            down(center)
            moveBy(Offset(30f, 0f))
        }
        compose.waitForIdle()
        val first = stages.last()
        canvas.performTouchInput {
            moveBy(Offset(20f, 10f))
            up()
        }
        compose.runOnIdle {
            assertTrue(stages.isNotEmpty())
            val last = stages.last()
            assertEquals("scaled horizontal delta", 20f / scale, (last.safe.left - first.safe.left).toFloat(), 2f)
            assertEquals("scaled vertical delta", 10f / scale, (last.safe.top - first.safe.top).toFloat(), 2f)
            assertEquals(model.safe.width, last.safe.width)
        }
        compose.onNodeWithTag("clusterDragResize").revealClusterEditorControl().performClick()
        val previous = stages.last()
        stages.clear()
        canvas.revealClusterEditorControl().performTouchInput {
            down(center)
            moveBy(Offset(30f, 0f))
            moveBy(Offset(15f, 10f))
            up()
        }
        compose.runOnIdle {
            assertTrue(stages.isNotEmpty())
            assertTrue(stages.last().safe.width > previous.safe.width)
            assertEquals(previous.safe.left, stages.last().safe.left)
            assertTrue(stages.last().output.contains(stages.last().safe))
        }
    }
    @Test @Config(qualifiers = "w1457dp-h567dp-land-xhdpi")
    fun loggedVehiclePixelViewportRendersAtExplicitReferenceDensity() {
        // Logs establish 2914×1134 px, not Android UI density. 320dpi is a fixture,
        // not an asserted vehicle measurement; the 1000×360dp test stresses less room.
        mount()
        compose.runOnIdle {
            assertEquals(2914, requireNotNull(root).width)
            assertEquals(1134, requireNotNull(root).height)
        }
        capture("vehicle-pixels-reference-320dpi")
        listOf("clusterLayoutCanvas", "clusterEditorBack", "clusterResetAutomatic", "clusterPreviewStart",
            "clusterNudge_SAFE_H_plus").forEach { fullyVisible(it) }
    }
    @Test @Config(qualifiers = "w840dp-h300dp-land-mdpi")
    fun extraShortViewportScrollsOnlyControlsAndKeepsActionsPinned() {
        mount()
        capture("extra-short-initial")
        compose.onNodeWithTag("clusterNudge_SAFE_H_plus").revealClusterEditorControl().assertIsDisplayed()
        listOf("clusterLayoutCanvas", "clusterEditorBack", "clusterResetAutomatic", "clusterPreviewStart")
            .forEach { fullyVisible(it) }
        capture("extra-short-controls")
    }
    @Test @Config(qualifiers = "w360dp-h720dp-port-mdpi")
    fun narrowViewportKeepsPreviewBoundedAndControlsReachable() {
        mount()
        capture("narrow")
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val canvas = compose.onNodeWithTag("clusterLayoutCanvas").fetchSemanticsNode().boundsInRoot
        assertTrue(canvas.height <= viewport.height * .5f)
        compose.onNodeWithTag("clusterNudge_SAFE_H_plus").revealClusterEditorControl().assertIsDisplayed()
        compose.onNodeWithTag("clusterPreviewStart").revealClusterEditorControl().assertIsDisplayed()
        compose.onNodeWithTag("clusterEditorBack").revealClusterEditorControl().assertIsDisplayed()
    }
    private fun fullyVisible(tag: String): androidx.compose.ui.geometry.Rect {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val viewport = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("$tag must be fully in viewport: $node / $viewport", node.left >= viewport.left &&
            node.top >= viewport.top && node.right <= viewport.right && node.bottom <= viewport.bottom)
        return node
    }
    private fun capture(name: String) {
        val tags = listOf("clusterLayoutCanvas", "clusterEditorBack", "clusterDragMove", "clusterDragResize",
            "clusterResetAutomatic", "clusterPreviewStart") + listOf("X", "Y", "W", "H").flatMap { axis ->
                listOf("minus", "plus").map { "clusterNudge_SAFE_${axis}_$it" }
            }
        val geometry = tags.joinToString("\n") { tag ->
            val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
            "$tag: bounds=${node.boundsInRoot}; layout=${node.layoutInfo.width}x${node.layoutInfo.height}"
        }
        compose.runOnIdle {
            val view = requireNotNull(root)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File(requireNotNull(System.getProperty("cluster.editor.render.dir", "build/editor-renders")),
                "sdk-${org.robolectric.RuntimeEnvironment.getApiLevel()}").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(dir, "$name-viewport.txt").writeText("root=${view.width}x${view.height}; density=${view.resources.displayMetrics.density}\n$geometry\n")
        }
    }
}
