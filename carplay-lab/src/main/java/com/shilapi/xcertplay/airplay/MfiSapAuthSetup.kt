// SPDX-License-Identifier: GPL-3.0-only
// From DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d; see PROVENANCE.md.
package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.mfi.MfiAuthenticator
import com.shilapi.xcertplay.mfi.MfiCertificateType
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CarPlay /auth-setup MFiSAP responder (accessory side).
 *
 * Proves MFi licensing to the phone. The request is [1-byte version=1][32-byte controller X25519
 * public key]; the response is this accessory's ephemeral public key, its MFi certificate, and its
 * coprocessor signature over the two public keys, AES-128-CTR encrypted under a key derived from
 * the shared secret. It runs over the already-encrypted control channel.
 *
 * [MfiAuthenticator] performs blocking I/O and must be called off the main thread.
 */
object MfiSapAuthSetup {
    private const val VERSION = 0x01
    private const val PUBLIC_KEY_BYTES = 32

    /** Returns the raw binary response, or null when the request is malformed. */
    fun handle(body: ByteArray, mfi: MfiAuthenticator): ByteArray? {
        if (body.size != 1 + PUBLIC_KEY_BYTES || body[0].toInt() != VERSION) return null
        val peerPublicKey = body.copyOfRange(1, body.size)

        val pair = AirPlayCrypto.x25519Generate()
        val shared = AirPlayCrypto.x25519Shared(pair.privateKey, peerPublicKey)
        if (shared.all { it.toInt() == 0 }) return null

        val aesKey = sha1("AES-KEY".asciiBytes(), shared).copyOf(16)
        val aesIv = sha1("AES-IV".asciiBytes(), shared).copyOf(16)

        return when (mfi.certificateType) {
            MfiCertificateType.MFI -> {
                val certificate = mfi.readCertificate()
                val digest = if (mfi.protocolMajor() == 2) {
                    sha1(pair.publicKey, peerPublicKey)
                } else {
                    sha256(pair.publicKey, peerPublicKey)
                }
                val encryptedSignature = aesCtr128(
                    aesKey,
                    aesIv,
                    mfi.signChallenge(digest),
                )
                buildMfiResponse(pair.publicKey, certificate, encryptedSignature)
            }

            MfiCertificateType.BAA -> {
                val certificates = mfi.baaCertificates()
                val encryptedSignature = aesCtr128(
                    aesKey,
                    aesIv,
                    mfi.signChallenge(pair.publicKey + peerPublicKey),
                )
                buildBaaResponse(
                    pair.publicKey,
                    certificates.leaf,
                    encryptedSignature,
                    encodeBaaIntermediate(certificates.intermediate),
                )
            }
        }
    }

    private fun buildMfiResponse(
        publicKey: ByteArray,
        certificate: ByteArray,
        encryptedSignature: ByteArray,
    ): ByteArray {
        val response = ByteArray(32 + 4 + certificate.size + 4 + encryptedSignature.size)
        publicKey.copyInto(response, 0)
        putU32(response, 32, certificate.size)
        certificate.copyInto(response, 36)
        putU32(response, 36 + certificate.size, encryptedSignature.size)
        encryptedSignature.copyInto(response, 40 + certificate.size)
        return response
    }

    private fun buildBaaResponse(
        publicKey: ByteArray,
        leaf: ByteArray,
        encryptedSignature: ByteArray,
        baicBlob: ByteArray,
    ): ByteArray {
        val response = ByteArray(
            32 + 4 + leaf.size + 4 + encryptedSignature.size + 4 + baicBlob.size,
        )
        publicKey.copyInto(response, 0)
        var offset = 32
        putU32(response, offset, leaf.size)
        offset += 4
        leaf.copyInto(response, offset)
        offset += leaf.size
        putU32(response, offset, encryptedSignature.size)
        offset += 4
        encryptedSignature.copyInto(response, offset)
        offset += encryptedSignature.size
        putU32(response, offset, baicBlob.size)
        offset += 4
        baicBlob.copyInto(response, offset)
        return response
    }

    /** OPACK `{"baIC": intermediate}` used by Showcase and CarPlay Simulator. */
    private fun encodeBaaIntermediate(intermediate: ByteArray): ByteArray {
        val header = when {
            intermediate.size <= 0x20 -> byteArrayOf(
                0xE1.toByte(),
                0x44,
                'b'.code.toByte(),
                'a'.code.toByte(),
                'I'.code.toByte(),
                'C'.code.toByte(),
                (0x70 + intermediate.size).toByte(),
            )

            intermediate.size <= 0xFF -> byteArrayOf(
                0xE1.toByte(),
                0x44,
                'b'.code.toByte(),
                'a'.code.toByte(),
                'I'.code.toByte(),
                'C'.code.toByte(),
                0x91.toByte(),
                intermediate.size.toByte(),
            )

            intermediate.size <= 0xFFFF -> byteArrayOf(
                0xE1.toByte(),
                0x44,
                'b'.code.toByte(),
                'a'.code.toByte(),
                'I'.code.toByte(),
                'C'.code.toByte(),
                0x92.toByte(),
                intermediate.size.toByte(),
                (intermediate.size ushr 8).toByte(),
            )

            else -> byteArrayOf(
                0xE1.toByte(),
                0x44,
                'b'.code.toByte(),
                'a'.code.toByte(),
                'I'.code.toByte(),
                'C'.code.toByte(),
                0x93.toByte(),
                intermediate.size.toByte(),
                (intermediate.size ushr 8).toByte(),
                (intermediate.size ushr 16).toByte(),
                (intermediate.size ushr 24).toByte(),
            )
        }
        return header + intermediate
    }

    private fun sha1(vararg parts: ByteArray): ByteArray = digest("SHA-1", parts)

    private fun sha256(vararg parts: ByteArray): ByteArray = digest("SHA-256", parts)

    private fun digest(algorithm: String, parts: Array<out ByteArray>): ByteArray {
        val digest = MessageDigest.getInstance(algorithm)
        for (part in parts) digest.update(part)
        return digest.digest()
    }

    private fun aesCtr128(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
