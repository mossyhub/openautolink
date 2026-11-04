package com.openautolink.app.secondary

import com.openautolink.app.cluster.ClusterDisplayMode
import com.openautolink.app.cluster.ClusterOutputSelection
import com.openautolink.app.data.ClusterOutputPreferences
import org.junit.Assert.*
import org.junit.Test

class SecondaryDisplayContractsTest {
    private val id = SecondaryOutputId(" Raw key Ω ")
    private val legacy = ClusterOutputPreferences(ClusterOutputSelection(), ClusterDisplayMode.NATIVE_ICONS)
    private fun descriptor() = SecondaryDisplayDescriptor(id, 2, 0, "Unknown", 1280, 720, 160,
        SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Unknown")
    private fun rejects(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun identitiesPreserveProducerBytesWithoutInventingASecondFormat() {
        assertEquals(" Raw key Ω ", id.value)
        assertEquals(512, SecondaryOutputId("x".repeat(512)).value.length)
        listOf("", "  \t\n", "x".repeat(513)).forEach { key -> rejects { SecondaryOutputId(key) } }
        assertEquals(SecondaryOutputPreference(false, null, false, false, null), SecondaryOutputPreference())
    }
    @Test fun descriptorRejectsInvalidLocatorsEpochsAndGeometry() {
        val d = descriptor()
        rejects { d.copy(displayId = -1) }; rejects { d.copy(attachmentEpoch = -1) }
        rejects { d.copy(width = 0) }; rejects { d.copy(height = -1) }; rejects { d.copy(densityDpi = 0) }
        rejects { SecondaryInventorySnapshot(-1, emptyList()) }
        rejects { SecondaryPreferencesSnapshot(-1, emptyMap(), legacy) }
        rejects { SecondaryOutputStatus(id, received = -1) }
    }
    @Test fun snapshotsAndCopiesDefensivelyFreezeCollectionsIncludingMutationByJavaCast() {
        val map = linkedMapOf(id to SecondaryOutputPreference())
        val list = arrayListOf(descriptor())
        val prefs = SecondaryPreferencesSnapshot(0, map, legacy)
        val inventory = SecondaryInventorySnapshot(0, list)
        map.clear(); list.clear()
        assertEquals(1, prefs.outputs.size); assertEquals(1, inventory.displays.size)
        assertThrows(UnsupportedOperationException::class.java) { (prefs.outputs as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (inventory.displays as MutableList).clear() }
        val replacement = linkedMapOf(id to SecondaryOutputPreference(enabled = true))
        val copy = prefs.copy(outputs = replacement)
        replacement.clear()
        assertTrue(copy.outputs.getValue(id).enabled)
        assertEquals(prefs, prefs.copy()); assertEquals(prefs.hashCode(), prefs.copy().hashCode())
        assertEquals(inventory, inventory.copy()); assertEquals(inventory.hashCode(), inventory.copy().hashCode())
    }
    @Test fun relativeLayoutIsFiniteBoundedAndSafeIsContainedWithoutAnyGmRole() {
        val full = SecondaryLayoutBox(0.0, 0.0, 1.0, 1.0)
        val scope = SecondaryLayoutScope(id, "vehicle", "build", 1280, 720, 160)
        val layout = SecondaryLayoutPreference(scope, full, SecondaryLayoutBox(.1, .2, .8, .9))
        assertTrue(layout.output.contains(layout.safe))
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.01, 1.01).forEach { invalid ->
            rejects { full.copy(left = invalid) }
        }
        rejects { full.copy(right = 0.0) }
        rejects { SecondaryLayoutPreference(scope, SecondaryLayoutBox(.2, .2, .8, .8), full) }
        rejects { scope.copy(panelWidth = 0) }; rejects { scope.copy(panelHeight = -1) }
        rejects { scope.copy(densityDpi = 0) }; rejects { scope.copy(vehicle = " ") }
        rejects { scope.copy(buildFingerprint = "x".repeat(513)) }
    }
}
