// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.pairing

import com.shilapi.xcertplay.airplay.ControlCipher
import com.shilapi.xcertplay.airplay.RtspMessage
import java.io.EOFException
import java.io.InputStream

/** Bounded, single-request reader. Never reads plaintext beyond the verify response boundary. */
internal class PairingFraming(private val input: InputStream, var cipher: ControlCipher? = null) {
    private var decrypted = ByteArray(0)
    private var position = 0

    fun readRequest(): RtspMessage.Request? {
        val head = ArrayList<Byte>()
        while (true) {
            val b = readByte()
            if (b < 0) {
                if (head.isEmpty()) return null
                throw EOFException()
            }
            require(b in 9..13 || b in 32..126) { "Invalid header byte" }
            head.add(b.toByte())
            require(head.size <= MAX_HEADER) { "Header limit" }
            if (head.size >= 4 && head.takeLast(4) == listOf<Byte>(13, 10, 13, 10)) break
        }
        val header = head.toByteArray()
        val lines = header.toString(Charsets.US_ASCII).removeSuffix("\r\n\r\n").split("\r\n")
        val line = lines.first().split(' ')
        require(line.size == 3 && line[0].matches(Regex("[A-Z]{1,20}"))) { "Invalid request line" }
        require(line[1].length in 1..1024 && line[1].none { it <= ' ' }) { "Invalid path" }
        require(line[2] == "RTSP/1.0" || line[2] == "HTTP/1.1" || line[2] == "HTTP/1.0") { "Invalid protocol" }
        val headers = linkedMapOf<String, String>()
        for (entry in lines.drop(1)) {
            val colon = entry.indexOf(':')
            require(colon > 0) { "Invalid header" }
            val name = entry.substring(0, colon)
            require(name.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid header name" }
            val lower = name.lowercase(java.util.Locale.ROOT)
            require(!headers.containsKey(lower)) { "Duplicate header" }
            val value = entry.substring(colon + 1).trim()
            require(value.none { it < ' ' || it == '\u007f' }) { "Invalid header value" }
            headers[lower] = value
        }
        require("transfer-encoding" !in headers) { "Transfer encoding forbidden" }
        val length = headers["content-length"]?.let {
            require(it.matches(Regex("[0-9]{1,5}"))) { "Invalid content length" }
            it.toInt()
        } ?: 0
        require(length <= MAX_BODY) { "Body limit" }
        val body = ByteArray(length) { readByte().also { if (it < 0) throw EOFException() }.toByte() }
        // Preserve the pinned upstream request model and parser on validated framing.
        return RtspMessage.parseMessages(header + body).messages.single()
    }

    private fun readByte(): Int {
        val active = cipher ?: return input.read()
        while (position == decrypted.size) {
            val low = input.read()
            if (low < 0) return -1
            val high = input.read()
            if (high < 0) throw EOFException()
            val length = low or (high shl 8)
            require(length <= MAX_FRAME) { "Control frame limit" }
            val wire = byteArrayOf(low.toByte(), high.toByte()) + exact(length + 16)
            decrypted = active.decrypt(wire).data
            position = 0
        }
        return decrypted[position++].toInt() and 255
    }

    private fun exact(length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val n = input.read(result, offset, length - offset)
            if (n < 0) throw EOFException()
            if (n == 0) throw EOFException()
            offset += n
        }
        return result
    }

    companion object {
        const val MAX_HEADER = 8192
        const val MAX_BODY = 32768
        const val MAX_FRAME = 16384
    }
}
