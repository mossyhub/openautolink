package com.openautolink.carplaylab.network

import com.shilapi.xcertplay.airplay.PairingStore
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

/** Network admission is consent/bootstrap scoped, NOT selected-phone cryptographic binding. */
class CarPlayOrdinaryEnrollmentTest {
    private val local = InetAddress.getByName("127.0.0.1")
    private val peer = InetAddress.getByName("127.0.0.2")

    @Test fun expiredWindowStopsNewCandidates() {
        var now = 100L
        val admission = CarPlayPeerAdmission(CarPlayNetworkSelection(local, 1, null), emptySet(),
            trustedOnly = true, enrollmentWindowMillis = 100, nanoTime = { now })
        now += 100_000_000
        assertFalse(admission.claim(Any(), peer, local))
    }

    @Test fun retirementRejectsLateM5AndNeverRevives() {
        val admission = fresh()
        val token = Any(); val store = PairingStore()
        assertTrue(admission.claim(token, peer, local))
        admission.retire()
        assertFalse(admission.allowsPairSetup(token))
        assertFalse(admission.persistEnrollment(token, "late", key(), store))
        assertFalse(admission.authenticate(token, "late"))
        assertTrue(store.controllerIdentifiers().isEmpty())
        assertFalse(admission.claim(Any(), peer, local))
    }

    @Test fun oldTokenCannotCommitAfterReplacement() {
        val admission = fresh()
        val old = Any(); val next = Any(); val store = PairingStore()
        assertTrue(admission.claim(old, peer, local))
        admission.release(old)
        assertTrue(admission.claim(next, peer, local))
        assertFalse(admission.persistEnrollment(old, "old", key(), store))
        assertTrue(admission.persistEnrollment(next, "next", key(), store))
        admission.release(old)
        assertTrue(admission.authenticate(next, "next"))
    }

    @Test fun successfulEnrollmentClosesSetupAcrossDisconnectButAllowsVerifiedReconnect() {
        var now = 0L
        val admission = fresh { now }; val first = Any(); val store = PairingStore()
        assertTrue(admission.claim(first, peer, local))
        assertTrue(admission.persistEnrollment(first, "first", key(), store))
        admission.release(first)
        now = 200_000_000_000L
        val next = Any()
        assertTrue(admission.claim(next, peer, local))
        assertFalse(admission.allowsPairSetup(next))
        assertFalse(admission.persistEnrollment(next, "second", key(), store))
        assertFalse(admission.authenticate(next, "second"))
        assertTrue(admission.authenticate(next, "first"))
    }

    @Test fun existingTrustAllowsProofOnlyPairSetupButNeverNewEnrollmentEvenWithWindow() {
        val admission = CarPlayPeerAdmission(CarPlayNetworkSelection(local, 1, null), setOf("trusted"),
            trustedOnly = true, enrollmentWindowMillis = 120_000)
        val token = Any(); assertTrue(admission.claim(token, peer, local))
        assertTrue(admission.allowsPairSetup(token))
        assertFalse(admission.persistEnrollment(token, "new", key(), PairingStore()))
        assertTrue(admission.authenticate(token, "trusted"))
    }

    @Test fun persistenceFailureCreatesNoAdmissionTrust() {
        val admission = fresh(); val token = Any()
        val store = PairingStore { _, _ -> throw java.io.IOException("synthetic write failure") }
        assertTrue(admission.claim(token, peer, local))
        assertThrows(java.io.IOException::class.java) { admission.persistEnrollment(token, "new", key(), store) }
        assertTrue(store.controllerIdentifiers().isEmpty())
        assertFalse(admission.authenticate(token, "new"))
    }

