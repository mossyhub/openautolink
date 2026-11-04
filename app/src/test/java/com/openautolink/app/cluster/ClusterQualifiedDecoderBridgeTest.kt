package com.openautolink.app.cluster

import android.app.Application
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.Surface
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaCodec
import org.robolectric.util.ReflectionHelpers

/** Actual bridge -> renderer worker -> MediaCodec factory, including in-band CSD replacement. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class,
    shadows = [ClusterVideoRendererTest.RecordingEgl::class, ClusterVideoRendererTest.RecordingGl::class,
        ClusterVideoRendererTest.RecordingTexture::class, ClusterQualifiedDecoderTest.RecordingCodec::class])
class ClusterQualifiedDecoderBridgeTest {
    private fun mapping(): ClusterVideoMapping {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        return ClusterVideoGeometry.carPlay(requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
            ClusterHostGeometry(7, p.panel, p.panel.bounds, p.panel.bounds))))
    }
    private fun config(name: String?) = ClusterCodecConfig(mapping().codedSize,
        byteArrayOf(0, 0, 0, 1, 0x67, 1), byteArrayOf(0, 0, 0, 1, 0x68, 2), name)
    private fun withSurface(block: (Surface) -> Unit) {
        for ((name, type) in listOf("EGL_NO_DISPLAY" to EGLDisplay::class.java,
            "EGL_NO_CONTEXT" to EGLContext::class.java, "EGL_NO_SURFACE" to EGLSurface::class.java)) {
            val constructor = type.getDeclaredConstructor(java.lang.Long.TYPE).apply { isAccessible = true }
            ReflectionHelpers.setStaticField(EGL14::class.java, name, constructor.newInstance(0L))
        }
        ClusterVideoRendererTest.failBlackSwap = false
        ClusterVideoRendererTest.pendingVideo = false
        ClusterVideoRendererTest.graphicsCalls.clear()
        ClusterQualifiedDecoderTest.factories.clear(); ClusterQualifiedDecoderTest.rejectName = null
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        try { block(surface) }
        finally { surface.release(); texture.release(); ShadowMediaCodec.clearCodecs() }
    }
    private fun awaitFactories(count: Int) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (ClusterQualifiedDecoderTest.factories.size < count && System.nanoTime() < end) Thread.sleep(2)
        assertEquals(count, ClusterQualifiedDecoderTest.factories.size)
    }

    @Test fun inBandReconfigurationKeepsExactFrozenDecoderAtActualFactory() = withSurface { surface ->
        val name = "c2.frozen.secondary.avc"
        val mapping = mapping()
        val bridge = ClusterProjectionOutput(mapping, avcDecoderName = name)
        val lease = ClusterOutputLease(55, 66)
        assertTrue(bridge.bindLease(lease))
        try {
            val source = bridge.beginStream(lease, Any()) { true }!!
            assertTrue(bridge.configure(lease, source, config(name)))
            assertTrue(bridge.attach(lease, surface, mapping.qualifiedDisplay.host).get(2, TimeUnit.SECONDS))
            awaitFactories(1)
            // A new in-band SPS/PPS reconstructs the immutable config without dropping its receipt.
            assertTrue(bridge.annexB(lease, source,
                byteArrayOf(0,0,0,1,0x67,3,0,0,0,1,0x68,4), 1))
            awaitFactories(2)
            assertEquals(listOf("name:$name", "name:$name"), ClusterQualifiedDecoderTest.factories)
            assertNull(bridge.status.value.error)
        } finally { assertTrue(bridge.retire(lease).get(2, TimeUnit.SECONDS)) }
    }

    @Test fun nameOnlyConfigChangeRecreatesActualDecoderInsteadOfReusingOldIdentity() = withSurface { surface ->
        val mapping = mapping()
        val bridge = ClusterProjectionOutput(mapping)
        val lease = ClusterOutputLease(77, 88)
        assertTrue(bridge.bindLease(lease))
        try {
            val source = bridge.beginStream(lease, Any()) { true }!!
            assertTrue(bridge.configure(lease, source, config("c2.first.avc")))
            assertTrue(bridge.attach(lease, surface, mapping.qualifiedDisplay.host).get(2, TimeUnit.SECONDS))
            awaitFactories(1)
            assertTrue(bridge.configure(lease, source, config("c2.second.avc")))
            awaitFactories(2)
            assertEquals(listOf("name:c2.first.avc", "name:c2.second.avc"), ClusterQualifiedDecoderTest.factories)
        } finally { assertTrue(bridge.retire(lease).get(2, TimeUnit.SECONDS)) }
    }
}
