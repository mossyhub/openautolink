package com.shilapi.xcertplay.media

import android.media.AudioTrack
import android.view.Surface
import com.openautolink.carplaylab.media.*
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowAudioTrack
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises the real output owner's volume application against platform return codes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE, shadows=[AudioVolumeDedupTest.VolumeTrack::class])
class AudioVolumeDedupTest {
    private fun withOwner(test: (Any, AudioTrack) -> Unit) {
        VolumeTrack.calls.clear(); VolumeTrack.result=AudioTrack.SUCCESS; VolumeTrack.during=null
        VolumeTrack.writeEntered=CountDownLatch(1); VolumeTrack.releaseWrite=CountDownLatch(1)
        val sink=AndroidMediaSink(RuntimeEnvironment.getApplication(),mock(Surface::class.java),
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),telemetry=ReceiverTelemetry(1))
        try {
            val format=AudioOutputSupport.negotiate(0x8000L,100,"media")!!
            sink.onAudioStarted(AudioStreamId(100,"media"),format,0)
            val owners=AndroidMediaSink::class.java.getDeclaredField("tracks").also { it.isAccessible=true }.get(sink) as Map<*,*>
            val owner=owners.values.single()!!
            val field=owner.javaClass.getDeclaredField("track").also { it.isAccessible=true }
            // writePcm calls applyVolume before entering this platform-write shadow.
            // Its latch publishes the completed cache writes; parking the worker also
            // prevents concurrent cache access during the reflective assertions below.
            sink.onAudioRtp(AudioStreamId(100,"media"),format,
                ByteArray(3852).also { it[0]=0x80.toByte() },0)
            assertTrue("worker completed applyVolume before write",VolumeTrack.writeEntered.await(3,TimeUnit.SECONDS))
            assertEquals("initial volume applied",1,VolumeTrack.calls.size)
            test(owner,field.get(owner) as AudioTrack)
        } finally {
            VolumeTrack.releaseWrite.countDown()
            sink.close(); assertTrue(sink.awaitRetired(3000))
        }
    }
    private fun apply(owner: Any, track: AudioTrack) {
        owner.javaClass.getDeclaredMethod("applyVolume",AudioTrack::class.java).also { it.isAccessible=true }.invoke(owner,track)
    }
    private fun volume(owner: Any, value: Float, revision: Long=2) {
        owner.javaClass.getDeclaredMethod("volume",Float::class.javaPrimitiveType,String::class.java,Long::class.javaPrimitiveType)
            .also { it.isAccessible=true }.invoke(owner,value,if(value==0f) "transient" else "unmuted",revision)
    }
    @Test fun unchangedValueIsSkippedButDesiredChangesAndRestoreApply() = withOwner { owner, track ->
        apply(owner,track); assertEquals(1,VolumeTrack.calls.size)
        volume(owner,0f); apply(owner,track); apply(owner,track)
        volume(owner,1f,3); apply(owner,track)
        assertEquals(listOf(1f,0f,1f),VolumeTrack.calls.map { it.second })
    }
    @Test fun replacementPlatformTrackReceivesUnchangedVolume() = withOwner { owner, track ->
        val replacement=AudioTrack.Builder().build()
        try {
            apply(owner,replacement); apply(owner,replacement)
            assertEquals(2,VolumeTrack.calls.size)
            assertSame(track,VolumeTrack.calls[0].first)
            assertSame(replacement,VolumeTrack.calls[1].first)
        } finally { replacement.release() }
    }
    @Test fun failedReturnDoesNotMarkDesiredVolumeApplied() = withOwner { owner, track ->
        volume(owner,0.2f)
        VolumeTrack.result=AudioTrack.ERROR_INVALID_OPERATION
        apply(owner,track)
        VolumeTrack.result=AudioTrack.SUCCESS
        apply(owner,track); apply(owner,track)
        assertEquals(listOf(1f,0.2f,0.2f),VolumeTrack.calls.map { it.second })
    }
    @Test fun focusChangeDuringPlatformCallRemainsPending() = withOwner { owner, track ->
        volume(owner,0.2f)
        VolumeTrack.during={ volume(owner,0f,3) }
        apply(owner,track); apply(owner,track); apply(owner,track)
        assertEquals(listOf(1f,0.2f,0f),VolumeTrack.calls.map { it.second })
    }
    @Test fun sameValueFocusRevisionDoesNotSuppressLaterMute() = withOwner { owner, track ->
        volume(owner,1f,2); apply(owner,track)
        volume(owner,0f,3); apply(owner,track)
        assertEquals(listOf(1f,0f),VolumeTrack.calls.map { it.second })
    }
    @Implements(AudioTrack::class)
    class VolumeTrack: ShadowAudioTrack() {
        @RealObject lateinit var real: AudioTrack
        @Implementation protected fun setVolume(value: Float): Int {
            val returned=result
            calls.add(real to value)
            val action=during; during=null; action?.invoke()
            return returned
        }
        @Implementation override fun native_write_byte(bytes: ByteArray,offset: Int,size: Int,encoding: Int,blocking: Boolean): Int {
            writeEntered.countDown()
            releaseWrite.await()
            return size
        }
        companion object {
            lateinit var writeEntered: CountDownLatch
            lateinit var releaseWrite: CountDownLatch
            val calls=CopyOnWriteArrayList<Pair<AudioTrack,Float>>()
            @Volatile var result=AudioTrack.SUCCESS
            @Volatile var during: (() -> Unit)?=null
        }
    }
}
