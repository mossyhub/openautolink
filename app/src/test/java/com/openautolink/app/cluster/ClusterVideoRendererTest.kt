package com.openautolink.app.cluster

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import android.opengl.EGL14
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.EGLContext
import android.opengl.GLES20
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowEGL14
import org.robolectric.shadows.ShadowGLES20
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class ClusterVideoRendererTest {
    companion object {
        val graphicsCalls = CopyOnWriteArrayList<String>()
        val textureListeners = CopyOnWriteArrayList<Pair<SurfaceTexture, SurfaceTexture.OnFrameAvailableListener>>()
        val textureHandlers = CopyOnWriteArrayList<android.os.Handler?>()
        @Volatile var failBlackSwap = false
        @Volatile var pendingVideo = false
    }
    @Implements(SurfaceTexture::class)
    class RecordingTexture : org.robolectric.shadows.ShadowSurfaceTexture() {
        @org.robolectric.annotation.RealObject lateinit var texture: SurfaceTexture
        @Implementation fun setOnFrameAvailableListener(listener: SurfaceTexture.OnFrameAvailableListener?) {
            if (listener != null) { textureListeners += texture to listener; textureHandlers += null }
        }
        @Implementation fun setOnFrameAvailableListener(listener: SurfaceTexture.OnFrameAvailableListener?, handler: android.os.Handler?) {
            if (listener != null) { textureListeners += texture to listener; textureHandlers += handler }
        }
        @Implementation fun updateTexImage() { graphicsCalls += "latch-texture" }
        @Implementation fun getTransformMatrix(out: FloatArray) {
            out.fill(0f); for (i in 0..3) out[i * 5] = 1f
        }
    }
    @Suppress("ACCIDENTAL_OVERRIDE")
    @Implements(EGL14::class)
    class RecordingEgl : ShadowEGL14() {
        companion object {
            @JvmStatic @Implementation fun eglQuerySurface(display: EGLDisplay, surface: EGLSurface, attribute: Int, out: IntArray, offset: Int): Boolean {
                out[offset] = if (attribute == EGL14.EGL_WIDTH) 2000 else 810; return true
            }
            @JvmStatic @Implementation fun eglMakeCurrent(display: EGLDisplay, draw: EGLSurface, read: EGLSurface, context: EGLContext): Boolean {
                graphicsCalls += if (context == EGL14.EGL_NO_CONTEXT) "unbind" else "current"; return true
            }
            @JvmStatic @Implementation fun eglSwapBuffers(display: EGLDisplay, surface: EGLSurface): Boolean {
                val video = pendingVideo
                val ok = video || !failBlackSwap
                graphicsCalls += if (!ok) "swap-failed" else if (video) "swap-video" else "swap-black"
                return ok
            }
            @JvmStatic @Implementation fun eglDestroySurface(display: EGLDisplay, surface: EGLSurface): Boolean { graphicsCalls += "destroy-window"; return true }
            @JvmStatic @Implementation fun eglDestroyContext(display: EGLDisplay, context: EGLContext): Boolean { graphicsCalls += "destroy-context"; return true }
            @JvmStatic @Implementation fun eglTerminate(display: EGLDisplay): Boolean { graphicsCalls += "terminate"; return true }
        }
    }
    @Implements(GLES20::class)
    class RecordingGl : ShadowGLES20() {
        companion object {
            @JvmStatic @Implementation fun glScissor(x: Int, y: Int, width: Int, height: Int) { graphicsCalls += "scissor:$x,$y,$width,$height" }
            @JvmStatic @Implementation fun glClearColor(r: Float, g: Float, b: Float, a: Float) { graphicsCalls += "clear:$r,$g,$b,$a" }
            @JvmStatic @Implementation fun glClear(mask: Int) { pendingVideo = false; graphicsCalls += "clear-buffer" }
            @JvmStatic @Implementation fun glDrawArrays(mode: Int, first: Int, count: Int) { pendingVideo = true; graphicsCalls += "draw:$count" }
            @JvmStatic @Implementation fun glFinish() { graphicsCalls += "finish" }
        }
    }
    private fun initializeShadowEglHandles() {
        // Robolectric's EGL native initializer has no handles; model the platform's zero handles.
        for ((name, type) in listOf("EGL_NO_DISPLAY" to EGLDisplay::class.java,
            "EGL_NO_CONTEXT" to EGLContext::class.java, "EGL_NO_SURFACE" to EGLSurface::class.java)) {
            val constructor = type.getDeclaredConstructor(java.lang.Long.TYPE).apply { isAccessible = true }
            org.robolectric.util.ReflectionHelpers.setStaticField(EGL14::class.java, name, constructor.newInstance(0L))
        }
    }
    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class, RecordingTexture::class])
    fun actualDriverUsesInternalDecodeSurfaceAndIgnoresRetiredTextureCallbacks() = withSurface { surface ->
        initializeShadowEglHandles(); graphicsCalls.clear(); textureListeners.clear(); textureHandlers.clear()
        // This is a deterministic codec OS boundary, not a physical AVC decode fixture.
        org.robolectric.shadows.ShadowMediaCodec.addDecoder("video/avc",
            org.robolectric.shadows.ShadowMediaCodec.CodecConfig(1024, 1024) { input, output -> output.put(input) })
        val driver = ClusterEglVideoDriver(surface, mapping())
        try {
            driver.initialize(); driver.configure(config())
            assertTrue(driver.submit(frame(1, 1)))
            assertEquals(1L, driver.poll().decoded)
            assertEquals(1, textureListeners.size)
            assertNotNull("SurfaceTexture must not default to Main delivery", textureHandlers.single())
            assertNotSame(android.os.Looper.getMainLooper(), textureHandlers.single()!!.looper)
            val old = textureListeners.single()
            driver.configure(config())
            assertEquals(2, textureListeners.size)
            old.second.onFrameAvailable(old.first)
            assertEquals(0L, driver.poll().presented)
            val current = textureListeners.last()
            current.second.onFrameAvailable(current.first)
            assertEquals(1L, driver.poll().presented)
            assertTrue(graphicsCalls.contains("latch-texture"))
            assertTrue(graphicsCalls.contains("draw:4"))
            assertNotSame(surface, current.first)
            graphicsCalls.clear()
            assertTrue(driver.detach())
            assertTrue("direct driver detach must swap black before unbind: $graphicsCalls",
                graphicsCalls.indexOf("swap-black") >= 0 && graphicsCalls.indexOf("swap-black") < graphicsCalls.indexOf("unbind"))
            assertTrue(surface.isValid)
        } finally { driver.detach(); org.robolectric.shadows.ShadowMediaCodec.clearCodecs() }
    }
    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class])
    fun realEglDriverClearsOnlyOwnedOutputOpaqueBlackAndDetachesGpuBeforeAck() = withSurface { surface ->
        graphicsCalls.clear(); initializeShadowEglHandles()
        val renderer = ClusterVideoRenderer()
        try {
            val attached = renderer.attach(surface, mapping(), 1)
            val ready = attached.ready.get(2, TimeUnit.SECONDS)
            assertTrue("actual driver failed: ${renderer.lastError}; $graphicsCalls", ready)
            assertTrue(graphicsCalls.contains("scissor:98,65,1806,680"))
            assertTrue(graphicsCalls.contains("clear:0.0,0.0,0.0,1.0"))
            assertTrue(renderer.detach(attached).get(2, TimeUnit.SECONDS))
            val calls = graphicsCalls.toList()
            assertTrue(calls.indexOf("finish") < calls.indexOf("unbind"))
            assertTrue(calls.indexOf("unbind") < calls.indexOf("destroy-window"))
            assertTrue(calls.indexOf("destroy-window") < calls.indexOf("destroy-context"))
            assertTrue(surface.isValid)
        } finally { assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }
    private fun retirePreviouslyPresentedImage(mode: String, failClear: Boolean = false) = withSurface { surface ->
        initializeShadowEglHandles(); graphicsCalls.clear(); textureListeners.clear(); textureHandlers.clear()
        failBlackSwap = false; pendingVideo = false
        org.robolectric.shadows.ShadowMediaCodec.addDecoder("video/avc",
            org.robolectric.shadows.ShadowMediaCodec.CodecConfig(1024, 1024) { input, output -> output.put(input) })
        val fault = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { host, map ->
            val actual = ClusterEglVideoDriver(host, map)
            object : ClusterRenderDriver by actual {
                override fun configure(config: ClusterCodecConfig) {
                    check(fault.get() != "configure") { "injected configure failure" }; actual.configure(config)
                }
                override fun submit(frame: ClusterEncodedFrame): Boolean {
                    check(fault.get() != "submit") { "injected submit failure" }; return actual.submit(frame)
                }
                override fun poll(): ClusterRenderProgress {
                    check(fault.get() != "poll") { "injected poll failure" }; return actual.poll()
                }
            }
        })
        try {
            val attachment = renderer.attach(surface, mapping(), 1)
            assertTrue(attachment.ready.get(2, TimeUnit.SECONDS))
            assertTrue(renderer.configure(1, config())); assertTrue(renderer.offer(frame(1, 1)))
            fence(renderer)
            val listener = textureListeners.single()
            listener.second.onFrameAvailable(listener.first)
            eventually { renderer.stats().presented == 1L }
            assertTrue("fixture must present prior image", graphicsCalls.contains("swap-video"))
            graphicsCalls.clear(); failBlackSwap = failClear
            when (mode) {
                "detach" -> assertTrue(renderer.detach(attachment).get(2, TimeUnit.SECONDS))
                "retire" -> assertTrue(renderer.retire().get(2, TimeUnit.SECONDS))
                "dimensions" -> assertTrue(renderer.configure(2,
                    ClusterCodecConfig(PixelSize(640, 480), byteArrayOf(1), byteArrayOf(2))))
                "configure" -> { fault.set(mode); assertTrue(renderer.configure(2, config())) }
                "submit" -> { fault.set(mode); assertTrue(renderer.offer(frame(1, 2))) }
                "poll" -> fault.set(mode)
                else -> error("unknown mode")
            }
            if (mode != "detach" && mode != "retire") {
                eventually { renderer.stats().errors > 0 }
                fence(renderer) // Error publication alone is not completion of cleanup.
            }
            val calls = graphicsCalls.toList()
            if (failClear) {
                assertFalse("uncleared output must retain retirement debt", renderer.detach(attachment).get(2, TimeUnit.SECONDS))
                assertTrue("failed black swap must be observable: $calls", calls.contains("swap-failed"))
                assertFalse("must not disconnect uncleared borrowed output: $calls", calls.contains("unbind"))
                assertTrue(renderer.lastError!!.contains("swap"))
            } else {
                val black = calls.indexOf("swap-black")
                val unbind = calls.indexOf("unbind")
                assertTrue("must swap opaque black before disconnect: $calls", black >= 0 && unbind > black)
                assertTrue(calls.contains("clear:0.0,0.0,0.0,1.0"))
                assertTrue(calls.contains("scissor:98,65,1806,680"))
            }
            assertTrue("renderer never releases borrowed host", surface.isValid)
        } finally {
            failBlackSwap = false; fault.set(null)
            assertTrue(renderer.retire().get(2, TimeUnit.SECONDS))
            org.robolectric.shadows.ShadowMediaCodec.clearCodecs()
        }
    }

    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class, RecordingTexture::class])
    fun submitFailureBlacksPreviouslyPresentedImageBeforeDisconnect() = retirePreviouslyPresentedImage("submit")
    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class, RecordingTexture::class])
    fun pollFailureBlacksPreviouslyPresentedImageBeforeDisconnect() = retirePreviouslyPresentedImage("poll")
    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class, RecordingTexture::class])
    fun configurationFailureBlacksPreviouslyPresentedImageBeforeDisconnect() = retirePreviouslyPresentedImage("configure")
    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class, RecordingTexture::class])
    fun invalidConfigurationBlacksPreviouslyPresentedImageBeforeDisconnect() = retirePreviouslyPresentedImage("dimensions")
    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class, RecordingTexture::class])
    fun normalDetachBlacksPreviouslyPresentedImageBeforeDisconnect() = retirePreviouslyPresentedImage("detach")
    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class, RecordingTexture::class])
    fun normalRetirementBlacksPreviouslyPresentedImageBeforeDisconnect() = retirePreviouslyPresentedImage("retire")
    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class, RecordingTexture::class])
    fun failedBlackSwapRetainsExplicitRetirementDebt() = retirePreviouslyPresentedImage("submit", failClear = true)
    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class, RecordingTexture::class])
    fun failedBlackSwapAfterPollFailureRetainsRetirementDebt() = retirePreviouslyPresentedImage("poll", failClear = true)
    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class, RecordingTexture::class])
    fun failedBlackSwapAfterConfigurationFailureRetainsRetirementDebt() = retirePreviouslyPresentedImage("configure", failClear = true)

    @Test @Config(shadows = [RecordingEgl::class, RecordingGl::class, RecordingTexture::class])
    fun realRendererFailureIsPersistedWithoutWaitingForAnotherPacket() = withSurface { surface ->
        initializeShadowEglHandles(); failBlackSwap = true; pendingVideo = false
        val lines = CopyOnWriteArrayList<String>()
        val trace = OptionalOutputDiagnostics({ 0 }, { _, line -> lines += line })
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { host, map -> ClusterEglVideoDriver(host, map) }, trace)
        try {
            assertFalse(renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS))
            eventually { lines.any { "stage=failure" in it && "reason=egl_swap" in it } }
            assertEquals(0, renderer.stats().received)
        } finally { failBlackSwap = false; renderer.retire().get(2, TimeUnit.SECONDS) }
    }

    private fun mapping(): ClusterVideoMapping {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        return ClusterVideoGeometry.androidAuto(requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
            ClusterHostGeometry(7, p.panel, p.panel.bounds, p.panel.bounds))))
    }
    private class Driver : ClusterRenderDriver {
        val calls = CopyOnWriteArrayList<String>()
        var busy = 0
        var failSubmit = false
        var allowDetach = true
        var heldDetach: java.util.concurrent.CountDownLatch? = null
        var heldSubmit: java.util.concurrent.CountDownLatch? = null
        val submitEntered = java.util.concurrent.CountDownLatch(1)
        val detachEntered = java.util.concurrent.CountDownLatch(1)
        var progress = ClusterRenderProgress(0, 0)
        override fun configure(config: ClusterCodecConfig) { calls += "configure" }
        override fun submit(frame: ClusterEncodedFrame): Boolean {
            submitEntered.countDown()
            check(heldSubmit?.await(2, TimeUnit.SECONDS) != false)
            if (failSubmit) error("optional codec failed")
            if (busy > 0) { busy--; calls += "busy:${frame.presentationTimeUs}"; return false }
            calls += "frame:${frame.presentationTimeUs}"; return true
        }
        override fun poll() = progress.also { progress = ClusterRenderProgress(0, 0) }
        override fun clearOutput() { calls += "black" }
        override fun stopDecoder() { calls += "stop" }
        override fun detach(): Boolean {
            calls += "detach"; detachEntered.countDown()
            check(heldDetach?.await(2, TimeUnit.SECONDS) != false)
            return allowDetach
        }
    }
    private fun config() = ClusterCodecConfig(mapping().codedSize, byteArrayOf(0, 0, 0, 1, 103), byteArrayOf(0, 0, 0, 1, 104))
    private fun frame(epoch: Long, pts: Long, key: Boolean = true) = ClusterEncodedFrame(epoch, byteArrayOf(1, 2), pts, key)
    private fun fence(renderer: ClusterVideoRenderer) { assertTrue(renderer.barrier().get(2, TimeUnit.SECONDS)) }
    private fun eventually(predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (!predicate() && System.nanoTime() < end) Thread.sleep(2)
        assertTrue(predicate())
    }
    private fun withSurface(block: (Surface) -> Unit) {
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        try { block(surface) } finally { surface.release(); texture.release() }
    }

    @Test fun streamBeforeAttachRetainsConfigIdrAndFollowingFrames() = withSurface { surface ->
        val driver = Driver(); val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        try {
            renderer.configure(4, config())
            renderer.offer(frame(4, 1, false)); renderer.offer(frame(4, 2)); renderer.offer(frame(4, 3, false))
            fence(renderer)
            assertTrue(driver.calls.isEmpty())
            assertTrue(renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS))
            fence(renderer)
            assertEquals(listOf("configure", "frame:2", "frame:3"), driver.calls)
        } finally { assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }

    @Test fun busyCodecMustRetryTheExactStartupIdrWithoutDiscardingItsConfig() = withSurface { surface ->
        val driver = Driver().apply { busy = 1 }
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        try {
            renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS)
            renderer.configure(1, config()); renderer.offer(frame(1, 9)); fence(renderer)
            eventually { driver.calls.contains("frame:9") }
            assertEquals(1, driver.calls.count { it == "configure" })
        } finally { assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }

    @Test fun replacementUsesExactHandlesAndStaleGenerationCannotReclaimOutput() = withSurface { first ->
        withSurface { second ->
            val drivers = CopyOnWriteArrayList<Driver>()
            val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> Driver().also { drivers += it } })
            try {
                val a = renderer.attach(first, mapping(), 3); assertTrue(a.ready.get(2, TimeUnit.SECONDS))
                renderer.configure(1, config()); renderer.offer(frame(1, 1)); fence(renderer)
                val b = renderer.attach(second, mapping(), 4); assertTrue(b.ready.get(2, TimeUnit.SECONDS))
                assertEquals(listOf("stop", "black", "detach"), drivers[0].calls.takeLast(3))
                assertTrue(renderer.detach(a).get(2, TimeUnit.SECONDS))
                assertFalse(renderer.attach(first, mapping(), 3).ready.get(2, TimeUnit.SECONDS))
                renderer.offer(frame(1, 2, false)); renderer.offer(frame(1, 3)); fence(renderer)
                assertEquals(listOf("configure", "frame:3"), drivers[1].calls)
                assertTrue(second.isValid)
                assertTrue(renderer.detach(b).get(2, TimeUnit.SECONDS))
                assertTrue(second.isValid)
            } finally { assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
        }
    }

    @Test fun newEpochConfigChangeAndEofFenceStaleFrames() = withSurface { surface ->
        val driver = Driver(); val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        try {
            renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS)
            renderer.configure(5, config()); renderer.offer(frame(5, 1)); fence(renderer)
            renderer.configure(6, config()); renderer.offer(frame(5, 2)); renderer.offer(frame(6, 3, false))
            renderer.offer(frame(6, 4)); fence(renderer)
            renderer.configure(6, config()); renderer.offer(frame(6, 5, false)); renderer.offer(frame(6, 6))
            renderer.endStream(5); renderer.offer(frame(6, 7, false)); renderer.endStream(6)
            renderer.offer(frame(6, 8)); renderer.configure(6, config()); renderer.offer(frame(6, 9))
            fence(renderer)
            assertEquals(listOf("frame:1", "frame:4", "frame:6", "frame:7"), driver.calls.filter { it.startsWith("frame:") })
            assertEquals(3L, renderer.stats().configs)
        } finally { assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }

    @Test fun retireWaitsForExactGpuDetachAndIsIdempotentWithoutReleasingHost() = withSurface { surface ->
        val release = java.util.concurrent.CountDownLatch(1)
        val driver = Driver().apply { heldDetach = release }
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        try {
            val a = renderer.attach(surface, mapping(), 1); a.ready.get(2, TimeUnit.SECONDS)
            val ack = renderer.detach(a)
            assertTrue(driver.detachEntered.await(2, TimeUnit.SECONDS))
            val retire = renderer.retire()
            assertSame(retire, renderer.retire())
            assertFalse(ack.isDone); assertFalse(retire.isDone)
            assertFalse(renderer.offer(frame(1, 1)))
            assertFalse(renderer.configure(1, config()))
            assertTrue(surface.isValid)
            release.countDown()
            assertTrue(ack.get(2, TimeUnit.SECONDS)); assertTrue(retire.get(2, TimeUnit.SECONDS))
            assertEquals(1, driver.calls.count { it == "detach" })
            assertTrue(surface.isValid)
        } finally { release.countDown(); renderer.retire().get(2, TimeUnit.SECONDS) }
    }

    @Test fun codecFailureIsLocalAndStatsDoNotInventDecodedOrPresentedFrames() = withSurface { surface ->
        val driver = Driver().apply { failSubmit = true }
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        try {
            renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS)
            renderer.configure(1, config()); renderer.offer(frame(1, 1)); fence(renderer)
            assertEquals(1L, renderer.stats().errors)
            assertEquals(0L, renderer.stats().decoded); assertEquals(0L, renderer.stats().presented)
            assertEquals("illegal_state", renderer.lastError)
            assertEquals(listOf("stop", "black", "detach"), driver.calls.takeLast(3))
            assertTrue(surface.isValid)
        } finally { assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }

    @Test fun failedGpuDetachCannotAcknowledgeReplacementOrRetirement() = withSurface { surface ->
        val driver = Driver().apply { allowDetach = false }
        var opens = 0
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> opens++; driver })
        renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS)
        assertFalse(renderer.attach(surface, mapping(), 2).ready.get(2, TimeUnit.SECONDS))
        assertEquals(1, opens)
        assertFalse(renderer.retire().get(2, TimeUnit.SECONDS))
        assertTrue(surface.isValid)
    }
    @Test fun staleEpochFloodCannotEvictCurrentOwnerFramesOrBreakItsPredictionChain() = withSurface { surface ->
        val release = java.util.concurrent.CountDownLatch(1)
        val driver = Driver().apply { heldSubmit = release }
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        try {
            renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS)
            renderer.configure(10, config()); renderer.offer(frame(10, 1))
            assertTrue(driver.submitEntered.await(2, TimeUnit.SECONDS))
            assertTrue(renderer.offer(frame(10, 2, false)))
            for (i in 0..100) assertFalse("stale frame admitted", renderer.offer(frame(9, i.toLong())))
            assertEquals(1, renderer.stats().queuedPackets)
            release.countDown(); fence(renderer)
            renderer.offer(frame(10, 3, false)); fence(renderer)
            assertEquals(listOf("frame:1", "frame:2", "frame:3"), driver.calls.filter { it.startsWith("frame:") })
        } finally { release.countDown(); assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }

    @Test fun eofClearsOwnedOutputBlackAfterStoppingDecoderAndKeepsHostAttached() = withSurface { surface ->
        val driver = Driver(); val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        try {
            renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS)
            renderer.configure(1, config()); renderer.offer(frame(1, 1)); fence(renderer)
            renderer.endStream(1); fence(renderer)
            assertEquals(listOf("stop", "black"), driver.calls.takeLast(2))
            assertFalse(driver.calls.contains("detach"))
            assertTrue(surface.isValid)
        } finally { assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }

    @Test fun rejectedPacketAdmissionBreaksPredictionUntilFreshIdr() = withSurface { surface ->
        val release = java.util.concurrent.CountDownLatch(1)
        val driver = Driver().apply { heldSubmit = release }
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        try {
            renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS)
            renderer.configure(1, config()); renderer.offer(frame(1, 1))
            assertTrue(driver.submitEntered.await(2, TimeUnit.SECONDS))
            val barriers = (1..ClusterVideoRenderer.MAX_COMMANDS).map { renderer.barrier() }
            assertFalse(renderer.offer(frame(1, 2, false)))
            release.countDown()
            barriers.forEach { assertTrue(it.get(2, TimeUnit.SECONDS)) }
            renderer.offer(frame(1, 3, false)); renderer.offer(frame(1, 4)); fence(renderer)
            assertEquals(listOf("frame:1", "frame:4"), driver.calls.filter { it.startsWith("frame:") })
        } finally { release.countDown(); assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }

    @Test fun startupOverflowRetainsConfigurationButRequiresFreshIdr() = withSurface { surface ->
        val driver = Driver(); val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        try {
            renderer.configure(1, config()); renderer.offer(frame(1, 0)); fence(renderer)
            for (i in 1..ClusterVideoRenderer.MAX_FRAMES + 2) { renderer.offer(frame(1, i.toLong(), false)); fence(renderer) }
            renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS)
            renderer.offer(frame(1, 50, false)); fence(renderer)
            assertFalse(driver.calls.any { it.startsWith("frame:") })
            renderer.offer(frame(1, 51)); fence(renderer)
            assertEquals(listOf("frame:51"), driver.calls.filter { it.startsWith("frame:") })
            assertTrue(renderer.stats().dropped > 0)
        } finally { assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }

    @Test fun queuePressureIsBoundedAndRetirementBypassesFullControlQueue() = withSurface { surface ->
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val driver = Driver()
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ ->
            entered.countDown(); check(release.await(2, TimeUnit.SECONDS)); driver
        })
        val attached = renderer.attach(surface, mapping(), 1)
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            renderer.configure(1, config())
            for (i in 0..100) renderer.offer(frame(1, i.toLong(), i % 10 == 0))
            assertTrue(renderer.stats().queuedPackets <= ClusterVideoRenderer.MAX_FRAMES)
            assertTrue(renderer.stats().queuedBytes <= ClusterVideoRenderer.MAX_QUEUED_BYTES)
            val barriers = (1..ClusterVideoRenderer.MAX_COMMANDS + 2).map { renderer.barrier() }
            assertTrue(barriers.any { it.isDone && !it.getNow(true) })
            val retired = renderer.retire()
            assertFalse(retired.isDone)
            release.countDown()
            assertTrue(retired.get(2, TimeUnit.SECONDS))
            assertTrue(attached.ready.isDone)
            assertTrue(barriers.all { it.isDone })
            assertEquals(0, renderer.stats().queuedPackets)
        } finally { release.countDown(); renderer.retire().get(2, TimeUnit.SECONDS) }
    }

    @Test fun decoderIdentityIsOptionalExactAndBounded() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68)
        assertNull(ClusterCodecConfig(mapping().codedSize, sps, pps).decoderName)
        val exact = "c2.vendor.avc.decoder"
        assertEquals(exact, ClusterCodecConfig(mapping().codedSize, sps, pps, decoderName = exact).decoderName)
        assertEquals("x".repeat(256), ClusterCodecConfig(mapping().codedSize, sps, pps, "x".repeat(256)).decoderName)
        for (invalid in listOf("", " ", "\t\n", "x".repeat(257))) {
            try {
                ClusterCodecConfig(mapping().codedSize, sps, pps, invalid)
                fail("Unbounded or blank decoder identity accepted")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun packetAndConfigurationSnapshotCallerMemoryAndRejectOversizedInput() {
        val bytes = byteArrayOf(1, 2, 3)
        val frame = ClusterEncodedFrame(1, bytes, 2, true)
        val config = ClusterCodecConfig(mapping().codedSize, bytes, bytes)
        bytes.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.bytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), config.sps)
        assertArrayEquals(byteArrayOf(1, 2, 3), config.pps)
        try { ClusterEncodedFrame(1, ByteArray(ClusterEncodedFrame.MAX_BYTES + 1), 0, true); fail("oversized") }
        catch (_: IllegalArgumentException) { }
        try { ClusterCodecConfig(mapping().codedSize, ByteArray(65537), byteArrayOf(1)); fail("oversized config") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun presentedAndDecodedStatsRemainIndependentFromInboundPackets() = withSurface { surface ->
        val driver = Driver().apply { progress = ClusterRenderProgress(3, 1) }
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        try {
            renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS)
            renderer.configure(1, config()); renderer.offer(frame(1, 1)); fence(renderer)
            assertEquals(1L, renderer.stats().received)
            assertEquals(3L, renderer.stats().decoded)
            assertEquals(1L, renderer.stats().presented)
        } finally { assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }

    @Test fun wrongCodedDimensionsFailClosedWithoutSubmittingVideo() = withSurface { surface ->
        val driver = Driver(); val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        try {
            renderer.attach(surface, mapping(), 1).ready.get(2, TimeUnit.SECONDS)
            renderer.configure(1, ClusterCodecConfig(PixelSize(640, 480), byteArrayOf(1), byteArrayOf(2)))
            renderer.offer(frame(1, 1)); fence(renderer)
            assertEquals(1L, renderer.stats().errors)
            assertFalse(driver.calls.any { it.startsWith("frame:") })
        } finally { assertTrue(renderer.retire().get(2, TimeUnit.SECONDS)) }
    }

    @Test fun textureCropIsRelativeToDecoderVisibleRectAndAppliedExactlyOnce() {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val display = requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
            ClusterHostGeometry(7, p.panel, p.panel.bounds, p.panel.bounds)))
        val m = ClusterVideoMapping(display, PixelSize(1280, 720), PixelRect(20, 120, 1260, 620), 30)
        val plan = ClusterGlPlan.create(m, PixelRect(10, 20, 1270, 700))
        assertArrayEquals(floatArrayOf(10f / 1260, 1f - 600f / 680, 1250f / 1260, 1f - 600f / 680,
            10f / 1260, 1f - 100f / 680, 1250f / 1260, 1f - 100f / 680), plan.textureCoordinates, 0.00001f)
        assertEquals(display.effectiveOutput, plan.clearRect)
        assertEquals(m.destination.left.toFloat() / 1000f - 1f, plan.vertices[0], 0.00001f)
        assertEquals(1f - m.destination.bottom.toFloat() * 2f / 810f, plan.vertices[1], 0.00001f)
        try { ClusterGlPlan.create(m, PixelRect(30, 20, 1270, 700)); fail("missing source pixels accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun attachBeforeStreamClearsOutputThenWaitsForConfigAndIdr() {
        val driver = Driver()
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver.apply { calls += "black" } })
        val texture = SurfaceTexture(0)
        val surface = Surface(texture)
        try {
            val attached = renderer.attach(surface, mapping(), 1)
            assertTrue(attached.ready.get(2, TimeUnit.SECONDS))
            assertEquals(listOf("black"), driver.calls)
            assertTrue(renderer.configure(1, ClusterCodecConfig(mapping().codedSize, byteArrayOf(0, 0, 0, 1, 103), byteArrayOf(0, 0, 0, 1, 104))))
            assertTrue(renderer.offer(ClusterEncodedFrame(1, byteArrayOf(1), 1, false)))
            assertTrue(renderer.offer(ClusterEncodedFrame(1, byteArrayOf(2), 2, true)))
            assertTrue(renderer.barrier().get(2, TimeUnit.SECONDS))
            assertEquals(listOf("black", "configure", "frame:2"), driver.calls)
            assertEquals(2L, renderer.stats().received)
            assertEquals(1L, renderer.stats().configs)
            assertEquals(1L, renderer.stats().keyframes)
            assertTrue(renderer.detach(attached).get(2, TimeUnit.SECONDS))
            assertEquals(listOf("stop", "black", "detach"), driver.calls.takeLast(3))
        } finally {
            assertTrue(renderer.retire().get(2, TimeUnit.SECONDS))
            surface.release(); texture.release()
        }
    }
}
