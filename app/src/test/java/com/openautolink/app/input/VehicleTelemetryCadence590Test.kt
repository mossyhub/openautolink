package com.openautolink.app.input

import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback as Callback
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.openautolink.app.carplay.CarPlayDrivingRestrictionBridge
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Source-only packet. Constructed existing Binder/reflection boundaries, not measured vehicle latency. */
class VehicleTelemetryCadence590Test {
    class Config(private val mode: Int, private val min: Float, private val max: Float) {
        fun getChangeMode() = mode
        fun getMinSampleRate() = min
        fun getMaxSampleRate() = max
    }
    class ModeOnly(private val mode: Int) { fun getChangeMode() = mode }
    data class Subscription(val api: String, val id: Int, val rate: Float, val callback: Callback)

    // Reuse the established read/value fixture, extending ONLY its OS config/registration boundary.
    open class Boundary {
        val reads = VehiclePermissionRefreshTest.Manager()
        val configs = mutableMapOf<Int, Any>(
            POWER to Config(2, 1f, 20f), BATTERY to Config(2, 1f, 20f),
            RANGE to Config(2, 1f, 20f), SPEED to Config(2, 1f, 100f), GEAR to ModeOnly(1),
        )
        val calls = CopyOnWriteArrayList<Subscription>()
        val readIds = CopyOnWriteArrayList<Int>()
        var accept = true
        var duringSubscribe: ((Int, Callback) -> Unit)? = null
        var duringRead: ((Int) -> Unit)? = null
        fun getCarPropertyConfig(id: Int): Any? = configs[id]
        fun getProperty(id: Int, area: Int): VehiclePermissionRefreshTest.Value {
            readIds.add(id)
            duringRead?.invoke(id)
            return when (id) {
                POWER -> VehiclePermissionRefreshTest.Value(id, -1_000_000f, 1_000_000_000L)
                SPEED -> VehiclePermissionRefreshTest.Value(id, 10f, 1_000_000_000L)
                // STATIC Park needs genuinely current HAL time; only ONCHANGE
                // can qualify an old event through a completed exact read.
                GEAR -> if ((configs[id] as? Config)?.getChangeMode() == 0)
                    VehiclePermissionRefreshTest.Value(id, 4, 60_000_000_000L)
                    else reads.getProperty(id, area)
                else -> reads.getProperty(id, area)
            }
        }
        fun record(api: String, id: Int, rate: Float, callback: Callback): Boolean {
            calls.add(Subscription(api, id, rate, callback))
            duringSubscribe?.invoke(id, callback)
            return accept
        }
        fun unsubscribePropertyEvents(callback: Callback) = Unit
        fun unregisterCallback(callback: Callback) = Unit
    }
    class SubscribeBoundary : Boundary() {
        fun subscribePropertyEvents(id: Int, rate: Float, cb: Callback) = record("subscribe", id, rate, cb)
    }
    class RegisterBoundary : Boundary() {
        fun registerCallback(cb: Callback, id: Int, rate: Float) = record("register", id, rate, cb)
    }

