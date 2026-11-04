package com.openautolink.app.cluster

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import com.openautolink.app.secondary.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class SecondaryVideoRendererTest {
    private fun mapping(id: String, epoch: Long = 1) = SecondaryVideoMapping.androidAuto(
        SecondaryRenderTarget(SecondaryOutputId(id), epoch, if (id == "a") 42 else 43,
            PixelSize(2000, 810), PixelRect(0, 0, 2000, 810), PixelRect(500, 100, 1500, 700)))
    private fun config(size: PixelSize = PixelSize(1280, 720)) = ClusterCodecConfig(size, byteArrayOf(1), byteArrayOf(2))
    private fun frame(epoch: Long, pts: Long = 1) = ClusterEncodedFrame(epoch, byteArrayOf(0, 0, 1, 0x65), pts, true)
    private fun fence(renderer: ClusterVideoRenderer) { assertTrue(renderer.barrier().get(3, TimeUnit.SECONDS)) }
    private fun eventually(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(predicate())
    }
    private inline fun surfaces(block: (Surface, Surface) -> Unit) {
        val ta = SurfaceTexture(1); val tb = SurfaceTexture(2)
        val a = Surface(ta); val b = Surface(tb)
        try { block(a, b) } finally { a.release(); b.release(); ta.release(); tb.release() }
    }
    private class Driver : ClusterRenderDriver {
        val configs = CopyOnWriteArrayList<ClusterCodecConfig>()
        val frames = CopyOnWriteArrayList<ClusterEncodedFrame>()
        val eof = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        var gate: CountDownLatch? = null
        var entered: CountDownLatch? = null
        var failClear = false
        override fun configure(config: ClusterCodecConfig) {
            entered?.countDown(); gate?.await(3, TimeUnit.SECONDS); configs += config
        }
        override fun submit(frame: ClusterEncodedFrame): Boolean { frames += frame; return true }
        override fun poll(): ClusterRenderProgress {
            check(!eof.get()) { "injected decoder EOF" }
            return ClusterRenderProgress(0, 0)
        }
        override fun stopDecoder() { stopped.set(true) }
        override fun clearOutput() { check(!failClear) { "injected black swap failure" } }
        override fun detach() = true
    }

    @Test fun independentRenderersKeepConfigurationEpochQueueAndRetirementLocal() = surfaces { sa, sb ->
        val da = Driver(); val db = Driver()
        val a = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> da })
        val b = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> db })
        try {
            val ma = mapping("a"); val mb = mapping("b")
            assertTrue(a.attach(sa, ma, 1).ready.get(3, TimeUnit.SECONDS))
            assertTrue(b.attach(sb, mb, 1).ready.get(3, TimeUnit.SECONDS))
            val ca = config(); val cb = config()
            assertTrue(a.configure(100, ca)); assertTrue(b.configure(1, cb)); fence(a); fence(b)
            assertSame(ca, da.configs.single()); assertSame(cb, db.configs.single())
            assertFalse(b.offer(frame(100))) // a's newer epoch has no authority over b
            assertTrue(a.offer(frame(100))); assertTrue(b.offer(frame(1))); fence(a); fence(b)
            assertEquals(100L, da.frames.single().streamEpoch); assertEquals(1L, db.frames.single().streamEpoch)
            val entered = CountDownLatch(1); val gate = CountDownLatch(1)
            da.entered = entered; da.gate = gate
            assertTrue(a.configure(101, config())); assertTrue(entered.await(3, TimeUnit.SECONDS))
            repeat(ClusterVideoRenderer.MAX_FRAMES * 3) { a.offer(frame(101, it.toLong())) }
            assertTrue(b.offer(frame(1, 2))); fence(b)
            assertEquals(2, db.frames.size); assertEquals(0L, b.stats().errors)
            assertTrue(a.stats().dropped > 0)
            val retiredA = a.retire(); gate.countDown()
            assertTrue(retiredA.get(3, TimeUnit.SECONDS))
            assertTrue(b.offer(frame(1, 3))); fence(b)
            assertEquals(3, db.frames.size); assertFalse(db.stopped.get())
            assertTrue(sa.isValid && sb.isValid)
        } finally { a.retire().get(3, TimeUnit.SECONDS); b.retire().get(3, TimeUnit.SECONDS) }
    }

    @Test fun eofAndFailedBlackClearDebtOnADoNotStopB() = surfaces { sa, sb ->
        val da = Driver(); val db = Driver()
        val a = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> da })
        val b = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> db })
        try {
            val ha = a.attach(sa, mapping("a"), 1)
            assertTrue(ha.ready.get(3, TimeUnit.SECONDS))
            assertTrue(b.attach(sb, mapping("b"), 1).ready.get(3, TimeUnit.SECONDS))
            assertTrue(a.configure(1, config())); assertTrue(b.configure(1, config())); fence(a); fence(b)
            da.failClear = true; da.eof.set(true)
            eventually { a.stats().errors > 0 }; fence(a)
            assertFalse(a.detach(ha).get(3, TimeUnit.SECONDS))
            assertEquals("illegal_state", a.lastError)
            assertTrue(b.offer(frame(1))); fence(b)
            assertEquals(1, db.frames.size); assertEquals(0L, b.stats().errors)
            da.failClear = false
            assertTrue(a.retire().get(3, TimeUnit.SECONDS))
            assertTrue(b.offer(frame(1, 2))); fence(b); assertEquals(2, db.frames.size)
        } finally { da.failClear = false; a.retire().get(3, TimeUnit.SECONDS); b.retire().get(3, TimeUnit.SECONDS) }
    }

    @Test fun directBridgeRequiresExactFrozenTargetAndNamedDecoderAndRetiresOnlyItsLease() = surfaces { sa, sb ->
        val da = Driver(); val db = Driver()
        val ma = mapping("a"); val mb = mapping("b")
        val a = ClusterProjectionOutput(ma, ClusterRenderDriverFactory { _, _ -> da }, "qualified.avc")
        val b = ClusterProjectionOutput(mb, ClusterRenderDriverFactory { _, _ -> db }, "qualified.avc")
        val la = ClusterOutputLease(5, 1); val lb = ClusterOutputLease(5, 2)
        try {
            assertTrue(a.bindLease(la)); assertTrue(b.bindLease(lb))
            assertFalse(a.attach(la, sa, ma.target.copy(attachmentEpoch = 2)).get())
            assertFalse(a.attach(la, sa, ma.target.copy(displayId = 99)).get())
            assertFalse(a.attach(la, sa, ma.target.copy(panel = PixelSize(2001, 810))).get())
            assertFalse(a.attach(la, sa, ma.target.copy(output = PixelRect(1, 0, 2000, 810))).get())
            assertTrue(a.attach(la, sa, ma.target).get(3, TimeUnit.SECONDS))
            assertTrue(b.attach(lb, sb, mb.target).get(3, TimeUnit.SECONDS))
            val streamA = a.beginStream(la, Any()) { true }!!
            val streamB = b.beginStream(lb, Any()) { true }!!
            assertFalse(a.configure(la, streamA, ClusterCodecConfig(ma.codedSize, byteArrayOf(1), byteArrayOf(2), "unqualified")))
            assertTrue(a.configure(la, streamA, config())); assertTrue(b.configure(lb, streamB, config()))
            assertTrue(a.frame(la, streamA, frame(streamA.epoch)))
            assertTrue(b.frame(lb, streamB, frame(streamB.epoch)))
            eventually { da.frames.size == 1 && db.frames.size == 1 }
            assertEquals("qualified.avc", da.configs.single().decoderName)
            assertEquals("qualified.avc", db.configs.single().decoderName)
            assertFalse(a.updateBounds(la, sa, ma.target.copy(attachmentEpoch = 2)).get(3, TimeUnit.SECONDS))
            assertFalse(a.status.value.hostAttached)
            assertTrue(b.status.value.hostAttached)
            assertTrue(a.retire(la).get(3, TimeUnit.SECONDS))
            assertTrue(b.frame(lb, streamB, frame(streamB.epoch, 2)))
            eventually { db.frames.size == 2 }
            assertFalse(db.stopped.get())
            assertEquals(1L, a.status.value.received); assertEquals(2L, b.status.value.received)
        } finally { a.retire(la).get(3, TimeUnit.SECONDS); b.retire(lb).get(3, TimeUnit.SECONDS) }
    }

    @Test fun gmBridgeStillRequalifiesExactValidatedLayoutHost() = surfaces { sa, _ ->
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val host = ClusterHostGeometry(9, profile.panel, PixelRect(0, 135, 2000, 810), PixelRect(0, 135, 2000, 810))
        val layout = ClusterValidatedLayout(profile, host, host.visible, PixelRect(150, 150, 1850, 780),
            PixelRect(650, 200, 1350, 650))
        val qualified = ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role, 9)), host, validatedLayout = layout)!!
        val gm = ClusterVideoGeometry.androidAuto(qualified)
        val output = ClusterProjectionOutput(gm, ClusterRenderDriverFactory { _, _ -> Driver() })
        val lease = ClusterOutputLease(6, 8)
        try {
            assertSame(layout, gm.gmReceipt.validatedLayout)
            assertTrue(output.bindLease(lease))
            assertFalse(output.attach(lease, sa, host.copy(displayId = 10)).get())
            assertFalse(output.attach(lease, sa, host.copy(visible = PixelRect(0, 136, 2000, 810))).get())
            assertTrue(output.attach(lease, sa, host).get(3, TimeUnit.SECONDS))
            assertTrue(output.status.value.hostAttached)
        } finally { assertTrue(output.retire(lease).get(3, TimeUnit.SECONDS)) }
    }

    @Test @Config(shadows = [ClusterVideoRendererTest.RecordingEgl::class, ClusterVideoRendererTest.RecordingGl::class])
    fun actualDriverAcceptsPublicGeometryAndClearsFullMeasuredCanvasOpaque() = surfaces { sa, _ ->
        for ((name, type) in listOf("EGL_NO_DISPLAY" to android.opengl.EGLDisplay::class.java,
            "EGL_NO_CONTEXT" to android.opengl.EGLContext::class.java, "EGL_NO_SURFACE" to android.opengl.EGLSurface::class.java)) {
            val constructor = type.getDeclaredConstructor(java.lang.Long.TYPE).apply { isAccessible = true }
            org.robolectric.util.ReflectionHelpers.setStaticField(android.opengl.EGL14::class.java, name, constructor.newInstance(0L))
        }
        ClusterVideoRendererTest.graphicsCalls.clear(); ClusterVideoRendererTest.failBlackSwap = false
        val renderer = ClusterVideoRenderer()
        try {
            val handle = renderer.attach(sa, mapping("a"), 1)
            assertTrue("actual driver: ${renderer.lastError}", handle.ready.get(3, TimeUnit.SECONDS))
            val calls = ClusterVideoRendererTest.graphicsCalls
            assertTrue(calls.contains("scissor:0,0,2000,810"))
            assertTrue(calls.contains("clear:0.0,0.0,0.0,1.0"))
            assertFalse(calls.contains("scissor:98,65,1806,680"))
            assertTrue(renderer.detach(handle).get(3, TimeUnit.SECONDS))
            assertTrue(calls.indexOf("swap-black") < calls.indexOf("unbind"))
            assertTrue(sa.isValid)
        } finally { assertTrue(renderer.retire().get(3, TimeUnit.SECONDS)) }
    }
}
