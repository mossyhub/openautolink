package com.shilapi.xcertplay.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE)
class MicrophoneConfigurationDiagnosticsTest {
    @Test fun enabledEffectsAreReportedFromExactConfigurationWithoutAttachingEffects() {
        val app=mock(Context::class.java)
        val record=mock(AudioRecord::class.java)
        val config=mock(AudioRecordingConfiguration::class.java)
        `when`(record.activeRecordingConfiguration).thenReturn(config)
        fun effect(type: java.util.UUID)=android.media.audiofx.AudioEffect.Descriptor().apply {
            this.type=type; name="private-effect-name"; implementor="private-vendor"
            uuid=java.util.UUID.randomUUID()
        }
        `when`(config.clientEffects).thenReturn(listOf(effect(android.media.audiofx.AudioEffect.EFFECT_TYPE_AEC)))
        `when`(config.effects).thenReturn(listOf(effect(android.media.audiofx.AudioEffect.EFFECT_TYPE_NS),effect(android.media.audiofx.AudioEffect.EFFECT_TYPE_AGC)))
        val lines=CopyOnWriteArrayList<String>();val received=CountDownLatch(2)
        val observer=MicrophonePolicyDiagnostics.forRecorder(app,record,MicrophoneSource.VOICE_COMMUNICATION,24000) {
            lines.add(it);if(it.startsWith("media stage=microphone-effects ")) received.countDown()
        }
        try {
            observer.sample()
            assertTrue("enabled-effect receipts missing",received.await(3,TimeUnit.SECONDS))
            assertTrue(lines.contains("media stage=microphone-effects scope=own source=VOICE_COMMUNICATION rate=24000 chain=client result=ok examined=1 truncated=false aec=true ns=false agc=false other=0"))
            assertTrue(lines.contains("media stage=microphone-effects scope=own source=VOICE_COMMUNICATION rate=24000 chain=capture result=ok examined=2 truncated=false aec=false ns=true agc=true other=0"))
            assertFalse(lines.any { it.contains("private") })
            verify(record,never()).audioSessionId
        } finally { observer.close() }
    }

    @Test fun exactRecorderConfigurationSeparatesRequestedClientAndCurrentCapturePath() {
        val app=mock(Context::class.java)
        val manager=mock(AudioManager::class.java)
        val record=mock(AudioRecord::class.java)
        val config=mock(AudioRecordingConfiguration::class.java)
        `when`(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)).thenReturn(PackageManager.PERMISSION_GRANTED)
        `when`(app.getSystemService(AudioManager::class.java)).thenReturn(manager)
        `when`(manager.mode).thenReturn(AudioManager.MODE_IN_COMMUNICATION)
        `when`(record.audioSource).thenReturn(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        `when`(record.activeRecordingConfiguration).thenReturn(config)
        `when`(config.clientAudioSource).thenReturn(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        `when`(config.audioSource).thenReturn(MediaRecorder.AudioSource.VOICE_RECOGNITION)
        fun format(rate: Int)=AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()
        `when`(config.clientFormat).thenReturn(format(24000))
        `when`(config.format).thenReturn(format(48000))
        val lines=CopyOnWriteArrayList<String>();val received=CountDownLatch(1)
        val observer=MicrophonePolicyDiagnostics.forRecorder(app,record,MicrophoneSource.VOICE_COMMUNICATION,24000) {
            lines.add(it);if(it.startsWith("media stage=microphone-config ")) received.countDown()
        }
        try {
            observer.sample()
            assertTrue("exact-recorder configuration receipt missing",received.await(3,TimeUnit.SECONDS))
            assertTrue(lines.contains("media stage=microphone-config scope=own requested=VOICE_COMMUNICATION rate=24000 recorder=VOICE_COMMUNICATION mode=communication config=present client=VOICE_COMMUNICATION active=VOICE_RECOGNITION clientRate=24000 deviceRate=48000 clientChannels=mono deviceChannels=mono clientEncoding=pcm16 deviceEncoding=pcm16 route=none"))
            verify(manager,never()).activeRecordingConfigurations
            verify(record,never()).audioSessionId
            verify(record,times(1)).getActiveMicrophones() // bounded own-recorder mapping on policy worker
        } finally { observer.close() }
    }
}
