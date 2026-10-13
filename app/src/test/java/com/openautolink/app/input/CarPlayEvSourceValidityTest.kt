package com.openautolink.app.input

import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.openautolink.app.carplay.OalCarPlayVehicleSnapshotAdapter
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.*
import org.junit.Test

/** Exercises reflection registration/read observations and the actual production CarPlay adapter. */
class CarPlayEvSourceValidityTest {
    class Config(private val mode: Int) { fun getChangeMode() = mode }
    class Value(private val id: Int, private val value: Float?, private val time: Long, private val status: Int = 0) {
        fun getPropertyId() = id
        fun getValue() = value
        fun getTimestamp() = time
        fun getStatus() = status
    }
    class Manager {
        var accept = true
        var config: Any? = Config(1) // HAL ONCHANGE, not the requested SENSOR_RATE_ONCHANGE
        var readFailure = false
        var readStatus = 0
        var duringRead: ((Int) -> Unit)? = null
        val reads = java.util.concurrent.CopyOnWriteArrayList<Int>()
        var duringSubscribe: ((Int, CarPropertyEventCallback) -> Unit)? = null
        lateinit var callback: CarPropertyEventCallback
        fun getCarPropertyConfig(id: Int): Any? = if (id == RANGE || id == BATTERY) config else null
        fun getProperty(id: Int, area: Int): Value {
            reads.add(id)
            duringRead?.invoke(id)
            if (readFailure) error("read unavailable")
            return Value(id, if (id == RANGE) 200000f else 50000f,
                if (id == RANGE) 28_705_000_000 else 34_800_000_000, readStatus)
        }
        fun subscribePropertyEvents(id: Int, rate: Float, cb: CarPropertyEventCallback): Boolean {
            callback = cb
            duringSubscribe?.invoke(id, cb)
            return accept
        }
    }
    private class Fixture {
        @Volatile var now = 60000L
        @Volatile var energyPermission = true
        val manager = Manager()
        val context = mockk<Context>(relaxed = true)
        val forwarder: VehicleDataForwarderImpl
        val adapter: OalCarPlayVehicleSnapshotAdapter
        init {
            every { context.checkSelfPermission(any()) } answers {
                if (firstArg<String>() == "android.car.permission.CAR_ENERGY" && !energyPermission) PackageManager.PERMISSION_DENIED
                else PackageManager.PERMISSION_GRANTED
            }
            every { SystemClock.elapsedRealtime() } answers { now }
            forwarder = VehicleDataForwarderImpl(context, {})
            set("desiredActive", true); set("isActive", true); set("propertyManager", manager)
            set("fuelTypes", listOf(10))
            adapter = OalCarPlayVehicleSnapshotAdapter({ forwarder.latestVehicleData }, { now })
        }
        fun set(name: String, value: Any) = forwarder.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(forwarder, value)
        fun register() = forwarder.javaClass.getDeclaredMethod("registerProperties", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(forwarder, 0L)
        fun publish() = forwarder.javaClass.getDeclaredMethod("publishImmediate").apply { isAccessible = true }.invoke(forwarder)
    }

