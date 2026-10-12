package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

class CarPlayVideoQualificationTest {
    @Test fun preferHevcRetainsExactAvcFallbackAndNames() {
        val calls = mutableListOf<VideoDecoderRequest>()
        val receipt = CarPlayVideoQualification.select(2400,960,31,true,VideoDecoderProbe {
            calls.add(it); if (it.codec == VideoCodec.H264) "avc.exact" else "hevc.exact"
        })
        assertEquals("avc.exact",receipt.avcDecoderName)
        assertEquals("hevc.exact",receipt.hevcDecoderName)
        assertEquals(listOf(VideoCodec.H264,VideoCodec.H265),calls.map { it.codec })
        assertTrue(calls.all { it.width==2400 && it.height==960 && it.fps==31 })
    }
    @Test fun unsupportedRequestedAvcUsesOnlyBaselineAndNeverHevcOnly() {
        val calls = mutableListOf<VideoDecoderRequest>()
        val receipt = CarPlayVideoQualification.select(3840,2160,60,true,VideoDecoderProbe {
            calls.add(it); if (it.codec==VideoCodec.H264 && it.width==1280 && it.height==720 && it.fps==30) "baseline" else null
        })
        assertTrue(receipt.usedFallback)
        assertFalse(receipt.hevcOffered)
        assertEquals(1280,receipt.width); assertEquals(720,receipt.height); assertEquals(30,receipt.fps)
        assertEquals(listOf(VideoCodec.H264,VideoCodec.H264),calls.map { it.codec })
    }
    @Test fun absentHevcStillReturnsRequestedAvcAndDisablingHevcSkipsProbe() {
        val calls = mutableListOf<VideoCodec>()
        val probe = VideoDecoderProbe { calls.add(it.codec); if(it.codec==VideoCodec.H264) "avc" else null }
        assertFalse(CarPlayVideoQualification.select(1920,1080,59,true,probe).hevcOffered)
        assertEquals(listOf(VideoCodec.H264,VideoCodec.H265),calls)
        calls.clear()
        assertFalse(CarPlayVideoQualification.select(1920,1080,59,false,probe).hevcOffered)
        assertEquals(listOf(VideoCodec.H264),calls)
    }
    @Test fun hevcOfferRequiresSameDimensionsRateAndProposedFeature() {
        val config=AirPlayConfig("Lab","id","00:11:22:33:44:55","1.0",AirPlayDisplayConfig(1280,720,fps=30),hevc=true,lpcmOnly=true)
        val receipt=CarPlayVideoSelection(1280,720,30,"avc","hevc")
        assertEquals(emptyMap<String,Any?>(),AirPlayInfoPlist.build(config,videoSelection=receipt)["hevcInfo"])
        assertFalse((AirPlayInfoPlist.build(config,videoSelection=receipt)["extendedFeatures"] as List<*>).contains("hevc"))
        assertTrue(setupEnabledFeatures(config,listOf("hevc"),receipt).contains("hevc"))
        assertTrue(setupEnabledFeatures(config,null,receipt).contains("hevc"))
        for (bad in listOf(receipt.copy(width=1920),receipt.copy(height=1080),receipt.copy(fps=31),receipt.copy(hevcDecoderName=null))) {
            assertFalse(AirPlayInfoPlist.build(config,videoSelection=bad).containsKey("hevcInfo"))
            assertFalse(setupEnabledFeatures(config,listOf("hevc"),bad).contains("hevc"))
        }
    }
    @Test fun invalidOrMissingBaselineFailsClosed() {
        val probe=VideoDecoderProbe { null }
        assertThrows(IllegalArgumentException::class.java) { CarPlayVideoQualification.select(0,720,30,true,probe) }
        assertThrows(IllegalStateException::class.java) { CarPlayVideoQualification.select(1920,1080,30,true,probe) }
    }
}
