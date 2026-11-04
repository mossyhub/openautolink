package com.openautolink.app.diagnostics.live

import org.junit.Assert.*
import org.junit.Test

class LiveBufferTest {
    @Test fun boundedOfflineBacklogHasSequenceGapAndPreservesMultiline() {
        val q = LiveBuffer(3, 4096)
        repeat(5) { q.offer(10L+it, it.toLong(), "INFO", "BENCH", "bench $it\nsecond", "diagnostic", 0) }
        val batch=q.batch(64)
        assertEquals(listOf(3L,4L,5L),batch.map { it.seq })
        assertEquals(2L,q.dropped)
        assertEquals("bench 2\nsecond",batch.first().message)
        q.acknowledge(4)
        assertEquals(listOf(5L),q.batch(64).map { it.seq })
    }
    @Test fun redactsCredentialsCoordinatesAndMultilineSensitiveDumps() {
        val secret="aBcd0123_aBcd0123-aBcd0123_aBcd0123-aBcd01234"
        val line="Authorization: Bearer $secret\npassword=mywifi\nlatitude=34.567 longitude=-118.234\nnormal line"
        val safe=LiveRedaction.clean(line)
        assertFalse(safe.contains(secret)); assertFalse(safe.contains("mywifi"))
        assertFalse(safe.contains("34.567")); assertFalse(safe.contains("118.234"))
        assertTrue(safe.contains("normal line"))
        assertEquals("OPTIONAL_VIDEO_V1 session=1 decoded=12 presented=10",LiveRedaction.clean("OPTIONAL_VIDEO_V1 session=1 decoded=12 presented=10"))
    }
    @Test fun technicalGeometryAndNumericSurfaceIdentityRemainExact() {
        val message="OPTIONAL_VISIBILITY_V1 surfaceToken=12345678 left=98.0954 right=1903.9046 oemVisibility=unknown"
        assertEquals(message,LiveRedaction.clean(message))
        assertFalse(LiveRedaction.clean("VIN=1G1FY6S00K1234567").contains("1G1FY6S00K1234567"))
        assertFalse(LiveRedaction.clean("-> varint: 375445678").contains("375445678"))
    }
    @Test fun sharedProcessSequenceDoesNotResetWhenEnabledAgain() {
        val seq=java.util.concurrent.atomic.AtomicLong()
        val a=LiveBuffer(sequence=seq);a.offer(1,1,"INFO","BENCH","one","diagnostic",0)
        val b=LiveBuffer(sequence=seq);b.offer(2,2,"INFO","BENCH","two","diagnostic",0)
        assertEquals(2L,b.batch(64).single().seq)
    }
    @Test fun backlogByteLimitCountsOversizedAndEvictedRecords() {
        val q=LiveBuffer(100,256)
        q.offer(1,1,"INFO","BENCH","x".repeat(400),"diagnostic",0)
        assertEquals(1L,q.dropped)
        assertTrue(q.batch(64).isEmpty())
    }
}
