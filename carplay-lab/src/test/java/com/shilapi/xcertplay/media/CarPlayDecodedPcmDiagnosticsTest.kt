package com.shilapi.xcertplay.media

import android.media.*
import android.view.Surface
import com.openautolink.carplaylab.media.*
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE,
    instrumentedPackages=["com.shilapi.xcertplay.media"],
    shadows=[CarPlayDecodedPcmDiagnosticsTest.DecodedTrack::class])
class CarPlayDecodedPcmDiagnosticsTest {
    // instrumentedPackages keys a separate SDK sandbox: shadows alone do not isolate the
    // process-lifetime decoder inventory from earlier receiver tests with no audio codecs.
    @Implements(AudioTrack::class)
    class DecodedTrack: ShadowAudioTrack()
    @After fun resetCodecFixturesAfterSinksClose() {
        ShadowMediaCodec.clearCodecs()
        ShadowMediaCodecList.reset()
    }
    @Test fun opusAndAacMeasureDecoderPcmNotCompressedInput() {
        val cases=listOf(0x40000000L to 100,0x800000L to 102)
        for ((bits,type) in cases) {
            val format=AudioOutputSupport.format(bits,type)!!
            val config=AudioOutputSupport.configuration(format)!!
            val name="fixture.${format.codec}.decoder"
            val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createAudioFormat(config.mime,48000,format.channels))
                .setIsEncoder(false).setProfileLevels(if(config.aacProfile==null) emptyArray() else arrayOf(
                    MediaCodecInfo.CodecProfileLevel().apply {profile=config.aacProfile;level=0})).build()
            ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(false).setCapabilities(caps).build())
            ShadowMediaCodec.addDecoder(name,ShadowMediaCodec.CodecConfig(4096,4096) { input,output ->
                input.position(input.limit())
                output.put(byteArrayOf(0,0x80.toByte()))
                if(format.channels==2) output.put(byteArrayOf(0xff.toByte(),0x7f))
            })
        }
        for ((bits,type) in cases) {
            val format=AudioOutputSupport.format(bits,type)!!
            val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
            val lines=CopyOnWriteArrayList<String>(); val telemetry=ReceiverTelemetry(1)
            val sink=AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
                display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),telemetry=telemetry,onStatus=lines::add)
            val id=AudioStreamId(type,"media")
            try {
                assertTrue("fixture has qualified exact decoder",sink.supportsAudio(format))
                assertEquals("fixture.${format.codec}.decoder",AndroidAudioDecoderSupport.decoderName(format))
                sink.onAudioStarted(id,format,0)
                sink.onAudioRtp(id,format,ByteArray(12).apply {this[0]=0x80.toByte()}+byteArrayOf(0x55,0x66),0)
                val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
                while((telemetry.snapshot(System.nanoTime()).audioWrittenBytes ?: 0L)<format.channels*2 && System.nanoTime()<deadline) Thread.sleep(5)
                assertEquals(lines.toString(),(format.channels*2).toLong(),telemetry.snapshot(System.nanoTime()).audioWrittenBytes)
                sink.onAudioStopped(id)
                val decoded=lines.single {it.contains("phase=decoded event=terminal")}
                assertTrue(decoded,decoded.contains("codec=${format.codec}"))
                assertTrue(decoded,decoded.contains("observed=1 scanned=1"))
                assertTrue(decoded,decoded.contains("ch0Samples=1 ch0Nonzero=1 ch0Peak=32768 ch0Rms=32768 ch0Clip=1"))
                if(format.channels==2) assertTrue(decoded.contains("ch1Samples=1 ch1Nonzero=1 ch1Peak=32767 ch1Rms=32767 ch1Clip=1"))
                println("qualifiedDecoder=${AndroidAudioDecoderSupport.decoderName(format)} $decoded")
            } finally {sink.close();assertTrue(sink.awaitRetired(3000))}
        }
    }
}
