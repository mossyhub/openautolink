package com.openautolink.app.cluster

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import androidx.car.app.CarContext
import androidx.car.app.HandshakeInfo
import androidx.car.app.ICarApp
import androidx.car.app.ICarHost
import androidx.car.app.IAppHost
import androidx.car.app.IOnDoneCallback
import androidx.car.app.activity.CarAppActivity
import androidx.car.app.activity.CarAppViewModel
import androidx.car.app.activity.renderer.ICarAppActivity
import androidx.car.app.activity.renderer.IRendererCallback
import androidx.car.app.activity.renderer.IRendererService
import androidx.car.app.navigation.INavigationHost
import androidx.car.app.navigation.INavigationManager
import androidx.car.app.serialization.Bundleable
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelProvider
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import io.mockk.*
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Published, unmodified AndroidX frontend + real OAL service/session/navigation manager.
 * Only the external renderer/host Binder and OS service-discovery/binding boundary are faked.
 * Robolectric does NOT implement GM cross-display task policy or prove physical HUD output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class NativeBootstrapFrontendTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun pumpUntil(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
        while (!predicate() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(10))
            Thread.sleep(5)
        }
        assertTrue("actual frontend/session outcome did not arrive at original deadline", predicate())
    }
    private class Done(val response: (Bundleable?) -> Unit = {}) : IOnDoneCallback.Stub() {
        override fun onSuccess(value: Bundleable?) { response(value) }
        override fun onFailure(value: Bundleable?) { throw AssertionError("host call rejected: ${value?.get()}") }
    }
    private fun <T : android.os.IInterface> localBinder(value: T, descriptor: String): IBinder =
        Binder().apply { attachInterface(value, descriptor) }

    @Test fun ordinaryStopPreservesPublishedFrontendConnectionSessionAndTrips() = scenario(false, false)
    @Test fun moveTaskToBackThenNormalStopPreservesSessionAndTrips() = scenario(true, false)
    @Test fun realHostStopNavigationDoesNotReassertAfterResume() = scenario(true, true)

    private fun scenario(background: Boolean, hostStop: Boolean) {
        ClusterNavigationState.clearHost()
        val lease = ClusterBindingState.openManager()
        val serviceController = Robolectric.buildService(OalClusterService::class.java).create()
        val service = serviceController.get()
        val renderIntent = Intent("android.car.template.host.RendererService")
        val rendererComponent = ComponentName("test.renderer", "test.renderer.RendererService")
        val resolved = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply { packageName = rendererComponent.packageName; name = rendererComponent.className }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(renderIntent, resolved)
        val renderer = mockk<IRendererService>(relaxed = true)
        val callback = mockk<IRendererCallback>(relaxed = true)
        val navHost = mockk<INavigationHost>(relaxed = true)
        val carHost = mockk<ICarHost>(relaxed = true)
        val appHost = mockk<IAppHost>(relaxed = true)
        every { carHost.getHost(CarContext.APP_SERVICE) } returns
            localBinder(appHost, "androidx.car.app.IAppHost")
        every { carHost.getHost(CarContext.NAVIGATION_SERVICE) } returns
            localBinder(navHost, "androidx.car.app.navigation.INavigationHost")
        var binder: ICarApp? = null
        var boundIntent: Intent? = null
        var terminateCount = 0
        var destroyedCount = 0
        var session: androidx.car.app.Session? = null
        var trips = 0
        var started = 0
        every { navHost.navigationStarted() } answers { started++; Unit }
        every { navHost.updateTrip(any()) } answers {
            // Deserialize actual production Trip, rather than counting a fabricated payload.
            assertTrue(firstArg<Bundleable>().get() is androidx.car.app.navigation.model.Trip)
            trips++; Unit
        }
        every { renderer.performHandshake(any(), any()) } returns Bundleable.create(HandshakeInfo("test.renderer", 6))
        every { renderer.initialize(any(), any(), any()) } answers {
            firstArg<ICarAppActivity>().registerRendererCallback(callback)
            true
        }
        every { renderer.onNewIntent(any(), any(), any()) } answers {
            val intent = firstArg<Intent>()
            boundIntent = Intent(intent)
            if (binder == null) {
                binder = ICarApp.Stub.asInterface(service.onBind(intent))
                binder!!.onHandshakeCompleted(Bundleable.create(HandshakeInfo("test.renderer", 6)), Done())
                binder!!.onAppCreate(carHost, intent, app.resources.configuration, Done())
                session = service.currentSession
                requireNotNull(session).lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onDestroy(owner: LifecycleOwner) { destroyedCount++ }
                })
            }
            true
        }
        every { callback.onStart() } answers { binder?.onAppStart(Done()); Unit }
        every { callback.onResume() } answers { binder?.onAppResume(Done()); Unit }
        every { callback.onPause() } answers { binder?.onAppPause(Done()); Unit }
        every { callback.onStop() } answers { binder?.onAppStop(Done()); Unit }
        every { renderer.terminate(any()) } answers {
            terminateCount++
            service.onUnbind(requireNotNull(boundIntent))
            Unit
        }
        shadowOf(app).setComponentNameAndServiceForBindService(rendererComponent,
            localBinder(renderer, "androidx.car.app.activity.renderer.IRendererService"))
        ClusterBootstrapWindowProtector.install(app)
        val intent = Intent(app, CarAppActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(CLUSTER_BINDING_GENERATION_EXTRA, lease.generation)
        val controller = Robolectric.buildActivity(CarAppActivity::class.java, intent)
        var destroyed = false
        try {
            controller.setup()
            val activity = controller.get()
            val viewModel = ViewModelProvider(activity)[CarAppViewModel::class.java]
            pumpUntil { viewModel.state.value == CarAppViewModel.State.CONNECTED && session != null }
            ClusterNavigationState.updateHost(ManeuverState(ManeuverType.TURN_LEFT, 100, "100 m", "Road", 60))
            pumpUntil { trips > 0 }
            val originalSession = session
            val before = trips
            if (background) activity.moveTaskToBack(true) // Public OS API; no lifecycle shadow override.
            controller.pause().stop() // Android framework lifecycle, never hand-call a manager helper.
            verify(atLeast = 1) { callback.onPause(); callback.onStop() }
            assertEquals(0, terminateCount)
            assertEquals(0, destroyedCount)
            assertSame(originalSession, service.currentSession)
            assertSame(viewModel, ViewModelProvider(activity)[CarAppViewModel::class.java])
            assertTrue(ClusterBindingState.hasReadySession(lease))
            ClusterNavigationState.updateHost(ManeuverState(ManeuverType.TURN_LEFT, 90, "90 m", "Road", 55))
            pumpUntil { trips > before }
            if (hostStop) {
                var manager: INavigationManager? = null
                binder!!.getManager(CarContext.NAVIGATION_SERVICE, Done { manager = it!!.get() as INavigationManager })
                pumpUntil { manager != null }
                manager!!.onStopNavigation(Done())
                pumpUntil { ClusterNavigationState.isRouteSuppressed }
                val stoppedTrips = trips
                val stoppedStarts = started
                controller.restart().start().resume()
                ClusterNavigationState.updateHost(ManeuverState(ManeuverType.TURN_LEFT, 80, "80 m", "Road", 50))
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500)
                while (System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
                assertEquals(stoppedTrips, trips)
                assertEquals(stoppedStarts, started)
                controller.pause().stop()
            }
            activity.finish()
            controller.destroy()
            destroyed = true
            pumpUntil { terminateCount == 1 && destroyedCount == 1 }
            activity.viewModelStore.clear() // Positive idempotence control; actual clear already occurred.
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, terminateCount)
            assertEquals(1, destroyedCount)
            verify(exactly = 1) { callback.onDestroyed() }
            assertFalse(ClusterBindingState.hasLiveSession(lease))
        } finally {
            ClusterNavigationState.clearHost()
            if (!destroyed) { controller.get().finish(); controller.destroy() }
            shadowOf(Looper.getMainLooper()).idle()
            ClusterBindingState.closeManager(lease)
            serviceController.destroy()
            app.unregisterActivityLifecycleCallbacks(ClusterBootstrapWindowProtector)
        }
    }
}
