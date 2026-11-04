package com.openautolink.app.cluster

import org.junit.Assert.*
import org.junit.Test

class OptionalOutputDiagnosticsTest {
    @Test fun milestonesAreDistinctAndSummariesAreBoundedWithoutPerFrameWrites() {
        var now = 0L
        val lines = mutableListOf<String>()
        val trace = OptionalOutputDiagnostics({ now }, { _, line -> lines += line })
        trace.bind(ClusterOutputLease(11, 12))
        trace.stream(7)
        repeat(1000) { trace.progress(1, 0, it.toLong()) }
        assertEquals(1, lines.count { "stage=first_decode " in it })
        assertFalse(lines.any { "stage=FIRST_PRESENT " in it })
        trace.progress(0, 1, 1000)
        assertEquals(1, lines.count { "stage=FIRST_PRESENT " in it })
        repeat(1000) { trace.progress(1, 1, it.toLong()) }
        assertFalse(lines.any { "stage=summary " in it })
        now = 5000
        trace.summary("received=2000 dropped=4")
        trace.summary("received=2000 dropped=4")
        assertEquals(1, lines.count { "stage=summary " in it })
        assertTrue(lines.all { "session=11 output=12" in it && it.length <= 480 })
        trace.stream(8)
        trace.progress(1, 1, 2001)
        assertEquals(2, lines.count { "stage=FIRST_PRESENT " in it })
    }

    @Test fun everyFailureIsPersistedWithFiniteReasonAndNoExceptionText() {
        val lines = mutableListOf<String>()
        val trace = OptionalOutputDiagnostics({ 0 }, { _, line -> lines += line })
        repeat(3) { trace.failure("configure", IllegalStateException("private phone SSID route")) }
        assertEquals(3, lines.size)
        assertTrue(lines.all { "stage=failure" in it && "reason=illegal_state" in it })
        assertTrue(lines.none { "private" in it || "SSID" in it || "route" in it })
        trace.failure("draw", ClusterGraphicsFailure("egl_swap", 0x300d))
        assertTrue(lines.last().contains("reason=egl_swap code=12301"))
    }
}
