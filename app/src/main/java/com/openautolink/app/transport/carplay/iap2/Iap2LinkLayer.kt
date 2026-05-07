package com.openautolink.app.transport.carplay.iap2

/**
 * iAP2 Link Layer — frame encoding/decoding and reliable delivery.
 *
 * The iAP2 link layer provides framed, reliable, ordered delivery of
 * control session messages over a raw byte stream (USB bulk, BT RFCOMM, etc).
 *
 * Frame format (from Apple MFi Accessory Interface Specification R30+):
 * ```
 * Byte 0:     Start of Frame marker (0xFF 0x5A)
 * Byte 1:     (continuation of SoF)
 * Byte 2-3:   Frame length (big-endian, includes header but not SoF)
 * Byte 4:     Control byte
 *               Bits 7-4: Frame type (SYN=0x8, ACK-only=0x4, Data=0x0)
 *               Bits 3-0: Reserved
 * Byte 5:     Sequence number (sender's seq for this frame)
 * Byte 6:     Acknowledgement number (last seq received from peer + 1)
 * Byte 7:     Session ID (0 = control session)
 * Byte 8..N:  Payload (iAP2 control message)
 * Last byte:  Checksum (one's complement of sum of bytes 2..N-1)
 * ```
 *
 * Connection setup (3-way handshake):
 *   Accessory → Device: SYN (with link params: max payload, retransmit timeout)
 *   Device → Accessory: SYN-ACK
 *   Accessory → Device: ACK
 *
 * Data transfer:
 *   Sender transmits data frames with incrementing seq numbers.
 *   Receiver acknowledges with ACK frames (ack number = next expected seq).
 *   Retransmit on timeout.
 *
 * Reference: libimobiledevice, Apple MFi R30+ spec chapter on iAP2 link layer.
 */

private const val TAG = "iAP2Link"

/** Start of Frame marker bytes */
const val IAP2_SOF_BYTE1: Byte = 0xFF.toByte()
const val IAP2_SOF_BYTE2: Byte = 0x5A

/** Frame type (upper nibble of control byte) */
object Iap2FrameType {
    const val DATA: Int = 0x00
    const val ACK: Int = 0x40
    const val SYN: Int = 0x80
    const val RST: Int = 0xC0 // Reset

    fun name(type: Int): String = when (type and 0xC0) {
        DATA -> "DATA"
        ACK -> "ACK"
        SYN -> "SYN"
        RST -> "RST"
        else -> "UNKNOWN(0x${type.toString(16)})"
    }
}

/** Control session ID (always 0 for iAP2 control messages) */
const val IAP2_CONTROL_SESSION: Byte = 0x00

/** Maximum link-layer payload (negotiated during SYN, typical default) */
const val IAP2_DEFAULT_MAX_PAYLOAD = 1500

/**
 * A parsed iAP2 link-layer frame.
 */
data class Iap2Frame(
    val frameType: Int,     // Upper nibble of control byte
    val seqNum: Int,        // Sequence number (0-255)
    val ackNum: Int,        // Acknowledgement number (0-255)
    val sessionId: Int,     // Session ID (0 = control)
    val payload: ByteArray, // Frame payload (empty for ACK/SYN)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Iap2Frame) return false
        return frameType == other.frameType &&
                seqNum == other.seqNum &&
                ackNum == other.ackNum &&
                sessionId == other.sessionId &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = seqNum * 31 + ackNum

    override fun toString(): String =
        "Frame(${Iap2FrameType.name(frameType)} seq=$seqNum ack=$ackNum session=$sessionId payload=${payload.size}B)"
}

/**
 * SYN payload — link parameters exchanged during handshake.
 *
 * Format (from spec):
 * ```
 * Byte 0:     Version (currently 1)
 * Byte 1:     Max outstanding (number of unacked frames)
 * Byte 2-3:   Max packet length (big-endian)
 * Byte 4-5:   Retransmit timeout ms (big-endian)
 * Byte 6-7:   Cumulative ACK timeout ms (big-endian)
 * Byte 8:     Max retransmissions
 * Byte 9:     Max cumulative ACKs
 * Byte 10-13: Session IDs (list of supported sessions)
 * ```
 */
