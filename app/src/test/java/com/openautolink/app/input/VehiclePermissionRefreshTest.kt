package com.openautolink.app.input

import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback as Callback
import android.content.Context
import android.content.pm.PackageManager
import com.openautolink.app.transport.ControlMessage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class VehiclePermissionRefreshTest {
    class Value(private val id: Int, private val value: Any?, private val timestamp: Long = 100) {
        fun getPropertyId() = id
        fun getValue() = value
        fun getTimestamp() = timestamp
        fun getStatus() = 0
    }
    class Manager {
        val subscriptions = CopyOnWriteArrayList<Int>()
        var duringSubscribe: ((Int, Callback) -> Unit)? = null
        @Volatile var accept = true
        @Volatile var exposed = setOf(GEAR, BATTERY, RANGE, CAPACITY)
        @Volatile var failStatic = false
        @Volatile var missingYear = false
        @Volatile var unavailableInitialValues = emptySet<Int>()
        val yearReads = CopyOnWriteArrayList<Int>()
        @Volatile var retirementCallbackFinished = false
        @Volatile var retired = false
        fun unsubscribePropertyEvents(callback: Callback) {
            val worker = Thread { callback.onChangeEvent(Value(GEAR, 8, 900)) }.apply { isDaemon = true; start() }
            worker.join(1000)
            retirementCallbackFinished = !worker.isAlive
            retired = true
        }
        fun getCarPropertyConfig(id: Int): Any? = if (id in exposed) Any() else null
        fun getProperty(id: Int, area: Int): Value {
            if (id in unavailableInitialValues) error("initial value unavailable")
            if (id == 0x11400103) {
                yearReads.add(id)
                if (missingYear) error("unsupported model year")
            }
            if (failStatic && id !in setOf(GEAR, BATTERY, RANGE, CAPACITY)) error("transient static read")
            return Value(id, when(id) {
            GEAR -> 4; BATTERY -> 50000f; RANGE -> 200000f; CAPACITY -> 80000f
            0x11400103 -> 2024
            0x11410105 -> intArrayOf(10)
            0x11410107 -> intArrayOf(1, 6)
            else -> "GM"
        })
        }
        fun subscribePropertyEvents(id: Int, rate: Float, callback: Callback): Boolean {
            subscriptions.add(id)
            duringSubscribe?.invoke(id, callback)
            return accept
        }
    }
    private class Fixture(private val sink: (ControlMessage.VehicleData) -> Unit = {}) {
        @Volatile var energy = false
        @Volatile var info = true
        val manager = Manager()
        val sent = CopyOnWriteArrayList<ControlMessage.VehicleData>()
        val context = mockk<Context>(relaxed = true)
        val f: VehicleDataForwarderImpl
        val proxy: Callback
        init {
            every { context.checkSelfPermission(any()) } answers {
                if (firstArg<String>() == "android.car.permission.CAR_ENERGY" && !energy) PackageManager.PERMISSION_DENIED
                else if (firstArg<String>() == "android.car.permission.CAR_INFO" && !info) PackageManager.PERMISSION_DENIED
                else PackageManager.PERMISSION_GRANTED
            }
            f = VehicleDataForwarderImpl(context, { sent.add(it); sink(it) })
            set("propertyManager", manager)
            proxy = method("createCallbackProxy", Class::class.java).invoke(f, Callback::class.java) as Callback
            set("callbackProxy", proxy)
            set("desiredActive", true)
            set("isActive", true)
            @Suppress("UNCHECKED_CAST")
            (field("trackedPropertyIds").get(f) as MutableSet<Int>).add(GEAR)
            proxy.onChangeEvent(Value(GEAR, 4))
        }
        fun field(name: String) = f.javaClass.getDeclaredField(name).apply { isAccessible = true }
        fun set(name: String, value: Any) = field(name).set(f, value)
        fun method(name: String, vararg types: Class<*>) = f.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }
        fun request() = f.requestSubscriptionRefresh("test")
        fun await(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("refresh did not complete", predicate())
        }
    }
    @Test fun unchangedMissingStaticRetriesDoNotTickRawLearningOrContribution() {
        val learned = CopyOnWriteArrayList<ControlMessage.VehicleData>()
        val contributed = CopyOnWriteArrayList<ControlMessage.VehicleData>()
        val coordinator = ProcessVehicleDataCoordinator({ data, _ -> learned.add(data) }, contributed::add, { 100L })
        val x = Fixture(coordinator::onRawBatch)
        x.manager.missingYear = true
        x.energy = true
        fun refreshAndWait(reason: String) {
            val before = x.manager.yearReads.size
            x.f.requestSubscriptionRefresh(reason)
            x.await { synchronized(x.f) {
                x.manager.yearReads.size > before && !(x.field("startInFlight").get(x.f) as Boolean)
            } }
        }
        try {
            refreshAndWait("initial-grant")
            val snapshot = x.f.latestVehicleData.value
            assertNull(snapshot.carYear)
            assertEquals(50000f, snapshot.evBatteryLevelWh)
            assertFalse(snapshot.evObservationMetadata.isEmpty())
            val counts = Triple(x.sent.size, learned.size, contributed.size)
            val subscriptions = x.manager.subscriptions.toList()
            val authorization = x.f.propertyStatus
            repeat(10) { refreshAndWait(if (it % 2 == 0) "activity-resume" else "permission-result") }
            assertEquals("unchanged static retry must not dispatch raw batches", counts.first, x.sent.size)
            assertEquals("unchanged static retry must not invoke learner", counts.second, learned.size)
            assertEquals("unchanged static retry must not record contribution", counts.third, contributed.size)
            assertEquals(snapshot, x.f.latestVehicleData.value)
            assertEquals(subscriptions, x.manager.subscriptions.toList())
            assertEquals(authorization, x.f.propertyStatus)
            assertEquals("missing INFO remains retryable on each idle request", 11, x.manager.yearReads.size)
        } finally { x.f.stop(); x.await { x.manager.retired } }
    }

    @Test fun recoveredStaticInfoPublishesWithoutNewSubscriptionOrObservation() {
        val x = Fixture()
        x.manager.missingYear = true
        try {
            x.request()
            x.await { synchronized(x.f) { !(x.field("startInFlight").get(x.f) as Boolean) } }
            val before = x.sent.size
            val subscriptions = x.manager.subscriptions.toList()
            val metadata = x.f.latestVehicleData.value.evObservationMetadata
            x.manager.missingYear = false
            x.request()
            x.await { synchronized(x.f) { !(x.field("startInFlight").get(x.f) as Boolean) } }
            assertEquals(before + 1, x.sent.size)
            assertEquals("2024", x.sent.last().carYear)
            assertEquals(metadata, x.sent.last().evObservationMetadata)
            assertEquals(subscriptions, x.manager.subscriptions.toList())
        } finally { x.f.stop(); x.await { x.manager.retired } }
    }

    @Test fun authorizationChangesWithoutInitialValuesStillPublish() {
        val x = Fixture()
        x.manager.missingYear = true
        x.manager.unavailableInitialValues = setOf(BATTERY, RANGE)
        fun refreshAndWait() {
            x.request()
            x.await { synchronized(x.f) { !(x.field("startInFlight").get(x.f) as Boolean) } }
        }
        try {
            refreshAndWait()
            val before = x.sent.size
            x.energy = true; refreshAndWait()
            assertEquals(before + 1, x.sent.size)
            assertEquals("subscribed", x.f.propertyStatus["EV_BATTERY_LEVEL"])
            assertNull(x.sent.last().evBatteryLevelWh)
            x.energy = false; refreshAndWait()
            assertEquals(before + 2, x.sent.size)
            assertTrue(x.f.propertyStatus.getValue("EV_BATTERY_LEVEL").startsWith("permission_denied"))
            x.energy = true; refreshAndWait()
            assertEquals(before + 3, x.sent.size)
            assertEquals("subscribed", x.f.propertyStatus["EV_BATTERY_LEVEL"])
            assertEquals(1, x.manager.subscriptions.count { it == BATTERY })
            refreshAndWait()
            assertEquals("now unchanged despite missing values and model year", before + 3, x.sent.size)
        } finally { x.f.stop(); x.await { x.manager.retired } }
    }

    @Test fun staticRetryPolicyKeepsImmediateCallbackErrorAndRetirementPublication() {
        val x = Fixture()
        x.manager.missingYear = true
        try {
            x.request()
            x.await { synchronized(x.f) { !(x.field("startInFlight").get(x.f) as Boolean) } }
            val before = x.sent.size
            val snapshot = x.sent.last()
            x.proxy.onChangeEvent(Value(GEAR, 4, 100))
            assertEquals("a real equal-value observation still dispatches immediately", before + 1, x.sent.size)
            assertNotEquals(snapshot.evObservationMetadata, x.sent.last().evObservationMetadata)
            x.proxy.onErrorEvent(GEAR, 0)
            assertEquals(before + 2, x.sent.size)
            assertNull(x.sent.last().gearRaw)
            assertEquals(1, x.sent.last().evObservationMetadata.getValue("GEAR_SELECTION").status)
            x.f.stop()
            assertNotEquals(snapshot.vhalRegistrationGeneration, x.f.latestVehicleData.value.vhalRegistrationGeneration)
            assertTrue("retirement must reach the raw sink", x.sent.any {
                it.vhalRegistrationGeneration != snapshot.vhalRegistrationGeneration
            })
            x.proxy.onChangeEvent(Value(GEAR, 8, 900))
            assertFalse(x.sent.any { it.evObservationMetadata["GEAR_SELECTION"]?.timestampElapsedNanos == 900L })
        } finally { x.f.stop(); x.await { x.manager.retired } }
    }

    @Test fun energyGrantAddsSubscriptionsWithoutReplacingLiveProxy() {
        val x = Fixture()
        val generation = x.field("registrationGeneration").getLong(x.f)
        val gearObservation = x.f.latestVehicleData.value.evObservationMetadata.getValue("GEAR_SELECTION")
        x.energy = true
        x.request()
        x.await { x.f.latestVehicleData.value.evBatteryLevelWh == 50000f }
        assertSame(x.manager, x.field("propertyManager").get(x.f))
        assertSame(x.proxy, x.field("callbackProxy").get(x.f))
        assertEquals(generation, x.f.latestVehicleData.value.vhalRegistrationGeneration)
        assertEquals(gearObservation, x.f.latestVehicleData.value.evObservationMetadata.getValue("GEAR_SELECTION"))
        x.proxy.onChangeEvent(Value(GEAR, 8, 200))
        assertEquals(8, x.f.latestVehicleData.value.gearRaw)
        x.request()
        Thread.sleep(100)
        assertEquals(1, x.manager.subscriptions.count { it == BATTERY })
        assertEquals(0, x.manager.subscriptions.count { it == GEAR })
        assertTrue(x.f.isActive)
        x.f.stop()
    }
    @Test fun stopDuringBlockedSubscribeFencesProxyBeforeCompletion() {
        val x = Fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        x.manager.duringSubscribe = { id, _ -> if (id == BATTERY) {
            entered.countDown(); assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
        } }
        x.energy = true
        x.request()
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        try {
            x.f.stop()
            x.proxy.onChangeEvent(Value(GEAR, 8, 900))
            assertFalse("stop must invalidate authority immediately", x.f.isActive)
            assertNotEquals("retired proxy must not publish", 900L,
                x.f.latestVehicleData.value.evObservationMetadata["GEAR_SELECTION"]?.timestampElapsedNanos)
        } finally { release.countDown() }
        x.await { x.field("propertyManager").get(x.f) == null }
        assertNull(x.f.latestVehicleData.value.evBatteryLevelWh)
    }

    @Test fun missingStaticFieldsRecoverWithoutClobberingGoodIdentity() {
        val x = Fixture()
        x.set("carMake", "Chevrolet")
        x.request()
        x.await { x.field("carModel").get(x.f) == "GM" }
        assertEquals("Chevrolet", x.field("carMake").get(x.f))
        x.f.stop()
    }

    @Test fun processOwnerRefreshPreservesLearningAndExactSessionAttachment() {
        var ticks = 0
        var resets = 0
        val coordinator = ProcessVehicleDataCoordinator({ _, _ -> ticks++ }, {}, { 100L })
        val x = Fixture(coordinator::onRawBatch)
        val owner = ProcessVehicleDataOwner(true, { x.f }, coordinator, { resets++ })
        owner.startProcess()
        val a = CopyOnWriteArrayList<ControlMessage.VehicleData>()
        val old = owner.attachSessionConsumer(a::add)
        val b = CopyOnWriteArrayList<ControlMessage.VehicleData>()
        owner.attachSessionConsumer(b::add)
        owner.detachSessionConsumer(old)
        x.energy = true
        owner.requestSubscriptionRefresh("test-owner")
        x.await { b.lastOrNull()?.evBatteryLevelWh == 50000f }
        assertNull(a.last().evBatteryLevelWh)
        assertTrue(ticks > 1)
        assertEquals(0, resets)
        assertSame(x.f, owner.forwarder)
        x.f.stop()
    }

    @Test fun synchronousBatteryCallbackBeatsOlderInitialRead() {
        val x = Fixture()
        x.manager.duringSubscribe = { id, callback -> if (id == BATTERY) callback.onChangeEvent(Value(id, 51000f, 200)) }
        x.energy = true
        x.request()
        x.await { x.f.latestVehicleData.value.evBatteryLevelWh == 51000f }
        assertEquals(200L, x.f.latestVehicleData.value.evObservationMetadata.getValue("EV_BATTERY_LEVEL").timestampElapsedNanos)
        x.f.stop()
    }

    @Test fun synchronousBatteryErrorInvalidatesInitialRead() {
        val x = Fixture()
        x.manager.duringSubscribe = { id, callback -> if (id == BATTERY) callback.onErrorEvent(id, 0) }
        x.energy = true
        x.request()
        x.await { x.f.propertyStatus["RANGE_REMAINING"] == "subscribed" }
        assertNull(x.f.latestVehicleData.value.evBatteryLevelWh)
        assertEquals(1, x.f.latestVehicleData.value.evObservationMetadata.getValue("EV_BATTERY_LEVEL").status)
        x.f.stop()
    }

    @Test fun failedSubscriptionDoesNotPromoteInitialOrSynchronousValue() {
        val x = Fixture()
        x.manager.accept = false
        x.manager.duringSubscribe = { id, callback -> callback.onChangeEvent(Value(id, 51000f, 200)) }
        x.energy = true
        x.request()
        x.await { x.f.propertyStatus["RANGE_REMAINING"] == "rejected" }
        assertNull(x.f.latestVehicleData.value.evBatteryLevelWh)
        assertFalse(x.f.latestVehicleData.value.evObservationMetadata.containsKey("EV_BATTERY_LEVEL"))
        x.f.stop()
    }

    @Test fun blockedEnergySubscribeDoesNotBlockExistingSafetyCallback() {
        val x = Fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        x.manager.duringSubscribe = { id, _ -> if (id == BATTERY) {
            entered.countDown(); assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
        } }
        val oldMetadata = x.f.latestVehicleData.value.evObservationMetadata.getValue("GEAR_SELECTION")
        x.energy = true
        x.request()
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        try {
            val worker = Thread { x.proxy.onChangeEvent(Value(GEAR, 8, 900)) }.apply { isDaemon = true; start() }
            worker.join(1000)
            assertFalse("Binder must not own callback monitor", worker.isAlive)
            assertEquals(8, x.f.latestVehicleData.value.gearRaw)
        } finally { release.countDown() }
        x.await { x.f.latestVehicleData.value.evBatteryLevelWh == 50000f }
        assertEquals(900L, x.f.latestVehicleData.value.evObservationMetadata.getValue("GEAR_SELECTION").timestampElapsedNanos)
        assertNotEquals(oldMetadata, x.f.latestVehicleData.value.evObservationMetadata.getValue("GEAR_SELECTION"))
        x.f.stop()
    }

    @Test fun grantDuringPassDrainsMissingStaticInfoWithoutDuplicateSubscription() {
        val x = Fixture()
        x.info = false
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        x.manager.duringSubscribe = { id, _ -> if (id == BATTERY) {
            entered.countDown(); assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
        } }
        x.energy = true
        x.request()
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        try { x.info = true; x.request() } finally { release.countDown() }
        x.await { x.field("carMake").get(x.f) == "GM" }
        assertEquals(1, x.manager.subscriptions.count { it == BATTERY })
        x.f.stop()
    }

    @Test fun grantQueuedDuringStartupDrainsAfterActivation() {
        val x = Fixture()
        x.set("startInFlight", true)
        x.energy = true
        x.request()
        assertTrue(x.manager.subscriptions.isEmpty())
        x.method("cleanupAfterStartAttempt", Long::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!).invoke(x.f, 0L, false)
        x.await { x.f.latestVehicleData.value.evBatteryLevelWh == 50000f }
        assertSame(x.proxy, x.field("callbackProxy").get(x.f))
        x.f.stop()
    }

    @Test fun actualStartupGrantOverlapKeepsOneManagerAndProxy() {
        var energy = false
        val context = mockk<Context>(relaxed = true)
        val pm = mockk<PackageManager>(relaxed = true)
        every { context.packageManager } returns pm
        every { pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) } returns true
        every { context.checkSelfPermission(any()) } answers {
            if (firstArg<String>() == "android.car.permission.CAR_ENERGY" && !energy) -1 else 0
        }
        val manager = Manager()
        val car = android.car.Car(manager)
        var connects = 0
        android.car.Car.factory = { connects++; car }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        manager.duringSubscribe = { id, _ -> if (id == CAPACITY) {
            entered.countDown(); assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
        } }
        val f = VehicleDataForwarderImpl(context, {})
        try {
            f.start()
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            energy = true
            f.requestSubscriptionRefresh("startup-grant")
            release.countDown()
            awaitCondition { f.isActive && f.latestVehicleData.value.evBatteryLevelWh == 50000f }
            assertEquals(1, connects)
            assertEquals(1, manager.subscriptions.count { it == GEAR })
            assertEquals(1, manager.subscriptions.count { it == BATTERY })
            assertEquals(0, car.disconnects)
            val history = f.javaClass.getDeclaredField("historyPollerJob").apply { isAccessible = true }
            val job = history.get(f)
            assertNotNull(job)
            f.requestSubscriptionRefresh("duplicate-resume")
            Thread.sleep(100)
            assertSame(job, history.get(f))
        } finally { release.countDown(); f.stop(); awaitCondition { car.disconnects == 1 }; android.car.Car.factory = null }
    }

    @Test fun serviceLossDuringSubscribeRetiresExactManagerBeforeReplacement() {
        val x = Fixture()
        val carA = android.car.Car(x.manager)
        x.set("carObject", carA)
        val managerB = Manager()
        val carB = android.car.Car(managerB)
        every { x.context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) } returns true
        // Fixture activation needs the real retry policy to own generation 1.
        val retry = x.field("retryState").get(x.f) as VhalRetryState
        val generation = retry.start()!!
        assertTrue(retry.started(generation))
        x.set("lifecycleGeneration", generation)
        android.car.Car.factory = { carB }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        x.manager.duringSubscribe = { id, _ -> if (id == BATTERY) {
            entered.countDown(); assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
        } }
        try {
            x.energy = true; x.request()
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            x.method("onCarServiceLost", Any::class.java).invoke(x.f, carA)
            x.method("onCarServiceReady", Any::class.java).invoke(x.f, carA)
            assertFalse(x.f.isActive)
            assertEquals(0, managerB.subscriptions.size)
            release.countDown()
            x.await { x.f.isActive && x.field("propertyManager").get(x.f) === managerB }
            x.proxy.onChangeEvent(Value(BATTERY, 999f, 999))
            assertEquals(50000f, x.f.latestVehicleData.value.evBatteryLevelWh)
            assertEquals(1, carA.disconnects)
            assertEquals(0, carB.disconnects)
            assertTrue(x.f.latestVehicleData.value.vhalRegistrationGeneration!! > 1L)
        } finally { release.countDown(); x.f.stop(); awaitCondition { carB.disconnects == 1 }; android.car.Car.factory = null }
    }

    @Test fun revokeDuringSubscriptionDoesNotPublishAuthorizedEnergy() {
        val x = Fixture()
        x.manager.duringSubscribe = { id, callback -> if (id == BATTERY) {
            x.energy = false
            callback.onChangeEvent(Value(id, 999f, 999))
        } }
        x.energy = true; x.request()
        x.await { !(x.field("startInFlight").get(x.f) as Boolean) }
        assertNull(x.f.latestVehicleData.value.evBatteryLevelWh)
        assertFalse(x.f.latestVehicleData.value.evObservationMetadata.containsKey("EV_BATTERY_LEVEL"))
        x.f.stop()
    }

    @Test fun regrantRestoresRetainedRegistrationWithoutDuplicateSubscribe() {
        val x = Fixture()
        x.energy = true; x.request()
        x.await { x.f.latestVehicleData.value.evBatteryLevelWh == 50000f }
        x.energy = false; x.request()
        x.await { x.f.latestVehicleData.value.evBatteryLevelWh == null }
        x.energy = true; x.request()
        x.await { x.f.latestVehicleData.value.evBatteryLevelWh == 50000f }
        assertEquals(1, x.manager.subscriptions.count { it == BATTERY })
        x.f.stop()
    }

    private fun awaitCondition(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("condition did not complete", predicate())
    }

    @Test fun deniedAndUnsupportedPermissionsDoNotResubscribeOnEveryResume() {
        val x = Fixture()
        x.manager.exposed = setOf(GEAR, CAPACITY)
        x.request()
        x.await { !(x.field("startInFlight").get(x.f) as Boolean) }
        assertTrue(x.f.propertyStatus.getValue("EV_BATTERY_LEVEL").startsWith("permission_denied"))
        x.energy = true; x.request()
        x.await { !(x.field("startInFlight").get(x.f) as Boolean) }
        assertEquals("not_exposed", x.f.propertyStatus["EV_BATTERY_LEVEL"])
        val before = x.manager.subscriptions.toList()
        repeat(10) { x.request() }
        Thread.sleep(100)
        assertEquals(before, x.manager.subscriptions.toList())
        assertTrue(x.f.isActive)
        x.f.stop()
    }

    @Test fun cleanupBinderRetirementDoesNotHoldCallbackMonitor() {
        val x = Fixture()
        x.f.stop()
        x.await { x.manager.retired }
        assertTrue("unsubscribe must permit callback thread to finish", x.manager.retirementCallbackFinished)
    }

    @Test fun transientStaticFailureRetainsGoodFieldsThenRecoversOnlyMissingFields() {
        val x = Fixture()
        x.set("carMake", "Chevrolet")
        x.manager.failStatic = true
        x.request()
        x.await { !(x.field("startInFlight").get(x.f) as Boolean) }
        assertEquals("Chevrolet", x.field("carMake").get(x.f))
        assertNull(x.field("carModel").get(x.f))
        x.manager.failStatic = false; x.request()
        x.await { x.f.latestVehicleData.value.carModel == "GM" }
        assertEquals("Chevrolet", x.f.latestVehicleData.value.carMake)
        assertEquals("2024", x.f.latestVehicleData.value.carYear)
        x.f.stop()
    }

    @Test fun stopThenStartWaitsForBlockedOldSubscribeBeforeConnectingReplacement() {
        val x = Fixture()
        val carA = android.car.Car(x.manager)
        x.set("carObject", carA)
        val managerB = Manager()
        val carB = android.car.Car(managerB)
        every { x.context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) } returns true
        var connects = 0
        android.car.Car.factory = { connects++; carB }
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        x.manager.duringSubscribe = { id, _ -> if (id == BATTERY) {
            entered.countDown(); assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
        } }
        try {
            x.energy = true; x.request()
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            x.f.stop(); x.f.start()
            assertEquals(0, connects)
            release.countDown()
            x.await { x.f.isActive && x.field("propertyManager").get(x.f) === managerB }
            x.proxy.onChangeEvent(Value(BATTERY, 999f, 999))
            assertEquals(50000f, x.f.latestVehicleData.value.evBatteryLevelWh)
            assertEquals(1, connects)
            assertEquals(1, carA.disconnects)
            assertEquals(0, carB.disconnects)
        } finally { release.countDown(); x.f.stop(); awaitCondition { carB.disconnects == 1 }; android.car.Car.factory = null }
    }

    @Test fun inactiveGrantExpeditesOneRetryAndOldTimerCannotReconnect() {
        var energy = false
        val context = mockk<Context>(relaxed = true)
        every { context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) } returns true
        every { context.checkSelfPermission(any()) } answers { if (!energy) -1 else 0 }
        val manager = Manager()
        var connects = 0
        val cars = CopyOnWriteArrayList<android.car.Car>()
        android.car.Car.factory = { connects++; android.car.Car(manager).also(cars::add) }
        val f = VehicleDataForwarderImpl(context, {})
        try {
            f.start()
            awaitCondition { cars.firstOrNull()?.disconnects == 1 }
            energy = true
            f.requestSubscriptionRefresh("inactive-grant")
            awaitCondition { f.isActive && f.latestVehicleData.value.evBatteryLevelWh == 50000f }
            assertEquals(2, connects)
            Thread.sleep(1100)
            assertEquals(2, connects)
        } finally { f.stop(); awaitCondition { cars.last().disconnects == 1 }; android.car.Car.factory = null }
    }

    @Test fun nonAutomotiveOwnerRefreshDoesNotConstructOrPrompt() {
        val owner = ProcessVehicleDataOwner(false, { error("must not construct") }, ProcessVehicleDataCoordinator({ _, _ -> }, {}, { 0 }))
        owner.requestSubscriptionRefresh("resume")
        assertNull(owner.forwarder)
    }

    @Test fun sourceTriggersUseProcessRuntimeWithoutAddingCarPrompts() {
        val activity = java.io.File("src/main/java/com/openautolink/app/MainActivity.kt").readText()
        assertTrue(activity.contains("ProcessVehicleDataRuntime.requestSubscriptionRefresh(\"activity-resume\")"))
        assertTrue(activity.contains("ProcessVehicleDataRuntime.requestSubscriptionRefresh(\"permission-result\")"))
        assertFalse(activity.contains("add(\"android.car.permission."))
    }

    @Test fun stopPublishesRetiredGenerationBeforeBlockedSubscribeReturns() {
        val x = Fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        x.manager.duringSubscribe = { id, _ -> if (id == BATTERY) {
            entered.countDown(); assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS))
        } }
        x.energy = true; x.request()
        assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
        try {
            x.f.stop()
            val data = x.f.latestVehicleData.value
            assertNotEquals("published generation must immediately retire safety authority",
                data.vhalRegistrationGeneration, data.evObservationMetadata.getValue("GEAR_SELECTION").registrationGeneration)
        } finally { release.countDown() }
        x.await { x.manager.retired }
    }

    @Test fun errorCallbackAfterRevokeCannotRestoreSubscribedAuthority() {
        val x = Fixture()
        x.energy = true; x.request()
        x.await { x.f.latestVehicleData.value.evBatteryLevelWh == 50000f }
        x.energy = false; x.request()
        x.await { x.f.latestVehicleData.value.evBatteryLevelWh == null }
        x.proxy.onErrorEvent(BATTERY, 0)
        assertFalse(x.f.latestVehicleData.value.evObservationMetadata.containsKey("EV_BATTERY_LEVEL"))
        x.f.stop()
    }

    @Test fun revokeAfterEnergyCommitBeforeEndOfPassDoesNotPublishEnergy() {
        val x = Fixture()
        x.energy = true
        x.manager.duringSubscribe = { id, _ -> if (id == CAPACITY) x.energy = false }
        x.request()
        x.await { !(x.field("startInFlight").get(x.f) as Boolean) }
        assertNull(x.f.latestVehicleData.value.evBatteryLevelWh)
        assertFalse(x.f.latestVehicleData.value.evObservationMetadata.containsKey("EV_BATTERY_LEVEL"))
        x.f.stop()
    }

    @Test fun parkedConsumerAttachmentReplaysCompleteRefreshedSnapshotWithoutAnotherEvent() {
        val coordinator = ProcessVehicleDataCoordinator({ _, _ -> }, {}, { 100L })
        val x = Fixture(coordinator::onRawBatch)
        x.energy = true; x.request()
        x.await { coordinator.latestVehicleData.value.evBatteryLevelWh == 50000f }
        val received = mutableListOf<ControlMessage.VehicleData>()
        coordinator.attachSessionConsumer(received::add)
        assertEquals(1, received.size)
        assertEquals(50000f, received.single().evBatteryLevelWh)
        assertEquals(80000f, received.single().evBatteryCapacityWh)
        assertNotNull(received.single().rangeKm)
        x.f.stop()
    }

    @Test fun synchronousRangeCallbackAndErrorOutrankInitialRead() {
        for (error in listOf(false, true)) {
            val x = Fixture()
            x.manager.duringSubscribe = { id, callback -> if (id == RANGE) {
                if (error) callback.onErrorEvent(id, 0) else callback.onChangeEvent(Value(id, 210000f, 200))
            } }
            x.energy = true; x.request()
            x.await { !(x.field("startInFlight").get(x.f) as Boolean) }
            val data = x.f.latestVehicleData.value
            if (error) {
                assertNull(data.rangeKm)
                assertEquals(1, data.evObservationMetadata.getValue("RANGE_REMAINING").status)
            } else {
                assertEquals(210f, data.rangeKm)
                assertEquals(200L, data.evObservationMetadata.getValue("RANGE_REMAINING").timestampElapsedNanos)
            }
            x.f.stop()
        }
    }

    companion object {
        const val GEAR = 0x11400400
        const val BATTERY = 0x11600309
        const val RANGE = 0x11600308
        const val CAPACITY = 0x11600106
    }
}
