package com.openautolink.app.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeedIdrPolicyTest {

    @Test
    fun `archive-derived defaults separate startup placeholders from real keyframes`() {
        val thresholds = SeedIdrThresholds()

        assertEquals(10_000, thresholds.h264Bytes)
        assertEquals(4_096, thresholds.h265Bytes)
        assertEquals(0, thresholds.vp9Bytes)

        assertTrue(SeedIdrPolicy.isSeed("h264", 8_176, thresholds))
        assertFalse(SeedIdrPolicy.isSeed("h264", 10_000, thresholds))

        assertTrue(SeedIdrPolicy.isSeed("h265", 1_075, thresholds))
        assertTrue(SeedIdrPolicy.isSeed("hevc", 898, thresholds))
        assertFalse(SeedIdrPolicy.isSeed("h265", 43_026, thresholds))

        assertFalse(SeedIdrPolicy.isSeed("vp9", 1, thresholds))
    }

    @Test
    fun `per-codec overrides are independent and nonnegative`() {
        val thresholds = SeedIdrThresholds(
            h264Bytes = 12_000,
            h265Bytes = 2_000,
            vp9Bytes = 500,
        )

        assertEquals(12_000, SeedIdrPolicy.thresholdBytes("avc", thresholds))
        assertEquals(2_000, SeedIdrPolicy.thresholdBytes("hevc", thresholds))
        assertEquals(500, SeedIdrPolicy.thresholdBytes("vp9", thresholds))
        assertEquals(12_000, SeedIdrPolicy.thresholdBytes("unknown", thresholds))

        assertEquals("h264", SeedIdrPolicy.codecForAaType(3))
        assertEquals("vp9", SeedIdrPolicy.codecForAaType(5))
        assertEquals("h265", SeedIdrPolicy.codecForAaType(7))
        assertEquals(null, SeedIdrPolicy.codecForAaType(6))
        assertEquals(
            500,
            SeedIdrPolicy.thresholdBytes(
                SeedIdrPolicy.codecForAaType(5)!!,
                thresholds,
            ),
        )

        assertTrue(SeedIdrPolicy.isSeed("vp9", 499, thresholds))
        assertFalse(SeedIdrPolicy.isSeed("vp9", 500, thresholds))
        assertEquals(0, SeedIdrPolicy.sanitizeThreshold(-1))
        assertEquals(1_000_000, SeedIdrPolicy.sanitizeThreshold(1_500_000))
    }
}
