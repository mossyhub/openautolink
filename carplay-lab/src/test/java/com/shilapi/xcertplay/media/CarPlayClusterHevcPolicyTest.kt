package com.shilapi.xcertplay.media

import com.openautolink.carplaylab.media.CarPlayClusterVideoConfiguration
import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32],manifest=Config.NONE)
class CarPlayClusterHevcPolicyTest {
    private val probe=VideoDecoderProbe { r -> if(r.width<=2560 && r.height<=1440) "${r.codec}.qualified" else null }
    private fun display(s: CarPlayVideoSelection) = CarPlayClusterVideoConfiguration.create(AirPlayInsets(),AirPlayInsets(),s,s.width,s.height).display
    @Test fun measuredOutputWinsAndPaddedHevcDimensionsMustQualify() {
        val requests=mutableListOf<VideoDecoderRequest>()
        val selected=CarPlayVideoQualification.selectCluster(2000,810,true,VideoDecoderProbe { r -> requests+=r; "decoder.${r.codec}" })
        assertEquals(2000,selected.width);assertEquals(810,selected.height);assertTrue(selected.hevcOffered)
        assertTrue(requests.contains(VideoDecoderRequest(VideoCodec.H265,2000,816,30)))
        val rejected=CarPlayVideoQualification.selectCluster(2000,810,true,VideoDecoderProbe { r ->
            if(r.codec==VideoCodec.H265 && r.height==816) null else "decoder.${r.codec}" })
        assertFalse(rejected.hevcOffered);assertEquals(2000,rejected.width)
    }
    @Test fun unsupportedHighResolutionUsesExactBoundedAspectThenConservativeAvc() {
        val result=CarPlayVideoQualification.selectCluster(2000,810,true,VideoDecoderProbe { r ->
            if(r.codec==VideoCodec.H264 && r.width<=1280 && r.height<=720) "avc" else null })
        assertEquals(1280,result.width);assertEquals(518,result.height);assertFalse(result.hevcOffered)
        val baseline=CarPlayVideoQualification.selectCluster(2000,810,false,VideoDecoderProbe { r ->
            if(r==VideoDecoderRequest(VideoCodec.H264,1280,480,30)) "baseline" else null })
        assertEquals(1280,baseline.width);assertEquals(480,baseline.height);assertTrue(baseline.usedFallback)
        assertThrows(IllegalStateException::class.java) { CarPlayVideoQualification.selectCluster(2000,810,false,VideoDecoderProbe { null }) }
    }
    @Test fun hevcRequiresBothReceiptsAndIndependentDecoderBudgetAndSerializesBplist() {
        val main=CarPlayVideoSelection(1920,1080,30,"main.avc","main.hevc")
        val secondary=CarPlayVideoQualification.selectCluster(2000,810,true,probe)
        val screen=display(secondary)
        val both=main.withCluster(screen,secondary,VideoDecoderBudgetProbe { _,_,_ -> true })
        val config=AirPlayConfig("test","id","00:11:22:33:44:55","1",AirPlayDisplayConfig(1920,1080,fps=30),cluster=screen,hevc=true,lpcmOnly=true)
        assertTrue(both.hevcOffered);assertTrue(both.matches(config))
        assertTrue(setupEnabledFeatures(config,null,both).contains("hevc"))
        val decoded=BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(config,videoSelection=both))) as Map<*,*>
        assertTrue(decoded.containsKey("hevcInfo"))
        val row=(decoded["displays"] as List<*>)[1] as Map<*,*>
        assertEquals(2000L,(row["widthPixels"] as Number).toLong());assertEquals(810L,(row["heightPixels"] as Number).toLong())
        assertEquals(30L,(row["maxFPS"] as Number).toLong())
        assertFalse(main.withCluster(screen,secondary,VideoDecoderBudgetProbe { _,_,_ -> false }).hevcOffered)
        assertFalse(main.copy(hevcDecoderName=null).withCluster(screen,secondary,VideoDecoderBudgetProbe { _,_,_ -> true }).hevcOffered)
        assertFalse(main.withCluster(screen,secondary.copy(hevcDecoderName=null),VideoDecoderBudgetProbe { _,_,_ -> true }).hevcOffered)
        assertFalse(both.matches(config.copy(cluster=screen.copy(widthPixels=1998))))
        assertFalse(both.matches(config.copy(main=config.main.copy(fps=60))))
    }
    @Test fun productionAndroidProbeUsesPaddedCapabilityAndReportedInstanceLimit() {
        org.robolectric.shadows.ShadowMediaCodecList.reset()
        try {
            fun add(name:String, mime:String, height:Int, instances:Int) {
                val caps=org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
                    .setMediaFormat(android.media.MediaFormat.createVideoFormat(mime,2000,height)).setIsEncoder(false)
                    .setColorFormats(intArrayOf(2130708361)).setProfileLevels(arrayOf(android.media.MediaCodecInfo.CodecProfileLevel().apply {
                        profile=if(mime=="video/hevc") android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain else android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                        level=if(mime=="video/hevc") android.media.MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5 else android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel52
                    })).build()
                org.robolectric.util.ReflectionHelpers.setField(caps,"mMaxSupportedInstances",instances)
                assertEquals(instances,caps.maxSupportedInstances)
                org.robolectric.shadows.ShadowMediaCodecList.addCodec(org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder()
                    .setName(name).setIsEncoder(false).setCapabilities(caps).build())
            }
            add("avc","video/avc",816,2);add("hevc.visible.only","video/hevc",810,1)
            assertFalse(CarPlayVideoQualification.selectCluster(2000,810,true,CarPlayVideoQualification.androidProbe()).hevcOffered)
            assertEquals(false,CarPlayVideoQualification.androidBudgetProbe().supports(VideoCodec.H265,"hevc.visible.only","hevc.visible.only"))
            assertNull(CarPlayVideoQualification.androidBudgetProbe().supports(VideoCodec.H265,"missing","missing"))
            org.robolectric.shadows.ShadowMediaCodecList.reset()
            add("avc","video/avc",816,2);add("hevc.padded","video/hevc",816,2)
            val selected=CarPlayVideoQualification.selectCluster(2000,810,true,CarPlayVideoQualification.androidProbe())
            assertEquals("hevc.padded",selected.hevcDecoderName)
            assertEquals(true,CarPlayVideoQualification.androidBudgetProbe().supports(VideoCodec.H265,"hevc.padded","hevc.padded"))
        } finally { org.robolectric.shadows.ShadowMediaCodecList.reset() }
    }
    @Test fun explicitAvcPreferenceNeverOffersExperimentalHevc() {
        assertFalse(CarPlayVideoQualification.selectCluster(2000,810,false,probe).hevcOffered)
    }
}
