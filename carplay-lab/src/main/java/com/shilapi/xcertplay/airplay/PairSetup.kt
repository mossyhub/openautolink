// SPDX-License-Identifier: GPL-3.0-only
// From DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d, shared/src/main/java/
// com/shilapi/xcertplay/airplay/; upstream shilapi/xcertplay. See PROVENANCE.md.
package com.shilapi.xcertplay.airplay

/**
 * CarPlay pair-setup responder (accessory/server side).
 *
 * Uses unauthenticated SRP-6a with the fixed PIN "3939", then exchanges encrypted long-term
 * Ed25519 keys. The controller's key is persisted for later pair-verify. TLV8 over
 * "application/pairing+tlv8".
 */
class PairSetup(
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    /** Optional owner-qualified M5 authorization; false returns protocol authentication failure.
     * Invoked only after genuine SRP and controller Ed25519 verification, never before proof.
     * Accept an exact existing stored key without writing, or durably commit explicitly authorized
     * first enrollment. The verified key must already be in pairings before returning true. */
    private val saveController: ((String, ByteArray) -> Boolean)? = null,
) {
    private var phase = 1
    private var srp: Srp6aSession? = null
    private var sessionKey: ByteArray? = null
    var complete: Boolean = false
        private set

    fun handle(body: ByteArray): ByteArray {
        var state: Int? = null
        return try {
            require(body.size <= 32768)
            val tlv = Tlv8Codec.decode(body)
            val stateBytes = tlv[TYPE_STATE]
            require(stateBytes?.size == 1)
            state = stateBytes!![0].toInt() and 0xff
            if (state != phase) return err(state ?: 0)
            when (state) {
                1 -> m2()
                3 -> m4(tlv)
                5 -> m6(tlv)
                else -> err(state ?: 0)
            }
        } catch (_: Exception) {
            err(state ?: 0)
        }
    }

    private fun m2(): ByteArray {
        val session = Srp6a.start(SETUP_USERNAME, SETUP_CODE)
        srp = session
        phase = 3
        return Tlv8Codec.encode(
            listOf(
                Tlv8Item(TYPE_STATE, byteArrayOf(2)),
                Tlv8Item(TYPE_PUBLIC_KEY, session.publicKey),
                Tlv8Item(TYPE_SALT, session.salt),
            ),
        )
    }

    private fun m4(tlv: Map<Int, ByteArray>): ByteArray {
        val publicKeyA = tlv[TYPE_PUBLIC_KEY]
        val proof = tlv[TYPE_PROOF]
        val session = srp
        if (session == null || publicKeyA == null || proof == null) return err(4)
        require(publicKeyA.size in 1..384 && proof.size == 64)
        val result = session.verify(publicKeyA, proof)
        if (!result.ok || result.sessionKey == null || result.serverProof == null) return err(4)
        sessionKey = result.sessionKey
        phase = 5
        return Tlv8Codec.encode(
            listOf(
                Tlv8Item(TYPE_STATE, byteArrayOf(4)),
                Tlv8Item(TYPE_PROOF, result.serverProof),
            ),
        )
    }

    private fun m6(tlv: Map<Int, ByteArray>): ByteArray {
        val encrypted = tlv[TYPE_ENCRYPTED_DATA]
        val key = sessionKey
        if (key == null || encrypted == null) return err(6)

        val encryptionKey = AirPlayCrypto.hkdfSha512(key, ENCRYPT_SALT.asciiBytes(), ENCRYPT_INFO.asciiBytes())
        val sub = Tlv8Codec.decode(AirPlayCrypto.chachaOpen(encryptionKey, AirPlayCrypto.nonceLabel(MSG05), encrypted))
        val controllerId = sub[TYPE_IDENTIFIER]
        val controllerLtpk = sub[TYPE_PUBLIC_KEY]
        val controllerSignature = sub[TYPE_SIGNATURE]
        if (controllerId == null || controllerLtpk == null || controllerSignature == null) return err(6)

        require(controllerId.size in 1..256 && controllerLtpk.size == 32 && controllerSignature.size == 64)
        require(controllerId.toString(Charsets.UTF_8).toByteArray(Charsets.UTF_8).contentEquals(controllerId))
        val controllerSignKey = AirPlayCrypto.hkdfSha512(
            key,
            CONTROLLER_SIGN_SALT.asciiBytes(),
            CONTROLLER_SIGN_INFO.asciiBytes(),
        )
        val controllerSignData = concatBytes(controllerSignKey, controllerId, controllerLtpk)
        if (!AirPlayCrypto.ed25519Verify(controllerLtpk, controllerSignData, controllerSignature)) return err(6)

        val accessoryId = identity.pairingId.toByteArray(Charsets.UTF_8)
        val accessorySignKey = AirPlayCrypto.hkdfSha512(
            key,
            ACCESSORY_SIGN_SALT.asciiBytes(),
            ACCESSORY_SIGN_INFO.asciiBytes(),
        )
        val accessorySignData = concatBytes(accessorySignKey, accessoryId, identity.publicKey)
        val accessorySignature = AirPlayCrypto.ed25519Sign(identity.privateKey, accessorySignData)
        val subResponse = Tlv8Codec.encode(
            listOf(
                Tlv8Item(TYPE_IDENTIFIER, accessoryId),
                Tlv8Item(TYPE_PUBLIC_KEY, identity.publicKey),
                Tlv8Item(TYPE_SIGNATURE, accessorySignature),
            ),
        )
        val sealed = AirPlayCrypto.chachaSeal(encryptionKey, AirPlayCrypto.nonceLabel(MSG06), subResponse)
        val identifier = controllerId.toString(Charsets.UTF_8)
        if (saveController != null) {
            if (!saveController.invoke(identifier, controllerLtpk.copyOf())) return err(6)
            // Callback success is never a substitute for an actual stored verified key.
            if (pairings.get(identifier)?.contentEquals(controllerLtpk) != true) return err(6)
        } else {
            pairings.save(identifier, controllerLtpk)
        }
        phase = 0
        srp = null
        sessionKey = null
        complete = true
        return Tlv8Codec.encode(
            listOf(
                Tlv8Item(TYPE_STATE, byteArrayOf(6)),
                Tlv8Item(TYPE_ENCRYPTED_DATA, sealed),
            ),
        )
    }

    private fun err(state: Int): ByteArray {
        phase = 0
        srp = null
        sessionKey = null
        complete = false
        return Tlv8Codec.encode(listOf(
            Tlv8Item(TYPE_STATE, byteArrayOf(state.toByte())),
            Tlv8Item(TYPE_ERROR, byteArrayOf(ERROR_AUTHENTICATION.toByte())),
        ))
    }

    private companion object {
        const val TYPE_METHOD = 0x00
        const val TYPE_IDENTIFIER = 0x01
        const val TYPE_SALT = 0x02
        const val TYPE_PUBLIC_KEY = 0x03
        const val TYPE_PROOF = 0x04
        const val TYPE_ENCRYPTED_DATA = 0x05
        const val TYPE_STATE = 0x06
        const val TYPE_ERROR = 0x07
        const val TYPE_SIGNATURE = 0x0a
        const val SETUP_USERNAME = "Pair-Setup"
        const val SETUP_CODE = "3939"
        const val ERROR_AUTHENTICATION = 2
        const val ENCRYPT_SALT = "Pair-Setup-Encrypt-Salt"
        const val ENCRYPT_INFO = "Pair-Setup-Encrypt-Info"
        const val CONTROLLER_SIGN_SALT = "Pair-Setup-Controller-Sign-Salt"
        const val CONTROLLER_SIGN_INFO = "Pair-Setup-Controller-Sign-Info"
        const val ACCESSORY_SIGN_SALT = "Pair-Setup-Accessory-Sign-Salt"
        const val ACCESSORY_SIGN_INFO = "Pair-Setup-Accessory-Sign-Info"
        const val MSG05 = "PS-Msg05"
        const val MSG06 = "PS-Msg06"
    }
}
