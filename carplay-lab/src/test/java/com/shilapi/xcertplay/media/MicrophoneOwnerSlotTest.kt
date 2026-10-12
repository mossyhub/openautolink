package com.shilapi.xcertplay.media

import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test

class MicrophoneOwnerSlotTest {
    private class Capture : Closeable {
        var closed = false
        var retired = false
        override fun close() { closed = true }
    }
    @Test fun replacementCannotOverlapRetiringCaptureAndStaleGenerationCannotStopCurrentOwner() {
        val slot = MicrophoneOwnerSlot<String, Capture>({ it.retired }, { it.closed })
        val first = Capture()
        assertTrue(slot.start("sessionA/type100/default/gen1") { first })
        assertTrue(slot.start("sessionA/type100/default/gen1") { error("duplicate capture") })
        assertFalse(slot.start("sessionB/type100/default/gen2") { error("overlap") })
        assertTrue(first.closed)
        first.retired = true
        val second = Capture()
        assertTrue(slot.start("sessionB/type100/default/gen2") { second })
        slot.stop("sessionA/type100/default/gen1")
        assertFalse(second.closed)
        slot.stop("sessionB/type100/default/gen2")
        assertTrue(second.closed)
        assertFalse(slot.start("sessionB/type100/default/gen2") { error("retiring same owner") })
    }
    @Test fun closedSinkCannotStartAnotherCaptureEvenAfterRetirement() {
        val slot = MicrophoneOwnerSlot<Int, Capture>({ it.retired }, { it.closed })
        val capture = Capture()
        assertTrue(slot.start(1) { capture })
        slot.close()
        capture.retired = true
        assertFalse(slot.start(2) { error("closed sink") })
    }
}
