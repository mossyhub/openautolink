package com.openautolink.app.carplay

import android.app.Application
import android.content.Context
import androidx.car.app.CarContext
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Trip
import com.openautolink.app.cluster.ClusterMainSession
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.carplaylab.metadata.CarPlayMetadataIntake
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Locale
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain

/** Wire-faithful synthetic fixture, not an owner-phone packet capture or GM render proof. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayDistanceUnitsConsumerTest {
    private fun frame(id: Int, hex: String) = Iap2Frame(id,
        hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray())

    private fun trip(state: ManeuverState): Trip {
        val context = mockk<CarContext>(relaxed = true)
        val app = RuntimeEnvironment.getApplication()
        every { context.resources } returns app.resources
        every { context.packageName } returns app.packageName
        val session = spyk(ClusterMainSession())
        every { session.carContext } returns context
        return ClusterMainSession::class.java.getDeclaredMethod("buildTrip", ManeuverState::class.java)
            .apply { isAccessible = true }.invoke(session, state) as Trip
    }

    private class Fixture {
        var now = 0L
        val intake = CarPlayMetadataIntake { now }.apply { authenticate() }
        val host = OalCarPlayMetadataHost(mockk<Context>())
        init {
            val owner = host.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(host)
            assertTrue(MetadataHostOwnership.process.claim(owner))
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
            intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,8); u8(3,1) })
        }
        fun update(raw: Int? = null, value: String? = null, unit: Int? = null,
            destRaw: Int? = null, destValue: String? = null, destUnit: Int? = null) {
            intake.handle(Iap2Messages.buildRaw(0x5201) {
                u8(1,1); u16List(13,listOf(7))
                raw?.let { u32(10,it) }; value?.let { string(11,it) }; unit?.let { u8(12,it) }
                destRaw?.let { u32(7,it) }; destValue?.let { string(8,it) }; destUnit?.let { u8(9,it) }
            })
        }
        fun state(): ManeuverState {
            host.publishRoute(null, intake.routeMetadata, null)
            return requireNotNull(ClusterNavigationState.state.value)
        }
        fun close() { host.close(); ClusterNavigationState.clear() }
    }
    private fun withFixture(block: (Fixture) -> Unit) {
        val locale = Locale.getDefault()
        val units = ClusterNavigationState.distanceUnits
        val f = Fixture()
        try {
            Locale.setDefault(Locale.US)
            ClusterNavigationState.distanceUnits = "imperial"
            block(f)
        } finally { f.close(); ClusterNavigationState.distanceUnits = units; Locale.setDefault(locale) }
    }
    private fun assertStep(f: Fixture, value: Double?, unit: Int?) {
        val estimate = trip(f.state()).stepTravelEstimates.single()
        val distance = estimate.remainingDistance
        if (value == null) assertNull(distance) else {
            assertNotNull(distance)
            assertEquals(value, distance!!.displayDistance, 0.000000001)
            assertEquals(unit, distance.displayUnit)
        }
        assertNull("trip duration is not next-step ETA", estimate.arrivalTimeAtDestination)
        assertEquals(androidx.car.app.navigation.model.TravelEstimate.REMAINING_TIME_UNKNOWN, estimate.remainingTimeSeconds)
    }
    @Test fun everySourcedWireUnitSurvivesConflictingLocalPolicyAndOptionalRawMeters() {
        val units = mapOf(0 to Distance.UNIT_KILOMETERS, 1 to Distance.UNIT_MILES,
            2 to Distance.UNIT_METERS, 3 to Distance.UNIT_YARDS, 4 to Distance.UNIT_FEET)
        for ((wire, model) in units) for (raw in listOf(null, 9999)) withFixture { f ->
            ClusterNavigationState.distanceUnits = if (wire == 1 || wire == 4) "metric" else "imperial"
            f.update(raw, "1.5", wire)
            assertStep(f, 1.5, model)
        }
    }
    @Test fun sourcedZeroIsValidWithoutRawAndUnknownPairsNeverBorrowDestination() {
        for (wire in 0..4) withFixture { f ->
            f.update(value = "0", unit = wire)
            assertStep(f, 0.0, mapOf(0 to Distance.UNIT_KILOMETERS, 1 to Distance.UNIT_MILES,
                2 to Distance.UNIT_METERS, 3 to Distance.UNIT_YARDS, 4 to Distance.UNIT_FEET)[wire])
        }
        for ((value, unit) in listOf(null to null, "1.5" to null, null to 0, "" to 0,
            "-1" to 0, "NaN" to 0, "Infinity" to 0, "bad" to 0, "1.5" to 255, "1.5" to 99)) {
            withFixture { f ->
                f.update(value = value, unit = unit, destRaw = 8800, destValue = "8.8", destUnit = 0)
                assertStep(f, null, null)
            }
        }
    }
    @Test fun absentOrInvalidSourceUsesOnlyValidRawWithExistingMetricImperialAndLocalePolicy() {
        for (policy in listOf("metric", "imperial", "auto")) withFixture { f ->
            ClusterNavigationState.distanceUnits = policy
            f.update(raw = 1500, value = "-1", unit = 0)
            if (policy == "metric") assertStep(f, 1.5, Distance.UNIT_KILOMETERS)
            else assertStep(f, 1500 / 1609.344, Distance.UNIT_MILES)
            f.update(raw = 0, value = "", unit = 255)
            assertStep(f, 0.0, if (policy == "metric") Distance.UNIT_METERS else Distance.UNIT_FEET)
        }
        withFixture { f ->
            ClusterNavigationState.distanceUnits = "auto"; Locale.setDefault(Locale.CANADA)
            f.update(raw = 100); assertStep(f, 100.0, Distance.UNIT_METERS)
            // A negative signed representation is not a valid U32 distance: reject the
            // complete malformed frame, retaining the prior good snapshot, not -1/zero.
            f.intake.handle(frame(0x5201,"0005000101 0006000d0007 0008000affffffff"))
            assertStep(f, 100.0, Distance.UNIT_METERS)
        }
    }
    @Test fun destinationDisplayIsIndependentOfNextStepEvenWithoutEitherRawField() = withFixture { f ->
        f.update(value = "2", unit = 4, destValue = "8.8", destUnit = 0)
        assertStep(f, 2.0, Distance.UNIT_FEET)
        val state = f.state()
        assertNull(state.distanceMeters); assertNull(state.destDistanceMeters)
        assertEquals("8.8", state.destDistanceDisplay)
        assertEquals("kilometers", state.destDistanceUnit)
    }
    @Test fun rawOnlyDeltaDoesNotDiscardStillFreshSourcedDisplayOrRefreshItsAge() = withFixture { f ->
        f.update(raw = 1500, value = "1.5", unit = 0)
        f.now = 1000; f.update(raw = 1490)
        assertStep(f, 1.5, Distance.UNIT_KILOMETERS)
        f.now = 30_000; f.intake.tick()
        assertStep(f, 1490 / 1609.344, Distance.UNIT_MILES)
    }
    @Test fun partialDisplayUpdateDoesNotCombineNewValueWithOldUnitOrDestinationUnit() = withFixture { f ->
        f.update(value = "1.5", unit = 0, destValue = "9", destUnit = 1)
        f.update(value = "2.5")
        assertStep(f, null, null)
        f.update(unit = 4)
        assertStep(f, null, null)
    }
    @Test fun selectionArrivalStopAndExpiryClearDisplayWithoutInventingZeroOrChangingRouteDemand() = withFixture { f ->
        f.update(value = "1.5", unit = 0)
        f.state()
        val demand = ClusterNavigationState.recoveryDemand.value
        f.now = 30_000; f.intake.tick(); assertStep(f, null, null)
        assertTrue(ClusterNavigationState.isActive)
        assertEquals(demand, ClusterNavigationState.recoveryDemand.value)
        f.intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(8)) })
        assertStep(f, null, null)
        for (terminal in listOf(2,0)) {
            f.update(value = "0", unit = 2); f.state()
            f.intake.handle(Iap2Messages.buildRaw(0x5201) { u8(1,terminal) })
            f.host.publishRoute(null, f.intake.routeMetadata, null)
            assertNull(ClusterNavigationState.state.value); assertFalse(ClusterNavigationState.isActive)
            // terminal clears definitions; repopulate for next independent route.
            f.intake.handle(Iap2Messages.buildRaw(0x5202) { u16(1,7); u8(3,2) })
        }
        f.update(value = "1.5", unit = 0); f.state()
        f.now += 120_000; f.intake.tick(); f.host.publishRoute(null, f.intake.routeMetadata, null)
        assertNull(ClusterNavigationState.state.value); assertFalse(ClusterNavigationState.isActive)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun displayOnlyKilometersPassActualMainAdmissionAndReachNavigationManager() = withFixture { f ->
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val lease = com.openautolink.app.cluster.ClusterBindingState.openManager()
        val context = mockk<CarContext>(relaxed = true)
        val app = RuntimeEnvironment.getApplication()
        every { context.resources } returns app.resources
        every { context.packageName } returns app.packageName
        val manager = mockk<androidx.car.app.navigation.NavigationManager>(relaxed = true)
        val trips = mutableListOf<Trip>()
        every { context.getCarService(androidx.car.app.navigation.NavigationManager::class.java) } returns manager
        every { manager.updateTrip(capture(trips)) } answers { Unit }
        val session = spyk(ClusterMainSession())
        every { session.carContext } returns context
        val lifecycle = androidx.lifecycle.LifecycleRegistry(session)
        every { session.lifecycle } returns lifecycle
        try {
            lifecycle.currentState = androidx.lifecycle.Lifecycle.State.CREATED
            session.onCreateScreen(android.content.Intent().putExtra(
                com.openautolink.app.cluster.CLUSTER_BINDING_GENERATION_EXTRA, lease.generation))
            f.update(value = "1.5", unit = 0)
            f.state()
            assertNull(ClusterNavigationState.state.value!!.distanceMeters)
            synchronized(com.openautolink.app.cluster.ClusterBindingLifecycle.lock) {
                ClusterMainSession::class.java.getDeclaredMethod("processStateUpdate", ManeuverState::class.java,
                    java.lang.Long::class.java, Boolean::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                    .apply { isAccessible = true }.invoke(session, ClusterNavigationState.state.value,
                        ClusterNavigationState.recoveryDemand.value, ClusterNavigationState.isActive,
                        ClusterNavigationState.terminalValidity.value)
            }
            io.mockk.verify(exactly = 1) { manager.navigationStarted() }
            val distance = requireNotNull(trips.single().stepTravelEstimates.single().remainingDistance)
            assertEquals(Distance.UNIT_KILOMETERS, distance.displayUnit)
            assertEquals(1.5, distance.displayDistance, 0.0)
        } finally {
            lifecycle.currentState = androidx.lifecycle.Lifecycle.State.DESTROYED
            com.openautolink.app.cluster.ClusterBindingState.closeManager(lease)
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test fun sourcedKilometersReachActualMainTripDespiteImperialPreferenceAndUsLocale() {
        val previousLocale = Locale.getDefault()
        val previousUnits = ClusterNavigationState.distanceUnits
        val intake = CarPlayMetadataIntake { 0L }.apply { authenticate() }
        val host = OalCarPlayMetadataHost(mockk<Context>())
        val owner = host.javaClass.getDeclaredField("owner").apply { isAccessible = true }.get(host)
        assertTrue(MetadataHostOwnership.process.claim(owner))
        try {
            Locale.setDefault(Locale.US)
            ClusterNavigationState.distanceUnits = "imperial"
            // 0x5202: index7, right turn2. 0x5201: ACTIVE1, current[7],
            // raw1500m (TLV10), display"1.5" NUL (TLV11), kilometers0 U8 (TLV12).
            intake.handle(frame(0x5202, "000600010007 0005000302"))
            intake.handle(frame(0x5201,
                "0005000101 0006000d0007 0008000a000005dc 0008000b312e3500 0005000c00"))
            host.publishRoute(null, intake.routeMetadata, null)
            val state = requireNotNull(ClusterNavigationState.state.value)
            assertEquals(1500, state.distanceMeters)
            val distance = requireNotNull(trip(state).stepTravelEstimates.single().remainingDistance)
            assertEquals("source kilometers must not become locale/preference miles", Distance.UNIT_KILOMETERS, distance.displayUnit)
            assertEquals(1.5, distance.displayDistance, 0.0)
        } finally {
            host.close(); ClusterNavigationState.clear()
            ClusterNavigationState.distanceUnits = previousUnits
            Locale.setDefault(previousLocale)
        }
    }
}
