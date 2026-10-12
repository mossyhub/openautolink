// SPDX-License-Identifier: GPL-3.0-only
// From DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d, shared/src/main/java/
// com/shilapi/xcertplay/airplay/; upstream shilapi/xcertplay. See PROVENANCE.md.
package com.shilapi.xcertplay.airplay

import java.util.UUID

/** Long-term Ed25519 identity of the CarPlay accessory, persisted by the caller. */
data class AirPlayIdentity(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val pairingId: String,
) {
    val publicKeyHex: String get() = publicKey.toHexString()

    companion object {
        fun generate(): AirPlayIdentity {
            val keyPair = AirPlayCrypto.ed25519Generate()
            return AirPlayIdentity(keyPair.privateKey, keyPair.publicKey, UUID.randomUUID().toString())
        }
    }
}
