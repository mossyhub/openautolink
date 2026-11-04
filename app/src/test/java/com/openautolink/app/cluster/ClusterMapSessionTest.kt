package com.openautolink.app.cluster

import android.content.Intent
import android.graphics.Rect
import android.os.Looper
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SessionInfo
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.navigation.NavigationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.openautolink.app.transport.aasdk.AasdkSecondaryVideoOffer
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(manifest = Config.NONE, application = android.app.Application::class, sdk = [32])

@OptIn(ExperimentalCoroutinesApi::class)
class ClusterMapSessionTest {
    private val dispatcher = StandardTestDispatcher()
    private val appManager = mockk<AppManager>(relaxed = true)
    private val navigation = mockk<NavigationManager>(relaxed = true)
    private val callbacks = mutableListOf<SurfaceCallback>()
    private val events = mutableListOf<String>()
    private val physicalActivities = mutableListOf<android.app.Activity>()
    private fun placeHost(generation: Long, id: Int): android.app.Activity = mockk<androidx.car.app.activity.CarAppActivity>(relaxed = true) {
        every { getIntent() } returns Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, generation)
        every { getDisplay() } returns mockk { every { displayId } returns id }
    }.also { physicalActivities += it; ClusterPhysicalHostState.record(generation, it) }
    private lateinit var manager: ClusterBindingRegistry.ManagerLease
    private val profile = ClusterDisplayProfiles.AEGEAN_ORANGE_239
    private val qualified = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
        listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 7)),
        ClusterHostGeometry(7, profile.panel, PixelRect(0,135,2000,810), PixelRect(0,135,2000,810))))
    private var handoffDetach = java.util.concurrent.CompletableFuture.completedFuture(true)
    private val bridge = object : ClusterHostOutputBridge {
        override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = java.util.concurrent.CompletableFuture.completedFuture(true).also { events += "attach" }
        override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = java.util.concurrent.CompletableFuture.completedFuture(true).also { events += "bounds" }
        override fun detach(lease: ClusterOutputLease, surface: Surface) = handoffDetach.also { events += "detach" }
    }
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        mockkObject(com.openautolink.app.diagnostics.DiagnosticLog)
        every { com.openautolink.app.diagnostics.DiagnosticLog.i(any(), any()) } just Runs
        every { com.openautolink.app.diagnostics.DiagnosticLog.w(any(), any()) } just Runs
        every { com.openautolink.app.diagnostics.DiagnosticLog.e(any(), any()) } just Runs
        every { com.openautolink.app.diagnostics.DiagnosticLog.d(any(), any()) } just Runs
        mockkConstructor(CarContext::class)
        every { anyConstructed<CarContext>().packageName } returns "com.openautolink.app"
        every { anyConstructed<CarContext>().resources } returns mockk(relaxed = true) {
            every { getResourceName(any()) } returns "com.openautolink.app:drawable/test"
        }
        every { anyConstructed<CarContext>().getCarService(AppManager::class.java) } returns appManager
        every { anyConstructed<CarContext>().getCarService(NavigationManager::class.java) } returns navigation
        every { anyConstructed<CarContext>().display } returns mockk { every { displayId } returns 7 }
        every { appManager.setSurfaceCallback(capture(callbacks)) } just Runs
        ClusterNavigationState.clear()
        manager = ClusterBindingState.openManager()
        placeHost(manager.generation, 7)
    }
    @After fun cleanup() {
        ClusterMapSession.invalidateBindingGeneration(manager.generation, "test cleanup")
        ClusterMainSession.invalidateBindingGeneration(manager.generation, "test cleanup")
        ClusterBindingState.closeManager(manager)
        ClusterNavigationState.clear()
        physicalActivities.forEach { ClusterPhysicalHostState.destroy(it) }
        Dispatchers.resetMain()
        unmockkAll()
    }
    private fun session(combined: Boolean = false): ClusterMapSession {
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation),
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, nativeIconsWithMap = combined),
            ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val coordinator = ClusterOutputCoordinator(snapshot, bridge)
        return ClusterMapSession(snapshot, coordinator, SessionInfo.DISPLAY_TYPE_MAIN).also {
            it.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, manager.generation))
            (it.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
    }
    private fun surface(): SurfaceContainer = mockk {
        every { surface } returns mockk<Surface>(relaxed = true) { every { isValid } returns true }
        every { width } returns 2000
        every { height } returns 810
    }
    private fun service(rendererGranted: Boolean = true, placeFixture: Boolean = true): OalClusterService {
        if (placeFixture) ClusterBindingState.frozenMapBinding()?.let { placeHost(it.generation, it.snapshot.display.displayId) }
        val info = android.content.pm.PackageInfo().apply {
            packageName = "com.gm.templates.host"
            applicationInfo = android.content.pm.ApplicationInfo().apply { uid = 22222 }
            signingInfo = mockk {
                every { hasMultipleSigners() } returns false
                every { signingCertificateHistory } returns arrayOf(android.content.pm.Signature(byteArrayOf(1,2,3)))
                every { apkContentsSigners } returns arrayOf(android.content.pm.Signature(byteArrayOf(1,2,3)))
            }
            requestedPermissions = arrayOf(androidx.car.app.validation.HostValidator.TEMPLATE_RENDERER_PERMISSION)
            requestedPermissionsFlags = intArrayOf(if (rendererGranted) android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED else 0)
        }
        val pm = mockk<android.content.pm.PackageManager> {
            every { getPackageInfo("com.gm.templates.host", any<Int>()) } returns info
        }
        return spyk(OalClusterService()).also {
            every { it.packageManager } returns pm
            every { it.hostInfo } returns androidx.car.app.HostInfo("com.gm.templates.host", 22222)
        }
    }
    @Test fun stoppedMapSessionReportsReturnWithoutTakingDriverPageOrRebindingSurface() {
        val hosted = session()
        val container = surface()
        val exact = requireNotNull(container.surface)
        val cb = callbacks.single()
        cb.onSurfaceAvailable(container)
        cb.onVisibleAreaChanged(Rect(0,135,2000,810))
        cb.onStableAreaChanged(Rect(0,135,2000,810))
        val lifecycle = hosted.lifecycle as LifecycleRegistry
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertEquals(listOf("attach"), events)
        assertTrue(ClusterBindingState.hasReadySession(manager))
        verify { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", match {
            it.startsWith("OPTIONAL_VISIBILITY_V1 ") && it.contains("event=stop ") &&
                it.contains("surfaceValid=true") && it.contains("gpuAttached=true") &&
                it.contains("oemVisibility=unknown")
        }) }
        // OEM page return need not redeliver the unchanged Surface.
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        verify { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", match {
            it.startsWith("OPTIONAL_VISIBILITY_V1 ") && it.contains("event=resume ") && it.contains("surfaceValid=true")
        }) }
        assertEquals(listOf("attach"), events)
        assertTrue(ClusterBindingState.hasReadySession(manager))
        verify(exactly = 0) { navigation.navigationStarted() }
        verify(exactly = 0) { exact.release() }
    }

    @Test fun hostVisibilitySampleContinuesWhileStoppedButStopsAtExactRetirement() {
        val hosted = session()
        val lifecycle = hosted.lifecycle as LifecycleRegistry
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        clearMocks(com.openautolink.app.diagnostics.DiagnosticLog, answers = false)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
        verify(exactly = 1) { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", match {
            it.startsWith("OPTIONAL_VISIBILITY_V1 ") && it.contains("event=sample ") && it.contains("lifecycle=CREATED")
        }) }
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        clearMocks(com.openautolink.app.diagnostics.DiagnosticLog, answers = false)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(15))
        verify(exactly = 0) { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", match {
            it.startsWith("OPTIONAL_VISIBILITY_V1 ") && it.contains("event=sample ")
        }) }
    }

    @Test fun destroyedSurfaceReceiptNamesExactHandleBeforeClearAndStaleCallbacksStayHarmless() {
        session()
        val a = surface(); val b = surface(); val cb = callbacks.single()
        val exactA = requireNotNull(a.surface); val exactB = requireNotNull(b.surface)
        val idA = System.identityHashCode(exactA); val idB = System.identityHashCode(exactB)
        fun available(c: SurfaceContainer) {
            cb.onSurfaceAvailable(c)
            cb.onVisibleAreaChanged(Rect(0,135,2000,810))
            cb.onStableAreaChanged(Rect(0,135,2000,810))
        }
        available(a); available(b)
        clearMocks(com.openautolink.app.diagnostics.DiagnosticLog, answers = false)
        cb.onSurfaceDestroyed(a)
        verify { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", match {
            it.startsWith("OPTIONAL_VISIBILITY_V1 ") && it.contains("event=surface_destroyed ") &&
                it.contains("callbackSurface=$idA ") && it.contains("accepted=false")
        }) }
        assertTrue(ClusterBindingState.hasReadySession(manager))
        cb.onSurfaceDestroyed(b)
        verify { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", match {
            it.startsWith("OPTIONAL_VISIBILITY_V1 ") && it.contains("event=surface_destroyed ") &&
                it.contains("callbackSurface=$idB ") && it.contains("accepted=true")
        }) }
        assertFalse(ClusterBindingState.hasReadySession(manager))
        verify(exactly = 0) { exactA.release(); exactB.release() }
    }

    @Test fun physicalHostStopAndReturnAreObservableWithoutDestroyingExactReceipt() {
        val activity = physicalActivities.single()
        ClusterBootstrapWindowProtector.onActivityStarted(activity)
        ClusterBootstrapWindowProtector.onActivityResumed(activity)
        ClusterBootstrapWindowProtector.onActivityPaused(activity)
        ClusterBootstrapWindowProtector.onActivityStopped(activity)
        assertSame(activity, ClusterPhysicalHostState.current(manager.generation)?.activity?.get())
        verify { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", match {
            it.startsWith("OPTIONAL_ACTIVITY_V1 ") && it.contains("event=stop ") &&
                it.contains("generation=${manager.generation} ") && it.contains("current=true")
        }) }
        ClusterBootstrapWindowProtector.onActivityStarted(activity)
        ClusterBootstrapWindowProtector.onActivityResumed(activity)
        assertSame(activity, ClusterPhysicalHostState.current(manager.generation)?.activity?.get())
        verify { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", match {
            it.startsWith("OPTIONAL_ACTIVITY_V1 ") && it.contains("event=resume ") && it.contains("current=true")
        }) }
    }

    @Test fun explicitReconnectRetainsExactLiveHostAndReadsDelayedExpansion() {
        val owner = ClusterManager(mockk(relaxed = true))
        val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
        val oldLease = requireNotNull(owner.beginMapOutput(91, selection,
            ClusterProjectionProtocol.ANDROID_AUTO, qualified, bridge))
        val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
        val hosted = service().onCreateSession(SessionInfo(SessionInfo.DISPLAY_TYPE_MAIN, "test"))
        hosted.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, binding.generation))
        val cb = callbacks.single()
        val container = surface()
        cb.onSurfaceAvailable(container)
        cb.onVisibleAreaChanged(Rect(0,135,2000,810))
        cb.onStableAreaChanged(Rect(0,135,2000,810))
        cb.onVisibleAreaChanged(Rect(0,0,2000,810))
        assertTrue(binding.coordinator.isHostAttached())
        try {
            val retain = owner.javaClass.methods.singleOrNull { it.name == "retainMapOutputForReconnect" }
            assertNotNull("Explicit reconnect must preserve the live AndroidX host, not reseed135", retain)
            val ticket = requireNotNull(retain!!.invoke(owner, oldLease, selection))
            val ready = ticket.javaClass.getMethod("getReady").invoke(ticket) as java.util.concurrent.CompletableFuture<*>
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(true, ready.getNow(null))
            assertTrue(ClusterBindingState.hasLiveSession(ClusterBindingRegistry.ManagerLease(binding.generation)))
            val read = owner.javaClass.methods.single { it.name == "readRetainedHost" }.invoke(owner, ticket) as QualifiedClusterDisplay
            assertEquals(PixelRect(98,65,1904,745), read.effectiveOutput)
            assertEquals(qualified.safe, read.safe)
            val successor = owner.javaClass.methods.single { it.name == "resumeRetainedMapOutput" }.invoke(owner,
                ticket, 92L, selection, ClusterProjectionProtocol.ANDROID_AUTO, read, bridge) as java.util.concurrent.CompletableFuture<*>
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
            val nextLease = successor.getNow(null) as ClusterOutputLease
            assertEquals(oldLease.outputEpoch, nextLease.outputEpoch)
            assertEquals(92L, nextLease.projectionEpoch)
            assertSame(binding, ClusterBindingState.frozenMapBinding())
            assertTrue(binding.coordinator.isHostAttached())
            assertEquals(1, callbacks.size)
            assertEquals(2, events.count { it == "attach" })
            assertEquals(1, events.count { it == "detach" })
            assertEquals(false, binding.coordinator.attachHost(oldLease, hosted, container.surface!!, read.host).getNow(true))
        } finally { owner.release() }
    }

    @Test fun explicitAaReconnectUsesRetainedHostInProductionOfferFactory() = runtimeExpandedReconnect(false)
    @Test fun explicitCpReconnectUsesRetainedHostInProductionConfigurationFactory() = runtimeExpandedReconnect(true)
    private fun runtimeExpandedReconnect(carPlay: Boolean) {
        val (context, launches) = driverContext()
        every { context.applicationContext } returns context
        val pref = mockk<com.openautolink.app.data.AppPreferences>()
        mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns pref
        coEvery { pref.getClusterLayout(any(), any()) } returns com.openautolink.app.data.ClusterLayoutRead(null,
            com.openautolink.app.data.ClusterLayoutUnavailableReason.NO_CUSTOM_LAYOUT)
        val descriptor = com.openautolink.app.secondary.SecondaryDisplayDescriptor(
            com.openautolink.app.secondary.SecondaryOutputId("actual-gm"), 7, 1, "GM", 2000, 810, 160,
            com.openautolink.app.secondary.SecondaryRoute.GM_HOSTED,
            com.openautolink.app.secondary.SecondaryReason.NONE, "Qualified driver cluster")
        val inventory = kotlinx.coroutines.flow.MutableStateFlow(com.openautolink.app.secondary.SecondaryInventorySnapshot(1, listOf(descriptor)))
        val drivers = mutableListOf<ClusterRenderDriver>()
        val runtime = com.openautolink.app.secondary.SecondaryOutputRuntime(context, inventory, { mapping, name ->
            val driver = object : ClusterRenderDriver {
                override fun initialize() {}
                override fun configure(config: ClusterCodecConfig) {}
                override fun submit(frame: ClusterEncodedFrame) = true
                override fun poll() = ClusterRenderProgress(0,0)
                override fun stopDecoder() {}
                override fun clearOutput() {}
                override fun detach() = true
            }
            drivers += driver
            ClusterProjectionOutput(mapping, ClusterRenderDriverFactory { _, _ -> driver }, name)
        }, { _, _ -> "exact.avc" })
        val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
        val preferences = com.openautolink.app.secondary.SecondaryPreferencesSnapshot(1,
            mapOf(descriptor.id to com.openautolink.app.secondary.SecondaryOutputPreference(true,
                com.openautolink.app.secondary.AaSecondaryRole.CLUSTER, true, true)),
            com.openautolink.app.data.ClusterOutputPreferences(selection, ClusterDisplayMode.MAP_VIDEO))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        fun pump(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!predicate() && System.nanoTime() < deadline) {
                org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(2)
            }
            assertTrue("Production runtime condition timed out", predicate())
        }
        try {
            val first = scope.async { if (carPlay) runtime.prepareCarPlay(101, preferences)
                else runtime.prepareAndroidAuto(101, preferences) }
            pump { ClusterBindingState.frozenMapBinding() != null }
            val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
            val hosted = service().onCreateSession(SessionInfo(SessionInfo.DISPLAY_TYPE_MAIN, "actual"))
            hosted.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, binding.generation))
            val callback = callbacks.single(); val container = surface()
            callback.onSurfaceAvailable(container)
            callback.onVisibleAreaChanged(Rect(0,135,2000,810))
            callback.onStableAreaChanged(Rect(0,135,2000,810))
            pump { first.isCompleted }
            val initial = kotlinx.coroutines.runBlocking { first.await() }
            assertNotNull(runtime.mapping(descriptor.id))
            callback.onVisibleAreaChanged(Rect(0,0,2000,810))
            val frozen = runtime.mapping(descriptor.id) as ClusterVideoMapping
            assertEquals(PixelRect(98,135,1904,745), frozen.qualifiedDisplay.effectiveOutput)
            val method = runtime.javaClass.methods.singleOrNull { it.name == "retainForExplicitReconnect" }
            assertNotNull("Production reconnect must consume retained live host instead of qualifiedInitialDisplay", method)
            assertEquals(true, method!!.invoke(runtime))
            val retirement = if (carPlay) (initial as com.openautolink.app.secondary.PreparedCarPlaySecondaryOutput).retire("explicit reconnect")
                else (initial as com.openautolink.app.secondary.PreparedAaSecondaryOutputs).retire("explicit reconnect")
            pump { retirement.isDone }
            assertTrue(retirement.get())
            assertSame(binding, ClusterBindingState.frozenMapBinding())
            val second = scope.async { if (carPlay) runtime.prepareCarPlay(102, preferences)
                else runtime.prepareAndroidAuto(102, preferences) }
            pump { second.isCompleted }
            val result = kotlinx.coroutines.runBlocking { second.await() }
            val expanded = runtime.mapping(descriptor.id) as ClusterVideoMapping
            assertEquals(PixelRect(98,65,1904,745), expanded.qualifiedDisplay.effectiveOutput)
            assertEquals(qualified.safe, expanded.qualifiedDisplay.safe)
            if (carPlay) {
                val configuration = (result as com.openautolink.app.secondary.PreparedCarPlaySecondaryOutput).configuration
                assertNotNull(configuration)
                assertNotEquals((initial as com.openautolink.app.secondary.PreparedCarPlaySecondaryOutput).configuration!!.display.safeArea,
                    configuration!!.display.safeArea)
                fun serialize(configuration: com.openautolink.carplaylab.media.CarPlayClusterVideoConfiguration): Pair<ByteArray, Map<*, *>> {
                    val info = com.shilapi.xcertplay.airplay.AirPlayInfoPlist.build(
                        com.shilapi.xcertplay.airplay.AirPlayConfig("fixture", "00:00:00:00:00:00", "00:00:00:00:00:00", "fixture",
                            com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(1280,720), cluster = configuration.display,
                            disableAudioOutput = true), availableAudioFormats = emptySet())
                    val bytes = com.shilapi.xcertplay.airplay.BplistCodec.encode(info)
                    return bytes to (com.shilapi.xcertplay.airplay.BplistCodec.decode(bytes) as Map<*, *>)
                }
                val (oldBytes, oldInfo) = serialize(initial.configuration!!)
                val (newBytes, newInfo) = serialize(configuration)
                val oldDisplays = oldInfo["displays"] as List<*>
                val newDisplays = newInfo["displays"] as List<*>
                assertEquals(oldDisplays.first(), newDisplays.first()) // Main110 stays byte-equivalent.
                val alternate = newDisplays[1] as Map<*, *>
                assertEquals(111L, (alternate["type"] as Number).toLong())
                val view = (alternate["viewAreas"] as List<*>).single() as Map<*, *>
                val wireSafe = view["safeArea"] as Map<*, *>
                assertEquals(configuration.display.safeArea!!.top.toLong(), (wireSafe["originYPixels"] as Number).toLong())
                assertEquals(false, wireSafe["drawUIOutsideSafeArea"])
                System.getProperty("handoff.receipt.dir")?.let { dir ->
                    java.io.File(dir).mkdirs()
                    java.io.File(dir, "cp-before.bplist").writeBytes(oldBytes)
                    java.io.File(dir, "cp-after.bplist").writeBytes(newBytes)
                }

            } else {
                val offer = (result as com.openautolink.app.secondary.PreparedAaSecondaryOutputs).offers.single()
                assertEquals(expanded.codecCrop.top, offer.cropTop)
                assertEquals(expanded.phoneSafeInsets.top, offer.safeTop)
                assertNotEquals((initial as com.openautolink.app.secondary.PreparedAaSecondaryOutputs).offers.single().cropTop, offer.cropTop)
                System.getProperty("handoff.receipt.dir")?.let { dir ->
                    val fields = listOf("channelId", "protocolDisplayId", "displayType", "density", "cropLeft", "cropTop", "cropRight", "cropBottom",
                        "safeLeft", "safeTop", "safeRight", "safeBottom", "stableLeft", "stableTop", "stableRight", "stableBottom")
                    fun json(value: AasdkSecondaryVideoOffer) = org.json.JSONObject().also { receipt ->
                        fields.forEach { field -> receipt.put(field, value.javaClass.getField(field).getInt(value)) }
                    }
                    java.io.File(dir).mkdirs()
                    java.io.File(dir, "aa-runtime-offers.json").writeText(org.json.JSONObject()
                        .put("before", json(initial.offers.single())).put("after", json(offer)).toString())
                }

            }
            assertSame(binding, ClusterBindingState.frozenMapBinding())
            assertEquals(1, launches.size)
            assertEquals(1, callbacks.size)
            assertEquals(2, drivers.size)
        } finally {
            val close = runtime.close(); pump { close.isDone }; assertTrue(close.get())
            scope.cancel()
        }
    }

    @Test fun expandedLiveDisplayMakesEditorBackgroundAndEssentialBoundsDistinct() {
        val expanded = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role, 7)), qualified.host.copy(visible = PixelRect(0,0,2000,810))))
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        val storage = object : com.openautolink.app.ui.settings.ClusterSettingsRuntimeAdapter.LayoutStorage {
            override suspend fun read(automatic: com.openautolink.app.ui.settings.ClusterLayoutModel, profileId: String) = null
            override suspend fun write(automatic: com.openautolink.app.ui.settings.ClusterLayoutModel, profileId: String,
                custom: com.openautolink.app.ui.settings.ClusterLayoutModel?) = true
        }
        val adapter = kotlinx.coroutines.runBlocking {
            com.openautolink.app.ui.settings.ClusterSettingsRuntimeAdapter.create(expanded,
                kotlinx.coroutines.flow.MutableStateFlow(com.openautolink.app.ui.settings.ClusterSettingsObservation()),
                storage, null, scope)
        }
        try {
            val model = requireNotNull(adapter.bindings.value.layout)
            assertEquals(PixelRect(98,65,1904,745), model.canvas)
            val essential = model.javaClass.methods.singleOrNull { it.name == "getEssentialCanvas" }
            assertNotNull("Editor must constrain essential edits separately from map background", essential)
            assertEquals(PixelRect(0,135,2000,810), essential!!.invoke(model))
            val moved = model.adjust(com.openautolink.app.ui.settings.ClusterLayoutTarget.SAFE, y = 65)
            assertEquals(135, moved.safe.top)
        } finally { adapter.close(); scope.cancel() }
    }

    @Test fun liveHostCallbackExpandsExistingEditorAndLossRevokesExpandedCanvas() {
        val observations = kotlinx.coroutines.flow.MutableStateFlow(com.openautolink.app.ui.settings.ClusterSettingsObservation())
        val storage = object : com.openautolink.app.ui.settings.ClusterSettingsRuntimeAdapter.LayoutStorage {
            override suspend fun read(automatic: com.openautolink.app.ui.settings.ClusterLayoutModel, profileId: String) = null
            override suspend fun write(automatic: com.openautolink.app.ui.settings.ClusterLayoutModel, profileId: String,
                custom: com.openautolink.app.ui.settings.ClusterLayoutModel?) = true
        }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatcher)
        val adapter = kotlinx.coroutines.runBlocking { com.openautolink.app.ui.settings.ClusterSettingsRuntimeAdapter.create(
            qualified, observations, storage, null, scope) }
        try {
            assertEquals(135, adapter.bindings.value.layout!!.canvas.top)
            val expanded = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
                listOf(ClusterDisplayBinding(profile.role,7)), qualified.host.copy(visible=PixelRect(0,0,2000,810))))
            observations.value = com.openautolink.app.ui.settings.ClusterSettingsObservation(liveDisplay=expanded)
            dispatcher.scheduler.runCurrent()
            assertEquals(PixelRect(98,65,1904,745), adapter.bindings.value.layout!!.canvas)
            assertEquals(qualified.host.essentialCanvas, adapter.bindings.value.layout!!.essentialCanvas)
            observations.value = com.openautolink.app.ui.settings.ClusterSettingsObservation()
            dispatcher.scheduler.runCurrent()
            assertEquals(135, adapter.bindings.value.layout!!.canvas.top)
        } finally { adapter.close(); scope.cancel() }
    }

    private fun retainedHostFixture(action: (ClusterManager, ClusterOutputLease, SurfaceCallback, SurfaceContainer) -> Unit) {
        val owner = ClusterManager(mockk(relaxed = true))
        val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
        val lease = requireNotNull(owner.beginMapOutput(91, selection, ClusterProjectionProtocol.ANDROID_AUTO, qualified, bridge))
        val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
        val hosted = service().onCreateSession(SessionInfo(SessionInfo.DISPLAY_TYPE_MAIN, "test"))
        hosted.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, binding.generation))
        val cb = callbacks.single(); val container = surface()
        cb.onSurfaceAvailable(container); cb.onVisibleAreaChanged(Rect(0,135,2000,810)); cb.onStableAreaChanged(Rect(0,135,2000,810))
        cb.onVisibleAreaChanged(Rect(0,0,2000,810))
        try { action(owner, lease, cb, container) } finally {
            handoffDetach.complete(true); handoffDetach = java.util.concurrent.CompletableFuture.completedFuture(true)
            owner.release(); org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle(); owner.release()
        }
    }
    @Test fun explicitHandoffPendingOldGpuRetirementWithholdsFreshDescriptor() = retainedHostFixture { owner, lease, _, _ ->
        handoffDetach = java.util.concurrent.CompletableFuture()
        val ticket = requireNotNull(owner.retainMapOutputForReconnect(lease, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)))
        assertFalse(ticket.ready.isDone)
        assertNull(owner.readRetainedHost(ticket))
        assertNull(owner.retainMapOutputForReconnect(lease, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)))
        handoffDetach.complete(true)
        assertTrue(ticket.ready.getNow(false))
        assertNotNull(owner.readRetainedHost(ticket))
    }
    @Test fun explicitHandoffFailedOldGpuRetirementNeverAuthorizesDescriptor() = retainedHostFixture { owner, lease, _, _ ->
        handoffDetach = java.util.concurrent.CompletableFuture.completedFuture(false)
        val ticket = requireNotNull(owner.retainMapOutputForReconnect(lease, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)))
        assertFalse(ticket.ready.getNow(true))
        assertNull(owner.readRetainedHost(ticket))
        assertFalse(ClusterBindingState.frozenMapBinding()!!.coordinator.isHostAttached())
    }
    @Test fun explicitHandoffChangedEssentialCanvasRevokesSameSurface() = retainedHostFixture { owner, lease, cb, _ ->
        val ticket = requireNotNull(owner.retainMapOutputForReconnect(lease, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)))
        cb.onStableAreaChanged(Rect(0,136,2000,810))
        assertNull(owner.readRetainedHost(ticket))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(ClusterBindingState.frozenMapBinding())
    }
    @Test fun explicitHandoffRealSurfaceDestructionRevokesCapturedHost() = retainedHostFixture { owner, lease, cb, container ->
        val ticket = requireNotNull(owner.retainMapOutputForReconnect(lease, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)))
        cb.onSurfaceDestroyed(container)
        assertNull(owner.readRetainedHost(ticket))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(ClusterBindingState.frozenMapBinding())
    }
    @Test fun explicitHandoffChangedSelectionCannotReuseFrozenSession() = retainedHostFixture { owner, lease, _, _ ->
        val ticket = requireNotNull(owner.retainMapOutputForReconnect(lease, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)))
        val display = requireNotNull(owner.readRetainedHost(ticket))
        val rejected = owner.resumeRetainedMapOutput(ticket, 92, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO, nativeIconsWithMap = true),
            ClusterProjectionProtocol.ANDROID_AUTO, display, bridge)
        assertNull(rejected.getNow(null))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(owner.readRetainedHost(ticket))
        assertNull(ClusterBindingState.frozenMapBinding())
    }
    @Test fun explicitHandoffWrongHostCannotPublishExpandedDescriptor() = retainedHostFixture { owner, lease, _, _ ->
        val ticket = requireNotNull(owner.retainMapOutputForReconnect(lease, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)))
        val wrong = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(profile.role, 8)), qualified.host.copy(displayId = 8, visible = PixelRect(0,0,2000,810))))
        assertNull(owner.resumeRetainedMapOutput(ticket, 92, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO),
            ClusterProjectionProtocol.ANDROID_AUTO, wrong, bridge).getNow(null))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertNull(owner.readRetainedHost(ticket))
    }

    @Test fun explicitHandoffDeadlinePerformsFullTeardownWithoutKeepalive() = retainedHostFixture { owner, lease, _, _ ->
        val ticket = requireNotNull(owner.retainMapOutputForReconnect(lease, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(16))
        assertNull(owner.readRetainedHost(ticket))
        assertNull(ClusterBindingState.frozenMapBinding())
    }
    @Test fun explicitHandoffOrdinaryFullStopRevokesPendingPredecessor() = retainedHostFixture { owner, lease, _, _ ->
        handoffDetach = java.util.concurrent.CompletableFuture()
        val ticket = requireNotNull(owner.retainMapOutputForReconnect(lease, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)))
        owner.release()
        assertNull(owner.readRetainedHost(ticket))
        handoffDetach.complete(true)
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertFalse(ticket.ready.getNow(true))
        assertNull(ClusterBindingState.frozenMapBinding())
    }

    @Test fun actualPhysicalActivityTwoAcceptsAndroidXVirtual55And56() {
        val physical = requireNotNull(ClusterDisplayProfiles.qualify(profile.vehicle,
            listOf(ClusterDisplayBinding(ClusterVehicleRole.DRIVER_CLUSTER, 2)),
            ClusterHostGeometry(2, profile.panel, PixelRect(0,135,2000,810), PixelRect(0,135,2000,810))))
        val owner = ClusterManager(mockk(relaxed = true))
        owner.beginMapOutput(91, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO),
            ClusterProjectionProtocol.ANDROID_AUTO, physical, bridge)
        val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
        val activity = mockk<androidx.car.app.activity.CarAppActivity>(relaxed = true) {
            every { getIntent() } returns Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, binding.generation)
            every { getDisplay() } returns mockk { every { displayId } returns 2 }
        }
        try {
            ClusterBootstrapWindowProtector.onActivityPreCreated(activity, null)
            every { anyConstructed<CarContext>().display } returns mockk { every { displayId } returns 55 }
            val hosted = service(placeFixture = false).onCreateSession(SessionInfo(SessionInfo.DISPLAY_TYPE_MAIN, "test"))
            hosted.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, binding.generation))
            val cb = callbacks.single()
            cb.onSurfaceAvailable(surface())
            cb.onVisibleAreaChanged(Rect(0,135,2000,810))
            cb.onStableAreaChanged(Rect(0,135,2000,810))
            assertEquals("Actual physical2 must attach despite virtual55", 1, events.count { it == "attach" })
            assertTrue(binding.coordinator.isHostAttached())
            every { anyConstructed<CarContext>().display } returns mockk { every { displayId } returns 56 }
            cb.onStableAreaChanged(Rect(0,135,2000,810))
            assertTrue("Virtual context recreation must not revoke physical host", binding.coordinator.isHostAttached())
            verify(exactly = 0) { anyConstructed<CarContext>().getCarService(NavigationManager::class.java) }
            ClusterBootstrapWindowProtector.onActivityDestroyed(activity)
            assertFalse(binding.coordinator.isHostAttached())
            val attachedBefore = events.count { it == "attach" }
            cb.onSurfaceAvailable(surface())
            cb.onVisibleAreaChanged(Rect(0,135,2000,810))
            cb.onStableAreaChanged(Rect(0,135,2000,810))
            assertEquals("Destroyed host cannot reattach", attachedBefore, events.count { it == "attach" })
        } finally {
            ClusterBootstrapWindowProtector.onActivityDestroyed(activity)
            owner.release()
        }
    }
    @Test fun mapSessionRevalidatesRendererPermissionEvenAfterLegacyValidatorWasCreated() {
        val owner = ClusterManager(mockk(relaxed = true))
        val service = service(false)
        assertSame(androidx.car.app.validation.HostValidator.ALLOW_ALL_HOSTS_VALIDATOR, service.createHostValidator())
        owner.beginMapOutput(91, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified, bridge)
        val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
        try {
            service.onCreateSession(SessionInfo(SessionInfo.DISPLAY_TYPE_MAIN, "test")).onCreateScreen(
                Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, binding.generation))
            assertTrue("untrusted host cannot install map callbacks", callbacks.isEmpty())
            verify(exactly = 0) { anyConstructed<CarContext>().getCarService(NavigationManager::class.java) }
        } finally { owner.release() }
    }
    private fun driverContext(): Pair<android.content.Context, MutableList<Pair<Intent, android.os.Bundle>>> {
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "PRODUCT", profile.vehicle.product)
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build::class.java, "ID", profile.vehicle.buildFamily)
        org.robolectric.util.ReflectionHelpers.setStaticField(android.os.Build.VERSION::class.java, "INCREMENTAL", "239")
        val display = mockk<android.view.Display> {
            every { displayId } returns 7
            every { isValid } returns true
            every { state } returns android.view.Display.STATE_ON
            every { flags } returns 0
            every { name } returns "sourced-driver-panel"
            every { deviceProductInfo } returns android.hardware.display.DeviceProductInfo("driver", "GMC", "fixture", 2023,
                android.hardware.display.DeviceProductInfo.CONNECTION_TO_SINK_BUILT_IN)
            every { getRealSize(any()) } answers { firstArg<android.graphics.Point>().set(2000,810) }
        }
        val displays = mockk<android.hardware.display.DisplayManager> { every { getDisplays() } returns arrayOf(display) }
        val am = mockk<android.app.ActivityManager>(relaxed = true) {
            every { isActivityStartAllowedOnDisplay(any(), 7, any()) } returns true
        }
        val launches = mutableListOf<Pair<Intent, android.os.Bundle>>()
        val context = mockk<android.content.Context>(relaxed = true) {
            every { getSystemService(android.hardware.display.DisplayManager::class.java) } returns displays
            every { getSystemService(android.app.ActivityManager::class.java) } returns am
            every { packageName } returns "com.openautolink.app"
            every { startActivity(any(), any()) } answers { launches += firstArg<Intent>() to secondArg<android.os.Bundle>() }
        }
        return context to launches
    }
    private fun receipt() = ClusterDriverDisplayReceipt(profile.vehicle, "sourced-driver-panel", null, null, "test source-backed public identity fixture")
    @Test fun publicDriverLaunchAndOnlyOneDestroyedSessionRecovery() {
        val (context, launches) = driverContext()
        val owner = ClusterManager(context)
        val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
        val lease = owner.beginMapOutput(91, selection, ClusterProjectionProtocol.ANDROID_AUTO, qualified, bridge)
        assertEquals(lease, owner.beginMapOutput(91, selection, ClusterProjectionProtocol.ANDROID_AUTO, qualified, bridge))
        assertNull(owner.beginMapOutput(91, selection.copy(nativeIconsWithMap = true), ClusterProjectionProtocol.ANDROID_AUTO, qualified, bridge))
        val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
        assertEquals(1, launches.size)
        assertEquals("com.openautolink.app.cluster.ClusterMapLaunchActivity", launches.single().first.component!!.className)
        assertEquals(7, launches.single().second.getInt("android.activity.launchDisplayId"))
        fun hosted(): androidx.car.app.Session {
            return service().onCreateSession(SessionInfo(SessionInfo.DISPLAY_TYPE_MAIN, "test")).also {
                it.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, binding.generation))
                (it.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                (it.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_START)
            }
        }
        try {
            val first = hosted()
            (first.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            repeat(10) { owner.ensureAlive(); owner.launchClusterBinding() }
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30))
            assertEquals(1, launches.size)
            (first.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
            assertEquals(2, launches.size)
            val second = hosted()
            (second.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            (second.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(1))
            repeat(10) { owner.ensureAlive(); owner.launchClusterBinding() }
            assertEquals(2, launches.size)
        } finally { owner.release() }
    }
    @Test fun managerDisableRetiresMapAndDoesNotDependOnSemanticDemand() {
        val context = mockk<android.content.Context>(relaxed = true)
        val owner = ClusterManager(context)
        val output = owner.beginMapOutput(91, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO),
            ClusterProjectionProtocol.ANDROID_AUTO, qualified, bridge)
        assertNotNull(output)
        assertNull(ClusterNavigationState.recoveryDemand.value)
        val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
        val session = service().onCreateSession(androidx.car.app.SessionInfo(androidx.car.app.SessionInfo.DISPLAY_TYPE_MAIN, "test"))
        assertTrue(session is ClusterMapSession)
        session.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, binding.generation))
        callbacks.single().onSurfaceAvailable(surface())
        callbacks.single().onVisibleAreaChanged(Rect(0,135,2000,810))
        callbacks.single().onStableAreaChanged(Rect(0,135,2000,810))
        owner.setClusterEnabled(false)
        assertEquals(1, events.count { it == "detach" })
        assertFalse(binding.coordinator.isLive())
        owner.release()
    }

    @Test fun exactRetirementFailureBlocksSuccessorUntilAcknowledged() {
        val context = mockk<android.content.Context>(relaxed = true)
        val owner = ClusterManager(context)
        val held = java.util.concurrent.CompletableFuture<Boolean>()
        val debtBridge = object : ClusterHostOutputBridge {
            override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = java.util.concurrent.CompletableFuture.completedFuture(true)
            override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = java.util.concurrent.CompletableFuture.completedFuture(true)
            override fun detach(lease: ClusterOutputLease, surface: Surface) = held
        }
        val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
        val old = requireNotNull(owner.beginMapOutput(91, selection, ClusterProjectionProtocol.ANDROID_AUTO, qualified, debtBridge))
        val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
        val session = service().onCreateSession(SessionInfo(SessionInfo.DISPLAY_TYPE_MAIN, "test"))
        session.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, binding.generation))
        callbacks.single().onSurfaceAvailable(surface())
        callbacks.single().onVisibleAreaChanged(Rect(0,135,2000,810))
        callbacks.single().onStableAreaChanged(Rect(0,135,2000,810))
        try {
            val retiring = owner.endMapOutput(old, selection, "disconnect")
            assertFalse("Main callback returns without GPU wait", retiring.isDone)
            assertNull(owner.beginMapOutput(92, selection, ClusterProjectionProtocol.ANDROID_AUTO, qualified, bridge))
            assertSame(binding, ClusterBindingState.frozenMapBinding())
            held.complete(true)
            assertTrue(retiring.getNow(false))
            val next = requireNotNull(owner.beginMapOutput(92, selection, ClusterProjectionProtocol.ANDROID_AUTO, qualified, bridge))
            assertNotEquals(old, next)
            assertFalse(owner.endMapOutput(old, selection, "stale disconnect").getNow(true))
            assertNotNull(owner.mapOutputCoordinator(next, selection))
            assertTrue(owner.endMapOutput(next, selection, "disconnect").getNow(false))
        } finally { held.complete(true); owner.release() }
    }
    @Test fun geometryChangeRetiresImageAndReadinessWithoutProjectionRecovery() {
        session()
        val callback = callbacks.single()
        callback.onSurfaceAvailable(surface())
        callback.onVisibleAreaChanged(Rect(0,135,2000,810))
        callback.onStableAreaChanged(Rect(0,135,2000,810))
        assertTrue(ClusterBindingState.hasReadySession(manager))
        callback.onVisibleAreaChanged(Rect(0,140,2000,810))
        assertEquals(1, events.count { it == "detach" })
        assertFalse(ClusterBindingState.hasReadySession(manager))
        verify(exactly = 0) { navigation.navigationStarted() }
    }
    @Test fun wrongPhysicalHostAndLogicalClusterSessionCannotAttach() {
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation),
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val coordinator = ClusterOutputCoordinator(snapshot, bridge)
        ClusterMapSession(snapshot, coordinator, SessionInfo.DISPLAY_TYPE_CLUSTER).onCreateScreen(
            Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, manager.generation))
        assertTrue(callbacks.isEmpty())
        val session = ClusterMapSession(snapshot, coordinator, SessionInfo.DISPLAY_TYPE_MAIN)
        session.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, manager.generation))
        placeHost(manager.generation, 0) // A genuinely wrong physical Activity, not a virtual context.
        every { anyConstructed<CarContext>().display } returns mockk { every { displayId } returns 55 }
        callbacks.single().onSurfaceAvailable(surface())
        callbacks.single().onVisibleAreaChanged(Rect(0,135,2000,810))
        callbacks.single().onStableAreaChanged(Rect(0,135,2000,810))
        assertTrue(events.isEmpty())
        assertFalse(ClusterBindingState.hasReadySession(manager))
    }
    @Test fun missingOrStalePhysicalReceiptCannotAttachEvenWhenVirtualIdMatchesDesired() {
        physicalActivities.forEach { ClusterPhysicalHostState.destroy(it) }
        val stale = placeHost(manager.generation - 1, 7)
        assertNull(ClusterPhysicalHostState.current(manager.generation))
        session()
        callbacks.single().onSurfaceAvailable(surface())
        callbacks.single().onVisibleAreaChanged(Rect(0,135,2000,810))
        callbacks.single().onStableAreaChanged(Rect(0,135,2000,810))
        assertTrue(events.isEmpty())
        assertFalse(ClusterBindingState.hasReadySession(manager))
        ClusterPhysicalHostState.destroy(stale)
    }
    @Test fun replacedPhysicalReceiptIsRevokedAndOldDestructionCannotClearSuccessor() {
        val first = requireNotNull(ClusterPhysicalHostState.current(manager.generation))
        session()
        callbacks.single().onSurfaceAvailable(surface())
        callbacks.single().onVisibleAreaChanged(Rect(0,135,2000,810))
        callbacks.single().onStableAreaChanged(Rect(0,135,2000,810))
        assertTrue(ClusterBindingState.hasReadySession(manager))
        val next = placeHost(manager.generation, 7)
        assertFalse(ClusterPhysicalHostState.isCurrent(first))
        assertFalse(ClusterBindingState.hasReadySession(manager))
        assertEquals(1, events.count { it == "detach" })
        ClusterPhysicalHostState.destroy(requireNotNull(first.activity.get()))
        assertSame(next, ClusterPhysicalHostState.current(manager.generation)?.activity?.get())
    }
    @Test fun staleCreatedIconAndMapSessionsCannotAdoptSuccessorGeneration() {
        val oldIcon = ClusterMainSession()
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation),
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val oldMap = ClusterMapSession(snapshot, ClusterOutputCoordinator(snapshot, bridge), SessionInfo.DISPLAY_TYPE_MAIN)
        val owner = ClusterManager(mockk(relaxed = true))
        owner.beginMapOutput(92, snapshot.selection, snapshot.protocol, qualified, bridge)
        try {
            oldIcon.onCreateScreen(Intent())
            oldMap.onCreateScreen(Intent())
            assertTrue(callbacks.isEmpty())
            verify(exactly = 0) { anyConstructed<CarContext>().getCarService(NavigationManager::class.java) }
        } finally { owner.release() }
    }
    @Test fun unknownPublicRoleNeverLaunchesEvenWithMatchingPanel() {
        val (context, launches) = driverContext()
        val owner = ClusterManager(context)
        try {
            owner.beginMapOutput(91, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified, bridge, null)
            repeat(10) { owner.ensureAlive(); owner.launchClusterBinding() }
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(1))
            assertTrue(launches.isEmpty())
        } finally { owner.release() }
    }
    @Test fun mapWindowStaysOpaqueAndNeverMovesBehindAnotherTask() {
        val owner = ClusterManager(mockk(relaxed = true))
        owner.beginMapOutput(91, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified, bridge)
        val binding = requireNotNull(ClusterBindingState.frozenMapBinding())
        val attributes = android.view.WindowManager.LayoutParams()
        val window = mockk<android.view.Window>(relaxed = true) { every { getAttributes() } returns attributes }
        val activity = mockk<androidx.car.app.activity.CarAppActivity>(relaxed = true) {
            every { getIntent() } returns Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, binding.generation)
            every { getWindow() } returns window
        }
        try {
            ClusterBootstrapWindowProtector.onActivityPreCreated(activity, null)
            assertEquals(1f, attributes.alpha)
            verify { activity.setTheme(android.R.style.Theme_Material_NoActionBar) }
            verify { window.setFormat(android.graphics.PixelFormat.OPAQUE) }
            ClusterBootstrapWindowProtector.moveTaskToBack(binding.generation)
            verify(exactly = 0) { activity.moveTaskToBack(any()) }
        } finally { owner.release() }
    }
    @Test fun delayedRendererAckCannotPublishReadyAfterDestroyAndBorrowedSurfaceIsRetained() {
        val selection = ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO)
        val attachAck = java.util.concurrent.CompletableFuture<Boolean>()
        val detachAck = java.util.concurrent.CompletableFuture<Boolean>()
        var detaches = 0
        val async = object : ClusterHostOutputBridge {
            override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = attachAck
            override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = java.util.concurrent.CompletableFuture.completedFuture(true)
            override fun detach(lease: ClusterOutputLease, surface: Surface) = detachAck.also { detaches++ }
        }
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation), selection, ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val coordinator = ClusterOutputCoordinator(snapshot, async)
        val session = ClusterMapSession(snapshot, coordinator, SessionInfo.DISPLAY_TYPE_MAIN)
        session.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, manager.generation))
        val lifecycle = session.lifecycle as LifecycleRegistry
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        callbacks.single().onSurfaceAvailable(surface())
        callbacks.single().onVisibleAreaChanged(Rect(0,135,2000,810))
        callbacks.single().onStableAreaChanged(Rect(0,135,2000,810))
        assertFalse(ClusterBindingState.hasReadySession(manager))
        repeat(7) { callbacks.single().onStableAreaChanged(Rect(0,135,2000,810)) }
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) // saturated queue must retain cleanup
        assertTrue("binding retains exact retirement debt", ClusterBindingState.hasLiveSession(manager))
        attachAck.complete(true)
        assertEquals(1, detaches)
        assertFalse(ClusterBindingState.hasReadySession(manager))
        detachAck.complete(true)
        assertFalse(ClusterBindingState.hasLiveSession(manager))
    }
    @Test fun saturatedRetirementCannotDropExactCleanupOrAcknowledgeBeforeDetach() {
        val attachAck = java.util.concurrent.CompletableFuture<Boolean>()
        val detachAck = java.util.concurrent.CompletableFuture<Boolean>()
        val borrowed = requireNotNull(surface().surface)
        var detaches = 0
        var bounds = 0
        val async = object : ClusterHostOutputBridge {
            override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = attachAck
            override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) =
                java.util.concurrent.CompletableFuture.completedFuture(true).also { bounds++ }
            override fun detach(lease: ClusterOutputLease, surface: Surface) = detachAck.also {
                assertSame(borrowed, surface); detaches++
            }
        }
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation),
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val coordinator = ClusterOutputCoordinator(snapshot, async)
        val hostOwner = Any()
        assertTrue(coordinator.claim(hostOwner))
        val geometry = ClusterHostGeometry(7, profile.panel, PixelRect(0,135,2000,810), PixelRect(0,135,2000,810))
        coordinator.attachHost(snapshot.lease, hostOwner, borrowed, geometry)
        val queued = (1..7).map { coordinator.updateBounds(snapshot.lease, hostOwner, borrowed, geometry) }
        assertTrue(queued.none { it.isDone })
        assertFalse(coordinator.updateBounds(snapshot.lease, hostOwner, borrowed, geometry).getNow(true))
        val retiring = coordinator.retire(snapshot.lease, "exit with saturated queue")
        assertFalse("terminal obligation must wait for actual cleanup", retiring.isDone)
        repeat(1000) { assertSame("repeated exit coalesces", retiring, coordinator.retire(snapshot.lease, "repeat exit")) }
        assertFalse(coordinator.isHostAttached())
        assertFalse(coordinator.isRetirementAcknowledged())
        attachAck.complete(true)
        assertEquals("exact borrowed surface must be detached after stalled attach responds", 1, detaches)
        assertEquals("invalidated bounds cannot supersede retirement", 0, bounds)
        assertFalse(retiring.isDone)
        assertFalse(coordinator.isRetirementAcknowledged())
        detachAck.complete(true)
        assertTrue(retiring.getNow(false))
        assertTrue(coordinator.isRetirementAcknowledged())
        assertTrue(queued.all { it.isDone && !it.getNow(true) })
        assertTrue(coordinator.retire(snapshot.lease, "already stopped").getNow(false))
        assertEquals(1, detaches)
        verify(exactly = 0) { borrowed.release() }
    }
    @Test fun saturatedHostReleaseRetainsFailedCleanupDebtUntilExplicitRetry() = failedSaturatedCleanup(retire = false)
    @Test fun saturatedRetirementRetainsFailedCleanupDebtUntilExplicitRetry() = failedSaturatedCleanup(retire = true)
    private fun failedSaturatedCleanup(retire: Boolean) {
        val attachAck = java.util.concurrent.CompletableFuture<Boolean>()
        var detachAck = java.util.concurrent.CompletableFuture<Boolean>()
        val borrowed = requireNotNull(surface().surface)
        var attaches = 0
        var detaches = 0
        var bounds = 0
        val async = object : ClusterHostOutputBridge {
            override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) =
                attachAck.also { attaches++ }
            override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) =
                java.util.concurrent.CompletableFuture.completedFuture(true).also { bounds++ }
            override fun detach(lease: ClusterOutputLease, surface: Surface) = detachAck.also {
                assertSame(borrowed, surface); detaches++
            }
        }
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation),
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val coordinator = ClusterOutputCoordinator(snapshot, async)
        val hostOwner = Any()
        val successor = Any()
        val geometry = ClusterHostGeometry(7, profile.panel, PixelRect(0,135,2000,810), PixelRect(0,135,2000,810))
        assertTrue(coordinator.claim(hostOwner))
        coordinator.attachHost(snapshot.lease, hostOwner, borrowed, geometry)
        repeat(7) { assertFalse(coordinator.updateBounds(snapshot.lease, hostOwner, borrowed, geometry).isDone) }
        fun stop() = if (retire) coordinator.retire(snapshot.lease, "exit") else coordinator.releaseHost(hostOwner)
        val stopping = stop()
        assertFalse(stopping.isDone)
        repeat(1000) { assertSame(stopping, stop()) }
        assertFalse(coordinator.claim(successor))
        assertFalse(coordinator.attachHost(snapshot.lease, hostOwner, borrowed, geometry).getNow(true))
        attachAck.complete(true)
        assertEquals(1, detaches)
        assertEquals(0, bounds)
        assertFalse(stopping.isDone)
        detachAck.complete(false)
        assertFalse(stopping.getNow(true))
        assertFalse(coordinator.isRetirementAcknowledged())
        assertFalse(coordinator.claim(successor))
        assertFalse(coordinator.claim(hostOwner))
        assertFalse(coordinator.attachHost(snapshot.lease, hostOwner, borrowed, geometry).getNow(true))
        assertEquals(1, attaches)
        detachAck = java.util.concurrent.CompletableFuture<Boolean>()
        val failedRetry = stop()
        assertEquals(2, detaches)
        detachAck.completeExceptionally(IllegalStateException("GPU cleanup failed"))
        assertFalse(failedRetry.getNow(true))
        assertFalse(coordinator.claim(successor))
        detachAck = java.util.concurrent.CompletableFuture<Boolean>()
        val retry = stop()
        repeat(1000) { assertSame(retry, stop()) }
        assertEquals(3, detaches)
        assertFalse(retry.isDone)
        detachAck.complete(true)
        assertTrue(retry.getNow(false))
        assertFalse("old failed acknowledgement is never rewritten", stopping.getNow(true))
        assertEquals(retire, coordinator.isRetirementAcknowledged())
        assertEquals(!retire, coordinator.claim(successor))
        if (!retire) {
            assertTrue(coordinator.attachHost(snapshot.lease, successor, borrowed, geometry).getNow(false))
            assertTrue(coordinator.isHostAttached())
            assertTrue(coordinator.releaseHost(successor).getNow(false))
        }
        verify(exactly = 0) { borrowed.release() }
    }
    @Test fun cleanupContinuationDoesNotHoldCoordinatorWhileWaitingForLifecycle() {
        val ack = java.util.concurrent.CompletableFuture<Boolean>()
        val async = object : ClusterHostOutputBridge {
            override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = java.util.concurrent.CompletableFuture.completedFuture(true)
            override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = java.util.concurrent.CompletableFuture.completedFuture(true)
            override fun detach(lease: ClusterOutputLease, surface: Surface) = ack
        }
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation),
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val coordinator = ClusterOutputCoordinator(snapshot, async)
        val owner = Any()
        assertTrue(coordinator.claim(owner))
        coordinator.attachHost(snapshot.lease, owner, requireNotNull(surface().surface),
            ClusterHostGeometry(7, profile.panel, PixelRect(0,135,2000,810), PixelRect(0,135,2000,810)))
        val retiring = coordinator.releaseHost(owner)
        assertFalse("Main returns before render ack", retiring.isDone)
        val entered = java.util.concurrent.CountDownLatch(1)
        val continuation = retiring.thenAccept {
            val held = Thread.holdsLock(coordinator)
            entered.countDown()
            check(!held) { "external cleanup continuation holds coordinator monitor" }
            synchronized(ClusterBindingLifecycle.lock) { assertTrue(it) }
        }
        val render = Thread { ack.complete(true) }
        synchronized(ClusterBindingLifecycle.lock) {
            render.start()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Old code is caught by the check before it can form a permanent deadlock.
            assertTrue("Main can retire coordinator while render waits for lifecycle",
                coordinator.retire(snapshot.lease, "Main retires during render completion").getNow(false))
        }
        render.join(5000)
        assertFalse(render.isAlive)
        continuation.get(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test fun completedReentrantBridgeCannotCompleteQueuedFutureUnderCoordinatorMonitor() {
        val first = java.util.concurrent.CompletableFuture<Boolean>()
        lateinit var coordinator: ClusterOutputCoordinator
        lateinit var pending: java.util.concurrent.CompletableFuture<Boolean>
        var calls = 0
        val async = object : ClusterHostOutputBridge {
            override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = first
            override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry): java.util.concurrent.CompletableFuture<Boolean> {
                calls++
                coordinator.retire(lease, "inline renderer reentry")
                assertFalse("reentry cannot complete queued controls under monitor", Thread.holdsLock(coordinator))
                return java.util.concurrent.CompletableFuture.completedFuture(true)
            }
            override fun detach(lease: ClusterOutputLease, surface: Surface) = java.util.concurrent.CompletableFuture.completedFuture(true)
        }
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation),
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        coordinator = ClusterOutputCoordinator(snapshot, async)
        val owner = Any(); coordinator.claim(owner)
        val exact = requireNotNull(surface().surface)
        val host = ClusterHostGeometry(7, profile.panel, PixelRect(0,135,2000,810), PixelRect(0,135,2000,810))
        coordinator.attachHost(snapshot.lease, owner, exact, host)
        pending = coordinator.updateBounds(snapshot.lease, owner, exact, host)
        val observer = pending.thenAccept { check(!Thread.holdsLock(coordinator)) { "queued external continuation holds coordinator" } }
        first.complete(true)
        observer.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(1, calls)
        assertTrue(coordinator.isRetirementAcknowledged())
    }

    @Test fun supersededReservedSurfaceNeverCreatesCleanupDebtBeforeEffectSubmission() {
        val ack = java.util.concurrent.CompletableFuture<Boolean>()
        val attached = java.util.concurrent.CopyOnWriteArrayList<Surface>()
        val detached = java.util.concurrent.CopyOnWriteArrayList<Surface>()
        val async = object : ClusterHostOutputBridge {
            override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) =
                java.util.concurrent.CompletableFuture.completedFuture(true).also { attached += surface }
            override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) =
                java.util.concurrent.CompletableFuture.completedFuture(true).also { fail("latest successor submits only once") }
            override fun detach(lease: ClusterOutputLease, surface: Surface) =
                if (detached.isEmpty()) ack.also { detached += surface }
                else java.util.concurrent.CompletableFuture.completedFuture(true).also { detached += surface }
        }
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation),
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val coordinator = ClusterOutputCoordinator(snapshot, async)
        val session = ClusterMapSession(snapshot, coordinator, SessionInfo.DISPLAY_TYPE_MAIN)
        session.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, manager.generation))
        val registry = session.lifecycle as LifecycleRegistry
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val callback = callbacks.single()
        fun available(container: SurfaceContainer) {
            callback.onSurfaceAvailable(container)
            callback.onVisibleAreaChanged(Rect(0,135,2000,810))
            callback.onStableAreaChanged(Rect(0,135,2000,810))
        }
        val a = surface(); val b = surface(); val c = surface()
        available(a)
        available(b)
        assertEquals(listOf(a.surface), attached)
        assertEquals(listOf(a.surface), detached)
        val drainEntered = java.util.concurrent.CountDownLatch(1)
        val drainAllowed = java.util.concurrent.CountDownLatch(1)
        val completion = java.util.concurrent.CompletableFuture<Boolean>()
        val renderer = Thread {
            try { completion.complete(ack.complete(true)) }
            catch (failure: Throwable) { completion.completeExceptionally(failure) }
        }.apply { isDaemon = true }
        // Pause the existing out-of-monitor effect dispatch, not the lifecycle reducer:
        // A's real completion has reserved B, but B's attach has not been submitted.
        mockkStatic("com.openautolink.app.cluster.ClusterMainSessionKt")
        every { clusterLifecycleEffect(any()) } answers {
            if (Thread.currentThread() === renderer && drainEntered.count > 0) {
                assertFalse(Thread.holdsLock(ClusterBindingLifecycle.lock))
                drainEntered.countDown()
                check(drainAllowed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            callOriginal()
        }
        try {
            renderer.start()
            assertTrue(drainEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertSame("host replacement is delivered on Main", Looper.getMainLooper(), Looper.myLooper())
            available(c)
            callback.onSurfaceDestroyed(a)
            callback.onSurfaceDestroyed(b)
            assertFalse(ClusterBindingState.hasReadySession(manager))
            assertEquals(listOf(a.surface), attached)
            drainAllowed.countDown()
            assertTrue(completion.get(5, java.util.concurrent.TimeUnit.SECONDS))
            renderer.join(5000)
            assertFalse(renderer.isAlive)
            assertEquals("canceled B must not fence the latest C", listOf(a.surface, c.surface), attached)
            assertEquals("never-submitted B has no GPU retirement obligation", listOf(a.surface), detached)
            assertTrue(coordinator.isHostAttached())
            assertTrue(ClusterBindingState.hasReadySession(manager))
            callback.onSurfaceDestroyed(b)
            assertTrue(ClusterBindingState.hasReadySession(manager))
            callback.onSurfaceDestroyed(c)
            assertEquals(listOf(a.surface, c.surface), detached)
            assertFalse(ClusterBindingState.hasReadySession(manager))
        } finally {
            drainAllowed.countDown()
            renderer.join(5000)
            ack.complete(true)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            unmockkStatic("com.openautolink.app.cluster.ClusterMainSessionKt")
        }
    }

    @Test fun delayedDetachReplaysLatestSuccessorExactlyOnce() = delayedSuccessor(acknowledged = true)
    @Test fun failedDetachNeverReplaysSuccessor() = delayedSuccessor(acknowledged = false)
    @Test fun destroyedSuccessorCannotReviveAfterDetachAck() = delayedSuccessor(acknowledged = true, destroy = true)
    @Test fun newerSuccessorSupersedesPendingSurfaceBeforeDetachAck() = delayedSuccessor(acknowledged = true, replace = true)
    @Test fun retiredSessionCannotReplaySuccessorAfterDetachAck() = delayedSuccessor(acknowledged = true, retire = true)
    @Test fun geometryCleanupReplaysSuccessorAfterExactAck() = delayedSuccessor(true, geometryRejected = true)
    @Test fun geometryCleanupFailureKeepsSuccessorFenced() = delayedSuccessor(false, geometryRejected = true)
    @Test fun geometryCleanupCannotReviveDestroyedSuccessor() = delayedSuccessor(true, destroy = true, geometryRejected = true)
    @Test fun geometryCleanupReplaysOnlyLatestSuccessor() = delayedSuccessor(true, replace = true, geometryRejected = true)
    @Test fun geometryCleanupCannotReviveRetiredSession() = delayedSuccessor(true, retire = true, geometryRejected = true)
    @Test fun geometryCleanupCannotReplayChangedSuccessorGeometry() = delayedSuccessor(true, geometryRejected = true, invalidSuccessor = true)
    @Test fun geometryCleanupAcknowledgedBeforeSuccessorStillAllowsReplay() = delayedSuccessor(true, geometryRejected = true, ackBeforeSuccessor = true)
    private fun delayedSuccessor(acknowledged: Boolean, destroy: Boolean = false, replace: Boolean = false, retire: Boolean = false,
        geometryRejected: Boolean = false, invalidSuccessor: Boolean = false, ackBeforeSuccessor: Boolean = false) {
        val ack = java.util.concurrent.CompletableFuture<Boolean>()
        val attached = mutableListOf<Surface>()
        val geometries = mutableListOf<ClusterHostGeometry>()
        var detaches = 0
        val async = object : ClusterHostOutputBridge {
            override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) =
                java.util.concurrent.CompletableFuture.completedFuture(true).also { attached += surface; geometries += geometry }
            override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) =
                java.util.concurrent.CompletableFuture.completedFuture(true).also { fail("successor must attach only once, without redundant bounds") }
            override fun detach(lease: ClusterOutputLease, surface: Surface) =
                if (++detaches == 1) ack else java.util.concurrent.CompletableFuture.completedFuture(true)
        }
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation),
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val coordinator = ClusterOutputCoordinator(snapshot, async)
        val session = ClusterMapSession(snapshot, coordinator, SessionInfo.DISPLAY_TYPE_MAIN)
        session.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, manager.generation))
        (session.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val callback = callbacks.single()
        fun available(container: SurfaceContainer) {
            callback.onSurfaceAvailable(container)
            callback.onVisibleAreaChanged(Rect(0,135,2000,810))
            callback.onStableAreaChanged(Rect(0,135,2000,810))
        }
        val a = surface(); val b = surface(); val c = surface()
        available(a)
        assertEquals(listOf(a.surface), attached)
        if (geometryRejected) {
            callback.onVisibleAreaChanged(Rect(0,140,2000,810))
            assertEquals("geometry rejection starts exact A cleanup", 1, detaches)
            assertFalse(ClusterBindingState.hasReadySession(manager))
        }
        if (ackBeforeSuccessor) ack.complete(acknowledged)
        available(b)
        if (invalidSuccessor) callback.onVisibleAreaChanged(Rect(0,140,2000,810))
        if (replace) available(c)
        // A's late destruction must not clear the pending B/C callbacks.
        callback.onSurfaceDestroyed(a)
        if (replace) callback.onSurfaceDestroyed(b)
        if (destroy) callback.onSurfaceDestroyed(b)
        if (retire) (session.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertEquals(1, detaches)
        if (!ackBeforeSuccessor) {
            assertEquals(listOf(a.surface), attached)
            assertFalse(ClusterBindingState.hasReadySession(manager))
        }
        ack.complete(acknowledged)
        if (acknowledged && !destroy && !retire && !invalidSuccessor) {
            assertEquals(listOf(a.surface, if (replace) c.surface else b.surface), attached)
            assertEquals(2, geometries.size)
            assertTrue(ClusterBindingState.hasReadySession(manager))
        } else {
            assertEquals(listOf(a.surface), attached)
            assertFalse(ClusterBindingState.hasReadySession(manager))
        }
        // Failed cleanup also fences later callback chatter, not just the first replay.
        if (!acknowledged) {
            available(c)
            assertEquals(listOf(a.surface), attached)
            assertTrue(coordinator.releaseHost(session).getNow(false))
        }
    }

    @Test fun rejectedGeometryCleanupReceiptBelongsToExactLeaseOwnerAndSurface() {
        val ack = java.util.concurrent.CompletableFuture<Boolean>()
        val a = requireNotNull(surface().surface)
        val b = requireNotNull(surface().surface)
        var detaches = 0
        val async = object : ClusterHostOutputBridge {
            override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = java.util.concurrent.CompletableFuture.completedFuture(true)
            override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) = java.util.concurrent.CompletableFuture.completedFuture(true)
            override fun detach(lease: ClusterOutputLease, surface: Surface) = ack.also { assertSame(a, surface); detaches++ }
        }
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation),
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val coordinator = ClusterOutputCoordinator(snapshot, async)
        val owner = Any(); val successor = Any()
        val host = ClusterHostGeometry(7, profile.panel, PixelRect(0,135,2000,810), PixelRect(0,135,2000,810))
        assertTrue(coordinator.claim(owner))
        assertTrue(coordinator.attachHost(snapshot.lease, owner, a, host).getNow(false))
        val rejected = coordinator.updateBounds(snapshot.lease, owner, a, host.copy(visible = PixelRect(0,140,2000,810)))
        val cleanup = coordinator.detachHost(snapshot.lease, owner, a)
        repeat(1000) { assertSame(cleanup, coordinator.detachHost(snapshot.lease, owner, a)) }
        assertFalse(cleanup.isDone)
        assertFalse(rejected.isDone)
        assertFalse(coordinator.detachHost(snapshot.lease, successor, a).getNow(true))
        assertFalse(coordinator.detachHost(snapshot.lease.copy(projectionEpoch = 99), owner, a).getNow(true))
        assertFalse(coordinator.detachHost(snapshot.lease, owner, b).getNow(true))
        val observer = cleanup.thenAccept { check(!Thread.holdsLock(coordinator)); assertTrue(it) }
        ack.complete(true)
        observer.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertTrue(cleanup.getNow(false))
        assertFalse("cleanup is not an attach acknowledgement", rejected.getNow(true))
        assertEquals(1, detaches)
        assertTrue(coordinator.releaseHost(owner).getNow(false))
        assertTrue(coordinator.claim(successor))
        assertFalse("successor cannot adopt predecessor cleanup receipt", coordinator.detachHost(snapshot.lease, successor, a).getNow(true))
        assertTrue(coordinator.attachHost(snapshot.lease, successor, b, host).getNow(false))
        assertFalse(coordinator.detachHost(snapshot.lease, successor, a).getNow(true))
        assertTrue(coordinator.isHostAttached())
    }

    @Test fun realSurfaceCallbackAndInlineOutputObserverDoNotInvertLifecycleAndOutputLocks() {
        val initialized = java.util.concurrent.CountDownLatch(1)
        val initialize = java.util.concurrent.CountDownLatch(1)
        val observerEntered = java.util.concurrent.CountDownLatch(1)
        val boundsEntered = java.util.concurrent.CountDownLatch(1)
        val observerHeldOutput = java.util.concurrent.atomic.AtomicBoolean()
        val bridgeHeldLifecycle = java.util.concurrent.atomic.AtomicBoolean()
        val mapping = ClusterVideoGeometry.androidAuto(qualified)
        val outputLease = ClusterOutputLease(31, manager.generation)
        val output = ClusterProjectionOutput(mapping, ClusterRenderDriverFactory { _, _ ->
            object : ClusterRenderDriver {
                override fun initialize() { initialized.countDown(); check(initialize.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
                override fun configure(config: ClusterCodecConfig) = Unit
                override fun submit(frame: ClusterEncodedFrame) = true
                override fun poll() = ClusterRenderProgress(0, 0)
                override fun stopDecoder() = Unit
                override fun clearOutput() = Unit
                override fun detach() = true
            }
        })
        assertTrue(output.bindLease(outputLease))
        val outputLock = output.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(output)
        val forwarding = object : ClusterHostOutputBridge {
            override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) =
                output.attach(lease, surface, geometry).also {
                    bridgeHeldLifecycle.compareAndSet(false, Thread.holdsLock(ClusterBindingLifecycle.lock))
                }
            override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry): java.util.concurrent.CompletableFuture<Boolean> {
                bridgeHeldLifecycle.compareAndSet(false, Thread.holdsLock(ClusterBindingLifecycle.lock))
                boundsEntered.countDown()
                return output.updateBounds(lease, surface, geometry)
            }
            override fun detach(lease: ClusterOutputLease, surface: Surface) = output.detach(lease, surface)
        }
        val snapshot = ClusterMapOutputSnapshot(outputLease, ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO),
            ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val coordinator = ClusterOutputCoordinator(snapshot, forwarding)
        val session = ClusterMapSession(snapshot, coordinator, SessionInfo.DISPLAY_TYPE_MAIN)
        session.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, manager.generation))
        (session.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined)
        val observed = java.util.concurrent.CompletableFuture<Boolean>()
        val job = scope.launch {
            output.status.collect { status ->
                if (status.configurations > 0 && observerEntered.count > 0) {
                    observerHeldOutput.set(Thread.holdsLock(outputLock))
                    observerEntered.countDown()
                    check(boundsEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    // Negative control stops before taking the second edge of an irreversible
                    // monitor cycle. The real callback has already entered the bridge edge.
                    if (!observerHeldOutput.get()) synchronized(ClusterBindingLifecycle.lock) { }
                    observed.complete(true)
                }
            }
        }
        val callback = callbacks.single()
        val host = surface()
        val callbackFailure = java.util.concurrent.CompletableFuture<Throwable?>()
        val hostThread = Thread {
            try {
                check(observerEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                callback.onStableAreaChanged(Rect(0,135,2000,810))
                callbackFailure.complete(null)
            } catch (failure: Throwable) { callbackFailure.complete(failure) }
        }.apply { isDaemon = true }
        try {
            callback.onSurfaceAvailable(host)
            callback.onVisibleAreaChanged(Rect(0,135,2000,810))
            callback.onStableAreaChanged(Rect(0,135,2000,810))
            assertTrue(initialized.await(5, java.util.concurrent.TimeUnit.SECONDS))
            hostThread.start()
            initialize.countDown()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!ClusterBindingState.hasReadySession(manager) && System.nanoTime() < deadline) Thread.yield()
            assertTrue(ClusterBindingState.hasReadySession(manager))
            val source = requireNotNull(output.beginStream(outputLease, Any()) { true })
            val publisher = Thread {
                output.configure(outputLease, source, ClusterCodecConfig(mapping.codedSize,
                    byteArrayOf(0,0,0,1,7,1), byteArrayOf(0,0,0,1,8,1)))
            }.apply { isDaemon = true; start() }
            assertTrue(observed.get(5, java.util.concurrent.TimeUnit.SECONDS))
            publisher.join(5000)
            assertFalse(publisher.isAlive)
            hostThread.join(5000)
            assertFalse("host callback must not deadlock with inline status observer", hostThread.isAlive)
            assertNull(callbackFailure.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("real bridge invocation inherited lifecycle monitor", bridgeHeldLifecycle.get())
            assertFalse("inline observer inherited output monitor", observerHeldOutput.get())
        } finally {
            initialize.countDown(); boundsEntered.countDown(); job.cancel()
            (session.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            assertTrue(output.retire(outputLease).get(5, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test fun inheritedLifecycleRetirementDispatchesExactCleanupAndCancelsLateCallbacks() {
        val ack = java.util.concurrent.CompletableFuture<Boolean>()
        val detaches = java.util.concurrent.atomic.AtomicInteger()
        val attaches = java.util.concurrent.atomic.AtomicInteger()
        val inherited = java.util.concurrent.atomic.AtomicBoolean()
        val checkedBridge = object : ClusterHostOutputBridge {
            override fun attach(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) =
                java.util.concurrent.CompletableFuture.completedFuture(true).also {
                    inherited.compareAndSet(false, Thread.holdsLock(ClusterBindingLifecycle.lock)); attaches.incrementAndGet()
                }
            override fun updateBounds(lease: ClusterOutputLease, surface: Surface, geometry: ClusterHostGeometry) =
                java.util.concurrent.CompletableFuture.completedFuture(true)
            override fun detach(lease: ClusterOutputLease, surface: Surface) = ack.also {
                inherited.compareAndSet(false, Thread.holdsLock(ClusterBindingLifecycle.lock)); detaches.incrementAndGet()
            }
        }
        val snapshot = ClusterMapOutputSnapshot(ClusterOutputLease(31, manager.generation),
            ClusterOutputSelection(ClusterDisplayMode.MAP_VIDEO), ClusterProjectionProtocol.ANDROID_AUTO, qualified)
        val coordinator = ClusterOutputCoordinator(snapshot, checkedBridge)
        val session = ClusterMapSession(snapshot, coordinator, SessionInfo.DISPLAY_TYPE_MAIN)
        session.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, manager.generation))
        val registry = session.lifecycle as LifecycleRegistry
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        val callback = callbacks.single()
        fun available() {
            callback.onSurfaceAvailable(surface())
            callback.onVisibleAreaChanged(Rect(0,135,2000,810))
            callback.onStableAreaChanged(Rect(0,135,2000,810))
        }
        available()
        synchronized(ClusterBindingLifecycle.lock) {
            ClusterMainSession.invalidateBindingGeneration(manager.generation, "manager enclosing lock")
            available() // authority revoked synchronously, before the owned Main cleanup runs
            assertEquals(1, attaches.get())
            assertEquals(0, detaches.get())
            assertTrue(ClusterBindingState.hasLiveSession(manager))
        }
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, detaches.get())
        available()
        assertEquals(1, attaches.get())
        assertFalse(inherited.get())
        val renderer = Thread { ack.complete(true) }
        renderer.start(); renderer.join(5000)
        assertFalse(renderer.isAlive)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertFalse(ClusterBindingState.hasLiveSession(manager))
    }

    @Test fun noHostReadinessUntilBothActualBoundsArrive() {
        session()
        callbacks.single().onSurfaceAvailable(surface())
        assertTrue("actual bounds, not frozen guesses", events.isEmpty())
        callbacks.single().onVisibleAreaChanged(Rect(0,135,2000,810))
        assertTrue(events.isEmpty())
        callbacks.single().onStableAreaChanged(Rect(0,135,2000,810))
        assertEquals(listOf("attach"), events)
    }
    @Test fun staleSurfaceDestroyDoesNotDetachSuccessor() {
        session()
        val old = surface(); val next = surface()
        val callback = callbacks.single()
        callback.onSurfaceAvailable(old)
        callback.onVisibleAreaChanged(Rect(0,135,2000,810))
        callback.onStableAreaChanged(Rect(0,135,2000,810))
        callback.onSurfaceAvailable(next)
        callback.onVisibleAreaChanged(Rect(0,135,2000,810))
        callback.onStableAreaChanged(Rect(0,135,2000,810))
        val count = events.count { it == "detach" }
        callback.onSurfaceDestroyed(old)
        assertEquals(count, events.count { it == "detach" })
        callback.onSurfaceDestroyed(next)
        assertEquals(count + 1, events.count { it == "detach" })
    }
    @Test fun combinedReusesSourcedNativeLifecycleButNeverStartsWithoutManeuver() {
        session(true)
        ClusterNavigationState.updateHost(null)
        dispatcher.scheduler.advanceTimeBy(300); dispatcher.scheduler.runCurrent()
        verify(exactly = 1) { anyConstructed<CarContext>().getCarService(NavigationManager::class.java) }
        verify(exactly = 0) { navigation.navigationStarted() }
        ClusterNavigationState.updateHost(ManeuverState(ManeuverType.TURN_LEFT,100,"100 m","Road",60))
        dispatcher.scheduler.advanceTimeBy(300); dispatcher.scheduler.runCurrent()
        verify(exactly = 1) { navigation.navigationStarted() }
        verify(exactly = 1) { navigation.updateTrip(match { !it.isLoading }) }
    }
    @Test fun stoppedSessionIsLiveAndDestroyDetachesExactlyOnce() {
        val session = session()
        val registry = session.lifecycle as LifecycleRegistry
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        callbacks.single().onSurfaceAvailable(surface())
        callbacks.single().onVisibleAreaChanged(Rect(0,135,2000,810))
        callbacks.single().onStableAreaChanged(Rect(0,135,2000,810))
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        assertTrue(ClusterBindingState.hasLiveSession(manager))
        assertEquals(0, events.count { it == "detach" })
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertFalse(ClusterBindingState.hasLiveSession(manager))
        assertEquals(1, events.count { it == "detach" })
    }
    @Test fun globalExitRetiresMapSurfaceAndRejectsLateHostAttach() {
        session()
        val callback = callbacks.single()
        callback.onSurfaceAvailable(surface())
        callback.onVisibleAreaChanged(Rect(0,135,2000,810))
        callback.onStableAreaChanged(Rect(0,135,2000,810))
        ClusterMainSession.endActiveNavigation()
        assertEquals(1, events.count { it == "detach" })
        callback.onSurfaceAvailable(surface())
        assertEquals(1, events.count { it == "attach" })
    }

    @Test fun mapOnlyRegistersRealHostWithoutAnyNavigationLookup() {
        session()
        ClusterNavigationState.updateHost(ManeuverState(ManeuverType.TURN_LEFT,100,"100 m","Road",60))
        dispatcher.scheduler.advanceTimeBy(300); dispatcher.scheduler.runCurrent()
        assertEquals(1, callbacks.size)
        verify(exactly = 0) { anyConstructed<CarContext>().getCarService(NavigationManager::class.java) }
        verify(exactly = 0) { navigation.navigationStarted() }
        verify(exactly = 0) { navigation.updateTrip(any()) }
        assertTrue(events.isEmpty()) // template/registration is not surface readiness
        callbacks.single().onSurfaceAvailable(surface())
        callbacks.single().onVisibleAreaChanged(Rect(0,135,2000,810))
        callbacks.single().onStableAreaChanged(Rect(0,135,2000,810))
        assertEquals(listOf("attach"), events)
    }
}
