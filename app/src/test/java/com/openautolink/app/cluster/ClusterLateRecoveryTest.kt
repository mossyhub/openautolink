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

    private fun metadataLink(clock: () -> Long = { 0L }): Pair<com.openautolink.app.carplay.OalCarPlayMetadataLink, java.util.concurrent.BlockingQueue<String>> {
        val prefs = mockk<com.openautolink.app.data.AppPreferences>()
        mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        every { prefs.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        every { prefs.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        mockkObject(com.openautolink.app.media.OalMediaSessionManager.Companion)
        every { com.openautolink.app.media.OalMediaSessionManager.getInstance(any()) } returns mockk(relaxed = true)
        val receipts = java.util.concurrent.LinkedBlockingQueue<String>()
        val host = com.openautolink.app.carplay.OalCarPlayMetadataHost(context)
        return com.openautolink.app.carplay.OalCarPlayMetadataLink(clock, { receipts.offer(it) }, host) to receipts
    }

    private fun awaitPublication(receipts: java.util.concurrent.BlockingQueue<String>, guidance: Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val line = receipts.poll(maxOf(1L, deadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS)
            assertNotNull("metadata publication must return", line)
            if (line.startsWith("carplay-nav publication=returned guidance=$guidance")) return
            assertTrue("metadata publication must return", System.nanoTime() < deadline)
        }
    }

    @Test
    fun activeCacheMissEndsDisplayedGuidanceAndRestartsSameEpochWhenReady() {
        ClusterMainSession().onCreateScreen(Intent())
        val trips = mutableListOf<androidx.car.app.navigation.model.Trip>()
        every { navigation.updateTrip(capture(trips)) } just Runs
        val (link, receipts) = metadataLink()
        try {
            link.authenticate()
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); u32(10,350) })
            awaitPublication(receipts, true)
            advance(300)
            assertEquals(1, trips.size)
            assertNotNull(trips.single().stepTravelEstimates.single().remainingDistance)
            val epoch = ClusterNavigationState.recoveryDemand.value
            assertNotNull(epoch)
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(8)); u32(10,200) })
            awaitPublication(receipts, false)
            advance(300)
            verify(exactly = 1) { navigation.navigationEnded() }
            assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
            assertTrue(ClusterNavigationState.isActive)
            assertNull(ClusterNavigationState.state.value)
            assertFalse(ClusterNavigationState.isRouteSuppressed)
            assertEquals(1, trips.size)
            assertTrue(trips.none { it.isLoading })
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5202) { u16(1,8); u8(3,3) })
            awaitPublication(receipts, true)
            advance(300)
            assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
            verify(exactly = 2) { navigation.navigationStarted() }
            assertFalse(trips.last().isLoading)
        } finally { link.close() }
    }

    @Test
    fun initiallyUnavailableRouteNeverStartsOrSendsLoadingTrip() {
        ClusterMainSession().onCreateScreen(Intent())
        advance(300) // Idle binding is not navigation ownership.
        ClusterNavigationState.updateHost(null)
        val epoch = ClusterNavigationState.recoveryDemand.value
        advance(300)
        verify(exactly = 0) { navigation.navigationStarted() }
        verify(exactly = 0) { navigation.navigationEnded() }
        verify(exactly = 0) { navigation.updateTrip(any()) }
        ClusterNavigationState.updateHost(maneuver)
        advance(300)
        assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
        verify(exactly = 1) { navigation.navigationStarted() }
        verify(exactly = 1) { navigation.updateTrip(match { !it.isLoading && it.steps.size == 1 }) }
    }

    @Test
    fun lateBindingRetainsUnavailableEpochWithoutClaimingHostNavigation() {
        ClusterNavigationState.updateHost(null)
        val epoch = ClusterNavigationState.recoveryDemand.value
        advance(300)
        ClusterMainSession().onCreateScreen(Intent())
        advance(300)
        verify(exactly = 0) { navigation.navigationStarted() }
        verify(exactly = 0) { navigation.updateTrip(any()) }
        ClusterNavigationState.updateHost(maneuver)
        advance(300)
        assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
        verify(exactly = 1) { navigation.navigationStarted() }
        verify(exactly = 1) { navigation.updateTrip(any()) }
    }

    @Test
    fun hostStopDuringUnavailableRouteSuppressesReadyUntilNewEpoch() {
        ClusterMainSession().onCreateScreen(Intent())
        ClusterNavigationState.updateHost(maneuver)
        advance(300)
        ClusterNavigationState.updateHost(null)
        advance(300)
        hostCallbacks.last().onStopNavigation()
        assertTrue(ClusterNavigationState.isRouteSuppressed)
        ClusterNavigationState.updateHost(maneuver)
        advance(300)
        verify(exactly = 1) { navigation.navigationStarted() }
        verify(exactly = 1) { navigation.updateTrip(any()) }
        verify(exactly = 1) { navigation.navigationEnded() }
        ClusterNavigationState.clearHost()
        advance(300)
        ClusterNavigationState.updateHost(maneuver)
        advance(300)
        verify(exactly = 2) { navigation.navigationStarted() }
        verify(exactly = 2) { navigation.updateTrip(any()) }
    }

    @Test
    fun clearingUnavailableRouteDoesNotEndTwiceOrResurrectRetainedTurn() {
        ClusterMainSession().onCreateScreen(Intent())
        ClusterNavigationState.updateHost(maneuver)
        advance(300)
        val epoch = ClusterNavigationState.recoveryDemand.value
        ClusterNavigationState.updateHost(null)
        advance(300)
        ClusterNavigationState.clearHost()
        advance(30_000)
        verify(exactly = 1) { navigation.navigationStarted() }
        verify(exactly = 1) { navigation.navigationEnded() }
        verify(exactly = 1) { navigation.updateTrip(any()) }
        assertFalse(ClusterNavigationState.isActive)
        ClusterNavigationState.updateHost(maneuver)
        advance(300)
        assertNotEquals(epoch, ClusterNavigationState.recoveryDemand.value)
        verify(exactly = 2) { navigation.navigationStarted() }
    }

    @Test
    fun failedTripBuildClearsPriorTurnOnceAndRecoversOnFreshInstruction() {
        ClusterMainSession().onCreateScreen(Intent())
        ClusterNavigationState.updateHost(maneuver)
        advance(300)
        val epoch = ClusterNavigationState.recoveryDemand.value
        val bad = maneuver.copy(roadName = "private route")
        mockkStatic("com.openautolink.app.cluster.OalClusterSessionKt")
        every { buildManeuver(bad, any()) } throws IllegalArgumentException("private route")
        ClusterNavigationState.updateHost(bad)
        advance(300)
        verify(exactly = 1) { navigation.navigationEnded() }
        verify(exactly = 1) { navigation.updateTrip(any()) }
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        // Forecast refresh invokes the real collector again with the same bad instruction.
        repeat(3) {
            ClusterNavigationState.vehicleEnergyForecast.value = mockk(relaxed = true)
            advance(300)
        }
        verify(exactly = 1) { buildManeuver(bad, any()) }
        verify(exactly = 1) { navigation.navigationEnded() }
        verify(exactly = 1) { navigation.navigationStarted() }
        ClusterNavigationState.updateHost(maneuver.copy(distanceMeters = 90))
        advance(300)
        assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
        verify(exactly = 2) { navigation.navigationStarted() }
        verify(exactly = 2) { navigation.updateTrip(any()) }
    }

    @Test
    fun failedInitialTripBuildNeverClaimsNavigationAndDoesNotRetryByTimer() {
        ClusterMainSession().onCreateScreen(Intent())
        mockkStatic("com.openautolink.app.cluster.OalClusterSessionKt")
        every { buildManeuver(maneuver, any()) } throws IllegalArgumentException("private route")
        ClusterNavigationState.updateHost(maneuver)
        advance(30_000)
        verify(exactly = 1) { buildManeuver(maneuver, any()) }
        verify(exactly = 0) { navigation.navigationStarted() }
        verify(exactly = 0) { navigation.navigationEnded() }
        verify(exactly = 0) { navigation.updateTrip(any()) }
        assertTrue(ClusterNavigationState.isActive)
        assertFalse(ClusterNavigationState.isRouteSuppressed)
    }

    @Test
    fun failedTripSubmissionEndsOldTurnAndRestartsOnlyOnFreshInstruction() {
        ClusterMainSession().onCreateScreen(Intent())
        ClusterNavigationState.updateHost(maneuver)
        advance(300)
        every { navigation.updateTrip(any()) } throws IllegalStateException("host rejected")
        ClusterNavigationState.updateHost(maneuver.copy(distanceMeters = 90))
        advance(300)
        verify(exactly = 1) { navigation.navigationEnded() }
        repeat(3) {
            ClusterNavigationState.vehicleEnergyForecast.value = mockk(relaxed = true)
            advance(300)
        }
        verify(exactly = 2) { navigation.updateTrip(any()) }
        verify(exactly = 1) { navigation.navigationStarted() }
        every { navigation.updateTrip(any()) } just Runs
        ClusterNavigationState.updateHost(maneuver.copy(distanceMeters = 80))
        advance(300)
        verify(exactly = 2) { navigation.navigationStarted() }
        verify(exactly = 3) { navigation.updateTrip(any()) }
    }

    @Test
    fun orderedAvailabilityChurnNeverSendsLoadingOrReplaysOldTurn() {
        val trips = mutableListOf<androidx.car.app.navigation.model.Trip>()
        every { navigation.updateTrip(capture(trips)) } just Runs
        ClusterMainSession().onCreateScreen(Intent())
        ClusterNavigationState.updateHost(null)
        advance(300)
        ClusterNavigationState.updateHost(maneuver)
        advance(300)
        val epoch = ClusterNavigationState.recoveryDemand.value
        ClusterNavigationState.updateHost(null)
        advance(300)
        ClusterNavigationState.updateHost(maneuver.copy(type = ManeuverType.TURN_RIGHT))
        advance(300)
        ClusterNavigationState.updateHost(null)
        advance(300)
        ClusterNavigationState.clearHost()
        advance(300)
        verifyOrder {
            navigation.navigationStarted()
            navigation.updateTrip(any())
            navigation.navigationEnded()
            navigation.navigationStarted()
            navigation.updateTrip(any())
            navigation.navigationEnded()
        }
        verify(exactly = 2) { navigation.navigationStarted() }
        verify(exactly = 2) { navigation.navigationEnded() }
        assertEquals(2, trips.size)
        assertTrue(trips.none { it.isLoading })
        assertEquals(androidx.car.app.navigation.model.Maneuver.TYPE_TURN_NORMAL_LEFT, trips[0].steps.single().maneuver!!.type)
        assertEquals(androidx.car.app.navigation.model.Maneuver.TYPE_TURN_NORMAL_RIGHT, trips[1].steps.single().maneuver!!.type)
        assertNotNull(epoch)
    }

    @Test
    fun availabilityDiagnosticIsFiniteTransitionOnlyAndContainsNoRouteText() {
        val outcomes = mutableListOf<String>()
        mockkObject(com.openautolink.app.diagnostics.DiagnosticLog)
        every { com.openautolink.app.diagnostics.DiagnosticLog.i("cluster", capture(outcomes)) } just Runs
        ClusterMainSession().onCreateScreen(Intent())
        advance(300)
        ClusterNavigationState.updateHost(null)
        advance(300)
        repeat(3) {
            ClusterNavigationState.vehicleEnergyForecast.value = mockk(relaxed = true)
            advance(300)
        }
        ClusterNavigationState.updateHost(maneuver.copy(roadName = "private route", cue = "private cue"))
        advance(300)
        val lines = outcomes.filter { it.startsWith("Guidance availability outcome=") }
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("outcome=idle "))
        assertTrue(lines[1].contains("outcome=unavailable "))
        assertTrue(lines[2].contains("outcome=available "))
        assertTrue(lines.none { it.contains("private") })
    }

    @Test
    fun restoredTerminalWithinLoadingDebounceRetainsSourceAuthority() {
        ClusterMainSession().onCreateScreen(Intent())
        val terminal = maneuver.copy(type = ManeuverType.DESTINATION)
        ClusterNavigationState.update(terminal)
        advance(10_100)
        val epoch = ClusterNavigationState.recoveryDemand.value
        ClusterNavigationState.updateHost(null)
        advance(50)
        ClusterNavigationState.updateHost(terminal)
        advance(100) // Old deadline passed, restored Trip still pending.
        verify(exactly = 0) { navigation.navigationEnded() }
        assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        advance(100) // Restored terminal submitted at 10_350.
        advance(9_999)
        verify(exactly = 0) { navigation.navigationEnded() }
        advance(1)
        verify(exactly = 0) { navigation.navigationEnded() }
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
        verify(exactly = 2) { navigation.updateTrip(any()) }
        ClusterNavigationState.clearHost()
        advance(300)
        verify(exactly = 1) { navigation.navigationEnded() }
    }

    @Test
    fun terminalNonterminalTerminalAbaCannotInventArrival() {
        ClusterMainSession().onCreateScreen(Intent())
        val terminal = maneuver.copy(type = ManeuverType.DESTINATION)
        ClusterNavigationState.update(terminal)
        advance(10_100)
        ClusterNavigationState.update(maneuver)
        advance(50)
        ClusterNavigationState.update(terminal)
        advance(100)
        verify(exactly = 0) { navigation.navigationEnded() }
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        advance(10_100)
        verify(exactly = 0) { navigation.navigationEnded() }
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        verify(exactly = 2) { navigation.updateTrip(any()) }
        ClusterNavigationState.clearHost()
        advance(300)
        verify(exactly = 1) { navigation.navigationEnded() }
    }

    private fun assertActiveTerminalUntilClear(type: ManeuverType) {
        ClusterMainSession().onCreateScreen(Intent())
        val trips = mutableListOf<androidx.car.app.navigation.model.Trip>()
        every { navigation.updateTrip(capture(trips)) } just Runs
        val terminal = maneuver.copy(type = type)
        ClusterNavigationState.updateHost(terminal)
        advance(300)
        val epoch = ClusterNavigationState.recoveryDemand.value
        repeat(49) {
            ClusterNavigationState.updateHost(terminal.copy(distanceMeters = 100 - it))
            advance(1_000)
            assertTrue(ClusterNavigationState.isActive)
            assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
            assertFalse("ACTIVE terminal cue is not source ARRIVED", ClusterNavigationState.isRouteSuppressed)
            verify(exactly = 0) { navigation.navigationEnded() }
        }
        assertTrue(trips.size >= 49)
        assertTrue(trips.all { !it.isLoading && it.stepTravelEstimates.single().remainingDistance != null })
        ClusterNavigationState.clearHost()
        advance(30_000)
        verify(exactly = 1) { navigation.navigationEnded() }
        assertFalse(ClusterNavigationState.isActive)
    }

    @Test fun freshActiveTerminalGuidanceRemainsNavigatingUntilSourceClear() =
        assertActiveTerminalUntilClear(ManeuverType.DESTINATION)

    @Test fun activeDestinationLeftIsNotArrival() = assertActiveTerminalUntilClear(ManeuverType.DESTINATION_LEFT)
    @Test fun activeDestinationRightIsNotArrival() = assertActiveTerminalUntilClear(ManeuverType.DESTINATION_RIGHT)
    @Test fun activeDestinationStraightIsNotArrival() = assertActiveTerminalUntilClear(ManeuverType.DESTINATION_STRAIGHT)

    private fun assertWireStatusClearsTerminal(status: Int) {
        ClusterMainSession().onCreateScreen(Intent())
        val (link, receipts) = metadataLink { now }
        try {
            link.authenticate()
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,10) })
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(7)); u32(10,100) })
            awaitPublication(receipts, true)
            advance(300)
            repeat(12) {
                link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,1); u32(10,99-it) })
                awaitPublication(receipts, true)
                advance(1_000)
            }
            verify(exactly = 0) { navigation.navigationEnded() }
            assertFalse(ClusterNavigationState.isRouteSuppressed)
            link.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1,status) })
            awaitPublication(receipts, false)
            advance(30_000)
            assertFalse(ClusterNavigationState.isActive)
            assertNull(ClusterNavigationState.state.value)
            assertNull(ClusterNavigationState.recoveryDemand.value)
            verify(exactly = 1) { navigation.navigationEnded() }
        } finally { link.close() }
        advance(300)
        verify(exactly = 1) { navigation.navigationEnded() }
    }

    @Test fun sourceArrivedEndsTerminalGuidanceOnce() = assertWireStatusClearsTerminal(2)
    @Test fun sourceStopEndsTerminalGuidanceOnce() = assertWireStatusClearsTerminal(0)

    @Test
    fun revokedTerminalOwnerRejectsLateHostStopAndGuidance() {
        ClusterMainSession().onCreateScreen(Intent())
        ClusterNavigationState.updateHost(maneuver.copy(type = ManeuverType.DESTINATION))
        advance(300)
        val oldCallback = hostCallbacks.last()
        manager.release()
        advance(300)
        verify(exactly = 1) { navigation.navigationEnded() }
        ClusterNavigationState.clearHost()
        ClusterNavigationState.updateHost(maneuver)
        oldCallback.onStopNavigation()
        advance(30_000)
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        verify(exactly = 1) { navigation.updateTrip(any()) }
        verify(exactly = 1) { navigation.navigationEnded() }
    }

    @Test
    fun hostStopReenteredFromTerminalUpdateRemainsSuppressed() {
        ClusterMainSession().onCreateScreen(Intent())
        every { navigation.updateTrip(any()) } answers { hostCallbacks.last().onStopNavigation() }
        ClusterNavigationState.updateHost(maneuver.copy(type = ManeuverType.DESTINATION))
        advance(30_000)
        assertTrue(ClusterNavigationState.isRouteSuppressed)
        ClusterNavigationState.updateHost(maneuver.copy(distanceMeters = 90))
        advance(300)
        verify(exactly = 1) { navigation.updateTrip(any()) }
        verify(exactly = 0) { navigation.navigationEnded() }
    }

    @Test
    fun unavailableTerminalSnapshotWithdrawsOnlyAtDebounceWithoutSuppression() {
        ClusterMainSession().onCreateScreen(Intent())
        ClusterNavigationState.update(maneuver.copy(type = ManeuverType.DESTINATION))
        advance(10_100) // Withdrawal is due at 10_300, not the obsolete 10_200 deadline.
        ClusterNavigationState.updateHost(null)
        advance(100)
        verify(exactly = 0) { navigation.navigationEnded() }
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        advance(100)
        verify(exactly = 1) { navigation.navigationEnded() }
        assertFalse(ClusterNavigationState.isRouteSuppressed)
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
    fun terminalToNormalNewRouteHasNoLateTermination() {
        val original = ClusterMainSession()
        original.onCreateScreen(Intent())
        ClusterNavigationState.update(maneuver.copy(type = ManeuverType.DESTINATION))
        advance(10_100)
        ClusterNavigationState.clear()
        ClusterNavigationState.update(maneuver)
        advance(30_000)
        assertFalse("old terminal guidance cannot terminate a new route", ClusterNavigationState.isRouteSuppressed)
        verify(exactly = 1) { navigation.navigationEnded() }
        verify(exactly = 2) { navigation.updateTrip(any()) }
    }

    @Test
    fun terminalReplacementStaysLiveUntilOwnerRetiresWithoutAnotherPacket() {
        val session = ClusterMainSession()
        session.onCreateScreen(Intent())
        (session.lifecycle as LifecycleRegistry).handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        advance(5_000)
        ClusterNavigationState.update(maneuver.copy(type = ManeuverType.DESTINATION))
        advance(1_000)
        // Clear and replace inside debounce while route A is displayed.
        ClusterNavigationState.clear()
        ClusterNavigationState.update(maneuver.copy(type = ManeuverType.DESTINATION, roadName = "Replacement"))
        advance(300)
        verify(exactly = 2) { navigation.navigationStarted() }
        verify(exactly = 2) { navigation.updateTrip(any()) }
        verify(exactly = 1) { navigation.navigationEnded() }
        advance(9_000) // The old guessed deadline must not terminate route B.
        assertFalse(ClusterNavigationState.isRouteSuppressed)
        verify(exactly = 1) { navigation.navigationEnded() }
        advance(30_000)
        assertFalse("replacement terminal route is still source-active", ClusterNavigationState.isRouteSuppressed)
        verify(exactly = 1) { navigation.navigationEnded() }
        ClusterNavigationState.clearHost()
        advance(300)
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
        verify(exactly = 0) { navigation.navigationEnded() }
        ClusterNavigationState.clearHost() // Source arrival, not terminal maneuver duration.
        advance(300)
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
