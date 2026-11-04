package com.openautolink.app.cluster

import android.app.Application
import android.graphics.SurfaceTexture
import android.view.Surface
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class ClusterRendererTerminalCompletionTest {
    private fun mapping(): ClusterVideoMapping {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        return ClusterVideoGeometry.carPlay(requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
            ClusterHostGeometry(7, p.panel, p.panel.bounds, p.panel.bounds))))
    }

    @Test fun abandonedAttachmentCompletionCannotInvertExternalOwnerAndRendererQueueLocks() = checkTerminalDrain("attach")
    @Test fun queuedDetachCompletionCannotInvertExternalOwnerAndRendererQueueLocks() = checkTerminalDrain("detach")
    @Test fun queuedBarrierCompletionCannotInvertExternalOwnerAndRendererQueueLocks() = checkTerminalDrain("barrier")

    private fun checkTerminalDrain(target: String) {
        val initializeEntered = CountDownLatch(1)
        val releaseInitialize = CountDownLatch(1)
        val callbackEntered = CountDownLatch(1)
        val ownerEntered = CountDownLatch(1)
        val retirementReturned = CountDownLatch(1)
        val callbackAcquiredOwner = AtomicBoolean(false)
        val callbackHeldQueue = AtomicBoolean(false)
        val failure = CompletableFuture<Throwable?>()
        // Bounded lock models an external bridge owner. tryLock makes the old inversion recoverable.
        val ownerLock = ReentrantLock()
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        val driver = object : ClusterRenderDriver {
            override fun initialize() { initializeEntered.countDown(); check(releaseInitialize.await(5, TimeUnit.SECONDS)) }
            override fun configure(config: ClusterCodecConfig) = Unit
            override fun submit(frame: ClusterEncodedFrame) = true
            override fun poll() = ClusterRenderProgress(0, 0)
            override fun stopDecoder() = Unit
            override fun clearOutput() = Unit
            override fun detach() = true
        }
        val renderer = ClusterVideoRenderer(ClusterRenderDriverFactory { _, _ -> driver })
        val queueLock = renderer.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(renderer)!!
        var owner: Thread? = null
        try {
            val attached = renderer.attach(surface, mapping(), 1)
            assertTrue(initializeEntered.await(2, TimeUnit.SECONDS))
            val abandoned = renderer.attach(surface, mapping(), 2)
            val detached = renderer.detach(attached)
            val barrier = renderer.barrier()
            val selected = when (target) { "attach" -> abandoned.ready; "detach" -> detached; else -> barrier }
            val callback = selected.thenRun {
                callbackHeldQueue.set(Thread.holdsLock(queueLock))
                callbackEntered.countDown()
                if (ownerLock.tryLock(3, TimeUnit.SECONDS)) {
                    try { callbackAcquiredOwner.set(true) } finally { ownerLock.unlock() }
                }
            }
            owner = Thread({
                try {
                    ownerLock.lock()
                    try {
                        ownerEntered.countDown()
                        check(callbackEntered.await(5, TimeUnit.SECONDS))
                        // Real bridge methods call renderer.retire while owning their own lock.
                        renderer.retire()
                        retirementReturned.countDown()
                    } finally { ownerLock.unlock() }
                    failure.complete(null)
                } catch (problem: Throwable) { failure.complete(problem) }
            }, "cluster-external-owner").apply { isDaemon = true; start() }
            assertTrue(ownerEntered.await(2, TimeUnit.SECONDS))
            val retirement = renderer.retire()
            releaseInitialize.countDown()
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS))
            assertTrue("external owner must return from retire while completion waits for its lock",
                retirementReturned.await(1, TimeUnit.SECONDS))
            callback.get(2, TimeUnit.SECONDS)
            assertNull(failure.get(2, TimeUnit.SECONDS))
            assertFalse("future continuation ran under renderer queue monitor", callbackHeldQueue.get())
            assertTrue(callbackAcquiredOwner.get())
            assertTrue(retirement.get(2, TimeUnit.SECONDS))
            assertTrue(attached.ready.get(2, TimeUnit.SECONDS))
            assertFalse(abandoned.ready.get(2, TimeUnit.SECONDS))
            assertTrue(detached.get(2, TimeUnit.SECONDS))
            assertFalse(barrier.get(2, TimeUnit.SECONDS))
        } finally {
            releaseInitialize.countDown()
            owner?.join(5000)
            renderer.retire().get(5, TimeUnit.SECONDS)
            surface.release(); texture.release()
        }
    }
}
