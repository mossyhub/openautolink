package com.openautolink.app.session

import android.os.Handler
import android.os.Looper
import com.openautolink.app.secondary.SecondaryHostRegistry
import com.openautolink.app.secondary.SecondaryOutputReservation
import com.openautolink.app.secondary.SecondaryOutputRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

/** Test-owned process boundary. A non-null pointer is not a live-owner census.
 * Only real close + exact retirement acknowledgments permit relinquishing that pointer.
 * Never restore a closed old-context runtime or reset capture/admission/retirement state.
 */
internal class SecondaryRuntimeProcessTestFixture {
    fun retirePredecessor() {
        SecondaryOutputRuntime.peekForDiagnostics()?.let { retireAndRelinquish(it) }
    }

    fun install(runtime: SecondaryOutputRuntime) {
        // Same monitor as production get(): do not overwrite a concurrently installed owner.
        synchronized(SecondaryOutputRuntime.Companion) {
            assertNull("fixture must not replace a live runtime", SecondaryOutputRuntime.peekForDiagnostics())
            ReflectionHelpers.setStaticField(SecondaryOutputRuntime::class.java, "process", runtime)
        }
    }

    fun retireAndRelinquish(exact: SecondaryOutputRuntime) {
        assertSame("retire only the captured process runtime", exact, SecondaryOutputRuntime.peekForDiagnostics())
        val registry = ReflectionHelpers.getField<SecondaryHostRegistry>(exact, "registry")
        val main = ReflectionHelpers.getField<Handler>(registry, "main").looper
        val closed = exact.close() // Real retained-host cancellation and reservation retirement.
        drainUntil("exact process runtime close did not complete; pointer retained", main) { closed.isDone }
        // Exceptional, false or timed-out completion must NEVER clear the pointer.
        assertTrue("exact process runtime retirement was not acknowledged; pointer retained",
            closed.get(0, TimeUnit.MILLISECONDS))

        val runtimeJob = requireNotNull(ReflectionHelpers.getField<CoroutineScope>(exact, "scope").coroutineContext[Job])
        val registryJob = requireNotNull(ReflectionHelpers.getField<CoroutineScope>(registry, "scope").coroutineContext[Job])
        val intake = ReflectionHelpers.getField<Thread>(registry, "intakeThread")
        // close() requests cancellation/quit; its future alone does not join these workers.
        // Do not cancel them here: production close must be what actually retires them.
        drainUntil("closed process runtime workers did not finish; pointer retained", main) {
            runtimeJob.isCompleted && registryJob.isCompleted && !intake.isAlive
        }
        runBlocking { runtimeJob.join(); registryJob.join() }

        // close() acknowledges the current reservation. Older failed detach debt can remain
        // outside that reservation, so also inspect the real exact-owner ledgers, read-only.
        val lock = ReflectionHelpers.getField<Any>(exact, "lock")
        synchronized(lock) {
            assertTrue("runtime retains output/retirement debt; pointer retained",
                ReflectionHelpers.getField<Map<*, *>>(exact, "owners").isEmpty())
            assertTrue("runtime retains host ownership; pointer retained",
                ReflectionHelpers.getField<Map<*, *>>(exact, "hostOwners").isEmpty())
            assertNull("runtime retains reconnect host; pointer retained",
                ReflectionHelpers.getField<Any?>(exact, "retainedOutput"))
            val reservation = ReflectionHelpers.getField<SecondaryOutputReservation<*>?>(exact, "reservation")
            assertTrue("reservation retains an unacknowledged owner; pointer retained",
                reservation == null || reservation.snapshot().isEmpty())
        }
        // Registry close can enqueue Main work. Drain it before checking its exact entries;
        // processEntries intentionally retains failed detach debt even after token revocation.
        shadowOf(main).idle()
        assertTrue("registry retains exact host debt; pointer retained",
            ReflectionHelpers.getField<Set<*>>(registry, "entries").isEmpty())
        val processEntries = ReflectionHelpers.getStaticField<Set<*>>(SecondaryHostRegistry::class.java, "processEntries")
        assertFalse("process registry retains exact predecessor debt; pointer retained",
            processEntries.any { entry -> entry != null && ReflectionHelpers.getField<Any>(entry, "registry") === registry })

        synchronized(SecondaryOutputRuntime.Companion) {
            assertSame("process owner changed during retirement; successor preserved",
                exact, SecondaryOutputRuntime.peekForDiagnostics())
            // Sole state write on retirement: release only this positively closed pointer.
            ReflectionHelpers.setStaticField(SecondaryOutputRuntime::class.java, "process", null)
        }
    }

    private fun drainUntil(boundary: String, main: Looper, complete: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!complete()) {
            // Dispatch the captured registry's handler, not just the new application's Main.
            shadowOf(main).idle()
            if (main !== Looper.getMainLooper()) shadowOf(Looper.getMainLooper()).idle()
            if (complete()) return
            assertTrue(boundary, System.nanoTime() < deadline)
            Thread.sleep(5)
        }
    }
}
