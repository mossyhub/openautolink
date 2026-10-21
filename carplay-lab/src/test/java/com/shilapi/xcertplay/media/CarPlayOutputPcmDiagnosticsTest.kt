package com.shilapi.xcertplay.media

import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayDisplaySelection
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import android.media.AudioTrack
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
class CarPlayOutputPcmDiagnosticsTest {
    @Test @Config(shadows=[NumericAudioTrack::class])
    fun actualSinkMeasuresConvertedChannelsAndOnlyPositiveContiguousAcceptedRanges() {
        NumericAudioTrack.reset(intArrayOf(1,2,0,1,-1))
        val lines=exerciseScriptedSink()
        val decoded=lines.single { it.contains("phase=decoded event=terminal") }
        val accepted=lines.single { it.contains("phase=accepted event=terminal") }
        assertTrue(decoded,decoded.contains("observed=2 scanned=2 tail=0"))
        assertTrue(decoded,decoded.contains("ch0Samples=2 ch0Nonzero=1 ch0Peak=32768 ch0Rms=23170 ch0Clip=1"))
        assertTrue(decoded,decoded.contains("ch1Samples=2 ch1Nonzero=1 ch1Peak=32767 ch1Rms=23170 ch1Clip=1"))
        assertTrue(accepted,accepted.contains("observed=1 scanned=1 tail=0"))
        assertTrue(accepted,accepted.contains("ch0Samples=1 ch0Nonzero=1 ch0Peak=32768 ch0Rms=32768 ch0Clip=1"))
        assertTrue(accepted,accepted.contains("ch1Samples=1 ch1Nonzero=1 ch1Peak=32767 ch1Rms=32767 ch1Clip=1"))
        assertArrayEquals("real AudioTrack receives existing BE-to-LE conversion",byteArrayOf(0,0x80.toByte(),0xff.toByte(),0x7f,0,0,0,0),NumericAudioTrack.lastBytes)
    }
    @Test @Config(shadows=[NumericAudioTrack::class])
    fun zeroWriteReturnHasNoAcceptedPcmAndIncompleteAcceptedFrameIsExplicitTail() {
        NumericAudioTrack.reset(intArrayOf(0,-1))
        val zero=exerciseScriptedSink().single { it.contains("phase=accepted event=terminal") }
        assertTrue(zero,zero.contains("observed=0 scanned=0 tail=0"))
        NumericAudioTrack.reset(intArrayOf(3,-1))
        val partial=exerciseScriptedSink().single { it.contains("phase=accepted event=terminal") }
        assertTrue(partial,partial.contains("observed=0 scanned=0 tail=3"))
    }
    private fun exerciseScriptedSink(): List<String> {
        val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val lines=CopyOnWriteArrayList<String>(); val terminal=CountDownLatch(1)
        val sink=AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),
            onStatus={ lines.add(it); if(it.contains("phase=accepted event=terminal")) terminal.countDown() })
        val id=AudioStreamId(101,"default"); val format=AudioFormat(AudioCodecKind.LPCM,8000,2,101,"default")
        try {
            sink.onAudioStarted(id,format,0)
            val body=byteArrayOf(0x80.toByte(),0,0x7f,0xff.toByte(),0,0,0,0)
            sink.onAudioRtp(id,format,ByteArray(12).apply {this[0]=0x80.toByte()}+body,0)
            assertTrue("scripted platform error terminates real output owner",terminal.await(3,TimeUnit.SECONDS))
            return lines.toList()
        } finally { sink.close() }
    }
    @Test @Config(shadows=[NumericAudioTrack::class])
    fun retiringActualOwnerRejectsLatePositivePlatformWriteAndQueuedPeriodicSnapshots() {
        NumericAudioTrack.reset(intArrayOf(8)); NumericAudioTrack.stall=true
        val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val queued=CopyOnWriteArrayList<Pair<String,()->Boolean>>(); val ended=CountDownLatch(1)
        val sink=AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),
            audioDiagnostic={line,current-> queued.add(line to current); if(line.contains("phase=accepted event=terminal")) ended.countDown(); true})
        val id=AudioStreamId(101,"default"); val format=AudioFormat(AudioCodecKind.LPCM,8000,2,101,"default")
        try {
            sink.onAudioStarted(id,format,0)
            sink.onAudioRtp(id,format,ByteArray(20).apply {this[0]=0x80.toByte();this[12]=0x7f;this[13]=0xff.toByte()},0)
            assertTrue(NumericAudioTrack.entered.await(3,TimeUnit.SECONDS))
            sink.onAudioStopped(id)
            assertTrue("old queued start receipts must be fenced",queued.none {it.second()})
            NumericAudioTrack.resume.countDown()
            assertTrue(ended.await(3,TimeUnit.SECONDS))
            val terminal=queued.single {it.first.contains("phase=accepted event=terminal")}
            assertTrue(terminal.second())
            assertTrue("write completion after retirement is not authorized numeric evidence",terminal.first.contains("observed=0 scanned=0 tail=0"))
        } finally { NumericAudioTrack.resume.countDown(); sink.close(); assertTrue(sink.awaitRetired(3000)) }
    }
    @Implements(AudioTrack::class)
    class NumericAudioTrack: ShadowAudioTrack() {
        @Implementation override fun native_write_byte(bytes: ByteArray,offset: Int,size: Int,encoding: Int,blocking: Boolean): Int {
            lastBytes=bytes.copyOf(); entered.countDown()
            if(stall) while(resume.count>0) {try {resume.await()} catch (_: InterruptedException) {}}
            return script.getOrElse(index.getAndIncrement()) {-1}
        }
        companion object {
            var script=intArrayOf(-1); val index=AtomicInteger(); var lastBytes=ByteArray(0)
            var stall=false; var entered=CountDownLatch(1); var resume=CountDownLatch(1)
            fun reset(counts: IntArray) {script=counts; index.set(0); lastBytes=ByteArray(0); stall=false;entered=CountDownLatch(1);resume=CountDownLatch(1)}
        }
    }
    @Test fun actualSinkDistinguishesPositiveWritesOfZeroPcmFromNoSamples() {
        val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val lines=CopyOnWriteArrayList<String>()
        val telemetry=com.openautolink.carplaylab.media.ReceiverTelemetry(1)
        val sink=AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),
            telemetry=telemetry,onStatus={lines.add(it)})
        val id=AudioStreamId(101,"default")
        val format=AudioFormat(AudioCodecKind.LPCM,8000,2,101,"default")
        try {
            sink.onAudioStarted(id,format,0)
            sink.onAudioRtp(id,format,ByteArray(20).apply { this[0]=0x80.toByte() },0)
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(telemetry.snapshot(System.nanoTime()).audioWrittenBytes != 8L && System.nanoTime()<deadline) Thread.sleep(5)
            assertEquals(8L,telemetry.snapshot(System.nanoTime()).audioWrittenBytes)
            sink.onAudioStopped(id)
            val terminal=lines.filter { it.startsWith("media stage=audio-pcm ") && it.contains("phase=accepted event=terminal") }
            assertEquals("actual PCM output must retain numeric accepted-zero evidence: $lines",1,terminal.size)
            assertTrue(terminal.single(),terminal.single().contains("rate=8000 channels=2 observed=2 scanned=2 tail=0"))
            assertTrue(terminal.single().contains("ch0Samples=2 ch0Nonzero=0 ch0Peak=0 ch0Rms=0 ch0Clip=0"))
        } finally { sink.close() }
    }
}
