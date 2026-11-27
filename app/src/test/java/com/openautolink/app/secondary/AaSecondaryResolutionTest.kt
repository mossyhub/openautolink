package com.openautolink.app.secondary

import android.app.Application
import android.graphics.SurfaceTexture
import android.media.MediaCodecInfo
import android.view.Surface
import com.openautolink.app.cluster.*
import com.openautolink.app.data.ClusterOutputPreferences
import com.openautolink.app.data.SecondaryDisplayPreferences
import com.openautolink.app.transport.aasdk.AasdkSecondaryVideoOffer
import com.openautolink.app.ui.settings.aaResolutionLabel
import com.openautolink.app.video.NalParser
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.media.VideoDecoderProbe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], manifest = Config.NONE, application = Application::class)
class AaSecondaryResolutionTest {
    /** A wide 3904x1320 driver display with its default Directions area, as measured on a vehicle. */
    private val wide = PixelSize(3904, 1320)
    private val target = SecondaryRenderTarget(SecondaryOutputId("test.wide"), 1, 2, wide, wide.bounds,
        PixelRect(1089, 114, 3307, 1089))

    @Test fun largerFramesGiveTheWidePanelProportionallyMoreSourcePixels() {
        val standard = SecondaryVideoMapping.androidAuto(target)
        assertEquals(PixelSize(1280, 720), standard.codedSize)
        assertEquals(PixelRect(0, 144, 1280, 576), standard.codecCrop) // unchanged 720p mapping
        val quad = SecondaryVideoMapping.androidAuto(target, AaSecondaryResolution.QUAD_HD.codedSize)
        assertEquals(PixelSize(2560, 1440), quad.codedSize)
        assertEquals(PixelRect(0, 287, 2560, 1153), quad.codecCrop)
        assertEquals(quad.codecCrop.top, 1440 - quad.codecCrop.bottom) // centred, as the offer requires
    }

    @Test fun offerAcceptsOnlyTheAdvertisedSizeAndCodecPairs() {
        fun offer(width: Int, height: Int, codec: Int, channel: Int = 19, display: Int = 2) =
            AasdkSecondaryVideoOffer.create(channel, display, 2, cropRight = width, cropBottom = height,
                codedWidth = width, codedHeight = height, codecType = codec)
        assertEquals(3, AasdkSecondaryVideoOffer.create(19, 2, 2).codecType) // defaults stay 720p H.264
        for (size in AaSecondaryResolution.entries) {
            val accepted = offer(size.codedSize.width, size.codedSize.height, size.codecType)
            assertEquals(size.codedSize, PixelSize(accepted.codedWidth, accepted.codedHeight))
        }
        assertThrows(IllegalArgumentException::class.java) { offer(1920, 1080, 3) } // padded H.264 not offered
        assertThrows(IllegalArgumentException::class.java) { offer(1280, 720, 7) }
        assertThrows(IllegalArgumentException::class.java) { offer(1600, 900, 7) }
        // GM's hosted channel keeps its 720p H.264 contract.
        assertThrows(IllegalArgumentException::class.java) { offer(1920, 1080, 7, channel = 3, display = 1) }
        // A 720p crop inside a larger frame is not centred, so it is rejected.
        assertThrows(IllegalArgumentException::class.java) {
            AasdkSecondaryVideoOffer.create(19, 2, 2, cropTop = 144, cropRight = 1280, cropBottom = 576,
                codedWidth = 2560, codedHeight = 1440, codecType = 7)
        }
    }

    @Test fun stretchIsDisplayWidthOverTheUsableBandOfEachSize() {
        assertEquals(3.05, AaSecondaryResolution.HD.stretchFor(wide), 0.005)
        assertEquals(2.03, AaSecondaryResolution.FULL_HD.stretchFor(wide), 0.005)
        assertEquals(1.525, AaSecondaryResolution.QUAD_HD.stretchFor(wide), 0.005)
        assertEquals(1.017, AaSecondaryResolution.ULTRA_HD.stretchFor(wide), 0.005)
        // A display taller than 16:9 is limited by the frame's height instead of its width.
        assertEquals(2.0, AaSecondaryResolution.HD.stretchFor(PixelSize(1920, 1440)), 0.005)
        assertEquals("1440p · H.265 (1.5x)", aaResolutionLabel(AaSecondaryResolution.QUAD_HD, wide))
        assertEquals("4K · H.265 (1.0x)", aaResolutionLabel(AaSecondaryResolution.ULTRA_HD, wide))
        assertEquals("720p · H.264", aaResolutionLabel(AaSecondaryResolution.HD, null))
    }

    @Test fun missingDecodersStepDownAndEndAt720p() {
        val hevcTo1440 = VideoDecoderProbe { request ->
            if (request.codec == VideoCodec.H265 && request.height <= 1440) "test.hevc" else null
        }
        assertEquals(AaSecondaryVideoSelection(AaSecondaryResolution.QUAD_HD, "test.hevc"), AaSecondaryResolution.ULTRA_HD.select(hevcTo1440))
        assertEquals(AaSecondaryVideoSelection(AaSecondaryResolution.FULL_HD, "test.hevc"), AaSecondaryResolution.FULL_HD.select(hevcTo1440))
        assertEquals(AaSecondaryVideoSelection(AaSecondaryResolution.HD, null), AaSecondaryResolution.ULTRA_HD.select { null })
        assertEquals(AaSecondaryVideoSelection(AaSecondaryResolution.HD, null), AaSecondaryResolution.ULTRA_HD.select { error("probe failure") })
        // 720p never needs the H.265 probe.
        assertEquals(AaSecondaryVideoSelection(AaSecondaryResolution.HD, null), AaSecondaryResolution.HD.select { error("unused") })
    }

