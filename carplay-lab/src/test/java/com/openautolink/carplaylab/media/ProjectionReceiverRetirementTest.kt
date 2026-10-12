// SPDX-License-Identifier: GPL-3.0-only
package com.openautolink.carplaylab.media

import android.media.AudioTrack
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.GeneratedHevcFixture
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE)
class ProjectionReceiverRetirementTest {
    private fun receiver(): ProjectionReceiver {
        val caps = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720))
            .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                level = MediaCodecInfo.CodecProfileLevel.AVCLevel52
            })).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("retirement.avc")
            .setIsEncoder(false).setCapabilities(caps).build())
        val surface = mock(Surface::class.java)
        `when`(surface.isValid).thenReturn(true)
        val context = RuntimeEnvironment.getApplication()
        java.io.File(context.noBackupFilesDir, "pairing-probe").deleteRecursively()
        return ProjectionReceiver(context, "02:00:00:00:00:01", surface,
            mock(MfiAuthenticator::class.java), wired = true, onStatus = {})
    }

    private fun sink(receiver: ProjectionReceiver): AndroidMediaSink =
        ProjectionReceiver::class.java.getDeclaredField("sink").apply { isAccessible = true }
            .get(receiver) as AndroidMediaSink

    private fun ignoreInterruptUntil(latch: CountDownLatch) {
        while (true) {
            try { latch.await(); return } catch (_: InterruptedException) { /* stalled platform call */ }
        }
    }

    @Test
    @Config(shadows = [StalledAudioTrack::class])
    fun receiverRetainsStalledAudioTrackAcrossStopAndCloseRetriesUntilOwnerRelease() {
        StalledAudioTrack.reset()
        val receiver = receiver()
        val sink = sink(receiver)
        val id = AudioStreamId(102, "media")
        val format = AudioFormat(AudioCodecKind.LPCM, 48000, 2, 102, "media")
        @Suppress("UNCHECKED_CAST")
        val owners = AndroidMediaSink::class.java.getDeclaredField("tracks").apply { isAccessible = true }
            .get(sink) as Map<AudioStreamId, Any>
        try {
            sink.onAudioStarted(id, format, 0)
            val owner = owners[id]
            assertNotNull(owner)
            sink.onAudioRtp(id, format, ByteArray(16).apply { this[0] = 0x80.toByte() }, 0)
            assertTrue(StalledAudioTrack.writing.await(3, TimeUnit.SECONDS))
            sink.onAudioStopped(id)
            assertSame("stop retains the worker that ignored interrupt", owner, owners[id])
            sink.onAudioStarted(id, format, 0)
            assertSame("replacement cannot overwrite a stalled exact owner", owner, owners[id])
            receiver.close()
            assertFalse("stalled AudioTrack worker must fence receiver acknowledgment", receiver.awaitClosed(100))
            assertFalse(sink.awaitRetired(0))
            assertSame("close retains the exact track", owner, owners[id])
            receiver.close()
            assertFalse(receiver.awaitClosed(100))
            assertSame("close retry retains the exact track", owner, owners[id])
            StalledAudioTrack.resumeWrite.countDown()
            assertTrue(StalledAudioTrack.releasing.await(3, TimeUnit.SECONDS))
            assertFalse("a returned write is not a completed owner release", receiver.awaitClosed(100))
            StalledAudioTrack.resumeRelease.countDown()
            assertTrue(receiver.awaitClosed(3000))
            assertTrue(sink.awaitRetired(0))
            assertTrue(owners.isEmpty())
            assertEquals(listOf("lab-audio-output"), StalledAudioTrack.releaseThreads)
        } finally {
            StalledAudioTrack.resumeWrite.countDown(); StalledAudioTrack.resumeRelease.countDown()
            receiver.close(); assertTrue(receiver.awaitClosed(3000))
        }
    }

    @Implements(AudioTrack::class)
    class StalledAudioTrack : ShadowAudioTrack() {
        @Implementation
        override fun native_write_byte(bytes: ByteArray, offset: Int, size: Int, encoding: Int, blocking: Boolean): Int {
            writing.countDown(); waitIgnoringInterrupt(resumeWrite)
            return size
        }
        @Implementation
        fun release() {
            releasing.countDown(); waitIgnoringInterrupt(resumeRelease)
            releaseThreads.add(Thread.currentThread().name)
        }
        companion object {
            lateinit var writing: CountDownLatch
            lateinit var resumeWrite: CountDownLatch
            lateinit var releasing: CountDownLatch
            lateinit var resumeRelease: CountDownLatch
            val releaseThreads = java.util.concurrent.CopyOnWriteArrayList<String>()
            fun reset() {
                writing = CountDownLatch(1); resumeWrite = CountDownLatch(1)
                releasing = CountDownLatch(1); resumeRelease = CountDownLatch(1); releaseThreads.clear()
            }
            private fun waitIgnoringInterrupt(latch: CountDownLatch) {
                while (true) {
                    try { latch.await(); return } catch (_: InterruptedException) { /* stalled OS boundary */ }
                }
            }
        }
    }

    @Test fun receiverWithholdsAcknowledgmentUntilExactStalledCodecOwnerReleases() {
        val receiver = receiver()
        val sink = sink(receiver)
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val released = CountDownLatch(1)
        val releaseThread = AtomicReference<String>()
        val codec = mock(MediaCodec::class.java)
        `when`(codec.codecInfo).thenReturn(
            android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS).codecInfos.first { it.name == "retirement.avc" })
        doAnswer { entered.countDown(); ignoreInterruptUntil(resume); null }.`when`(codec)
            .configure(any(MediaFormat::class.java), any(Surface::class.java), isNull(), eq(0))
        doAnswer { releaseThread.set(Thread.currentThread().name); released.countDown(); null }.`when`(codec).release()
        val factory: (String) -> MediaCodec = { codec }
        AndroidMediaSink::class.java.getDeclaredField("videoCodecFactory").apply { isAccessible = true }.set(sink, factory)
        try {
            sink.onVideoCodec(110, VideoCodec.H264)
            sink.onVideoConfig(110, GeneratedHevcFixture.avcc)
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            receiver.close() // Deliberately exceeds the sink's 500ms best-effort join.
            assertFalse("live codec owner must fence successor admission", receiver.awaitClosed(100))
            assertFalse(sink.awaitRetired(0))
            receiver.close()
            assertFalse("retry cannot forget the exact owner", receiver.awaitClosed(100))
            verify(codec, never()).release()
            resume.countDown()
            assertTrue(released.await(3, TimeUnit.SECONDS))
            assertTrue(receiver.awaitClosed(3000))
            assertEquals("lab-video", releaseThread.get())
            verify(codec, times(1)).release()
        } finally { resume.countDown(); receiver.close(); assertTrue(receiver.awaitClosed(3000)) }
    }
}
