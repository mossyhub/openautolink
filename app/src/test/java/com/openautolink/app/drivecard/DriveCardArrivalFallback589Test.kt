package com.openautolink.app.drivecard

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import com.openautolink.app.cluster.ClusterNavigationState
import com.openautolink.app.cluster.PixelRect
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.navigation.EnergyAtDistance
import com.openautolink.app.navigation.ManeuverState
import com.openautolink.app.navigation.ManeuverType
import com.openautolink.app.navigation.VehicleEnergyForecast
import com.openautolink.app.secondary.SecondaryHostGeometry
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Tests-only vertical slice. Existing model/state API; no proposed production API.
 * All local arrivals are OAL approximations, never Apple-accepted or AA-sourced SOC. */
class DriveCardArrivalFallback589Test {
    @Test fun freshStationaryProcessCanEstimateLocalArrivalBeforeAnyDriveHistory() {
        for (nominalOnly in listOf(false, true)) {
            val state = local(sample(nominalOnly = nominalOnly))
            assertNull("No learned rate is fabricated", state.recentKmPerKwh)
            assertNull("Stationary battery/range is not instantaneous efficiency", state.nowKmPerKwh)
            assertNull("No power telemetry is supplied or inferred", state.powerKw)
            assertTrue("Fresh process has no completed distance windows", state.trace.isEmpty())
            assertLocal(state, if (nominalOnly) 53 else 60)
            assertEquals(DriveCardAvailability.READY, state.availability)
        }
        // Both valid scales: current usable wins over reported nominal for LOCAL percent.
        assertLocal(local(sample().copy(evBatteryCapacityWh = 80_000f)), 60)
    }

    @Test fun rangeEnergyBasisUsesDistanceNotRouteDurationOrPower() {
        for (seconds in listOf(1L, 1200L, 7200L)) {
            assertLocal(local(sample(), route().copy(remainingSeconds = seconds)), 60)
        }
        assertLocal(local(sample().copy(speedKmh = 84f)), 60)
        // 50,000 Wh / 250 km = 200 Wh/km; 20 km consumes 4,000 Wh.
        assertLocal(local(sample(), route().copy(distanceMeters = 20_000)), 66)
        assertLocal(local(sample(), route().copy(distanceMeters = 250_000)), 0)
        assertNull("Insufficient energy is not a clamped fictitious zero", local(sample(), route().copy(distanceMeters = 251_000)).arrival)
    }

    @Test fun eachRequiredObservationMustRetainItsOwnSourceStatusIdentityAndFreshness() {
        for (nominalOnly in listOf(false, true)) {
            val data = sample(nominalOnly = nominalOnly)
            val capacityKey = if (nominalOnly) INFO else CAPACITY
            for (key in listOf(BATTERY, SPEED, RANGE, capacityKey)) {
                val observation = data.evObservationMetadata.getValue(key)
                val age = if (key == capacityKey) 120_001L else 15_001L
                val bad = listOf(
                    "bad-status" to observation.copy(status = 1),
                    "unknown-status" to observation.copy(status = null),
                    "inactive" to observation.copy(subscriptionActive = false),
                    "wrong-generation" to observation.copy(registrationGeneration = 6),
                    "wrong-property" to observation.copy(propertyId = 0x11600703),
                    "wrong-source" to observation.copy(source = "history-provider-coherent"),
                    "old-event" to observation.copy(timestampElapsedNanos = (NOW - age) * 1_000_000),
                    "old-receipt" to observation.copy(receivedElapsedMs = NOW - age),
                    "future-event" to observation.copy(timestampElapsedNanos = (NOW + 1) * 1_000_000),
                    "future-receipt" to observation.copy(receivedElapsedMs = NOW + 1),
                    "zero-event" to observation.copy(timestampElapsedNanos = 0),
                    "missing-event" to observation.copy(timestampElapsedNanos = null),
                )
                for ((reason, changed) in bad) {
                    assertNull("$key/$reason/nominalOnly=$nominalOnly", local(withObservation(data, key, changed)).arrival)
                }
                assertNull("Missing $key metadata", local(data.copy(evObservationMetadata = data.evObservationMetadata - key)).arrival)
                assertLocal(local(data), if (nominalOnly) 53 else 60)
            }
            for (generation in listOf<Long?>(null, 0, 8)) {
                assertNull("No live matching registration: $generation", local(data.copy(vhalRegistrationGeneration = generation)).arrival)
            }
        }
    }

