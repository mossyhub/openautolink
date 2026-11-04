package com.openautolink.app.transport.aasdk

import com.openautolink.app.cluster.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test

class AasdkClusterVideoStreamTest {
    private fun offer(): AasdkClusterVideoOffer {
        val display = requireNotNull(ClusterDisplayProfiles.qualify(
            ClusterVehicleIdentity("aegean_orange", 32, "VCUSR5-163", 239),
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
            ClusterHostGeometry(7, PixelSize(2000, 810), PixelRect(0, 135, 2000, 810), PixelRect(0, 135, 2000, 810)),
        ))
        return AasdkClusterVideoOffer.fromMapping(ClusterVideoGeometry.androidAuto(display))
    }
    @Test fun offerCopiesQualifiedDrawableInsetsWithoutAddingCodecPadding() {
        val v=offer()
        assertEquals(144,v.cropTop); assertEquals(576,v.cropBottom)
        assertEquals(22,v.safeTop); assertEquals(372,v.safeLeft); assertEquals(v.safeTop,v.stableTop)
        assertEquals(3,AasdkClusterVideoOffer.CHANNEL_ID); assertEquals(1,AasdkClusterVideoOffer.PROTOCOL_DISPLAY_ID)
        try { AasdkClusterVideoOffer.fromMapping(v.mapping,0);fail("zero density") } catch (_: IllegalArgumentException) { }
        try { AasdkClusterVideoOffer.fromMapping(ClusterVideoGeometry.carPlay(v.mapping.qualifiedDisplay));fail("CarPlay offer") } catch (_: IllegalArgumentException) { }
    }
    @Test fun queueFencesReplacementStopAndOldSessionCallbacks() = runBlocking {
        val s=AasdkClusterVideoStream();val owner=s.begin(offer())
        s.onState(owner,1,3,0,71,true)
        val first=requireNotNull(s.selection.value)
        val seen=mutableListOf<AasdkClusterVideoFrame>()
        val job=launch(start=CoroutineStart.UNDISPATCHED){s.frames.collect{seen+=it}}
        s.onFrame(owner,byteArrayOf(1),2,1,1);yield()
        assertEquals(1,seen.size);assertTrue(s.isCurrent(seen.single()))
        s.onState(owner,2,3,-1,0,false);assertNull(s.selection.value);assertFalse(s.isCurrent(seen.single()))
        s.onFrame(owner,byteArrayOf(2),3,1,1)
        s.onState(owner,3,3,0,72,true);assertNotSame(first,s.selection.value)
        s.onFrame(owner,byteArrayOf(3),4,3,1)
        s.onState(owner,4,3,0,73,true);yield() // replacement before queued delivery
        assertEquals(1,seen.size)
        val next=s.begin(offer());s.onState(next,1,3,0,74,true)
        s.onState(owner,100,3,0,99,true);s.onFrame(owner,byteArrayOf(4),5,100,1);s.retire(owner)
        assertEquals(74,s.selection.value?.mediaSessionId)
        s.onFrame(next,byteArrayOf(5),6,1,1);yield();assertEquals(2,seen.size)
        s.retire();s.onState(next,2,3,0,75,true)
        assertNull(s.selection.value);assertFalse(s.isCurrent(seen.last()));job.cancelAndJoin()
    }
    @Test fun boundedQueueNeverWaitsForSlowConsumer() = runBlocking {
        val s=AasdkClusterVideoStream();val owner=s.begin(offer());s.onState(owner,1,3,0,1,true)
        val seen=mutableListOf<Long>();val gate=CompletableDeferred<Unit>()
        val job=launch(start=CoroutineStart.UNDISPATCHED){s.frames.collect{seen+=it.timestampUs;if(seen.size==1)gate.await()}}
        s.onFrame(owner,byteArrayOf(1),0,1,1);yield()
        repeat(20){s.onFrame(owner,byteArrayOf(1),(it+1).toLong(),1,1)}
        gate.complete(Unit);yield();assertEquals(listOf(0L,17L,18L,19L,20L),seen)
        s.onFrame(owner,ByteArray(AasdkClusterVideoStream.MAX_FRAME_BYTES+1),21,1,1)
        assertNull(s.selection.value);job.cancelAndJoin()
    }
    @Test fun omittedOfferAndInvalidSelectionsDoNotAdmitFrames() {
        val s=AasdkClusterVideoStream();var owner=s.begin(null)
        s.onState(owner,1,3,0,1,true);assertNull(s.selection.value)
        owner=s.begin(offer());s.onState(owner,1,7,0,1,true);assertNull(s.selection.value)
        s.onState(owner,2,3,1,1,true);assertNull(s.selection.value)
        s.onState(owner,3,3,0,1,true);val selected=s.selection.value
        s.onState(owner,2,3,-1,0,false);assertSame(selected,s.selection.value)
        s.onState(owner,3,3,-1,0,false);assertSame(selected,s.selection.value)
    }
    @Test fun jniDescriptorsKeepMainAndClusterCallbacksSeparate() {
        val c=AasdkSessionCallback::class.java
        assertEquals(Void.TYPE,c.getMethod("onVideoFrame",ByteArray::class.java,Long::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).returnType)
        assertEquals(Void.TYPE,c.getMethod("onClusterVideoFrame",ByteArray::class.java,Long::class.javaPrimitiveType,Long::class.javaPrimitiveType,Int::class.javaPrimitiveType).returnType)
        assertEquals(Void.TYPE,c.getMethod("onClusterVideoState",Long::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType).returnType)
        assertEquals(AasdkClusterVideoOffer::class.java,AasdkSdrConfig::class.java.getField("clusterOffer").type)
        for(f in listOf("density","cropLeft","cropTop","cropRight","cropBottom","safeLeft","safeTop","safeRight","safeBottom","stableLeft","stableTop","stableRight","stableBottom"))assertEquals(Integer.TYPE,AasdkClusterVideoOffer::class.java.getField(f).type)
    }
}
