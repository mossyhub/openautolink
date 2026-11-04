package com.openautolink.app.ui.settings

import com.openautolink.app.cluster.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import androidx.datastore.preferences.core.*
import org.junit.Assert.*
import org.junit.Test

class ClusterSettingsRuntimeAdapterTest {
    @Test fun failedCommitPreservesDraftAndDoesNotMutateObservedStatus() = runBlocking {
        val storage = MemoryLayouts()
        val observed = MutableStateFlow(ClusterSettingsObservation())
        val adapter = ClusterSettingsRuntimeAdapter.create(display(), observed, storage, null, this)
        val initial = requireNotNull(adapter.bindings.value.layout)
        val draft = initial.copy(safe = PixelRect(630, 170, 1360, 660))
        adapter.bindings.value.onStageLayout(draft)
        assertTrue(adapter.bindings.value.isCustomLayout)
        storage.acknowledge = false
        assertFalse(adapter.bindings.value.commitLayoutDraft())
        assertEquals(draft, adapter.bindings.value.layout)
        assertNull(adapter.bindings.value.status.host)
        storage.acknowledge = true
        assertTrue(adapter.bindings.value.commitLayoutDraft())
        assertEquals(draft, storage.saved)
        adapter.close()
    }

    @Test fun editDuringSuspendedCommitRemainsPendingAndReopenRestoresStoredLayout() = runBlocking {
        val storage = MemoryLayouts()
        val observed = MutableStateFlow(ClusterSettingsObservation())
        val adapter = ClusterSettingsRuntimeAdapter.create(display(), observed, storage, null, this)
        val auto = requireNotNull(adapter.bindings.value.layout)
        val first = auto.copy(safe = PixelRect(630, 170, 1360, 660))
        val newer = auto.copy(safe = PixelRect(640, 180, 1350, 650))
        adapter.bindings.value.onStageLayout(first)
        val hold = CompletableDeferred<Unit>(); storage.hold = hold
        val commit = async(start = CoroutineStart.UNDISPATCHED) { adapter.bindings.value.commitLayoutDraft() }
        adapter.bindings.value.onStageLayout(newer)
        hold.complete(Unit)
        assertTrue(commit.await())
        assertEquals(newer, adapter.bindings.value.layout)
        adapter.close()
        storage.hold = null
        val reopened = ClusterSettingsRuntimeAdapter.create(display(), observed, storage, null, this)
        assertEquals(first, reopened.bindings.value.layout)
        reopened.bindings.value.onResetLayout()
        assertTrue(reopened.bindings.value.commitLayoutDraft())
        assertNull(storage.saved)
        reopened.close()
    }

    @Test fun unknownQualificationWithholdsGeometryAndObservedSurfaceIsNotInferred() = runBlocking {
        val observed = MutableStateFlow(ClusterSettingsObservation(source = "Android Auto", output = ClusterProjectionStatus(received = 3, presented = 2)))
        val adapter = ClusterSettingsRuntimeAdapter.create(null, observed, MemoryLayouts(), null, this)
        assertNull(adapter.bindings.value.layout)
        assertNull(adapter.bindings.value.actualSurface)
        assertEquals("Android Auto", adapter.bindings.value.status.source)
        assertNull(adapter.bindings.value.status.host)
        assertTrue(requireNotNull(adapter.bindings.value.status.renderedFrames).contains("2"))
        adapter.close()
    }

