// SPDX-License-Identifier: GPL-3.0-only
package com.shilapi.xcertplay.media

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.openautolink.carplaylab.media.ReceiverTelemetry
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class CarPlaySecondarySinkTest {
    private class Decoder {
        val codec = mock(MediaCodec::class.java)
        val surface = AtomicReference<Surface>()
        val format = AtomicReference<MediaFormat>()
        val listener = AtomicReference<MediaCodec.OnFrameRenderedListener>()
        val configured = CountDownLatch(1)
        val queued = LinkedBlockingQueue<Unit>()
        val released = CountDownLatch(1)
        init {
            val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,1280,720))
                .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
                .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                    profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh; level = MediaCodecInfo.CodecProfileLevel.AVCLevel52
                })).build()
            `when`(codec.codecInfo).thenReturn(MediaCodecInfoBuilder.newBuilder().setName("test.avc").setIsEncoder(false).setCapabilities(caps).build())
            `when`(codec.name).thenReturn("test.avc")
            `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java),anyLong())).thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER)
            `when`(codec.dequeueInputBuffer(anyLong())).thenReturn(0)
            `when`(codec.getInputBuffer(0)).thenReturn(ByteBuffer.allocate(4096))
            doAnswer { format.set(it.getArgument(0)); surface.set(it.getArgument(1)); configured.countDown(); null }.`when`(codec)
                .configure(any(MediaFormat::class.java),any(Surface::class.java),isNull(),eq(0))
            doAnswer { listener.set(it.getArgument(0)); null }.`when`(codec)
                .setOnFrameRenderedListener(any(MediaCodec.OnFrameRenderedListener::class.java),any(android.os.Handler::class.java))
            doAnswer { queued.offer(Unit); null }.`when`(codec).queueInputBuffer(anyInt(),anyInt(),anyInt(),anyLong(),anyInt())
            doAnswer { released.countDown(); null }.`when`(codec).release()
        }
        fun frame() { assertNotNull("production decoder received frame",queued.poll(3,TimeUnit.SECONDS)) }
    }
    private class Fixture(enabled: Boolean = true, setup: (Decoder)->Unit = {}) {
        val main = Surface(SurfaceTexture(0))
        val alt = Surface(SurfaceTexture(1))
        val created = LinkedBlockingQueue<Decoder>()
        val telemetry = ReceiverTelemetry(1)
        val selection = CarPlayVideoSelection(1280,720,30,"test.avc")
        val sink: AndroidMediaSink
        init {
            // Reflect the future defaulted constructor so baseline RED compiles.
            val constructor = AndroidMediaSink::class.java.declaredConstructors.firstOrNull {
                it.parameterTypes.lastOrNull() == CarPlayVideoSelection::class.java
            }
            assertNotNull("sink must freeze a nullable exact secondary-video qualification",constructor)
            val count = constructor!!.parameterCount
            val synthetic = AndroidMediaSink::class.java.declaredConstructors.single { it.parameterCount == count + 2 }
            val arguments = arrayOfNulls<Any>(count+2)
            arguments[0] = RuntimeEnvironment.getApplication()
            arguments[1] = main
            arguments[2] = com.openautolink.carplaylab.media.CarPlayDisplaySelection(
                com.openautolink.carplaylab.media.CarPlayDisplayPreferences(),"test.avc",false)
            arguments[5] = telemetry
            arguments[6] = selection
            arguments[7] = { _: String -> Decoder().also { setup(it); created.offer(it) }.codec }
            arguments[count-1] = if (enabled) selection else null
            // All unspecified constructor values use Kotlin defaults, including primitives.
            var defaults = 0
            for (i in 2 until count-1) if (arguments[i] == null) {
                defaults = defaults or (1 shl i)
                if (constructor.parameterTypes[i] == Boolean::class.javaPrimitiveType) arguments[i] = false
            }
            arguments[count] = defaults
            sink = synthetic.newInstance(*arguments) as AndroidMediaSink
        }
        fun attach(surface: Surface = alt): Any? {
            val method = sink.javaClass.methods.firstOrNull { it.name == "attachClusterSurface" }
            assertNotNull("exact lease-returning cluster surface attach API",method)
            return method!!.invoke(sink,surface)
        }
        fun detach(lease: Any) { sink.javaClass.methods.single { it.name == "detachClusterSurface" }.invoke(sink,lease) }
        fun config(type: Int) { sink.onVideoCodec(type,VideoCodec.H264); sink.onVideoConfig(type,GeneratedHevcFixture.avcc) }
        fun decoder(): Decoder = checkNotNull(created.poll(3,TimeUnit.SECONDS)).also {
            assertTrue(it.configured.await(3,TimeUnit.SECONDS))
            val deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while (it.listener.get() == null && System.nanoTime() < deadline) Thread.sleep(1)
            assertNotNull(it.listener.get())
        }
        fun close() { sink.close(); assertTrue(sink.awaitRetired(3000)); main.release(); alt.release() }
    }
    private val idr get() = byteArrayOf(0,0,0,1,0x65,0)
    @Test fun realEncryptedDualStreamRoutingAndBadSecondaryTagLeaveMainLive() {
        val f = Fixture()
        val stopped = CountDownLatch(1)
        val engine = com.shilapi.xcertplay.airplay.CarPlayMediaEngine(object : com.shilapi.xcertplay.airplay.MediaSink by f.sink {
            override fun onScreenStreamActive(type: Int, active: Boolean) {
                f.sink.onScreenStreamActive(type,active)
                if (type == 111 && !active) stopped.countDown()
            }
        })
        val current = com.shilapi.xcertplay.airplay.AirPlaySession(java.net.Socket(),
            com.shilapi.xcertplay.airplay.AirPlayConfig("test","synthetic","synthetic","1",
                com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(1280,720),
                cluster = com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(1280,720)),
            com.shilapi.xcertplay.airplay.AirPlayIdentity.generate(),com.shilapi.xcertplay.airplay.PairingStore(),null,
            object : com.shilapi.xcertplay.airplay.AirPlaySessionListener {},engine)
        val pairing = current.javaClass.getDeclaredField("pairVerify").apply { isAccessible = true }.get(current)
        pairing.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }.set(pairing,ByteArray(32))
        fun header(size: Int, opcode: Int) = ByteBuffer.allocate(128).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(size).put(opcode.toByte()).array()
        fun config(socket: java.net.Socket) { socket.getOutputStream().apply { write(header(GeneratedHevcFixture.avcc.size,1)); write(GeneratedHevcFixture.avcc); flush() } }
        fun frame(socket: java.net.Socket, id: Int, counter: Long, corrupt: Boolean = false) {
            val key = com.shilapi.xcertplay.airplay.AirPlayCrypto.hkdfSha512(ByteArray(32),"DataStream-Salt$id".toByteArray(),"DataStream-Output-Encryption-Key".toByteArray(),32)
            val payload = byteArrayOf(0,0,0,2,0x65,0)
            val header = header(payload.size+16,0)
            val sealed = com.shilapi.xcertplay.airplay.AirPlayCrypto.chachaSeal(key,com.shilapi.xcertplay.airplay.AirPlayCrypto.nonce64(counter),payload,header)
            if (corrupt) sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
            socket.getOutputStream().apply { write(header); write(sealed); flush() }
        }
        try {
            f.attach()
            val mainPort = checkNotNull(engine.onScreen(current,110,mapOf("streamConnectionID" to 1L)))
            val altPort = checkNotNull(engine.onScreen(current,111,mapOf("streamConnectionID" to 2L)))
            java.net.Socket("127.0.0.1",mainPort).use { mainSocket -> java.net.Socket("127.0.0.1",altPort).use { altSocket ->
                config(mainSocket); val main = f.decoder()
                config(altSocket); val alt = f.decoder()
                frame(mainSocket,1,0); main.frame(); frame(altSocket,2,0); alt.frame()
                assertSame(f.main,main.surface.get()); assertSame(f.alt,alt.surface.get())
                frame(altSocket,2,1,corrupt = true)
                assertTrue("actual ALT AEAD failure retires only that stream",stopped.await(3,TimeUnit.SECONDS))
                synchronized(engine) { assertFalse(current.isClosed) }
                assertTrue(alt.released.await(3,TimeUnit.SECONDS))
                frame(mainSocket,1,1); main.frame()
                verify(main.codec,never()).release()
                current.close() // shared transport/session close still retires main normally
                assertTrue(main.released.await(3,TimeUnit.SECONDS))
            } }
        } finally { engine.onSessionClosed(current); current.close(); f.close() }
    }
    @Test fun queuedConfigurationCannotBorrowSuccessorRenderedOrRecoveryHandlers() {
        for (type in listOf(110,111)) {
            val f = Fixture()
            val entered = CountDownLatch(1); val resume = CountDownLatch(1)
            try {
                f.attach()
                val once = java.util.concurrent.atomic.AtomicBoolean()
                val gate = { if (once.compareAndSet(false,true)) { entered.countDown(); assertTrue(resume.await(5,TimeUnit.SECONDS)) }; Unit }
                if (type == 110) f.sink.beforeVideoPoll = gate else secondaryOwner(f.sink).beforePoll = gate
                assertTrue(entered.await(3,TimeUnit.SECONDS))
                val oldRender = java.util.concurrent.atomic.AtomicInteger(); val freshRender = java.util.concurrent.atomic.AtomicInteger()
                val oldRecovery = LinkedBlockingQueue<Unit>(); val freshRecovery = LinkedBlockingQueue<Unit>()
                f.sink.setVideoDiagnosticHandler(type) { oldRender.incrementAndGet() }
                f.sink.setVideoRecoveryHandler(type) { oldRecovery.offer(Unit) }
                f.config(type)
                // Engine installs successor closures before revoking the predecessor config.
                f.sink.setVideoDiagnosticHandler(type) { freshRender.incrementAndGet() }
                f.sink.setVideoRecoveryHandler(type) { freshRecovery.offer(Unit) }
                f.sink.onVideoFrame(type,idr); resume.countDown()
                val old = f.decoder(); old.frame()
                old.listener.get().onFrameRendered(old.codec,1,1)
                assertEquals("old rendered callback keeps its original stream epoch",1,oldRender.get())
                assertEquals(0,freshRender.get())
                assertNotNull(oldRecovery.poll(3,TimeUnit.SECONDS)); assertTrue(freshRecovery.isEmpty())
            } finally { resume.countDown(); f.close() }
        }
    }
    @Test fun exactSurfaceLeaseRejectsStaleDetachAndRecoveryWaitsForFreshIdr() {
        val f = Fixture()
        try {
            val recovery = LinkedBlockingQueue<Unit>()
            val rendered = LinkedBlockingQueue<Unit>()
            f.sink.setVideoRecoveryHandler(111) { recovery.offer(Unit) }
            f.sink.setVideoDiagnosticHandler(111) { rendered.offer(Unit) }
            val oldLease = checkNotNull(f.attach())
            f.config(111); val old = f.decoder()
            assertNotNull(recovery.poll(3,TimeUnit.SECONDS))
            val freshLease = checkNotNull(f.attach()) // identical Java Surface, new binding epoch
            f.detach(oldLease)
            val fresh = f.decoder()
            assertTrue(old.released.await(3,TimeUnit.SECONDS))
            assertNotNull(recovery.poll(3,TimeUnit.SECONDS))
            old.listener.get().onFrameRendered(old.codec,1,1)
            assertTrue(rendered.isEmpty())
            f.sink.onVideoFrame(111,byteArrayOf(0,0,0,1,0x41,0)); f.sink.onVideoFrame(111,idr)
            fresh.frame()
            verify(fresh.codec,times(1)).queueInputBuffer(anyInt(),anyInt(),anyInt(),anyLong(),anyInt())
            fresh.listener.get().onFrameRendered(fresh.codec,2,2)
            assertNotNull(rendered.poll(3,TimeUnit.SECONDS))
            f.detach(freshLease)
            assertTrue(fresh.released.await(3,TimeUnit.SECONDS))
            repeat(1000) { f.sink.onVideoFrame(111,idr) }
            assertTrue(f.created.isEmpty())
            assertNotNull(f.attach()); val third = f.decoder()
            assertSame(f.alt,third.surface.get())
        } finally { f.close() }
    }
    private fun secondaryOwner(sink: AndroidMediaSink): CarPlayScreenVideoOwner =
        sink.javaClass.getDeclaredField("clusterVideo").apply { isAccessible = true }.get(sink) as CarPlayScreenVideoOwner
    @Test fun secondaryOverflowIsBoundedAndCannotStarveMainOrLoseLatestConfig() {
        val f = Fixture()
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        try {
            f.attach(); f.config(110); val main = f.decoder()
            f.config(111); f.decoder()
            val owner = secondaryOwner(f.sink)
            val once = java.util.concurrent.atomic.AtomicBoolean()
            owner.beforePoll = { if (once.compareAndSet(false,true)) { entered.countDown(); assertTrue(resume.await(5,TimeUnit.SECONDS)) } }
            assertTrue(entered.await(3,TimeUnit.SECONDS))
            repeat(1000) { f.sink.onVideoFrame(111,idr) }
            val queue = owner.javaClass.getDeclaredField("queue").apply { isAccessible = true }.get(owner) as java.util.Queue<*>
            assertTrue(queue.size in 1..24)
            f.sink.onVideoFrame(110,idr); main.frame()
            val changed = GeneratedHevcFixture.avcc.copyOf().apply { this[41] = (this[41].toInt() xor 1).toByte() }
            f.sink.onVideoConfig(111,changed)
            f.sink.onVideoFrame(111,idr)
            resume.countDown()
            val fresh = f.decoder(); fresh.frame()
            assertTrue(queue.isEmpty())
            val expected = CarPlayVideoQualification.mediaFormat(VideoCodec.H264,changed,f.selection).getByteBuffer("csd-1")
            assertEquals(expected,fresh.format.get().getByteBuffer("csd-1"))
            verify(main.codec,never()).release()
        } finally { resume.countDown(); f.close() }
    }
    @Test fun stalledSecondaryReleaseRemainsPartOfSinkRetirementAcknowledgement() {
        val releaseEntered = CountDownLatch(1); val resume = CountDownLatch(1)
        val f = Fixture { decoder ->
            doAnswer {
                releaseEntered.countDown()
                while (resume.count > 0) try { resume.await() } catch (_: InterruptedException) { }
                decoder.released.countDown(); null
            }.`when`(decoder.codec).release()
        }
        try {
            f.attach(); f.config(111); f.decoder()
            f.sink.close()
            assertTrue(releaseEntered.await(3,TimeUnit.SECONDS))
            assertFalse("interrupt/join timeout is not native release acknowledgement",f.sink.awaitRetired(20))
            resume.countDown(); assertTrue(f.sink.awaitRetired(3000))
            assertNull(f.attach())
        } finally { resume.countDown(); f.close() }
    }
    @Test fun disabledSecondaryKeepsExistingCodecRejectionAndAllocatesNoOwner() {
        val f = Fixture(enabled = false)
        try {
            assertNull(f.attach())
            assertThrows(IllegalArgumentException::class.java) { f.sink.onVideoCodec(111,VideoCodec.H264) }
            f.sink.onVideoConfig(111,byteArrayOf(0)); f.sink.onVideoFrame(111,idr)
            f.config(110); val main = f.decoder(); f.sink.onVideoFrame(110,idr); main.frame()
            assertTrue(f.created.isEmpty())
        } finally { f.close() }
    }
    @Test fun replacementOfEitherStreamInvalidatesPredecessorConfigAndRenderedCallbacks() {
        for (type in listOf(110,111)) {
            val f = Fixture()
            try {
                f.attach(); f.config(type); val old = f.decoder()
                f.sink.onScreenStreamActive(type,true)
                old.listener.get().onFrameRendered(old.codec,1,1)
                assertNull("replacement revokes old main frame receipt",f.telemetry.snapshot(1).renderedFrames)
                f.config(type); val fresh = f.decoder()
                assertNotSame(old.codec,fresh.codec)
                f.sink.onVideoFrame(type,idr); fresh.frame()
                old.listener.get().onFrameRendered(old.codec,2,2)
                assertNull(f.telemetry.snapshot(2).renderedFrames)
            } finally { f.close() }
        }
    }
    @Test fun secondaryHasIndependentDecoderConfigFramesAndDoesNotContaminateMainTelemetry() {
        val f = Fixture()
        try {
            assertNotNull(f.attach())
            f.config(110); val main = f.decoder()
            f.config(111); val alt = f.decoder()
            assertSame(f.main,main.surface.get()); assertSame(f.alt,alt.surface.get())
            f.sink.onVideoFrame(110,idr); main.frame()
            f.sink.onVideoFrame(111,idr); alt.frame()
            alt.listener.get().onFrameRendered(alt.codec,1,1)
            assertNull("secondary never counts as main output",f.telemetry.snapshot(1).renderedFrames)
            main.listener.get().onFrameRendered(main.codec,2,2)
            assertEquals(1L,f.telemetry.snapshot(2).renderedFrames)
            verify(main.codec,never()).release()
        } finally { f.close() }
    }
}
