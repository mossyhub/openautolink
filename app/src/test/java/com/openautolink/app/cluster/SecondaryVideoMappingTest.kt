package com.openautolink.app.cluster

import com.openautolink.app.secondary.SecondaryOutputId
import com.openautolink.app.secondary.SecondaryRenderTarget
import com.openautolink.app.secondary.SecondaryVideoMapping
import org.junit.Assert.*
import org.junit.Test

class SecondaryVideoMappingTest {
    private fun target(output: PixelRect = PixelRect(0, 0, 1806, 680),
        safe: PixelRect = PixelRect(524, 100, 1276, 604)) = SecondaryRenderTarget(
        SecondaryOutputId("public-external"), 7, 42, PixelSize(1806, 680), output, safe)

    @Test fun publicAaUsesCenteredEvenCropAndConservativeDrawableInsets() {
        val mapping = SecondaryVideoMapping.androidAuto(target())
        assertEquals(PixelSize(1280, 720), mapping.codedSize)
        assertEquals(PixelRect(0, 119, 1280, 601), mapping.codecCrop)
        assertEquals(PixelInsets(0, 119, 0, 119), mapping.codecMargins)
        assertEquals(30, mapping.framesPerSecond)
        val safe = mapping.drawableToHost.forward(mapping.safeInDrawable)
        assertTrue(safe.left >= mapping.target.safe.left && safe.top >= mapping.target.safe.top)
        assertTrue(safe.right <= mapping.target.safe.right && safe.bottom <= mapping.target.safe.bottom)
        assertEquals(mapping.phoneSafeInsets, mapping.stableContentInsets)
        assertEquals(mapping.safeInDrawable, mapping.phoneSafeInsets.applyTo(mapping.drawableSize))
        val encoded = mapping.encodedToHost.forward(0.0, 119.0)
        assertEquals(mapping.destination.left, encoded.x, 1e-8)
        assertEquals(mapping.destination.top, encoded.y, 1e-8)
    }

    @Test fun gmFacadeRetainsExactNumbersAndReceiptWithoutMakingPublicGm() {
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val host = ClusterHostGeometry(9, profile.panel, profile.panel.bounds, profile.panel.bounds)
        val qualified = ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role, 9)), host)!!
        val old = ClusterVideoGeometry.androidAuto(qualified)
        val target = SecondaryRenderTarget.fromQualifiedCluster(qualified, SecondaryOutputId("gm"), 3)
        val general = SecondaryVideoMapping.androidAuto(target)
        assertSame(qualified, old.qualifiedDisplay)
        assertSame(qualified, old.gmReceipt)
        assertNull(general.gmReceipt)
        assertEquals(old.codecCrop, general.codecCrop)
        assertEquals(old.destination, general.destination)
        assertEquals(old.phoneSafeInsets, general.phoneSafeInsets)
        assertEquals(qualified.effectiveOutput, target.output)
        assertEquals(qualified.safe, target.safe)
    }

    @Test fun carPlayIsOnly1280x480AndRejectsEssentialSafeInLetterbox() {
        assertEquals(PixelSize(1280, 480), SecondaryVideoMapping.carPlay(target()).codedSize)
        val full = SecondaryRenderTarget(SecondaryOutputId("square"), 1, 5,
            PixelSize(1000, 1000), PixelRect(0, 0, 1000, 1000), PixelRect(0, 0, 1000, 1000))
        assertThrows(IllegalArgumentException::class.java) { SecondaryVideoMapping.carPlay(full) }
    }

    @Test fun targetRejectsInvalidContainmentIdentityAndEpoch() {
        assertThrows(IllegalArgumentException::class.java) { target(safe = PixelRect(0, 0, 1807, 680)) }
        assertThrows(IllegalArgumentException::class.java) { target().copy(attachmentEpoch = -1) }
        assertThrows(IllegalArgumentException::class.java) { target().copy(displayId = -1) }
        assertThrows(IllegalArgumentException::class.java) { target().copy(output = PixelRect(0, 0, 1807, 680)) }
    }

    @Test fun heightLimitedAaPreservesUniformScaleWithAsymmetricCropOrigin() {
        val target = SecondaryRenderTarget(SecondaryOutputId("portrait"), 1, 3,
            PixelSize(601, 1000), PixelRect(0, 0, 601, 1000), PixelRect(10, 10, 591, 990))
        val map = SecondaryVideoMapping.androidAuto(target)
        assertEquals(0, map.codecCrop.width % 2)
        assertEquals(720, map.codecCrop.height)
        val p = map.encodedToHost.forward(map.codecCrop.left + 12.0, 24.0)
        val inverse = map.encodedToHost.inverse(p.x, p.y)
        assertEquals(map.codecCrop.left + 12.0, inverse.x, 1e-8)
        assertEquals(24.0, inverse.y, 1e-8)
        assertEquals(map.destination.width / map.codecCrop.width,
            map.destination.height / map.codecCrop.height, 1e-8)
    }
}
