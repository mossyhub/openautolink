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
import kotlinx.coroutines.flow.first
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
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class SecondaryDensitySettingsViewModelTest {
    private fun pump(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
        }
        assertTrue("Actual Settings persistence did not complete", predicate())
    }

    @Test fun globalSettingsCommitPreservesDensityThroughPresentationStagingAndReset() {
        val app = RuntimeEnvironment.getApplication() as Application
        val root = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "density-vm-").toFile()
        val storeJob = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + storeJob),
            produceFile = { File(root, "density.preferences_pb") })
        val prefs = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        val a = SecondaryOutputId("density-vm-a"); val b = SecondaryOutputId("density-vm-b")
        val target = SecondaryDisplayDescriptor(a, 7, 1, "Driver display", 2000, 810, 220,
            SecondaryRoute.PUBLIC_ACTIVITY, SecondaryReason.NONE, "Unknown")
        val models = ViewModelStore()
        var vm: SettingsViewModel? = null
        try {
            runBlocking {
                prefs.setClusterOutputSelection(ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO))
                prefs.updateSecondaryDisplayPreferences(0, mapOf(
                    a to SecondaryOutputPreference(true, AaSecondaryRole.AUXILIARY),
                    b to SecondaryOutputPreference(false, AaSecondaryRole.CLUSTER, aaDensityDpi = 180)))
            }
            val mainDensity = runBlocking { prefs.aaDpi.first() }
            mockkObject(AppPreferences.Companion)
            every { AppPreferences.getInstance(any()) } returns prefs
            val viewModel = SettingsViewModel(app); vm = viewModel; models.put("density", viewModel)
            pump { viewModel.clusterLoaded.value && viewModel.carPlaySettingsLoaded.value && viewModel.secondaryPreferences.value != null }
            viewModel.observeSecondaryPresentationTarget(target)
            viewModel.updateSecondaryOutput(a) { it.copy(aaDensityDpi = 140) }
            pump { viewModel.secondaryPreferences.value?.outputs?.get(a)?.aaDensityDpi == 140 }
            assertNull(runBlocking { prefs.getSecondaryDisplayPreferences() }.outputs.getValue(a).aaDensityDpi)
            val committed = viewModel.viewModelScope.async { viewModel.commitSettings { true } }
            pump { committed.isCompleted }
            assertTrue(viewModel.carPlayApplyError.value, runBlocking { committed.await() })
            val saved = runBlocking { prefs.getSecondaryDisplayPreferences() }
            assertEquals(140, saved.outputs.getValue(a).aaDensityDpi)
            assertEquals(180, saved.outputs.getValue(b).aaDensityDpi)
            assertTrue(saved.outputs.getValue(a).enabled); assertFalse(saved.outputs.getValue(b).enabled)
            assertEquals(AaSecondaryRole.AUXILIARY, saved.outputs.getValue(a).aaRole)
            assertEquals(mainDensity, runBlocking { prefs.aaDpi.first() })
            pump { viewModel.secondaryPreferences.value?.revision == saved.revision }
            viewModel.updateSecondaryOutput(a) { it.copy(aaDensityDpi = null) }
            val reset = viewModel.viewModelScope.async { viewModel.commitSettings { true } }
            pump { reset.isCompleted }
            assertTrue(viewModel.carPlayApplyError.value, runBlocking { reset.await() })
            val restored = runBlocking { prefs.getSecondaryDisplayPreferences() }
            assertNull(restored.outputs.getValue(a).aaDensityDpi)
            assertEquals(180, restored.outputs.getValue(b).aaDensityDpi)
            assertEquals(mainDensity, runBlocking { prefs.aaDpi.first() })
        } finally {
            val job = vm?.viewModelScope?.coroutineContext?.get(Job)
            models.clear(); shadowOf(Looper.getMainLooper()).idle()
            if (job != null) { pump { job.isCompleted }; runBlocking { job.join() } }
            runBlocking { storeJob.cancelAndJoin() }
            unmockkObject(AppPreferences.Companion)
            root.deleteRecursively()
        }
    }
}
