package com.openautolink.app.diagnostics.live

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.rules.ExternalResource
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** A test process boundary, not a reset of capture/admission/retirement authority.
 * Retire the real old owner before initialize may bind the next test's application context.
 * Keep the process SupervisorJob alive and never restore an owner from an old sandbox.
 */
internal class LiveProcessTestFixture : ExternalResource() {
    private val ownerField = LiveDiagnostics::class.java.getDeclaredField("owner").apply { isAccessible = true }
    private val scopeField = LiveDiagnostics::class.java.getDeclaredField("scope").apply { isAccessible = true }

    override fun before() {
        retire()
        assertFalse("process boundary must leave the observer uninitialized", LiveDiagnostics.ready.value)
    }

    override fun after() = retire()

    /** Also call inside a constructor-mocking fixture's finally, BEFORE removing its mocks. */
    fun retire() {
        val owner = ownerField.get(LiveDiagnostics)
        val scope = scopeField.get(LiveDiagnostics) as CoroutineScope
        val processJob = requireNotNull(scope.coroutineContext[Job])
        val jobs = linkedSetOf<Job>()
        // restore()/preference operations need not be retained in the owner's job fields.
        jobs.addAll(processJob.children.toList())
        LiveDiagnostics.stop()
        jobs.addAll(processJob.children.toList())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            // Include children launched by an in-flight restore before stop took its guard.
            jobs.addAll(processJob.children.toList())
            jobs.forEach { it.cancel() }
            shadowOf(Looper.getMainLooper()).idle()
            if (jobs.all { it.isCompleted } && processJob.children.none()) break
            assertTrue("observer children did not retire; owner retained, unfinished=${jobs.count { !it.isCompleted }}",
                System.nanoTime() < deadline)
            Thread.sleep(5)
        }
        // Join the exact captured jobs (including their descendants), not just an idle Main queue.
        runBlocking { withTimeout(5_000) { jobs.forEach { it.join() } } }
        assertTrue("observer process must have no children before relinquishing owner", processJob.children.none())
        assertTrue("fixture must not cancel the reusable process scope", processJob.isActive)
        synchronized(LiveDiagnostics) {
            assertSame("observer owner changed during retirement", owner, ownerField.get(LiveDiagnostics))
            // Only the context-bearing observer pointer is released, after real stop + quiescence.
            ownerField.set(LiveDiagnostics, null)
        }
    }
}
