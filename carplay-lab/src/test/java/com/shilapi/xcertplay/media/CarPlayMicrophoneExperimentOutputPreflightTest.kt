package com.shilapi.xcertplay.media

import android.Manifest
import android.app.Application
import android.media.*
import android.view.Surface
import com.openautolink.carplaylab.media.*
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,manifest=Config.NONE,
    shadows=[CarPlayMicrophoneExperimentOutputPreflightTest.OpusRateRecorder::class,
        AndroidMicrophoneExperimentQualificationTest.ObservedCodec::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayMicrophoneExperimentOutputPreflightTest {
    @Implements(AudioRecord::class)
    class OpusRateRecorder {
        companion object {
            @JvmStatic @Implementation fun native_get_min_buff_size(rate: Int, channels: Int, format: Int): Int =
                if (rate == 48000 && channels == 2) 1024 else -2
        }
    }
    private fun prepare() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(OpusEncoder.format(64000)).setIsEncoder(true).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("qualified.opus.encoder")
            .setIsEncoder(true).setCapabilities(caps).build())
    }
    @Test fun opusWithActualEncoderButNoOutputDecoderRejectsBeforePairing() = rejects(CarPlayMicrophoneCodec.OPUS)
    @Test fun autoWithOnlyUnavailableRowInputsRejectsBeforePairing() = rejects(CarPlayMicrophoneCodec.AUTO)
    private fun rejects(codec: CarPlayMicrophoneCodec) {
        prepare()
        val app=RuntimeEnvironment.getApplication()
        val pairing=File(app.noBackupFilesDir,"pairing-probe");pairing.deleteRecursively()
        assertTrue(AndroidAudioDecoderSupport.availableBits.intersect(MicrophoneSupport.opusBits).isEmpty())
        val profile=CarPlayMicrophoneExperimentProfile(source=MicrophoneSource.VOICE_RECOGNITION,
            rate=CarPlayMicrophoneCaptureRate.NEGOTIATED,channels=CarPlayMicrophoneChannels.TWO,codec=codec)
        val cap=AndroidMicrophoneSupport.experimentCapability(app,profile)
        assertTrue("Production experimental encoder qualification succeeded",0x20000000L in cap.bits("telephony"))
        assertTrue("Only unavailable Opus-row inputs qualify",cap.bits("telephony").intersect(MicrophoneSupport.pcmBits).isEmpty())
        val receipts=mutableListOf<String>()
        CarPlayMicrophoneExperiments.arm(profile)
        var receiver: ProjectionReceiver?=null
        try {
            val failure=runCatching { receiver=ProjectionReceiver(app,"00:11:22:33:44:55",mock(Surface::class.java),
                mock(MfiAuthenticator::class.java),display=CarPlayDisplayPreferences(
                    qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"avc.exact")),captureAdmission={true},onStatus=receipts::add) }.exceptionOrNull()
            receiver?.let { it.close();assertTrue(it.awaitClosed(3000)) }
            assertEquals(listOf("media stage=microphone-experiment-rejected codec=$codec reason=no-qualified-input"),receipts)
            assertTrue(failure is IllegalArgumentException)
            assertFalse("No pairing allocation when actual rows have no input",pairing.exists())
            MicrophoneCaptureFence.tryDiagnostic()!!.release()
        } finally { CarPlayMicrophoneExperiments.disarm() }
    }
}
