package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

class OutputPcmDiagnosticsTest {
    private val format=AudioFormat(AudioCodecKind.LPCM,8000,2,101,"default")
    @Test fun windowBudgetIsPrefixBoundedAndResetDoesNotCarryOwners() {
        var time=0L
        val lines=mutableListOf<String>()
        val d=OutputPcmDiagnostics(format,7,{line,_->lines.add(line);true},{time})
        d.configure(8000,2)
        val pcm=ByteArray(4096*4).apply {this[0]=1}
        repeat(100) { d.decoded(pcm) }
        assertEquals("no packet-proportional reports",2,lines.size)
        time=OutputPcmDiagnostics.WINDOW_NS; d.tick()
        val prefix=lines.single {it.contains("phase=decoded event=periodic")}
        assertTrue(prefix,prefix.contains("observed=409600 scanned=2048 tail=0 limit=2048 coverage=prefix"))
        assertTrue(prefix.contains("ch0Samples=2048 ch0Nonzero=1"))
        d.decoded(ByteArray(4)); d.finish()
        val final=lines.single {it.contains("phase=decoded event=terminal")}
        assertTrue(final,final.contains("window=1 rate=8000 channels=2 observed=1 scanned=1"))
        assertTrue(final.contains("ch0Samples=1 ch0Nonzero=0 ch0Peak=0 ch0Rms=0 ch0Clip=0"))
        val next=mutableListOf<String>(); val successor=OutputPcmDiagnostics(format,8,{line,_->next.add(line);true},{time})
        successor.configure(8000,2); successor.finish()
        assertTrue(next.last().contains("stream=8")); assertTrue(next.last().contains("observed=0 scanned=0"))
    }
    @Test fun rejectedQueueOffersAreTruthfulAndRetiredOwnersFenceQueuedNonterminalSnapshots() {
        var time=0L; var offers=0
        val pending=mutableListOf<Pair<String,()->Boolean>>()
        val d=OutputPcmDiagnostics(format,1,{line,current->
            offers++; if(offers<=2) false else {pending.add(line to current);true}
        },{time})
        d.configure(8000,2); d.decoded(ByteArray(4))
        time=OutputPcmDiagnostics.WINDOW_NS; d.tick()
        assertEquals(2,pending.size)
        assertTrue(pending.all {it.first.endsWith("queueDrops=2") && it.second()})
        d.revoke()
        assertTrue(pending.none {it.second()})
        d.accepted(byteArrayOf(0xff.toByte(),0x7f,0xff.toByte(),0x7f),0,4)
        d.tick(); d.finish(); d.finish()
        val finals=pending.filter {it.first.contains("event=terminal")}
        assertEquals(2,finals.size)
        assertTrue(finals.all {it.second() && it.first.contains("observed=0 scanned=0")})
        assertEquals(6,offers)
    }
    @Test fun acceptedFrameSpanningPositiveWritesCountsOnceAndTailsNeverCrossBuffers() {
        val lines=mutableListOf<String>(); val d=OutputPcmDiagnostics(format,1,{line,_->lines.add(line);true},{0})
        d.configure(8000,2)
        val pcm=byteArrayOf(0,0x80.toByte(),0xff.toByte(),0x7f,0,0,0,0)
        d.decoded(pcm); d.accepted(pcm,0,1); d.accepted(pcm,1,3); d.accepted(pcm,4,2); d.acceptedTail(pcm,6)
        d.accepted(pcm,0,2); d.acceptedTail(pcm,2)
        d.finish()
        val accepted=lines.single {it.contains("phase=accepted event=terminal")}
        assertTrue(accepted,accepted.contains("observed=1 scanned=1 tail=4"))
        assertTrue(accepted.contains("ch0Samples=1 ch0Nonzero=1 ch0Peak=32768 ch0Rms=32768 ch0Clip=1"))
    }
}
