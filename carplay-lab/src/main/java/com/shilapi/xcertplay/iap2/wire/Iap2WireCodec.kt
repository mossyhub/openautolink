package com.shilapi.xcertplay.iap2.wire

import java.io.ByteArrayOutputStream

/**
 * Big-endian scalar and string codecs shared by every iAP2 endpoint body.
 *
 * The codec intentionally contains no endpoint IDs or business defaults. Optional values are
 * represented by nullable arguments at the builder layer, so an omitted value never becomes a
 * zero-filled wire parameter.
 */
object Iap2WireCodec {
    fun void(): ByteArray = ByteArray(0)

    fun u8(value: Int): ByteArray {
        require(value in 0..0xff) { "u8 value must be in 0..255" }
        return byteArrayOf(value.toByte())
    }

    fun i8(value: Int): ByteArray {
        require(value in -0x80..0x7f) { "i8 value must be in -128..127" }
        return byteArrayOf(value.toByte())
    }

    fun bool(value: Boolean): ByteArray = u8(if (value) 1 else 0)

    fun u16(value: Int): ByteArray {
        require(value in 0..0xffff) { "u16 value must be in 0..65535" }
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    fun i16(value: Int): ByteArray {
        require(value in -0x8000..0x7fff) { "i16 value must be in -32768..32767" }
        return u16(value and 0xffff)
    }

    fun u32(value: Long): ByteArray {
        require(value in 0..0xffff_ffffL) { "u32 value must be in 0..4294967295" }
        return byteArrayOf(
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }

    fun u32(value: Int): ByteArray = u32(value.toLong())

    fun i32(value: Int): ByteArray = u32(value.toLong() and 0xffff_ffffL)

    fun u64(value: Long): ByteArray {
        require(value >= 0) { "u64 value must be non-negative in this API" }
        return byteArrayOf(
            (value ushr 56).toByte(),
            (value ushr 48).toByte(),
            (value ushr 40).toByte(),
            (value ushr 32).toByte(),
            (value ushr 24).toByte(),
            (value ushr 16).toByte(),
            (value ushr 8).toByte(),
            value.toByte(),
        )
    }

    fun bytes(value: ByteArray): ByteArray = value.copyOf()

    fun string(value: String): ByteArray {
        require('\u0000' !in value) { "NUL-terminated strings must not contain U+0000" }
        return value.encodeToByteArray() + byteArrayOf(0)
    }

    fun strings(values: Iterable<String>): ByteArray {
        val output = ByteArrayOutputStream()
        for (value in values) output.write(string(value))
        return output.toByteArray()
    }

    fun u16List(values: Iterable<Int>): ByteArray {
        val materialized = values.toList()
        val output = ByteArray(materialized.size * 2)
        materialized.forEachIndexed { index, value ->
            require(value in 0..0xffff) { "u16[] value must be in 0..65535" }
            output[index * 2] = (value ushr 8).toByte()
            output[index * 2 + 1] = value.toByte()
        }
        return output
    }

    fun readU8(bytes: ByteArray, offset: Int = 0): Int = u8(bytes[offset])

    fun readI8(bytes: ByteArray, offset: Int = 0): Int = bytes[offset].toInt()

    fun readBool(bytes: ByteArray, offset: Int = 0, nonzeroIsTrue: Boolean = true): Boolean {
        val value = readU8(bytes, offset)
        return if (nonzeroIsTrue) value != 0 else value == 1
    }

    fun readU16(bytes: ByteArray, offset: Int = 0): Int =
        (u8(bytes[offset]) shl 8) or u8(bytes[offset + 1])

    fun readI16(bytes: ByteArray, offset: Int = 0): Int {
        val value = readU16(bytes, offset)
        return if (value and 0x8000 != 0) value or -0x1_0000 else value
    }

    fun readU32(bytes: ByteArray, offset: Int = 0): Long =
        (u8(bytes[offset]).toLong() shl 24) or
            (u8(bytes[offset + 1]).toLong() shl 16) or
            (u8(bytes[offset + 2]).toLong() shl 8) or
            u8(bytes[offset + 3]).toLong()

    fun readI32(bytes: ByteArray, offset: Int = 0): Int = readU32(bytes, offset).toInt()

    fun readU64(bytes: ByteArray, offset: Int = 0): Long {
        var value = 0L
        repeat(8) { index ->
            value = (value shl 8) or u8(bytes[offset + index]).toLong()
        }
        return value
    }

    fun readBytes(payload: ByteArray): ByteArray = payload.copyOf()

    fun readString(payload: ByteArray): String {
        if (payload.isEmpty() || payload.last() != 0.toByte()) {
            throw Iap2ProtocolException("CSM string is not NUL terminated")
        }
        return payload.copyOf(payload.size - 1).decodeToString()
    }

    fun readStrings(payload: ByteArray): List<String> {
        if (payload.isEmpty()) return emptyList()
        if (payload.last() != 0.toByte()) {
            throw Iap2ProtocolException("CSM string[] is not NUL terminated")
        }
        val values = ArrayList<String>()
        var start = 0
        for (index in payload.indices) {
            if (payload[index] != 0.toByte()) continue
            values += payload.copyOfRange(start, index).decodeToString()
            start = index + 1
        }
        return values
    }

    fun readU16List(payload: ByteArray): List<Int> {
        if (payload.size % 2 != 0) {
            throw Iap2ProtocolException("CSM u16[] payload has odd length ${payload.size}")
        }
        return List(payload.size / 2) { index -> readU16(payload, index * 2) }
    }

    private fun u8(byte: Byte): Int = byte.toInt() and 0xff
}
