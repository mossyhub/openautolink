package com.openautolink.app.ui.settings

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.data.AppPreferences.Companion.SAFE_AREA_BOTTOM
import com.openautolink.app.data.AppPreferences.Companion.SAFE_AREA_LEFT
import com.openautolink.app.data.AppPreferences.Companion.SAFE_AREA_RIGHT
import com.openautolink.app.data.AppPreferences.Companion.SAFE_AREA_TOP
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AndroidAutoControlAreaDraftTest {
    private val legacy = listOf(7, 11, 13, 17)
    private val edited = listOf(19, 23, 29, 31)
    private val safeAreaKeys = listOf(SAFE_AREA_TOP, SAFE_AREA_BOTTOM, SAFE_AREA_LEFT, SAFE_AREA_RIGHT)

    @Test fun doneIsMemoryOnlyAndOrdinarySavePersistsOnReopen() = withPreferenceFile { file ->
        openStore(file) { store ->
            io { store.edit { row -> safeAreaKeys.zip(legacy).forEach { (key, value) -> row[key] = value } } }
            withViewModel(store) { vm ->
                await("legacy control area loaded") { vm.settingsState.value.insets() == legacy }
                vm.updateSafeAreaInsets(19, 23, 29, 31)
                await("Done draft published") { vm.settingsState.value.insets() == edited }
                assertEquals("Done must not write the file", legacy, storedInsets(store))
                assertEquals("Runtime-facing settings must stay saved-only", legacy, vm.uiState.value.insets())
            }
        }
        openStore(file) { store ->
            assertEquals("Retiring an unsaved editor must not write the file", legacy, storedInsets(store))
            withViewModel(store) { vm ->
                await("legacy control area reloaded") { vm.settingsState.value.insets() == legacy }
                vm.updateSafeAreaInsets(19, 23, 29, 31)
                // A concurrent unrelated write must survive the final safe-area transaction.
                io { store.edit { it[AppPreferences.DIRECT_TRANSPORT] = AppPreferences.DIRECT_TRANSPORT_USB } }
                val save = vm.viewModelScope.async { vm.commitSettings(null) }
                await("ordinary Save completed") { save.isCompleted }
                assertTrue(vm.carPlayApplyError.value, io { save.await() })
            }
        }
        openStore(file) { store ->
            assertEquals(edited, storedInsets(store))
            assertEquals(AppPreferences.DIRECT_TRANSPORT_USB, io { store.data.first()[AppPreferences.DIRECT_TRANSPORT] })
        }
    }

    @Test fun heldLayoutSaveRejectsEditsAndFailedLayoutRetainsOriginalDraftForRetry() = withPreferenceFile { file ->
        openStore(file) { store ->
            withViewModel(store) { vm ->
                val defaults = listOf(AppPreferences.DEFAULT_SAFE_AREA_TOP, AppPreferences.DEFAULT_SAFE_AREA_BOTTOM,
                    AppPreferences.DEFAULT_SAFE_AREA_LEFT, AppPreferences.DEFAULT_SAFE_AREA_RIGHT)
                assertEquals(defaults, vm.settingsState.value.insets())
                vm.updateSafeAreaInsets(19, 23, 29, 31)
                await("Done draft published") { vm.settingsState.value.insets() == edited }

                val layoutEntered = CompletableDeferred<Unit>()
                val layoutResult = CompletableDeferred<Boolean>()
                val save = vm.viewModelScope.async {
                    vm.commitSettings {
                        layoutEntered.complete(Unit)
                        layoutResult.await()
                    }
                }
                try {
                    await("Save is held at the layout stage") { layoutEntered.isCompleted }
                    vm.updateSafeAreaInsets(101, 102, 103, 104)
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(edited, vm.settingsState.value.insets())
                    assertEquals(List(4) { null }, storedInsets(store))
                } finally {
                    layoutResult.complete(false)
                }
                await("failed Save completed") { save.isCompleted }
                assertFalse(io { save.await() })
                assertEquals(edited, vm.settingsState.value.insets())
                assertEquals(List(4) { null }, storedInsets(store))

                val retry = vm.viewModelScope.async {
                    vm.commitSettings {
                        // The layout acknowledgement is the last optional store; insets are not committed yet.
                        check(storedInsets(store) == List(4) { null })
                        true
                    }
                }
                await("retry completed") { retry.isCompleted }
                assertTrue(vm.carPlayApplyError.value, io { retry.await() })
            }
        }
        openStore(file) { store -> assertEquals(edited, storedInsets(store)) }
    }

    @Test fun atomicSetterKeepsLegacyScaleAndUnrelatedKeysAcrossReopen() = withPreferenceFile { file ->
        val rawLegacyValues = listOf(-7, 1001, 2003, 4007)
        openStore(file) { store ->
            io { store.edit { it[AppPreferences.DIRECT_TRANSPORT] = AppPreferences.DIRECT_TRANSPORT_USB } }
            val safeAreaWrites = mutableListOf<List<Int?>>()
            val countedStore = object : DataStore<Preferences> {
                override val data = store.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    store.updateData { before ->
                        transform(before).also { after ->
                            if (safeAreaKeys.any { before[it] != after[it] }) {
                                safeAreaWrites.add(safeAreaKeys.map { after[it] })
                            }
                        }
                    }
            }
            withViewModel(countedStore) { vm ->
                vm.updateSafeAreaInsets(-7, 1001, 2003, 4007)
                await("unscaled draft published") { vm.settingsState.value.insets() == rawLegacyValues }
                val save = vm.viewModelScope.async { vm.commitSettings(null) }
                await("ordinary Save completed") { save.isCompleted }
                assertTrue(vm.carPlayApplyError.value, io { save.await() })
                assertEquals("Four keys must be written by one transaction", listOf(rawLegacyValues), safeAreaWrites)
            }
        }
        openStore(file) { store ->
            assertEquals(rawLegacyValues, storedInsets(store))
            assertEquals(AppPreferences.DIRECT_TRANSPORT_USB, io { store.data.first()[AppPreferences.DIRECT_TRANSPORT] })
        }
    }

    @Test fun failedFinalPreferenceWriteRetainsDraftAndAllowsRetry() = withPreferenceFile { file ->
        openStore(file) { store ->
            val failWrite = java.util.concurrent.atomic.AtomicBoolean(true)
            val failingStore = object : DataStore<Preferences> {
                override val data = store.data
                override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                    store.updateData { before ->
                        transform(before).also { after ->
                            if (safeAreaKeys.any { before[it] != after[it] } && failWrite.getAndSet(false)) {
                                throw java.io.IOException("injected final safe-area persistence failure")
                            }
                        }
                    }
            }
            withViewModel(failingStore) { vm ->
                vm.updateSafeAreaInsets(19, 23, 29, 31)
                await("Done draft published") { vm.settingsState.value.insets() == edited }
                val save = vm.viewModelScope.async { vm.commitSettings(null) }
                await("failed final preference write completed") { save.isCompleted }
                assertFalse(io { save.await() })
                assertEquals(List(4) { null }, storedInsets(store))
                assertEquals(edited, vm.settingsState.value.insets())
                val retry = vm.viewModelScope.async { vm.commitSettings(null) }
                await("preference-write retry completed") { retry.isCompleted }
                assertTrue(vm.carPlayApplyError.value, io { retry.await() })
                assertEquals(edited, storedInsets(store))
                val newer = listOf(41, 43, 47, 53)
                io { store.edit { row -> safeAreaKeys.zip(newer).forEach { (key, value) -> row[key] = value } } }
                await("successful Save cleared the draft overlay") { vm.settingsState.value.insets() == newer }
            }
        }
        openStore(file) { store -> assertEquals(listOf(41, 43, 47, 53), storedInsets(store)) }
    }

    private fun SettingsUiState.insets() = listOf(safeAreaTop, safeAreaBottom, safeAreaLeft, safeAreaRight)

    private fun storedInsets(store: DataStore<Preferences>): List<Int?> = io {
        val row = store.data.first()
        safeAreaKeys.map { row[it] }
    }

    private fun withPreferenceFile(block: (File) -> Unit) {
        val directory = Files.createTempDirectory(File("build/tmp").apply { mkdirs() }.toPath(), "aa-control-area-").toFile()
        try {
            block(File(directory, "settings.preferences_pb"))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun openStore(file: File, block: (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        try {
            block(store)
        } finally {
            io { job.cancelAndJoin() }
        }
    }

    private fun withViewModel(store: DataStore<Preferences>, block: (SettingsViewModel) -> Unit) {
        val preferences = AppPreferences::class.java.getDeclaredConstructor(DataStore::class.java)
            .apply { isAccessible = true }.newInstance(store)
        val policyScope = AppPreferences::class.java.getDeclaredField("connectionPolicyScope")
            .apply { isAccessible = true }.get(preferences) as CoroutineScope
        val models = ViewModelStore()
        var scopeJob: Job? = null
        mockkObject(AppPreferences.Companion)
        try {
            every { AppPreferences.getInstance(any()) } returns preferences
            val vm = SettingsViewModel(RuntimeEnvironment.getApplication())
            models.put("aa-control-area", vm)
            scopeJob = vm.viewModelScope.coroutineContext[Job]
            vm.viewModelScope.launch { vm.settingsState.collect {} }
            await("Settings ready") { vm.clusterLoaded.value && vm.carPlaySettingsLoaded.value }
            block(vm)
        } finally {
            try {
                models.clear()
                await("Settings scope retired") { scopeJob?.isCompleted != false }
            } finally {
                unmockkObject(AppPreferences.Companion)
                io { policyScope.coroutineContext[Job]?.cancelAndJoin() }
            }
        }
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue(label, predicate())
    }

    private fun <T> io(block: suspend () -> T): T = runBlocking { withTimeout(5_000) { block() } }
}
