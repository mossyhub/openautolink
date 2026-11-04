package com.openautolink.app.cluster

import android.app.Application
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from

/** Executes production MediaCodec factory calls, not AVC decoding or physical GPU qualification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class,
    shadows = [ClusterVideoRendererTest.RecordingEgl::class, ClusterVideoRendererTest.RecordingGl::class,
        ClusterVideoRendererTest.RecordingTexture::class, ClusterQualifiedDecoderTest.RecordingCodec::class])
class ClusterQualifiedDecoderTest {
    companion object {
        val factories = CopyOnWriteArrayList<String>()
        @Volatile var rejectName: String? = null
    }

    @Implements(MediaCodec::class)
    class RecordingCodec : ShadowMediaCodec() {
        companion object {
            private fun construct(name: String, byType: Boolean): MediaCodec = ReflectionHelpers.callConstructor(
                MediaCodec::class.java, from(String::class.java, name),
                from(Boolean::class.javaPrimitiveType!!, byType), from(Boolean::class.javaPrimitiveType!!, false))
            @JvmStatic @Implementation fun createByCodecName(name: String): MediaCodec {
                factories += "name:$name"
                if (name == rejectName) throw java.io.IOException("qualified decoder unavailable")
                return construct(name, false)
            }
            @JvmStatic @Implementation fun createDecoderByType(type: String): MediaCodec {
                factories += "type:$type"
                return construct(type, true)
            }
        }
    }

    private fun mapping(): ClusterVideoMapping {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        return ClusterVideoGeometry.carPlay(requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
            ClusterHostGeometry(7, p.panel, p.panel.bounds, p.panel.bounds))))
    }
    private fun config(name: String?) = ClusterCodecConfig(mapping().codedSize,
        byteArrayOf(0, 0, 0, 1, 0x67, 1), byteArrayOf(0, 0, 0, 1, 0x68, 2), name)
    private fun <T> withSurface(block: (Surface) -> T): T {
        for ((name, type) in listOf("EGL_NO_DISPLAY" to EGLDisplay::class.java,
            "EGL_NO_CONTEXT" to EGLContext::class.java, "EGL_NO_SURFACE" to EGLSurface::class.java)) {
            val constructor = type.getDeclaredConstructor(java.lang.Long.TYPE).apply { isAccessible = true }
            ReflectionHelpers.setStaticField(EGL14::class.java, name, constructor.newInstance(0L))
        }
        ClusterVideoRendererTest.failBlackSwap = false
        ClusterVideoRendererTest.pendingVideo = false
        ClusterVideoRendererTest.graphicsCalls.clear()
        factories.clear(); rejectName = null
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        try { return block(surface) }
        finally { rejectName = null; surface.release(); texture.release(); ShadowMediaCodec.clearCodecs() }
    }

    @Test fun actualFactoryUsesExactNameForEveryConfigureAndNameOnlyReplacement() = withSurface { surface ->
        val driver = ClusterEglVideoDriver(surface, mapping())
        try {
            driver.initialize()
            driver.configure(config("c2.qualified.avc"))
            driver.configure(config("c2.qualified.avc"))
            driver.configure(config("c2.successor.avc"))
            assertEquals(listOf("name:c2.qualified.avc", "name:c2.qualified.avc", "name:c2.successor.avc"), factories)
        } finally { assertTrue(driver.detach()) }
    }

    @Test fun omittedDecoderIdentityKeepsAndroidAutoTypeFactory() = withSurface { surface ->
        val driver = ClusterEglVideoDriver(surface, mapping())
        try {
            driver.initialize(); driver.configure(config(null))
            assertEquals(listOf("type:video/avc"), factories)
        } finally { assertTrue(driver.detach()) }
    }

    @Test fun unavailableQualifiedDecoderFailsLocallyWithoutTypeFallback() = withSurface { surface ->
        rejectName = "c2.unavailable.avc"
        val renderer = ClusterVideoRenderer()
        try {
            assertTrue(renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS))
            assertTrue(renderer.configure(1, config(rejectName)))
            assertTrue(renderer.barrier().get(2, TimeUnit.SECONDS))
            assertEquals(listOf("name:c2.unavailable.avc"), factories)
            assertEquals("io_exception", renderer.lastError)
            assertEquals(1L, renderer.stats().errors)
            assertEquals(0L, renderer.stats().decoded)
            assertEquals(0L, renderer.stats().presented)
            assertTrue(surface.isValid)
        } finally { assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }
}
