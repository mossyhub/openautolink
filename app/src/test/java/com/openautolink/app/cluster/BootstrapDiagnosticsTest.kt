package com.openautolink.app.cluster

import android.app.Application
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class BootstrapDiagnosticsTest {
    private fun snapshot(generation: Long, event: String, changing: Boolean = false) =
        BootstrapDiagnostics.Snapshot(generation, 123, 42, 0, event, event == "destroy", changing, 500)

    @Test fun stalledSinkNeverBlocksProducerAndLatestTerminalSurvivesDetailExhaustion() {
        val entered = CountDownLatch(1)
        val inventoryEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val terminal = CountDownLatch(1)
        val lines = CopyOnWriteArrayList<String>()
        val diagnostics = BootstrapDiagnostics({ snapshot ->
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            inventoryEntered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            "callbackTaskExists=false task=${snapshot.task}"
        }, { message ->
            assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
            if (entered.count > 0) { entered.countDown(); assertTrue(release.await(3, TimeUnit.SECONDS)) }
            lines.add(message)
            if (message.startsWith("BOOTSTRAP_TASKS_V1 cause=destroy generation=12 ")) terminal.countDown()
        })
        diagnostics.offer(snapshot(10, "create"))
        assertTrue(entered.await(3, TimeUnit.SECONDS))
        assertTrue(inventoryEntered.await(3, TimeUnit.SECONDS))
        try {
            // Every call completes while the actual sink is still held; not a wall-time benchmark.
            repeat(100) { diagnostics.offer(snapshot(10, "resume")) }
            diagnostics.offer(snapshot(10, "destroy"))
            diagnostics.offer(snapshot(11, "destroy"))
            diagnostics.offer(snapshot(12, "destroy"))
            assertEquals(1L, release.count)
        } finally { release.countDown() }
        assertTrue(terminal.await(3, TimeUnit.SECONDS))
        val final = lines.first { it.startsWith("BOOTSTRAP_LIFETIME_V1 event=destroy generation=12 ") }
        assertTrue(final.contains("terminalCoalesced=2"))
        assertTrue(final.contains("finalizationCaller=UNKNOWN"))
        assertTrue(final.contains("finishing=true"))
        assertTrue(Regex("rejected=[1-9][0-9]*").containsMatchIn(final))
        assertTrue(lines.none { it.startsWith("BOOTSTRAP_LIFETIME_V1 event=destroy generation=10 ") })
    }

    @Test fun configuredRecreationCannotConsumeFinalTeardownAllowance() {
        val lines = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)
        val diagnostics = BootstrapDiagnostics({ "callbackTaskExists=unknown inventory=unavailable" }, {
            lines.add(it)
            if (it.startsWith("BOOTSTRAP_TASKS_V1 cause=destroy ")) done.countDown()
        })
        repeat(30) { diagnostics.offer(snapshot(30, "destroy", changing = true)) }
        diagnostics.offer(snapshot(30, "destroy"))
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(lines.any { it.contains("event=destroy generation=30") && it.contains("changingConfigurations=false") })
        assertTrue(lines.size <= 20)
    }

    @Test fun inventoryAndObserverRunOutsideAdmissionMonitor() {
        val inventory = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val diagnostics = BootstrapDiagnostics({
            inventory.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            "callbackTaskExists=true"
        }, { if (it.contains("event=destroy")) finished.countDown() })
        diagnostics.offer(snapshot(40, "create"))
        assertTrue(inventory.await(3, TimeUnit.SECONDS))
        try {
            diagnostics.offer(snapshot(40, "stop"))
            diagnostics.offer(snapshot(40, "destroy"))
            assertEquals(1L, release.count)
        } finally { release.countDown() }
        assertTrue(finished.await(3, TimeUnit.SECONDS))
    }
}
