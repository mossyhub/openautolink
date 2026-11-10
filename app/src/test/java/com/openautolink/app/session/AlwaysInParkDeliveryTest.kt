package com.openautolink.app.session

import android.content.Context
import com.openautolink.app.data.AppPreferences
import com.openautolink.app.input.ProcessVehicleDataCoordinator
import com.openautolink.app.input.ProcessVehicleDataOwner
import com.openautolink.app.input.ProcessVehicleDataRuntime
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.aasdk.AasdkSession
import io.mockk.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real manager emissions with only the native recipient and VHAL source mocked. */
class AlwaysInParkDeliveryTest {
    private val moving = ControlMessage.VehicleData(speedKmh = 72f, gearRaw = 8,
        gear = "DRIVE", parkingBrake = false, driving = true, nightMode = true)

    private class Fixture(enabled: Boolean, initial: ControlMessage.VehicleData) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob())
        val context = mockk<Context>(relaxed = true)
        val manager = SessionManager(scope, context)
        val phone = mockk<AasdkSession>(relaxed = true)
        val coordinator = ProcessVehicleDataCoordinator({ _, _ -> }, {}, { 100L })
        val raw = coordinator.latestVehicleData
        private val previousOwner = field(ProcessVehicleDataRuntime, "owner").get(ProcessVehicleDataRuntime)
        init {
            field(ProcessVehicleDataRuntime, "owner").set(ProcessVehicleDataRuntime,
                ProcessVehicleDataOwner(false, { error("No automotive VHAL on this fixture") }, coordinator))
            coordinator.onRawBatch(initial)
            method("ensureVehicleDataForwarder").invoke(manager)
            assertNull(ProcessVehicleDataRuntime.forwarderOrNull())
            field(manager, "aasdkSession").set(manager, phone)
            field(manager, "alwaysInPark").setBoolean(manager, enabled)
        }
        fun forward(vd: ControlMessage.VehicleData) = coordinator.onRawBatch(vd)
        fun subscribe(type: Int) = method("onPhoneSubscribedSensor", Int::class.javaPrimitiveType!!)
            .invoke(manager, type)
        fun parked() {
            verify(atLeast = 1) { phone.sendGear(4) }
            verify(atLeast = 1) { phone.sendSpeed(0) }
            verify(atLeast = 1) { phone.sendParkingBrake(true) }
            verify(atLeast = 1) { phone.sendDrivingStatus(false) }
            verify(exactly = 0) { phone.sendGear(8) }
            verify(exactly = 0) { phone.sendSpeed(20_000) }
            verify(exactly = 0) { phone.sendParkingBrake(false) }
            verify(exactly = 0) { phone.sendDrivingStatus(true) }
        }
        override fun close() {
            runBlocking {
                (field(manager, "scope").get(manager) as CoroutineScope).coroutineContext[Job]!!.cancelAndJoin()
            }
            method("detachProcessVehicleSession").invoke(manager)
            field(ProcessVehicleDataRuntime, "owner").set(ProcessVehicleDataRuntime, previousOwner)
            scope.cancel()
        }
    }

    @Test fun regularUpdatesCannotRevokeFullOverrideOrMutateRawState() {
        Fixture(true, moving).use { x ->
            x.forward(moving)
            x.forward(moving.copy(speedKmh = 108f))
            x.parked()
            assertEquals(108f, x.raw.value.speedKmh)
            assertEquals(8, x.raw.value.gearRaw)
            assertEquals(false, x.raw.value.parkingBrake)
            assertEquals(true, x.raw.value.driving)
            verify(exactly = 1) { x.phone.sendNightMode(true) }
        }
    }

    @Test fun overrideEmitsAllFourEvenWithoutRealSamples() {
        Fixture(true, ControlMessage.VehicleData()).use { x ->
            x.forward(ControlMessage.VehicleData())
            x.parked()
        }
    }

    @Test fun coldAndReconnectSubscriptionsReplayFullOverrideWithoutRealSamples() {
        Fixture(true, ControlMessage.VehicleData()).use { x ->
            repeat(2) { for (sensor in listOf(3, 7, 8, 13)) x.subscribe(sensor) }
            x.parked()
            verify(exactly = 2) { x.phone.sendGear(4) }
            verify(exactly = 2) { x.phone.sendSpeed(0) }
            verify(exactly = 2) { x.phone.sendParkingBrake(true) }
            verify(exactly = 2) { x.phone.sendDrivingStatus(false) }
        }
    }

    @Test fun controlVehicleDataUsesSameFullOverride() = runBlocking {
        Fixture(true, moving).use { x ->
            x.manager.sendControlMessage(moving)
            x.parked()
        }
    }

    @Test fun defaultOffPreservesRealSamplesAndDoesNotInventGear() = runBlocking {
        Fixture(false, moving).use { x ->
            x.forward(moving)
            x.manager.sendControlMessage(moving)
            verify(exactly = 1) { x.phone.sendGear(8) }
            verify(exactly = 2) { x.phone.sendSpeed(20_000) }
            verify(exactly = 1) { x.phone.sendParkingBrake(false) }
            verify(exactly = 1) { x.phone.sendDrivingStatus(true) }
            verify(exactly = 0) { x.phone.sendGear(4) }
        }
        Fixture(false, ControlMessage.VehicleData()).use { x ->
            x.forward(ControlMessage.VehicleData())
            x.subscribe(8)
            verify(exactly = 0) { x.phone.sendGear(any()) }
            verify(exactly = 0) { x.phone.sendSpeed(any()) }
            verify(exactly = 0) { x.phone.sendParkingBrake(any()) }
        }
    }

    @Test fun preferenceToggleReplaysAllFourAndOffImmediatelyRestoresLatestRealSamples() = runBlocking {
        mockkObject(AppPreferences.Companion)
        try {
            Fixture(false, moving).use { x ->
                val preferences = mockk<AppPreferences>()
                val enabled = MutableStateFlow(false)
                every { AppPreferences.getInstance(x.context) } returns preferences
                every { preferences.alwaysInPark } returns enabled
                method("observeAlwaysInPark").invoke(x.manager)
                withTimeout(5_000) { while (enabled.subscriptionCount.value == 0) delay(10) }
                x.forward(moving)
                clearMocks(x.phone, answers = false)
                enabled.value = true
                verify(timeout = 5_000) { x.phone.sendParkingBrake(true) }
                x.parked()
                // New real source values must be restored without waiting for another VHAL tick.
                val latest = moving.copy(speedKmh = 36f, gearRaw = 16, gear = "REVERSE")
                x.forward(latest)
                enabled.value = false
                verify(timeout = 5_000) { x.phone.sendGear(16) }
                verify(timeout = 5_000) { x.phone.sendSpeed(10_000) }
                verify(timeout = 5_000) { x.phone.sendParkingBrake(false) }
                verify(timeout = 5_000) { x.phone.sendDrivingStatus(true) }
                assertSame(latest, x.raw.value)
            }
        } finally { unmockkObject(AppPreferences.Companion) }
    }

    @Test fun controlMessageSnapshotRestoresAfterOverrideWithoutProcessVhalSamples() = runBlocking {
        mockkObject(AppPreferences.Companion)
        try {
            Fixture(false, ControlMessage.VehicleData()).use { x ->
                val preferences = mockk<AppPreferences>()
                val enabled = MutableStateFlow(false)
                every { AppPreferences.getInstance(x.context) } returns preferences
                every { preferences.alwaysInPark } returns enabled
                method("observeAlwaysInPark").invoke(x.manager)
                withTimeout(5_000) { while (enabled.subscriptionCount.value == 0) delay(10) }
                x.manager.sendControlMessage(moving)
                clearMocks(x.phone, answers = false)
                enabled.value = true
                verify(timeout = 5_000) { x.phone.sendDrivingStatus(false) }
                x.parked()
                clearMocks(x.phone, answers = false)
                enabled.value = false
                verify(timeout = 5_000) { x.phone.sendDrivingStatus(true) }
                verify { x.phone.sendGear(8) }
                verify { x.phone.sendSpeed(20_000) }
                verify { x.phone.sendParkingBrake(false) }
            }
        } finally { unmockkObject(AppPreferences.Companion) }
    }

    @Test fun disablingOverrideWithoutRealSamplesRevokesUnrestrictedDriving() = runBlocking {
        mockkObject(AppPreferences.Companion)
        try {
            Fixture(false, ControlMessage.VehicleData()).use { x ->
                val preferences = mockk<AppPreferences>()
                val enabled = MutableStateFlow(false)
                every { AppPreferences.getInstance(x.context) } returns preferences
                every { preferences.alwaysInPark } returns enabled
                method("observeAlwaysInPark").invoke(x.manager)
                withTimeout(5_000) { while (enabled.subscriptionCount.value == 0) delay(10) }
                enabled.value = true
                verify(timeout = 5_000) { x.phone.sendDrivingStatus(false) }
                x.parked()
                clearMocks(x.phone, answers = false)
                enabled.value = false
                verify(timeout = 5_000) { x.phone.sendDrivingStatus(true) }
                verify(exactly = 0) { x.phone.sendDrivingStatus(false) }
                verify(exactly = 0) { x.phone.sendGear(any()) }
            }
        } finally { unmockkObject(AppPreferences.Companion) }
    }

    @Test fun explicitOffWithoutSamplesStaysRestrictedAcrossSubscribeAndUnknownUpdates() = runBlocking {
        mockkObject(AppPreferences.Companion)
        try {
            Fixture(false, ControlMessage.VehicleData()).use { x ->
                val preferences = mockk<AppPreferences>()
                val enabled = MutableStateFlow(false)
                every { AppPreferences.getInstance(x.context) } returns preferences
                every { preferences.alwaysInPark } returns enabled
                method("observeAlwaysInPark").invoke(x.manager)
                withTimeout(5_000) { while (enabled.subscriptionCount.value == 0) delay(10) }
                enabled.value = true
                verify(timeout = 5_000) { x.phone.sendDrivingStatus(false) }
                enabled.value = false
                verify(timeout = 5_000) { x.phone.sendDrivingStatus(true) }
                clearMocks(x.phone, answers = false)
                x.subscribe(13)
                x.forward(ControlMessage.VehicleData())
                x.subscribe(13)
                verify(exactly = 0) { x.phone.sendDrivingStatus(false) }
                verify(exactly = 2) { x.phone.sendDrivingStatus(true) }
                x.forward(moving.copy(gearRaw = 4, speedKmh = 0f, parkingBrake = true, driving = false))
                x.subscribe(13)
                verify(exactly = 2) { x.phone.sendDrivingStatus(false) }
            }
        } finally { unmockkObject(AppPreferences.Companion) }
    }

    @Test fun startupSeedsSavedOverrideBeforeObserverAndNativeSession() {
        val source = java.io.File("src/main/java/com/openautolink/app/session/SessionManager.kt").readText()
        val start = source.substringAfter("    suspend fun start(")
        val beforeObserver = start.substringBefore("        observeAlwaysInPark()")
        assertTrue("saved shared choice must be read synchronously before observing/starting native",
            beforeObserver.contains("AppPreferences.getInstance(it).alwaysInPark.first()"))
        assertTrue(beforeObserver.contains("alwaysInPark = savedAlwaysInPark"))
    }

    companion object {
        private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        private fun method(name: String, vararg args: Class<*>) = SessionManager::class.java
            .getDeclaredMethod(name, *args).apply { isAccessible = true }
    }
}
