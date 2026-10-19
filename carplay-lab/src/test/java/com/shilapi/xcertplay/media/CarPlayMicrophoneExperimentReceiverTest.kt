package com.shilapi.xcertplay.media

import android.app.Application
import android.view.Surface
import com.openautolink.carplaylab.media.ProjectionReceiver
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],application=Application::class,manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayMicrophoneExperimentReceiverTest {
    @Test fun incompatibleExperimentalCodecRejectsBeforePairingOrMediaResources() {
        val app=RuntimeEnvironment.getApplication()
        val pairing=File(app.noBackupFilesDir,"pairing-probe")
        assertFalse(pairing.exists())
        val receipts=mutableListOf<String>()
        CarPlayMicrophoneExperiments.arm(CarPlayMicrophoneExperimentProfile(codec=CarPlayMicrophoneCodec.LPCM))
        try {
            val failure=runCatching { ProjectionReceiver(app,"00:11:22:33:44:55",mock(Surface::class.java),
                mock(MfiAuthenticator::class.java),
                display=com.openautolink.carplaylab.media.CarPlayDisplayPreferences(
                    qualifiedVideoSelection=CarPlayVideoSelection(1280,720,30,"avc.exact")),
                wired=false,onStatus=receipts::add) }.exceptionOrNull()
            assertEquals(listOf("media stage=microphone-experiment-rejected codec=LPCM reason=no-qualified-input"),receipts)
            assertTrue(failure is IllegalArgumentException)
            assertFalse("Rejected experiment must not create pairing state",pairing.exists())
            MicrophoneCaptureFence.tryDiagnostic()!!.release()
        } finally { CarPlayMicrophoneExperiments.disarm() }
    }
}
