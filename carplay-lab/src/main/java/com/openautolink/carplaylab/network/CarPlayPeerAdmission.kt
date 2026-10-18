// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.network

import java.net.InetAddress
import java.net.Inet6Address

/** Inet6Address.equals ignores zone IDs. Never use it as the scoped endpoint admission predicate. */
fun exactCarPlayAddress(expected: InetAddress, actual: InetAddress): Boolean =
    expected.address.contentEquals(actual.address) &&
        (expected !is Inet6Address || actual is Inet6Address && expected.scopeId == actual.scopeId)

/** Exact per-control-session socket contract. Created before auth, exposed to media only after auth. */
class CarPlaySocketBinding(val local: InetAddress, val peer: InetAddress) {
    fun allowsPeer(candidate: InetAddress): Boolean = exactCarPlayAddress(peer, candidate)
    fun allowsLocal(candidate: InetAddress): Boolean = exactCarPlayAddress(local, candidate)
}

/** One live pre-auth candidate, 32 attempts, exact interface/family/scope.
 * Default reconnect uses immutable startup trust. Additional enrollment is bounded by the
 * caller's selected, consent-qualified bootstrap lifetime, NOT a cryptographic BT/key binding.
 * Call persistEnrollment ONLY after real PairSetup M5 proof; authenticate ONLY after PairVerify.
 * Tokens must be the exact control owner. Retirement serializes against durable enrollment.
 */
class CarPlayPeerAdmission(private val selection: CarPlayNetworkSelection, trustedControllers: Set<String>,
    private val trustedOnly: Boolean = false,
    private val enrollmentWindowMillis: Long = 0,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val trusted = trustedControllers.toSet()
    private val enrollmentStartedNanos = nanoTime()
    private val firstEnrollment = enrollmentWindowMillis > 0 && trusted.isEmpty()
    private fun enrollmentOpen(): Boolean {
        val elapsed = nanoTime() - enrollmentStartedNanos
        return enrollmentWindowMillis > 0 && enrolledController == null &&
            elapsed >= 0 && elapsed < enrollmentWindowMillis * 1_000_000
    }
    private var enrolledController: String? = null
    @Synchronized fun allowsPairSetup(token: Any): Boolean {
        if (retired || owner !== token) return false
        // Pinned DiPlay accepts PairSetup before PairVerify even with persisted trust. M1 has
        // no controller identity: defer authorization to genuine SRP/Ed25519 M5, not HTTP403.
        // Existing trust permits only read-only proof of the exact startup controller/key.
        return trusted.isNotEmpty() || enrollmentOpen()
    }
    /** Called ONLY after real SRP/Ed25519 M5. Under the retirement fence, acknowledge an exact
     * existing startup key without writing, or commit the explicitly authorized first key.
     * Neither path authenticates media; stored-key PairVerify is still required. */
    @Synchronized fun persistEnrollment(token: Any, identifier: String, publicKey: ByteArray,
        store: com.shilapi.xcertplay.airplay.PairingStore): Boolean {
        if (!allowsPairSetup(token)) return false
        if (identifier in trusted || trusted.isNotEmpty() && identifier == enrolledController) return publicKey.size == 32 &&
            store.get(identifier)?.contentEquals(publicKey) == true
        if (!enrollmentOpen() || store.controllerIdentifiers() != trusted || publicKey.size != 32) return false
        store.save(identifier, publicKey)
        enrolledController = identifier
        return true
    }
    private var owner: Any? = null
    private var binding: CarPlaySocketBinding? = null
    private var authenticated = false
    private var attempts = 0
    private var retired = false
    init {
        require(enrollmentWindowMillis in 0..120_000) { "Invalid first-enrollment window" }
        require(enrollmentWindowMillis == 0L || trustedOnly) { "First enrollment requires explicit trust admission" }
        require(!trustedOnly || trusted.isNotEmpty() || enrollmentWindowMillis > 0) {
            "Existing controller trust or authorized first-enrollment window required"
        }
        require(selection.manualPeer != null || trusted.isNotEmpty() || enrollmentWindowMillis > 0) { "Existing trusted controller required for unknown AP peer" }
    }
    @Synchronized fun claim(token: Any, peer: InetAddress, local: InetAddress): Boolean {
        val elapsed = nanoTime() - enrollmentStartedNanos
        if (retired || owner != null || attempts >= 32 ||
            firstEnrollment && enrolledController == null &&
                (elapsed < 0 || elapsed >= enrollmentWindowMillis * 1_000_000) ||
            !exactCarPlayAddress(selection.local, local) ||
            (selection.local is Inet6Address) != (peer is Inet6Address) ||
            peer.isAnyLocalAddress || peer.isMulticastAddress ||
            selection.local.isLinkLocalAddress && (!peer.isLinkLocalAddress || peer !is Inet6Address || peer.scopeId != selection.interfaceIndex) ||
            selection.manualPeer?.let { !exactCarPlayAddress(it, peer) } == true) return false
        owner = token; binding = CarPlaySocketBinding(selection.local, peer); authenticated = false; attempts++
        return true
    }
    @Synchronized fun authenticate(token: Any, verifiedControllerId: String?): Boolean {
        if (retired || owner !== token || verifiedControllerId == null ||
            (trustedOnly || selection.manualPeer == null) &&
            verifiedControllerId !in trusted && verifiedControllerId != enrolledController) return false
        authenticated = true
        return true
    }
    @Synchronized fun authenticatedBinding(token: Any): CarPlaySocketBinding? =
        if (owner === token && authenticated) binding else null
    @Synchronized fun retire() {
        retired = true
        owner = null; binding = null; authenticated = false
    }
    @Synchronized fun release(token: Any) {
        if (owner === token) { owner = null; binding = null; authenticated = false }
    }
}
