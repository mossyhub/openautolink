package com.openautolink.app.transport.aasdk

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class AasdkSecondaryVideoTest {
    private fun aux() = AasdkSecondaryVideoOffer.create(19, 2, 2)
    @Test fun completeSetRejectsReservedDuplicateAndOversizedOffers() {
        val gm = AasdkSecondaryVideoOffer.create(3, 1, 1)
        assertEquals(2, AasdkSecondaryVideoOffer.validated(listOf(gm, aux())).size)
        assertTrue(AasdkSecondaryVideoOffer.validated(listOf(gm, gm)).isEmpty())
        assertTrue(AasdkSecondaryVideoOffer.validated(List(9) { aux() }).isEmpty())
        for (channel in (0..18).filter { it != 3 } + 255) {
            assertThrows(IllegalArgumentException::class.java) { AasdkSecondaryVideoOffer.create(channel, 2, 2) }
        }
        assertThrows(IllegalArgumentException::class.java) { AasdkSecondaryVideoOffer.create(19, 1, 2) }
        assertEquals(2, AasdkSecondaryVideoOffer.create(3, 1, 2).displayType)
        assertThrows(IllegalArgumentException::class.java) { AasdkSecondaryVideoOffer.create(19, 2, 0) }
        assertThrows(IllegalArgumentException::class.java) { AasdkSecondaryVideoOffer.create(19, 2, 2, cropLeft=1) }
    }
    @Test fun boundedQueuesDoNotReplayAndSelectionObserversRunOutsideOwnershipLock() = kotlinx.coroutines.runBlocking {
        val streams=AasdkSecondaryVideoStreams(listOf(AasdkSecondaryVideoOffer.create(3,1,1),aux()))
        val owner=streams.begin()
        val lockField=streams.javaClass.getDeclaredField("lock").apply { isAccessible=true }
        val lock=lockField.get(streams)
        var observed=0
        val observer=launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            streams.selections.collect { assertFalse(Thread.holdsLock(lock)); observed++ }
        }
        streams.onState(owner,3,1,3,0,10,true)
        streams.onState(owner,19,1,3,0,20,true)
        assertTrue(observed>=3)
        val held=kotlinx.coroutines.CompletableDeferred<Unit>()
        val entered=kotlinx.coroutines.CompletableDeferred<Unit>()
        val auxReceived=kotlinx.coroutines.CompletableDeferred<AasdkSecondaryVideoFrame>()
        var mainCount=0
        val collector=launch {
            streams.frames.collect { frame ->
                assertTrue(streams.isCurrent(frame))
                if(frame.selection.channelId==3){mainCount++;if(mainCount==1){entered.complete(Unit);held.await()}}
                else auxReceived.complete(frame)
            }
        }
        repeat(20){kotlinx.coroutines.yield()}
        streams.onFrame(owner,3,byteArrayOf(1),1,1,0)
        kotlinx.coroutines.withTimeout(1000){entered.await()}
        repeat(100){streams.onFrame(owner,3,byteArrayOf(1),it.toLong(),1,0)}
        streams.onFrame(owner,19,byteArrayOf(9),99,1,0)
        held.complete(Unit)
        val frame=kotlinx.coroutines.withTimeout(1000){auxReceived.await()}
        assertEquals(19,frame.selection.channelId)
        repeat(20){kotlinx.coroutines.yield()}
        assertTrue("main ingress must remain bounded: $mainCount",mainCount<=6)
        streams.begin()
        assertFalse(streams.isCurrent(frame))
        collector.cancel();observer.cancel()
    }
    @Test fun independentChannelsAndRestartsFenceLateCallbacksAndRetirement() {
        val offers = listOf(AasdkSecondaryVideoOffer.create(3, 1, 1), aux())
        val streams = AasdkSecondaryVideoStreams(offers)
        val first = streams.begin()
        streams.onState(first, 3, 2, 3, 0, 40, true)
        streams.onState(first, 19, 7, 3, 0, 80, true)
        val old = streams.selections.value.getValue(19)
        assertEquals(setOf(3,19), streams.selections.value.keys)
        streams.retire(3)
        assertSame(old, streams.selections.value[19])
        val second = streams.begin()
        streams.onState(second, 19, 1, 3, 0, 90, true)
        streams.retire(19, first)
        streams.onState(first, 19, 200, 3, 0, 1, true)
        assertEquals(90, streams.selections.value.getValue(19).mediaSessionId)
        assertFalse(streams.isCurrent(AasdkSecondaryVideoFrame(old, byteArrayOf(1), 1, 0)))
        streams.onFrame(second,19,ByteArray(2*1024*1024+1),1,1,0)
        assertTrue(streams.selections.value.isEmpty())
    }
}
