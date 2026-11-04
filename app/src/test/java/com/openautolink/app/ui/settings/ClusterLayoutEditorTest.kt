package com.openautolink.app.ui.settings

import com.openautolink.app.cluster.*
import org.junit.Assert.*
import org.junit.Test

class ClusterLayoutEditorTest {
    private val key = ClusterLayoutKey(ClusterVehicleIdentity("fixture", 32, "fixture", 1), ClusterVehicleRole.DRIVER_CLUSTER, PixelSize(1000, 400))
    private fun model() = ClusterLayoutModel(key, PixelRect(0, 20, 1000, 400), PixelRect(50, 40, 950, 380), PixelRect(300, 100, 700, 300))

    @Test fun editorRejectsSafeOutsideOutputAndCanvasOutsidePanel() {
        assertThrows(IllegalArgumentException::class.java) { model().copy(safe = PixelRect(0, 0, 800, 300)) }
        assertThrows(IllegalArgumentException::class.java) { model().copy(canvas = PixelRect(0, 0, 1001, 400)) }
        assertThrows(IllegalArgumentException::class.java) {
            key.copy(role = ClusterVehicleRole.CENTER)
        }
    }

    @Test fun dragAndResizeAreClampedWithoutLosingSafeContent() {
        var edited = model()
        for (target in ClusterLayoutTarget.entries) {
            for (v in listOf(-100000, -1, 0, 1, 100000)) {
                edited = edited.adjust(target, x = v, y = v, width = v, height = v)
                assertTrue(edited.canvas.contains(edited.output))
                assertTrue(edited.output.contains(edited.safe))
                assertTrue(edited.safe.width > 0 && edited.safe.height > 0)
            }
        }
        assertEquals(key, edited.key)
    }

    @Test fun customClusterEditDoesNotChangeAutomaticModel() {
        val automatic = model()
        val edited = automatic.adjust(ClusterLayoutTarget.SAFE, x = 320, width = 300)
        assertEquals(300, automatic.safe.left)
        assertEquals(320, edited.safe.left)
        assertEquals(300, edited.safe.width)
        assertEquals(automatic.output, edited.output)
    }
}
