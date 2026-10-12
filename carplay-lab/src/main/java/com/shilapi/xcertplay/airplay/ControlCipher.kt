// SPDX-License-Identifier: GPL-3.0-only
// From DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d, shared/src/main/java/
// com/shilapi/xcertplay/airplay/; upstream shilapi/xcertplay. See PROVENANCE.md.
package com.shilapi.xcertplay.airplay

/**
 * ChaCha20-Poly1305 framing for the CarPlay control channel.
 *
 * Each frame is [2-byte little-endian ciphertext length][ciphertext][16-byte tag]; the length
 * header is the AEAD associated data and each direction uses an 8-byte little-endian nonce
 * counter plus its own key.
 */
class ControlCipher(private val readKey: ByteArray, private val writeKey: ByteArray) {
    data class Decrypted(val data: ByteArray, val rest: ByteArray)

    private var readCounter = 0L
    private var writeCounter = 0L

    fun decrypt(buffer: ByteArray): Decrypted {
        val output = ArrayList<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= HEADER_SIZE) {
            val length = readU16Le(buffer, offset)
            require(length <= MAX_PAYLOAD) { "Control frame limit" }
            val frameEnd = offset + HEADER_SIZE + length + TAG_SIZE
            if (buffer.size < frameEnd) break
            val aad = buffer.copyOfRange(offset, offset + HEADER_SIZE)
            val ciphertextAndTag = buffer.copyOfRange(offset + HEADER_SIZE, frameEnd)
            output.add(AirPlayCrypto.chachaOpen(readKey, AirPlayCrypto.nonce64(readCounter), ciphertextAndTag, aad))
            readCounter++
            offset = frameEnd
        }
        return Decrypted(concatBytes(*output.toTypedArray()), buffer.copyOfRange(offset, buffer.size))
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val output = ArrayList<ByteArray>()
        var offset = 0
        do {
            val chunk = plaintext.copyOfRange(offset, minOf(offset + MAX_PAYLOAD, plaintext.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size ushr 8).toByte())
            val ciphertextAndTag = AirPlayCrypto.chachaSeal(writeKey, AirPlayCrypto.nonce64(writeCounter), chunk, header)
            output.add(header)
            output.add(ciphertextAndTag)
            writeCounter++
            offset += MAX_PAYLOAD
        } while (offset < plaintext.size)
        return concatBytes(*output.toTypedArray())
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private companion object {
        const val HEADER_SIZE = 2
        const val TAG_SIZE = 16
        const val MAX_PAYLOAD = 0x4000
    }
}
