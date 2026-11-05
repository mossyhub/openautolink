package com.openautolink.app.cluster

import org.junit.Assert.*
import org.junit.Test

class ClusterDisplayProfileTest {
    private val identity = ClusterVehicleIdentity("aegean_orange", 32, "VCUSR5-163", 239)
    private val bindings = listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7))
    private val host = ClusterHostGeometry(7, PixelSize(2000, 810), PixelRect(0, 135, 2000, 810), PixelRect(0, 135, 2000, 810))

    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }

    @Test fun pixelEdgesRejectEmptyNegativeInvertedAndUnboundedValues() {
        invalid { PixelRect(0, 0, 0, 1) }
        invalid { PixelRect(0, 1, 1, 1) }
        invalid { PixelRect(-1, 0, 1, 1) }
        invalid { PixelRect(2, 0, 1, 1) }
        invalid { PixelRect(0, 0, Int.MAX_VALUE, 1) }
        invalid { PixelSize(0, 1) }
        invalid { PixelSize(1, Int.MAX_VALUE) }
        assertNull(PixelRect(0, 0, 10, 10).intersect(PixelRect(10, 0, 20, 10)))
        assertEquals(PixelRect(5, 6, 10, 10), PixelRect(0, 0, 10, 10).intersect(PixelRect(5, 6, 20, 20)))
    }

    @Test fun profileAndHostRequireContainedNonemptyAreas() {
        invalid { ClusterDisplayProfiles.AEGEAN_ORANGE_239.copy(safe = PixelRect(0, 0, 2000, 810)) }
        invalid { ClusterDisplayProfiles.AEGEAN_ORANGE_239.copy(panel = PixelSize(100, 100)) }
        invalid { ClusterDisplayProfiles.AEGEAN_ORANGE_239.copy(source = "") }
        invalid { host.copy(visible = PixelRect(0, 0, 2001, 810)) }
        invalid { host.copy(displayId = -1) }
        invalid { ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, -1) }
    }

    @Test fun sourcedVehicleDefaultDoesNotRequireExactFirmware() {
        assertNull(ClusterDisplayProfiles.qualify(identity.copy(product = "other_gm"), bindings, host))
        for (updated in listOf(identity.copy(sdk = 34), identity.copy(buildFamily = ""),
            identity.copy(buildFamily = "VCUSR5-164"), identity.copy(buildRevision = 0))) {
            assertNotNull(ClusterDisplayProfiles.qualify(updated, bindings, host))
        }
    }

    @Test fun ambiguousRoleDuplicateIdAndWrongHostAreWithheld() {
        val cluster = bindings.single()
        for (ambiguous in listOf(emptyList(), listOf(cluster, cluster),
            listOf(cluster, cluster.copy(displayId = 8)),
            listOf(cluster, ClusterDisplayBinding(ClusterVehicleRole.CENTER, 7)),
            listOf(ClusterDisplayBinding(ClusterVehicleRole.CENTER, 7)))) {
            assertNull(ClusterDisplayProfiles.qualify(identity, ambiguous, host))
        }
        assertNull(ClusterDisplayProfiles.qualify(identity, bindings, host.copy(displayId = 2)))
        assertNotNull(ClusterDisplayProfiles.qualify(identity,
            bindings + ClusterDisplayBinding(ClusterVehicleRole.CENTER, 0), host))
    }

    @Test fun hostChangesAreRequalifiedAndSafeCannotBeSilentlyClipped() {
        val changed = host.copy(visible = PixelRect(100, 140, 1800, 800), stable = PixelRect(120, 150, 1900, 700))
        val qualified = requireNotNull(ClusterDisplayProfiles.qualify(identity, bindings, changed))
        assertEquals("Background uses visible intersection, not stable essential bounds",
            PixelRect(100, 140, 1800, 745), qualified.effectiveOutput)
        assertEquals(PixelRect(120, 150, 1800, 700),
            qualified.effectiveOutput.intersect(changed.stable))
        assertEquals(qualified.profile.safe, qualified.safe)
        assertTrue(requireNotNull(qualified.effectiveOutput.intersect(changed.stable)).contains(qualified.safe))
        assertNull(ClusterDisplayProfiles.qualify(identity, bindings, host.copy(visible = PixelRect(0, 170, 2000, 810))))
        assertNull(ClusterDisplayProfiles.qualify(identity, bindings, host.copy(stable = PixelRect(0, 0, 2000, 100))))
        val resized = ClusterHostGeometry(7, PixelSize(1000, 405), PixelRect(0, 0, 1000, 405), PixelRect(0, 0, 1000, 405))
        assertNull(ClusterDisplayProfiles.qualify(identity, bindings, resized))
    }

    @Test fun ordinaryZeroInsetsAreNotUsedToReplaceSourcedSafeBounds() {
        val full = host.copy(visible = host.surfaceSize.bounds, stable = host.surfaceSize.bounds)
        val result = requireNotNull(ClusterDisplayProfiles.qualify(identity, bindings, full))
        assertEquals(result.profile.output, result.effectiveOutput)
        assertEquals(PixelRect(622, 165, 1374, 669), result.safe)
    }

    @Test fun qualifiedReceiptCannotBypassHostAndSafeValidation() {
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        invalid { QualifiedClusterDisplay(profile, host, PixelRect(0, 0, 2000, 810)) }
        invalid { QualifiedClusterDisplay(profile, host, PixelRect(98, 170, 1904, 745)) }
    }

    @Test fun qualifiedProfileRetainsWideOutputAndEssentialSafeArea() {
        val qualified = requireNotNull(ClusterDisplayProfiles.qualify(identity, bindings, host))
        assertEquals(PixelRect(98, 65, 1904, 745), qualified.profile.output)
        assertEquals(PixelRect(98, 135, 1904, 745), qualified.effectiveOutput)
        assertEquals(PixelRect(622, 165, 1374, 669), qualified.safe)
        assertNotEquals(qualified.effectiveOutput, qualified.safe)
        assertEquals(7, qualified.displayId)
    }
}
