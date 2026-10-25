package com.openautolink.carplaylab.telecom

import org.junit.Assert.*
import org.junit.Test

/** Synthetic ENCODING vectors constructed from sourced serializer schema; NOT captured packets.
 * Strings are neutral encoding probes, never phone/caller fixtures. */
class CallTlvDecoderTest {
    private fun tlv(id: Int, vararg value: Int): ByteArray {
        val n = value.size + 4
        return byteArrayOf((n ushr 8).toByte(), n.toByte(), (id ushr 8).toByte(), id.toByte()) +
            value.map { it.toByte() }.toByteArray()
    }

    private fun rejects(body: ByteArray, message: Int = 0x4155) {
        try { CallTlvDecoder().decode(message, body); fail("accepted malformed encoding") }
        catch (_: IllegalArgumentException) { /* bounded policy rejection */ }
    }

    @Test fun rejectsUnsupportedMessage() { rejects(byteArrayOf(), 0x4154) }
    @Test fun rejectsTruncatedHeaders() { for (n in 1..3) rejects(ByteArray(n)) }
    @Test(timeout = 2000) fun rejectsNoninclusiveAndTruncatedLengths() {
        for (n in 0..3) rejects(byteArrayOf(0, n.toByte(), 0, 4))
        rejects(byteArrayOf(0, 6, 0, 2, 4))
    }
    @Test fun rejectsWrongScalarWidths() {
        rejects(tlv(2)); rejects(tlv(2, 4, 5)); rejects(tlv(3)); rejects(tlv(12, 0))
    }
    @Test fun rejectsDuplicateKnownFields() { rejects(tlv(2, 4) + tlv(2, 5)) }
    @Test fun rejectsMalformedUtf8AndNuls() {
        for (value in listOf(intArrayOf(), intArrayOf(120), intArrayOf(0, 120, 0),
            intArrayOf(0xc0, 0x80, 0), intArrayOf(0xed, 0xa0, 0x80, 0), intArrayOf(0xe2, 0x82, 0))) {
            rejects(tlv(4, *value))
        }
    }

    private fun call(vararg fields: ByteArray) = CallTlvDecoder().decode(0x4155,
        fields.fold(byteArrayOf()) { a, b -> a + b }) as PartialCallObservation

