// SPDX-License-Identifier: GPL-3.0-only
// From DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d, shared/src/main/java/
// com/shilapi/xcertplay/airplay/; upstream shilapi/xcertplay. See PROVENANCE.md.
package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import java.security.SecureRandom

/**
 * SRP-6a server (RFC 5054 3072-bit group, SHA-512), the AirPlay pair-setup variant.
 *
 * The username is the literal "Pair-Setup" and the password is the fixed setup code. This is a
 * clean-room port of LIVI's `srp.ts` over java.math.BigInteger.
 */
object Srp6a {
    private const val SALT_BYTES = 16
    private const val PRIVATE_BYTES = 32
    private val random = SecureRandom()
    private val n = BigInteger(N_HEX, 16)
    private val g = BigInteger.valueOf(5)
    private val multiplier = toBigInt(AirPlayCrypto.sha512(toBytes(n), pad(g)))

    fun start(username: String, password: String): Srp6aSession {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val identifier = username.toByteArray(Charsets.UTF_8)
        val pass = password.toByteArray(Charsets.UTF_8)
        val x = toBigInt(AirPlayCrypto.sha512(salt, AirPlayCrypto.sha512(identifier, COLON, pass)))
        val verifier = g.modPow(x, n)
        val privateExponent = toBigInt(ByteArray(PRIVATE_BYTES).also(random::nextBytes))
        val publicKeyB = (multiplier * verifier + g.modPow(privateExponent, n)).mod(n)
        return Srp6aSession(salt, publicKeyB, identifier, verifier, privateExponent)
    }

    internal fun toBytes(value: BigInteger): ByteArray {
        if (value.signum() == 0) return byteArrayOf(0)
        val encoded = value.toByteArray()
        return if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
    }

    internal fun toBigInt(bytes: ByteArray): BigInteger = BigInteger(1, bytes)

    internal fun pad(value: BigInteger): ByteArray {
        val bytes = toBytes(value)
        if (bytes.size >= N_BYTES) return bytes
        return ByteArray(N_BYTES).also { bytes.copyInto(it, N_BYTES - bytes.size) }
    }

    internal fun modulus(): BigInteger = n
    internal fun generator(): BigInteger = g

    /** RFC 5054 3072-bit prime. */
    private const val N_HEX =
        "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183" +
            "995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A" +
            "85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7AB" +
            "F5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8" +
            "7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208" +
            "E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"

    private const val N_BYTES = 384
    private val COLON = byteArrayOf(':'.code.toByte())
}

data class Srp6aResult(
    val ok: Boolean,
    val sessionKey: ByteArray? = null,
    val serverProof: ByteArray? = null,
)

class Srp6aSession internal constructor(
    val salt: ByteArray,
    private val publicKeyB: BigInteger,
    private val identifier: ByteArray,
    private val verifier: BigInteger,
    private val privateExponent: BigInteger,
) {
    val publicKey: ByteArray get() = Srp6a.pad(publicKeyB)

    fun verify(publicKeyA: ByteArray, clientProof: ByteArray): Srp6aResult {
        val a = Srp6a.toBigInt(publicKeyA)
        val n = Srp6a.modulus()
        if (a.mod(n).signum() == 0) return Srp6aResult(false)

        val u = Srp6a.toBigInt(AirPlayCrypto.sha512(Srp6a.pad(a), Srp6a.pad(publicKeyB)))
        val s = (a * verifier.modPow(u, n)).mod(n).modPow(privateExponent, n)
        val sessionKey = AirPlayCrypto.sha512(Srp6a.toBytes(s))

        val hashN = AirPlayCrypto.sha512(Srp6a.toBytes(n))
        val hashG = AirPlayCrypto.sha512(Srp6a.toBytes(Srp6a.generator()))
        val hashXor = ByteArray(hashN.size)
        for (index in hashN.indices) {
            hashXor[index] = (hashN[index].toInt() xor hashG[index].toInt()).toByte()
        }
        val expected = AirPlayCrypto.sha512(
            hashXor,
            AirPlayCrypto.sha512(identifier),
            salt,
            Srp6a.pad(a),
            Srp6a.pad(publicKeyB),
            sessionKey,
        )
        if (!java.security.MessageDigest.isEqual(expected, clientProof)) return Srp6aResult(false)

        val serverProof = AirPlayCrypto.sha512(Srp6a.pad(a), clientProof, sessionKey)
        return Srp6aResult(true, sessionKey, serverProof)
    }
}
