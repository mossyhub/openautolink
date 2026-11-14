package com.openautolink.app.data

import com.openautolink.app.cluster.ClusterOutputSelection
import com.openautolink.app.secondary.*
import com.openautolink.carplaylab.media.CarPlayClusterContent
import org.junit.Assert.*
import org.junit.Test

class SecondaryCarPlayContentTest {
    private val legacy = ClusterOutputPreferences(ClusterOutputSelection(), null)
    private val id = SecondaryOutputId("a")
    private fun raw(value: String? = null) = """{"version":1,"revision":0,"outputs":{"a":{"enabled":true,"aaRole":"AUXILIARY","androidAuto":false,"carPlay":false,"layout":null${value?.let { ",\"carPlayContent\":$it" } ?: ""}}}}"""

    @Test fun absentMeansDefaultAndLegacyBytesAreUnchanged() {
        val snapshot = SecondaryDisplayPreferences.read(raw(), legacy)
        assertNull(snapshot.error)
        assertNull(snapshot.outputs.getValue(id).carPlayContent)
        assertEquals(raw(), SecondaryDisplayPreferences.encode(snapshot))
        assertEquals(raw(), SecondaryDisplayPreferences.encode(SecondaryDisplayPreferences.read(raw("null"), legacy)))
    }

    @Test fun everyChoiceRoundTrips() {
        for (content in CarPlayClusterContent.entries) {
            val snapshot = SecondaryDisplayPreferences.read(raw("\"${content.name}\""), legacy)
            assertNull(snapshot.error)
            assertEquals(content, snapshot.outputs.getValue(id).carPlayContent)
            assertEquals(raw("\"${content.name}\""), SecondaryDisplayPreferences.encode(snapshot))
        }
    }

    @Test fun unknownOrMistypedValuesFailClosed() {
        for (value in listOf("\"map\"", "\"OTHER\"", "\"\"", "1", "true", "[]", "{}")) {
            val snapshot = SecondaryDisplayPreferences.read(raw(value), legacy)
            assertNotNull("accepted $value", snapshot.error)
            assertTrue(snapshot.outputs.isEmpty())
        }
    }

    @Test fun choiceIsPerOutput() {
        val b = SecondaryOutputId("b")
        val snapshot = SecondaryPreferencesSnapshot(0, mapOf(
            id to SecondaryOutputPreference(carPlayContent = CarPlayClusterContent.MAP),
            b to SecondaryOutputPreference(carPlayContent = CarPlayClusterContent.INSTRUCTION_CARD)), legacy)
        val reopened = SecondaryDisplayPreferences.read(SecondaryDisplayPreferences.encode(snapshot), legacy)
        assertEquals(CarPlayClusterContent.MAP, reopened.outputs.getValue(id).carPlayContent)
        assertEquals(CarPlayClusterContent.INSTRUCTION_CARD, reopened.outputs.getValue(b).carPlayContent)
    }
}
