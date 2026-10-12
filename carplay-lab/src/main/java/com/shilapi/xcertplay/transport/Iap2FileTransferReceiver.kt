package com.shilapi.xcertplay.transport

import java.io.ByteArrayOutputStream

/** A completed iAP2 v2 NowPlayingArtworkData transfer. */
data class Iap2ArtworkTransfer(val id: Int, val bytes: ByteArray)

/**
 * Bounded receiver for iAP2 file-transfer session datagrams.
 *
 * Datagram byte 0 is the transfer id. Byte 1 contains First/Last flags and a low-nibble opcode:
 * Data(0), Start(1), Cancel(2), Setup(4), or Success(5). Version-2 Setup carries an eight-byte
 * big-endian size followed by a two-byte file type. Only NowPlayingArtworkData is accepted.
 */
class Iap2FileTransferReceiver(
    private val maximumArtworkBytes: Int = DEFAULT_MAXIMUM_ARTWORK_BYTES,
) {
    data class Outcome(
        val replies: List<ByteArray> = emptyList(),
        val completed: Iap2ArtworkTransfer? = null,
    )

    private data class Pending(
        val expectedBytes: Int,
        val output: ByteArrayOutputStream = ByteArrayOutputStream(expectedBytes),
    )

    private val pending = LinkedHashMap<Int, Pending>()

    init {
        require(maximumArtworkBytes > 0)
    }

    @Synchronized
    fun accept(datagram: ByteArray): Outcome {
        if (datagram.size < HEADER_BYTES) return Outcome()
        val id = datagram[0].toInt() and 0xff
        val flags = datagram[1].toInt() and 0xff
        return when (flags and OPCODE_MASK) {
            OPCODE_SETUP -> setup(id, datagram)
            OPCODE_DATA -> data(id, flags, datagram)
            OPCODE_CANCEL -> {
                if (pending.remove(id) == null) return Outcome()
                Outcome(completed = Iap2ArtworkTransfer(id, ByteArray(0)))
            }
            else -> Outcome()
        }
    }

    @Synchronized
    fun clear() = pending.clear()

    private fun setup(id: Int, datagram: ByteArray): Outcome {
        pending.remove(id)
        if (datagram.size < VERSION_2_SETUP_BYTES) return Outcome(replies = listOf(cancel(id)))
        val size = readU64(datagram, SIZE_OFFSET)
        val type = readU16(datagram, FILE_TYPE_OFFSET)
        if (type != NOW_PLAYING_ARTWORK_TYPE) return Outcome(replies = listOf(cancel(id)))
        if (size < 0 || size > maximumArtworkBytes) return rejected(id)
        if (size == 0L) {
            return Outcome(
                replies = listOf(start(id), success(id)),
                completed = Iap2ArtworkTransfer(id, ByteArray(0)),
            )
        }
        pending[id] = Pending(size.toInt())
        while (pending.size > MAXIMUM_PENDING_TRANSFERS) pending.remove(pending.keys.first())
        return Outcome(replies = listOf(start(id)))
    }

    private fun data(id: Int, flags: Int, datagram: ByteArray): Outcome {
        val transfer = pending[id] ?: return Outcome()
        val count = datagram.size - HEADER_BYTES
        if (count > transfer.expectedBytes - transfer.output.size()) {
            pending.remove(id)
            return rejected(id)
        }
        transfer.output.write(datagram, HEADER_BYTES, count)
        val last = flags and FLAG_LAST != 0
        if (!last && transfer.output.size() < transfer.expectedBytes) return Outcome()
        pending.remove(id)
        if (transfer.output.size() != transfer.expectedBytes) {
            return rejected(id)
        }
        return Outcome(
            replies = listOf(success(id)),
            completed = Iap2ArtworkTransfer(id, transfer.output.toByteArray()),
        )
    }

    // An empty completion clears the art, so a refused cover does not leave the previous song's.
    private fun rejected(id: Int) = Outcome(replies = listOf(cancel(id)), completed = Iap2ArtworkTransfer(id, ByteArray(0)))

    private fun start(id: Int) = byteArrayOf(id.toByte(), OPCODE_START.toByte())

    private fun cancel(id: Int) = byteArrayOf(id.toByte(), OPCODE_CANCEL.toByte())

    private fun success(id: Int) = byteArrayOf(id.toByte(), OPCODE_SUCCESS.toByte())

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(8) {
            if (value < 0) return -1
            value = (value shl 8) or (bytes[offset + it].toLong() and 0xff)
        }
        return value
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    companion object {
        private const val HEADER_BYTES = 2
        private const val SIZE_OFFSET = 2
        private const val FILE_TYPE_OFFSET = 10
        private const val VERSION_2_SETUP_BYTES = 12
        private const val OPCODE_MASK = 0x0f
        private const val FLAG_LAST = 0x40
        private const val OPCODE_DATA = 0
        private const val OPCODE_START = 1
        private const val OPCODE_CANCEL = 2
        private const val OPCODE_SETUP = 4
        private const val OPCODE_SUCCESS = 5
        private const val NOW_PLAYING_ARTWORK_TYPE = 2
        private const val MAXIMUM_PENDING_TRANSFERS = 4
        const val DEFAULT_MAXIMUM_ARTWORK_BYTES = 2 * 1024 * 1024
    }
}
