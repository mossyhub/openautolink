package com.openautolink.app.input

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.openautolink.app.data.EvLearnedRateEstimator
import com.openautolink.app.transport.ControlMessage.VehicleData
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LearnChargeStateContinuityTest {
    // The real proxy uses method names, not an Android Car service dependency.
    interface Callback {
        fun onChangeEvent(value: Value)
    }

    class Value(private val id: Int, private val raw: Any?, private val ns: Long,
        private val status: Int, private val area: Int) {
        fun getPropertyId() = id
        fun getAreaId() = area
        fun getValue() = raw
        fun getTimestamp() = ns
        fun getStatus() = status
    }

    private class Fixture(private val test: TestScope) {
        var now = 30_000L
        val contributed = mutableListOf<VehicleData>()
        val projected = mutableListOf<VehicleData>()
        private val estimator = EvLearnedRateEstimator.createForTest(object : EvLearnedRateEstimator.Store {
            override suspend fun load() = "{}"
            override suspend fun save(value: String) = Unit
        }, test.backgroundScope)
        private val coordinator = ProcessVehicleDataCoordinator.withEvLearner(
            estimator, contributed::add, MutableStateFlow(true), test.backgroundScope, { now },
        )
        private val context = mockk<Context> {
            every { checkSelfPermission(any()) } returns PackageManager.PERMISSION_GRANTED
        }
        private val owner = ProcessVehicleDataOwner(true, { sink -> VehicleDataForwarderImpl(context, sink) }, coordinator)
        private val forwarder = owner.forwarder as VehicleDataForwarderImpl
        private val callback: Callback

        private fun field(name: String) = forwarder.javaClass.getDeclaredField(name).apply { isAccessible = true }
        private fun set(name: String, value: Any) = field(name).set(forwarder, value)

        init {
            every { SystemClock.elapsedRealtime() } answers { now }
            every { SystemClock.elapsedRealtimeNanos() } answers { now * 1_000_000L }
            set("scope", test.backgroundScope) // Hold/advance the actual trailing-flush coroutine.
            set("desiredActive", true)
            set("isActive", true)
            set("propertyManager", Any())
            set("carMake", "Test")
            set("carModel", "EV")
            set("carYear", "2024")
            set("fuelTypes", listOf(10))
            val proxy = forwarder.javaClass.getDeclaredMethod("createCallbackProxy", Class::class.java)
                .apply { isAccessible = true }
            callback = proxy.invoke(forwarder, Callback::class.java) as Callback
            set("callbackProxy", callback)
            @Suppress("UNCHECKED_CAST")
            (field("trackedPropertyIds").get(forwarder) as MutableSet<Int>)
                .addAll(listOf(GEAR, SPEED, PACK, RANGE, CAPACITY, IGNITION, CHARGE))
            @Suppress("UNCHECKED_CAST")
            (field("propertyChangeModes").get(forwarder) as MutableMap<Int, Int>)
                .putAll(mapOf(GEAR to 1, IGNITION to 1, CHARGE to 1, CAPACITY to 0))
            owner.attachSessionConsumer(projected::add)
            event(IGNITION, 4)
            event(CAPACITY, 60_000f)
            event(CHARGE, 3)
            event(SPEED, 0f)
            event(RANGE, 400_000f)
            event(PACK, 50_000f)
            event(GEAR, 4)
            now += 1_000
            event(SPEED, 0f)
            now += 50
            event(RANGE, 400_000f)
            now += 50
            event(PACK, 50_000f)
            now += 100
            event(GEAR, 8)
            assertNotNull(coordinator.learnQualificationStatus.value)
        }

        fun event(id: Int, raw: Any?, status: Int = 0, area: Int = 0) =
            callback.onChangeEvent(Value(id, raw, now * 1_000_000L, status, area))

        fun finish(burst: () -> Unit): Int? {
            val before = contributed.size
            now += 100
            burst()
            event(CHARGE, 3)
            test.runCurrent() // Start the queued trailing job; keep its deadline held.
            assertEquals("no early contribution or projection", before, contributed.size)
            assertEquals(before, projected.size)
            now += 400
            test.advanceTimeBy(400)
            test.runCurrent()
            assertEquals("one ordinary trailing batch", before + 1, contributed.size)
            assertEquals(before + 1, projected.size)
            assertSame(contributed.last(), projected.last())
            assertEquals(3, contributed.last().evChargeState)
            assertEquals(0, contributed.last().evObservationMetadata.getValue("EV_CHARGE_STATE").status)
            repeat(65) { i ->
                now += 1_000
                event(SPEED, 60f / 3.6f)
                event(PACK, 50_000f - (i + 1) * 2.5f)
            }
            now += 1_000
            event(SPEED, 0f)
            now += 100
            event(PACK, 49_837.5f)
            now += 100
            event(GEAR, 4)
            assertEquals(4, contributed.last().gearRaw)
            assertSame(contributed.last(), projected.last())
            return coordinator.learnQualificationStatus.value?.trainingWindows
        }
    }

    private fun TestScope.withForwarder(burst: Fixture.() -> Unit): Int? {
        mockkStatic(SystemClock::class)
        return try { Fixture(this).run { finish { burst() } } }
        finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun `unavailable then available before the flush cannot complete`() = runTest {
        assertEquals(0, withForwarder { event(CHARGE, null, status = 1) })
    }

    @Test fun `charging then not charging before the flush cannot complete`() = runTest {
        assertEquals(0, withForwarder { event(CHARGE, 1) })
    }

    @Test fun `wrong area then valid charge state before the flush cannot complete`() = runTest {
        assertEquals(0, withForwarder { event(CHARGE, 3, area = 1) })
    }

    @Test fun `uninterrupted charge state completes with the same ordinary flush cadence`() = runTest {
        assertEquals(1, withForwarder {})
    }

    private companion object {
        const val GEAR = 0x11400400
        const val SPEED = 0x11600207
        const val PACK = 0x11600309
        const val RANGE = 0x11600308
        const val CAPACITY = 0x11600106
        const val IGNITION = 0x11400409
        const val CHARGE = 0x11400F41
    }
}
