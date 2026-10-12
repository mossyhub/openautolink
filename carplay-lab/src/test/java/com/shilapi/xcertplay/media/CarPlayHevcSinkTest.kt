package com.shilapi.xcertplay.media

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.openautolink.carplaylab.media.*
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE)
class CarPlayHevcSinkTest {
    @Test fun busyHevcOutputDoesNotStarveAvcReplacement() {
        val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val draining=CountDownLatch(1); val replacementQueued=CountDownLatch(1)
        val configured=CountDownLatch(1); val calls=java.util.concurrent.atomic.AtomicInteger()
        val callsAtReplacement=java.util.concurrent.atomic.AtomicInteger()
        fun decoder(name: String,mime: String,profile: Int,level: Int): MediaCodec {
            val caps=org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(mime,1280,720)).setIsEncoder(false)
                .setColorFormats(intArrayOf(2130708361)).setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                    this.profile=profile; this.level=level })).build()
            val info=org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder().setName(name)
                .setIsEncoder(false).setCapabilities(caps).build()
            return mock(MediaCodec::class.java).apply {
                `when`(codecInfo).thenReturn(info); `when`(this.name).thenReturn(name)
                `when`(dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java),anyLong())).thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER)
            }
        }
        val hevc=decoder("hevc.exact",MediaFormat.MIMETYPE_VIDEO_HEVC,MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31)
        val avc=decoder("avc.exact",MediaFormat.MIMETYPE_VIDEO_AVC,MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        doAnswer {
            draining.countDown(); check(replacementQueued.await(3,TimeUnit.SECONDS))
            if(calls.incrementAndGet() <= 100) 0 else MediaCodec.INFO_TRY_AGAIN_LATER
        }.`when`(hevc).dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java),anyLong())
        doAnswer { callsAtReplacement.set(calls.get()); null }.`when`(avc)
            .configure(any(MediaFormat::class.java),eq(surface),isNull(),eq(0))
        val sink=AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),
            videoSelection=CarPlayVideoSelection(1280,720,30,"avc.exact","hevc.exact"),
            videoCodecFactory={ if(it=="hevc.exact") hevc else avc },
            onStatus={ if(it=="media stage=h264-configured") configured.countDown() })
        try {
            sink.onVideoCodec(110,VideoCodec.H265); sink.onVideoConfig(110,GeneratedHevcFixture.hvcc)
            assertTrue(draining.await(3,TimeUnit.SECONDS))
            sink.onVideoCodec(110,VideoCodec.H264); sink.onVideoConfig(110,GeneratedHevcFixture.avcc)
            replacementQueued.countDown()
            assertTrue(configured.await(3,TimeUnit.SECONDS))
            assertTrue("bounded drain must service replacement before exhausting busy output",callsAtReplacement.get() <= 32)
        } finally { replacementQueued.countDown(); sink.close() }
    }
    @Test fun avcFallbackReconfigurationRetiresExactHevcAndIgnoresOldRenderCallback() {
        val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val callbacks=mutableMapOf<String,MediaCodec.OnFrameRenderedListener>()
        fun decoder(name: String,mime: String,profile: Int,level: Int): MediaCodec {
            val caps=org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                .setMediaFormat(MediaFormat.createVideoFormat(mime,1280,720)).setIsEncoder(false)
                .setColorFormats(intArrayOf(2130708361)).setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                    this.profile=profile; this.level=level })).build()
            val info=org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder().setName(name)
                .setIsEncoder(false).setCapabilities(caps).build()
            return mock(MediaCodec::class.java).apply {
                `when`(codecInfo).thenReturn(info); `when`(this.name).thenReturn(name)
                `when`(dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java),anyLong())).thenReturn(MediaCodec.INFO_TRY_AGAIN_LATER)
                doAnswer { synchronized(callbacks) { callbacks[name]=it.getArgument(0) }; null }.`when`(this)
                    .setOnFrameRenderedListener(any(MediaCodec.OnFrameRenderedListener::class.java),any(android.os.Handler::class.java))
            }
        }
        val hevc=decoder("hevc.exact",MediaFormat.MIMETYPE_VIDEO_HEVC,MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31)
        val avc=decoder("avc.exact",MediaFormat.MIMETYPE_VIDEO_AVC,MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        val h265=CountDownLatch(1); val h264=CountDownLatch(1)
        val statuses=java.util.concurrent.CopyOnWriteArrayList<String>()
        val sink=AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),
            videoSelection=CarPlayVideoSelection(1280,720,30,"avc.exact","hevc.exact"),
            videoCodecFactory={ if(it=="hevc.exact") hevc else { assertEquals("avc.exact",it); avc } },
            onStatus={ statuses.add(it); if(it=="media stage=h265-configured") h265.countDown()
                if(it=="media stage=h264-configured") h264.countDown() })
        try {
            sink.onVideoCodec(110,VideoCodec.H265); sink.onVideoConfig(110,GeneratedHevcFixture.hvcc)
            assertTrue(h265.await(3,TimeUnit.SECONDS))
            sink.onVideoCodec(110,VideoCodec.H264); sink.onVideoConfig(110,GeneratedHevcFixture.avcc)
            assertTrue(h264.await(3,TimeUnit.SECONDS))
            verify(hevc).stop(); verify(hevc).release()
            synchronized(callbacks) { callbacks.getValue("hevc.exact").onFrameRendered(hevc,1,1) }
            assertFalse(statuses.any { it.contains("first-frame-rendered") })
            synchronized(callbacks) { callbacks.getValue("avc.exact").onFrameRendered(avc,2,2) }
            assertTrue(statuses.any { it.contains("first-frame-rendered") })
        } finally { sink.close() }
        assertTrue(sink.isVideoRetired); verify(avc).stop(); verify(avc).release()
        synchronized(callbacks) { callbacks.getValue("avc.exact").onFrameRendered(avc,3,3) }
        assertEquals(1,statuses.count { it.contains("first-frame-rendered") })
    }
    @Test fun hevcSpsMustMatchAdvertisedSizeAndRecordProfile() {
        val bytes=GeneratedHevcFixture.hvcc
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayVideoQualification.mediaFormat(VideoCodec.H265,bytes,CarPlayVideoSelection(1920,1080,30,"avc","hevc"))
        }
        bytes[1]=(bytes[1].toInt() and 0xe0 or 2).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            CarPlayVideoQualification.mediaFormat(VideoCodec.H265,bytes,CarPlayVideoSelection(1280,720,30,"avc","hevc"))
        }
    }
    @Test fun incomingHevcConfigUsesExactNameHevcMimeCsdAndOwnerRelease() {
        val surface=mock(Surface::class.java); `when`(surface.isValid).thenReturn(true)
        val codec=mock(MediaCodec::class.java)
        val caps=org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC,1280,720))
            .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                profile=MediaCodecInfo.CodecProfileLevel.HEVCProfileMain; level=MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31 })).build()
        val info=org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder().setName("hevc.exact")
            .setIsEncoder(false).setCapabilities(caps).build()
        `when`(codec.codecInfo).thenReturn(info)
        `when`(codec.dequeueOutputBuffer(any(MediaCodec.BufferInfo::class.java),anyLong()))
            .thenReturn(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,MediaCodec.INFO_TRY_AGAIN_LATER)
        `when`(codec.outputFormat).thenReturn(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC,1280,720))
        `when`(codec.name).thenReturn("hevc.exact")
        val output=CountDownLatch(1); val outputDiagnostic=AtomicReference<String>()
        val configured=CountDownLatch(1); val format=AtomicReference<MediaFormat>()
        doAnswer { format.set(it.getArgument(0)); null }.`when`(codec).configure(any(MediaFormat::class.java),eq(surface),isNull(),eq(0))
        val sink=AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),
            videoSelection=CarPlayVideoSelection(1280,720,30,"avc.exact","hevc.exact"),
            videoCodecFactory={ name -> assertEquals("hevc.exact",name); codec },
            onStatus={ if(it=="media stage=h265-configured") configured.countDown()
                if(it.startsWith("BENCH_DIAG videoOutput")) { outputDiagnostic.set(it); output.countDown() } })
        try {
            sink.onVideoCodec(110,VideoCodec.H265); sink.onVideoConfig(110,GeneratedHevcFixture.hvcc)
            assertTrue("exact HEVC configure/start receipt",configured.await(3,TimeUnit.SECONDS))
            assertEquals(MediaFormat.MIMETYPE_VIDEO_HEVC,format.get().getString(MediaFormat.KEY_MIME))
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,format.get().getInteger(MediaFormat.KEY_PROFILE))
            assertNotNull(format.get().getByteBuffer("csd-0")); assertFalse(format.get().containsKey("csd-1"))
            verify(codec).start()
            assertTrue(output.await(3,TimeUnit.SECONDS))
            assertEquals("BENCH_DIAG videoOutput codec=H265 advertised=1280x720 coded=1280x720 visible=1280x720",outputDiagnostic.get())
        } finally { sink.close() }
        verify(codec,timeout(1000)).stop(); verify(codec,timeout(1000)).release()
    }
    private fun sink(hevc: String? = "hevc.exact"): AndroidMediaSink {
        val surface=mock(Surface::class.java)
        `when`(surface.isValid).thenReturn(true)
        return AndroidMediaSink(RuntimeEnvironment.getApplication(),surface,
            display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),
            videoSelection=CarPlayVideoSelection(1280,720,30,"avc.exact",hevc))
    }
    @Test fun qualifiedHevcAndAvcFallbackAreBothAdmitted() {
        sink().use { it.onVideoCodec(110,VideoCodec.H265); it.onVideoCodec(110,VideoCodec.H264) }
    }
    @Test fun unqualifiedHevcAndNonMainScreenAreRejected() {
        sink(null).use { sink ->
            assertThrows(IllegalArgumentException::class.java) { sink.onVideoCodec(110,VideoCodec.H265) }
            assertThrows(IllegalArgumentException::class.java) { sink.onVideoCodec(111,VideoCodec.H264) }
        }
    }
}