    private class Fixture(val manager: Boundary = SubscribeBoundary()) : AutoCloseable {
        @Volatile var energy = true
        private val clockMonitor = Any()
        private var elapsedBase = 60_000L
        private var nanoBase = System.nanoTime()
        var elapsed: Long
            get() = synchronized(clockMonitor) { elapsedBase + (System.nanoTime() - nanoBase) / 1_000_000L }
            set(value) = synchronized(clockMonitor) { elapsedBase = value; nanoBase = System.nanoTime() }
        val sent = CopyOnWriteArrayList<ControlMessage.VehicleData>()
        val wake = java.util.concurrent.atomic.AtomicReference<CountDownLatch?>()
        val context = mockk<Context>(relaxed = true)
        val f: VehicleDataForwarderImpl
        init {
            every { context.checkSelfPermission(any()) } answers {
                if (firstArg<String>() == "android.car.permission.CAR_ENERGY" && !energy)
                    PackageManager.PERMISSION_DENIED else PackageManager.PERMISSION_GRANTED
            }
            every { SystemClock.elapsedRealtime() } answers { elapsed }
            every { SystemClock.elapsedRealtimeNanos() } answers { elapsed * 1_000_000L }
            f = VehicleDataForwarderImpl(context, { sent.add(it); wake.get()?.countDown() })
            set("desiredActive", true); set("isActive", true); set("propertyManager", manager)
            set("fuelTypes", listOf(10))
        }
        fun field(name: String) = f.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun set(name: String, value: Any) = field(name).set(f, value)
        fun method(name: String, vararg args: Class<*>) = f.javaClass.getDeclaredMethod(name, *args).apply { isAccessible = true }
        fun register() = method("registerProperties", Long::class.javaPrimitiveType!!).invoke(f, 0L)
        fun callback(): Callback = field("callbackProxy").get(f) as Callback
        fun event(id: Int, value: Any?, stamp: Long, status: Int = 0) =
            callback().onChangeEvent(VehiclePermissionRefreshTest.Value(id, value, stamp, status))
        @Suppress("UNCHECKED_CAST")
        fun cached(name: String): VehiclePropertyObservation = synchronized(f) {
            (field("evObservationMetadata").get(f) as Map<String, VehiclePropertyObservation>).getValue(name)
        }
        fun snapshot(): ControlMessage.VehicleData = synchronized(f) { method("buildVehicleData").invoke(f) as ControlMessage.VehicleData }
        fun activeChildren(): Int = (field("scope").get(f) as CoroutineScope).coroutineContext[Job]!!.children.count()
        fun quietFor(ms: Long, count: Int) {
            val signal = CountDownLatch(1); wake.set(signal)
            try {
                assertFalse("unexpected publication during quiet observation", signal.await(ms, TimeUnit.MILLISECONDS))
                assertEquals(count, sent.size)
            } finally { wake.compareAndSet(signal, null) }
        }
        fun awaitPower(stamp: Long): ControlMessage.VehicleData {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline) {
                sent.firstOrNull { it.evObservationMetadata[POWER_NAME]?.timestampElapsedNanos == stamp }?.let { return it }
                val signal = CountDownLatch(1); wake.set(signal)
                try {
                    if (sent.none { it.evObservationMetadata[POWER_NAME]?.timestampElapsedNanos == stamp })
                        signal.await(20, TimeUnit.MILLISECONDS)
                } finally { wake.compareAndSet(signal, null) }
            }
            fail("quiet-tail latest power must publish without another external callback")
            error("unreachable")
        }
        // Actual immediate energy observation sets both the wall-clock throttle and raw publication.
        // No reflected lastSendTime manipulation, no global clock/fence reset.
        fun armPower(stamp: Long = B): VehiclePropertyObservation {
            event(BATTERY, 50_000f, 60_000_000_000L)
            val count = sent.size
            val wall = System.currentTimeMillis()
            elapsed += 100L
            event(POWER, -2_000_000f, stamp)
            check(System.currentTimeMillis() - wall < 400L) { "fixture missed the real 500ms suppression window" }
            assertEquals("B is initially suppressed", count, sent.size)
            return cached(POWER_NAME)
        }
        override fun close() {
            // Exact instance retirement; never reset any process-wide admission/fence state.
            set("startInFlight", true)
            f.stop()
            method("cleanup").invoke(f)
            val job = (field("scope").get(f) as CoroutineScope).coroutineContext[Job]!!
            runBlocking { kotlinx.coroutines.withTimeout(3_000) { job.cancelAndJoin() } }
        }
    }

    private fun fixture(manager: Boundary = SubscribeBoundary(), body: (Fixture) -> Unit) {
        mockkStatic(SystemClock::class)
        try { Fixture(manager).use(body) } finally { unmockkStatic(SystemClock::class) }
    }

    @Test fun continuousEvRequestsUiFiveHzClampedToFiniteConfigOnBothActualRegistrationApis() {
        // Finite min is a platform lower clamp, NOT an instruction to disable callbacks at zero.
        val cases = listOf(
            Config(2, 1f, 20f) to 5f, Config(2, 0.1f, 2f) to 2f,
            Config(2, 7f, 20f) to 7f, Config(2, 0f, 20f) to 5f,
            ModeOnly(2) to 5f,
        )
        for (legacy in listOf(false, true)) for ((config, expected) in cases) {
            val manager = if (legacy) RegisterBoundary() else SubscribeBoundary()
            for (id in EV_IDS) manager.configs[id] = config
            fixture(manager) { x ->
                x.register()
                for (id in EV_IDS) {
                    val call = manager.calls.single { it.id == id }
                    assertEquals(if (legacy) "register" else "subscribe", call.api)
                    assertEquals("authorized continuous EV id=$id must not request ONCHANGE/0", expected, call.rate, 0f)
                    assertTrue(call.rate.isFinite() && call.rate > 0f)
                }
                assertEquals(10f, manager.calls.single { it.id == SPEED }.rate, 0f)
                assertEquals(0f, manager.calls.single { it.id == GEAR }.rate, 0f)
                assertEquals(5, manager.calls.size)
                assertEquals(1, manager.calls.map { System.identityHashCode(it.callback) }.distinct().size)
                val calls = manager.calls.toList(); x.register()
                assertEquals("refresh must reuse registrations and the sole callback", calls, manager.calls.toList())
            }
        }
    }

    @Test fun staticOnchangeUnknownPermissionRejectionAndParkPoliciesRemainUnchanged() {
        for (mode in listOf(0, 1, 2, 99)) fixture { x ->
            // Continuous gear never acquires the EV cadence/read-authority exception.
            for (id in EV_IDS) x.manager.configs[id] = Config(if (mode == 2) 1 else mode, 1f, 20f)
            x.manager.configs[GEAR] = Config(mode, 1f, 20f)
            x.register()
            for (id in EV_IDS + GEAR) assertEquals(0f, x.manager.calls.single { it.id == id }.rate, 0f)
            assertEquals(10f, x.manager.calls.single { it.id == SPEED }.rate, 0f)
            x.elapsed = 66_000; x.register()
            val policy = CarPlayDrivingRestrictionBridge(x.f.latestVehicleData, Dispatchers.IO, { x.elapsed }, 15_000)
            try {
                if (mode in 0..1) assertEquals("actual_park", policy.restrictionReason(x.f.latestVehicleData.value))
                else assertNotEquals("actual_park", policy.restrictionReason(x.f.latestVehicleData.value))
                if (mode == 0) {
                    x.elapsed = 80_001; x.register()
                    assertEquals(60_000_000_000L, x.f.latestVehicleData.value
                        .evObservationMetadata.getValue("GEAR_SELECTION").timestampElapsedNanos)
                    assertEquals("old STATIC cannot borrow ONCHANGE read authority", "stale",
                        policy.restrictionReason(x.f.latestVehicleData.value))
                }
            } finally { runBlocking { policy.close() } }
        }
        fixture { x ->
            for (id in EV_IDS) x.manager.configs[id] = Any() // getter absent: unknown, not CONTINUOUS
            x.register()
            for (id in EV_IDS) assertEquals(0f, x.manager.calls.single { it.id == id }.rate, 0f)
        }
        fixture { x ->
            x.energy = false; x.register()
            assertTrue(x.manager.calls.none { it.id in EV_IDS })
            x.energy = true; x.manager.accept = false
            x.manager.duringSubscribe = { id, cb -> cb.onChangeEvent(VehiclePermissionRefreshTest.Value(id, 999f, B)) }
            x.register()
            assertTrue(x.f.latestVehicleData.value.evObservationMetadata.keys.none { it in EV_NAMES })
            assertNull(x.f.latestVehicleData.value.evChargeRateW)
        }
    }

    @Test fun quietTailPublishesLatestPowerOnceWithExactlyItsOriginalMetadata() = fixture { x ->
        x.register()
        val original = x.armPower()
        val before = x.sent.size
        val published = x.awaitPower(B) // deliberately NO external callback/read after B
        assertEquals(-2_000f, published.evChargeRateW)
        assertEquals(original, published.evObservationMetadata.getValue(POWER_NAME))
        assertTrue(original.receivedElapsedMs >= 60_100L)
        assertEquals(B, original.timestampElapsedNanos)
        assertNull(original.statusReadElapsedMs)
        assertEquals(before + 1, x.sent.size)
        x.quietFor(650, before + 1)
    }

    @Test fun burstAndImmediateEnergyRangeAndExactReadDrainOneLatestCacheWithoutDuplicates() {
        for (drain in listOf("quiet", "speed", "energy", "range", "exact-read")) fixture { x ->
            x.register(); x.armPower()
            val count = x.sent.size
            val wall = System.currentTimeMillis()
            repeat(32) { n -> x.event(POWER, -3_000_000f - n * 1000f, B + n + 1) }
            check(System.currentTimeMillis() - wall < 400L) { "burst fixture escaped suppression window" }
            assertEquals(count, x.sent.size)
            assertTrue("one forwarder may have at most one pending flush Job, not one per callback", x.activeChildren() <= 1)
            val original = x.cached(POWER_NAME)
            val latestStamp = B + 32
            when (drain) {
                "speed" -> x.event(SPEED, 12f, B + 100)
                "energy" -> x.event(BATTERY, 49_999f, B + 100)
                "range" -> x.event(RANGE, 199_000f, B + 100)
                "exact-read" -> {
                    // Range/battery can be due while the newer power receipt is not.
                    val readAt = original.receivedElapsedMs + 4_950L
                    x.elapsed = readAt; x.register()
                    assertTrue(x.cached("RANGE_REMAINING").statusReadElapsedMs?.let {
                        it in readAt..x.elapsed
                    } == true)
                }
            }
            val published = x.awaitPower(latestStamp)
            assertEquals(-3_031f, published.evChargeRateW)
            assertEquals(original, published.evObservationMetadata.getValue(POWER_NAME))
            val after = x.sent.size
            x.quietFor(650, after)
            if (drain == "quiet") assertEquals("burst coalesces to exactly one latest batch", count + 1, after)
            // Real later speed/energy/read publications may legitimately include identical cached power.
            assertTrue(x.sent.none { batch -> batch.evObservationMetadata[POWER_NAME]?.timestampElapsedNanos?.let {
                it in (B + 1) until latestStamp
            } == true })
            assertTrue(x.activeChildren() <= 1)
        }
    }

    @Test fun permissionLossAndExactOwnerRetirementCancelPendingWithoutAffectingSuccessor() {
        for (boundary in listOf("permission", "permission-no-refresh", "stop", "replacement")) fixture { x ->
            x.register(); val oldCallback = x.callback(); val old = x.armPower()
            when (boundary) {
                "permission" -> { x.energy = false; x.register() }
                "permission-no-refresh" -> { x.energy = false }
                "stop" -> { x.set("startInFlight", true); x.f.stop() }
                else -> {
                    // Real exact instance cleanup increments registrationGeneration and retires old proxy.
                    x.method("cleanup").invoke(x.f)
                    x.set("desiredActive", true); x.set("isActive", true); x.set("propertyManager", x.manager)
                    x.register()
                    assertNotSame(oldCallback, x.callback())
                }
            }
            val afterBoundary = x.sent.size
            oldCallback.onChangeEvent(VehiclePermissionRefreshTest.Value(POWER, -9_000_000f, B + 900))
            x.quietFor(700, afterBoundary)
            assertTrue("old pending B must never publish after authority loss", x.sent.drop(afterBoundary).none {
                it.evObservationMetadata[POWER_NAME] == old
            })
            if (boundary != "stop") {
                x.energy = true; x.elapsed = 66_000; x.register()
                val successor = x.armPower(B + 1000)
                val delivered = x.awaitPower(B + 1000)
                assertEquals(successor, delivered.evObservationMetadata.getValue(POWER_NAME))
                if (boundary == "replacement") assertNotEquals(old.registrationGeneration, successor.registrationGeneration)
                val count = x.sent.size; x.quietFor(650, count)
            }
        }
    }

    @Test fun errorReadSequenceAndOldTimestampCadenceNeverFabricateOrStarveReadAuthority() {
        fixture { x ->
        x.register(); val original = x.armPower()
        x.callback().onErrorEvent(POWER, 0)
        val error = x.f.latestVehicleData.value.evObservationMetadata.getValue(POWER_NAME)
        assertEquals(1, error.status); assertTrue(error.sequence > original.sequence)
        assertNull(x.f.latestVehicleData.value.evChargeRateW)
        val count = x.sent.size; x.quietFor(650, count)
        // Actual serialized read races a newer callback; the read must not replace its sequence.
        x.elapsed = 66_000
        x.manager.duringRead = { id -> if (id == POWER) {
            x.manager.duringRead = null
            x.event(POWER, -4_000_000f, B + 2000)
        } }
        x.register()
        val winner = x.cached(POWER_NAME)
        assertEquals(B + 2000, winner.timestampElapsedNanos)
        assertNull(winner.statusReadElapsedMs)
        assertEquals(-4_000f, x.snapshot().evChargeRateW)
        val published = x.awaitPower(B + 2000)
        assertEquals(winner, published.evObservationMetadata.getValue(POWER_NAME))
        val after = x.sent.size; x.quietFor(650, after)
        }
        fixture { x ->
            x.register()
            val initialReads = x.manager.readIds.count { it == POWER }
            val exactObservations = mutableListOf<VehiclePropertyObservation>()
            // Constructed 5Hz callbacks retain the same old HAL timestamp. Receipt freshness
            // is NOT authority, and may not defer the exact-read backup indefinitely.
            val cadenceStart = x.elapsed
            repeat(31) { n ->
                x.elapsed = cadenceStart + n * 200L
                x.event(POWER, -1_000_000f, 1_000_000_000L)
                val callbackOnly = x.cached(POWER_NAME)
                assertEquals(1_000_000_000L, callbackOnly.timestampElapsedNanos)
                assertNull(callbackOnly.statusReadElapsedMs)
                assertFalse(VehicleTelemetryFreshness.sourceTimeConfirmed(
                    callbackOnly, POWER_NAME, x.elapsed, 15_000L))
                x.register() // existing serialized real exact-read path; no new poller/subscriber
                x.cached(POWER_NAME).takeIf { it.statusReadElapsedMs != null }?.let(exactObservations::add)
            }
            assertTrue("unchanged old HAL callbacks must not starve the existing real exact-read backup",
                x.manager.readIds.count { it == POWER } > initialReads)
            assertTrue("authority must come from an actual completed read", exactObservations.isNotEmpty())
            for (confirmed in exactObservations) {
                assertEquals(1_000_000_000L, confirmed.timestampElapsedNanos)
                assertEquals(confirmed.receivedElapsedMs, confirmed.statusReadElapsedMs)
                assertTrue(VehicleTelemetryFreshness.sourceTimeConfirmed(confirmed, POWER_NAME, x.elapsed, 15_000L))
            }
        }
    }

    companion object {
        private const val POWER = 0x1160030C
        private const val BATTERY = 0x11600309
        private const val RANGE = 0x11600308
        private const val SPEED = 0x11600207
        private const val GEAR = 0x11400400
        private const val POWER_NAME = "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE"
        private const val B = 60_100_000_000L
        private val EV_IDS = setOf(POWER, BATTERY, RANGE)
        private val EV_NAMES = setOf(POWER_NAME, "EV_BATTERY_LEVEL", "RANGE_REMAINING")
    }
}
