package com.openautolink.app.cluster

import org.junit.Assert.*
import org.junit.Test

class ClusterVideoGeometryTest {
    private fun qualified(top: Int = 65): QualifiedClusterDisplay = requireNotNull(ClusterDisplayProfiles.qualify(
        ClusterVehicleIdentity("aegean_orange", 32, "VCUSR5-163", 239),
        listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
        ClusterHostGeometry(7, PixelSize(2000, 810), PixelRect(0, top, 2000, 810), PixelRect(0, top, 2000, 810)),
    ))

    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }

    @Test fun clippedHostRecomputesAlignedDrawableRatherThanReusingFullProfile() {
        val plan = ClusterVideoGeometry.androidAuto(qualified(135))
        assertEquals(PixelRect(0, 144, 1280, 576), plan.codecCrop)
        assertEquals(PixelSize(1280, 432), plan.drawableSize)
        assertEquals(PixelRect(372, 22, 904, 378), plan.safeInDrawable)
        assertEquals(PixelInsets(372, 22, 376, 54), plan.phoneSafeInsets)
        assertEquals(plan.phoneSafeInsets, plan.stableContentInsets)
        assertEquals(plan.safeInDrawable, plan.phoneSafeInsets.applyTo(plan.drawableSize))
        assertEquals(PixelRect(98, 136, 1904, 744), plan.destinationPixels)
        assertSafeMapsInsideProfile(plan)
    }

    @Test fun carPlayUsesExactLetterboxOffsetsAndNoCodecOrMainScreenInset() {
        val plan = ClusterVideoGeometry.carPlay(qualified(135))
        assertEquals(PixelSize(1280, 480), plan.drawableSize)
        assertEquals(PixelInsets.ZERO, plan.codecMargins)
        assertEquals(187.6666666666667, plan.destination.left, 1e-9)
        assertEquals(135.0, plan.destination.top, 1e-9)
        assertEquals(PixelRect(342, 24, 933, 420), plan.safeInDrawable)
        assertEquals(PixelInsets(342, 24, 347, 60), plan.phoneSafeInsets)
        assertEquals(PixelRect(188, 135, 1814, 745), plan.destinationPixels)
        assertSafeMapsInsideProfile(plan)
        val full = ClusterVideoGeometry.carPlay(qualified())
        assertEquals(PixelRect(372, 70, 904, 427), full.safeInDrawable)
        assertEquals(66.375, full.destination.top, 1e-9)
    }

    private fun assertSafeMapsInsideProfile(plan: ClusterVideoMapping) {
        val mapped = plan.drawableToHost.forward(plan.safeInDrawable)
        val safe = plan.qualifiedDisplay.safe
        assertTrue(mapped.left >= safe.left && mapped.top >= safe.top)
        assertTrue(mapped.right <= safe.right && mapped.bottom <= safe.bottom)
        assertTrue(plan.qualifiedDisplay.effectiveOutput.contains(plan.destinationPixels))
    }

    @Test fun cropOriginAndAsymmetricInsetsAreAppliedExactlyOnce() {
        val transform = ClusterVideoTransform.aspectFit(PixelRect(20, 30, 120, 80), PixelRect(100, 200, 500, 500))
        assertEquals(VideoRect(100.0, 250.0, 500.0, 450.0), transform.destination)
        assertEquals(VideoPoint(100.0, 250.0), transform.forward(20.0, 30.0))
        assertEquals(VideoPoint(120.0, 80.0), transform.inverse(500.0, 450.0))
        assertEquals(PixelRect(30, 40, 100, 70), transform.inverseInward(PixelRect(140, 290, 420, 410)))
        val insets = PixelInsets.around(PixelSize(100, 50), PixelRect(10, 5, 80, 40))
        assertEquals(PixelInsets(10, 5, 20, 10), insets)
        assertEquals(PixelRect(10, 5, 80, 40), insets.applyTo(PixelSize(100, 50)))
    }

    @Test fun conservativeRoundingNeverExpandsSafeEdges() {
        assertEquals(PixelRect(2, 3, 10, 20), VideoRect(1.01, 2.01, 10.99, 20.99).inward())
        val transform = ClusterVideoTransform.aspectFit(PixelRect(0, 0, 100, 100), PixelRect(10, 20, 310, 320))
        assertEquals(PixelRect(1, 1, 99, 99), transform.inverseInward(PixelRect(11, 21, 309, 319)))
        invalid { VideoRect(1.1, 1.1, 1.9, 1.9).inward() }
    }

    @Test fun geometryRejectsNonfiniteOutOfRangeEmptyAndMisalignedInputs() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            invalid { VideoPoint(bad, 0.0) }
            invalid { VideoRect(0.0, 0.0, bad, 20.0) }
            invalid { ClusterVideoGeometry.nearestAligned(bad, 2, 720) }
            invalid { ClusterVideoGeometry.carPlay(qualified()).drawableToHost.forward(bad, 0.0) }
            invalid { ClusterVideoGeometry.carPlay(qualified()).drawableToHost.inverse(0.0, bad) }
        }
        invalid { VideoRect(0.0, 0.0, 0.0, 20.0) }
        invalid { VideoRect(-1.0, 0.0, 10.0, 20.0) }
        invalid { VideoRect(0.0, 0.0, 32769.0, 20.0) }
        invalid { PixelInsets(-1, 0, 0, 0) }
        invalid { PixelInsets(60, 0, 40, 0).applyTo(PixelSize(100, 50)) }
        invalid { PixelInsets.around(PixelSize(10, 10), PixelRect(0, 0, 11, 10)) }
        invalid { ClusterVideoGeometry.nearestAligned(482.0, 0, 720) }
        invalid { ClusterVideoGeometry.nearestAligned(482.0, 2, 719) }
        invalid { ClusterVideoGeometry.nearestAligned(0.0, 2, 720) }
        val transform = ClusterVideoTransform.aspectFit(PixelRect(0, 0, 100, 50), PixelRect(0, 0, 100, 100))
        invalid { transform.inverseInward(PixelRect(0, 0, 100, 100)) }
        invalid { transform.forward(PixelRect(0, 0, 101, 50)) }
        assertEquals(482, ClusterVideoGeometry.nearestAligned(481.949, 2, 720))
        assertEquals(720, ClusterVideoGeometry.nearestAligned(721.0, 2, 720))
    }

    @Test fun narrowHostUsesHorizontalCodecPaddingWithoutStretching() {
        val display = requireNotNull(ClusterDisplayProfiles.qualify(
            ClusterVehicleIdentity("aegean_orange", 32, "VCUSR5-163", 239),
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
            ClusterHostGeometry(7, PixelSize(2000, 810), PixelRect(500, 65, 1500, 745), PixelRect(0, 0, 2000, 810)),
        ))
        val plan = ClusterVideoGeometry.androidAuto(display)
        assertEquals(PixelRect(111, 0, 1169, 720), plan.codecCrop)
        assertEquals(PixelInsets(111, 0, 111, 0), plan.codecMargins)
        assertEquals(PixelRect(129, 106, 925, 639), plan.safeInDrawable)
        assertSafeMapsInsideProfile(plan)
    }

    @Test fun hostChangesThatPutEssentialContentInLetterboxAreRejected() {
        val tight = requireNotNull(ClusterDisplayProfiles.qualify(
            ClusterVehicleIdentity("aegean_orange", 32, "VCUSR5-163", 239),
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
            ClusterHostGeometry(7, PixelSize(2000, 810), PixelRect(622, 135, 1374, 745), PixelRect(0, 0, 2000, 810)),
        ))
        invalid { ClusterVideoGeometry.carPlay(tight) }
    }

    @Test fun acceptedHostAreaSweepPreservesAspectBoundsAndSafeContainment() {
        for (top in listOf(65, 100, 135, 150)) {
            for (right in listOf(1700, 1800, 1904)) {
                val display = requireNotNull(ClusterDisplayProfiles.qualify(
                    ClusterVehicleIdentity("aegean_orange", 32, "VCUSR5-163", 239),
                    listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
                    ClusterHostGeometry(7, PixelSize(2000, 810), PixelRect(98, top, right, 745), PixelRect(0, 0, 2000, 810)),
                ))
                for (plan in listOf(ClusterVideoGeometry.androidAuto(display), ClusterVideoGeometry.carPlay(display))) {
                    assertSafeMapsInsideProfile(plan)
                    assertEquals(plan.drawableSize.width.toDouble() / plan.drawableSize.height,
                        plan.destination.width / plan.destination.height, 1e-12)
                    assertTrue(plan.destinationPixels.width > display.safe.width)
                    val source = plan.encodedToHost.source
                    val point = plan.encodedToHost.forward(source.left + source.width * 0.37, source.top + source.height * 0.73)
                    val inverse = plan.encodedToHost.inverse(point.x, point.y)
                    assertEquals(source.left + source.width * 0.37, inverse.x, 1e-9)
                    assertEquals(source.top + source.height * 0.73, inverse.y, 1e-9)
                }
            }
        }
    }

    @Test fun aaFullProfileHasAligned482DrawableAndSeparate119CodecPadding() {
        val plan = ClusterVideoGeometry.androidAuto(qualified())
        assertEquals(PixelSize(1280, 720), plan.codedSize)
        assertEquals(PixelRect(0, 119, 1280, 601), plan.codecCrop)
        assertEquals(PixelInsets(0, 119, 0, 119), plan.codecMargins)
        assertEquals(PixelSize(1280, 482), plan.drawableSize)
        assertTrue(plan.phoneSafeInsets.left > 0)
        assertEquals(30, plan.framesPerSecond)
        val encoded = plan.encodedToHost.forward(0.0, 119.0)
        val drawable = plan.drawableToHost.forward(0.0, 0.0)
        assertEquals(drawable.x, encoded.x, 1e-9)
        assertEquals(drawable.y, encoded.y, 1e-9)
        val inverse = plan.encodedToHost.inverse(encoded.x, encoded.y)
        assertEquals(0.0, inverse.x, 1e-9)
        assertEquals(119.0, inverse.y, 1e-9)
    }
}
