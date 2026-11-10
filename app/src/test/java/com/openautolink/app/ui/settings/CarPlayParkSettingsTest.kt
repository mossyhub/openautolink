package com.openautolink.app.ui.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.CarPlaySettings
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CarPlayParkSettingsTest {
    @Test fun missingLegacyUnknownAndMalformedRecordsNeverOptIntoOverride() {
        for (record in listOf(null, "{}", "{\"future\":true}", "{", "{\"alwaysInPark\":\"true\"}",
            "{\"alwaysInPark\":1}", "{\"alwaysInPark\":null}")) {
            assertFalse(record, CarPlaySettings.decode(record).alwaysInPark)
            assertFalse(record, CarPlaySettings.decode(record).drivingRestrictionsEnabled)
        }
        assertTrue(CarPlaySettings.decode(CarPlaySettings.encode(CarPlaySettings(alwaysInPark=true,
            drivingRestrictionsEnabled=true))).alwaysInPark)
    }
    @Test fun draftSavePreservesSharedChoiceAndDoesNotMutateRunningSnapshot() = runTest {
        val directory=File("build/tmp/carplay-park-${java.util.UUID.randomUUID()}").apply {mkdirs()}
        try {
            open(directory) { prefs ->
                prefs.setAlwaysInPark(false)
                val running=prefs.carPlaySettings.first()
                val draft=CarPlaySettingsDraft()
                draft.update {it.copy(drivingRestrictionsEnabled=true,alwaysInPark=true)}
                assertFalse(prefs.carPlaySettings.first().alwaysInPark)
                val events=mutableListOf<String>()
                applySettingsAndRestart({draft.commit { merge, _ ->
                    prefs.updateCarPlaySettings(merge) {check(carPlayEditError(it)==null)}
                    events+="persist"
                }},{events+="reconnect"},{events+="close"})
                assertEquals(listOf("persist","reconnect","close"),events)
                assertFalse(running.alwaysInPark)
                assertFalse(prefs.alwaysInPark.first())
            }
            open(directory) {prefs ->
                val saved=prefs.carPlaySettings.first()
                assertFalse(saved.alwaysInPark);assertTrue(saved.drivingRestrictionsEnabled)
                assertFalse(prefs.alwaysInPark.first())
                prefs.setAlwaysInPark(true)
                val draft=CarPlaySettingsDraft();draft.update {it.copy(alwaysInPark=false)}
                assertTrue(draft.commit {merge,_->prefs.updateCarPlaySettings(merge) {}})
                assertTrue(prefs.alwaysInPark.first())
            }
            open(directory) {prefs ->
                assertTrue(prefs.carPlaySettings.first().alwaysInPark)
                assertTrue(prefs.carPlaySettings.first().drivingRestrictionsEnabled)
                assertTrue(prefs.alwaysInPark.first())
            }
        } finally {directory.deleteRecursively()}
    }
    private suspend fun open(directory:File, block:suspend (AppPreferences)->Unit) {
        val job=SupervisorJob()
        val store=PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+job),
            produceFile={File(directory,"settings.preferences_pb")})
        val constructor=AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply {isAccessible=true}
        try {block(constructor.newInstance(store))} finally {job.cancelAndJoin()}
    }
}
