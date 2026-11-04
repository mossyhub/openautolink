package com.openautolink.app.ui.settings

import android.app.Application
import com.openautolink.app.cluster.*
import com.openautolink.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(sdk=[32,34],application=Application::class)
class ExpandedLegacyLayoutPersistenceTest {
    @get:Rule val temporary=org.junit.rules.TemporaryFolder()
    @Test fun liveExpansionLegacyEditAndSharedSavePersistExactSupportedIntent()=runBlocking {
        val storeScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val adapterScope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val store=androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope=storeScope,
            produceFile={java.io.File(temporary.root,"legacy.preferences_pb")})
        val prefs=AppPreferences::class.java.getDeclaredConstructor(androidx.datastore.core.DataStore::class.java)
            .apply { isAccessible=true }.newInstance(store)
        val profile=ClusterDisplayProfiles.AEGEAN_ORANGE_239
        fun qualify(visible:PixelRect)=requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role,7)),ClusterHostGeometry(7,profile.panel,visible,PixelRect(0,135,2000,810))))
        val seed=qualify(PixelRect(0,135,2000,810));val expanded=qualify(PixelRect(0,0,2000,810))
        val namespace=ClusterLayoutScope(profile.vehicle,profile.id,profile.role,profile.panel)
        val saved=SavedClusterLayout(namespace,requireNotNull(seed.host.essentialCanvas),seed.effectiveOutput,seed.safe)
        assertEquals(saved,prefs.saveClusterLayout(saved).custom)
        val observations=MutableStateFlow(ClusterSettingsObservation())
        val preview=ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _,_,_,_,_->null },{0L},{ _,_-> {} })
        val adapter=ClusterSettingsRuntimeAdapter.create(seed,observations,prefs,preview,adapterScope)
        suspend fun await(condition:()->Boolean) { withTimeout(5000) { while(!condition())delay(2) } }
        try {
            assertEquals(saved.output,adapter.bindings.value.layout!!.output)
            observations.value=ClusterSettingsObservation(liveDisplay=expanded)
            await { adapter.bindings.value.automaticLayout?.canvas?.top==65 }
            assertEquals("Read/observation must not rewrite durable legacy intent",saved,prefs.getClusterLayout(namespace,saved.canvas).custom)
            val old=adapter.bindings.value.layout!!
            assertEquals(saved.canvas,old.canvas)
            val edit=old.adjust(ClusterLayoutTarget.SAFE,x=old.safe.left+1)
            adapter.bindings.value.onStageLayout(edit)
            var reconnected=0
            applySettingsAndRestart({ adapter.bindings.value.commitLayoutDraft() },{ reconnected++ },{})
            assertEquals(1,reconnected)
            val persisted=prefs.getClusterLayout(namespace,saved.canvas).custom!!
            assertEquals(saved.canvas,persisted.canvas);assertEquals(saved.output,persisted.output);assertEquals(edit.safe,persisted.safe)
            adapter.bindings.value.onResetLayout()
            val automatic=adapter.bindings.value.automaticLayout!!
            adapter.bindings.value.onStageLayout(automatic.adjust(ClusterLayoutTarget.SAFE,x=automatic.safe.left+1))
            assertTrue(adapter.bindings.value.commitLayoutDraft())
            val current=prefs.getClusterLayout(namespace,automatic.canvas).custom!!
            assertEquals(PixelRect(98,65,1904,745),current.canvas)
            assertEquals(automatic.output,current.output)
            observations.value=ClusterSettingsObservation()
            await { adapter.bindings.value.automaticLayout?.canvas?.top==135 }
            assertEquals("Loss of live authority may not rewrite expanded saved intent",current,prefs.getClusterLayout(namespace,current.canvas).custom)
        } finally { adapter.close();storeScope.cancel();adapterScope.cancel() }
    }
}
