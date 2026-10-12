package com.openautolink.carplaylab.media

import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Test

class CarPlayAutoResolutionPolicyTest {
    @Test fun selectedReceiptIsConsumedWithoutRequeryingCodecInventory() {
        val receipt = CarPlayVideoSelection(2560,1440,31,"original-avc","original-hevc")
        val display = CarPlayDisplayPreferences(resolution = CarPlayResolution.CUSTOM,
            customWidth = 2560, customHeight = 1440, fps = 31, preferHevc = true,
            qualifiedVideoSelection = receipt)
        val actual = CarPlayDisplayPolicy.videoSelection(display,VideoDecoderProbe { error("must not requery") })
        assertSame(receipt,actual)
    }

    @Test fun manualPhysicalCalibrationDoesNotChangeWhenAutomaticGeometryChanges() {
        val probe = VideoDecoderProbe { "avc" }
        val receipt = CarPlayVideoQualification.selectAutomatic(1280,720,3840,2160,30,false,probe)
        val display = CarPlayDisplayPreferences(resolution=CarPlayResolution.CUSTOM,
            customWidth=receipt.width,customHeight=receipt.height,widthPhysicalMm=225)
        assertEquals(225,display.physicalSize.widthMm)
        assertEquals(CarPlayAutoScalePolicy.Basis.MANUAL,display.physicalSize.basis)
    }

    @Test fun wideAndPortraitRankingIsBoundedAlignedAndAspectPreserving() {
        for ((w,h) in listOf(3000 to 1000,1080 to 1920,16384 to 2)) {
            val requests = mutableListOf<VideoDecoderRequest>()
            val result = CarPlayVideoQualification.selectAutomatic(w,h,0,0,59,true,VideoDecoderProbe {
                requests += it
                if (it.width==1280 && it.height==720 && it.fps==30 || it.width%16==0 && it.height%16==0 && it.width>1280) "aligned" else null
            })
            assertTrue(requests.size<=11)
            assertTrue(requests.all { it.width<=4096 && it.height<=4096 })
            if (!result.usedFallback) assertTrue(kotlin.math.abs(result.width.toDouble()/result.height/(w.toDouble()/h)-1)<=.02)
        }
    }

    @Test fun lowerQualifiedGeometryKeepsRequestedRateAndDoesNotOfferUnqualifiedHevc() {
        val requests = mutableListOf<VideoDecoderRequest>()
        val result = CarPlayVideoQualification.selectAutomatic(2560,1440,2560,1440,31,true,VideoDecoderProbe {
            requests += it
            if(it.codec==VideoCodec.H264 && it.width==1920 && it.height==1080) "lower-avc" else null
        })
        assertEquals(1920,result.width)
        assertEquals(31,result.fps)
        assertEquals("automatic_lower_qualified",result.selectionReason)
        assertFalse(result.hevcOffered)
        assertEquals(1,requests.count { it.codec==VideoCodec.H265 })
    }

    @Test fun missingViewportUsesOneExactBaselineProbeAndFailsClosedIfUnavailable() {
        val requests = mutableListOf<VideoDecoderRequest>()
        val probe = VideoDecoderProbe { requests += it; "baseline" }
        val result = CarPlayVideoQualification.selectAutomatic(0,0,3840,2160,60,true,probe)
        assertEquals(listOf(VideoDecoderRequest(VideoCodec.H264,1280,720,30)),requests)
        assertEquals("automatic_viewport_unavailable_baseline",result.selectionReason)
        assertTrue(result.usedFallback)
        assertFalse(result.hevcOffered)
        assertThrows(IllegalStateException::class.java) {
            CarPlayVideoQualification.selectAutomatic(2560,1440,2560,1440,30,true,VideoDecoderProbe { null })
        }
    }

    @Test fun failedBaselineIsNotReprobedAndPromotedByMutableInventory() {
        var baselineProbes = 0
        assertThrows(IllegalStateException::class.java) {
            CarPlayVideoQualification.selectAutomatic(2560,1440,2560,1440,30,true,VideoDecoderProbe {
                if (it.codec==VideoCodec.H264 && it.width==1280 && it.height==720 && ++baselineProbes>1) "changed" else null
            })
        }
        assertEquals(1,baselineProbes)
    }

    @Test fun smallAppViewportStillTargetsQualified1440ClassPanelDetail() {
        val probe = VideoDecoderProbe { "exact-${it.codec}" }
        val result = CarPlayVideoQualification.selectAutomatic(1280,720,3840,2160,30,true,probe)
        assertEquals("Auto must target 1440 class rather than default 4K or viewport 720p",2560,result.width)
        assertEquals(1440,result.height)
        assertEquals("exact-${VideoCodec.H264}",result.avcDecoderName)
        assertTrue(result.hevcOffered)
    }
}
