package com.openautolink.app.ui.settings.cimigration

import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import com.openautolink.app.ui.settings.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Retained adapter/resolver behavior is not a public Settings button. */
internal object LegacyDirectionsAdapterContract {
    fun verify() = runBlocking<Unit> {
        val app = RuntimeEnvironment.getApplication()
        val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        val essential = PixelRect(0,135,2000,810)
        val host = ClusterHostGeometry(7,profile.panel,essential,essential)
        val display = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role,7)),host))
        val scope = ClusterLayoutScope(profile.vehicle,profile.id,profile.role,profile.panel)
        val safe = PixelRect(636,150,1471,661)
        val file = File(app.filesDir,"legacy-adapter-${java.util.UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val work = CoroutineScope(job + Dispatchers.Main.immediate)
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(
            scope=CoroutineScope(job+Dispatchers.IO),produceFile={file})
        val prefs = AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
            .apply{isAccessible=true}.newInstance(store)
        mockkObject(ClusterProjectionOutput.Companion)
        every { ClusterProjectionOutput.qualifiedInitialDisplay(any()) } returns display
        val preview = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _,_,_,_,_->
            error("Restore/migration cannot start preview") },{0L},{_,_->{}})
        var adapter: ClusterSettingsRuntimeAdapter? = null
        try {
            prefs.saveClusterLayout(SavedClusterLayout(scope,essential,essential,safe))
            adapter = ClusterSettingsRuntimeAdapter.create(app,MutableStateFlow(ClusterSettingsObservation()),prefs,preview,work)
            val migrated = requireNotNull(adapter.bindings.value.layout)
            assertEquals(profile.output,migrated.canvas);assertEquals(profile.output,migrated.output);assertEquals(safe,migrated.safe)
            assertTrue(adapter.bindings.value.commitLayoutDraft())
            val saved = requireNotNull(prefs.getClusterLayout(scope,profile.output).custom)
            assertEquals(SavedClusterLayout(scope,profile.output,profile.output,safe),saved)
            // Old narrow host cannot clip/adopt an output above its essential canvas.
            val withheld = ClusterSavedLayoutResolver.resolve(display,prefs::getClusterLayout)
            assertEquals(ClusterLayoutFallbackReason.CANVAS_CHANGED,withheld.fallbackReason)
            assertEquals(saved,prefs.getClusterLayout(scope,profile.output).custom)
            val expanded = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
                listOf(ClusterDisplayBinding(profile.role,7)),host.copy(visible=profile.panel.bounds)))
            val restored = ClusterSavedLayoutResolver.resolve(expanded,prefs::getClusterLayout)
            assertNull(restored.fallbackReason)
            assertEquals(saved.output,restored.display.effectiveOutput);assertEquals(saved.safe,restored.display.safe)
            ClusterVideoGeometry.androidAuto(restored.display);ClusterVideoGeometry.carPlay(restored.display)
        } finally {
            adapter?.close();job.cancelAndJoin();unmockkObject(ClusterProjectionOutput.Companion);file.delete()
        }
    }
}
