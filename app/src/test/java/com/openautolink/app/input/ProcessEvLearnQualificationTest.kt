package com.openautolink.app.input

import com.openautolink.app.data.EvLearnQualification
import com.openautolink.app.data.EvLearnedRateEstimator
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProcessEvLearnQualificationTest {
    private class Fixture(private val test: TestScope, persisted: String = "{}") {
        val estimator = EvLearnedRateEstimator.createForTest(object : EvLearnedRateEstimator.Store {
            override suspend fun load() = persisted
            override suspend fun save(value: String) = Unit
        }, test.backgroundScope)
        val enabled = MutableStateFlow(false)
        private var now = 30_000L
        var generation = 1L
        var model = "EV"
        var capacity = 60_000f
        val contributed = mutableListOf<ControlMessage.VehicleData>()
        val projected = mutableListOf<ControlMessage.VehicleData>()
        val coordinator = ProcessVehicleDataCoordinator.withEvLearner(
            estimator, contributed::add, enabled, test.backgroundScope, { now },
        )
        val status get() = coordinator.learnQualificationStatus.value

        fun batch(
            gear: Int,
            speed: Float,
            wh: Float,
            dt: Long = 1_000L,
            charge: Int = 3,
            stalePack: Boolean = false,
            cachedSpeed: Boolean = false,
            packStatus: Int = 0,
            speedLag: Long = 0,
            packLag: Long = 0,
            rangeLag: Long = 0,
            exactPackRead: Boolean = false,
        ) {
            now += dt
            if (!cachedSpeed) speedTime = now - speedLag
            fun obs(id: Int, at: Long = now, status: Int = 0) = VehiclePropertyObservation(
                at * 1_000_000L, now, status, registrationGeneration = generation,
                propertyId = id, subscriptionActive = true,
            )
            coordinator.onRawBatch(ControlMessage.VehicleData(
                speedKmh = speed, gearRaw = gear, ignitionState = 4, evChargeState = charge,
                evBatteryLevelWh = wh, evBatteryCapacityWh = capacity, rangeKm = wh / 125f,
                carMake = "Test", carModel = model, carYear = "2024", fuelTypes = listOf(10),
                vhalRegistrationGeneration = generation,
                evObservationMetadata = mapOf(
                    "PERF_VEHICLE_SPEED" to obs(0x11600207, speedTime),
                    "GEAR_SELECTION" to obs(0x11400400),
                    "IGNITION_STATE" to obs(0x11400409),
                    "EV_CHARGE_STATE" to obs(0x11400F41),
                    "EV_BATTERY_LEVEL" to obs(0x11600309, if (stalePack) now - 11_000 else now - packLag, packStatus)
                        .copy(statusReadElapsedMs = now.takeIf { exactPackRead }),
                    "INFO_EV_BATTERY_CAPACITY" to obs(0x11600106),
                    "RANGE_REMAINING" to obs(0x11600308, now - rangeLag),
                ),
            ))
            test.runCurrent()
        }
        private var speedTime = now

        fun start(packLag: Long = 0, rangeLag: Long = 0, exactPackRead: Boolean = false, speedLag: Long = 0) {
            batch(4, 0f, 50_000f)
            batch(8, 0f, 50_000f, packLag = packLag, rangeLag = rangeLag,
                exactPackRead = exactPackRead, speedLag = speedLag)
        }

        fun end(interrupt: Int? = null, gap: Long = 1_000L, stalePack: Boolean = false,
            repeatCached: Boolean = false, depletionPerStep: Float = 2.5f,
            speedLag: Long = 0, packLag: Long = 0, exactPackRead: Boolean = false) {
            // Slightly over ten km, leaving margin above the policy's aggregate distance floors.
            repeat(610) { i ->
                val pack = 50_000f - (i + 1) * depletionPerStep
                batch(8, 60f, pack,
                    dt = if (i == 300) gap else if (repeatCached && i > 0) 750 else 1_000,
                    charge = if (i == 300) interrupt ?: 3 else 3)
                if (repeatCached) batch(8, 60f, pack, dt = 250, cachedSpeed = true)
            }
            batch(4, 0f, 50_000f - 610 * depletionPerStep, stalePack = stalePack, speedLag = speedLag,
                packLag = packLag, exactPackRead = exactPackRead)
        }

        fun drive() { start(); end() }
    }

    @Test fun `the real process feed warms up and pairs with prior learner without a phone`() = runTest {
        val f = Fixture(this)
        assertNull(f.status)
        f.enabled.value = true
        repeat(3) { f.drive() }
        assertEquals(3, f.status?.trainingWindows)
        assertEquals(0, f.status?.pairs)
        repeat(5) { f.start(); f.end(depletionPerStep = 2.4f) }
        assertEquals(5, f.status?.pairs)
        assertEquals(f.status.toString(), EvLearnQualification.Decision.QUALIFIED_SHADOW, f.status?.decision)
        assertNull(f.coordinator.sessionConsumer())
    }

    @Test fun `an old pack event does not close a drive even after an exact reread`() = runTest {
        val completed = listOf(false, true).map { exact ->
            val f = Fixture(this)
            f.enabled.value = true
            f.start()
            f.end(speedLag = 200, packLag = if (exact) 11_000 else 8_000, exactPackRead = exact)
            f.status?.trainingWindows
        }
        assertEquals(listOf(0, 0), completed)
    }

    @Test fun `old pack and range events independently veto the opening`() = runTest {
        val completed = listOf(11_000L to 0L, 0L to 8_000L).map { (packLag, rangeLag) ->
            val f = Fixture(this)
            f.enabled.value = true
            f.start(packLag, rangeLag, exactPackRead = true, speedLag = 200)
            f.end()
            f.status?.trainingWindows
        }
        assertEquals(listOf(0, 0), completed)
    }

    @Test fun `separately timed stopped speed pack range and gear complete and score`() = runTest {
        val f = Fixture(this)
        f.enabled.value = true
        repeat(4) { i ->
            f.start(speedLag = 200, packLag = 100, rangeLag = 150)
            f.end(speedLag = 200, packLag = 100, depletionPerStep = if (i < 3) 2.5f else 2.4f)
        }
        assertEquals(4, f.status?.trainingWindows)
        assertEquals(1, f.status?.pairs)
        assertNotNull(f.status?.learnedMaeWh)
    }

    @Test fun `source replacement reset and Off retire evidence and open windows`() = runTest {
        val f = Fixture(this)
        f.enabled.value = true
        f.drive()
        assertEquals(1, f.status?.trainingWindows)
        f.start()
        f.generation++
        f.end()
        assertEquals(0, f.status?.trainingWindows)
        f.drive()
        assertEquals(1, f.status?.trainingWindows)
        f.start()
        f.estimator.reset()
        runCurrent()
        assertNull(f.status)
        f.end()
        assertEquals(0, f.status?.trainingWindows)
        f.drive()
        f.enabled.value = false
        runCurrent()
        assertNull(f.status)
        f.enabled.value = true
        f.drive()
        assertEquals(1, f.status?.trainingWindows)
    }

    @Test fun `explicit observation revocation clears evidence even if scalar was retained`() = runTest {
        val f = Fixture(this)
        f.drive()
        assertNull(f.status) // Off never starts a qualification owner.
        f.enabled.value = true
        f.drive()
        assertEquals(1, f.status?.trainingWindows)
        f.start()
        f.batch(8, 24f, 49_999f, packStatus = 1)
        assertNull(f.status)
        f.end()
        assertEquals(0, f.status?.trainingWindows)
    }

    @Test fun `raw stream rejects interrupted stale and incomplete drives and keeps projection bytes unchanged`() = runTest {
        val f = Fixture(this)
        f.enabled.value = true
        f.start(); f.end(interrupt = 1)
        f.start(); f.end(gap = 16_000)
        f.start(); f.end(stalePack = true)
        f.start(); f.model = "replacement"; f.end()
        f.start(); f.capacity += 1f; f.end()
        assertEquals(0, f.status?.trainingWindows)
        f.coordinator.attachSessionConsumer(f.projected::add)
        f.start(); f.end(repeatCached = true)
        assertEquals(1, f.status?.trainingWindows)
        assertSame(f.contributed.last(), f.projected.last())
        assertSame(f.projected.last(), f.coordinator.latestVehicleData.value)
    }

    @Test fun `persisted band-keyed learner rate without lineage cannot pair`() = runTest {
        val persisted = """{"Test|EV|2024|cap60000-65000|rev2|absolute-wh":{"whPerKm":150,"sampleKm":100,"lastUpdateMs":1}}"""
        val f = Fixture(this, persisted)
        f.enabled.value = true
        repeat(5) { f.drive() }
        assertEquals(5, f.status?.trainingWindows)
        assertEquals(0, f.status?.pairs)
        assertEquals(EvLearnQualification.Reason.NO_PRIOR_CANDIDATE, f.status?.reason)
    }
}
