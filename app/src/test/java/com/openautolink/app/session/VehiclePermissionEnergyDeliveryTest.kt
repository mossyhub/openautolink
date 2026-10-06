package com.openautolink.app.session

import android.car.hardware.property.CarPropertyManager.CarPropertyEventCallback as Callback
import android.content.Context
import android.content.pm.PackageManager
import com.openautolink.app.input.ProcessVehicleDataCoordinator
import com.openautolink.app.input.ProcessVehicleDataOwner
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.input.VehicleDataForwarderImpl
import com.openautolink.app.transport.aasdk.AasdkSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Executes the production process-refresh -> SessionManager -> AasdkSession send path.
 * Reflection installs an already-connected fake CarPropertyManager and session recipient;
 * it does NOT replace forwarding/replay with a list consumer or exercise JNI, OEM Binder,
 * phone negotiation, or Maps. The recipient models the native boundary only.
 */
class VehiclePermissionEnergyDeliveryTest {
    class Value(private val id: Int, private val value: Any?) {
        fun getPropertyId() = id
        fun getValue() = value
        fun getTimestamp() = 100L
        fun getStatus() = 0
    }

    class Manager {
        val subscriptions = CopyOnWriteArrayList<Int>()
        var exposed = setOf(GEAR, BATTERY, CAPACITY, RANGE)
        var battery: Float = 50_000f
        fun getCarPropertyConfig(id: Int): Any? = if (id in exposed) Any() else null
        fun getProperty(id: Int, area: Int) = Value(id, when (id) {
            GEAR -> 4 // parked, never changes during the refresh/replay
            BATTERY -> battery
            CAPACITY -> 80_000f
            RANGE -> 200_000f
            0x11400103 -> 2024
            0x11410105 -> intArrayOf(10)
            0x11410107 -> intArrayOf(1, 6)
            else -> "GM"
        })
        fun subscribePropertyEvents(id: Int, rate: Float, callback: Callback): Boolean {
            subscriptions.add(id)
            return true // no synthetic onChangeEvent at permission grant/subscription
        }
        fun unsubscribePropertyEvents(callback: Callback) {}
    }

    private class Fixture : AutoCloseable {
        @Volatile var energyGranted = false
        val manager = Manager()
        val rawBatches = AtomicInteger()
        val contributionBatches = AtomicInteger()
        val events = AtomicInteger()
        val context = mockk<Context>(relaxed = true)
        val coordinator = ProcessVehicleDataCoordinator(
            { _, _ -> rawBatches.incrementAndGet() },
            { contributionBatches.incrementAndGet() },
            { 100L },
        )
        val forwarder: VehicleDataForwarderImpl
        val callback: Callback
        val externalScope = CoroutineScope(SupervisorJob())
        val sessionManager = SessionManager(externalScope)
        val owner: ProcessVehicleDataOwner
        private val previousOwner: Any?
        val initialGeneration: Long

        init {
            every { context.checkSelfPermission(any()) } answers {
                if (firstArg<String>() == "android.car.permission.CAR_ENERGY" && !energyGranted)
                    PackageManager.PERMISSION_DENIED else PackageManager.PERMISSION_GRANTED
            }
            forwarder = VehicleDataForwarderImpl(context, coordinator::onRawBatch)
            set(forwarder, "propertyManager", manager)
            callback = method(forwarder, "createCallbackProxy", Class::class.java)
                .invoke(forwarder, Callback::class.java) as Callback
            set(forwarder, "callbackProxy", callback)
            set(forwarder, "desiredActive", true)
            set(forwarder, "isActive", true)
            @Suppress("UNCHECKED_CAST")
            (field(forwarder, "trackedPropertyIds").get(forwarder) as MutableSet<Int>).add(GEAR)
            events.incrementAndGet()
            callback.onChangeEvent(Value(GEAR, 4))
            initialGeneration = field(forwarder, "registrationGeneration").getLong(forwarder)
            owner = ProcessVehicleDataOwner(true, { forwarder }, coordinator)
            previousOwner = field(ProcessVehicleDataRuntime, "owner").get(ProcessVehicleDataRuntime)
            set(ProcessVehicleDataRuntime, "owner", owner)
            // Real production attachment: its consumer is ::forwardVehicleData.
            assertSame(forwarder, method(sessionManager, "ensureVehicleDataForwarder").invoke(sessionManager))
        }