    @Test fun exactCompletedReadsCanQualifyOldEventsButCachedOrWrongReadMarkersCannot() {
        for (nominalOnly in listOf(false, true)) {
            val data = sample(nominalOnly = nominalOnly)
            val exact = data.copy(evObservationMetadata = data.evObservationMetadata.mapValues { (_, observation) ->
                observation.copy(timestampElapsedNanos = 1000L * 1_000_000, statusReadElapsedMs = NOW)
            })
            assertLocal(local(exact), if (nominalOnly) 53 else 60)
            for (key in listOf(BATTERY, SPEED, RANGE, if (nominalOnly) INFO else CAPACITY)) {
                val observation = exact.evObservationMetadata.getValue(key)
                for (marker in listOf<Long?>(null, NOW - 1)) {
                    assertNull("$key must be its exact completed current read", local(withObservation(exact, key,
                        observation.copy(statusReadElapsedMs = marker))).arrival)
                }
            }
            val model = model(exact)
            assertNull("A timer cannot refresh cached vehicle receipts", model.snapshot(NOW + 15_001).arrival)
        }
    }

    @Test fun invalidScalarInputsAndMissingCapacityCannotInventLocalArrival() {
        for (value in listOf<Float?>(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull("Invalid battery $value", local(sample().copy(evBatteryLevelWh = value)).arrival)
            assertNull("Invalid dashboard range $value", local(sample().copy(rangeKm = value)).arrival)
            assertNull("Invalid usable capacity $value", local(sample().copy(evCurrentBatteryCapacityWh = value)).arrival)
            assertNull("Invalid nominal capacity $value", local(sample(nominalOnly = true).copy(evBatteryCapacityWh = value)).arrival)
        }
        for (value in listOf<Float?>(null, -1f, 351f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertNull("Speed qualification remains required even though speed is not consumption: $value",
                local(sample().copy(speedKmh = value)).arrival)
        }
        assertNull(local(sample(nominalOnly = true).copy(evBatteryCapacityWh = 9999f)).arrival)
        assertNull(local(sample(nominalOnly = true).copy(evBatteryCapacityWh = 250_001f)).arrival)
        assertNull("Neither capacity source is available", local(sample().copy(evCurrentBatteryCapacityWh = null)).arrival)
        assertNull("Explicit current charging suppresses local driving arrival", local(sample().copy(evChargeState = 1)).arrival)
    }

    @Test fun routeStillNeedsFreshPositiveDistanceAndTimeAndClearRetiresArrival() {
        val invalid = listOf<DriveCardRoute?>(null,
            route().copy(distanceMeters = null), route().copy(distanceMeters = 0), route().copy(distanceMeters = -1),
            route().copy(remainingSeconds = null), route().copy(remainingSeconds = 0), route().copy(remainingSeconds = -1),
            route().copy(receivedAtMs = NOW - 120_001), route().copy(receivedAtMs = NOW + 1))
        for (next in invalid) assertNull("Reject route $next", local(sample(), next).arrival)
        val model = model(sample())
        model.route(route().copy(receivedAtMs = NOW - 120_000))
        assertLocal(model.snapshot(NOW), 60)
        model.route(null)
        assertNull(model.snapshot(NOW).arrival)
        model.route(route().copy(key = "new-independent-qualified-route", distanceMeters = 20_000))
        assertLocal(model.snapshot(NOW), 66)
        model.clear()
        assertEquals(DriveCardState(), model.snapshot(NOW))
    }

    @Test fun readyMeasuredHistoryWinsRatherThanBeingReplacedByDashboardFallback() {
        val model = DriveCardModel()
        for (i in 0..80) {
            val at = NOW + i * 1000L
            model.update(sample(at).copy(speedKmh = 90f, evBatteryLevelWh = 50_000f - i * 5), at)
        }
        val at = NOW + 80_000
        model.forecast(null, carPlay = true)
        model.route(route(at))
        val state = model.snapshot(at)
        assertEquals(5.0, requireNotNull(state.recentKmPerKwh), 0.001)
        assertTrue(state.trace.isNotEmpty())
        // Final 49,600 Wh - (40,000 m / 5 km/kWh) = 41,600 Wh, /70,000 -> 59%.
        // Dashboard fallback would round to 60%; it MUST NOT win this control.
        assertLocal(state, 59)
    }

    @Test fun genuineAaForecastKeepsExactSourcedWhAndOldUsableOnlyDenominatorAndPriority() {
        val model = model(sample())
        model.forecast(forecast(), carPlay = false)
        val aa = requireNotNull(model.snapshot(NOW).arrival)
        assertEquals("Exact sourced 35,000 Wh / current usable 70,000 Wh; not local 60%", 50, aa.percent)
        assertFalse("High-quality genuine AA remains preferred", aa.approximate)
        model.forecast(forecast().copy(forecastQuality = VehicleEnergyForecast.QUALITY_LOW), carPlay = false)
        assertLocal(model.snapshot(NOW), 50)
        model.forecast(forecast(), carPlay = true)
        assertLocal(model.snapshot(NOW), 60)
        // Without range there is no local fallback. Invalid AA must not leak sourced SOC.
        val noRange = sample().copy(rangeKm = null)
        for (replacement in listOf<DriveCardRoute?>(null,
            route().copy(key = "new-destination"), route().copy(distanceMeters = 60_000))) {
            val retiring = model(noRange)
            retiring.forecast(forecast(), carPlay = false)
            assertEquals(DriveCardArrival(50, false), retiring.snapshot(NOW).arrival)
            retiring.route(replacement)
            assertNull("Route identity/removal/reroute must retire old AA provenance", retiring.snapshot(NOW).arrival)
        }
        for (invalid in listOf(
            forecast().copy(receivedAtElapsedMs = NOW - 120_001),
            forecast().copy(receivedAtElapsedMs = NOW + 1),
            forecast().copy(energyAtNextStop = EnergyAtDistance(1000, 35_000, 60)),
            forecast().copy(energyAtNextStop = EnergyAtDistance(40_000, -1, 1200)),
            forecast().copy(energyAtNextStop = EnergyAtDistance(40_000, 70_001, 1200)))) {
            val rejected = model(noRange)
            rejected.forecast(invalid, carPlay = false)
            assertNull("Existing AA forecast guard remains: $invalid", rejected.snapshot(NOW).arrival)
        }
        val reportedOnly = model(sample(nominalOnly = true).copy(rangeKm = null))
        reportedOnly.forecast(forecast(), carPlay = false)
        assertNull("Nominal is LOCAL approximate scale, never an AA denominator", reportedOnly.snapshot(NOW).arrival)
        val carPlay = model(noRange)
        carPlay.forecast(forecast(), carPlay = true)
        assertNull("AA data is never Apple-accepted arrival", carPlay.snapshot(NOW).arrival)
        // Capacity alone suffices under the OLD AA guard; do not broaden local pack/range
        // prerequisites into new prerequisites for an independently valid sourced forecast.
        val aaOnly = model(sample().copy(evBatteryLevelWh = null, speedKmh = null, rangeKm = null))
        aaOnly.forecast(forecast(), carPlay = false)
        assertEquals(DriveCardArrival(50, false), aaOnly.snapshot(NOW).arrival)
    }

    private fun forecast() = VehicleEnergyForecast(EnergyAtDistance(40_000, 35_000, 1200),
        forecastQuality = VehicleEnergyForecast.QUALITY_HIGH, receivedAtElapsedMs = NOW)
    private fun model(data: ControlMessage.VehicleData) = DriveCardModel().also {
        it.update(data, NOW); it.forecast(null, carPlay = true); it.route(route())
    }
    private fun local(data: ControlMessage.VehicleData, next: DriveCardRoute? = route()) = model(data).run {
        route(next); snapshot(NOW)
    }
    private fun withObservation(data: ControlMessage.VehicleData, key: String, observation: VehiclePropertyObservation) =
        data.copy(evObservationMetadata = data.evObservationMetadata + (key to observation))

    companion object {
        const val NOW = 170_000L
        const val BATTERY = "EV_BATTERY_LEVEL"
        const val SPEED = "PERF_VEHICLE_SPEED"
        const val RANGE = "RANGE_REMAINING"
        const val CAPACITY = "EV_CURRENT_BATTERY_CAPACITY"
        const val INFO = "INFO_EV_BATTERY_CAPACITY"
        fun route(at: Long = NOW) = DriveCardRoute("fixture-A", 40_000, 1200, at)
        fun sample(at: Long = NOW, nominalOnly: Boolean = false): ControlMessage.VehicleData {
            fun obs(id: Int) = VehiclePropertyObservation(at * 1_000_000, at, 0,
                source = "vhal", registrationGeneration = 7, propertyId = id,
                subscriptionActive = true, changeMode = 2)
            return ControlMessage.VehicleData(speedKmh = 0f, rangeKm = 250f, evBatteryLevelWh = 50_000f,
                evCurrentBatteryCapacityWh = if (nominalOnly) null else 70_000f,
                evBatteryCapacityWh = if (nominalOnly) 80_000f else null,
                evMotorPowerW = null, evChargeRateW = null, evChargeState = 3, chargePortConnected = false,
                vhalRegistrationGeneration = 7, evObservationMetadata = mapOf(
                    BATTERY to obs(0x11600309), SPEED to obs(0x11600207), RANGE to obs(0x11600308),
                    CAPACITY to obs(0x1160030d), INFO to obs(0x11600106),
                    "EV_CHARGE_STATE" to obs(0x11400f41), "EV_CHARGE_PORT_CONNECTED" to obs(0x1120030b)))
        }
        fun assertLocal(state: DriveCardState, percent: Int) {
            assertNotNull("Qualified local dashboard range/energy arrival must exist before history is ready", state.arrival)
            val arrival = requireNotNull(state.arrival)
            assertEquals(percent, arrival.percent)
            assertTrue("Local fallback is always approximate, not an accepted Apple/AA forecast", arrival.approximate)
        }
    }
}

/** Actual View route-association guards, not a second hand-built route gate.
 * Parent must first restore the pinned 589 View after its separate 588 negative gate. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class DriveCardArrivalFallbackView589Test {
    private val nav = ManeuverState(ManeuverType.STRAIGHT, 100, "100 m", "Fixture road", 100,
        destination = "fixture-A", destDistanceMeters = 41_000, timeToArrivalSeconds = 1300)

    @Test fun destinationReplacementNeedsNewDistanceAndTimeBeforeLocalFallbackCanReappear() = withFreshView { view, _ ->
        val active = requireNotNull(ClusterNavigationState.state.value)
        ClusterNavigationState.updateHost(active.copy(destination = "fixture-B")); idle()
        assertNull("New destination cannot borrow retired distance/time", state(view).arrival)
        ClusterNavigationState.updateHost(active.copy(destination = "fixture-B", roadName = "New road")); idle()
        assertNull("Road-only metadata cannot renew route association", state(view).arrival)
        ClusterNavigationState.updateHost(active.copy(destination = "fixture-B", destDistanceMeters = 39_000)); idle()
        assertNull("Distance alone cannot renew cached time", state(view).arrival)
        ClusterNavigationState.updateHost(active.copy(destination = "fixture-B", destDistanceMeters = 39_000, timeToArrivalSeconds = 1100)); idle()
        DriveCardArrivalFallback589Test.assertLocal(state(view), 60)
        assertNoHistory(view)
    }

    @Test fun conflatedTerminalBoundaryRetiresReceiptsUntilBothNewInputsArrive() = withFreshView { view, _ ->
        val active = requireNotNull(ClusterNavigationState.state.value)
        val epoch = ClusterNavigationState.terminalValidity.value
        ClusterNavigationState.clearHost(); ClusterNavigationState.updateHost(active)
        assertEquals(active, ClusterNavigationState.state.value)
        assertTrue(ClusterNavigationState.terminalValidity.value > epoch)
        idle()
        assertNull("Even conflated same-route restore cannot revive retired arrival", state(view).arrival)
        ClusterNavigationState.updateHost(active.copy(roadName = "Another road")); idle()
        assertNull(state(view).arrival)
        ClusterNavigationState.updateHost(active.copy(destDistanceMeters = 39_000)); idle()
        assertNull("Old remaining time is still retired", state(view).arrival)
        ClusterNavigationState.updateHost(active.copy(destDistanceMeters = 39_000, timeToArrivalSeconds = 1100)); idle()
        DriveCardArrivalFallback589Test.assertLocal(state(view), 60)
        ClusterNavigationState.clearHost(); idle()
        assertNull("Actual terminal route removal omits arrival", state(view).arrival)
        assertNoHistory(view)
        view.stop()
        assertEquals(DriveCardState(), state(view))
    }

    @Test fun roadOnlyUpdateCannotReageRouteWhenVehicleDataWasIndependentlyRenewed() = withFreshView { view, vehicles ->
        ShadowSystemClock.advanceBy(Duration.ofSeconds(121))
        vehicles.value = DriveCardArrivalFallback589Test.sample(SystemClock.elapsedRealtime())
        val active = requireNotNull(ClusterNavigationState.state.value)
        ClusterNavigationState.updateHost(active.copy(roadName = "Fresh road only")); idle()
        assertNull("Vehicle is fresh but route energy inputs expired", state(view).arrival)
        assertNoHistory(view)
    }

    private fun state(view: DriveCardView) = ReflectionHelpers.getField<DriveCardState>(view, "state")
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun assertNoHistory(view: DriveCardView) {
        assertNull(state(view).recentKmPerKwh)
        assertNull(state(view).powerKw)
        assertTrue(state(view).trace.isEmpty())
    }
    private fun withFreshView(test: (DriveCardView, MutableStateFlow<ControlMessage.VehicleData>) -> Unit) {
        ShadowSystemClock.advanceBy(Duration.ofSeconds(170))
        ClusterNavigationState.clearHost(); ClusterNavigationState.updateHost(nav)
        val vehicles = MutableStateFlow(DriveCardArrivalFallback589Test.sample(SystemClock.elapsedRealtime()))
        mockkObject(ProcessVehicleDataRuntime)
        val view = DriveCardView(RuntimeEnvironment.getApplication(),
            SecondaryHostGeometry(2000, 810, PixelRect(558, 70, 1694, 668)), true, { true }, {})
        try {
            every { ProcessVehicleDataRuntime.latestVehicleData() } returns vehicles
            every { ProcessVehicleDataRuntime.isActive() } returns true
            view.start(); idle()
            assertNull("Initial cached route replay is not a fresh distance/time receipt", state(view).arrival)
            ClusterNavigationState.updateHost(nav.copy(roadName = "Road only")); idle()
            assertNull(state(view).arrival)
            ClusterNavigationState.updateHost(nav.copy(destDistanceMeters = 40_000)); idle()
            assertNull("Fresh distance alone cannot qualify route", state(view).arrival)
            ClusterNavigationState.updateHost(nav.copy(destDistanceMeters = 40_000, timeToArrivalSeconds = 1200)); idle()
            DriveCardArrivalFallback589Test.assertLocal(state(view), 60)
            assertNoHistory(view)
            test(view, vehicles)
        } finally {
            view.stop(); ClusterNavigationState.clearHost(); unmockkObject(ProcessVehicleDataRuntime)
        }
    }
}
