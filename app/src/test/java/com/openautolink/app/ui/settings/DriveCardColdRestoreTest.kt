package com.openautolink.app.ui.settings

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.openautolink.app.cluster.*
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.secondary.*
import io.mockk.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34],application=Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardColdRestoreTest {
    private fun pump(predicate:()->Boolean) {
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
        while(!predicate() && System.nanoTime()<deadline) {shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5)}
        assertTrue("Actual Settings/DataStore transaction did not finish",predicate())
    }
    @Test fun sharedSaveThenClosedStoreAndNewViewModelRestoreExplicitCardOnly() {
        val root=Files.createTempDirectory(File("build/tmp").apply{mkdirs()}.toPath(),"drivecard-cold-").toFile()
        val file=File(root,"settings.preferences_pb")
        val app=RuntimeEnvironment.getApplication() as Application
        val id=SecondaryOutputId("cold-driver")
        val other=SecondaryOutputId("cold-other")
        val target=SecondaryDisplayDescriptor(id,7,1,"Driver",2000,810,220,SecondaryRoute.PUBLIC_ACTIVITY,SecondaryReason.NONE,"Unknown")
        var storeJob=SupervisorJob()
        fun open():AppPreferences {
            val store=PreferenceDataStoreFactory.create(scope=CoroutineScope(Dispatchers.IO+storeJob),produceFile={file})
            return AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java).apply{isAccessible=true}.newInstance(store)
        }
        var prefs=open()
        val models=ViewModelStore()
        var vm:SettingsViewModel?=null
        fun closeModel() {
            val job=vm?.viewModelScope?.coroutineContext?.get(Job)
            models.clear();shadowOf(Looper.getMainLooper()).idle()
            if(job!=null) {pump{job.isCompleted};runBlocking{job.join()}}
            vm=null
        }
        try {
            runBlocking {
                prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
                prefs.updateSecondaryDisplayPreferences(0,mapOf(id to SecondaryOutputPreference(true,AaSecondaryRole.AUXILIARY),other to SecondaryOutputPreference()))
            }
            mockkObject(AppPreferences.Companion)
            every {AppPreferences.getInstance(any())} answers {prefs}
            val first=SettingsViewModel(app);vm=first;models.put("first",first)
            pump{first.clusterLoaded.value && first.carPlaySettingsLoaded.value && first.secondaryPreferences.value!=null}
            first.observeSecondaryPresentationTarget(target)
            first.updateSecondaryOutput(id){it.copy(driveCardEnabled=true)}
            assertFalse(runBlocking{prefs.getSecondaryDisplayPreferences()}.outputs.getValue(id).driveCardEnabled)
            val commit=first.viewModelScope.async{first.commitSettings{true}}
            pump{commit.isCompleted};assertTrue(runBlocking{commit.await()})
            closeModel();runBlocking{storeJob.cancelAndJoin()}
            storeJob=SupervisorJob();prefs=open()
            val cold=SettingsViewModel(app);vm=cold;models.put("cold",cold)
            pump{cold.clusterLoaded.value && cold.carPlaySettingsLoaded.value && cold.secondaryPreferences.value!=null}
            val snapshot=cold.secondaryPreferences.value!!
            assertTrue(snapshot.outputs.getValue(id).driveCardEnabled)
            assertFalse(snapshot.outputs.getValue(other).driveCardEnabled)
            assertEquals(AaSecondaryRole.AUXILIARY,snapshot.outputs.getValue(id).aaRole)
            assertEquals(ClusterDisplayMode.MAP_VIDEO,snapshot.legacyCluster.selection.mode)
        } finally {
            closeModel();runBlocking{storeJob.cancelAndJoin()};unmockkObject(AppPreferences.Companion);root.deleteRecursively()
        }
    }
}
