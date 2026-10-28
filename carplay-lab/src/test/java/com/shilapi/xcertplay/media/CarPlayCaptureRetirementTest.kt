package com.shilapi.xcertplay.media

import android.Manifest
import android.app.Application
import android.media.AudioRecord
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
import org.robolectric.annotation.*
import java.net.*
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class,manifest=Config.NONE,
    shadows=[com.shilapi.xcertplay.media.capture.StalledReleaseRecorder::class],
    instrumentedPackages=["com.shilapi.xcertplay.media.capture"])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayCaptureRetirementTest {
    @Test fun captureOnlyRetirementWaitsForRealRecorderReleaseAndFencesLateStartWithoutClosingPlayback() {
        val fixture=com.shilapi.xcertplay.media.capture.StalledReleaseRecorder.install()
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val cap=MicrophoneCapability.of(mapOf("speechrecognition" to setOf(0x40L)))
        val profile=CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.VOICE_RECOGNITION,
            rate=CarPlayMicrophoneCaptureRate.HZ_16000,aaStyle=true)
        val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val sink=AndroidMediaSink(app,surface,display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),
            microphoneCapability=cap,speechRecognitionProfile=profile,captureAdmission={true})
        val session=AirPlaySession(Socket(),AirPlayConfig("test","id","mac","950.7.1",AirPlayDisplayConfig(800,480)),
            AirPlayIdentity.generate(),PairingStore(),null,object : AirPlaySessionListener {},object : AirPlayMediaHandler {})
        val completed=CompletableFuture<Unit>()
        DatagramSocket(0,InetAddress.getLoopbackAddress()).use { udp ->
            fun config()=MicrophoneSupport.negotiate(cap,100,"speechrecognition",0x40L,udp.localPort,480,udp.localAddress,ByteArray(32) { 1 })!!
            try {
                sink.onMicrophoneStarted(MicrophoneStreamId(session,100,"speechrecognition",1),config())
                assertTrue(fixture.reading.await(3,TimeUnit.SECONDS))
                Thread { try { sink.retireMicrophoneCapture(); completed.complete(Unit) } catch(e:Exception) { completed.completeExceptionally(e) } }.start()
                assertTrue(fixture.releasing.await(3,TimeUnit.SECONDS))
                assertFalse("Posted close is not recorder retirement",completed.isDone)
                assertEquals(0,fixture.nativeReleases.get())
                val late=config()
                sink.onMicrophoneStarted(MicrophoneStreamId(session,100,"speechrecognition",2),late)
                assertTrue(late.key.all { it==0.toByte() })
                fixture.allowRelease.countDown()
                completed.get(3,TimeUnit.SECONDS)
                assertEquals(1,fixture.nativeReleases.get())
                assertEquals(AudioRecord.STATE_UNINITIALIZED,fixture.record!!.state)
                assertNotEquals("Capture retirement does not close playback/focus",CarPlayPlayDisposition.CLOSED,sink.prepareUserPlay())
                MicrophoneCaptureFence.tryDiagnostic()!!.release()
            } finally {
                fixture.allowRelease.countDown(); sink.close(); assertTrue(sink.awaitRetired(5000)); session.close(); fixture.close()
            }
        }
    }
}