data class Iap2SynPayload(
    val version: Int = 1,
    val maxOutstanding: Int = 1,
    val maxPacketLength: Int = IAP2_DEFAULT_MAX_PAYLOAD,
    val retransmitTimeoutMs: Int = 1000,
    val cumAckTimeoutMs: Int = 50,
    val maxRetransmissions: Int = 30,
    val maxCumAcks: Int = 3,
    val sessions: List<Int> = listOf(0), // Session 0 = control
) {
    fun encode(): ByteArray {
        val data = ByteArray(10 + sessions.size)
        data[0] = version.toByte()
        data[1] = maxOutstanding.toByte()
        data[2] = (maxPacketLength shr 8).toByte()
        data[3] = (maxPacketLength and 0xFF).toByte()
        data[4] = (retransmitTimeoutMs shr 8).toByte()
        data[5] = (retransmitTimeoutMs and 0xFF).toByte()
        data[6] = (cumAckTimeoutMs shr 8).toByte()
        data[7] = (cumAckTimeoutMs and 0xFF).toByte()
        data[8] = maxRetransmissions.toByte()
        data[9] = maxCumAcks.toByte()
        for (i in sessions.indices) {
            data[10 + i] = sessions[i].toByte()
        }
        return data
    }

    companion object {
        fun decode(data: ByteArray): Iap2SynPayload {
            if (data.size < 10) throw IllegalArgumentException("SYN payload too short: ${data.size}")
            val sessions = mutableListOf<Int>()
            for (i in 10 until data.size) {
                sessions.add(data[i].toInt() and 0xFF)
            }
            return Iap2SynPayload(
                version = data[0].toInt() and 0xFF,
                maxOutstanding = data[1].toInt() and 0xFF,
                maxPacketLength = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF),
                retransmitTimeoutMs = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF),
                cumAckTimeoutMs = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF),
                maxRetransmissions = data[8].toInt() and 0xFF,
                maxCumAcks = data[9].toInt() and 0xFF,
                sessions = sessions,
            )
        }
    }
}

/**
 * Encodes and decodes iAP2 link-layer frames.
 */
object Iap2FrameCodec {

    /** Header size: SoF(2) + length(2) + control(1) + seq(1) + ack(1) + session(1) = 8 */
    const val HEADER_SIZE = 8

    /** Minimum frame size: header + checksum */
    const val MIN_FRAME_SIZE = HEADER_SIZE + 1

    /**
     * Encode a frame to bytes for transmission.
     */
    fun encode(frame: Iap2Frame): ByteArray {
        val payloadLen = frame.payload.size
        // Length field = everything after SoF: length(2) + ctrl(1) + seq(1) + ack(1) + session(1) + payload + checksum(1)
        val frameLen = 6 + payloadLen + 1
        val buf = ByteArray(2 + frameLen) // SoF + frame

        // Start of Frame
        buf[0] = IAP2_SOF_BYTE1
        buf[1] = IAP2_SOF_BYTE2

        // Length (big-endian)
        buf[2] = (frameLen shr 8).toByte()
        buf[3] = (frameLen and 0xFF).toByte()

        // Control byte
        buf[4] = frame.frameType.toByte()

        // Sequence number
        buf[5] = frame.seqNum.toByte()

        // Acknowledgement number
        buf[6] = frame.ackNum.toByte()

        // Session ID
        buf[7] = frame.sessionId.toByte()

        // Payload
        if (payloadLen > 0) {
            System.arraycopy(frame.payload, 0, buf, 8, payloadLen)
        }

        // Checksum: one's complement of sum of bytes from index 2 to end-1
        var sum = 0
        for (i in 2 until buf.size - 1) {
            sum += buf[i].toInt() and 0xFF
        }
        buf[buf.size - 1] = (0xFF - (sum and 0xFF)).toByte()

        return buf
    }

    /**
     * Try to decode a frame from the given buffer starting at [offset].
     * Returns the frame and the number of bytes consumed, or null if incomplete/invalid.
     */
    fun decode(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): DecodeResult? {
        if (length < MIN_FRAME_SIZE) return null

        // Find SoF
        var pos = offset
        while (pos < offset + length - 1) {
            if (buf[pos] == IAP2_SOF_BYTE1 && buf[pos + 1] == IAP2_SOF_BYTE2) break
            pos++
        }
        if (pos >= offset + length - 1) return null

        val sofPos = pos
        val remaining = length - (sofPos - offset)
        if (remaining < MIN_FRAME_SIZE) return null

        // Read length
        val frameLen = ((buf[sofPos + 2].toInt() and 0xFF) shl 8) or (buf[sofPos + 3].toInt() and 0xFF)
        val totalLen = 2 + frameLen // SoF + frame content
        if (remaining < totalLen) return null // Incomplete frame

        // Verify checksum
        var sum = 0
        for (i in sofPos + 2 until sofPos + totalLen) {
            sum += buf[i].toInt() and 0xFF
        }
        if ((sum and 0xFF) != 0xFF) {
            // Bad checksum — skip this SoF and try again
            return null
        }

        // Parse frame
        val controlByte = buf[sofPos + 4].toInt() and 0xFF
        val frameType = controlByte and 0xC0
        val seqNum = buf[sofPos + 5].toInt() and 0xFF
        val ackNum = buf[sofPos + 6].toInt() and 0xFF
        val sessionId = buf[sofPos + 7].toInt() and 0xFF

        // Payload = everything between header and checksum
        val payloadLen = totalLen - HEADER_SIZE - 1
        val payload = if (payloadLen > 0) {
            buf.copyOfRange(sofPos + HEADER_SIZE, sofPos + HEADER_SIZE + payloadLen)
        } else {
            ByteArray(0)
        }

        val frame = Iap2Frame(
            frameType = frameType,
            seqNum = seqNum,
            ackNum = ackNum,
            sessionId = sessionId,
            payload = payload,
        )

        val bytesConsumed = totalLen + (sofPos - offset)
        return DecodeResult(frame, bytesConsumed)
    }

    data class DecodeResult(val frame: Iap2Frame, val bytesConsumed: Int)
}
