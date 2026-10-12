package com.openautolink.app.carplay
import org.junit.Assert.*
import org.junit.Test
class UsbHotPlugConsentTest {
    @Test fun pendingCandidateDoesNotConsumeOwnership() {
        val gate = UsbHotPlugConsent()
        val candidate = gate.offer("phone", 0x05ac)
        assertNotNull(candidate)
        assertEquals(candidate, gate.current(candidate!!.generation))
    }
    @Test fun staySuppressesOnlyExactAttachmentUntilReplugOrRetry() {
        val gate = UsbHotPlugConsent()
        val first = gate.offer("phone", 1)!!
        assertTrue(gate.stay(first.generation))
        assertNull(gate.offer("phone", 1))
        gate.retry("phone")
        val retry = gate.offer("phone", 1)!!
        gate.stay(retry.generation)
        gate.detach("phone")
        assertNotEquals(first.generation, gate.offer("phone", 1)!!.generation)
    }
    @Test fun acceptIsSingleUseAndNeverAcceptsAReplacement() {
        val gate = UsbHotPlugConsent()
        val first = gate.offer("phone", 1)!!
        gate.detach("phone")
        val second = gate.offer("phone", 1)!!
        assertNull(gate.accept(first.generation))
        assertEquals(second, gate.accept(second.generation))
        assertNull(gate.accept(second.generation))
    }
    @Test fun secondPendingCannotOverwriteFirstAndDetachClearsPrompt() {
        val gate = UsbHotPlugConsent()
        val first = gate.offer("first", 1)!!
        assertNull(gate.offer("second", 0x05ac))
        assertEquals(first, gate.current(first.generation))
        assertTrue(gate.detach("first"))
        assertNull(gate.current(first.generation))
        assertNotNull(gate.offer("second", 0x05ac))
    }
    @Test fun explicitRetryReplacesPendingConsentAndFencesItsStaleButtons() {
        val gate = UsbHotPlugConsent()
        val old = gate.offer("phone", 1)!!
        gate.retry("phone")
        val replacement = gate.offer("phone", 1)
        assertNotNull(replacement)
        assertNotEquals(old.generation, replacement!!.generation)
        assertNull(gate.accept(old.generation))
        assertFalse(gate.stay(old.generation))
        assertEquals(replacement, gate.current(replacement.generation))
    }
}
