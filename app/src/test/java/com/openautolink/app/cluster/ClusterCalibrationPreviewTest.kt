package com.openautolink.app.cluster

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.mockk.*
import android.view.Surface
import android.graphics.Canvas
import java.util.concurrent.Executor
import java.util.concurrent.CompletableFuture

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [32])
class ClusterCalibrationPreviewTest {
    @Test fun exactOwnerStopCannotStopReplacement() {
        val backend = FakeBackend()
        val clock = ManualClock()
        val preview = ClusterCalibrationPreview(backend, clock::now, clock::schedule)
        val old = preview.start(display(), display().effectiveOutput, display().safe, 15)
        assertTrue(preview.isActive(old))
        assertTrue(preview.stop(old).get())
        val replacement = preview.start(display(), display().effectiveOutput, display().safe, 15)
        assertTrue(preview.stop(old).get())
        assertTrue(preview.isActive(replacement))
        assertEquals(1, backend.stops)
        preview.stop(replacement).get()
    }

    @Test fun watchdogStopsHeadlessOwnerAtElapsedDeadlineAndCancelsTask() {
        val backend = FakeBackend()
        val clock = ManualClock()
        val preview = ClusterCalibrationPreview(backend, clock::now, clock::schedule)
        val owner = preview.start(display(), display().effectiveOutput, display().safe, 200)
        clock.time = 14999; clock.fire()
        assertTrue(preview.isActive(owner))
        clock.time = 15000; clock.fire()
        assertFalse(preview.isActive(owner))
        assertEquals(1, backend.stops)
        assertTrue(clock.cancelled)
    }

    @Test fun failedRetirementFencesReplacementAndExplicitStopRetriesExactLease() {
        val backend = FakeBackend().apply { retirement = CompletableFuture() }
        val clock = ManualClock()
        val preview = ClusterCalibrationPreview(backend, clock::now, clock::schedule)
        val owner = preview.start(display(), display().effectiveOutput, display().safe, 15)
        val pending = preview.stop(owner)
        assertFalse(pending.isDone)
        assertThrows(IllegalStateException::class.java) { preview.start(display(), display().effectiveOutput, display().safe, 15) }
        backend.retirement.complete(false)
        assertFalse(pending.get())
        backend.retirement = CompletableFuture.completedFuture(true)
        assertTrue(preview.stop(owner).get())
        val next = preview.start(display(), display().effectiveOutput, display().safe, 15)
        preview.stop(next).get()
    }

    @Test fun deniedAdmissionDoesNotPretendPreviewIsActive() {
        val backend = FakeBackend().apply { admitted = false }
        val clock = ManualClock()
        val preview = ClusterCalibrationPreview(backend, clock::now, clock::schedule)
        assertThrows(IllegalStateException::class.java) { preview.start(display(), display().effectiveOutput, display().safe, 15) }
        assertNull(preview.activeOwner)
        assertEquals(0, backend.stops)
    }

    @Test fun queuedExpiredStartCannotAcquireOutput() {
        val backend = FakeBackend()
        val clock = ManualClock().apply { time = 9000 }
        val preview = ClusterCalibrationPreview(backend, clock::now, clock::schedule)
        assertThrows(IllegalArgumentException::class.java) { preview.start(display(), display().effectiveOutput, display().safe, 0) }
        assertNull(preview.activeOwner)
    }

    @Test fun queuedDrawIsFencedByStopAndTerminalClearSurvivesFullOrdinaryQueue() {
        val jobs = ArrayDeque<Runnable>()
        val paints = mutableListOf<Boolean>()
        val d = display(); val lease = ClusterOutputLease(-1, 21)
        val surface = mockk<Surface>()
        val bridge = ClusterCalibrationGridBridge(d, d.effectiveOutput, d.safe, 15000, { 0L }, Executor { jobs.add(it) }) { _, clear -> paints += clear; true }
        bridge.bind(lease)
        val attach = bridge.attach(lease, surface, d.host)
        val updates = (1..12).map { bridge.updateBounds(lease, surface, d.host) }
        assertEquals(8, jobs.size)
        val detach = bridge.detach(lease, surface)
        assertEquals(9, jobs.size)
        assertSame(detach, bridge.detach(lease, surface))
        while (jobs.isNotEmpty()) jobs.removeFirst().run()
        assertFalse(attach.get())
        assertTrue(updates.all { !it.get() })
        assertTrue(detach.get())
        assertEquals(listOf(true), paints)
        verify(exactly = 0) { surface.release() }
    }

    @Test fun actualSurfaceCanvasPostsOpaqueGridThenBlackWithoutReleasingBorrowedSurface() {
        val d = display(); val lease = ClusterOutputLease(-1, 22)
        val canvas = mockk<Canvas>(relaxed = true)
        every { canvas.width } returns d.profile.panel.width
        every { canvas.height } returns d.profile.panel.height
        val surface = mockk<Surface>(relaxed = true)
        every { surface.isValid } returns true
        every { surface.lockCanvas(null) } returns canvas
        val bridge = ClusterCalibrationGridBridge(d, d.effectiveOutput, d.safe, 15000, { 0L }, Executor { it.run() })
        bridge.bind(lease)
        assertTrue(bridge.attach(lease, surface, d.host).get())
        verify { canvas.drawText(match { it.startsWith("OUTPUT 1806x680") }, any(), any(), any()) }
        verify { canvas.drawText(match { it.startsWith("SAFE 752x504") }, any(), any(), any()) }
        assertTrue(bridge.detach(lease, surface).get())
        verify(exactly = 2) { canvas.drawColor(android.graphics.Color.BLACK) }
        verify(exactly = 2) { surface.unlockCanvasAndPost(canvas) }
        verify(exactly = 0) { surface.release() }
    }

