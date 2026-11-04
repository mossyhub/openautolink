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
@Config(sdk = [32,34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class IconsOnlyNativeGuidanceTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)); Thread.sleep(5) }
        assertTrue("actual source/native lifecycle completion", condition())
    }
    @Test fun videoOptOutStillRestoresNativeTripOnlyAfterActualPhoneInstruction() {
        val f = HostedSecondaryRuntimeFixture(2)
        val navigation = mockk<NavigationManager>(relaxed = true)
        val trips = java.util.concurrent.CopyOnWriteArrayList<androidx.car.app.navigation.model.Trip>()
        every { navigation.updateTrip(capture(trips)) } just Runs
        mockkConstructor(CarContext::class)
        every { anyConstructed<CarContext>().packageName } returns "com.openautolink.app"
        every { anyConstructed<CarContext>().resources } returns org.robolectric.RuntimeEnvironment.getApplication().resources
        every { anyConstructed<CarContext>().getCarService(NavigationManager::class.java) } returns navigation
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
        try {
            // Combined release: the real process observer must not disturb the native collector.
            mockkConstructor(com.openautolink.app.diagnostics.live.LiveCredentialStore::class,
                com.openautolink.app.diagnostics.live.LiveHttps::class)
            every { anyConstructed<com.openautolink.app.diagnostics.live.LiveCredentialStore>().load() } returns "synthetic-native-fixture"
            every { anyConstructed<com.openautolink.app.diagnostics.live.LiveHttps>().close() } just Runs
            val streamed = java.util.concurrent.CopyOnWriteArrayList<String>()
            every { anyConstructed<com.openautolink.app.diagnostics.live.LiveHttps>().post(any(),any(),any()) } answers {
                streamed.add(secondArg<org.json.JSONObject>().toString())
                org.json.JSONObject().put("token","synthetic-native-bearer").put("ack",Long.MAX_VALUE)
            }
            com.openautolink.app.diagnostics.live.LiveDiagnostics.initialize(org.robolectric.RuntimeEnvironment.getApplication())
            com.openautolink.app.diagnostics.live.LiveDiagnostics.enable(f.context, "")
            await { com.openautolink.app.diagnostics.live.LiveDiagnostics.status.value.startsWith("Live read-only") }
            com.openautolink.app.diagnostics.DiagnosticLog.i("IsolationBench", "native collector observer marker")
            await { streamed.any { it.contains("native collector observer marker") } }
            ClusterNavigationState.clear()
            val icons = ClusterOutputSelection(ClusterDisplayMode.NATIVE_ICONS, false, false)
            val snapshot = SecondaryPreferencesSnapshot(8,
                mapOf(f.descriptor.id to SecondaryOutputPreference(false, AaSecondaryRole.AUXILIARY, false, false)),
                ClusterOutputPreferences(icons, ClusterDisplayMode.MAP_VIDEO))
            val prepared = runBlocking { f.runtime.prepareAndroidAuto(80, snapshot) }
            assertTrue(prepared.offers.isEmpty()); assertNull(prepared.hostedManager)
            f.field("aasdkSession").set(f.manager, f.session)
            SessionManager::class.java.getDeclaredMethod("prepareLegacyIcons", ClusterOutputSelection::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(f.manager, prepared.legacySelection, false)
            assertNotNull("real legacy manager created despite saved video protocol opt-outs", f.field("_clusterManager").get(f.manager))
            native = ClusterMainSession().also {
                it.onCreateScreen(Intent())
                (it.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            }
            SessionManager::class.java.getDeclaredMethod("bindSessionCollectors", AasdkSession::class.java)
                .apply { isAccessible = true }.invoke(f.manager, f.session)
            await { controls.subscriptionCount.value > 0 }
            verify(exactly = 0) { navigation.navigationStarted() }
            assertTrue(trips.isEmpty())
            assertTrue(controls.tryEmit(ControlMessage.NavState("turn_left", 100, "Road", 60)))
            await { trips.any { !it.isLoading } }
            verify(exactly = 1) { navigation.navigationStarted() }
            val trip = trips.last { !it.isLoading }
            assertEquals(1, trip.steps.size)
            assertEquals(androidx.car.app.navigation.model.Maneuver.TYPE_TURN_NORMAL_LEFT, trip.steps.single().maneuver!!.type)
            assertNotNull(trip.steps.single().maneuver!!.icon)
            assertTrue(controls.tryEmit(ControlMessage.NavStateClear))
            await { ClusterNavigationState.state.value == null }
            verify(exactly = 0) { f.session.stop(); f.session.forceReconnect(any()) }
        } finally {
            com.openautolink.app.diagnostics.live.LiveDiagnostics.stop()
            native?.let { (it.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY) }
            f.close()
            ClusterNavigationState.clear()
        }
    }
}
