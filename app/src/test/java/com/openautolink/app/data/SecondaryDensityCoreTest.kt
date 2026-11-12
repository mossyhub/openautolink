package com.openautolink.app.data

import com.openautolink.app.cluster.ClusterOutputSelection
import com.openautolink.app.secondary.*
import com.openautolink.app.transport.aasdk.AasdkSecondaryVideoOffer
import java.lang.reflect.Modifier
import org.junit.Assert.*
import org.junit.Test

class SecondaryDensityCoreTest {
    private val legacy = ClusterOutputPreferences(ClusterOutputSelection(), null)
    private val id = SecondaryOutputId("a")
    private fun raw(value: String? = null) = """{"version":1,"revision":0,"outputs":{"a":{"enabled":true,"aaRole":"AUXILIARY","androidAuto":false,"carPlay":false,"layout":null${value?.let { ",\"aaDensityDpi\":$it" } ?: ""}}}}"""

    @Test fun automaticPreservesLegacyBytesAndExplicitNullResets() {
        val automatic = SecondaryDisplayPreferences.read(raw(), legacy)
        assertNull(automatic.outputs.getValue(id).aaDensityDpi)
        assertEquals(raw(), SecondaryDisplayPreferences.encode(automatic))
        assertEquals(raw(), SecondaryDisplayPreferences.encode(SecondaryDisplayPreferences.read(raw("null"), legacy)))
        val custom = SecondaryDisplayPreferences.read(raw("120"), legacy)
        val reset = custom.copy(outputs = custom.outputs.mapValues { it.value.copy(aaDensityDpi = null) })
        assertEquals(raw(), SecondaryDisplayPreferences.encode(reset))
    }

    @Test fun strictIntegerBoundsAndOverflowAreFailClosed() {
        for (value in listOf("80", "120", "320")) {
            val snapshot = SecondaryDisplayPreferences.read(raw(value), legacy)
            assertNull(snapshot.error)
            assertEquals(value.toInt(), snapshot.outputs.getValue(id).aaDensityDpi)
            assertEquals(raw(value), SecondaryDisplayPreferences.encode(snapshot))
        }
        for (value in listOf("79", "321", "0", "-1", "120.0", "1e2", "\"120\"", "true", "[]", "{}", "2147483648", "99999999999999999999999999999")) {
            val snapshot = SecondaryDisplayPreferences.read(raw(value), legacy)
            assertNotNull("accepted $value", snapshot.error)
            assertTrue(snapshot.outputs.isEmpty())
        }
    }

    @Test fun modelRejectsInvalidDensityAndPerOutputResetIsIndependent() {
        for (value in listOf(Int.MIN_VALUE, -1, 0, 79, 321, Int.MAX_VALUE)) {
            try { SecondaryOutputPreference(aaDensityDpi = value); fail("accepted $value") }
            catch (_: IllegalArgumentException) { }
        }
        for (value in listOf(80, 120, 320)) assertEquals(value, SecondaryOutputPreference(aaDensityDpi = value).aaDensityDpi)
        val b = SecondaryOutputId("b")
        val snapshot = SecondaryPreferencesSnapshot(0, mapOf(id to SecondaryOutputPreference(aaDensityDpi = 120),
            b to SecondaryOutputPreference(aaDensityDpi = 240)), legacy)
        val reset = snapshot.copy(outputs = snapshot.outputs + (id to snapshot.outputs.getValue(id).copy(aaDensityDpi = null)))
        val reopened = SecondaryDisplayPreferences.read(SecondaryDisplayPreferences.encode(reset), legacy)
        assertNull(reopened.outputs.getValue(id).aaDensityDpi)
        assertEquals(240, reopened.outputs.getValue(b).aaDensityDpi)
        assertEquals(120, snapshot.outputs.getValue(id).aaDensityDpi)
    }

    @Test fun physicalDensityIsImmutableDefaultsToLogicalAndValidatesSeparately() {
        val automatic = AasdkSecondaryVideoOffer.create(19, 2, 2, density = 240)
        assertEquals(240, automatic.realDensity)
        val custom = AasdkSecondaryVideoOffer.create(19, 2, 2, density = 120, realDensity = 160)
        assertEquals(120, custom.density); assertEquals(160, custom.realDensity)
        assertTrue(Modifier.isFinal(custom.javaClass.getField("realDensity").modifiers))
        for (value in listOf(1, 1000)) assertEquals(value, AasdkSecondaryVideoOffer.create(19, 2, 2, realDensity = value).realDensity)
        for (value in listOf(-1, 0, 1001, Int.MAX_VALUE)) {
            try { AasdkSecondaryVideoOffer.create(19, 2, 2, realDensity = value); fail("accepted $value") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
