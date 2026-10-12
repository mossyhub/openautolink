package com.shilapi.xcertplay.media

import android.media.AudioAttributes
import android.media.AudioTrack
import android.view.Surface
import com.openautolink.carplaylab.media.CarPlayDisplayPreferences
import com.openautolink.carplaylab.media.CarPlayDisplaySelection
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAudioTrack
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32], manifest=Config.NONE, shadows=[CarPlayAudioPurposeRoutingTest.WrittenTrack::class])
class CarPlayAudioPurposeRoutingTest {
    private fun writtenAttributes(type: Int, kind: String): AudioAttributes {
        WrittenTrack.written = CountDownLatch(1)
        WrittenTrack.attributes.set(null)
        val surface = mock(Surface::class.java)
        `when`(surface.isValid).thenReturn(true)
        val sink = AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false))
        val format = requireNotNull(AudioOutputSupport.negotiate(0x4000L,type,kind))
        val id = AudioStreamId(type,format.audioType)
        try {
            sink.onAudioStarted(id,format,0)
            sink.onAudioRtp(id,format,ByteArray(16).apply { this[0]=0x80.toByte() },0)
            assertTrue("production sink must write negotiated PCM",WrittenTrack.written.await(3,TimeUnit.SECONDS))
            return requireNotNull(WrittenTrack.attributes.get())
        } finally { sink.close(); assertTrue(sink.awaitRetired(3000)) }
    }
    @Test fun alternateCompatibilityPcmUsesNavigationSpeechAttributes() {
        val actual = writtenAttributes(101,"compatibility")
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,actual.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH,actual.contentType)
    }
    @Test fun alternateDefaultPcmUsesNavigationSpeechAttributes() {
        val actual = writtenAttributes(101,"default")
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,actual.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH,actual.contentType)
    }
    @Test fun negotiatedTelephonyUsesVoiceCommunicationSpeechAttributes() {
        val actual = writtenAttributes(100,"telephony")
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION,actual.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH,actual.contentType)
    }
    @Test fun negotiatedSiriUsesAssistantSpeechAttributes() {
        val actual = writtenAttributes(100,"speechRecognition")
        assertEquals(AudioAttributes.USAGE_ASSISTANT,actual.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH,actual.contentType)
    }
    @Test fun negotiatedAlertMatchesAndroidAutoRingtoneSonification() {
        val actual = writtenAttributes(100,"alert")
        assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE,actual.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION,actual.contentType)
    }
    @Test fun mainMediaAndCompatibilityStayOnMusicAttributes() {
        for (kind in listOf("media","compatibility","default")) {
            val actual = writtenAttributes(100,kind)
            assertEquals(AudioAttributes.USAGE_MEDIA,actual.usage)
            assertEquals(AudioAttributes.CONTENT_TYPE_MUSIC,actual.contentType)
        }
    }
    @Implements(AudioTrack::class)
    class WrittenTrack : ShadowAudioTrack() {
        @RealObject lateinit var actual: AudioTrack
        @Implementation override fun native_write_byte(bytes: ByteArray, offset: Int, size: Int,
            encoding: Int, blocking: Boolean): Int {
            attributes.set(actual.audioAttributes)
            written.countDown()
            return size
        }
        companion object {
            lateinit var written: CountDownLatch
            val attributes = AtomicReference<AudioAttributes?>()
        }
    }
}
