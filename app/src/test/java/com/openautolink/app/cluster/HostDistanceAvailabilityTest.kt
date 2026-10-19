package com.openautolink.app.cluster

import android.app.Application
import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.model.Distance
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class HostDistanceAvailabilityTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var owner: ClusterBindingRegistry.ManagerLease
    private lateinit var session: ClusterMainSession
    private lateinit var lifecycle: LifecycleRegistry
    private lateinit var callback: NavigationManagerCallback
    private val manager = mockk<NavigationManager>(relaxed = true)
    private val trips = mutableListOf<Trip>()
    private val missing = ManeuverState(ManeuverType.UNKNOWN, null, "", "Road", null, cue = "Keep going")
    private val zero = missing.copy(displayDistance = "0", displayDistanceUnit = "feet")

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        ClusterNavigationState.clearHost()
        owner = ClusterBindingState.openManager()
        val context = mockk<CarContext>(relaxed = true)
        val app = RuntimeEnvironment.getApplication()
        every { context.resources } returns app.resources
        every { context.packageName } returns app.packageName
        every { context.getCarService(NavigationManager::class.java) } returns manager
        val slot = slot<NavigationManagerCallback>()
        every { manager.setNavigationManagerCallback(capture(slot)) } just Runs
        every { manager.updateTrip(capture(trips)) } just Runs
        session = spyk(ClusterMainSession())
        every { session.carContext } returns context
        lifecycle = LifecycleRegistry(session)
        every { session.lifecycle } returns lifecycle
        lifecycle.currentState = Lifecycle.State.CREATED
        session.onCreateScreen(Intent().putExtra(CLUSTER_BINDING_GENERATION_EXTRA, owner.generation))
        callback = slot.captured
    }
    @After fun cleanup() {
        lifecycle.currentState = Lifecycle.State.DESTROYED
        ClusterBindingState.closeManager(owner)
        ClusterNavigationState.clearHost()
        Dispatchers.resetMain()
        unmockkAll()
    }
    // Invoke the real guarded effect boundary using its exact current singleton snapshot.
    private fun process() = synchronized(ClusterBindingLifecycle.lock) {
        ClusterMainSession::class.java.getDeclaredMethod("processStateUpdate", ManeuverState::class.java,
            java.lang.Long::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(session, ClusterNavigationState.state.value,
                ClusterNavigationState.recoveryDemand.value, ClusterNavigationState.isActive,
                ClusterNavigationState.terminalValidity.value)
    }
    private fun publish(state: ManeuverState) { ClusterNavigationState.updateHost(state); process() }

    @Test fun unknownDestinationTimeNeverUsesKnownStepEta() {
        publish(zero.copy(etaSeconds = 60, destination = "Destination", destDistanceMeters = 400))
        val trip = trips.single()
        val destination = trip.destinationTravelEstimates.single()
        assertNull("unknown destination ETA must not inherit step ETA", destination.arrivalTimeAtDestination)
        assertEquals(TravelEstimate.REMAINING_TIME_UNKNOWN, destination.remainingTimeSeconds)
        assertNotNull(trip.stepTravelEstimates.single().arrivalTimeAtDestination)
    }

    @Test fun sourcedDestinationZeroIsArrivalNowNotStepEta() {
        val before = java.time.ZonedDateTime.now().toInstant().toEpochMilli()
        publish(zero.copy(etaSeconds = 60, destination = "Destination", destDistanceMeters = 400,
            timeToArrivalSeconds = 0))
        val after = java.time.ZonedDateTime.now().toInstant().toEpochMilli()
        val trip = trips.single()
        val destination = trip.destinationTravelEstimates.single()
        assertEquals(0L, destination.remainingTimeSeconds)
        assertArrivalBetween(destination, before, after)
        assertArrivalBetween(trip.stepTravelEstimates.single(), before + 60_000, after + 60_000)
    }

    @Test fun negativeDestinationTimeRemainsUnknownWithKnownStepEta() {
        publish(zero.copy(etaSeconds = 60, destination = "Destination", destDistanceMeters = 400,
            timeToArrivalSeconds = -30))
        val destination = trips.single().destinationTravelEstimates.single()
        assertNull(destination.arrivalTimeAtDestination)
        assertEquals(TravelEstimate.REMAINING_TIME_UNKNOWN, destination.remainingTimeSeconds)
        assertNotNull(trips.single().stepTravelEstimates.single().arrivalTimeAtDestination)
    }

    @Test fun positiveDestinationTimeIsIndependentOfStepEta() {
        val before = java.time.ZonedDateTime.now().toInstant().toEpochMilli()
        publish(zero.copy(etaSeconds = 60, destination = "Destination", destDistanceMeters = 400,
            timeToArrivalSeconds = 900))
        val after = java.time.ZonedDateTime.now().toInstant().toEpochMilli()
        val trip = trips.single()
        val destination = trip.destinationTravelEstimates.single()
        assertEquals(900L, destination.remainingTimeSeconds)
        assertArrivalBetween(destination, before + 900_000, after + 900_000)
        assertArrivalBetween(trip.stepTravelEstimates.single(), before + 60_000, after + 60_000)
    }

    @Test fun sourcedStepZeroRemainsValidWhileDestinationTimeIsUnknown() {
        val before = java.time.ZonedDateTime.now().toInstant().toEpochMilli()
        publish(zero.copy(etaSeconds = 0, destination = "Destination", destDistanceMeters = 400))
        val after = java.time.ZonedDateTime.now().toInstant().toEpochMilli()
        val trip = trips.single()
        assertArrivalBetween(trip.stepTravelEstimates.single(), before, after)
        assertNull(trip.destinationTravelEstimates.single().arrivalTimeAtDestination)
        assertEquals(TravelEstimate.REMAINING_TIME_UNKNOWN,
            trip.destinationTravelEstimates.single().remainingTimeSeconds)
    }

    private fun assertArrivalBetween(estimate: TravelEstimate, earliest: Long, latest: Long) {
        val arrival = estimate.arrivalTimeAtDestination!!.timeSinceEpochMillis
        // AndroidX DateTimeWithZone serializes epoch seconds, dropping subsecond precision.
        assertTrue("arrival $arrival must be between $earliest and $latest",
            arrival in (earliest / 1000 * 1000)..latest)
    }

    @Test fun unsourcedDestinationIsOmittedEvenWithEtaAndStepDistance() {
        for (state in listOf(zero.copy(destination = "Destination"),
            zero.copy(destination = "Destination", timeToArrivalSeconds = 120),
            zero.copy(destination = "Destination", destDistanceMeters = -1,
                destDistanceDisplay = "NaN", destDistanceUnit = "feet"))) {
            publish(state)
            assertTrue("unsourced destination must be omitted", trips.last().destinations.isEmpty())
            assertTrue(trips.last().destinationTravelEstimates.isEmpty())
        }
    }
    @Test fun sourcedDestinationRawZeroAndFormattedZeroAreAcceptedWithoutFakeEta() {
        for (state in listOf(zero.copy(destination = "Destination", destDistanceMeters = 0),
            zero.copy(destination = "Destination", destDistanceDisplay = "0", destDistanceUnit = "feet"))) {
            publish(state)
            val estimate = trips.last().destinationTravelEstimates.single()
            assertEquals(0.0, estimate.remainingDistance!!.displayDistance, 0.0)
            assertNull(estimate.arrivalTimeAtDestination)
            assertEquals(TravelEstimate.REMAINING_TIME_UNKNOWN, estimate.remainingTimeSeconds)
        }
    }
    @Test fun unavailableDistanceWithdrawsWithoutReusingKnownDistanceThenResumesSameEpoch() {
        publish(zero)
        val epoch = ClusterNavigationState.recoveryDemand.value
        for (invalid in listOf(missing, missing.copy(distanceMeters = -1),
            missing.copy(displayDistance = "NaN", displayDistanceUnit = "feet"),
            missing.copy(displayDistance = "0"))) publish(invalid)
        verify(exactly = 1) { manager.updateTrip(any()) }
        verify(exactly = 1) { manager.navigationEnded() }
        assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
        assertEquals("Keep going", ClusterNavigationState.state.value!!.cue)
        publish(zero)
        verify(exactly = 2) { manager.navigationStarted() }
        verify(exactly = 2) { manager.updateTrip(any()) }
        assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
    }
    @Test fun hostStopDuringUnavailableDistanceCannotResumeOnRefresh() {
        publish(zero); publish(missing)
        callback.onStopNavigation()
        publish(zero)
        assertTrue(ClusterNavigationState.isRouteSuppressed)
        verify(exactly = 1) { manager.navigationStarted() }
        verify(exactly = 1) { manager.updateTrip(any()) }
    }
    @Test fun revokedBindingCannotResumeOnRefresh() {
        publish(zero); publish(missing)
        ClusterBindingState.closeManager(owner)
        publish(zero)
        verify(exactly = 1) { manager.navigationStarted() }
        verify(exactly = 1) { manager.updateTrip(any()) }
    }
    @Test fun realCarPlayEstimateExpiryWithdrawsAndSourcedRefreshResumes() {
        var now = 0L
        val intake = com.openautolink.carplaylab.metadata.CarPlayMetadataIntake { now }.apply { authenticate() }
        val host = com.openautolink.app.carplay.OalCarPlayMetadataHost(mockk<android.content.Context>())
        val token = host.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(host)!!
        assertTrue(com.openautolink.app.carplay.MetadataHostOwnership.process.claim(token))
        try {
            intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5202) { u16(1, 7); u8(3, 2); string(4, "Turn road") })
            fun estimate() { intake.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0x5201) { u8(1, 1); u16List(13, listOf(7)); u32(10, 350) }) }
            fun deliver() { host.publishRoute(intake.media, intake.routeMetadata, intake.artwork); process() }
            estimate(); deliver()
            val epoch = ClusterNavigationState.recoveryDemand.value
            assertNotNull(trips.single().stepTravelEstimates.single().remainingDistance)
            now = 30_110; intake.tick(); deliver()
            assertNull(ClusterNavigationState.state.value!!.distanceMeters)
            assertEquals("Turn road", ClusterNavigationState.state.value!!.roadName)
            assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
            verify(exactly = 1) { manager.updateTrip(any()) }
            verify(exactly = 1) { manager.navigationEnded() }
            estimate(); deliver()
            assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
            verify(exactly = 2) { manager.navigationStarted() }
            verify(exactly = 2) { manager.updateTrip(any()) }
            assertTrue(trips.all { t -> t.stepTravelEstimates.all { it.remainingDistance != null } })
        } finally { host.close() }
    }

    @Test fun missingDistanceNeverClaimsHostAndFormattedZeroResumesSameEpoch() {
        publish(missing)
        val epoch = ClusterNavigationState.recoveryDemand.value
        verify(exactly = 0) { manager.navigationStarted() }
        verify(exactly = 0) { manager.updateTrip(any()) }
        assertEquals(missing, ClusterNavigationState.state.value)
        assertTrue(ClusterNavigationState.isActive)
        publish(zero)
        assertEquals(epoch, ClusterNavigationState.recoveryDemand.value)
        verify(exactly = 1) { manager.navigationStarted() }
        val estimate = trips.single().stepTravelEstimates.single()
        assertEquals(0.0, estimate.remainingDistance!!.displayDistance, 0.0)
        assertEquals(Distance.UNIT_FEET, estimate.remainingDistance!!.displayUnit)
        assertNull(estimate.arrivalTimeAtDestination)
        assertEquals(TravelEstimate.REMAINING_TIME_UNKNOWN, estimate.remainingTimeSeconds)
        assertEquals("Keep going", trips.single().steps.single().cue.toString())
    }
}
