package com.openautolink.app.input

import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.openautolink.app.carplay.CarPlayEvPilotConfiguration
import com.openautolink.app.carplay.OalCarPlayVehicleSnapshotAdapter
import com.openautolink.app.transport.ControlMessage
import com.openautolink.carplaylab.intake.OalCarDataIdentification
import com.openautolink.carplaylab.intake.OalIap2CarDataReporter
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Framework-boundary fixture; assertions use the same APIs on baseline and candidate. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarPlayLowRangeWarningTest {
    class Config { fun getChangeMode() = 2 } // Exercise exact-read confirmation, not ONCHANGE age exemption.
    class Value(private val id: Int, private val raw: Any?, private val area: Int = 0, private val status: Int = 0,
        private val timestamp: Long = 1_000_000_000L) {
        fun getPropertyId() = id
        fun getAreaId() = area
        fun getValue() = raw
        fun getStatus() = status
        fun getTimestamp() = timestamp
    }
    class Manager {
        var warning = Value(LOW, false)
        var exposeWarning = true
        var acceptWarning = true
        var exposeRpm = false
        var duringWarningRead: (() -> Unit)? = null
        var duringConnectorRead: (() -> Unit)? = null
        val reads = mutableListOf<Int>()
        lateinit var callback: CarPropertyEventCallback
        fun getCarPropertyConfig(id: Int) = if (id == RANGE || (id == LOW && exposeWarning) || (id == RPM && exposeRpm)) Config() else null
        fun getProperty(id: Int, area: Int): Value {
            check(area == 0)
            reads += id
            if (id == LOW) duringWarningRead?.invoke()
            if (id == CONNECTORS) duringConnectorRead?.invoke()
            return when (id) {
                LOW -> warning
                RPM -> Value(RPM, 1000f)
                else -> Value(RANGE, 200_000f)
            }
        }
        fun subscribePropertyEvents(id: Int, rate: Float, cb: CarPropertyEventCallback): Boolean {
            callback = cb
            return id != LOW || acceptWarning
        }
        fun event(value: Value) {
            // Same shared proxy entry point as Binder, with reflected platform-value getters.
            val method = CarPropertyEventCallback::class.java.methods.single { it.name == "onChangeEvent" }
            Proxy.getInvocationHandler(callback).invoke(callback, method, arrayOf(value))
        }
    }
    private class Fixture {
        @Volatile var now = 60_000L
        @Volatile var energyGranted = true
        val manager = Manager()
        val context = mockk<Context>(relaxed = true)
        val learned = mutableListOf<ControlMessage.VehicleData>()
        val contributed = mutableListOf<ControlMessage.VehicleData>()
        val projected = mutableListOf<ControlMessage.VehicleData>()
        val coordinator = ProcessVehicleDataCoordinator({ data, _ -> learned += data }, { contributed += it }, { now })
        val forwarder = VehicleDataForwarderImpl(context, coordinator::onRawBatch)
        init {
            every { SystemClock.elapsedRealtime() } answers { now }
            every { SystemClock.elapsedRealtimeNanos() } answers { now * 1_000_000L }
            every { context.checkSelfPermission(any()) } answers {
                if (!energyGranted && firstArg<String>() == "android.car.permission.CAR_ENERGY") PackageManager.PERMISSION_DENIED
                else PackageManager.PERMISSION_GRANTED
            }
            set("desiredActive", true); set("isActive", true); set("propertyManager", manager)
            set("registrationGeneration", 1L)
            set("fuelTypes", listOf(10)); set("carMake", "Chevrolet"); set("carModel", "C234"); set("carYear", "2024")
            coordinator.attachSessionConsumer { projected += it }
        }
        private fun field(name: String) = forwarder.javaClass.getDeclaredField(name).apply { isAccessible = true }
        private fun set(name: String, value: Any) = field(name).set(forwarder, value)
        fun useScheduler(scheduler: TestCoroutineScheduler) {
            ((field("scope").get(forwarder) as CoroutineScope).coroutineContext[Job])?.cancel()
            set("scope", CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler)))
        }
        fun close() {
            val job = (field("scope").get(forwarder) as CoroutineScope).coroutineContext[Job]!!
            job.cancel()
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(5_000) { job.join() } }
        }
        fun joinRefresh() {
            val job = (field("scope").get(forwarder) as CoroutineScope).coroutineContext[Job]!!
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(5_000) { job.children.toList().forEach { it.join() } } }
        }
        fun refresh() = forwarder.javaClass.getDeclaredMethod("registerProperties", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(forwarder, 0L)
        fun advance() { now += 5_000; refresh() }
        val data get() = forwarder.latestVehicleData.value
        val observation get() = data.evObservationMetadata["FUEL_LEVEL_LOW"]
    }
    private fun withFixture(body: (Fixture) -> Unit) {
        mockkStatic(SystemClock::class)
        try {
            val fixture = Fixture()
            try { body(fixture) } finally { fixture.close() }
        } finally { unmockkStatic(SystemClock::class) }
    }

    private fun assertWarningLeavesBatchAlone(f: Fixture, exactRead: Boolean, sharing: Boolean) {
        val scheduler = TestCoroutineScheduler()
        f.useScheduler(scheduler)
        try {
            f.manager.exposeRpm = true
            f.refresh(); f.advance()
            // Keep the range source fresh but not itself due when only LOW needs a read.
            f.now += 5_000
            f.manager.event(Value(RANGE, 200_000f, timestamp = f.now * 1_000_000L))
            val pilot = CarPlayEvPilotConfiguration(enabled = sharing,
                profileId = if (sharing) CarPlayEvPilotConfiguration.GM_SOURCE else CarPlayEvPilotConfiguration.GM_ROUTING,
                averageChargeKw = "90")
            val adapter = OalCarPlayVehicleSnapshotAdapter({ f.forwarder.latestVehicleData }, { f.now }, { pilot })
            val caps = adapter.capabilities(false)
            if (!sharing) assertFalse("EV sharing Off must not advertise warning", caps.electricRangeWarning)
            val fanOut = listOf(f.learned, f.contributed, f.projected)
            val ordinary = f.projected.last()
            val size = f.projected.size
            f.manager.event(Value(RPM, 1500f))
            scheduler.runCurrent() // Start the real scheduled trailing flush at this clock value.
            assertEquals(size, f.projected.size)
            if (exactRead) {
                f.manager.warning = Value(LOW, true)
                f.refresh()
                assertEquals(f.now, f.observation?.statusReadElapsedMs)
            } else {
                f.manager.event(Value(LOW, true, timestamp = f.now * 1_000_000L))
                assertNull(f.observation?.statusReadElapsedMs)
            }
            assertEquals(true, f.data.evLowRangeWarning)
            assertEquals("Warning-only update must not dispatch regular fan-out (EV sharing=$sharing)",
                listOf(size, size, size), fanOut.map { it.size })
            assertEquals("Pending RPM cannot leak into CarPlay snapshot early", ordinary.rpmE3, f.data.rpmE3)
            assertEquals(if (sharing) true else null, adapter.snapshot {}?.electricRangeWarning)

            f.manager.callback.onErrorEvent(LOW, 0)
            assertNull(f.data.evLowRangeWarning)
            assertEquals(listOf(size, size, size), fanOut.map { it.size })
            f.now += 499; scheduler.advanceTimeBy(499); scheduler.runCurrent()
            assertEquals(listOf(size, size, size), fanOut.map { it.size })
            f.now++; scheduler.advanceTimeBy(1); scheduler.runCurrent()
            assertEquals(listOf(size + 1, size + 1, size + 1), fanOut.map { it.size })
            fanOut.forEach { assertEquals(1_500_000, it.last().rpmE3) }
        } finally {
            // Cancellation is drained on this fixture's scheduler, not a live process worker.
            (f.forwarder.javaClass.getDeclaredField("scope").apply { isAccessible = true }
                .get(f.forwarder) as CoroutineScope).coroutineContext[Job]!!.cancel()
            scheduler.runCurrent()
        }
    }

    @Test fun warningCallbackDoesNotDispatchOrRetireOrdinaryFlush() = withFixture {
        assertWarningLeavesBatchAlone(it, exactRead = false, sharing = true)
    }

    @Test fun warningExactReadDoesNotDispatchOrRetireOrdinaryFlush() = withFixture {
        assertWarningLeavesBatchAlone(it, exactRead = true, sharing = true)
    }

    @Test fun warningCallbackDoesNotDispatchWithEvSharingOff() = withFixture {
        assertWarningLeavesBatchAlone(it, exactRead = false, sharing = false)
    }

    @Test fun defaultCarPlaySourceSeesWarningWithoutChangingRawRuntimeState() = withFixture { f ->
        val runtime = ProcessVehicleDataRuntime
        val field = runtime.javaClass.getDeclaredField("owner").apply { isAccessible = true }
        val owner = ProcessVehicleDataOwner(true, { f.forwarder }, f.coordinator)
        synchronized(runtime) {
            assertNull("Plain JVM fixture does not replace a live runtime", field.get(runtime))
            field.set(runtime, owner)
        }
        try {
            f.refresh(); f.advance()
            val adapter = OalCarPlayVehicleSnapshotAdapter(elapsedMs = { f.now }, pilotConfiguration = {
                CarPlayEvPilotConfiguration(enabled = true, profileId = CarPlayEvPilotConfiguration.GM_SOURCE, averageChargeKw = "90")
            })
            val sent = mutableListOf<Iap2Frame>()
            val raw = runtime.latestVehicleData()!!
            val retained = raw.value
            val caps = adapter.capabilities(false)
            OalIap2CarDataReporter(caps, { f.now }, { null }, { adapter.snapshot() }, {}, sent::add, {}).use { reporter ->
                reporter.authenticate()
                reporter.handle(Iap2Messages.buildRaw(0xA100) { void(11) })
                assertArrayEquals(byteArrayOf(0), Iap2BodyReader.of(sent.single()).optionalRaw(15))
                f.now += 30_000
                f.manager.event(Value(LOW, true, timestamp = f.now * 1_000_000L))
                assertSame("Coordinator must retain its prior ordinary batch", retained, raw.value)
                reporter.tick()
                assertEquals(2, sent.size)
                val latest = Iap2BodyReader.of(sent.last())
                assertEquals(200, latest.u16(11))
                assertArrayEquals("Normal adapter must see forwarder-only warning", byteArrayOf(1), latest.optionalRaw(15))
            }
        } finally {
            synchronized(runtime) {
                assertSame(owner, field.get(runtime))
                field.set(runtime, null)
            }
        }
    }

    @Test fun knownDenialRevokesWarningWhileEarlierRefreshIsBlocked() = withFixture { f ->
        f.refresh(); f.advance()
        val adapter = OalCarPlayVehicleSnapshotAdapter({ f.forwarder.latestVehicleData }, { f.now }, {
            CarPlayEvPilotConfiguration(enabled = true, profileId = CarPlayEvPilotConfiguration.GM_SOURCE, averageChargeKw = "90")
        })
        val caps = adapter.capabilities(false)
        val sent = mutableListOf<Iap2Frame>()
        OalIap2CarDataReporter(caps, { f.now }, { null }, { adapter.snapshot() }, {}, sent::add, {}).use { reporter ->
            reporter.authenticate()
            reporter.handle(Iap2Messages.buildRaw(0xA100) { void(11) })
            assertArrayEquals(byteArrayOf(0), Iap2BodyReader.of(sent.single()).optionalRaw(15))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val size = f.projected.size
            f.manager.duringConnectorRead = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
            f.now += 30_000 // Authenticated reporting due; range and warning still fresh at the inclusive boundary.
            try {
                f.forwarder.requestSubscriptionRefresh("hold-earlier-static-read")
                assertTrue("Earlier refresh must be held in the CAR_INFO read", entered.await(5, TimeUnit.SECONDS))
                f.energyGranted = false
                f.forwarder.requestSubscriptionRefresh("observed-energy-denial")
                reporter.tick()
                assertEquals("Still-qualified range must produce a due update", 2, sent.size)
                val denied = Iap2BodyReader.of(sent.last())
                assertEquals(200, denied.u16(11))
                assertNull("Known denial must omit ID 15 while refresh is held", denied.optionalRaw(15))
                assertEquals(listOf(size, size, size), listOf(f.learned.size, f.contributed.size, f.projected.size))

                f.manager.warning = Value(LOW, true)
                f.energyGranted = true
                f.forwarder.requestSubscriptionRefresh("restored-energy-grant")
                assertNull("Regrant alone cannot resurrect the old sample", adapter.snapshot {}?.electricRangeWarning)
            } finally {
                release.countDown()
                f.joinRefresh()
            }
            assertEquals(f.now, f.observation?.statusReadElapsedMs)
            f.now += 30_000
            reporter.tick()
            assertEquals(3, sent.size)
            assertArrayEquals("New authorized exact read recovers on the same link", byteArrayOf(1),
                Iap2BodyReader.of(sent.last()).optionalRaw(15))
        }
    }

    @Test fun warningReadUsesExactGlobalBooleanAndOriginalEventTime() = withFixture { f ->
        f.refresh()
        assertTrue("FUEL_LEVEL_LOW must be read", LOW in f.manager.reads)
        assertEquals(LOW, f.observation?.propertyId)
        assertNull("Do not repurpose the AA lowFuel field", f.data.lowFuel)
        f.advance()
        assertEquals(1_000_000_000L, f.observation?.timestampElapsedNanos)
        assertEquals(f.now, f.observation?.statusReadElapsedMs)
        for (invalid in listOf(Value(LOW, 0), Value(LOW, false, area = 1), Value(RANGE, false), Value(LOW, false, status = 1))) {
            f.manager.warning = invalid; f.advance()
            assertNotEquals(0, f.observation?.status)
            f.manager.warning = Value(LOW, false); f.advance()
            assertEquals(0, f.observation?.status)
        }
        f.energyGranted = false; f.advance()
        assertNull(f.observation)
    }

    @Test fun missingPermissionExposureOrSubscriptionDoesNotAuthorizeWarning() = withFixture { f ->
        f.energyGranted = false; f.refresh()
        assertFalse(LOW in f.manager.reads)
        f.energyGranted = true; f.manager.exposeWarning = false; f.refresh()
        assertFalse(LOW in f.manager.reads)
        assertNull(f.observation)
    }

    @Test fun rejectedSubscriptionCannotPublishAnInitialRead() = withFixture { f ->
        f.manager.acceptWarning = false; f.refresh()
        assertTrue(LOW in f.manager.reads)
        assertNull(f.observation)
    }

    @Test fun qualifiedWarningTakesOrdinaryElectricRangeAndExplicitSelectorPaths() = withFixture { f ->
        f.refresh(); f.advance()
        val flow = MutableStateFlow(f.data)
        val pilot = CarPlayEvPilotConfiguration(enabled = true, profileId = CarPlayEvPilotConfiguration.GM_SOURCE, averageChargeKw = "90")
        val adapter = OalCarPlayVehicleSnapshotAdapter({ flow }, { f.now }, { pilot })
        val caps = adapter.capabilities(false)
        val identify = OalCarDataIdentification.apply(Iap2Messages.buildRaw(0x1D01) {
            u16List(6, emptyList()); u16List(7, emptyList())
        }, caps)
        val advertised = Iap2BodyReader.of(identify).group(21)
        val sent = mutableListOf<Iap2Frame>()
        OalIap2CarDataReporter(caps, { f.now }, { null }, { adapter.snapshot() }, {}, sent::add, {}).use { reporter ->
            val ordinary = Iap2Messages.buildRaw(0xA100) { void(3); void(11); void(21) }
            reporter.handle(ordinary)
            assertTrue(sent.isEmpty())
            reporter.authenticate(); reporter.handle(ordinary)
            fun last() = Iap2BodyReader.of(sent.last().also { assertEquals(0xA101, it.messageId) })
            assertEquals(200, last().u16(11))
            assertArrayEquals("Known false is not omission", byteArrayOf(0), last().optionalRaw(15))
            assertArrayEquals(byteArrayOf(), advertised.optionalRaw(15))
            assertNull(advertised.optionalRaw(6)); assertNull(last().optionalRaw(6))

            val explicit = Iap2Messages.buildRaw(0xA100) { void(15) }
            f.now += 30_000; f.manager.warning = Value(LOW, true); f.refresh(); flow.value = f.data
            reporter.handle(explicit)
            assertEquals(2, sent.size)
            assertEquals(listOf(15), last().list().map { it.id })
            assertArrayEquals(byteArrayOf(1), last().raw(15))

            // A callback error supersedes the read that was already in flight.
            f.manager.duringWarningRead = { f.manager.callback.onErrorEvent(LOW, 0) }
            f.now += 30_000; f.refresh(); flow.value = f.data; reporter.tick()
            assertEquals("Unknown warning-only produces no empty update", 2, sent.size)
            f.manager.duringWarningRead = null
            f.now += 30_000; f.refresh(); flow.value = f.data
            reporter.handle(Iap2Messages.buildRaw(0xA100) { void(3) })
            assertEquals(listOf(3), last().list().map { it.id })
            assertEquals(3, sent.size)

            // Fresh range does not extend the retained warning's receipt deadline.
            val oldWarning = f.observation!!
            f.now += 30_001; f.refresh()
            flow.value = f.data.copy(evObservationMetadata = f.data.evObservationMetadata + ("FUEL_LEVEL_LOW" to oldWarning))
            reporter.handle(ordinary)
            assertEquals(4, sent.size); assertEquals(200, last().u16(11)); assertNull(last().optionalRaw(15))

            // Coherent new VHAL owner is not the link that advertised the capability.
            f.now += 30_000; f.refresh()
            flow.value = f.data.copy(vhalRegistrationGeneration = 99L,
                evObservationMetadata = f.data.evObservationMetadata.mapValues { it.value.copy(registrationGeneration = 99L) })
            reporter.handle(ordinary)
            assertEquals(5, sent.size); assertEquals(200, last().u16(11)); assertNull(last().optionalRaw(15))
        }
    }

    companion object {
        private const val LOW = 0x11200405
        private const val RANGE = 0x11600308
        private const val RPM = 0x11600305
        private const val CONNECTORS = 0x11410107
    }
}
