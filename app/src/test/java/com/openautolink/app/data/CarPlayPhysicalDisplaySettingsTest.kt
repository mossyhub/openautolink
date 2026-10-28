package com.openautolink.app.data

import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class CarPlayPhysicalDisplaySettingsTest {
    @Test fun higherResolutionIdsRoundTripAsPreferencesNotCapabilityPromises() {
        for (id in listOf("1440p", "4k")) {
            val settings = CarPlaySettings(resolution = id, fps = 60, widthPhysicalMm = 400)
            assertEquals(settings, CarPlaySettings.decode(CarPlaySettings.encode(settings)))
        }
    }

    @Test fun realRuntimeRestoresSavedPhysicalWidthRatherThanHardcodedScale() {
        val root = generateSequence(java.io.File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .first { java.io.File(it, "app/src/main").isDirectory }
        val vm = java.io.File(root, "app/src/main/java/com/openautolink/app/ui/projection/ProjectionViewModel.kt").readText()
        assertTrue("Lexical runtime wiring guard, not Android execution proof", vm.contains(
            "saved.qualifiedDisplay(viewport)"))
    }

    @Test fun atomicWidthRestorationAcceptsEveryIntegerInProductRange() {
        for (width in com.openautolink.carplaylab.media.CarPlayDisplayPreferences.PHYSICAL_WIDTHS_MM) {
            val restored = CarPlaySettings.decode("""{"widthPhysicalMm":$width,"ssid":" LAN "}""")
            assertEquals(width, restored.widthPhysicalMm)
            assertEquals(" LAN ", restored.ssid)
        }
        for (width in listOf(-1, 0, 99, 2001, Int.MAX_VALUE)) {
            assertEquals(200, CarPlaySettings.decode("""{"widthPhysicalMm":$width}""").widthPhysicalMm)
        }
    }
    @Test fun physicalScaleRemainsInsideOneNonSecretAtomicRecordWithoutChangingAa() = kotlinx.coroutines.runBlocking {
        val state = kotlinx.coroutines.flow.MutableStateFlow(androidx.datastore.preferences.core.emptyPreferences())
        var writes = 0
        val store = object : androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
            override val data = state
            override suspend fun updateData(transform: suspend (androidx.datastore.preferences.core.Preferences) -> androidx.datastore.preferences.core.Preferences) =
                transform(state.value).also { state.value = it; writes++ }
        }
        val preferences = AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        preferences.setAaResolution("1080p")
        val before = writes
        val saved = CarPlaySettings(enabled = true, resolution = "4k", fps = 45, widthPhysicalMm = 350)
        preferences.setCarPlaySettings(saved)
        assertEquals(before + 1, writes)
        assertEquals(saved, preferences.carPlaySettings.first())
        assertEquals("1080p", preferences.aaResolution.first())
        assertEquals(setOf("aa_resolution", "carplay_settings_v1"), state.value.asMap().keys.map { it.name }.toSet())
        val record = state.value[androidx.datastore.preferences.core.stringPreferencesKey("carplay_settings_v1")]!!
        assertFalse(record.contains("passphrase", ignoreCase = true))
        assertFalse(record.contains("dpi", ignoreCase = true))
    }

    @Test fun oldAtomicRecordGainsSourceBackedPhysicalWidthDefault() {
        val restored = CarPlaySettings.decode("""{"enabled":true,"resolution":"1080p","fps":60}""")
        val record = Json.parseToJsonElement(CarPlaySettings.encode(restored)).jsonObject
        assertEquals("200", record["widthPhysicalMm"]?.jsonPrimitive?.content)
        assertTrue(restored.enabled)
        assertEquals("1080p", restored.resolution)
    }
}
