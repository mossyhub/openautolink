package com.shilapi.xcertplay.media

import android.Manifest
import android.media.*
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
class WiredPcmCaptureTest {
    @Test fun twoChannelInitializationFailureOmitsCallsWithoutMonoFallbackOrNoncallChanges() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=mock(AudioDeviceInfo::class.java)
        `when`(device.isSource).thenReturn(true); `when`(device.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        val shapes=mutableListOf<Pair<Int,Int>>()
        mockConstruction(AudioRecord.Builder::class.java,withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
            var source=0
            var rate=0
            var channels=0
            doAnswer { source=it.getArgument(0); builder }.`when`(builder).setAudioSource(anyInt())
            doAnswer { val f=it.getArgument<android.media.AudioFormat>(0); rate=f.sampleRate; channels=f.channelCount; builder }
                .`when`(builder).setAudioFormat(any(android.media.AudioFormat::class.java))
            `when`(builder.build()).thenAnswer {
                assertNotEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION,source)
                shapes.add(rate to channels)
                mock(AudioRecord::class.java).also {
                    `when`(it.state).thenReturn(if(channels==1) AudioRecord.STATE_INITIALIZED else AudioRecord.STATE_UNINITIALIZED)
                    `when`(it.sampleRate).thenReturn(rate); `when`(it.channelCount).thenReturn(channels)
                    `when`(it.audioFormat).thenReturn(android.media.AudioFormat.ENCODING_PCM_16BIT)
                }
            }
        }.use {
            val cap=AndroidMicrophoneSupport.probe(app)
            assertTrue(cap.bits("telephony").isEmpty())
            assertTrue(0x40L in cap.bits("default")); assertTrue(0x40L in cap.bits("speechrecognition"))
            assertEquals(listOf(24000 to 1,48000 to 1,24000 to 1,48000 to 1,48000 to 2),shapes)
        }
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
    }
    @Test fun qualificationNeverPromotesAnotherSourceOrCaptureRateIntoPcm() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=mock(AudioDeviceInfo::class.java)
        `when`(device.isSource).thenReturn(true); `when`(device.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        val records=mutableListOf<AudioRecord>()
        val sources=mutableListOf<Int>()
        // Only the OS builder/recorder is replaced. The production source/rate probe and capability filtering run unchanged.
        mockConstruction(AudioRecord.Builder::class.java,withSettings().defaultAnswer(RETURNS_SELF)) { builder, _ ->
            var source=MediaRecorder.AudioSource.MIC
            var rate=48000
            var channels=1
            doAnswer { source=it.getArgument(0); sources.add(source); builder }.`when`(builder).setAudioSource(anyInt())
            doAnswer { val format=it.getArgument<android.media.AudioFormat>(0); rate=format.sampleRate; channels=format.channelCount; builder }.`when`(builder).setAudioFormat(any(android.media.AudioFormat::class.java))
            `when`(builder.build()).thenAnswer {
                val record=mock(AudioRecord::class.java); records.add(record)
                `when`(record.state).thenReturn(if (rate==48000 && source!=MediaRecorder.AudioSource.VOICE_COMMUNICATION) AudioRecord.STATE_INITIALIZED else AudioRecord.STATE_UNINITIALIZED)
                `when`(record.sampleRate).thenReturn(rate)
                `when`(record.channelCount).thenReturn(channels)
                `when`(record.audioFormat).thenReturn(android.media.AudioFormat.ENCODING_PCM_16BIT)
                record
            }
        }.use {
            val cap=AndroidMicrophoneSupport.probe(app)
            assertTrue(0x40L in cap.bits("telephony"))
            assertFalse(0x40L in cap.bits("default"))
            assertFalse(0x40L in cap.bits("speechrecognition"))
            assertFalse("Normal qualification must never request legacy source7",sources.contains(MediaRecorder.AudioSource.VOICE_COMMUNICATION))
            records.forEach { verify(it).release(); verify(it,never()).startRecording() }
        }
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(AndroidMicrophoneSupport.capability.available)
    }
    @Test fun qualifiedPcmRecorderSendsRealSealedBigEndianUdpWithoutOpus() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device = mock(AudioDeviceInfo::class.java)
        `when`(device.isSource).thenReturn(true)
        `when`(device.type).thenReturn(AudioDeviceInfo.TYPE_BUILTIN_MIC)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        val cap = AndroidMicrophoneSupport.probe(app)
        assertTrue("qualified recorder must support PCM independently of Opus inventory",0x40L in cap.bits("telephony"))
        val captured = CountDownLatch(1)
        val pcm = ByteArray(960) { if (it % 2 == 0) 0x34 else 0x12 }
        ShadowAudioRecord.setSourceProvider { recorder ->
            assertEquals(16000, recorder.sampleRate)
            assertEquals(1,recorder.channelCount)
            assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION,recorder.audioSource)
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInByteArray(bytes: ByteArray, offset: Int, size: Int, blocking: Boolean): Int {
                    captured.countDown()
                    val count = minOf(size,pcm.size)
                    pcm.copyInto(bytes,offset,0,count)
                    return count
                }
            }
        }
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { receive ->
            receive.soTimeout = 2000
            val key = ByteArray(32) { (it+1).toByte() }
            val mic = MicrophoneSupport.negotiate(cap,100,"telephony",0x40L,receive.localPort,480,
                receive.localAddress,key)!!
            val reports = java.util.concurrent.CopyOnWriteArrayList<String>()
            val uplink = MicrophoneUplink(mic,reports::add,captureAdmission=MicrophoneCaptureAdmission {true})
            try {
                uplink.start()
                assertTrue("capture never reached",captured.await(2,TimeUnit.SECONDS))
                val packet = DatagramPacket(ByteArray(4096),4096); receive.receive(packet)
                val wire = packet.data.copyOf(packet.length)
                assertEquals(12+960+16+8,wire.size)
                val nonce = ByteArray(4)+wire.takeLast(8).toByteArray()
                val body = AirPlayCrypto.chachaOpen(key,nonce,wire.copyOfRange(12,wire.size-8),wire.copyOfRange(4,12))
                assertArrayEquals(ByteArray(960) { if (it % 2 == 0) 0x12 else 0x34 },body)
                assertEquals(100,wire[1].toInt())
                val first = ByteBuffer.wrap(wire,4,4).order(ByteOrder.BIG_ENDIAN).int
                receive.receive(packet)
                assertEquals(first+480,ByteBuffer.wrap(packet.data,4,4).int)
            } finally { uplink.close(); ShadowAudioRecord.clearSource() }
            assertTrue(uplink.isRetired)
            assertArrayEquals(ByteArray(32),mic.key)
        }
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(AndroidMicrophoneSupport.capability.available)
    }
}
