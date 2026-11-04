package com.openautolink.app.cluster

import android.app.Application
import android.graphics.SurfaceTexture
import android.opengl.*
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowEGL14
import org.robolectric.shadows.ShadowGLES20
import org.robolectric.util.ReflectionHelpers

/** Actual driver/renderer, with unavailable native-window behavior enforced at the OS boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class,
    shadows = [ClusterDestroyedSurfaceRetirementTest.LostWindowEgl::class,
        ClusterDestroyedSurfaceRetirementTest.LostWindowGl::class])
class ClusterDestroyedSurfaceRetirementTest {
    companion object {
        @Volatile var lost = false
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    }
    @Suppress("ACCIDENTAL_OVERRIDE")
    @Implements(EGL14::class)
    class LostWindowEgl : ShadowEGL14() {
        companion object {
            @JvmStatic @Implementation fun eglQuerySurface(d: EGLDisplay, s: EGLSurface, a: Int, o: IntArray, offset: Int): Boolean {
                o[offset] = if (a == EGL14.EGL_WIDTH) 2000 else 810; return !lost
            }
            @JvmStatic @Implementation fun eglMakeCurrent(d: EGLDisplay, draw: EGLSurface, read: EGLSurface, c: EGLContext): Boolean {
                calls += if (c == EGL14.EGL_NO_CONTEXT) "unbind" else "current"
                return c == EGL14.EGL_NO_CONTEXT || !lost
            }
            @JvmStatic @Implementation fun eglSwapBuffers(d: EGLDisplay, s: EGLSurface): Boolean { calls += "swap"; return !lost }
            @JvmStatic @Implementation fun eglGetError(): Int = if (lost) EGL14.EGL_BAD_SURFACE else EGL14.EGL_SUCCESS
            @JvmStatic @Implementation fun eglDestroySurface(d: EGLDisplay, s: EGLSurface): Boolean { calls += "destroy-window"; return true }
            @JvmStatic @Implementation fun eglDestroyContext(d: EGLDisplay, c: EGLContext): Boolean { calls += "destroy-context"; return true }
            @JvmStatic @Implementation fun eglTerminate(d: EGLDisplay): Boolean { calls += "terminate"; return true }
        }
    }
    @Implements(GLES20::class)
    class LostWindowGl : ShadowGLES20() {
        companion object {
            @JvmStatic @Implementation fun glClear(mask: Int) { calls += "clear" }
            @JvmStatic @Implementation fun glFinish() { calls += "finish" }
        }
    }
    private fun handles() {
        for ((name, type) in listOf("EGL_NO_DISPLAY" to EGLDisplay::class.java,
            "EGL_NO_CONTEXT" to EGLContext::class.java, "EGL_NO_SURFACE" to EGLSurface::class.java)) {
            val constructor = type.getDeclaredConstructor(java.lang.Long.TYPE).apply { isAccessible = true }
            ReflectionHelpers.setStaticField(EGL14::class.java, name, constructor.newInstance(0L))
        }
    }
    private fun mapping(): ClusterVideoMapping {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        return ClusterVideoGeometry.androidAuto(requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
            ClusterHostGeometry(7, p.panel, p.panel.bounds, p.panel.bounds))))
    }
    @Test fun destroyedHostDuringHeldStopReleasesActualEglBeforeQueuedSuccessor() {
        handles(); lost = false; calls.clear()
        val textureA = SurfaceTexture(0); val a = Surface(textureA)
        val textureB = SurfaceTexture(0); val b = Surface(textureB)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var opens = 0
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { host, map ->
            val actual = ClusterEglVideoDriver(host, map)
            opens++
            if (opens == 1) object : ClusterRenderDriver by actual {
                override fun stopDecoder() {
                    entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); actual.stopDecoder()
                }
            } else { calls += "successor-open"; lost = false; actual }
        })
        try {
            val first = renderer.attach(a, mapping(), 1)
            assertTrue(first.ready.get(2, TimeUnit.SECONDS))
            calls.clear()
            val detached = renderer.detach(first)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            a.release(); assertFalse(a.isValid); lost = true
            val successor = renderer.attach(b, mapping(), 2)
            assertFalse(detached.isDone); assertFalse(successor.ready.isDone)
            release.countDown()
            assertTrue("OS-destroyed window must retire actual EGL resources", detached.get(2, TimeUnit.SECONDS))
            assertTrue(successor.ready.get(2, TimeUnit.SECONDS))
            val predecessor = calls.takeWhile { it != "successor-open" }
            assertTrue(predecessor.contains("destroy-window")); assertTrue(predecessor.contains("destroy-context"))
            assertFalse("never draw into a withdrawn OS window: $predecessor", predecessor.contains("clear"))
            assertTrue(b.isValid)
        } finally {
            lost = false; release.countDown(); renderer.retire().get(2, TimeUnit.SECONDS)
            a.release(); b.release(); textureA.release(); textureB.release()
        }
    }
}
