package com.shilapi.xcertplay.media

import android.Manifest
import android.app.Application
import android.media.*
import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE, application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayMicrophoneExperimentUplinkTest {
    private fun prepare(): MicrophoneCapability {
        val app=RuntimeEnvironment.getApplication(); shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        return AndroidMicrophoneSupport.probe(app)
    }
    @Test fun incomingCallUsesFrozenRecognitionStereo48CaptureAndAuthenticatedMono24Wire() {
        exercise(CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.VOICE_RECOGNITION,
            rate=CarPlayMicrophoneCaptureRate.HZ_48000,channels=CarPlayMicrophoneChannels.TWO,
            selection=CarPlayMicrophoneChannelSelection.SECOND), 3840, 2000)
    }
    @Test fun unarmedTelephonyUsesAcceptedMono16WithoutExperimentUx() { exercise(null,640,-1000) }
    @Test fun unarmedDefaultUsesAcceptedMono16AndUnchangedWireBytes() { exercise(null,640,-1000,"default") }
    @Test fun unarmedSiriUsesAcceptedMono16AndUnchangedWireBytes() { exercise(null,640,-1000,"speechrecognition") }
    private fun exercise(profile: CarPlayMicrophoneExperimentProfile?, expectedReadBytes: Int, expectedSample: Int,
        kind: String = "telephony") {
        val capability=prepare(); val reads=CopyOnWriteArrayList<Int>(); val lines=CopyOnWriteArrayList<String>()
        val count=AtomicInteger()
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(b: ByteArray,o: Int,n: Int,blocking: Boolean): Int {
                if(count.incrementAndGet()>2) { Thread.sleep(10); return 0 }
                reads.add(n)
                val data=ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
                for (i in o until o+n step 2) data.putShort(i, (if ((i/2)%2==0 || kind!="telephony") -1000 else 2000).toShort())
                // Changing global arming after the actual owner starts must not affect this owner.
                CarPlayMicrophoneExperiments.arm(CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.MIC))
                CarPlayMicrophoneExperiments.disarm()
                return n
            }
        })
        val surface=mock(Surface::class.java);`when`(surface.isValid).thenReturn(true)
        val sink=AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,onStatus=lines::add,
            display=com.openautolink.carplaylab.media.CarPlayDisplaySelection(
                com.openautolink.carplaylab.media.CarPlayDisplayPreferences(),"avc.exact",false),
            microphoneExperiment=profile,microphoneCapability=capability,captureAdmission={true})
        val session=AirPlaySession(Socket(),AirPlayConfig("test","id","mac","950.7.1",AirPlayDisplayConfig(800,480)),
            AirPlayIdentity.generate(),PairingStore(),null,object:AirPlaySessionListener{},object:AirPlayMediaHandler{})
        val key=ByteArray(32) { (it+1).toByte() }
        try {
            DatagramSocket(0,InetAddress.getLoopbackAddress()).use { peer ->
                peer.soTimeout=3000
                val config=MicrophoneSupport.negotiate(capability,100,kind,0x40L,peer.localPort,480,peer.localAddress,key)!!
                sink.onMicrophoneStarted(MicrophoneStreamId(session,100,kind,1),config)
                repeat(2) { index ->
                    val packet=DatagramPacket(ByteArray(4096),4096);peer.receive(packet)
                    val bytes=packet.data.copyOf(packet.length)
                    assertEquals(index*480,ByteBuffer.wrap(bytes).getInt(4))
                    val body=AirPlayCrypto.chachaOpen(key,ByteArray(4)+bytes.takeLast(8).toByteArray(),
                        bytes.copyOfRange(12,bytes.size-8),bytes.copyOfRange(4,12))
                    assertEquals(960,body.size)
                    val samples=ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN).asShortBuffer()
                    for (sample in 0 until 480) {
                        val expected = if (profile==null && kind=="telephony") {
                            if (sample%3==2) 2000 else -1000 // exact 16 -> 24 nearest-frame mapping
                        } else expectedSample
                        assertEquals(expected,samples.get().toInt())
                    }
                    assertFalse(samples.hasRemaining())
                }
            }
            assertEquals(listOf(expectedReadBytes,expectedReadBytes),reads.toList())
            assertTrue(lines.any { it.contains("source=${profile?.source ?: MicrophoneSource.VOICE_RECOGNITION}") })
            if (profile==null) assertTrue(lines.any {
                it.startsWith("media stage=microphone-acquisition path=aa-style source=VOICE_RECOGNITION captureRate=16000 captureChannels=1 wireRate=24000 ")
            })
            if (profile==null) assertFalse(lines.any { it.contains("stage=microphone-experiment ") })
        } finally {
            sink.close();assertTrue(sink.awaitRetired(3000));session.close();ShadowAudioRecord.clearSource()
            CarPlayMicrophoneExperiments.disarm()
        }
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
    }
}
