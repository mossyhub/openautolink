package com.openautolink.app.cluster

import android.app.Application
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [32])
class ClusterPreviewPublicationTest {
    @Test fun stoppedStartCannotOverwriteOrClearPublishedSuccessor() = race(Any(), Any())

    @Test fun stoppedStartCannotClearSuccessorReusingTheSameOwner() {
        val owner = Any()
        race(owner, owner)
    }

    private fun race(old: Any, replacement: Any) {
        val installed = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val failures = CopyOnWriteArrayList<Throwable>()
        val observed = CopyOnWriteArrayList<Any?>()
        val stopped = CopyOnWriteArrayList<Any>()
        val armed = CopyOnWriteArrayList<() -> Unit>()
        lateinit var preview: ClusterCalibrationPreview
        preview = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { owner, _, _, _, _ ->
            assertFalse(Thread.holdsLock(preview))
            object : ClusterCalibrationPreview.Session {
                override fun stop(): CompletableFuture<Boolean> {
                    assertFalse(Thread.holdsLock(preview))
                    stopped.add(owner)
                    return CompletableFuture.completedFuture(true)
                }
            }
        }, { 0L }, { _, callback ->
            assertFalse(Thread.holdsLock(preview))
            armed.add(callback)
            val cancel: () -> Unit = {}
            cancel
        }, {
            assertFalse(Thread.holdsLock(preview))
            if (first.compareAndSet(true, false)) {
                installed.countDown()
                check(resume.await(5, TimeUnit.SECONDS)) { "publication barrier not released" }
            }
        })
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val display = requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle,
            listOf(ClusterDisplayBinding(p.role, 7)),
            ClusterHostGeometry(7, p.panel, p.panel.bounds, p.panel.bounds)))
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            preview.owner.collect {
                assertFalse("owner observers must run outside preview monitor", Thread.holdsLock(preview))
                observed.add(it)
            }
        }
        val a = Thread {
            try { assertSame(old, preview.start(display, display.effectiveOutput, display.safe, 15, old)) }
            catch (failure: Throwable) { failures.add(failure) }
        }
        try {
            a.start()
            assertTrue("A must reach actual installed-run/publication boundary", installed.await(5, TimeUnit.SECONDS))
            assertTrue(preview.isActive(old))
            assertNull(preview.activeOwner)
            assertTrue(preview.stop(old).get(1, TimeUnit.SECONDS))
            assertSame(replacement, preview.start(display, display.effectiveOutput, display.safe, 15, replacement))
            assertSame(replacement, preview.activeOwner)
            assertSame(replacement, preview.owner.value)
            assertTrue(preview.isActive(replacement))
            val beforeOldPublication = observed.size
            resume.countDown()
            a.join(5000)
            assertFalse("A must complete", a.isAlive)
            assertTrue(failures.toString(), failures.isEmpty())
            assertSame("late A must not erase B", replacement, preview.activeOwner)
            assertSame(replacement, preview.owner.value)
            assertTrue(preview.isActive(replacement))
            assertEquals("late A must not publish stale owner or null", beforeOldPublication, observed.size)
            assertEquals(1, stopped.size)
            assertSame(old, stopped.single())
            assertEquals("retired A must not arm a watchdog", 1, armed.size)
            assertTrue(preview.stop(replacement).get(1, TimeUnit.SECONDS))
            assertNull(preview.activeOwner)
            assertEquals(2, stopped.size)
        } finally {
            resume.countDown()
            a.join(5000)
            preview.stop(old).get(1, TimeUnit.SECONDS)
            preview.stop(replacement).get(1, TimeUnit.SECONDS)
            observer.cancel()
            scope.cancel()
        }
    }
}
