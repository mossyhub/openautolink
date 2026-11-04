package com.openautolink.app.cluster

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [32])
class ClusterProcessAdmissionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun display(): QualifiedClusterDisplay {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        return requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle, listOf(ClusterDisplayBinding(p.role, 7)),
            ClusterHostGeometry(7, p.panel, p.panel.bounds, p.panel.bounds)))
    }
    private fun preview() = ClusterCalibrationPreview.forProduction(context) { true }
    private fun start(p: ClusterCalibrationPreview): Any {
        val d = display(); return p.start(d, d.effectiveOutput, d.safe, 15)
    }
    private fun await(f: CompletableFuture<Boolean>): Boolean {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!f.isDone && System.nanoTime() < until) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.sleep(2)
        }
        return f.get(1, TimeUnit.SECONDS)
    }
    @Test fun directVideoAllowsOnlyExplicitGuidanceAndKeepsBothExactSlotsFenced() {
        val directOnly = requireNotNull(ClusterProcessAdmission.acquireDirect(Any(), false))
        try {
            assertNull(ClusterProcessAdmission.acquireGuidance(Any()))
            assertNull(ClusterProcessAdmission.acquire(Any()))
            assertNull(ClusterProcessAdmission.acquireDirect(Any(), true))
        } finally { assertTrue(ClusterProcessAdmission.release(directOnly)) }
        val direct = requireNotNull(ClusterProcessAdmission.acquireDirect(Any(), true))
        val guidance = requireNotNull(ClusterProcessAdmission.acquireGuidance(Any()))
        try {
            assertTrue(ClusterProcessAdmission.owns(direct)); assertTrue(ClusterProcessAdmission.owns(guidance))
            assertFalse(ClusterProcessAdmission.exclusive(guidance))
            assertNull(ClusterProcessAdmission.acquire(Any()))
            assertNull(ClusterProcessAdmission.acquireGuidance(Any()))
            assertNull(ClusterProcessAdmission.acquireDirect(Any(), true))
            assertTrue(ClusterProcessAdmission.release(direct))
            assertFalse(ClusterProcessAdmission.release(direct))
            assertTrue(ClusterProcessAdmission.owns(guidance))
            // Old guidance must retire before any new direct session or preview.
            assertNull(ClusterProcessAdmission.acquireDirect(Any(), true))
            assertNull(ClusterProcessAdmission.acquire(Any()))
        } finally { ClusterProcessAdmission.release(direct); assertTrue(ClusterProcessAdmission.release(guidance)) }
    }

    @Test fun retiringGuidanceDoesNotReleaseItsStillLiveDirectVideoOwner() {
        val direct = requireNotNull(ClusterProcessAdmission.acquireDirect(Any(), true))
        val guidance = requireNotNull(ClusterProcessAdmission.acquireGuidance(Any()))
        try {
            assertTrue(ClusterProcessAdmission.release(guidance))
            assertTrue(ClusterProcessAdmission.owns(direct))
            assertNull(ClusterProcessAdmission.acquire(Any()))
            val next = requireNotNull(ClusterProcessAdmission.acquireGuidance(Any()))
            try {
                assertFalse(ClusterProcessAdmission.release(guidance))
                assertTrue(ClusterProcessAdmission.owns(next))
            } finally { assertTrue(ClusterProcessAdmission.release(next)) }
        } finally { ClusterProcessAdmission.release(guidance); assertTrue(ClusterProcessAdmission.release(direct)) }
    }

    @Test fun sharingManagerInstanceCannotBypassAnIconOrPreviewReservation() {
        val manager = ClusterManager(context); val ownership = ClusterOutputOwnership(manager)
        manager.setClusterEnabled(true)
        try { assertNull(ownership.tryAcquire(Any())) } finally { manager.release() }
        val claim = requireNotNull(ownership.tryAcquire(Any()))
        try {
            manager.setClusterEnabled(true); manager.release()
            val d = display()
            assertNull(manager.beginMapOutput(41, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO),
                ClusterProjectionProtocol.ANDROID_AUTO, d, io.mockk.mockk()))
            assertNull(ClusterProcessAdmission.acquire(Any()))
        } finally { assertTrue(claim.abandon()); manager.release() }
    }

    @Test fun iconOwnerEvenWithoutRouteBlocksProductionPreviewAndIdleManagerCannotDisplaceIt() {
        val live = ClusterManager(context); val idle = ClusterManager(context); val p = preview()
        try {
            live.setClusterEnabled(true)
            val registered = requireNotNull(ClusterBindingState.registerSession())
            assertThrows(IllegalStateException::class.java) { start(p) }
            idle.release()
            assertTrue(ClusterBindingState.isSessionCurrent(registered))
        } finally { live.release(); idle.release() }
    }
    @Test fun productionPreviewBlocksBothProtocolsAndStaleDestructorCannotReleaseSuccessor() {
        val p = preview(); val owner = start(p); val a = ClusterManager(context); val b = ClusterManager(context)
        try {
            val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
            val bridge = object : ClusterHostOutputBridge {
                override fun attach(l: ClusterOutputLease, s: android.view.Surface, g: ClusterHostGeometry) = CompletableFuture.completedFuture(true)
                override fun updateBounds(l: ClusterOutputLease, s: android.view.Surface, g: ClusterHostGeometry) = CompletableFuture.completedFuture(true)
                override fun detach(l: ClusterOutputLease, s: android.view.Surface) = CompletableFuture.completedFuture(true)
            }
            for (protocol in ClusterProjectionProtocol.entries) {
                assertNull(a.beginMapOutput(200, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, true, true, false), protocol, display(), bridge))
            }
            b.setClusterEnabled(true); a.release(); b.release()
            assertSame(binding, ClusterBindingState.frozenMapBinding())
            assertTrue(await(p.stop(owner)))
            b.setClusterEnabled(true)
            a.release()
            assertThrows(IllegalStateException::class.java) { start(preview()) }
        } finally { await(p.stop(owner)); a.release(); b.release() }
    }
    @Test fun bothRealProtocolOwnersBlockPreviewWithoutInvalidatingTheirFrozenBinding() {
        for (protocol in ClusterProjectionProtocol.entries) {
            val manager = ClusterManager(context); val d = display(); val p = preview()
            val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, true, true, false)
            val lease = requireNotNull(manager.beginMapOutput(80, selection, protocol, d, io.mockk.mockk()))
            val frozen = requireNotNull(ClusterBindingState.frozenMapBinding())
            try {
                assertThrows(IllegalStateException::class.java) { start(p) }
                assertSame(frozen, ClusterBindingState.frozenMapBinding())
            } finally { assertTrue(await(manager.endMapOutput(lease, selection, "test"))); manager.release() }
        }
    }

    @Test fun realMapAndPreviewContendAtomicallyForBothProtocols() {
        for (protocol in ClusterProjectionProtocol.entries) repeat(4) {
            val manager = ClusterManager(context); val p = preview(); val d = display()
            val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, true, true, false)
            val go = CountDownLatch(1)
            val map = java.util.concurrent.atomic.AtomicReference<ClusterOutputLease?>()
            val grid = java.util.concurrent.atomic.AtomicReference<Any?>()
            val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val phone = Thread { try { go.await(); map.set(manager.beginMapOutput(123, selection, protocol, d, io.mockk.mockk())) } catch (e: Throwable) { failures.add(e) } }
            val previewThread = Thread { try { go.await(); grid.set(start(p)) } catch (_: IllegalStateException) {} catch (e: Throwable) { failures.add(e) } }
            phone.start(); previewThread.start(); go.countDown()
            phone.join(3000); previewThread.join(3000)
            try {
                assertFalse(phone.isAlive); assertFalse(previewThread.isAlive); assertTrue(failures.toString(), failures.isEmpty())
                assertTrue((map.get() != null) xor (grid.get() != null))
            } finally {
                grid.get()?.let { assertTrue(await(p.stop(it))) }
                map.get()?.let { assertTrue(await(manager.endMapOutput(it, selection, "race cleanup"))) }
                manager.release()
            }
        }
    }

    @Test fun atomicSlotRaceHasOneWinnerAndStaleReleaseCannotReleaseSuccessor() {
        repeat(50) {
            val go = CountDownLatch(1)
            val results = java.util.concurrent.CopyOnWriteArrayList<ClusterProcessAdmission.Claim>()
            val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
            val threads = (1..2).map {
                Thread {
                    try { go.await(); ClusterProcessAdmission.acquire(Any())?.let(results::add) }
                    catch (failure: Throwable) { failures.add(failure) }
                }.apply { start() }
            }
            go.countDown(); threads.forEach { it.join(2000); assertFalse(it.isAlive) }
            assertTrue(failures.isEmpty()); assertEquals(1, results.size)
            val old = results.single(); assertTrue(ClusterProcessAdmission.release(old))
            val next = requireNotNull(ClusterProcessAdmission.acquire(Any()))
            try { assertFalse(ClusterProcessAdmission.release(old)); assertTrue(ClusterProcessAdmission.owns(next)) }
            finally { assertTrue(ClusterProcessAdmission.release(next)) }
        }
    }

    @Test fun unknownProfileCannotAcquireEvenWhenFabricatedQualifiedObjectExists() {
        val d = display(); val unknown = QualifiedClusterDisplay(d.profile.copy(id = "unknown"), d.host, d.effectiveOutput)
        val manager = ClusterManager(context)
        try {
            assertNull(manager.beginMapOutput(90, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, true, true, false),
                ClusterProjectionProtocol.ANDROID_AUTO, unknown, io.mockk.mockk()))
            val p = preview(); val owner = start(p); assertTrue(await(p.stop(owner)))
        } finally { manager.release() }
    }

    @Test fun synchronousBackendStopAndPublicCompletionNeverHoldPreviewMonitor() {
        lateinit var p: ClusterCalibrationPreview
        val retirement = CompletableFuture<Boolean>()
        var heldAtBackend = true
        p = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ ->
            object : ClusterCalibrationPreview.Session {
                override fun stop(): CompletableFuture<Boolean> {
                    heldAtBackend = Thread.holdsLock(p)
                    return retirement
                }
            }
        }, { 0L }, { _, _ -> {} })
        val d = display(); val owner = p.start(d, d.effectiveOutput, d.safe, 15)
        val result = p.stop(owner)
        var heldAtReceipt = true
        result.thenRun { heldAtReceipt = Thread.holdsLock(p) }
        retirement.complete(true)
        assertTrue(result.get()); assertFalse(heldAtBackend); assertFalse(heldAtReceipt)
    }

    @Test fun ownershipInvokesSynchronousManagerAndCompletesOutsideOwnershipMonitor() {
        val manager = io.mockk.mockk<ClusterManager>()
        val ownership = ClusterOutputOwnership(manager)
        val lease = ClusterOutputLease(55, 66)
        val retirement = CompletableFuture<Boolean>()
        io.mockk.every { manager.reservePreviewAdmission(any()) } returns true
        io.mockk.every { manager.beginMapOutput(any(), any(), any(), any(), any(), any(), any()) } answers {
            assertFalse(Thread.holdsLock(ownership)); lease
        }
        io.mockk.every { manager.endMapOutput(any(), any(), any()) } answers {
            assertFalse(Thread.holdsLock(ownership)); retirement
        }
        val claim = requireNotNull(ownership.tryAcquire(Any()))
        val d = display()
        claim.begin(55, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, true, true, false),
            ClusterProjectionProtocol.ANDROID_AUTO, d, io.mockk.mockk())
        val result = claim.end("test")
        var held = true
        result.thenRun { held = Thread.holdsLock(ownership) }
        retirement.complete(true)
        assertTrue(result.get()); assertFalse(held)
    }

    @Test fun actualManagerRetirementReceiptNeverHoldsLifecycleLock() {
        val manager = ClusterManager(context); val d = display()
        val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, true, true, false)
        val bridge = io.mockk.mockk<ClusterHostOutputBridge>()
        val detach = CompletableFuture<Boolean>()
        io.mockk.every { bridge.attach(any(), any(), any()) } returns CompletableFuture.completedFuture(true)
        io.mockk.every { bridge.detach(any(), any()) } returns detach
        val lease = requireNotNull(manager.beginMapOutput(71, selection, ClusterProjectionProtocol.ANDROID_AUTO, d, bridge))
        val coordinator = requireNotNull(manager.mapOutputCoordinator(lease, selection))
        val host = Any(); val surface = io.mockk.mockk<android.view.Surface>()
        io.mockk.every { surface.isValid } returns true
        assertTrue(coordinator.claim(host)); assertTrue(await(coordinator.attachHost(lease, host, surface, d.host)))
        val result = manager.endMapOutput(lease, selection, "test")
        var held = true
        result.thenRun { held = Thread.holdsLock(ClusterBindingLifecycle.lock) }
        detach.complete(true)
        try { assertTrue(await(result)); assertFalse(held) } finally { manager.release() }
    }

    @Test fun busyProjectionPredicateRejectsBeforeOpeningManagerGeneration() {
        val p = ClusterCalibrationPreview.forProduction(context) { false }
        assertThrows(IllegalStateException::class.java) { start(p) }
        assertNull(p.activeOwner)
        val next = preview(); val owner = start(next)
        assertTrue(await(next.stop(owner)))
    }
}
