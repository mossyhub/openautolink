package com.shilapi.xcertplay.media

import android.Manifest
import android.media.*
import android.view.Surface
import com.openautolink.carplaylab.media.*
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers
import java.net.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE)
class MicrophoneCaptureEvidenceTest {
    @Test fun siriCaptureReportsOnlyAggregateZeroVersusNonzeroFramesNotAudioOrPeerIdentity() {
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        val cap=AndroidMicrophoneSupport.probe(app)
        val reads=AtomicInteger();val lines=CopyOnWriteArrayList<String>()
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(b: ByteArray,o: Int,n: Int,blocking: Boolean): Int {
                Thread.sleep(20)
                val frame=reads.incrementAndGet();if(frame>2) return 0
                b.fill(0,o,o+n);if(frame==2) b[o]=1
                return n
            }
        })
        val surface=mock(Surface::class.java);`when`(surface.isValid).thenReturn(true)
        val sink=AndroidMediaSink(app,surface,display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),onStatus=lines::add)
        val session=AirPlaySession(Socket(),AirPlayConfig("test","id","mac","950.7.1",AirPlayDisplayConfig(800,480)),
            AirPlayIdentity.generate(),PairingStore(),null,object : AirPlaySessionListener {},object : AirPlayMediaHandler {})
        try {
            DatagramSocket(0,InetAddress.getLoopbackAddress()).use { peer ->
                peer.soTimeout=3000
                val config=MicrophoneSupport.negotiate(cap,100,"speechRecognition",0x40L,peer.localPort,480,peer.localAddress,ByteArray(32))!!
                sink.onMicrophoneStarted(MicrophoneStreamId(session,100,config.audioType,1),config)
                repeat(2) { peer.receive(DatagramPacket(ByteArray(4096),4096)) }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
                while (lines.none { it.contains("microphone-policy") } && System.nanoTime() < deadline) Thread.sleep(5)
                sink.close();assertTrue(sink.awaitRetired(3000))
                assertTrue("successful datagrams cannot distinguish silent capture without aggregate PCM receipts",
                    lines.any { it=="media stage=microphone-capture type=100 audioType=speechrecognition frames=2 nonzeroFrames=1 state=1 ended=true" })
                assertTrue("exact recorder input policy must be observable without dumping identities",
                    lines.any { it.startsWith("media stage=microphone-policy scope=own source=VOICE_RECOGNITION rate=24000 permission=granted ") })
                assertTrue(lines.filter { it.contains("microphone-capture") }.all {
                    it.matches(Regex("media stage=microphone-capture type=100 audioType=speechrecognition frames=[0-9]+ nonzeroFrames=[0-9]+ state=(1|3|unknown) ended=(true|false)"))
                })
            }
        } finally { sink.close();assertTrue(sink.awaitRetired(3000));session.close();ShadowAudioRecord.clearSource() }
    }
}
