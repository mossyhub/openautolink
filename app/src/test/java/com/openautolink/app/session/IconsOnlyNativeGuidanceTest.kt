package com.openautolink.app.session

import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.navigation.NavigationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.openautolink.app.cluster.*
import com.openautolink.app.data.ClusterOutputPreferences
import com.openautolink.app.diagnostics.live.LiveProcessTestFixture
import com.openautolink.app.secondary.*
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.aasdk.AasdkSession
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.TimeUnit

/** Actual frozen preparation -> legacy manager -> real phone-control collector -> native Trip.
 * Only transport and AndroidX host endpoints are mocked; this is not physical OEM visibility. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32,34], application = IconsOnlyNativeGuidanceTest.NativeApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class IconsOnlyNativeGuidanceTest {
    class NativeApplication : Application()
    private val process = LiveProcessTestFixture()
    @get:org.junit.Rule val processRule: org.junit.rules.TestRule = process
    private fun stopAndJoinObserver() {
        process.retire()
    }
    @org.junit.Before fun validatedBenchDefaultNetwork() {
        // Match LiveEnabledIsolationTest: this observer uses the legacy endpoint,
        // whose real Android network observer requires validated Internet.
        val manager = org.robolectric.RuntimeEnvironment.getApplication()
            .getSystemService(android.net.ConnectivityManager::class.java)
        val shadow = shadowOf(manager)
        shadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,
            android.net.ConnectivityManager.TYPE_MOBILE, 0, true, true))
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(manager.activeNetwork, caps)
    }
    private fun await(boundary: String = "actual source/native lifecycle completion", condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)); Thread.sleep(5) }
        if (!condition()) fail("$boundary; observer=${com.openautolink.app.diagnostics.live.LiveDiagnostics.status.value}; " +
            "source=${ClusterNavigationState.state.value}; " +
            "recent=${com.openautolink.app.diagnostics.DiagnosticLog.localLogs.value.takeLast(12).map { it.message }}")
    }
    @Test fun videoOptOutStillRestoresNativeTripOnlyAfterActualPhoneInstruction() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as Application
        assertEquals(NativeApplication::class.java, app.javaClass)
        assertFalse("this fixture must own an uninitialized observer",
            com.openautolink.app.diagnostics.live.LiveDiagnostics.ready.value)
        com.openautolink.app.diagnostics.live.LiveConsentStore(app).save(false)
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_NETWORK_STATE,
            android.Manifest.permission.INTERNET)
        val runtimeProcess = SecondaryRuntimeProcessTestFixture()
        // Retire the exact old-context runtime BEFORE this fixture installs preferences/mocks.
        // A non-null singleton alone says nothing about whether its owners have retired.
        runtimeProcess.retirePredecessor()
        val f = HostedSecondaryRuntimeFixture(2)
        runtimeProcess.install(f.runtime)
        val navigation = mockk<NavigationManager>(relaxed = true)
        val trips = java.util.concurrent.CopyOnWriteArrayList<androidx.car.app.navigation.model.Trip>()
        every { navigation.updateTrip(capture(trips)) } just Runs
        val car = mockk<CarContext>(relaxed = true) {
            every { packageName } returns app.packageName
            every { resources } returns app.resources
            every { getCarService(NavigationManager::class.java) } returns navigation
        }
        val controls = MutableSharedFlow<ControlMessage>(extraBufferCapacity = 8)
        every { f.session.controlMessages } returns controls
        every { f.session.videoFrames } returns MutableSharedFlow()
        every { f.session.audioFrames } returns MutableSharedFlow()
        every { f.session.secondaryVideoFrames } returns emptyFlow()
        every { f.session.secondaryVideoSelections } returns MutableStateFlow(emptyMap())
        every { f.session.evTelemetryToken } returns "icons-fixture"
        every { f.session.reconnectAttempt } returns MutableStateFlow(0)
        every { f.session.vehicleEnergyForecast } returns MutableStateFlow(null)
        every { f.session.negotiatedCodecType } returns MutableStateFlow(0)
        var native: ClusterMainSession? = null
        var nativeJob: Job? = null
        var preparation: Deferred<PreparedAaSecondaryOutputs>? = null
        try {
            // Combined release: the real process observer must not disturb the native collector.
            mockkConstructor(com.openautolink.app.diagnostics.live.LiveCredentialStore::class,
                com.openautolink.app.diagnostics.live.LiveHttps::class)
            every { anyConstructed<com.openautolink.app.diagnostics.live.LiveCredentialStore>()
                .load(com.openautolink.app.diagnostics.live.LiveEndpoint.LEGACY) } returns "synthetic-native-fixture"
            every { anyConstructed<com.openautolink.app.diagnostics.live.LiveHttps>().close() } just Runs
            val streamed = java.util.concurrent.CopyOnWriteArrayList<String>()
            every { anyConstructed<com.openautolink.app.diagnostics.live.LiveHttps>().post(any(),any(),any()) } answers {
                val path = firstArg<String>()
                val body = secondArg<org.json.JSONObject>()
                when (path) {
                    "/v2/connect" -> {
                        assertEquals("synthetic-native-fixture", body.getString("deviceCredential"))
                        assertNull(thirdArg<String?>())
                        org.json.JSONObject().put("token", "synthetic-native-bearer")
                    }
                    "/v1/ingest" -> {
                        assertEquals("synthetic-native-bearer", thirdArg<String?>())
                        streamed.add(body.toString())
                        val rows = body.getJSONArray("events")
                        org.json.JSONObject().put("ack", if (rows.length() == 0) 0L
                            else rows.getJSONObject(rows.length() - 1).getLong("seq"))
                    }
                    else -> error("unexpected observer route: $path")
                }
            }
            com.openautolink.app.diagnostics.live.LiveDiagnostics.initialize(org.robolectric.RuntimeEnvironment.getApplication())
            com.openautolink.app.diagnostics.live.LiveDiagnostics.enable(f.context, "")
            await("observer live after endpoint-scoped credential restore") { com.openautolink.app.diagnostics.live.LiveDiagnostics.status.value.startsWith("Live read-only") }
            com.openautolink.app.diagnostics.DiagnosticLog.i("IsolationBench", "native collector observer marker")
            await { streamed.any { it.contains("native collector observer marker") } }
            ClusterNavigationState.clear()
            val icons = ClusterOutputSelection(ClusterDisplayMode.NATIVE_ICONS, false, false)
            runBlocking {
                f.preferences.setClusterOutputSelection(icons)
                val saved = f.preferences.getSecondaryDisplayPreferences()
                f.preferences.updateSecondaryDisplayPreferences(saved.revision,
                    mapOf(f.descriptor.id to SecondaryOutputPreference(false, AaSecondaryRole.AUXILIARY, false, false)))
            }
            f.field("aasdkSession").set(f.manager, f.session)
            // Use the connection-owned factory, not reflection into a retired preparation seam.
            preparation = f.scope.async { f.manager.prepareAndroidAutoSecondaryOutputs(f.context) }
            await { preparation!!.isCompleted }
            val prepared = runBlocking { preparation!!.await() }
            assertTrue(prepared.offers.isEmpty()); assertNull(prepared.hostedManager)
            val admitted = f.field("_clusterManager").get(f.manager) as ClusterManager?
            assertNotNull("real legacy manager created despite saved video protocol opt-outs", admitted)
            val lease = requireNotNull(org.robolectric.util.ReflectionHelpers.getField<ClusterBindingRegistry.ManagerLease?>(
                admitted!!, "admittedLease")) { "native manager must hold its actual admission lease" }
            var bindingIntent: Intent? = null
            await { bindingIntent = bindingIntent ?: shadowOf(app).nextStartedActivity; bindingIntent != null }
            assertEquals("androidx.car.app.activity.CarAppActivity", bindingIntent!!.component!!.className)
            assertEquals(lease.generation,
                bindingIntent!!.getLongExtra(CLUSTER_BINDING_GENERATION_EXTRA, Long.MIN_VALUE))
            // Host creation follows the real generation-bearing launch and current service factory.
            native = spyk(OalClusterService().onCreateSession() as ClusterMainSession)
            every { native!!.carContext } returns car
            native!!.onCreateScreen(bindingIntent!!)
            (native!!.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            nativeJob = (ClusterMainSession::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(native) as CoroutineScope).coroutineContext[Job]
            await { ClusterBindingState.hasReadySession(lease) }
            SessionManager::class.java.getDeclaredMethod("bindSessionCollectors", AasdkSession::class.java)
                .apply { isAccessible = true }.invoke(f.manager, f.session)
            await { controls.subscriptionCount.value > 0 }
            verify(exactly = 0) { navigation.navigationStarted() }
            assertTrue(trips.isEmpty())
            assertTrue(controls.tryEmit(ControlMessage.NavState("turn_left", 100, "Road", 60)))
            await("sourced phone instruction consumed as a non-loading native Trip") { trips.any { !it.isLoading } }
            verify(exactly = 1) { navigation.navigationStarted() }
            val trip = trips.last { !it.isLoading }
            assertEquals(1, trip.steps.size)
            assertEquals(androidx.car.app.navigation.model.Maneuver.TYPE_TURN_NORMAL_LEFT, trip.steps.single().maneuver!!.type)
            assertNotNull(trip.steps.single().maneuver!!.icon)
            assertTrue(controls.tryEmit(ControlMessage.NavStateClear))
            await { ClusterNavigationState.state.value == null }
            verify(exactly = 0) { f.session.stop(); f.session.forceReconnect(any()) }
        } finally {
            try {
                stopAndJoinObserver()
                preparation?.cancel()
                preparation?.let { task -> await { task.isCompleted }; runBlocking { task.join() } }
                native?.let { (it.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
                nativeJob?.let { task -> await { task.isCompleted }; runBlocking { task.join() } }
                // f.close() removes mocks; all fixture-created consumers must be finished first.
                val managerJob = (f.field("scope").get(f.manager) as CoroutineScope).coroutineContext[Job]!!
                val fixtureJob = f.scope.coroutineContext[Job]!!
                managerJob.cancel(); fixtureJob.cancel()
                await { managerJob.isCompleted && fixtureJob.isCompleted }
                runBlocking { managerJob.join(); fixtureJob.join() }
                f.close()
                // f.close() must succeed first; then join/inspect real close completion before
                // relinquishing our pointer. A failed close leaves this exact runtime installed.
                runtimeProcess.retireAndRelinquish(f.runtime)
            } finally {
                ClusterNavigationState.clear()
            }
        }
    }
}
