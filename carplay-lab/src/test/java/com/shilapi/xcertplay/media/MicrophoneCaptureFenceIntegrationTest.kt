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
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowAudioRecord
import org.robolectric.util.ReflectionHelpers
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE)
class MicrophoneCaptureFenceIntegrationTest {
    private fun prepare(): MicrophoneCapability {
        val app=RuntimeEnvironment.getApplication();shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val d=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(d,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(d))
        return AndroidMicrophoneSupport.probe(app)
    }
    private fun config(cap: MicrophoneCapability) = MicrophoneSupport.negotiate(cap,100,"speechRecognition",0x40L,
        50000,480,InetAddress.getLoopbackAddress(),ByteArray(32))!!
    @Test fun temporaryQualificationCannotCreateRecordersDuringDiagnostic() {
        prepare()
        val lease=MicrophoneCaptureFence.tryDiagnostic()!!
        try {
            val cap=AndroidMicrophoneSupport.probe(RuntimeEnvironment.getApplication())
            assertTrue("qualification must honor diagnostic ownership",cap.bits("speechrecognition").isEmpty())
        } finally { lease.release() }
        assertTrue("temporary diagnostic rejection must not poison capability cache",
            AndroidMicrophoneSupport.capability.bits("speechrecognition").contains(0x40L))
    }
    @Test fun normalUplinkIsRejectedBeforeWorkerStartWhenDiagnosticOwnsCapture() {
        val cap=prepare();val lines=mutableListOf<String>()
        val lease=MicrophoneCaptureFence.tryDiagnostic()!!
        val owner=MicrophoneUplink(config(cap),lines::add,captureAdmission=MicrophoneCaptureAdmission {true})
        try {
            owner.start()
            assertTrue("diagnostic fence rejects normal start synchronously",owner.isRetired)
            assertTrue(lines.contains("media stage=microphone-owner-busy"))
        } finally { owner.close();lease.release() }
    }
    @Test fun interruptedNormalReadRetainsFenceUntilOwningWorkerActuallyReleases() {
        val cap=prepare();val entered=CountDownLatch(1);val unblock=CountDownLatch(1)
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(b: ByteArray,o: Int,n: Int,blocking: Boolean): Int {
                entered.countDown()
                while(unblock.count>0) try { unblock.await() } catch (_: InterruptedException) {}
                return -1
            }
        })
        val owner=MicrophoneUplink(config(cap),captureAdmission=MicrophoneCaptureAdmission {true})
        try {
            owner.start();assertTrue(entered.await(3,TimeUnit.SECONDS));owner.close()
            assertFalse(owner.isRetired)
            val wrong=MicrophoneCaptureFence.tryDiagnostic()
            try { assertNull("interruption is not a capture release acknowledgement",wrong) }
            finally { wrong?.release() }
        } finally {
            unblock.countDown();owner.close();ShadowAudioRecord.clearSource()
        }
        assertTrue(owner.isRetired)
        MicrophoneCaptureFence.tryDiagnostic()!!.release()
    }
}
