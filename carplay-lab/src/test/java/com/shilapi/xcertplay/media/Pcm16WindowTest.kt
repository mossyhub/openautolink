package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class Pcm16WindowTest {
    @Test fun zeroMonoHasSamplesButNoSignalAndEmptyHasNoSamples() {
        val w=Pcm16Window(1)
        assertEquals(0,w.snapshot().channels.single().samples)
        w.observe(ByteArray(8))
        assertEquals(Pcm16Window.Channel(4,0,0,0,0),w.snapshot().channels.single())
    }
    @Test fun explicitByteOrderMatchesAndOffsetExcludesPrivatePrefix() {
        val le=Pcm16Window(1); val be=Pcm16Window(1)
        le.observe(byteArrayOf(99,0x34,0x12,0xcc.toByte(),0xed.toByte(),98),1,4,true)
        be.observe(byteArrayOf(0x12,0x34,0xed.toByte(),0xcc.toByte()),0,4,false)
        assertEquals(le.snapshot(),be.snapshot())
        assertEquals(Pcm16Window.Channel(2,2,4660,4660,0),le.snapshot().channels.single())
    }
    @Test fun prefixBudgetCountsCoverageAndDoesNotCarryTailBetweenCallsOrReset() {
        val w=Pcm16Window(2,2)
        w.observe(ByteArray(19))
        assertEquals(4L,w.snapshot().observedFrames)
        assertEquals(2,w.snapshot().scannedFrames)
        assertEquals(3L,w.snapshot().tailBytes)
        w.observe(byteArrayOf(0x7f))
        assertEquals(4L,w.snapshot().observedFrames)
        assertEquals(4L,w.snapshot().tailBytes)
        w.reset(); w.observe(byteArrayOf(0x7f))
        assertEquals(0,w.snapshot().scannedFrames)
        assertEquals(1L,w.snapshot().tailBytes)
        assertEquals(0,w.snapshot().channels[0].peak)
    }
    @Test fun signedStereoDoesNotCancelAndMinValueDoesNotOverflow() {
        val type = runCatching { Class.forName("com.shilapi.xcertplay.media.Pcm16Window") }.getOrNull()
        assertNotNull("bounded numeric PCM aggregator must exist", type)
        val window = type!!.getConstructor(Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).newInstance(2,2048)
        type.getMethod("observe",ByteArray::class.java,Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Boolean::class.javaPrimitiveType)
            .invoke(window,byteArrayOf(0,0x80.toByte(),0xff.toByte(),0x7f,0,0,0,0),0,8,true)
        val snapshot = type.getMethod("snapshot").invoke(window)
        val channels = snapshot.javaClass.getMethod("getChannels").invoke(snapshot) as List<*>
        fun value(ch: Int, field: String) = channels[ch]!!.javaClass.getMethod("get$field").invoke(channels[ch])
        assertEquals(32768,value(0,"Peak")); assertEquals(32767,value(1,"Peak"))
        assertEquals(23170,value(0,"Rms")); assertEquals(23170,value(1,"Rms"))
        assertEquals(1,value(0,"Clipped")); assertEquals(1,value(1,"Clipped"))
    }
}
