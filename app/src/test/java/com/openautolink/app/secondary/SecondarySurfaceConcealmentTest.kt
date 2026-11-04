package com.openautolink.app.secondary

import android.app.Application
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class SecondarySurfaceConcealmentTest {
    @Test fun concealmentCoversStaleImageWithoutDestroyingRendererWindow() {
        val controller = Robolectric.buildActivity(SecondaryProjectionActivity::class.java).create()
        val activity = controller.get()
        val root = FrameLayout(activity)
        val surface = SurfaceView(activity)
        root.addView(surface)
        activity.setContentView(root)
        ReflectionHelpers.setField(activity, "surfaceView", surface)
        try {
            activity.concealSurface()
            assertEquals("INVISIBLE destroys the Surface before the worker can clear it", View.VISIBLE, surface.visibility)
            val cover = root.getChildAt(root.childCount - 1)
            assertNotSame("a separate opaque cover must hide old imagery", surface, cover)
            assertEquals(Color.BLACK, (cover.background as ColorDrawable).color)
            assertFalse(cover.isClickable)
            activity.concealSurface()
            assertEquals("withdrawal is idempotent", 2, root.childCount)
        } finally { controller.destroy() }
    }
}