        fun recipient(session: AasdkSession?) {
            set(sessionManager, "aasdkSession", session)
            @Suppress("UNCHECKED_CAST")
            val state = field(sessionManager, "_sessionState").get(sessionManager) as MutableStateFlow<SessionState>
            state.value = if (session == null) SessionState.IDLE else SessionState.STREAMING
        }

        fun grantAndRefresh() {
            energyGranted = true
            ProcessVehicleDataRuntime.requestSubscriptionRefresh("permission-result-test")
            await {
                synchronized(forwarder) {
                    manager.subscriptions.containsAll(manager.exposed - GEAR) &&
                        !field(forwarder, "startInFlight").getBoolean(forwarder) &&
                        coordinator.latestVehicleData.value.evBatteryCapacityWh != null
                }
            }
        }

        fun sensor23() = method(sessionManager, "onPhoneSubscribedSensor", Int::class.javaPrimitiveType!!)
            .invoke(sessionManager, 23)

        fun assertSameSource() {
            assertSame(owner, field(ProcessVehicleDataRuntime, "owner").get(ProcessVehicleDataRuntime))
            assertSame(forwarder, ProcessVehicleDataRuntime.forwarderOrNull())
            assertSame(forwarder, field(sessionManager, "_vehicleDataForwarder").get(sessionManager))
            assertSame(manager, field(forwarder, "propertyManager").get(forwarder))
            assertSame(callback, field(forwarder, "callbackProxy").get(forwarder))
            assertEquals(initialGeneration, field(forwarder, "registrationGeneration").getLong(forwarder))
            assertEquals(1, events.get())
            assertEquals(4, forwarder.latestVehicleData.value.gearRaw)
        }

        override fun close() {
            recipient(null)
            method(sessionManager, "detachProcessVehicleSession").invoke(sessionManager)
            set(ProcessVehicleDataRuntime, "owner", previousOwner)
            forwarder.stop()
            await { field(forwarder, "propertyManager").get(forwarder) == null }
            (field(sessionManager, "scope").get(sessionManager) as CoroutineScope).cancel()
            externalScope.cancel()
        }
    }