    @Test fun deadlineRejectsPendingM5ButDoesNotExpireVerifiedMedia() {
        var now = 0L
        val admission = fresh { now }; val token = Any(); val store = PairingStore()
        assertTrue(admission.claim(token, peer, local))
        now = 120_000_000_000L
        assertFalse(admission.persistEnrollment(token, "late", key(), store))
        assertTrue(store.controllerIdentifiers().isEmpty())
        val accepted = fresh { now }; val good = Any()
        assertTrue(accepted.claim(good, peer, local))
        assertTrue(accepted.persistEnrollment(good, "good", key(), store))
        now += 120_000_000_000L
        assertTrue(accepted.authenticate(good, "good"))
        assertNotNull(accepted.authenticatedBinding(good))
    }

    @Test fun manualAddressStillNeedsWindowAndRejectsOtherSources() {
        val admission = CarPlayPeerAdmission(CarPlayNetworkSelection(local, 1, peer), emptySet(),
            trustedOnly = true, enrollmentWindowMillis = 120_000)
        assertFalse(admission.claim(Any(), InetAddress.getByName("127.0.0.3"), local))
        assertTrue(admission.claim(Any(), peer, local))
        assertFalse(admission.claim(Any(), peer, local))
    }

    @Test fun wrongInterfaceFamilyAndIpv6ScopeRemainRejectedDuringEnrollment() {
        val local6 = java.net.Inet6Address.getByAddress(null, InetAddress.getByName("fe80::1").address, 7)
        val peer6 = java.net.Inet6Address.getByAddress(null, InetAddress.getByName("fe80::2").address, 7)
        val wrongScope = java.net.Inet6Address.getByAddress(null, peer6.address, 8)
        val admission = CarPlayPeerAdmission(CarPlayNetworkSelection(local6, 7, null), emptySet(),
            trustedOnly = true, enrollmentWindowMillis = 120_000)
        assertFalse(admission.claim(Any(), peer, local6))
        assertFalse(admission.claim(Any(), wrongScope, local6))
        assertFalse(admission.claim(Any(), peer6, local))
        assertTrue(admission.claim(Any(), peer6, local6))
    }

    @Test fun retirementLinearizesWithDurableCommit() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val unblock = java.util.concurrent.CountDownLatch(1)
        val admission = fresh(); val token = Any()
        val store = PairingStore { _, _ ->
            entered.countDown()
            check(unblock.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        assertTrue(admission.claim(token, peer, local))
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val commit = executor.submit<Boolean> { admission.persistEnrollment(token, "first", key(), store) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val retired = executor.submit { admission.retire() }
            unblock.countDown()
            assertTrue(commit.get(5, java.util.concurrent.TimeUnit.SECONDS))
            retired.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(setOf("first"), store.controllerIdentifiers())
            assertFalse(admission.authenticate(token, "first"))
            assertNull(admission.authenticatedBinding(token))
        } finally { unblock.countDown(); executor.shutdownNow() }
    }

    private fun fresh(clock: () -> Long = System::nanoTime) = CarPlayPeerAdmission(
        CarPlayNetworkSelection(local, 1, null), emptySet(), trustedOnly = true,
        enrollmentWindowMillis = 120_000, nanoTime = clock)
    private fun key() = ByteArray(32) { 1 }

    @Test fun emptyStoreCanEnrollOneControllerThenRequiresRealPairVerifyCallback() {
        val admission = CarPlayPeerAdmission(CarPlayNetworkSelection(local, 1, null), emptySet(),
            trustedOnly = true, enrollmentWindowMillis = 120_000)
        val token = Any()
        val store = PairingStore()
        assertTrue(admission.claim(token, peer, local))
        assertTrue(admission.allowsPairSetup(token))
        assertFalse(admission.authenticate(token, "new-controller"))
        assertNull(admission.authenticatedBinding(token))
        assertTrue(admission.persistEnrollment(token, "new-controller", ByteArray(32) { 1 }, store))
        assertEquals(setOf("new-controller"), store.controllerIdentifiers())
        assertFalse(admission.allowsPairSetup(token))
        assertNull(admission.authenticatedBinding(token))
        // This call is wired ONLY from the real successful PairVerify callback.
        assertTrue(admission.authenticate(token, "new-controller"))
        assertNotNull(admission.authenticatedBinding(token))
        assertFalse(admission.authenticate(token, "unrelated"))
    }
}