    @Test fun expiredQueuedAttachAndFailedClearKeepExactBorrowedDebtUntilRetry() {
        val jobs = ArrayDeque<Runnable>(); var time = 0L; var clearSucceeds = false
        val d = display(); val lease = ClusterOutputLease(-1, 23)
        val old = mockk<Surface>(); val next = mockk<Surface>()
        val bridge = ClusterCalibrationGridBridge(d, d.effectiveOutput, d.safe, 15000, { time }, Executor { jobs.add(it) }) { _, clear -> clear && clearSucceeds }
        bridge.bind(lease)
        val attach = bridge.attach(lease, old, d.host)
        time = 15000; jobs.removeFirst().run(); assertFalse(attach.get())
        val failed = bridge.detach(lease, old); jobs.removeFirst().run(); assertFalse(failed.get())
        assertFalse(bridge.attach(lease, next, d.host).get())
        assertFalse(bridge.detach(lease, next).get())
        clearSucceeds = true
        val retry = bridge.detach(lease, old); jobs.removeFirst().run(); assertTrue(retry.get())
    }

    @Test fun realManagerPreviewAdmissionCannotDisplaceOwnedProjectionAndReleasesOnStop() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val manager = ClusterManager(context)
        val ownership = ClusterOutputOwnership(manager)
        val projection = requireNotNull(ownership.tryAcquire(Any()))
        val preview = ClusterCalibrationPreview(ownership)
        try {
            assertThrows(IllegalStateException::class.java) { preview.start(display(), display().effectiveOutput, display().safe, 15) }
            assertTrue(projection.abandon())
            val exact = preview.start(display(), display().effectiveOutput, display().safe, 15)
            assertNull(ownership.tryAcquire(Any()))
            val result = preview.stop(exact)
            val until = System.nanoTime() + 5_000_000_000L
            while (!result.isDone && System.nanoTime() < until) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertTrue(result.get(1, java.util.concurrent.TimeUnit.SECONDS))
            val successor = requireNotNull(ownership.tryAcquire(Any()))
            assertTrue(successor.abandon())
        } finally {
            preview.activeOwner?.let { preview.stop(it) }
            manager.release()
        }
    }

    @Test fun realPreviewWorkerRetainsProcessAdmissionUntilExactBlackClearAcknowledges() {
        val manager = ClusterManager(org.robolectric.RuntimeEnvironment.getApplication())
        val ownership = ClusterOutputOwnership(manager)
        val preview = ClusterCalibrationPreview(ownership)
        val d = display()
        val canvas = mockk<Canvas>(relaxed = true)
        every { canvas.width } returns d.profile.panel.width
        every { canvas.height } returns d.profile.panel.height
        val surface = mockk<Surface>(relaxed = true)
        every { surface.isValid } returns true
        every { surface.lockCanvas(null) } returns canvas
        val exact = preview.start(d, d.effectiveOutput, d.safe, 15)
        try {
            val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
            val host = Any()
            assertTrue(binding.coordinator.claim(host))
            assertTrue(await(binding.coordinator.attachHost(binding.snapshot.lease, host, surface, d.host)))
            every { surface.lockCanvas(null) } throws IllegalStateException("OS clear failure")
            assertFalse(await(preview.stop(exact)))
            assertNull(ownership.tryAcquire(Any()))
            every { surface.lockCanvas(null) } returns canvas
            assertTrue(await(preview.stop(exact)))
            assertTrue(requireNotNull(ownership.tryAcquire(Any())).abandon())
            verify(exactly = 0) { surface.release() }
        } finally {
            every { surface.lockCanvas(null) } returns canvas
            await(preview.stop(exact)); manager.release()
        }
    }

    private fun await(result: CompletableFuture<Boolean>): Boolean {
        val until = System.nanoTime() + 5_000_000_000L
        while (!result.isDone && System.nanoTime() < until) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        return result.get(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    private class ManualClock {
        var time = 0L
        var cancelled = false
        var action: (() -> Unit)? = null
        fun now() = time
        fun schedule(delay: Long, callback: () -> Unit): () -> Unit {
            assertTrue(delay in 1..15000)
            action = callback
            return { cancelled = true }
        }
        fun fire() { action?.invoke() }
    }
    private class FakeBackend : ClusterCalibrationPreview.Backend {
        var admitted = true
        var stops = 0
        var retirement = CompletableFuture.completedFuture(true)
        override fun begin(owner: Any, display: QualifiedClusterDisplay, output: PixelRect, safe: PixelRect, deadline: Long): ClusterCalibrationPreview.Session? =
            if (!admitted) null else object : ClusterCalibrationPreview.Session {
                override fun stop(): CompletableFuture<Boolean> { stops++; return retirement }
            }
    }
    private fun display(): QualifiedClusterDisplay {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        return requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,
            listOf(ClusterDisplayBinding(p.role, 7)), ClusterHostGeometry(7, p.panel, p.panel.bounds, p.panel.bounds)))
    }
}
