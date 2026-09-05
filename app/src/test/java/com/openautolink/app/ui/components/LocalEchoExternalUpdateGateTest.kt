package com.openautolink.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalEchoExternalUpdateGateTest {
    @Test
    fun reopeningTabIgnoresStaleExternalValueAfterManualSave() {
        val firstComposition = LocalEchoExternalUpdateGate()
        assertTrue(firstComposition.consume("A", "A", 1L))
        assertFalse(firstComposition.consume("B", "A", 1L))

        val reopenedComposition = LocalEchoExternalUpdateGate()
        assertFalse("Saved B must remain visible when the tab remounts with stale A",
            reopenedComposition.consume("B", "A", 1L))
    }

    @Test
    fun newerSameValueEventCanReplaceAnotherFocusedDraft() {
        val gate = LocalEchoExternalUpdateGate()
        assertTrue(gate.consume("A", "A", 1L))
        assertFalse(gate.consume("A", "A", 1L))

        // Upstream and external text are unchanged; only the event version advances.
        assertTrue(gate.consume("A", "A", 2L))
        assertFalse(gate.consume("A", "A", 2L))
        assertFalse("An older version must not become new again", gate.consume("A", "A", 1L))
    }

    @Test
    fun absentAndNonPositiveEventsAreIgnored() {
        val gate = LocalEchoExternalUpdateGate()
        assertFalse(gate.consume("A", "A", 0L))
        assertFalse(gate.consume("A", "A", -1L))
        assertFalse(gate.consume("A", null, 1L))
        assertTrue("An incomplete event must not consume its version", gate.consume("A", "A", 1L))
    }

    @Test
    fun alreadyMatchingLocalTextStillConsumesEvent() {
        val gate = LocalEchoExternalUpdateGate()
        var localText = "A"
        if (gate.consume("A", "A", 1L) && localText != "A") localText = "A"
        localText = "manual draft"
        if (gate.consume("A", "A", 1L) && localText != "A") localText = "A"
        org.junit.Assert.assertEquals("manual draft", localText)
    }

    @Test
    fun consumedUpdateDoesNotOverwriteLaterEditsOnDelayedEcho() {
        val gate = LocalEchoExternalUpdateGate()
        assertTrue(gate.consume(upstreamValue = "A", externalValue = "A", version = 1L))

        // A focused user types and saves B; an older persistence echo then arrives.
        assertFalse(gate.consume(upstreamValue = "B", externalValue = "A", version = 1L))
        assertFalse("The already consumed event must not replay over the user's B draft",
            gate.consume(upstreamValue = "A", externalValue = "A", version = 1L))
    }

    @Test
    fun externalUpdateWaitsForPersistedEcho() {
        val gate = LocalEchoExternalUpdateGate()

        assertFalse("Do not replace a focused draft before persistence catches up",
            gate.consume(upstreamValue = "old", externalValue = "A", version = 1L))
        assertTrue("Apply the explicit update even while focused once persisted",
            gate.consume(upstreamValue = "A", externalValue = "A", version = 1L))
    }
}
