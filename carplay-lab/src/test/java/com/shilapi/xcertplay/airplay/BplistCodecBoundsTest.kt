package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class BplistCodecBoundsTest {
    private fun plist(vararg objects: ByteArray, refSize: Int = 1): ByteArray {
        val body = objects.fold(ByteArray(0)) { a, b -> a + b }
        val table = ByteBuffer.allocate(objects.size * 4)
        var offset = 8
        objects.forEach { table.putInt(offset); offset += it.size }
        val trailer = ByteBuffer.allocate(32)
        trailer.put(6, 4); trailer.put(7, refSize.toByte())
        trailer.putLong(8, objects.size.toLong()).putLong(24, offset.toLong())
        return "bplist00".toByteArray() + body + table.array() + trailer.array()
    }

    @Test fun rejectsContainerCountBeforeAllocatingOrReadingReferences() {
        // Array length guard on RED: never ask the heap to allocate a huge backing array.
        val count = byteArrayOf(0xaf.toByte(), 0x12) + ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array()
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(count)) }
    }

    @Test fun rejectsCyclicReferences() {
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(byteArrayOf(0xa1.toByte(), 0))) }
        assertThrows(IllegalArgumentException::class.java) {
            BplistCodec.decode(plist(byteArrayOf(0xa1.toByte(), 1), byteArrayOf(0xa1.toByte(), 0)))
        }
    }

    @Test fun rejectsObjectPayloadsThatCrossTheOffsetTable() {
        listOf(byteArrayOf(0x4e), byteArrayOf(0x6e), byteArrayOf(0x13), byteArrayOf(0xa2.toByte(), 0)).forEach {
            assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(it)) }
        }
    }

    @Test fun rejectsInvalidReferencesAndExtendedCountMarkers() {
        listOf(byteArrayOf(0xa1.toByte(), 1), byteArrayOf(0xaf.toByte(), 0x50, 0),
            byteArrayOf(0x4f, 0x13) + ByteArray(8) { 0xff.toByte() }).forEach {
            assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(it)) }
        }
    }

    @Test fun boundsDeepAcyclicGraphs() {
        val objects = Array(100) { i -> if (i == 99) byteArrayOf(9) else byteArrayOf(0xa1.toByte(), (i + 1).toByte()) }
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(*objects)) }
    }

    @Test fun boundsRepeatedReferenceExpansion() {
        val objects = Array(20) { i -> if (i == 19) byteArrayOf(9) else byteArrayOf(0xa2.toByte(), (i + 1).toByte(), (i + 1).toByte()) }
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(*objects)) }
    }

    @Test fun boundsInputBytes() {
        val bytes = BplistCodec.encode(ByteArray(8 * 1024 * 1024))
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }
    }

    @Test fun rejectsMalformedTrailerWidthsOffsetsCountsAndRootIndices() {
        val valid = BplistCodec.encode(true)
        val trailer = valid.size - 32
        val cases = listOf<Pair<Int, Long>>(
            trailer + 8 to 0L, trailer + 8 to 0x1_0000_0001L, trailer + 8 to -1L,
            trailer + 16 to 1L, trailer + 16 to 0x1_0000_0000L,
            trailer + 24 to 0L, trailer + 24 to Long.MAX_VALUE, trailer + 24 to -1L,
        )
        cases.forEach { (offset, value) ->
            val bytes = valid.copyOf(); ByteBuffer.wrap(bytes).putLong(offset, value)
            assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }
        }
        for (width in listOf(0, 9, 255)) for (field in listOf(6, 7)) {
            val bytes = valid.copyOf(); bytes[trailer + field] = width.toByte()
            assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }
        }
        for (offset in listOf(0, 7, 9, 255)) {
            val bytes = valid.copyOf(); bytes[9] = offset.toByte()
            assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }
        }
    }

    @Test fun preservesProtocolValuesExtendedLengthsAndSharedReferences() {
        val root = linkedMapOf("type" to "update", "params" to listOf(true, false, 42L, -1L,
            1.25, "é水".repeat(30), "a".repeat(300)))
        val decoded = BplistCodec.decode(BplistCodec.encode(root)) as Map<*, *>
        assertEquals("update", decoded["type"])
        val values = decoded["params"] as List<*>
        assertEquals(listOf(true, false, 42L, -1.0, 1.25, "é水".repeat(30), "a".repeat(300)), values)
        assertArrayEquals(ByteArray(300) { it.toByte() }, BplistCodec.decode(BplistCodec.encode(ByteArray(300) { it.toByte() })) as ByteArray)
        assertEquals(listOf(true, true), BplistCodec.decode(plist(byteArrayOf(0xa2.toByte(), 1, 1), byteArrayOf(9))))
        assertEquals(listOf(true), BplistCodec.decode(plist(byteArrayOf(0xc1.toByte(), 1), byteArrayOf(9))))
        assertEquals(java.util.Date(978307200000), BplistCodec.decode(plist(byteArrayOf(0x33) + ByteArray(8))))
        assertEquals(42L, BplistCodec.decode(plist(byteArrayOf(0x80.toByte(), 42))))
        assertEquals(-1L, BplistCodec.decode(plist(byteArrayOf(0x13) + ByteArray(8) { 0xff.toByte() })))
        // Eight-byte table/ref fields remain valid; only sizes and unsigned ranges are restricted.
        assertEquals(listOf(true), BplistCodec.decode(plist(byteArrayOf(0xa1.toByte()) + ByteBuffer.allocate(8).putLong(1).array(), byteArrayOf(9), refSize = 8)))
    }

    @Test fun malformedPrePairVerifyCommandIsHandledWithoutErrorThroughActualSession() {
        val bytes = ByteArray(40)
        "bplist00".toByteArray().copyInto(bytes)
        bytes[14] = 1; bytes[15] = 1
        ByteBuffer.wrap(bytes).putLong(16, Int.MAX_VALUE.toLong()).putLong(32, 8)
        val config = AirPlayConfig("test", "test", "test", "950.7.1", AirPlayDisplayConfig(800, 480))
        val session = AirPlaySession(java.net.Socket(), config, AirPlayIdentity.generate(), PairingStore(), null,
            object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        try {
            assertFalse(session.pairVerify.isVerified)
            val handle = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java).apply { isAccessible = true }
            val response = handle.invoke(session, RtspMessage.Request("POST", "/command", "RTSP/1.0", emptyMap(), bytes)) as RtspMessage.Response
            // Existing command compatibility: malformed input becomes an empty command, acknowledged 200.
            assertEquals(200, response.status)
            assertFalse(session.controlAuthenticated)
        } finally { session.close() }
    }

    @Test fun boundsContainerEntriesEvenWhenAllReferencesFit() {
        val count = 16_385
        val marker = byteArrayOf(0xaf.toByte(), 0x12) + ByteBuffer.allocate(4).putInt(count).array()
        for (type in listOf(0xaf, 0xcf, 0xdf)) {
            val header = marker.copyOf().also { it[0] = type.toByte() }
            val refs = ByteArray(count * if (type == 0xdf) 2 else 1) { 1 }
            assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(header + refs, byteArrayOf(9))) }
        }
    }

    @Test fun boundsRepeatedScalarCopies() {
        val data = byteArrayOf(0x4f, 0x12) + ByteBuffer.allocate(4).putInt(1024 * 1024).array() + ByteArray(1024 * 1024)
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(plist(byteArrayOf(0xa9.toByte()) + ByteArray(9) { 1 }, data)) }
    }

    @Test fun dictionaryKeysMustBeStringsRatherThanStringifyingContainers() {
        assertThrows(IllegalArgumentException::class.java) {
            BplistCodec.decode(plist(byteArrayOf(0xd1.toByte(), 1, 2), byteArrayOf(0xa1.toByte(), 2), byteArrayOf(9)))
        }
    }

    @Test fun rejectsFortyByteTrailerBeforeAllocatingObjectOffsets() {
        // Int.MAX_VALUE hits the JVM array-length guard, not a real heap allocation on RED.
        val bytes = ByteArray(40)
        "bplist00".toByteArray().copyInto(bytes)
        bytes[14] = 1; bytes[15] = 1
        ByteBuffer.wrap(bytes).putLong(16, Int.MAX_VALUE.toLong()).putLong(32, 8)
        assertThrows(IllegalArgumentException::class.java) { BplistCodec.decode(bytes) }
    }
}
