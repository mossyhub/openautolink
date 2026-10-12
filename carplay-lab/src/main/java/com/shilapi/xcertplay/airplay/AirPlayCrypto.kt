// SPDX-License-Identifier: GPL-3.0-only
// From DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d, shared/src/main/java/
// com/shilapi/xcertplay/airplay/; upstream shilapi/xcertplay. See PROVENANCE.md.
package com.shilapi.xcertplay.airplay

import android.os.Build
import org.bouncycastle.crypto.Digest
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Cryptographic primitives for the CarPlay pairing and control channel. */
object AirPlayCrypto {
    private const val MAC_BITS = 128
    private const val NONCE_SIZE = 12
    private const val LABEL_SIZE = 8
    private const val MAX_RETAINED_BUFFER = 1024 * 1024
    private val random = SecureRandom()
    // Android's Conscrypt name first, then a desktop JDK's (unit tests).
    private val PLATFORM_CHACHA_NAMES = listOf("ChaCha20/Poly1305/NoPadding", "ChaCha20-Poly1305")

    /**
     * The platform's ChaCha20-Poly1305 (Conscrypt on Android), used before BouncyCastle's Java one;
     * every video frame goes through it. Conscrypt gained its native direct-buffer AEAD path in
     * Android 12. Older supported releases use the array API to avoid extra staging copies. Buffers
     * for unusually large frames are temporary, so a frame cannot permanently retain large native
     * allocations on a long-lived receive thread.
     */
    private class PlatformChacha(val cipher: Cipher) {
        var input: ByteBuffer = ByteBuffer.allocateDirect(0)
        var output: ByteBuffer = ByteBuffer.allocateDirect(0)
        var implementation: String = cipher.provider.name
    }

    private val platformState = ThreadLocal.withInitial<PlatformChacha?> {
        PLATFORM_CHACHA_NAMES.firstNotNullOfOrNull { runCatching { Cipher.getInstance(it) }.getOrNull() }
            ?.let(::PlatformChacha)
    }

    /** Implementation used by this thread's last successful operation, for diagnostics. */
    val chachaImplementation: String
        get() = platformState.get()?.implementation ?: "BouncyCastle"

    data class X25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)
    data class Ed25519KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun x25519Generate(): X25519KeyPair {
        val privateKey = X25519PrivateKeyParameters(random)
        return X25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun x25519Shared(privateKeyRaw: ByteArray, peerPublicKeyRaw: ByteArray): ByteArray {
        val privateKey = X25519PrivateKeyParameters(privateKeyRaw, 0)
        val peerPublicKey = X25519PublicKeyParameters(peerPublicKeyRaw, 0)
        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        privateKey.generateSecret(peerPublicKey, shared, 0)
        return shared
    }

    fun ed25519Generate(): Ed25519KeyPair {
        val privateKey = Ed25519PrivateKeyParameters(random)
        return Ed25519KeyPair(privateKey.encoded, privateKey.generatePublicKey().encoded)
    }

    fun ed25519Sign(privateKeyRaw: ByteArray, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyRaw, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKeyRaw: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKeyRaw, 0))
        signer.update(data, 0, data.size)
        signer.verifySignature(signature)
    } catch (_: Exception) {
        false
    }

    fun hkdfSha512(
        inputKeyMaterial: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        length: Int = 32,
    ): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(inputKeyMaterial, salt, info))
        val output = ByteArray(length)
        generator.generateBytes(output, 0, length)
        return output
    }

    fun sha512(vararg parts: ByteArray): ByteArray = digest(SHA512Digest(), parts)

    fun chachaSeal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray = platformChacha(Cipher.ENCRYPT_MODE, key, nonce, plaintext, aad)
        ?: chachaSealBouncyCastle(key, nonce, plaintext, aad).also {
            platformState.get()?.implementation = "BouncyCastle"
        }

    fun chachaOpen(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray = platformChacha(Cipher.DECRYPT_MODE, key, nonce, ciphertextAndTag, aad)
        ?: chachaOpenBouncyCastle(key, nonce, ciphertextAndTag, aad).also {
            platformState.get()?.implementation = "BouncyCastle"
        }

    /**
     * Null when the platform has no ChaCha20-Poly1305 or refuses this use of it (a desktop JDK rejects
     * reusing a key and nonce for encryption, which tests do); a failed tag still throws.
     */
    internal fun platformChacha(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        input: ByteArray,
        aad: ByteArray,
        useDirectBuffers: Boolean = Build.VERSION.SDK_INT >= 31,
    ): ByteArray? {
        val state = platformState.get() ?: return null
        return try {
            val cipher = state.cipher
            cipher.init(mode, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            if (aad.isNotEmpty()) cipher.updateAAD(aad)
            if (!useDirectBuffers) return cipher.doFinal(input).also { state.implementation = cipher.provider.name }
            val source = sized(state.input, input.size).also {
                if (it.capacity() <= MAX_RETAINED_BUFFER) state.input = it
            }
            source.put(input).flip()
            val target = sized(state.output, cipher.getOutputSize(input.size).coerceAtLeast(0)).also {
                if (it.capacity() <= MAX_RETAINED_BUFFER) state.output = it
            }
            val written = cipher.doFinal(source, target)
            target.flip()
            ByteArray(written).also { target.get(it); state.implementation = cipher.provider.name }
        } catch (failure: AEADBadTagException) {
            throw failure
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    /** [buffer], or a larger direct one, cleared and limited to [size]. */
    private fun sized(buffer: ByteBuffer, size: Int): ByteBuffer =
        (if (buffer.capacity() >= size) buffer else ByteBuffer.allocateDirect(
            if (size > MAX_RETAINED_BUFFER) size else maxOf(size, buffer.capacity() * 2).coerceAtMost(MAX_RETAINED_BUFFER),
        ))
            .apply { clear(); limit(size) }

    internal fun chachaSealBouncyCastle(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(plaintext.size))
        val length = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
        cipher.doFinal(output, length)
        return output
    }

    internal fun chachaOpenBouncyCastle(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray = ByteArray(0),
    ): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val output = ByteArray(cipher.getOutputSize(ciphertextAndTag.size))
        val processed = cipher.processBytes(ciphertextAndTag, 0, ciphertextAndTag.size, output, 0)
        val finalized = cipher.doFinal(output, processed)
        val outputLength = processed + finalized
        return if (outputLength == output.size) output else output.copyOf(outputLength)
    }

    /** 12-byte nonce: four zero bytes followed by an eight-byte little-endian counter. */
    fun nonce64(counter: Long): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        var value = counter
        for (index in 4 until NONCE_SIZE) {
            nonce[index] = value.toByte()
            value = value ushr 8
        }
        return nonce
    }

    /** 12-byte nonce from an eight-byte ASCII label placed after four zero bytes. */
    fun nonceLabel(label: String): ByteArray {
        val nonce = ByteArray(NONCE_SIZE)
        val ascii = label.asciiBytes()
        ascii.copyInto(nonce, 4, 0, minOf(ascii.size, LABEL_SIZE))
        return nonce
    }

    private fun digest(digest: Digest, parts: Array<out ByteArray>): ByteArray {
        for (part in parts) digest.update(part, 0, part.size)
        val output = ByteArray(digest.digestSize)
        digest.doFinal(output, 0)
        return output
    }
}
