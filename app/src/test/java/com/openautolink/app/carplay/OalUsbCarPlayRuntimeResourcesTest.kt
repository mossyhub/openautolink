package com.openautolink.app.carplay

import android.content.Context
import android.hardware.usb.UsbDevice
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

class OalUsbCarPlayRuntimeResourcesTest {
    private fun runtime() = OalUsbCarPlayRuntime(mockk<Context>(), mockk<UsbDevice>(relaxed = true),
        CarPlayDisplayPreferences(), mockk<Surface>(), {})

    // Invoke the production installer; support its original eager signature for behavioral RED.
    private fun install(runtime: OalUsbCarPlayRuntime, factory: () -> AutoCloseable) {
        val method = runtime.javaClass.declaredMethods.single { it.name == "own" }.apply { isAccessible = true }
        try {
            method.invoke(runtime, if (method.parameterTypes.single() == AutoCloseable::class.java) factory() else factory)
        } catch (error: InvocationTargetException) { throw error.targetException }
    }
    private fun CountDownLatch.waitFor() = assertTrue("Latch timed out", await(5, TimeUnit.SECONDS))

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

    @Test fun receiverAcknowledgementFailureRetainsOnlyThatExactOwnerForRetry() {
        val runtime = runtime()
        val receiver = mockk<ProjectionReceiver>()
        var succeededCloses = 0
        every { receiver.close() } returns Unit
        every { receiver.awaitClosed(10_000) } returnsMany listOf(false, true)
        install(runtime) { AutoCloseable { succeededCloses++ } }
        install(runtime) { receiver }
        try {
            try { runtime.close(); fail("Unacknowledged receiver was retired") }
            catch (expected: IllegalStateException) { assertEquals("USB receiver retirement unacknowledged", expected.message) }
            assertEquals(1, succeededCloses)
            runtime.close()
            assertEquals("Acknowledged owners must not be retried", 1, succeededCloses)
            runtime.close()
            verify(exactly = 2) { receiver.close() }
            verify(exactly = 2) { receiver.awaitClosed(10_000) }
        } finally { runtime.close() }
    }

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

    @Test fun failedCloseRetriesOnlyTheExactUnretiredOwner() {
        val runtime = runtime()
        var succeededCloses = 0
        var failedCloses = 0
        install(runtime) { AutoCloseable { succeededCloses++ } }
        install(runtime) { AutoCloseable { failedCloses++; if (failedCloses == 1) error("Exact owner not retired") } }
        try {
            try { runtime.close(); fail("Failed owner acknowledged as retired") }
            catch (expected: IllegalStateException) { assertEquals("Exact owner not retired", expected.message) }
            assertEquals(1, succeededCloses)
            assertEquals(1, failedCloses)
            runtime.close()
            assertEquals(2, failedCloses)
            assertEquals("Successful owners must not be retried", 1, succeededCloses)
            runtime.close()
            assertEquals(2, failedCloses)
        } finally { runtime.close() }
    }

    @Test fun cancellationDuringCreationRetainsFailedExactOwnerForCloseRetry() {
        val runtime = runtime()
        val creating = CountDownLatch(1)
        val finishCreation = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        val path = Files.createTempFile("usb-carplay-runtime-owner", ".lock")
        val channel = FileChannel.open(path, StandardOpenOption.WRITE)
        val exactLock = channel.lock()
        var closes = 0
        val resource = AutoCloseable {
            closes++
            if (closes == 1) error("Exact USB owner not retired")
            exactLock.release()
        }
        try {
            val installing = pool.submit {
                try { install(runtime) { creating.countDown(); finishCreation.waitFor(); resource }; fail("Cancelled install accepted") }
                catch (_: IllegalStateException) { }
            }
            creating.waitFor()
            runtime.cancel()
            finishCreation.countDown()
            installing.get(5, TimeUnit.SECONDS)
            assertEquals("Cancelled installation must retain the owner, not close it inline", 0, closes)
            try { runtime.close(); fail("Failed owner acknowledged as retired") }
            catch (expected: IllegalStateException) { assertEquals("Exact USB owner not retired", expected.message) }
            assertEquals(1, closes)
            FileChannel.open(path, StandardOpenOption.WRITE).use { replacement ->
                try { replacement.tryLock()?.also { it.release(); fail("Failed owner lost its lock") } }
                catch (_: java.nio.channels.OverlappingFileLockException) { }
            }
            runtime.close()
            assertEquals(2, closes)
            FileChannel.open(path, StandardOpenOption.WRITE).use { replacement ->
                requireNotNull(replacement.tryLock()).release()
            }
            runtime.close()
            assertEquals(2, closes)
        } finally {
            finishCreation.countDown(); pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
            runtime.close()
            if (exactLock.isValid) exactLock.release()
            channel.close(); Files.deleteIfExists(path)
        }
    }
}