    @Test fun upload519OnChangeRangeSurvives31295msSourceAgeAndSlowBatteryRemainsValid() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register()
            // 519: AVAILABLE range age 31295ms, current age 25200ms, active matching epoch.
            assertEquals(200, f.adapter.snapshot()!!.rangeKm)
            assertEquals(50000L, f.adapter.snapshot()!!.currentChargeWh)
            assertNull("absent usable capacity must remain absent", f.adapter.snapshot()!!.maxChargeWh)
            val original = f.forwarder.latestVehicleData.value.evObservationMetadata
            f.now = 90000 // no on-change events while the value stays unchanged; receipt still within 30s
            assertEquals(200, f.adapter.snapshot()!!.rangeKm)
            assertEquals(50000L, f.adapter.snapshot()!!.currentChargeWh)
            assertEquals("reading must not fabricate a heartbeat", original, f.forwarder.latestVehicleData.value.evObservationMetadata)
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun unchangedOnChangeReadRevalidatesStatusWithoutRejuvenatingEventTimestamp() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register()
            val before = f.forwarder.latestVehicleData.value.evObservationMetadata.getValue("RANGE_REMAINING")
            f.now = 85000
            f.register() // same live registration, fresh real getProperty result
            val after = f.forwarder.latestVehicleData.value.evObservationMetadata.getValue("RANGE_REMAINING")
            assertEquals(85000L, after.receivedElapsedMs)
            assertEquals(before.timestampElapsedNanos, after.timestampElapsedNanos)
            assertEquals(before.registrationGeneration, after.registrationGeneration)
            f.now = 90001
            assertEquals(200, f.adapter.snapshot()!!.rangeKm)
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun unavailableOnChangeCallbackImmediatelyRevokesRetainedCarPlayRange() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register()
            assertEquals(200, f.adapter.snapshot()!!.rangeKm)
            f.now = 60100
            f.manager.callback.onChangeEvent(Value(RANGE, null, 60_100_000_000, 1))
            assertNull("status failure must be published even without a numeric value", f.adapter.snapshot()!!.rangeKm)
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun processPollerRevalidatesUnchangedEvEvenWhenHistoryProviderIsUnavailable() {
        mockkStatic(SystemClock::class)
        val f = Fixture()
        try {
            f.set("carMake", "GM"); f.set("carModel", "EV"); f.set("carYear", "2024")
            f.set("evConnectorTypes", listOf(1))
            f.register()
            f.now = 65000
            f.forwarder.javaClass.getDeclaredMethod("startHistoryPoller").apply { isAccessible = true }.invoke(f.forwarder)
            val deadline = System.nanoTime() + 8_000_000_000L
            while (f.forwarder.latestVehicleData.value.evObservationMetadata["RANGE_REMAINING"]?.receivedElapsedMs != 65000L && System.nanoTime() < deadline) Thread.sleep(10)
            assertEquals("independent status poll must run without HistoryProvider", 65000L,
                f.forwarder.latestVehicleData.value.evObservationMetadata.getValue("RANGE_REMAINING").receivedElapsedMs)
        } finally {
            f.forwarder.javaClass.getDeclaredMethod("cleanup").apply { isAccessible = true }.invoke(f.forwarder)
            unmockkStatic(SystemClock::class)
        }
    }

