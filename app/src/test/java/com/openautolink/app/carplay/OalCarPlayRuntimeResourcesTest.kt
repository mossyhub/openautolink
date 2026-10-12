package com.openautolink.app.carplay

import android.content.Context
import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.ProjectionReceiver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.lang.reflect.InvocationTargetException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class OalCarPlayRuntimeResourcesTest {
    private fun runtime() = OalCarPlayRuntime(mockk<Context>(), CarPlayRuntimeConfig(
        "", "", "", false, "", "", 0, CarPlayDisplayPreferences()), mockk<Surface>(), {})

    // Exercise the actual runtime installer, including its pre-fix eager-resource signature.
    private fun install(runtime: OalCarPlayRuntime, factory: () -> AutoCloseable) {
        val method = runtime.javaClass.declaredMethods.single { it.name == "own" }.apply { isAccessible = true }
        try {
            method.invoke(runtime, if (method.parameterTypes.single() == AutoCloseable::class.java) factory() else factory)
        } catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun CountDownLatch.waitFor() = assertTrue("Latch timed out", await(5, TimeUnit.SECONDS))

    @Test fun coordinatorCannotAcknowledgeLateReceiverUntilAwaitClosedReturns() {
        val runtime = runtime()
        val owner = CarPlayOwnership()
        val creating = CountDownLatch(1)
        val finishCreation = CountDownLatch(1)
        val awaitingReceiver = CountDownLatch(1)
        val finishReceiver = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val receiver = mockk<ProjectionReceiver>()
        every { receiver.close() } returns Unit
        every { receiver.awaitClosed(10_000) } answers {
            awaitingReceiver.countDown()
            finishReceiver.waitFor()
            true
        }
        val coordinator = CarPlayCoordinator(owner, {}) { }
        try {
            assertTrue(coordinator.connect(true) { object : CarPlayRuntime {
                override fun run() {
                    install(runtime) { creating.countDown(); finishCreation.waitFor(); receiver }
                }
                override fun cancel() = runtime.cancel()
                override fun close() = runtime.close()
            } })
            creating.waitFor()
            coordinator.returnToAndroidAuto { returned.countDown() }
            finishCreation.countDown()
            awaitingReceiver.waitFor()
            assertFalse("Coordinator released ownership before receiver awaitClosed", returned.await(100, TimeUnit.MILLISECONDS))
            assertTrue(owner.blocksAndroidAuto)
            assertNull(owner.reserve(true))
            finishReceiver.countDown()
            returned.waitFor()
            assertFalse(owner.blocksAndroidAuto)
            verify(exactly = 1) { receiver.close() }
            verify(exactly = 1) { receiver.awaitClosed(10_000) }
        } finally {
            finishCreation.countDown(); finishReceiver.countDown(); coordinator.close(); runtime.close()
        }
    }

    @Test fun canceledAttemptNeverInvokesAnotherResourceFactory() {
        val runtime = runtime()
        runtime.close()
        var created = false
        try {
            install(runtime) { created = true; AutoCloseable {} }
            fail("Canceled installation must be rejected")
        } catch (_: IllegalStateException) { }
        assertFalse(created)
        runtime.close()
    }

    @Test fun failedCloseRetriesOnlyTheExactUnretiredOwner() {
        val runtime = runtime()
        var succeededCloses = 0
        var failedCloses = 0
        val failed = AutoCloseable { failedCloses++; if (failedCloses == 1) error("Exact owner not retired") }
        install(runtime) { AutoCloseable { succeededCloses++ } }
        install(runtime) { failed }
        try {
            try { runtime.close(); fail("Failed owner must prevent retirement acknowledgement") }
            catch (expected: IllegalStateException) { assertEquals("Exact owner not retired", expected.message) }
            assertEquals(1, failedCloses)
            assertEquals(1, succeededCloses)
            runtime.close()
            assertEquals(2, failedCloses)
            assertEquals("Already retired owners must not be closed again on retry", 1, succeededCloses)
            runtime.close()
            assertEquals(2, failedCloses)
        } finally { runtime.close() }
    }

    @Test fun installationAcrossRetirementSnapshotMustRetireExactOwnerBeforeOwnershipAcknowledgement() {
        val runtime = runtime()
        val owner = CarPlayOwnership()
        val token = requireNotNull(owner.reserve(true))
        owner.acknowledgeAaRetired(token)
        val pool = Executors.newFixedThreadPool(2)
        val creating = CountDownLatch(1)
        val finishCreation = CountDownLatch(1)
        val sentinelClosing = CountDownLatch(1)
        val finishSentinel = CountDownLatch(1)
        val lateClosing = CountDownLatch(1)
        val finishLate = CountDownLatch(1)
        val installationFinished = CountDownLatch(1)
        val acknowledged = CountDownLatch(1)
        val path = Files.createTempFile("carplay-runtime-owner", ".lock")
        val channel = FileChannel.open(path, StandardOpenOption.WRITE)
        val exactLock = channel.lock()
        val late = AutoCloseable {
            lateClosing.countDown()
            finishLate.waitFor()
            if (exactLock.isValid) exactLock.release()
        }
        install(runtime) { AutoCloseable { sentinelClosing.countDown(); finishSentinel.waitFor() } }
        try {
            val installing = pool.submit {
                try { install(runtime) { creating.countDown(); finishCreation.waitFor(); late } }
                catch (_: IllegalStateException) { /* Cancellation after creation must retain this exact owner. */ }
                finally { installationFinished.countDown() }
            }
            creating.waitFor()
            runtime.cancel()
            val closing = pool.submit {
                runtime.close()
                owner.acknowledgeCarPlayRetired(token)
                assertTrue(owner.release(token))
                acknowledged.countDown()
            }
            // Give retirement a chance to snapshot while creation is suspended. A serialized
            // factory intentionally cannot reach the sentinel until creation has returned.
            sentinelClosing.await(150, TimeUnit.MILLISECONDS)
            finishCreation.countDown()
            installationFinished.waitFor()
            finishSentinel.countDown()
            assertTrue("Exact late-created owner was lost by retirement", lateClosing.await(3, TimeUnit.SECONDS))
            assertFalse("Ownership acknowledged before exact close returned", acknowledged.await(100, TimeUnit.MILLISECONDS))
            assertTrue(owner.blocksAndroidAuto)
            FileChannel.open(path, StandardOpenOption.WRITE).use { replacement ->
                try { replacement.tryLock()?.also { it.release(); fail("Replacement acquired retiring owner's lock") } }
                catch (_: java.nio.channels.OverlappingFileLockException) { }
            }
            finishLate.countDown()
            installing.get(5, TimeUnit.SECONDS)
            closing.get(5, TimeUnit.SECONDS)
            acknowledged.waitFor()
            FileChannel.open(path, StandardOpenOption.WRITE).use { replacement ->
                requireNotNull(replacement.tryLock()).release()
            }
        } finally {
            finishCreation.countDown(); finishSentinel.countDown(); finishLate.countDown()
            pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
            runtime.close()
            if (exactLock.isValid) exactLock.release()
            channel.close(); Files.deleteIfExists(path)
        }
    }
}
