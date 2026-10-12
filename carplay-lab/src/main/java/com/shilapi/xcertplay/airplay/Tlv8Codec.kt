// SPDX-License-Identifier: GPL-3.0-only
// From DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d, shared/src/main/java/
// com/shilapi/xcertplay/airplay/; upstream shilapi/xcertplay. See PROVENANCE.md.
package com.shilapi.xcertplay.airplay

/** HomeKit/AirPlay-style TLV8 codec used by the pairing messages. */
object Tlv8Codec {
    private const val SEPARATOR_TYPE = 0xff
    private const val MAX_FRAGMENT = 255

    fun encode(items: List<Tlv8Item>): ByteArray {
        val chunks = ArrayList<ByteArray>()
        var previousType: Int? = null
        for (item in items) {
            if (previousType == item.type) {
                chunks.add(byteArrayOf(SEPARATOR_TYPE.toByte(), 0))
            }
            var offset = 0
            do {
                val length = minOf(MAX_FRAGMENT, item.value.size - offset)
                chunks.add(byteArrayOf(item.type.toByte(), length.toByte()))
                chunks.add(item.value.copyOfRange(offset, offset + length))
                offset += length
            } while (offset < item.value.size)
            previousType = item.type
        }
        return concatBytes(*chunks.toTypedArray())
    }

    fun decode(buffer: ByteArray): Map<Int, ByteArray> {
        val output = HashMap<Int, ByteArray>()
        var position = 0
        var lastType: Int? = null
        var lastLength = 0
        while (position + 2 <= buffer.size) {
            val type = buffer[position].toInt() and 0xff
            val length = buffer[position + 1].toInt() and 0xff
            position += 2
            require(position + length <= buffer.size) { "Truncated TLV" }
            val value = buffer.copyOfRange(position, position + length)
            position += length
            if (type == lastType && lastLength == MAX_FRAGMENT) {
                val previous = output[type]
                output[type] = if (previous != null) previous + value else value
            } else {
                require(type == SEPARATOR_TYPE || type !in output) { "Duplicate TLV field" }
                output[type] = value
            }
            lastType = type
            lastLength = length
        }
        require(position == buffer.size) { "Truncated TLV header" }
        return output
    }
}

data class Tlv8Item(val type: Int, val value: ByteArray)
