package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.*
import org.junit.Assert.*
import org.junit.Test

class CarPlayHevcTest {
    private val config = AirPlayConfig("Lab","id","00:11:22:33:44:55","1.0",AirPlayDisplayConfig(1280,720,fps=30),hevc=true,lpcmOnly=true)
    @Test fun realRawHevcRecordWithFourccInsideSeiIsNotMistakenForABox() {
        val record=GeneratedHevcFixture.hvcc.apply { this[22]=4 }
        val sei=byteArrayOf(78,1)+"avcC".toByteArray(Charsets.US_ASCII)
        val bytes=record+byteArrayOf(39,0,1,0,sei.size.toByte())+sei
        val (codec,data)=ScreenCodec.detectConfig(bytes)
        assertEquals(VideoCodec.H265,codec); assertArrayEquals(bytes,data)
    }
    @Test fun realHevcBoxHonorsBoundariesAndRetainsGeneratedSpsDimensions() {
        val bytes=GeneratedHevcFixture.hvcc
        val box=java.nio.ByteBuffer.allocate(8+bytes.size).putInt(8+bytes.size)
            .put("hvcC".toByteArray(Charsets.US_ASCII)).put(bytes).array()
        val (codec,data)=ScreenCodec.detectConfig(box+byteArrayOf(0,0,0,8)+"free".toByteArray())
        assertEquals(VideoCodec.H265,codec); assertArrayEquals(bytes,data)
        val parsed=MediaCodecSupport.hevcConfiguration(data)!!
        val sps=MediaCodecSupport.hevcSpsInfo(parsed.sps.single())!!
        assertEquals(1280,sps.width); assertEquals(720,sps.height)
        assertEquals(1,sps.profileIdc); assertEquals(93,sps.levelIdc)
    }
    @Test fun configBooleanCannotAdvertiseUnqualifiedHevc() {
        assertFalse(AirPlayInfoPlist.build(config).containsKey("hevcInfo"))
    }
    @Test fun setupMustNotEnableHevcWithoutQualificationAndProposal() {
        assertFalse(setupEnabledFeatures(config,listOf("hevc")).contains("hevc"))
        assertFalse(setupEnabledFeatures(config,null).contains("hevc"))
    }
    private fun record(types: List<Int> = listOf(32,33,34)): ByteArray {
        val header = ByteArray(23).apply { this[0]=1; this[1]=1; this[12]=120; this[13]=0xf0.toByte(); this[15]=0xfc.toByte(); this[16]=0xfd.toByte(); this[17]=0xf8.toByte(); this[18]=0xf8.toByte(); this[21]=3; this[22]=types.size.toByte() }
        return header + types.flatMap { listOf(it.toByte(),0,1,0,2,(it shl 1).toByte(),1) }.toByteArray()
    }
    @Test fun invalidHevcHeadersCannotAnchorReferenceChain() {
        assertFalse(MediaCodecSupport.isRandomAccess(byteArrayOf(0,0,0,1,0xa0.toByte(),1),VideoCodec.H265))
        assertFalse(MediaCodecSupport.isRandomAccess(byteArrayOf(0,0,0,1,0x26,0),VideoCodec.H265))
        for (type in 16..21) assertTrue(MediaCodecSupport.isRandomAccess(byteArrayOf(0,0,1,(type shl 1).toByte(),1),VideoCodec.H265))
    }
    @Test fun avcTruncationCannotExposePartialParameterSets() {
        val avcc=byteArrayOf(1,100,0,31,0xff.toByte(),0xe2.toByte(),0,2,0x67,1,0,2,0x67,2,1,0,2,0x68,1)
        val (sps,pps)=MediaCodecSupport.avcParameterSets(avcc.copyOf(avcc.size-1))
        assertEquals(0,sps.size); assertEquals(0,pps.size)
    }
    @Test fun incompleteHevcParameterSetsCannotInitializeDecoder() {
        assertEquals(0,MediaCodecSupport.hevcCodecSpecificData(record(listOf(33,34))).size)
    }
    @Test fun malformedUnknownConfigIsNotImplicitHevc() {
        assertThrows(IllegalArgumentException::class.java) { ScreenCodec.detectConfig(byteArrayOf(1,2,3)) }
    }
    @Test fun completeHevcProducesOnlyVpsSpsPpsAnnexB() {
        val expected = listOf(32,33,34).flatMap { listOf<Byte>(0,0,0,1,(it shl 1).toByte(),1) }.toByteArray()
        assertArrayEquals(expected, MediaCodecSupport.hevcCodecSpecificData(record()))
    }
    @Test fun truncationTrailingGarbageAndMismatchedNalTypeFailClosed() {
        val bytes=record()
        for (n in bytes.indices) assertEquals("prefix $n",0,MediaCodecSupport.hevcCodecSpecificData(bytes.copyOf(n)).size)
        assertEquals(0,MediaCodecSupport.hevcCodecSpecificData(bytes+byteArrayOf(0)).size)
        bytes[28]=0
        assertEquals(0,MediaCodecSupport.hevcCodecSpecificData(bytes).size)
    }
}