    @Test fun preservesPresentEmptyAndAbsentStrings() {
        val c = call(tlv(0, 0), tlv(1, 0x61, 0), tlv(6, 0), tlv(7, 0), tlv(4, 0))
        assertEquals(Presence.Present(""), c.remoteId)
        assertEquals(Presence.Present("a"), c.displayName)
        assertEquals(Presence.Present(""), c.addressBookId)
        assertEquals(Presence.Present(""), c.label)
        assertEquals(Presence.Present(""), c.callUuid)
        assertEquals(Presence.Absent, call().remoteId)
    }
    @Test fun preservesRawEnumsAndOnlySourcedStatusNames() {
        val names = mapOf(0 to KnownCallStatus.ENDED, 2 to KnownCallStatus.RINGING,
            4 to KnownCallStatus.ACTIVE, 5 to KnownCallStatus.HELD)
        for (n in 0..255) {
            val c = call(tlv(2, n), tlv(3, n), tlv(8, n), tlv(11, n))
            assertEquals(names[n], (c.status as Presence.Present).value.known)
            assertEquals(Presence.Present(n), c.direction)
            assertEquals(Presence.Present(n), c.service)
            assertEquals(Presence.Present(n), c.disconnectReason)
        }
    }
    @Test fun conferenceAbsenceDoesNotClearPriorObservation() {
        val first = call(tlv(9, 1), tlv(10, 255))
        val partial = call(tlv(2, 5))
        assertEquals(Presence.Present(true), first.isConferenced)
        assertEquals(Presence.Present(255), first.conferenceGroup)
        assertEquals(Presence.Absent, partial.isConferenced)
        assertEquals(Presence.Absent, partial.conferenceGroup)
        assertEquals(Presence.Present(false), call(tlv(9, 0)).isConferenced)
        rejects(tlv(9, 2))
    }
    @Test fun timestampIsRawUnsignedBigEndianIncludingMaximum() {
        assertEquals(Presence.Present(ULong.MAX_VALUE), call(tlv(12, *IntArray(8) { 255 })).rawStartTimestamp)
        assertEquals(Presence.Present(0x0102030405060708uL), call(tlv(12, 1,2,3,4,5,6,7,8)).rawStartTimestamp)
        assertEquals(Presence.Present(0uL), call(tlv(12, *IntArray(8))).rawStartTimestamp)
    }
    @Test fun countIsPartialHintNotInventoryOrEnd() {
        val c = CallTlvDecoder().decode(0x4158, tlv(10, 0)) as PartialCommunicationsUpdate
        assertEquals(Presence.Present(0), c.currentCallCountHint)
        assertEquals(Presence.Absent, (CallTlvDecoder().decode(0x4158, byteArrayOf()) as PartialCommunicationsUpdate).currentCallCountHint)
        val prior = call(tlv(4, 0x78, 0), tlv(2, 4))
        assertEquals(Presence.Present(RawCallStatus(4)), prior.status) // mismatch has no reconciliation side effect
        rejects(tlv(10), 0x4158); rejects(tlv(10,1)+tlv(10,2), 0x4158)
    }
    @Test fun unknownIdsAreOpaqueSkippedAndDoNotAcquireKnownMeaning() {
        assertEquals(Presence.Present("x"), call(tlv(5,255), tlv(65000,0), tlv(65000), tlv(4,120,0)).callUuid)
    }
    @Test fun boundedBodyParameterCountAndUtf8ByteCount() {
        fun denied(d: CallTlvDecoder, b: ByteArray) {
            try { d.decode(0x4155,b); fail("limit ignored") } catch (_: IllegalArgumentException) {}
        }
        denied(CallTlvDecoder(maxBodyBytes=4),tlv(2,4))
        denied(CallTlvDecoder(maxParameters=1),tlv(65000)+tlv(65000))
        denied(CallTlvDecoder(maxStringBytes=1),tlv(4,0xc3,0xa9,0))
        val c = CallTlvDecoder(maxBodyBytes=7,maxParameters=1,maxStringBytes=2)
            .decode(0x4155,tlv(4,0xc3,0xa9,0)) as PartialCallObservation
        assertEquals(Presence.Present("é"), c.callUuid)
    }
    @Test fun defaultRepresentationsDoNotExportPrivateIdentity() {
        val c = call(tlv(4,120,0))
        assertFalse(c.toString().contains("callUuid"))
        assertFalse(c.callUuid.toString().contains("x"))
    }

    @Test fun allDecodedFieldsRejectDuplicateAndWrongWidths() {
        for (id in listOf(0,1,4,6,7)) rejects(tlv(id,0)+tlv(id,0))
        for (id in listOf(2,3,8,9,10,11)) {
            rejects(tlv(id)); rejects(tlv(id,0,0)); rejects(tlv(id,0)+tlv(id,0))
        }
        for (n in listOf(0,1,7,9)) rejects(tlv(12,*IntArray(n)))
        rejects(tlv(12,*IntArray(8))+tlv(12,*IntArray(8)))
    }
    @Test fun unknownSkipStillChecksFramingAndKnownMessageSpecificIds() {
        rejects(byteArrayOf(0,5,0xff.toByte(),0xff.toByte()))
        val hint = CallTlvDecoder().decode(0x4158,tlv(4,255)+tlv(4)+tlv(10,255)) as PartialCommunicationsUpdate
        assertEquals(Presence.Present(255),hint.currentCallCountHint)
    }
    @Test fun defaultLimitsAreFiniteAndBoundaryIsInclusive() {
        val maxString = tlv(4,*IntArray(1024) { 120 },0)
        assertEquals(1024,(call(maxString).callUuid as Presence.Present).value.length)
        rejects(tlv(4,*IntArray(1025) { 120 },0))
        rejects(ByteArray(8193))
        rejects((0..64).fold(byteArrayOf()) { a,_ -> a+tlv(65000) })
        call((0 until 64).fold(byteArrayOf()) { a,_ -> a+tlv(65000) })
    }

    @Test fun sourcedInclusiveFramingDecodesPartialCall() {
        val result = CallTlvDecoder().decode(0x4155, tlv(4, 0x78, 0) + tlv(2, 4)) as PartialCallObservation
        assertEquals(Presence.Present("x"), result.callUuid)
        assertEquals(Presence.Present(RawCallStatus(4)), result.status)
        assertEquals(Presence.Absent, result.direction)
    }
}
