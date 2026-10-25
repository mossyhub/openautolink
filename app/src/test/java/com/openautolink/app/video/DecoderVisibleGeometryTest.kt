package com.openautolink.app.video

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderVisibleGeometryTest {
    @Test fun `requested frame headers are not visible output before codec reports a crop`() {
        val decoder = MediaCodecDecoder()
        fun field(name: String) = decoder.javaClass.getDeclaredField(name).apply { isAccessible = true }
        field("pendingWidth").set(decoder, 1920)
        field("pendingHeight").set(decoder, 1080)
        @Suppress("UNCHECKED_CAST")
        val stats = field("_stats").get(decoder) as MutableStateFlow<VideoStats>
        decoder.javaClass.getDeclaredMethod("updateStats", String::class.java)
            .apply { isAccessible = true }.invoke(decoder, null)
        assertEquals("Until output-format receipt, mapping must fall back to selected tier", 0, stats.value.width)
        assertEquals(0, stats.value.height)
    }

    @Test fun `periodic stats cannot overwrite a known visible crop with requested size`() {
        val decoder = MediaCodecDecoder()
        fun field(name: String) = decoder.javaClass.getDeclaredField(name).apply { isAccessible = true }
        field("pendingWidth").set(decoder, 1920)
        field("pendingHeight").set(decoder, 1080)
        @Suppress("UNCHECKED_CAST")
        val stats = field("_stats").get(decoder) as MutableStateFlow<VideoStats>
        stats.value = VideoStats(width = 1280, height = 720)
        decoder.javaClass.getDeclaredMethod("updateStats", String::class.java)
            .apply { isAccessible = true }.invoke(decoder, null)
        assertEquals(1280, stats.value.width)
        assertEquals(720, stats.value.height)
    }
}
