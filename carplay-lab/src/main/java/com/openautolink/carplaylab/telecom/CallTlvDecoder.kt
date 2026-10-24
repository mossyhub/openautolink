package com.openautolink.carplaylab.telecom

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Presence is not nullability: present empty is retained, never inferred to mean clear. */
sealed class Presence<out T> {
    object Absent : Presence<Nothing>()
    data class Present<T>(val value: T) : Presence<T>() {
        override fun toString(): String = "Present(<private>)"
    }
}
enum class KnownCallStatus { ENDED, RINGING, ACTIVE, HELD }
/** Modern/nonlegacy only; unproved values keep their raw byte. */
data class RawCallStatus(val raw: Int) {
    val known: KnownCallStatus? get() = when (raw) {
        0 -> KnownCallStatus.ENDED
        2 -> KnownCallStatus.RINGING
        4 -> KnownCallStatus.ACTIVE
        5 -> KnownCallStatus.HELD
        else -> null
    }
}
sealed interface PartialCommunicationsObservation
/** Private partial wire facts, NOT inventory admission, reconciliation, liveness or Telecom input. */
class PartialCallObservation(
    val callUuid: Presence<String> = Presence.Absent,
    val status: Presence<RawCallStatus> = Presence.Absent,
    val direction: Presence<Int> = Presence.Absent,
    val remoteId: Presence<String> = Presence.Absent,
    val displayName: Presence<String> = Presence.Absent,
    val addressBookId: Presence<String> = Presence.Absent,
    val label: Presence<String> = Presence.Absent,
    val service: Presence<Int> = Presence.Absent,
    val isConferenced: Presence<Boolean> = Presence.Absent,
    val conferenceGroup: Presence<Int> = Presence.Absent,
    val disconnectReason: Presence<Int> = Presence.Absent,
    /** Unsigned wire value only: no epoch, duration or ordering interpretation. */
    val rawStartTimestamp: Presence<ULong> = Presence.Absent,
) : PartialCommunicationsObservation

/** Minimal 0x4158 surface; other IDs are opaque skips, not inferred call facts. */
class PartialCommunicationsUpdate(
    val currentCallCountHint: Presence<Int> = Presence.Absent,
) : PartialCommunicationsObservation

/**
 * Pure dormant decoder of complete 0x4155/0x4158 parameter bodies, not outer CSM frames.
 * Source: telecom/schema-followup.md + schema-evidence-index.json, retained iOS 24A446
 * accessoryd: call serializer 0x1001208f8; communications 0x10012114c;
 * inclusive u16BE length/u16BE ID 0x100003aa4; UTF8 C string 0x10000420c;
 * U8 0x100003e80; U64BE 0x10000462c. Modern status names require explicitly
 * nonlegacy subscription; this class cannot establish subscription/session mode.
 *
 * Receiver policy, NOT native acceptance claims: bounded body/count/string bytes; reject duplicate
 * decoded fields, invalid widths/UTF8/NUL/bools. Unknown IDs (including call ID5) skip raw by length,
 * without allocation, text decoding, diagnostics or retention. All parameters count toward limits.
 * No null/clear sentinel is sourced. Present empty/false/zero and Absent stay distinct.
 * Never connected to ObservedCallInventory, expiry, authenticated dispatcher or native Telecom.
 */
class CallTlvDecoder(
    private val maxBodyBytes: Int = 8192,
    private val maxParameters: Int = 64,
    private val maxStringBytes: Int = 1024,
) {
    init {
        require(maxBodyBytes in 0..65529 && maxParameters in 0..16382 && maxStringBytes in 0..65524) {
            "invalid decoder limits"
        }
    }

    fun decode(messageId: Int, body: ByteArray): PartialCommunicationsObservation {
        require(messageId == 0x4155 || messageId == 0x4158) { "unsupported message" }
        require(body.size <= maxBodyBytes) { "body limit" }
        val strings = HashMap<Int, String>()
        val numbers = HashMap<Int, Int>()
        val seen = HashSet<Int>()
        var timestamp: Presence<ULong> = Presence.Absent
        var offset = 0
        var count = 0
        while (offset < body.size) {
            require(++count <= maxParameters) { "parameter limit" }
            require(body.size - offset >= 4) { "truncated header" }
            val length = u16(body, offset)
            require(length >= 4 && length <= body.size - offset) { "invalid inclusive length" }
            val id = u16(body, offset + 2)
            val known = if (messageId == 0x4155) id in 0..12 && id != 5 else id == 10
            if (known) {
                require(seen.add(id)) { "duplicate known field" }
                val start = offset + 4
                val size = length - 4
                if (messageId == 0x4155 && id in STRING_IDS) {
                    require(size >= 1 && size - 1 <= maxStringBytes) { "string limit" }
                    require(body[offset + length - 1] == 0.toByte()) { "invalid string terminator" }
                    require((start until offset + length - 1).none { body[it] == 0.toByte() }) { "embedded NUL" }
                    strings[id] = try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(body, start, size - 1)).toString()
                    } catch (_: CharacterCodingException) { throw IllegalArgumentException("invalid UTF-8") }
                } else if (messageId == 0x4155 && id == 12) {
                    require(size == 8) { "invalid field width" }
                    var raw = 0uL
                    for (i in start until start + 8) raw = (raw shl 8) or (body[i].toInt() and 255).toULong()
                    timestamp = Presence.Present(raw)
                } else {
                    require(size == 1) { "invalid field width" }
                    val raw = body[start].toInt() and 255
                    require(messageId != 0x4155 || id != 9 || raw in 0..1) { "invalid boolean" }
                    numbers[id] = raw
                }
            }
            offset += length
        }
        fun text(id: Int): Presence<String> = strings[id]?.let { Presence.Present(it) } ?: Presence.Absent
        fun number(id: Int): Presence<Int> = numbers[id]?.let { Presence.Present(it) } ?: Presence.Absent
        if (messageId == 0x4158) return PartialCommunicationsUpdate(number(10))
        return PartialCallObservation(
            callUuid = text(4), status = numbers[2]?.let { Presence.Present(RawCallStatus(it)) } ?: Presence.Absent,
            direction = number(3), remoteId = text(0), displayName = text(1), addressBookId = text(6), label = text(7),
            service = number(8), isConferenced = numbers[9]?.let { Presence.Present(it == 1) } ?: Presence.Absent,
            conferenceGroup = number(10), disconnectReason = number(11), rawStartTimestamp = timestamp,
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 8) or (bytes[offset + 1].toInt() and 255)

    private companion object { val STRING_IDS = setOf(0, 1, 4, 6, 7) }
}
