package com.openautolink.carplaylab.network

import java.net.InetAddress
import java.net.Inet6Address
import org.junit.Assert.*
import org.junit.Test

class CarPlayNetworkAdmissionTest {
    private fun v6(ip: String, scope: Int) = Inet6Address.getByAddress(null, InetAddress.getByName(ip).address, scope)
    @Test fun unknownPeerCannotOwnMediaUntilPreviouslyTrustedControllerVerifies() {
        val selection = CarPlayNetworkSelection(v6("fe80::1", 7), 7, null)
        assertThrows(IllegalArgumentException::class.java) { CarPlayPeerAdmission(selection, emptySet()) }
        val admission = CarPlayPeerAdmission(selection, setOf("trusted"))
        val token = Any()
        assertTrue(admission.claim(token, v6("fe80::2", 7), selection.local))
        assertFalse(admission.claim(Any(), v6("fe80::3", 7), selection.local))
        assertNull(admission.authenticatedBinding(token))
        assertFalse(admission.authenticate(token, "new-untrusted"))
        assertTrue(admission.authenticate(token, "trusted"))
        val binding = checkNotNull(admission.authenticatedBinding(token))
        assertTrue(binding.allowsPeer(v6("fe80::2", 7)))
        assertFalse(binding.allowsPeer(v6("fe80::2", 8)))
        assertFalse(binding.allowsPeer(v6("fe80::3", 7)))
        assertFalse(binding.allowsPeer(InetAddress.getByName("192.0.2.2")))
        admission.release(token)
        assertNull(admission.authenticatedBinding(token))
        assertFalse(admission.claim(Any(), v6("fe80::2", 8), selection.local))
        assertFalse(admission.claim(Any(), v6("fe80::2", 7), v6("fe80::1", 8)))
    }
}
