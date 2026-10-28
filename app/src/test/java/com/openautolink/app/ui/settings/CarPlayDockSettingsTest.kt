package com.openautolink.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.VideoDecoderProbe
import java.io.File
import java.lang.reflect.Modifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CarPlayDockSettingsTest {
    private fun dock(settings: CarPlaySettings): String {
        val getter = settings.javaClass.methods.firstOrNull { it.name == "getDockPosition" }
        assertNotNull("Persisted dockPosition must be part of the shared next-session settings", getter)
        return getter!!.invoke(settings) as String
    }
    private fun positioned(settings: CarPlaySettings, id: String): CarPlaySettings {
        dock(settings)
        val copy = settings.javaClass.methods.single { it.name == "copy" }
        val fields = settings.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        val args = copy.parameterTypes.indices.map { settings.javaClass.getMethod("component${it + 1}").invoke(settings) }.toTypedArray()
        args[fields.indexOfFirst { it.name == "dockPosition" }] = id
        return copy.invoke(settings, *args) as CarPlaySettings
    }

    @Test fun oldJsonDefaultsAndEveryStableIdRoundTripsWithoutChangingOtherSettings() {
        assertEquals("automatic", dock(CarPlaySettings.decode("{\"resolution\":\"1080p\",\"fps\":59}")))
        for (id in listOf("automatic", "bottom", "driver_side")) {
            val settings = positioned(CarPlaySettings(resolution = "1080p", fps = 59, safeLeft = 120,
                ssid = "preserve-network", autoScale = false), id)
            assertEquals(settings, CarPlaySettings.decode(CarPlaySettings.encode(settings)))
            assertEquals(id, dock(settings))
        }
    }

    @Test fun corruptStoredIdRestoresOnlyAutomaticWhileInteractiveInvalidIsRejected() {
        for (invalid in listOf("", "BOTTOM", "left", "right", "0", " automatic ")) {
            val record = "{\"dockPosition\":\"$invalid\",\"fps\":59,\"safeLeft\":120,\"ssid\":\"preserve\"}"
            val restored = CarPlaySettings.decode(record)
            assertEquals("automatic", dock(restored))
            assertEquals(59, restored.fps); assertEquals(120, restored.safeLeft); assertEquals("preserve", restored.ssid)
            val draft = positioned(restored, invalid)
            assertNotNull("Invalid draft must not be silently sanitized at Save", carPlayEditError(draft))
            assertTrue(carPlayEditError(draft)!!.contains("Display"))
            assertTrue(runCatching { draft.displayRequest() }.exceptionOrNull() is IllegalArgumentException ||
                runCatching { draft.displayRequest() }.exceptionOrNull() is NoSuchElementException)
        }
        for (invalidJson in listOf("null", "1", "true", "{}", "[]")) {
            val restored = CarPlaySettings.decode("{\"dockPosition\":$invalidJson,\"fps\":59,\"ssid\":\"preserve\"}")
            assertEquals("automatic", dock(restored))
            assertEquals(59, restored.fps); assertEquals("preserve", restored.ssid)
        }
        for (id in listOf("automatic", "bottom", "driver_side"))
            assertNull(carPlayEditError(positioned(CarPlaySettings(), id)))
    }

    @Test fun bothTransportSnapshotsMapDockAndQualifiedFallbackRetainsIt() {
        for (mode in listOf("usb", "wireless")) for ((id, edge) in listOf("automatic" to null, "bottom" to 1, "driver_side" to 2)) {
            val saved = positioned(CarPlaySettings(connectionMode = mode, resolution = "1080p", fps = 59,
                autoResolution = false, fitToWindow = false, autoScale = false), id)
            val snapshot = CarPlaySettings.decode(CarPlaySettings.encode(saved))
            val request = snapshot.displayRequest()
            assertEquals(edge, request.toAirPlayDisplay().viewAreas?.single()?.dockEdge)
            val selected = snapshot.qualifiedDisplay(null, VideoDecoderProbe {
                if (it.width == 1280 && it.height == 720 && it.fps == 30) "baseline" else null
            })
            assertEquals(1280, selected.width); assertEquals(720, selected.height)
            assertEquals(edge, selected.toAirPlayDisplay().viewAreas?.single()?.dockEdge)
            val next = positioned(snapshot, if (id == "bottom") "driver_side" else "bottom")
            assertNotEquals(dock(snapshot), dock(next))
            assertEquals(edge, request.toAirPlayDisplay().viewAreas?.single()?.dockEdge)
        }
    }

    @Test fun globalDraftCommitsAtomicallyAgainstLatestAndKeepsConcurrentEditsForNextSave() = runTest {
        val directory = File("build/tmp/carplay-dock-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            open(directory) { prefs ->
                val running = prefs.carPlaySettings.first().displayRequest()
                val draft = CarPlaySettingsDraft()
                draft.update { positioned(it, "bottom") }
                assertEquals("automatic", dock(prefs.carPlaySettings.first()))
                val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val save = async {
                    draft.commit { merge, _ ->
                        entered.complete(Unit); release.await()
                        prefs.updateCarPlaySettings(merge) { require(carPlayEditError(it) == null) }
                    }
                }
                entered.await()
                draft.update { positioned(it, "driver_side") }
                prefs.updateCarPlaySettings({ it.copy(ssid = "concurrent-network", fps = 59) }) {}
                release.complete(Unit)
                assertTrue(save.await())
                assertEquals("bottom", dock(prefs.carPlaySettings.first()))
                assertEquals("concurrent-network", prefs.carPlaySettings.first().ssid)
                assertEquals(59, prefs.carPlaySettings.first().fps)
                assertEquals("driver_side", dock(draft.preview(prefs.carPlaySettings.first())))
                assertNull(running.toAirPlayDisplay().viewAreas)
                assertTrue(draft.commit { merge, _ -> prefs.updateCarPlaySettings(merge) {} })
            }
            open(directory) { prefs -> assertEquals("driver_side", dock(prefs.carPlaySettings.first())) }
        } finally { directory.deleteRecursively() }
    }

    @Test fun invalidDraftBlocksAtomicSaveAndPreservesDraftAndStoredRecord() = runTest {
        val directory = File("build/tmp/carplay-dock-invalid-${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try { open(directory) { prefs ->
            val previous = prefs.carPlaySettings.first()
            val draft = CarPlaySettingsDraft()
            draft.update { positioned(it.copy(fps = 59), "left") }
            val events = mutableListOf<String>()
            applySettingsAndRestart({ draft.commit { merge, _ ->
                prefs.updateCarPlaySettings(merge) { require(carPlayEditError(it) == null) }
            } }, { events += "restart" }, { events += "close" })
            assertTrue(events.isEmpty())
            assertEquals(previous, prefs.carPlaySettings.first())
            assertEquals("left", dock(draft.preview(previous)))
        } } finally { directory.deleteRecursively() }
    }

    private suspend fun open(directory: File, block: suspend (AppPreferences) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(directory, "settings.preferences_pb") })
        val constructor = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }
        try { block(constructor.newInstance(store)) } finally { job.cancelAndJoin() }
    }
}
