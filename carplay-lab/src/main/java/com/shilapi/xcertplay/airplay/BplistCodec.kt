// SPDX-License-Identifier: GPL-3.0-only
// From DiPlay e2fd8eadf5a6b705666f292055e72aa93387533d, shared/src/main/java/
// com/shilapi/xcertplay/airplay/; upstream shilapi/xcertplay. See PROVENANCE.md.
package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size in 40..MAX_INPUT_BYTES) { "bplist: invalid input size" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "bplist: bad magic" }

        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val refSize = bytes[trailer + 7].toInt() and 0xff
        val objectCount = readBigEndianLong(bytes, (trailer + 8).toLong(), 8)
        val top = readBigEndianLong(bytes, (trailer + 16).toLong(), 8)
        val offsetTable = readBigEndianLong(bytes, (trailer + 24).toLong(), 8)
        require(offsetSize in 1..8 && refSize in 1..8) { "bplist: invalid integer widths" }
        require(objectCount in 1..65_536 && top >= 0 && top < objectCount) { "bplist: invalid object count or root" }
        require(offsetTable >= magic.size && offsetTable <= trailer.toLong()) { "bplist: invalid offset table" }
        require(objectCount <= (trailer - offsetTable) / offsetSize) { "bplist: truncated offset table" }
        val numObjects = objectCount.toInt()
        val topObject = top.toInt()

        // Validate every offset before allocating the object-offset array, including unreachable objects.
        for (index in 0 until numObjects) {
            val offset = readBigEndianLong(bytes, offsetTable + index.toLong() * offsetSize, offsetSize)
            require(offset >= magic.size && offset < offsetTable) { "bplist: invalid object offset" }
        }
        val offsets = LongArray(numObjects) { index ->
            readBigEndianLong(bytes, offsetTable + index.toLong() * offsetSize, offsetSize)
        }
        return Decoder(bytes, offsets, refSize, offsetTable.toInt()).read(topObject)
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    // Control plists are small; generous limits retain data/string and extended-length compatibility.
    private const val MAX_INPUT_BYTES = 8 * 1024 * 1024
    private const val MAX_CONTAINER_COUNT = 16_384
    private const val MAX_DEPTH = 64
    private const val MAX_VISITS = 131_072
    private const val MAX_DECODED_BYTES = 16 * 1024 * 1024

    private class Decoder(
        val bytes: ByteArray, val offsets: LongArray, val refSize: Int, val objectEnd: Int,
    ) {
        private val active = BooleanArray(offsets.size)
        private var visits = 0
        private var decodedBytes = 0L

        private fun span(position: Int, length: Long) {
            require(position >= magic.size && position <= objectEnd && length >= 0 &&
                length <= objectEnd.toLong() - position) { "bplist: truncated object" }
        }

        private fun number(position: Int, size: Int): Long {
            require(size in 1..8) { "bplist: unsupported integer width" }
            span(position, size.toLong())
            return readBigEndianLong(bytes, position.toLong(), size)
        }

        private fun reference(position: Int): Int {
            val value = number(position, refSize)
            require(value >= 0 && value < offsets.size) { "bplist: invalid reference" }
            return value.toInt()
        }

        fun read(index: Int, depth: Int = 0): Any? {
            require(depth < MAX_DEPTH && ++visits <= MAX_VISITS) { "bplist: traversal limit" }
            require(!active[index]) { "bplist: cyclic reference" }
            active[index] = true
            try {
                var position = offsets[index].toInt()
                val markerByte = number(position++, 1).toInt()
                val type = markerByte ushr 4
                val nibble = markerByte and 0x0f
                fun count(): Int {
                    if (nibble != 15) return nibble
                    val marker = number(position++, 1).toInt()
                    require(marker ushr 4 == 1 && marker and 15 <= 3) { "bplist: invalid count marker" }
                    val size = 1 shl (marker and 15)
                    val value = number(position, size)
                    position += size
                    require(value in 0..MAX_INPUT_BYTES.toLong()) { "bplist: excessive count" }
                    return value.toInt()
                }
                fun payload(count: Int, width: Int): Int {
                    val length = count.toLong() * width
                    span(position, length)
                    decodedBytes += length * 2 // Includes the string's decoded character storage.
                    require(decodedBytes <= MAX_DECODED_BYTES) { "bplist: decoded byte limit" }
                    return length.toInt()
                }
                fun container(count: Int, refsPerEntry: Int) {
                    require(count <= MAX_CONTAINER_COUNT) { "bplist: excessive container count" }
                    val refs = count.toLong() * refsPerEntry
                    span(position, refs * refSize)
                    require(refs <= MAX_VISITS - visits) { "bplist: traversal limit" }
                    // Validate the entire ref block before allocating or following any reference.
                    for (i in 0 until refs.toInt()) reference(position + i * refSize)
                }
                return when (type) {
                    0 -> when (nibble) {
                        0 -> null
                        8 -> false
                        9 -> true
                        else -> throw IllegalArgumentException("bplist: unsupported primitive")
                    }
                    1 -> {
                        require(nibble <= 3) { "bplist: unsupported integer width" }
                        number(position, 1 shl nibble)
                    }
                    2 -> when (nibble) {
                        2 -> Float.fromBits(number(position, 4).toInt())
                        3 -> Double.fromBits(number(position, 8))
                        else -> throw IllegalArgumentException("bplist: unsupported real width")
                    }
                    3 -> {
                        require(nibble == 3) { "bplist: invalid date marker" }
                        java.util.Date(Math.round((Double.fromBits(number(position, 8)) + APPLE_EPOCH_SECONDS) * 1000))
                    }
                    4 -> { val length = payload(count(), 1); bytes.copyOfRange(position, position + length) }
                    5 -> { val length = payload(count(), 1); String(bytes, position, length, Charsets.US_ASCII) }
                    6 -> { val length = payload(count(), 2); String(bytes, position, length, Charsets.UTF_16BE) }
                    8 -> number(position, nibble + 1)
                    0xa, 0xc -> {
                        val count = count()
                        container(count, 1)
                        ArrayList<Any?>(count).also { result ->
                            for (i in 0 until count) result.add(read(reference(position + i * refSize), depth + 1))
                        }
                    }
                    0xd -> {
                        val count = count()
                        container(count, 2)
                        LinkedHashMap<String, Any?>(count).also { result ->
                            for (i in 0 until count) {
                                val key = read(reference(position + i * refSize), depth + 1)
                                require(key is String) { "bplist: dictionary key is not a string" }
                                result[key] = read(reference(position + (count + i) * refSize), depth + 1)
                            }
                        }
                    }
                    else -> throw IllegalArgumentException("bplist: unsupported object type")
                }
            } finally {
                active[index] = false
            }
        }
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        return byteArrayOf((0x10 or log).toByte()) + raw.copyOfRange(raw.size - size, raw.size)
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        var value = 0L
        var base = offset.toInt()
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()

    private const val APPLE_EPOCH_SECONDS = 978_307_200.0
}
