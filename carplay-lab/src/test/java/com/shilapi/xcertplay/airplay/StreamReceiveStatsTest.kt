package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class StreamReceiveStatsTest {
    @Test fun exportsWindowGapsDuplicatesWrapAndTimingWithoutSequenceIdentifiers() {
        var now = 1L
        val lines = mutableListOf<String>()
        val stats = StreamReceiveStats("audio type=102", lines::add) { now }
        stats.reading(); now += 300_000_000; stats.received(10,65535,10); stats.processed()
        stats.reading(); now += 310_000_000; stats.received(20,2,20); stats.processed()
        stats.reading(); now += 1_000_000; stats.received(30,2,20); stats.processed()
        assertTrue(lines.isEmpty())
        stats.flush(true)
        assertEquals("media stage=receive kind=audio type=102 packets=3 bytes=60 missing=2 gaps=1 late=1 maxGap=2 readMs=310 arrivalMs=310 processMs=0 longReads=2 ended=true", lines.single())
        stats.flush(true)
        assertTrue(lines.last().contains("packets=0 bytes=0 missing=0 gaps=0 late=0"))
    }
    @Test fun unknownLabelsNeverLeakAndVideoUsesFiniteType() {
        val lines = mutableListOf<String>()
        StreamReceiveStats("private route", lines::add).flush(true)
        assertTrue(lines.isEmpty())
        StreamReceiveStats("video", lines::add).flush(true)
        assertTrue(lines.single().startsWith("media stage=receive kind=video type=110"))
    }
}
