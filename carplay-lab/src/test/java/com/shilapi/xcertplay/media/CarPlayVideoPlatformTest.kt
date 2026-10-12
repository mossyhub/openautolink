package com.shilapi.xcertplay.media

import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList

/** Real Android capability predicates in Robolectric, not new physical codec receipts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE)
class CarPlayVideoPlatformTest {
    private fun add(name: String,mime: String,profile: Int,level: Int) {
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(mime,1280,720)).setIsEncoder(false)
            .setColorFormats(intArrayOf(2130708361)).setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                this.profile=profile; this.level=level })).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName(name).setIsEncoder(false).setCapabilities(caps).build())
    }
    @Test fun androidProbeFiltersExactMimeProfileSizeRateAlignmentAndRetainsName() {
        ShadowMediaCodecList.reset()
        try {
            add("avc.high",MediaFormat.MIMETYPE_VIDEO_AVC,MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,MediaCodecInfo.CodecProfileLevel.AVCLevel52)
            add("hevc.main31",MediaFormat.MIMETYPE_VIDEO_HEVC,MediaCodecInfo.CodecProfileLevel.HEVCProfileMain,MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31)
            val probe=CarPlayVideoQualification.androidProbe()
            assertEquals("hevc.main31",probe.decoderFor(VideoDecoderRequest(VideoCodec.H265,1280,720,30)))
            assertNull(probe.decoderFor(VideoDecoderRequest(VideoCodec.H265,1280,720,60)))
            assertNull(probe.decoderFor(VideoDecoderRequest(VideoCodec.H265,1920,1080,30)))
            assertNull(probe.decoderFor(VideoDecoderRequest(VideoCodec.H265,1279,720,30)))
            assertEquals("avc.high",CarPlayVideoQualification.select(1280,720,30,true,probe).avcDecoderName)
            assertEquals("hevc.main31",CarPlayVideoQualification.select(1280,720,30,true,probe).hevcDecoderName)
            assertFalse(CarPlayVideoQualification.select(1920,1080,60,true,probe).hevcOffered)
        } finally { ShadowMediaCodecList.reset() }
    }
    @Test fun highTierCannotBorrowHigherMainTierCapability() {
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC,1280,720)).setIsEncoder(false)
            .setColorFormats(intArrayOf(2130708361)).setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                profile=MediaCodecInfo.CodecProfileLevel.HEVCProfileMain; level=MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel31 })).build()
        val format=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC,1280,720).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE,30); setInteger(MediaFormat.KEY_PROFILE,MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            setInteger(MediaFormat.KEY_LEVEL,MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel3)
        }
        assertFalse(CarPlayVideoQualification.supports(caps,format,30))
    }
    @Test fun main10OnlyInventoryDoesNotPretendToQualifyMainOffer() {
        ShadowMediaCodecList.reset()
        try {
            add("hevc.main10",MediaFormat.MIMETYPE_VIDEO_HEVC,MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel51)
            assertNull(CarPlayVideoQualification.androidProbe().decoderFor(VideoDecoderRequest(VideoCodec.H265,1280,720,30)))
        } finally { ShadowMediaCodecList.reset() }
    }
}
