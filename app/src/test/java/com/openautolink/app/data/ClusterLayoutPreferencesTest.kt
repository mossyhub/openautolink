package com.openautolink.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import com.openautolink.app.cluster.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ClusterLayoutPreferencesTest {
    private val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
    private val scope = ClusterLayoutScope(profile.vehicle, profile.id, profile.role, profile.panel)
    private val custom = SavedClusterLayout(scope, profile.panel.bounds, profile.output, profile.safe)
    private fun preferences(store: DataStore<Preferences>): AppPreferences = AppPreferences::class.java
        .getDeclaredConstructor(DataStore::class.java).apply { isAccessible = true }.newInstance(store)
    private fun expectInvalid(block: () -> Unit) { try { block(); fail("must reject, not clamp") } catch (_: IllegalArgumentException) {} }

    @Test fun customLayoutsHaveScopedPersistenceBoundary() {
        assertTrue(AppPreferences::class.java.methods.any { it.name == "saveClusterLayout" })
    }

    @Test fun realDataStoreReopensScopedLayoutAndAtomicResetPreservesOtherChoices() = runBlocking {
        val root = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "cluster-layout-").toFile()
        val file = File(root,"settings.preferences_pb")
        var job = SupervisorJob()
        fun open() = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        try {
            var store = open(); var prefs = preferences(store)
            prefs.setDistanceUnits("imperial"); prefs.setSendImuSensors(false)
            assertEquals(custom, prefs.saveClusterLayout(custom).custom)
            val second = scope.copy(profileId = "other|profile")
            prefs.saveClusterLayout(custom.copy(scope = second))
            job.cancelAndJoin(); job = SupervisorJob(); store = open(); prefs = preferences(store)
            assertEquals(custom, prefs.getClusterLayout(scope, custom.canvas).custom)
            assertEquals(custom, prefs.clusterLayout(scope, custom.canvas).first().custom)
            assertNull(prefs.resetClusterLayout(scope, custom.canvas).custom)
            assertEquals(ClusterLayoutUnavailableReason.NO_CUSTOM_LAYOUT, prefs.getClusterLayout(scope,custom.canvas).unavailableReason)
            assertNotNull(prefs.getClusterLayout(second,custom.canvas).custom)
            assertEquals("imperial",prefs.distanceUnits.first()); assertFalse(prefs.sendImuSensors.first())
        } finally { job.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun everyVehicleProfileAndPanelFieldIsAnIsolatedNamespace() {
        val vehicle = scope.vehicle
        val scopes = listOf(scope, scope.copy(vehicle=vehicle.copy(product=vehicle.product+"x")),
            scope.copy(vehicle=vehicle.copy(sdk=33)), scope.copy(vehicle=vehicle.copy(buildFamily="other")),
            scope.copy(vehicle=vehicle.copy(buildRevision=240)),scope.copy(profileId="other"),scope.copy(panel=PixelSize(2001,810)),
            scope.copy(vehicle=vehicle.copy(product="a|b",buildFamily="c")),
            scope.copy(vehicle=vehicle.copy(product="a",buildFamily="b|c")))
        assertEquals(scopes.size,scopes.map { it.preferenceKey.name }.toSet().size)
        for (other in scopes.drop(1)) assertEquals(ClusterLayoutUnavailableReason.SCOPE_MISMATCH,
            ClusterLayoutStorage.decode(custom.encode(),other,custom.canvas).unavailableReason)
    }

    @Test fun rejectsInvalidGeometryAndDoesNotSilentlyClampSafeArea() {
        expectInvalid { custom.copy(safe=PixelRect(0,0,20,20)) }
        expectInvalid { custom.copy(output=PixelRect(0,0,10,10)) }
        expectInvalid { custom.copy(canvas=PixelRect(0,0,100,100)) }
        expectInvalid { PixelRect(0,0,32769,1) }
        expectInvalid { PixelRect(1,1,1,2) }
        expectInvalid { scope.copy(role=ClusterVehicleRole.CENTER) }
        expectInvalid { scope.copy(vehicle=scope.vehicle.copy(product="")) }
        assertEquals(ClusterLayoutUnavailableReason.CANVAS_CHANGED,
            ClusterLayoutStorage.decode(custom.encode(),scope,PixelRect(0,1,2000,810)).unavailableReason)
    }

    @Test fun malformedUnknownVersionAndOutOfBoundsRecordsFailClosed() {
        for (raw in listOf("", "[]", "{}", "not json", custom.encode().replace("\"version\":1", "\"version\":2"),
            custom.encode().replace("\"safe\":[622,165,1374,669]", "\"safe\":[0,0,20,20]"),
            custom.encode().replace("\"safe\":[622,165,1374,669]", "\"safe\":[622.5,165,1374,669]"),
            custom.encode().replace("\"output\":[98,65,1904,745]", "\"output\":[98,65,99999999999,745]"))) {
            val read = ClusterLayoutStorage.decode(raw,scope,custom.canvas)
            assertNull(raw,read.custom); assertNotNull(read.unavailableReason)
        }
        assertEquals(custom,ClusterLayoutStorage.decode(custom.encode(),scope,custom.canvas).custom)
    }

    @Test fun layoutSaveAndResetReturnTheirOwnAtomicSnapshotDespiteSuccessorWrite() = runBlocking {
        val state=kotlinx.coroutines.flow.MutableStateFlow<Preferences>(emptyPreferences())
        val key=scope.preferenceKey
        val store=object : DataStore<Preferences> {
            override val data: kotlinx.coroutines.flow.Flow<Preferences> = state
            override suspend fun updateData(transform: suspend (Preferences)->Preferences): Preferences {
                val own=transform(state.value)
                state.value=own.toMutablePreferences().also {
                    if(own[key]==null) it[key]=custom.encode() else it[key]="malformed successor"
                }.toPreferences()
                return own
            }
        }
        val prefs=preferences(store)
        assertEquals(custom,prefs.saveClusterLayout(custom).custom)
        assertEquals(ClusterLayoutUnavailableReason.INVALID_RECORD,prefs.getClusterLayout(scope,custom.canvas).unavailableReason)
        assertNull(prefs.resetClusterLayout(scope,custom.canvas).custom)
        assertEquals(custom,prefs.getClusterLayout(scope,custom.canvas).custom)
    }

    @Test fun storageFailureCannotClaimSuccessfulLayoutSaveOrReset() = runBlocking {
        val store = object : DataStore<Preferences> {
            override val data = kotlinx.coroutines.flow.flowOf(emptyPreferences())
            override suspend fun updateData(transform: suspend (Preferences)->Preferences): Preferences {
                transform(emptyPreferences()); throw java.io.IOException("storage failure")
            }
        }
        val prefs=preferences(store)
        for (save in listOf(true,false)) {
            try { if(save) prefs.saveClusterLayout(custom) else prefs.resetClusterLayout(scope,custom.canvas); fail("must propagate") }
            catch (_: java.io.IOException) { assertNull(prefs.getClusterLayout(scope,custom.canvas).custom) }
        }
    }
}
