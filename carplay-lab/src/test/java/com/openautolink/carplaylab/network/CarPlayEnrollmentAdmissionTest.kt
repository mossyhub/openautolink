package com.openautolink.carplaylab.network

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

/** Trusted-only remains closed unless runtime supplies an explicit first-enrollment window. */
class CarPlayEnrollmentAdmissionTest {
    private val local = InetAddress.getByName("127.0.0.1")
    private val peer = InetAddress.getByName("127.0.0.2")

    @Test fun unrelatedControllerOnSelectedWifiAddressCannotAuthenticate() {
        val admission = CarPlayPeerAdmission(CarPlayNetworkSelection(local, 1, peer), setOf("trusted"), trustedOnly = true)
        val candidate = Any()
        assertTrue(admission.claim(candidate, peer, local))
        assertFalse(admission.authenticate(candidate, "unrelated"))
        assertNull(admission.authenticatedBinding(candidate))
        assertTrue(admission.authenticate(candidate, "trusted"))
        assertNotNull(admission.authenticatedBinding(candidate))
    }

    @Test fun retiredAdmissionRejectsLateProofAndReplacementCandidates() {
        val admission = CarPlayPeerAdmission(CarPlayNetworkSelection(local, 1, null), setOf("trusted"), trustedOnly = true)
        val candidate = Any()
        assertTrue(admission.claim(candidate, peer, local))
        admission.retire()
        assertFalse(admission.authenticate(candidate, "trusted"))
        assertNull(admission.authenticatedBinding(candidate))
        admission.release(candidate)
        assertFalse(admission.claim(Any(), peer, local))
    }

    @Test fun releasedOldControlTokenCannotAuthorizeItsSuccessor() {
        val admission = CarPlayPeerAdmission(CarPlayNetworkSelection(local, 1, null), setOf("trusted"), trustedOnly = true)
        val old = Any()
        val next = Any()
        assertTrue(admission.claim(old, peer, local))
        admission.release(old)
        assertTrue(admission.claim(next, peer, local))
        assertFalse(admission.authenticate(old, "trusted"))
        admission.release(old)
        assertTrue(admission.authenticate(next, "trusted"))
    }

    @Test fun unknownPeerWithEmptyTrustReportsBindingBlocker() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CarPlayPeerAdmission(CarPlayNetworkSelection(local, 1, null), emptySet(), trustedOnly = true)
        }
        assertEquals("Existing controller trust or authorized first-enrollment window required", failure.message)
    }

    @Test fun selectedPeerBenchAdmissionRemainsSeparateFromProductionTrustPolicy() {
        val bench = CarPlayPeerAdmission(CarPlayNetworkSelection(local, 1, peer), emptySet())
        val token = Any()
        assertTrue(bench.claim(token, peer, local))
        assertTrue(bench.authenticate(token, "synthetic-lab-enrolled"))
    }

    @Test fun manualPeerCannotBypassMissingControllerTrustInProduction() {
        val selection = CarPlayNetworkSelection(local, 1, peer)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            CarPlayPeerAdmission(selection, emptySet(), trustedOnly = true)
        }
        assertEquals("Existing controller trust or authorized first-enrollment window required", failure.message)
    }
}
