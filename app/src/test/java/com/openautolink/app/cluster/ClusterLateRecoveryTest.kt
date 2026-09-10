package com.openautolink.app.cluster

import android.content.Context
import android.content.Intent
import android.os.Handler

import androidx.car.app.CarContext
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.core.content.ContextCompat
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClusterLateRecoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = mockk<Context>(relaxed = true)
    private val callbacks = mutableListOf<Pair<Long, Runnable>>()
    private var now = 0L
    private lateinit var manager: ClusterManager
    private val maneuver = ManeuverState(ManeuverType.TURN_LEFT, 100, "100 m", "Main St", 60)
    private val navigation = mockk<NavigationManager>(relaxed = true)
    private val hostCallbacks = mutableListOf<NavigationManagerCallback>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(android.os.Looper::class)
        val mainLooper = mockk<android.os.Looper>()
        every { mainLooper.thread } returns Thread.currentThread()
        every { android.os.Looper.getMainLooper() } returns mainLooper
        every { android.os.Looper.myLooper() } returns mainLooper

        mockkConstructor(CarContext::class)
        every { anyConstructed<CarContext>().getCarService(NavigationManager::class.java) } returns navigation
        every { anyConstructed<CarContext>().packageName } returns "com.openautolink.app"
        every { anyConstructed<CarContext>().resources } returns mockk(relaxed = true)
        every { navigation.setNavigationManagerCallback(capture(hostCallbacks)) } just Runs
        mockkConstructor(Handler::class)
        every { anyConstructed<Handler>().postDelayed(any(), any()) } answers {
            callbacks += (now + secondArg<Long>()) to firstArg<Runnable>()
            true
        }
        every { anyConstructed<Handler>().removeCallbacksAndMessages(any()) } answers {
            callbacks.clear()
        }
        mockkStatic(ContextCompat::class)
        every { ContextCompat.checkSelfPermission(any(), any()) } returns 0
        ClusterNavigationState.clear()
        manager = ClusterManager(context)
        manager.setClusterEnabled(true)
        manager.launchClusterBinding()
    }

    @After
    fun tearDown() {
        manager.release()
        dispatcher.scheduler.runCurrent()
        ClusterNavigationState.clear()
        Dispatchers.resetMain()

        unmockkAll()
    }

    private fun advance(ms: Long) {
        val target = now + ms
        while (now < target) {
            dispatcher.scheduler.runCurrent()
            val next = minOf(target, callbacks.minOfOrNull { it.first } ?: target, now + 100)
            dispatcher.scheduler.advanceTimeBy(next - now)
            now = next
            val due = callbacks.filter { it.first <= now }
            callbacks.removeAll(due.toSet())
            due.forEach { it.second.run() }
        }
        dispatcher.scheduler.runCurrent()
    }

    private fun ready(): ClusterBindingRegistry.SessionLease {
        val lease = requireNotNull(ClusterBindingState.registerSession())
        assertTrue(ClusterBindingState.markPrimary(lease))
        assertTrue(ClusterBindingState.markReady(lease))
        advance(5_000)
        return lease
    }

    @Test
    fun cancelledClearCallbackCannotAdoptUnrestrictedDemand() {
        ClusterBindingState.unregisterSession(ready())
        ClusterNavigationState.update(maneuver)
        advance(100)
        val stale = callbacks.map { it.second }
        assertTrue(stale.isNotEmpty())
        ClusterNavigationState.clear()
        dispatcher.scheduler.runCurrent()
        stale.forEach { it.run() }
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun cancelledSuppressionCallbackCannotAdoptUnrestrictedDemand() {
        ClusterBindingState.unregisterSession(ready())
        ClusterNavigationState.update(maneuver)
        advance(100)
        val stale = callbacks.map { it.second }
        assertTrue(stale.isNotEmpty())
        ClusterNavigationState.suppressCurrentRoute()
        dispatcher.scheduler.runCurrent()
        stale.forEach { it.run() }
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun disableThenReenableCannotReviveDequeuedCallback() {
        ClusterBindingState.unregisterSession(ready())
        ClusterNavigationState.update(maneuver)
        advance(100)
        val stale = callbacks.map { it.second }
        assertTrue(stale.isNotEmpty())
        manager.setClusterEnabled(false)
        dispatcher.scheduler.runCurrent()
        manager.setClusterEnabled(true)
        stale.forEach { it.run() }
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun tripEffectsAndPrimaryCheckShareTheLifecycleLock() {
        ClusterMainSession().onCreateScreen(Intent())
        every { navigation.navigationStarted() } answers {
            assertTrue(Thread.holdsLock(ClusterBindingLifecycle.lock))
        }
        every { navigation.updateTrip(any()) } answers {
            assertTrue(Thread.holdsLock(ClusterBindingLifecycle.lock))
        }
        mockkObject(ClusterBindingState)
        every { ClusterBindingState.isSessionCurrent(any()) } answers {
            assertTrue(Thread.holdsLock(ClusterBindingLifecycle.lock))
            callOriginal()
        }
        ClusterNavigationState.update(maneuver)
        advance(300)
        verify(exactly = 1) { navigation.updateTrip(any()) }
    }

    @Test
    fun routeReplacementDuringStartCannotSendTheOldTrip() {
        ClusterMainSession().onCreateScreen(Intent())
        every { navigation.navigationStarted() } answers {
            ClusterNavigationState.clear()
            ClusterNavigationState.update(maneuver.copy(roadName = "Replacement"))
        }
        ClusterNavigationState.update(maneuver)
        advance(250)
        verify(exactly = 0) { navigation.updateTrip(any()) }
        every { navigation.navigationStarted() } just Runs
        advance(300)
        verify(exactly = 1) { navigation.updateTrip(any()) }
        assertFalse(ClusterNavigationState.isRouteSuppressed)
    }

    @Test
    fun hostStopForOldObservedRouteCannotSuppressReplacementRoute() {
        ClusterMainSession().onCreateScreen(Intent())
        ClusterNavigationState.update(maneuver)
        advance(300)
        ClusterNavigationState.clear()
        ClusterNavigationState.update(maneuver.copy(roadName = "Replacement"))
        hostCallbacks.last().onStopNavigation()
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        advance(300)
        verify(exactly = 2) { navigation.updateTrip(any()) }
    }

    @Test
    fun routeReplacementDuringEndCannotStartTheStaleSnapshot() {
        ClusterMainSession().onCreateScreen(Intent())
        ClusterNavigationState.update(maneuver)
        advance(300)
        every { navigation.navigationEnded() } answers {
            ClusterNavigationState.clear()
            ClusterNavigationState.update(maneuver.copy(roadName = "Newest"))
        }
        ClusterNavigationState.clear()
        ClusterNavigationState.update(maneuver.copy(roadName = "Superseded"))
        advance(250)
        verify(exactly = 1) { navigation.navigationStarted() }
        every { navigation.navigationEnded() } just Runs
        advance(300)
        verify(exactly = 2) { navigation.navigationStarted() }
        verify(exactly = 2) { navigation.updateTrip(any()) }
    }

    @Test
    fun oldArrivalTimerCannotTerminateANewRoute() {
        val original = ClusterMainSession()
        original.onCreateScreen(Intent())
        ClusterNavigationState.update(maneuver.copy(type = ManeuverType.DESTINATION))
        advance(10_100)
        ClusterNavigationState.clear()
        ClusterNavigationState.update(maneuver)
        advance(500)
        assertFalse("arrival belongs only to the route that scheduled it", ClusterNavigationState.isRouteSuppressed)
        verify(exactly = 2) { navigation.updateTrip(any()) }
    }

    @Test
    fun terminalReplacementGetsItsOwnArrivalTimerWithoutAnotherPacket() {
        val session = ClusterMainSession()
        session.onCreateScreen(Intent())
        (session.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        advance(5_000)
        ClusterNavigationState.update(maneuver.copy(type = ManeuverType.DESTINATION))
        advance(1_000)
        // Clear and replace inside debounce while route A's arrival job is active.
        ClusterNavigationState.clear()
        ClusterNavigationState.update(maneuver.copy(type = ManeuverType.DESTINATION, roadName = "Replacement"))
        advance(300)
        verify(exactly = 2) { navigation.navigationStarted() }
        verify(exactly = 2) { navigation.updateTrip(any()) }
        verify(exactly = 1) { navigation.navigationEnded() }
        advance(9_000) // Route A's deadline must not terminate route B.
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        verify(exactly = 1) { navigation.navigationEnded() }
        advance(1_000) // Route B's own deadline, with no additional packets.
        assertTrue("replacement terminal route must reach arrival suppression", ClusterNavigationState.isRouteSuppressed)
        verify(exactly = 2) { navigation.navigationEnded() }
        (session.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        advance(30_000)
        verify(exactly = 1) { context.startActivity(any()) }
        verify(exactly = 2) { navigation.navigationEnded() }
    }

    @Test
    fun releaseRejectsAlreadyDequeuedRecoveryCallbacks() {
        ClusterBindingState.unregisterSession(ready())
        ClusterNavigationState.update(maneuver)
        advance(100)
        val stale = callbacks.map { it.second }
        manager.release()
        stale.forEach { it.run() }
        advance(60_000)
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun disableRejectsAlreadyDequeuedRecoveryCallbacks() {
        ClusterBindingState.unregisterSession(ready())
        ClusterNavigationState.update(maneuver)
        advance(100)
        val stale = callbacks.map { it.second }
        manager.setClusterEnabled(false)
        stale.forEach { it.run() }
        advance(60_000)
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun hostStopChecksGenerationUnderTheSuppressionLock() {
        val original = ClusterMainSession()
        original.onCreateScreen(Intent())
        ClusterNavigationState.update(maneuver)
        advance(300)
        mockkObject(ClusterBindingState)
        every { ClusterBindingState.isSessionCurrent(any()) } answers {
            assertTrue("ownership and suppression must share lifecycle lock", Thread.holdsLock(ClusterBindingLifecycle.lock))
            callOriginal()
        }
        hostCallbacks.last().onStopNavigation()
        assertTrue(ClusterNavigationState.isRouteSuppressed)
    }

    @Test
    fun repeatedReadyThenDeathConsumesOneRouteBudget() {
        var lost = ready()
        ClusterNavigationState.update(maneuver)
        advance(100)
        repeat(4) {
            ClusterBindingState.unregisterSession(lost)
            advance(7_100)
            lost = ready()
        }
        ClusterBindingState.unregisterSession(lost)
        advance(60_000)
        verify(exactly = 5) { context.startActivity(any()) }
    }

    @Test
    fun arrivedRouteDoesNotResurrectAfterTheHostDestroysItsConsumer() {
        val session = ClusterMainSession()
        session.onCreateScreen(Intent())
        (session.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        advance(5_000)
        ClusterNavigationState.update(maneuver.copy(type = ManeuverType.DESTINATION))
        advance(10_500)
        verify(exactly = 1) { navigation.navigationEnded() }
        (session.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        advance(30_000)
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun hostStopRemainsSuppressedAcrossSessionLossAndReplacement() {
        val original = ClusterMainSession()
        original.onCreateScreen(Intent())
        (original.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        advance(5_000)
        ClusterNavigationState.update(maneuver)
        advance(300)
        verify(exactly = 1) { navigation.navigationStarted() }
        verify(exactly = 1) { navigation.updateTrip(any()) }
        hostCallbacks.last().onStopNavigation()
        (original.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        ClusterNavigationState.update(maneuver.copy(distanceMeters = 90))
        advance(10_000)
        verify(exactly = 1) { context.startActivity(any()) }

        // The host can recreate a session by itself: suppression is route-owned,
        // not a property of the old consumer or the recovery implementation.
        val replacement = ClusterMainSession()
        replacement.onCreateScreen(Intent())
        advance(300)
        verify(exactly = 1) { navigation.navigationStarted() }
        verify(exactly = 1) { navigation.updateTrip(any()) }
        ClusterNavigationState.clear()
        ClusterNavigationState.update(maneuver)
        advance(300)
        verify(exactly = 2) { navigation.navigationStarted() }
        verify(exactly = 2) { navigation.updateTrip(any()) }
    }

    @Test
    fun clearingRouteRevokesQueuedRecoveryBeforeItCanRelaunch() {
        ClusterBindingState.unregisterSession(ready())
        ClusterNavigationState.update(maneuver)
        advance(100)
        ClusterNavigationState.clear()
        advance(60_000)
        verify(exactly = 1) { context.startActivity(any()) }

        ClusterNavigationState.update(maneuver)
        advance(2_100)
        verify(exactly = 2) { context.startActivity(any()) }
    }

    @Test
    fun guidanceDuringBootstrapDoesNotReplaceTheInFlightGeneration() {
        val initializing = requireNotNull(ClusterBindingState.registerSession())
        assertTrue(ClusterBindingState.markPrimary(initializing))
        repeat(20) {
            ClusterNavigationState.update(maneuver.copy(distanceMeters = it))
            manager.ensureAlive()
            advance(100)
        }
        assertTrue("async session initialization must retain ownership", ClusterBindingState.markReady(initializing))
        advance(5_000)
        verify(exactly = 1) { context.startActivity(any()) }
    }

    @Test
    fun failedRecoveryIsBoundedDespiteRepeatedGuidanceAndRearmsForNewRoute() {
        ClusterBindingState.unregisterSession(ready())
        repeat(267) {
            ClusterNavigationState.update(maneuver.copy(distanceMeters = 500 - it))
            advance(250)
        }
        // One original bootstrap, then one recovery launch plus three retries.
        verify(exactly = 5) { context.startActivity(any()) }
        advance(60_000)
        verify(exactly = 5) { context.startActivity(any()) }

        ClusterNavigationState.clear()
        ClusterNavigationState.update(maneuver)
        advance(2_100)
        verify(exactly = 6) { context.startActivity(any()) }
    }

    @Test
    fun activeRouteRecoversLossWithoutAnotherNavigationPacket() {
        val lost = ready()
        ClusterNavigationState.update(maneuver)
        advance(1_000)
        ClusterBindingState.unregisterSession(lost)

        advance(7_100)

        verify(exactly = 2) { context.startActivity(any()) }
        assertEquals(maneuver, ClusterNavigationState.state.value)
    }

    @Test
    fun laterGuidanceRecoversLostBindingAndPreservesReplay() {
        val lost = ready()
        ClusterBindingState.unregisterSession(lost)
        advance(60_000) // No idle resurrection after startup health succeeds.
        verify(exactly = 1) { context.startActivity(any()) }

        ClusterNavigationState.update(maneuver)
        advance(2_100)

        verify(exactly = 2) { context.startActivity(any()) }
        assertEquals(maneuver, ClusterNavigationState.state.value)
        val replacement = requireNotNull(ClusterBindingState.registerSession())
        assertTrue(replacement.generation > lost.generation)
        assertFalse(ClusterBindingState.isSessionCurrent(lost))
        ClusterBindingState.unregisterSession(replacement)
        val consumer = ClusterMainSession()
        consumer.onCreateScreen(Intent())
        advance(300)
        verify(exactly = 1) { navigation.navigationStarted() }
        verify(exactly = 1) { navigation.updateTrip(any()) }
    }
}