    @Test fun permissionRefreshBeforePhoneReadyReplaysCachedEnergyOnParkedSensor23() {
        Fixture().use { x ->
            val phone = mockk<AasdkSession>(relaxed = true)
            assertNull(x.forwarder.latestVehicleData.value.evBatteryLevelWh)
            x.grantAndRefresh() // no session exists and no VHAL event fires
            val cached = x.forwarder.latestVehicleData.value
            assertEquals(50_000f, cached.evBatteryLevelWh)
            assertEquals(80_000f, cached.evBatteryCapacityWh)
            assertEquals(200f, cached.rangeKm)
            verify(exactly = 0) { phone.sendEnergyModel(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            val batches = x.rawBatches.get()
            val contributions = x.contributionBatches.get()
            x.recipient(phone) // model phone/native readiness, not a new source attachment
            x.sensor23() // real onPhoneSubscribedSensor -> sendCurrentEnergyModel
            verify(exactly = 1) { phone.sendEnergyModel(50_000, 80_000, 200_000, 0, -1f, -1f, -1f, -1f, -1, -1) }
            verify(exactly = 1) { phone.sendEnergyModel(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            assertEquals(batches, x.rawBatches.get())
            assertEquals(contributions, x.contributionBatches.get())
            assertEquals(cached, x.forwarder.latestVehicleData.value)
            x.assertSameSource()
            verify(exactly = 0) { phone.forceReconnect(any()) }
            verify(exactly = 0) { phone.stop() }
        }
    }

    @Test fun alreadyStreamingPermissionRefreshForwardsEnergyWithoutReconnectOrSourceReplacement() {
        Fixture().use { x ->
            val phone = mockk<AasdkSession>(relaxed = true)
            x.recipient(phone)
            val attachment = field(x.sessionManager, "processVehicleAttachment").get(x.sessionManager)
            val batches = x.rawBatches.get()
            x.grantAndRefresh() // real raw dispatch -> forwardVehicleData -> sendEnergyModelWithTuning
            verify(exactly = 1) { phone.sendEnergyModel(50_000, 80_000, 200_000, 0, -1f, -1f, -1f, -1f, -1, -1) }
            verify(exactly = 1) { phone.sendEnergyModel(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            assertTrue("the refreshed raw process batch must execute", x.rawBatches.get() > batches)
            assertSame(attachment, field(x.sessionManager, "processVehicleAttachment").get(x.sessionManager))
            assertEquals(SessionState.STREAMING, x.sessionManager.sessionState.value)
            x.assertSameSource()
            verify(exactly = 0) { phone.forceReconnect(any()) }
            verify(exactly = 0) { phone.stop() }
        }
    }

    @Test fun sensor23ReplayUsesCurrentSessionRatherThanRecipientAtRefreshTime() {
        Fixture().use { x ->
            val oldPhone = mockk<AasdkSession>(relaxed = true)
            val currentPhone = mockk<AasdkSession>(relaxed = true)
            x.recipient(oldPhone)
            x.grantAndRefresh()
            verify(exactly = 1) { oldPhone.sendEnergyModel(50_000, 80_000, 200_000, 0, -1f, -1f, -1f, -1f, -1, -1) }
            val batches = x.rawBatches.get()
            x.recipient(currentPhone)
            x.sensor23()
            verify(exactly = 1) { oldPhone.sendEnergyModel(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            verify(exactly = 1) { currentPhone.sendEnergyModel(50_000, 80_000, 200_000, 0, -1f, -1f, -1f, -1f, -1, -1) }
            assertEquals(batches, x.rawBatches.get())
            x.assertSameSource()
        }
    }

    @Test fun sensor23RejectsIncompleteCachedEnergySnapshot() {
        Fixture().use { x ->
            x.manager.exposed = setOf(GEAR, BATTERY, CAPACITY) // range unsupported
            x.grantAndRefresh()
            assertNull(x.forwarder.latestVehicleData.value.rangeKm)
            val phone = mockk<AasdkSession>(relaxed = true)
            x.recipient(phone)
            x.sensor23()
            verify(exactly = 0) { phone.sendEnergyModel(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            assertFalse(x.sessionManager.forceSendEnergyModel())
        }
    }

    @Test fun sensor23RejectsInvalidBatteryInsteadOfTreatingCacheAsComplete() {
        Fixture().use { x ->
            x.manager.battery = Float.NaN
            x.grantAndRefresh()
            assertTrue("invalid raw battery must not qualify for replay",
                x.forwarder.latestVehicleData.value.evBatteryLevelWh?.isNaN() == true)
            val phone = mockk<AasdkSession>(relaxed = true)
            x.recipient(phone)
            x.sensor23()
            verify(exactly = 0) { phone.sendEnergyModel(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            assertFalse(x.sessionManager.forceSendEnergyModel())
        }
    }

    companion object {
        private const val GEAR = 0x11400400
        private const val BATTERY = 0x11600309
        private const val CAPACITY = 0x11600106
        private const val RANGE = 0x11600308
        private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        private fun set(target: Any, name: String, value: Any?) = field(target, name).set(target, value)
        private fun method(target: Any, name: String, vararg types: Class<*>) =
            target.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }
        private fun await(predicate: () -> Boolean) {
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("refresh/retirement did not complete", predicate())
        }
    }
}