    @Test fun densityGrowsWithTheFrameSoTheInterfaceKeepsItsSize() {
        assertEquals(listOf(149, 223, 298, 447), AaSecondaryResolution.entries.map { it.density(149) })
        assertEquals(1000, AaSecondaryResolution.ULTRA_HD.density(400)) // wire limit
    }

    @Test fun savedSizeIsOptionalPerOutputAndUnknownValuesFailClosed() {
        val legacy = ClusterOutputPreferences(ClusterOutputSelection(), null)
        val id = SecondaryOutputId("a")
        fun raw(value: String? = null) = """{"version":1,"revision":0,"outputs":{"a":{"enabled":true,"aaRole":"AUXILIARY","androidAuto":false,"carPlay":false,"layout":null${value?.let { ",\"aaResolution\":$it" } ?: ""}}}}"""
        // Absent means 720p, and records written before this setting keep their exact bytes.
        val absent = SecondaryDisplayPreferences.read(raw(), legacy)
        assertNull(absent.error)
        assertNull(absent.outputs.getValue(id).aaResolution)
        assertEquals(raw(), SecondaryDisplayPreferences.encode(absent))
        for (size in AaSecondaryResolution.entries) {
            val saved = SecondaryDisplayPreferences.read(raw("\"${size.name}\""), legacy)
            assertEquals(size, saved.outputs.getValue(id).aaResolution)
            assertEquals(raw("\"${size.name}\""), SecondaryDisplayPreferences.encode(saved))
        }
        for (value in listOf("\"1440p\"", "\"\"", "7", "{}"))
            assertNotNull("accepted $value", SecondaryDisplayPreferences.read(raw(value), legacy).error)
    }

    @Test fun inBandParameterSetsConfigureTheNamedHevcDecoderAtTheAdvertisedSize() {
        val mapping = SecondaryVideoMapping.androidAuto(target, AaSecondaryResolution.QUAD_HD.codedSize)
        val configured = CompletableFuture<ClusterCodecConfig>()
        val driver = object : ClusterRenderDriver {
            override fun configure(config: ClusterCodecConfig) { configured.complete(config) }
            override fun submit(frame: ClusterEncodedFrame) = true
            override fun poll() = ClusterRenderProgress(0, 0)
            override fun stopDecoder() = Unit
            override fun clearOutput() = Unit
            override fun detach() = true
        }
        val bridge = ClusterProjectionOutput(mapping, ClusterRenderDriverFactory { _, _ -> driver }, "test.avc")
        bridge.aaHevcDecoderName = "test.hevc"
        val lease = ClusterOutputLease(1, 1)
        assertTrue(bridge.bindLease(lease))
        val texture = SurfaceTexture(0); val surface = Surface(texture)
        try {
            assertTrue(bridge.attach(lease, surface, target).get(3, TimeUnit.SECONDS))
            val stream = bridge.beginStream(lease, Any()) { true }!!
            val idr = byteArrayOf(0, 0, 0, 1, 0x26, 1, 0x55)
            // A slice before any parameter sets cannot configure a decoder.
            assertFalse(bridge.annexB(lease, stream, idr, 0))
            // A stream that is not the advertised size is refused rather than stretched.
            assertFalse(bridge.annexB(lease, stream, parameterSets(1920, 1080), 0))
            assertTrue(bridge.annexB(lease, stream, parameterSets(2560, 1440) + idr, 1))
            val config = configured.get(3, TimeUnit.SECONDS)
            assertEquals("test.hevc", config.decoderName)
            assertEquals(VideoCodec.H265, config.codec)
            assertEquals(PixelSize(2560, 1440), config.decoderCodedSize)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, config.profile)
            assertEquals(MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel5, config.level)
            assertEquals(1L, bridge.status.value.configurations)
        } finally {
            assertTrue(bridge.retire(lease).get(3, TimeUnit.SECONDS)); surface.release(); texture.release()
        }
    }

    /** VPS + SPS + PPS as Annex-B. Only the SPS is parsed, so it carries real size fields. */
    private fun parameterSets(width: Int, height: Int): ByteArray {
        val bits = StringBuilder()
        fun u(value: Int, count: Int) { for (i in count - 1 downTo 0) bits.append((value shr i) and 1) }
        fun ue(value: Int) {
            val code = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(code)
            repeat(length - 1) { bits.append('0') }
            u(code, length)
        }
        u(0, 4); u(0, 3); u(1, 1)                        // vps id, max_sub_layers_minus1, nesting
        u(1, 8)                                          // profile space/tier/idc = Main
        repeat(32 + 48) { bits.append('1') }             // compatibility + constraint flags (no zero runs)
        u(150, 8)                                        // general_level_idc
        ue(0); ue(1); ue(width); ue(height)              // sps id, chroma 4:2:0, picture size
        bits.append('1')
        while (bits.length % 8 != 0) bits.append('0')
        val sps = byteArrayOf(0x42, 1) + ByteArray(bits.length / 8) { bits.substring(it * 8, it * 8 + 8).toInt(2).toByte() }
        val start = byteArrayOf(0, 0, 0, 1)
        val sets = start + byteArrayOf(0x40, 1, 0x0c) + start + sps + start + byteArrayOf(0x44, 1, 0x55)
        assertEquals(width to height, NalParser.parseH265SpsResolution(sets))
        return sets
    }
}
