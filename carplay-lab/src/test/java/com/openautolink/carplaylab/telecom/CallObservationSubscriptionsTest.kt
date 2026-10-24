package com.openautolink.carplaylab.telecom

import org.junit.Assert.*
import org.junit.Test

/** Synthetic encoding expectations from sourced void-parameter subscription schema, NOT captures. */
class CallObservationSubscriptionsTest {
    private fun expected(message: Int, ids: List<Int>): ByteArray {
        val size = 6 + ids.size * 4
        return byteArrayOf(0x40,0x40,(size ushr 8).toByte(),size.toByte(),
            (message ushr 8).toByte(),message.toByte()) + ids.flatMap {
                listOf<Byte>(0,4,(it ushr 8).toByte(),it.toByte())
            }.toByteArray()
    }
    @Test fun explicitCallRequestsSelectNonlegacyAndMatchActualMessageEncoder() {
        val frame = CallObservationSubscriptions.nonlegacyCalls()
        assertArrayEquals(expected(0x4154,listOf(0,1,2,3,4,6,7,8,9,10,11,12)),frame.encodedFrame())
        assertFalse(frame.body().has(5))
        assertEquals(12,frame.body().size)
    }
    @Test fun countSubscriptionRequestsOnlySourcedHintUsingActualEncoder() {
        val frame = CallObservationSubscriptions.callCountHint()
        assertArrayEquals(expected(0x4157,listOf(10)),frame.encodedFrame())
    }
}
