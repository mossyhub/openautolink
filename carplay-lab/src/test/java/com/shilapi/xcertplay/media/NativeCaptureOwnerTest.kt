package com.shilapi.xcertplay.media

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class NativeCaptureOwnerTest {
    private class Queue : Executor {
        val tasks = java.util.ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.remove().run() }
    }
    private class Native : NativeCaptureOwnerRegistry.NativeRun {
        val calls = mutableListOf<String>()
        var receipt: NativeCaptureOwnerRegistry.Receipt? = NativeCaptureOwnerRegistry.Receipt(1, 0, 0, 0, false)
        override fun begin() { calls += "begin" }
        override fun cancel() { calls += "cancel" }
        override fun pollClosedJoin(): NativeCaptureOwnerRegistry.Receipt? { calls += "poll"; return receipt }
        override fun disposeJoined() { calls += "dispose" }
    }
    private fun command(registry: NativeCaptureOwnerRegistry, name: String, owner: NativeCaptureOwnerRegistry.Owner) {
        val method = registry.javaClass.methods.singleOrNull { it.name == name }
        assertNotNull("retained owner needs exact-token $name", method)
        method!!.invoke(registry, owner)
    }

    @Test fun policyRevocationDuringOpenReadCloseKeepsLeaseUntilJoined() {
        for (phase in listOf("open", "read", "close")) {
            val queue = Queue()
            var onMain = false
            val registry = NativeCaptureOwnerRegistry(queue) { onMain }
            val native = Native().apply { receipt = null }
            val owner = registry.start { native }!!
            try {
                queue.drain() // native worker is pending in the selected phase
                onMain = true
                command(registry, "requestCancel", owner)
                assertEquals("no native call on policy caller during $phase", listOf("begin", "poll"), native.calls)
                assertEquals(NativeCaptureOwnerRegistry.State.STOPPING, owner.state)
                assertNull(MicrophoneCaptureFence.tryProjectionClaim())
                assertNull(registry.start { Native() })
                onMain = false
                queue.drain()
                assertTrue(native.calls.contains("cancel"))
                assertFalse(native.calls.contains("dispose"))
                command(registry, "poll", owner)
                queue.drain() // timeout/pending is never release
                assertNull(MicrophoneCaptureFence.tryNormal())
                native.receipt = NativeCaptureOwnerRegistry.Receipt(2, 0, -9, 0, false)
                command(registry, "poll", owner)
                queue.drain()
                assertEquals(NativeCaptureOwnerRegistry.State.RETIRED, owner.state)
                assertEquals(-9, owner.receipt!!.stopError)
                MicrophoneCaptureFence.tryNormal()!!.release()
                val successorNative = Native().apply { receipt = null }
                val successor = registry.start { successorNative }!!
                command(registry, "requestCancel", owner)
                command(registry, "poll", owner)
                queue.drain()
                assertFalse(successorNative.calls.contains("cancel"))
                assertNull(MicrophoneCaptureFence.tryNormal())
                successorNative.receipt = NativeCaptureOwnerRegistry.Receipt(1, 0, 0, 0, false)
                command(registry, "poll", successor)
                queue.drain()
            } finally { owner.lease.release() } // failed-test isolation, not a production recovery path
        }
    }

    @Test fun adapterOrExecutorFailureKeepsOwnerWithoutRetry() {
        for (phase in listOf("begin", "poll", "dispose", "main", "dispatch")) {
            val queue = Queue()
            val calls = mutableListOf<String>()
            val native = object : NativeCaptureOwnerRegistry.NativeRun {
                fun call(name: String) { calls += name; if (phase == name) error("injected adapter failure") }
                override fun begin() = call("begin")
                override fun cancel() = call("cancel")
                override fun pollClosedJoin(): NativeCaptureOwnerRegistry.Receipt {
                    call("poll"); return NativeCaptureOwnerRegistry.Receipt(1, 0, 0, 0, false)
                }
                override fun disposeJoined() = call("dispose")
            }
            val registry = NativeCaptureOwnerRegistry(
                if (phase == "dispatch") Executor { throw java.util.concurrent.RejectedExecutionException() } else queue
            ) { phase == "main" }
            var owner: NativeCaptureOwnerRegistry.Owner? = null
            try {
                owner = registry.start { native }!!
                queue.drain()
                assertEquals(phase, NativeCaptureOwnerRegistry.State.RESTART_REQUIRED, owner.state)
                val before = calls.toList()
                command(registry, "requestCancel", owner)
                command(registry, "poll", owner)
                queue.drain()
                assertEquals(before, calls)
                assertNull(MicrophoneCaptureFence.tryNormal())
                if (phase == "main" || phase == "dispatch") assertTrue(calls.isEmpty())
            } finally {
                // Explicit fixture teardown only, not a lifecycle recovery API.
                if (owner != null) owner.lease.release() else {
                    val f = MicrophoneCaptureFence.javaClass.getDeclaredField("diagnostic")
                    f.isAccessible = true
                    (f.get(MicrophoneCaptureFence) as MicrophoneCaptureFence.Lease?)?.release()
                }
            }
        }
    }

    @Test fun failedCloseIsProcessLifetimeTombstoneNeverRetriedOrDisposed() {
        val queue = Queue()
        val registry = NativeCaptureOwnerRegistry(queue) { false }
        val native = Native().apply { receipt = NativeCaptureOwnerRegistry.Receipt(5, -1, -8, -9, true) }
        val owner = registry.start { native }!!
        try {
            queue.drain()
            assertEquals(NativeCaptureOwnerRegistry.State.RESTART_REQUIRED, owner.state)
            val before = native.calls.toList()
            command(registry, "requestCancel", owner)
            command(registry, "poll", owner)
            queue.drain()
            assertEquals(before, native.calls)
            assertFalse(native.calls.contains("dispose"))
            assertNull(registry.start { Native() })
            assertNull(NativeCaptureOwnerRegistry(queue) { false }.start { Native() })
            assertNull(MicrophoneCaptureFence.tryProjectionClaim())
            assertEquals(-9, owner.receipt!!.closeError)
        } finally { owner.lease.release() } // test isolation only; no reset API exists
    }

    @Test fun missingNativeLibraryIsTerminalRetainedNotEscaped() {
        val queue=Queue();val registry=NativeCaptureOwnerRegistry(queue){false}
        val owner=registry.start { throw UnsatisfiedLinkError("fixture only") }!!
        try {
            queue.drain()
            assertEquals(NativeCaptureOwnerRegistry.State.RESTART_REQUIRED,owner.state)
            assertNull(MicrophoneCaptureFence.tryProjectionClaim())
        } finally { owner.lease.release() }
    }

    @Test fun schedulerPollingCannotGrowQueueBehindBlockedOwner() {
        val queue=Queue(); val registry=NativeCaptureOwnerRegistry(queue){false}
        val native=Native().apply { receipt=null }
        val owner=registry.start { native }!!
        try {
            repeat(1000){registry.poll(owner)}
            assertEquals("retain one service command, not an unbounded poll backlog",1,queue.tasks.size)
        } finally { owner.lease.release() }
    }

    @Test fun cancellationBeforeQueuedBeginNeverCreatesNativeEffects() {
        val queue = Queue()
        val registry = NativeCaptureOwnerRegistry(queue) { false }
        var created = 0
        val owner = registry.start { created++; Native() }!!
        registry.requestCancel(owner)
        queue.drain()
        try {
            assertEquals("revoked admission must not create a native runner", 0, created)
            assertEquals(NativeCaptureOwnerRegistry.State.RETIRED, owner.state)
        } finally { owner.lease.release() }
    }

    @Test fun normalCompletionRetiresOffCallerWithoutCancellation() {
        val queue = Queue()
        val registry = NativeCaptureOwnerRegistry(queue) { false }
        val native = Native()
        val owner = registry.start { native }!!
        assertTrue(native.calls.isEmpty())
        assertNull(MicrophoneCaptureFence.tryNormal())
        queue.drain()
        assertEquals(listOf("begin", "poll", "dispose"), native.calls)
        assertEquals(NativeCaptureOwnerRegistry.State.RETIRED, owner.state)
        assertEquals(1, owner.receipt!!.termination)
        MicrophoneCaptureFence.tryNormal()!!.release()
    }
}