    @Test fun continuousAndUnknownConfigsDoNotGetAnOnChangeAgeExemption() {
        mockkStatic(SystemClock::class)
        try {
            for (config in listOf(Config(2), Config(99), Any())) {
                val f = Fixture(); f.manager.config = config; f.register()
                assertNull(f.adapter.snapshot()!!.rangeKm)
                val reads = f.manager.reads.size
                f.now = 90001; f.register()
                assertNull(f.adapter.snapshot())
                assertEquals("do not poll continuous or unknown modes as on-change", reads, f.manager.reads.size)
            }
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun staticConfigUsesConfirmedValueButStillExpiresWithoutRealReads() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.manager.config = Config(0); f.register()
            assertEquals(200, f.adapter.snapshot()!!.rangeKm)
            f.now = 90001
            assertNull("registration alone is not indefinite liveness", f.adapter.snapshot())
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun unsupportedConfigAndRejectedSubscriptionCannotQualifyEvenWithSynchronousCallback() {
        mockkStatic(SystemClock::class)
        try {
            val absent = Fixture(); absent.manager.config = null; absent.register()
            assertNull(absent.adapter.snapshot())
            val rejected = Fixture(); rejected.manager.accept = false
            rejected.manager.duringSubscribe = { id, cb -> cb.onChangeEvent(Value(id, 100f, 59_000_000_000)) }
            rejected.register()
            assertNull(rejected.adapter.snapshot())
            assertTrue(rejected.forwarder.latestVehicleData.value.evObservationMetadata.isEmpty())
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun actualReadFailureRevokesOnChangeValuesWithoutWaitingForACallback() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register(); f.now = 65000; f.manager.readFailure = true; f.register()
            assertNull(f.adapter.snapshot())
            assertEquals(1, f.forwarder.latestVehicleData.value.evObservationMetadata.getValue("RANGE_REMAINING").status)
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun silentUnavailableStatusReadRevokesOnChangeValues() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register(); f.now = 65000; f.manager.readStatus = 1; f.register()
            assertNull(f.adapter.snapshot())
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun newerCallbackDuringReadCannotBeOverwrittenByOlderReadResult() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register(); f.now = 65000
            f.manager.duringRead = { id -> if (id == RANGE) f.manager.callback.onChangeEvent(Value(id, 300000f, 65_000_000_000)) }
            f.register()
            assertEquals(300, f.adapter.snapshot()!!.rangeKm)
            assertEquals(65_000_000_000L, f.forwarder.latestVehicleData.value.evObservationMetadata.getValue("RANGE_REMAINING").timestampElapsedNanos)
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun errorDuringReadCannotBeResurrectedByOlderAvailableRead() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register(); f.now = 65000
            f.manager.duringRead = { id -> if (id == RANGE) f.manager.callback.onErrorEvent(id, 0) }
            f.register()
            assertNull(f.adapter.snapshot()!!.rangeKm)
            assertEquals(1, f.forwarder.latestVehicleData.value.evObservationMetadata.getValue("RANGE_REMAINING").status)
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun retiredGenerationCannotBeRestoredByBlockedReadOrLateCallback() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register(); f.now = 65000
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            f.manager.duringRead = { id -> if (id == RANGE) { entered.countDown(); check(release.await(3, java.util.concurrent.TimeUnit.SECONDS)) } }
            f.set("startInFlight", true) // same lane reservation as production launchRefresh
            val worker = Thread { try { f.register() } catch (t: Throwable) { failure.set(t) } }.apply { start() }
            try {
                assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
                f.forwarder.stop()
                assertNull(f.adapter.snapshot())
            } finally { release.countDown(); worker.join(3000) }
            assertFalse(worker.isAlive); failure.get()?.let { throw it }
            f.manager.callback.onChangeEvent(Value(RANGE, 400000f, 65_000_000_000))
            assertNull(f.adapter.snapshot())
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun permissionRevokedDuringReadCannotPublishReadAuthority() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register(); f.now = 65000
            f.manager.duringRead = { f.energyPermission = false }
            f.register()
            assertNull(f.adapter.snapshot())
            assertFalse(f.forwarder.latestVehicleData.value.evObservationMetadata.containsKey("RANGE_REMAINING"))
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun statusReadsAreThrottledAndDoNotReplaceLiveSubscriptions() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register()
            val before = f.forwarder.latestVehicleData.value.evObservationMetadata
            val callback = f.manager.callback
            repeat(10) { f.now++; f.register() }
            assertEquals(2, f.manager.reads.size)
            assertEquals(before, f.forwarder.latestVehicleData.value.evObservationMetadata)
            f.now = 65000; f.register()
            assertEquals(4, f.manager.reads.size)
            assertSame(callback, f.manager.callback)
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun futureAndMissingSourceTimesRemainInvalidForOnChange() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register()
            f.manager.callback.onChangeEvent(Value(RANGE, 200000f, 61_000_000_000))
            assertNull(f.adapter.snapshot()!!.rangeKm)
            f.manager.callback.onChangeEvent(Value(RANGE, 200000f, 0))
            assertNull(f.adapter.snapshot()!!.rangeKm)
        } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun knownReadStatusFailureIsPublishedBeforeAnotherPropertyReadCanBlock() {
        mockkStatic(SystemClock::class)
        try {
            val f = Fixture(); f.register(); f.now = 65000; f.manager.readStatus = 1
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            f.manager.duringRead = { id -> if (id == BATTERY) { entered.countDown(); check(release.await(3, java.util.concurrent.TimeUnit.SECONDS)) } }
            val worker = Thread { try { f.register() } catch (t: Throwable) { failure.set(t) } }.apply { start() }
            try {
                assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS))
                assertNull("known range status failure cannot wait behind battery Binder read", f.adapter.snapshot()!!.rangeKm)
            } finally { release.countDown(); worker.join(3000) }
            assertFalse(worker.isAlive); failure.get()?.let { throw it }
        } finally { unmockkStatic(SystemClock::class) }
    }

    companion object {
        private const val RANGE = 0x11600308
        private const val BATTERY = 0x11600309
    }
}
