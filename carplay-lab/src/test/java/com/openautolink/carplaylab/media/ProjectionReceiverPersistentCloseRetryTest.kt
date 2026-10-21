// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.openautolink.carplaylab.pairing.PairingPersistence
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class ProjectionReceiverPersistentCloseRetryTest {
    @Test fun persistentCloseFailureCanRetryAfterGracefulStatusShutdownWithoutLosingLedger() {
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720))
            .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                level = MediaCodecInfo.CodecProfileLevel.AVCLevel52
            })).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("persistent-retry.avc")
            .setIsEncoder(false).setCapabilities(caps).build())
        val surface = mock(Surface::class.java)
        `when`(surface.isValid).thenReturn(true)
        val context = RuntimeEnvironment.getApplication()
        java.io.File(context.noBackupFilesDir, "pairing-probe").deleteRecursively()
        val lines = CopyOnWriteArrayList<String>()
        val receiver = ProjectionReceiver(context, "02:00:00:00:00:01", surface,
            mock(MfiAuthenticator::class.java), wired = true, onStatus = { lines.add(it) })
        fun field(name: String) = ProjectionReceiver::class.java.getDeclaredField(name).apply { isAccessible = true }
        val persistenceField = field("persistence")
        val realPersistence = persistenceField.get(receiver) as PairingPersistence
        // Mock only the durable close boundary; receiver, ledger and executors remain real.
        val persistence = spy(realPersistence)
        val calls = AtomicInteger()
        val firstClose = CountDownLatch(1)
        val retryClose = CountDownLatch(1)
        val allowRetryClose = CountDownLatch(1)
        val releaseStatuses = CountDownLatch(1)
        val statusBlocked = CountDownLatch(1)
        val rejectedAfterShutdown = AtomicInteger()
        val retryWorker = AtomicReference<Thread>()
        val firstWorker = AtomicReference<Thread>()
        doAnswer {
            if (calls.incrementAndGet() == 1) {
                firstWorker.set(Thread.currentThread())
                firstClose.countDown()
                throw IOException("synthetic persistent close failure")
            }
            retryWorker.set(Thread.currentThread())
            retryClose.countDown()
            check(allowRetryClose.await(3, TimeUnit.SECONDS))
            realPersistence.close()
        }.`when`(persistence).close()
        persistenceField.set(receiver, persistence)
        val statuses = field("statuses").get(receiver) as ThreadPoolExecutor
        val handler = statuses.rejectedExecutionHandler
        statuses.rejectedExecutionHandler = RejectedExecutionHandler { task, executor ->
            if (executor.isShutdown) {
                rejectedAfterShutdown.incrementAndGet()
                retryWorker.compareAndSet(null, Thread.currentThread())
            }
            handler.rejectedExecution(task, executor)
        }
        try {
            // Hold the actual passive worker: queued ledger receipts must drain gracefully.
            statuses.execute {
                statusBlocked.countDown()
                check(releaseStatuses.await(3, TimeUnit.SECONDS))
            }
            assertTrue(statusBlocked.await(3, TimeUnit.SECONDS))
            receiver.close()
            assertTrue(firstClose.await(3, TimeUnit.SECONDS))
            firstWorker.get().join(1000)
            assertFalse("failed persistent close must not acknowledge", receiver.awaitClosed(50))
            assertTrue("first retirement shuts down status admission", statuses.isShutdown)
            assertFalse("queued passive worker has not retired", statuses.isTerminated)
            releaseStatuses.countDown()
            assertTrue(statuses.awaitTermination(3, TimeUnit.SECONDS))
            val retired = lines.filter { it.contains("kind=receiver action=retired result=ok") }
            assertEquals("capture retirement is historical, not awaitClosed", 1, retired.size)
            val receiverId = Regex("receiver=([0-9]+)").find(retired.single())!!.groupValues[1]
            assertTrue(lines.any { it.contains("receiver=$receiverId ") && it.contains("action=snapshot result=ok") })
            assertFalse("drained capture retirement is not storage acknowledgment", receiver.awaitClosed(0))
            receiver.close() // Explicit retry of the same receiver/storage owner.
            assertTrue("retry must reach persistent close, not reject forever", retryClose.await(2, TimeUnit.SECONDS))
            assertFalse("retry in persistent close must still fence successor", receiver.awaitClosed(50))
            allowRetryClose.countDown()
            assertTrue("successful retry must eventually acknowledge", receiver.awaitClosed(3000))
            receiver.close()
            assertTrue(receiver.awaitClosed(0))
            assertEquals(2, calls.get())
            assertEquals("retry must never submit into the shutdown executor", 0, rejectedAfterShutdown.get())
            assertEquals("no duplicate capture retirement on storage retry", 1,
                lines.count { it.contains("receiver=$receiverId ") && it.contains("kind=receiver action=retired result=ok") })
        } finally {
            releaseStatuses.countDown(); allowRetryClose.countDown()
            // Also bounded on behavioral RED: interrupt only this fixture's rejected retry owner.
            if (rejectedAfterShutdown.get() > 0) retryWorker.get()?.interrupt()
            firstWorker.get()?.join(1000); retryWorker.get()?.join(1000)
            statuses.shutdownNow(); assertTrue(statuses.awaitTermination(3, TimeUnit.SECONDS))
            realPersistence.close()
            surface.release()
        }
    }
}