    @Test fun staleEditorStageStopsAdaptersCurrentPreviewNotSuccessorProjection() = runBlocking {
        var stops = 0
        val backend = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ ->
            object : ClusterCalibrationPreview.Session {
                override fun stop() = java.util.concurrent.CompletableFuture.completedFuture(true).also { stops++ }
            }
        }, { 0L }, { _, _ -> {} })
        val adapter = ClusterSettingsRuntimeAdapter.create(display(), MutableStateFlow(ClusterSettingsObservation()), MemoryLayouts(), backend, this)
        val stale = adapter.bindings.value
        val model = requireNotNull(stale.layout)
        stale.onStartPreview(model, 15)
        stale.onStopPreview()
        adapter.bindings.value.onStartPreview(model, 15)
        stale.onStageLayout(model.copy(safe = PixelRect(630, 170, 1360, 660)))
        assertEquals(2, stops)
        adapter.close()
    }

    @Test fun publicFactoryPersistsAndRestoresActualScopedLayoutWithoutTouchingCenterOrRetainedMode() = runBlocking {
        val file = java.io.File(System.getProperty("user.dir"), "build/ui-runtime-layout-${java.util.UUID.randomUUID()}.preferences_pb")
        val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file })
        val preferences = com.openautolink.app.data.AppPreferences::class.java
            .getDeclaredConstructor(androidx.datastore.core.DataStore::class.java).apply { isAccessible = true }.newInstance(store)
        val backend = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ -> null }, { 0L }, { _, _ -> {} })
        var adapter: ClusterSettingsRuntimeAdapter? = null
        try {
            store.updateData { values -> values.toMutablePreferences().apply {
                this[com.openautolink.app.data.AppPreferences.AA_DPI] = 168
                this[com.openautolink.app.data.AppPreferences.CLUSTER_DISPLAY_MODE] = "OFF"
                this[com.openautolink.app.data.AppPreferences.CLUSTER_LAST_ENABLED_MODE] = "MAP_VIDEO"
            } }
            val observed = MutableStateFlow(ClusterSettingsObservation())
            adapter = ClusterSettingsRuntimeAdapter.create(display(), observed, preferences, backend, this)
            val auto = requireNotNull(adapter.bindings.value.layout)
            val custom = auto.copy(safe = PixelRect(630, 170, 1360, 660))
            adapter.bindings.value.onStageLayout(custom)
            assertTrue(adapter.bindings.value.commitLayoutDraft())
            assertTrue(file.isFile)
            adapter.close()
            adapter = ClusterSettingsRuntimeAdapter.create(display(), observed, preferences, backend, this)
            assertEquals(custom, adapter.bindings.value.layout)
            assertTrue(adapter.bindings.value.isCustomLayout)
            val namespace = com.openautolink.app.data.ClusterLayoutScope(custom.key.vehicle, display().profile.id, custom.key.role, custom.key.panel)
            assertEquals(custom.safe, preferences.getClusterLayout(namespace, custom.canvas).custom?.safe)
            adapter.bindings.value.onResetLayout()
            assertTrue(adapter.bindings.value.commitLayoutDraft())
            assertNull(preferences.getClusterLayout(namespace, custom.canvas).custom)
            val final = store.data.first()
            assertEquals(168, final[com.openautolink.app.data.AppPreferences.AA_DPI])
            assertEquals("OFF", final[com.openautolink.app.data.AppPreferences.CLUSTER_DISPLAY_MODE])
            assertEquals("MAP_VIDEO", final[com.openautolink.app.data.AppPreferences.CLUSTER_LAST_ENABLED_MODE])
        } finally {
            adapter?.close(); storeScope.cancel(); storeScope.coroutineContext[Job]?.join(); file.delete()
        }
    }

    @Test fun closeRetriesExactFailedPreviewRetirementRatherThanDroppingOldOwner() = runBlocking {
        var stops = 0
        val backend = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ ->
            object : ClusterCalibrationPreview.Session {
                override fun stop() = java.util.concurrent.CompletableFuture.completedFuture(++stops > 1)
            }
        }, { 0L }, { _, _ -> {} })
        val adapter = ClusterSettingsRuntimeAdapter.create(display(), MutableStateFlow(ClusterSettingsObservation()), MemoryLayouts(), backend, this)
        val initial = adapter.bindings.value
        initial.onStartPreview(requireNotNull(initial.layout), 15)
        initial.onStopPreview()
        assertEquals(1, stops)
        adapter.close()
        assertEquals(2, stops)
    }

    @Test fun statusPublicationDoesNotEnterHeldPreviewMonitor() = runBlocking {
        val backend = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ -> null }, { 0L }, { _, _ -> {} })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val observed = MutableStateFlow(ClusterSettingsObservation())
        val adapter = ClusterSettingsRuntimeAdapter.create(display(), observed, MemoryLayouts(), backend, scope)
        val done = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val thread = Thread {
            entered.countDown()
            observed.value = ClusterSettingsObservation(source = "held-monitor observation")
            adapter.bindings.value.onStageLayout(requireNotNull(adapter.bindings.value.layout))
            done.countDown()
        }.apply { isDaemon = true }
        try {
            synchronized(backend) {
                thread.start()
                assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertTrue("status publication entered preview monitor", done.await(2, java.util.concurrent.TimeUnit.SECONDS))
                assertEquals("held-monitor observation", adapter.bindings.value.status.source)
            }
        } finally { thread.join(3000); adapter.close(); scope.cancel() }
    }

    @Test fun startInvokesBackendOutsideAdapterLockAndRetiresEditDuringBegin() = runBlocking {
        lateinit var adapter: ClusterSettingsRuntimeAdapter
        var stops = 0
        var editThread: Thread? = null
        val backend = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ ->
            val edited = java.util.concurrent.CountDownLatch(1)
            editThread = Thread {
                adapter.bindings.value.onStageLayout(requireNotNull(adapter.bindings.value.layout).copy(safe = PixelRect(630, 170, 1360, 660)))
                edited.countDown()
            }.apply { isDaemon = true; start() }
            check(edited.await(2, java.util.concurrent.TimeUnit.SECONDS)) { "begin held adapter lock across external callback" }
            object : ClusterCalibrationPreview.Session {
                override fun stop() = java.util.concurrent.CompletableFuture.completedFuture(true).also { stops++ }
            }
        }, { 0L }, { _, _ -> {} })
        adapter = ClusterSettingsRuntimeAdapter.create(display(), MutableStateFlow(ClusterSettingsObservation()), MemoryLayouts(), backend, this)
        try {
            val initial = adapter.bindings.value
            initial.onStartPreview(requireNotNull(initial.layout), 15)
            assertFalse(adapter.bindings.value.previewActive)
            assertEquals(1, stops)
            assertNull(backend.activeOwner)
        } finally { editThread?.join(3000); adapter.close() }
    }

    @Test fun stopInvokesBackendOutsideAdapterLockAndAllowsReentrantReset() = runBlocking {
        lateinit var adapter: ClusterSettingsRuntimeAdapter
        var resetThread: Thread? = null
        val callbackCompleted = java.util.concurrent.atomic.AtomicBoolean(false)
        val backend = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ ->
            object : ClusterCalibrationPreview.Session {
                override fun stop(): java.util.concurrent.CompletableFuture<Boolean> {
                    val reset = java.util.concurrent.CountDownLatch(1)
                    resetThread = Thread { adapter.bindings.value.onResetLayout(); reset.countDown() }.apply { isDaemon = true; start() }
                    callbackCompleted.set(reset.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    check(callbackCompleted.get()) { "stop held adapter lock across external callback" }
                    return java.util.concurrent.CompletableFuture.completedFuture(true)
                }
            }
        }, { 0L }, { _, _ -> {} })
        adapter = ClusterSettingsRuntimeAdapter.create(display(), MutableStateFlow(ClusterSettingsObservation()), MemoryLayouts(), backend, this)
        try {
            val initial = adapter.bindings.value
            initial.onStartPreview(requireNotNull(initial.layout), 15)
            initial.onStopPreview()
            assertTrue("stop callback could not reenter adapter", callbackCompleted.get())
            assertNull(backend.activeOwner)
            assertFalse(adapter.bindings.value.previewActive)
        } finally { resetThread?.join(3000); adapter.close() }
    }

    @Test fun synchronousBindingObserverCannotEncloseBackendInvocationInAdapterLock() = runBlocking {
        lateinit var adapter: ClusterSettingsRuntimeAdapter
        val held = java.util.concurrent.atomic.AtomicBoolean(true)
        val backend = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ ->
            val monitor = adapter.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(adapter)
            held.set(Thread.holdsLock(monitor))
            object : ClusterCalibrationPreview.Session {
                override fun stop() = java.util.concurrent.CompletableFuture.completedFuture(true)
            }
        }, { 0L }, { _, _ -> {} })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val observed = MutableStateFlow(ClusterSettingsObservation())
        adapter = ClusterSettingsRuntimeAdapter.create(display(), observed, MemoryLayouts(), backend, scope)
        val once = java.util.concurrent.atomic.AtomicBoolean()
        val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            adapter.bindings.collect { binding ->
                if (binding.isCustomLayout && once.compareAndSet(false, true))
                    binding.onStartPreview(requireNotNull(binding.layout), 15)
            }
        }
        try {
            val edit = Thread {
                adapter.bindings.value.onStageLayout(requireNotNull(adapter.bindings.value.layout))
            }.apply { isDaemon = true; start() }
            edit.join(3000)
            assertFalse(edit.isAlive)
            assertTrue(once.get())
            assertFalse("synchronous publication retained adapter monitor", held.get())
        } finally { observer.cancel(); adapter.close(); scope.cancel() }
    }

    @Test fun failedAsyncRetirementRetriesExactDebtAndStaleStopCannotFlipSuccessor() = runBlocking {
        val pending = java.util.concurrent.CompletableFuture<Boolean>()
        var stops = 0
        val backend = ClusterCalibrationPreview(ClusterCalibrationPreview.Backend { _, _, _, _, _ ->
            object : ClusterCalibrationPreview.Session {
                override fun stop(): java.util.concurrent.CompletableFuture<Boolean> =
                    if (++stops == 1) pending else java.util.concurrent.CompletableFuture.completedFuture(true)
            }
        }, { 0L }, { _, _ -> {} })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val adapter = ClusterSettingsRuntimeAdapter.create(display(), MutableStateFlow(ClusterSettingsObservation()), MemoryLayouts(), backend, scope)
        try {
            val original = adapter.bindings.value
            val model = requireNotNull(original.layout)
            original.onStartPreview(model, 15)
            assertTrue(adapter.bindings.value.previewActive)
            original.onStopPreview()
            adapter.bindings.value.onStopPreview()
            assertEquals(1, stops)
            val completion = Thread { pending.complete(false) }.apply { isDaemon = true; start() }
            completion.join(3000)
            assertFalse("preview completion deadlocked with adapter", completion.isAlive)
            assertFalse(adapter.bindings.value.previewActive)
            adapter.bindings.value.onStopPreview()
            assertEquals(2, stops)
            adapter.bindings.value.onStartPreview(model, 15)
            val successor = adapter.bindings.value.previewOwner
            assertTrue(adapter.bindings.value.previewActive)
            original.onStopPreview()
            assertTrue(adapter.bindings.value.previewActive)
            assertSame(successor, backend.activeOwner)
            assertEquals(2, stops)
        } finally { adapter.close(); scope.cancel() }
    }

    private class MemoryLayouts : ClusterSettingsRuntimeAdapter.LayoutStorage {
        var saved: ClusterLayoutModel? = null
        var acknowledge = true
        var hold: CompletableDeferred<Unit>? = null
        override suspend fun read(automatic: ClusterLayoutModel, profileId: String) = saved
        override suspend fun write(automatic: ClusterLayoutModel, profileId: String, custom: ClusterLayoutModel?): Boolean {
            hold?.await()
            if (acknowledge) saved = custom
            return acknowledge
        }
    }
    private fun display(): QualifiedClusterDisplay {
        val p = ClusterDisplayProfiles.AEGEAN_ORANGE_239
        return requireNotNull(ClusterDisplayProfiles.qualify(p.vehicle, listOf(ClusterDisplayBinding(p.role, 7)),
            ClusterHostGeometry(7, p.panel, p.panel.bounds, p.panel.bounds)))
    }
}
