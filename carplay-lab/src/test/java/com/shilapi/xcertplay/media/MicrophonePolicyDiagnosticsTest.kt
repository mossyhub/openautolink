package com.shilapi.xcertplay.media

import android.Manifest
import android.media.*
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers
import org.mockito.Mockito.*
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE,shadows=[MicrophonePolicyDiagnosticsTest.PolicyRecorder::class])
class MicrophonePolicyDiagnosticsTest {
    @Implements(AudioRecord::class)
    class PolicyRecorder {
        companion object {
            @JvmStatic @Implementation fun native_get_min_buff_size(rate: Int, channels: Int, format: Int): Int = 1024
        }
        @Implementation fun native_start(event: Int, session: Int): Int = 0
        @Implementation fun native_read_in_byte_array(b: ByteArray, o: Int, n: Int, blocking: Boolean): Int =
            readSource!!.readInByteArray(b,o,n,blocking)
        @Implementation fun getRoutedDevice(): AudioDeviceInfo? {
            queryEntered.countDown()
            while (queryRelease.count > 0) { try { queryRelease.await() } catch (_: InterruptedException) {} }
            return device
        }
        @Implementation fun getActiveRecordingConfiguration(): AudioRecordingConfiguration {
            return mock(AudioRecordingConfiguration::class.java).also { `when`(it.isClientSilenced).thenReturn(true) }
        }
    }
    companion object {
        @JvmField var queryEntered=CountDownLatch(1)
        @JvmField var queryRelease=CountDownLatch(1)
        @JvmField var device: AudioDeviceInfo?=null
        @JvmField var readSource: ShadowAudioRecord.AudioRecordSource?=null
    }

    @Test fun exactRecorderRouteAndAccessibleSilencingAreFinitePolicyOnly() {
        queryEntered=CountDownLatch(1);queryRelease=CountDownLatch(0)
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device!!,"mPort"),"mRole",1)
        AndroidMicrophoneSupport.initialize(app)
        val record=AndroidMicrophoneSupport.recorder(MicrophoneSource.VOICE_RECOGNITION,24000)
        val received=CountDownLatch(1);val lines=CopyOnWriteArrayList<String>()
        val policy=AndroidMicrophoneSupport.policyDiagnostics(record,MicrophoneSource.VOICE_RECOGNITION,24000) {
            lines.add(it);received.countDown()
        }
        try {
            record.startRecording();policy.sample()
            assertTrue(received.await(3,TimeUnit.SECONDS))
            assertEquals(listOf("media stage=microphone-policy scope=own source=VOICE_RECOGNITION rate=24000 permission=granted mute=false route=builtin silenced=true result=ok"),lines.filter { it.startsWith("media stage=microphone-policy ") })
        } finally { policy.close();record.stop();record.release() }
    }

    @Test fun revokeFencesLatePolicyWhileExactCaptureWorkerIsStillBlocked() {
        exercise(blockRead=true)
    }
    @Test fun stalledPolicyQueryDoesNotHoldCaptureTransportOrRetirement() {
        exercise(blockRead=false)
    }
    private fun exercise(blockRead: Boolean) {
        queryEntered=CountDownLatch(1);queryRelease=CountDownLatch(1)
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device!!,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device!!))
        val cap=AndroidMicrophoneSupport.probe(app)
        val reads=AtomicInteger();val readEntered=CountDownLatch(1);val readRelease=CountDownLatch(1)
        val receipt=CountDownLatch(1);val lines=CopyOnWriteArrayList<String>()
        readSource=object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(b: ByteArray,o: Int,n: Int,blocking: Boolean): Int {
                if (reads.incrementAndGet()>1 && blockRead) {
                    readEntered.countDown()
                    while (readRelease.count>0) { try { readRelease.await() } catch (_: InterruptedException) {} }
                } else Thread.sleep(20)
                b.fill(0,o,o+n);return n
            }
        }
        var uplink: MicrophoneUplink?=null
        try {
            DatagramSocket(0,InetAddress.getLoopbackAddress()).use { peer ->
                peer.soTimeout=3000
                val config=MicrophoneSupport.negotiate(cap,100,"speechRecognition",0x40L,peer.localPort,480,peer.localAddress,ByteArray(32))!!
                uplink=MicrophoneUplink(config,report={ lines.add(it);if(it.contains("microphone-policy")) receipt.countDown() },
                    captureAdmission=MicrophoneCaptureAdmission {true})
                uplink!!.start()
                peer.receive(DatagramPacket(ByteArray(4096),4096))
                assertTrue(queryEntered.await(3,TimeUnit.SECONDS))
                if(blockRead) assertTrue(readEntered.await(3,TimeUnit.SECONDS))
                else repeat(2) { peer.receive(DatagramPacket(ByteArray(4096),4096)) }
                uplink!!.close()
                assertEquals("only the recorder owner, never its observation, gates retirement",!blockRead,uplink!!.isRetired)
                queryRelease.countDown()
                assertFalse("revoked exact stream must suppress a late public OS query",receipt.await(3,TimeUnit.SECONDS))
                readRelease.countDown()
                uplink!!.close();assertTrue(uplink!!.isRetired)
            }
        } finally {
            queryRelease.countDown();readRelease.countDown();uplink?.close();ShadowAudioRecord.clearSource()
        }
    }
}
