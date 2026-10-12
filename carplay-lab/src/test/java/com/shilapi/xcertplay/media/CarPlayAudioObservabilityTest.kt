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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
class CarPlayAudioObservabilityTest {
    @Test fun pcmStartAndFinalWriteStatsAreQualifiedPerLocalOwnerNotPhoneIds() {
        val surface = mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val lines = CopyOnWriteArrayList<String>()
        val configured = CountDownLatch(1)
        val telemetry = com.openautolink.carplaylab.media.ReceiverTelemetry(1)
        val sink = AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false), telemetry=telemetry,
            onStatus={ lines.add(it); if(it.startsWith("media stage=audio-track")) configured.countDown() })
        val id = AudioStreamId(102,"media")
        val format = AudioFormat(AudioCodecKind.LPCM,48000,2,102,"media")
        try {
            sink.onAudioStarted(id,format,0)
            assertTrue("actual AudioTrack creation needs qualified receipt",configured.await(2,TimeUnit.SECONDS))
            assertTrue(lines.contains("media stage=audio-setup stream=1 type=102 audioType=media codec=LPCM rate=48000 clock=48000 channels=2 spf=0"))
            sink.onAudioRtp(id,format,ByteArray(16).apply { this[0]=0x80.toByte() },0)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (telemetry.snapshot(System.nanoTime()).audioWrittenBytes != 4L && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(4L,telemetry.snapshot(System.nanoTime()).audioWrittenBytes)
            sink.onAudioStopped(id)
            val final = lines.single { it.startsWith("media stage=audio-stats stream=1 ") && it.endsWith("ended=true") }
            assertTrue(final,final.contains("packets=1 queue=0 queueDrops=0 decoderDrops=0 written=4"))
            assertTrue(final,final.contains("underruns=")); assertTrue(final,final.contains("route="))
            assertTrue(lines.any { it.startsWith("media stage=audio-stats stream=1 type=102 audioType=media codec=LPCM") && it.endsWith("ended=true") })
            sink.onAudioStarted(id,format,0)
            sink.onAudioStopped(id)
            assertTrue(lines.any { it.startsWith("media stage=audio-setup stream=2 ") })
        } finally { sink.close() }
    }
}
