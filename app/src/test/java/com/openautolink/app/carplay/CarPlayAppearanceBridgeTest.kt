package com.openautolink.app.carplay

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.app.input.ProcessVehicleDataCoordinator
import com.openautolink.app.input.ProcessVehicleDataRuntime
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.AirPlaySession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CarPlayAppearanceBridgeTest {
    @Test fun `scheduled burst is conflated to current value instead of a command backlog`() = runTest {
        val source = MutableStateFlow(observed(true))
        val ready = MutableStateFlow(false)
        val sent = mutableListOf<Boolean>()
        val bridge = CarPlayAppearanceBridge(source, StandardTestDispatcher(testScheduler), { 1_000L }, 5_000L)
        bridge.replace(ready) { sent += it; true }
        runCurrent()
        repeat(10_000) { source.value = observed(it % 2 == 0) }
        ready.value = true
        runCurrent()
        assertEquals(listOf(false), sent)
        bridge.close()
    }

    @Test fun `retiring current generation drops pending readiness but allows a successor`() = runTest {
        val source = MutableStateFlow(observed(true))
        val ready = MutableStateFlow(false)
        val sent = mutableListOf<Boolean>()
        val bridge = CarPlayAppearanceBridge(source, StandardTestDispatcher(testScheduler), { 1_000L }, 5_000L)
        val owner = bridge.replace(ready) { sent += it; true }
        runCurrent()
        bridge.retire(owner)
        ready.value = true
        runCurrent()
        assertTrue(sent.isEmpty())
        bridge.replace(ready) { sent += it; true }
        runCurrent()
        assertEquals(listOf(true), sent)
        bridge.close()
    }

    @Test fun `replacement waits for admitted write and rejects old updates after acknowledgment`() = runBlocking {
        val source = MutableStateFlow(observed(true))
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val oldSent = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val nextSent = CompletableDeferred<Boolean>()
        val bridge = CarPlayAppearanceBridge(source, Dispatchers.IO, { 1_000L }, 5_000L)
        try {
            bridge.replace(MutableStateFlow(true)) {
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                oldSent += it
                true
            }
            withTimeout(5_000L) { entered.await() }
            val replacing = async(start = CoroutineStart.UNDISPATCHED) {
                bridge.replace(MutableStateFlow(true)) { nextSent.complete(it); true }
            }
            assertFalse(replacing.isCompleted)
            source.value = observed(false)
            release.countDown()
            withTimeout(5_000L) { replacing.await() }
            assertFalse(withTimeout(5_000L) { nextSent.await() })
            assertEquals(listOf(true), oldSent)
        } finally { release.countDown(); bridge.close() }
    }

    @Test fun `production facade borrows process flow and sends on IO`() = runBlocking {
        val coordinator = ProcessVehicleDataCoordinator({ _, _ -> }, {}, { 1_000L })
        val caller = Thread.currentThread()
        val delivery = CompletableDeferred<Boolean>()
        val session = mockk<AirPlaySession>()
        every { session.controlAuthenticated } returns true
        every { session.setNightMode(any()) } answers {
            assertNotSame(caller, Thread.currentThread())
            delivery.complete(firstArg())
            true
        }
        mockkObject(ProcessVehicleDataRuntime)
        mockkStatic(SystemClock::class)
        every { ProcessVehicleDataRuntime.latestVehicleData() } returns coordinator.latestVehicleData
        every { SystemClock.elapsedRealtime() } returns 1_000L
        val bridge = CarPlayAppearanceBridge()
        try {
            verify(exactly = 1) { ProcessVehicleDataRuntime.latestVehicleData() }
            bridge.replace(session, MutableStateFlow(true))
            coordinator.onRawBatch(observed(false))
            assertFalse(withTimeout(5_000L) { delivery.await() })
            verify(exactly = 1) { session.setNightMode(false) }
        } finally {
            bridge.close()
            unmockkObject(ProcessVehicleDataRuntime)
            unmockkStatic(SystemClock::class)
        }
    }

    @Test fun `ready before first observation delivers true without replacing AA consumer`() = runTest {
        val coordinator = ProcessVehicleDataCoordinator({ _, _ -> }, {}, { 1_000L })
        val aa = mutableListOf<ControlMessage.VehicleData>()
        val attachment = coordinator.attachSessionConsumer { aa += it }
        val aaConsumer = coordinator.sessionConsumer()
        val sent = mutableListOf<Boolean>()
        val bridge = CarPlayAppearanceBridge(coordinator.latestVehicleData, StandardTestDispatcher(testScheduler), { 1_000L }, 5_000L)
        val owner = bridge.replace(MutableStateFlow(true)) { sent += it; true }
        runCurrent()
        assertTrue(sent.isEmpty())
        val sample = observed(true)
        coordinator.onRawBatch(sample)
        runCurrent()
        assertEquals(listOf(true), sent)
        assertEquals(listOf(sample), aa)
        assertSame(aaConsumer, coordinator.sessionConsumer())
        bridge.retire(owner)
        bridge.close()
        assertSame(aaConsumer, coordinator.sessionConsumer())
        coordinator.onRawBatch(observed(false))
        assertEquals(2, aa.size)
        coordinator.detachSessionConsumer(attachment)
    }

    @Test fun `fresh pending observation expires before event readiness`() = runTest {
        var now = 1_000L
        val source = MutableStateFlow(observed(true))
        val ready = MutableStateFlow(false)
        val sent = mutableListOf<Boolean>()
        val bridge = CarPlayAppearanceBridge(source, StandardTestDispatcher(testScheduler), { now }, 500L)
        bridge.replace(ready) { sent += it; true }
        runCurrent()
        now = 1_501L
        ready.value = true
        runCurrent()
        assertTrue(sent.isEmpty())
        source.value = observed(false, now)
        runCurrent()
        assertEquals(listOf(false), sent)
        bridge.close()
    }

    @Test fun `shutdown awaits admitted IO write but discards update pending behind it`() = runBlocking {
        val callerThread = Thread.currentThread()
        val source = MutableStateFlow(observed(true))
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val sent = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val bridge = CarPlayAppearanceBridge(source, Dispatchers.IO, { 1_000L }, 5_000L)
        try {
            bridge.replace(MutableStateFlow(true)) {
                assertNotSame(callerThread, Thread.currentThread())
                entered.complete(Unit)
                check(release.await(5, TimeUnit.SECONDS))
                sent += it
                true
            }
            withTimeout(5_000L) { entered.await() }
            val closing = async(start = CoroutineStart.UNDISPATCHED) { bridge.close() }
            assertFalse("shutdown must wait for already admitted write", closing.isCompleted)
            source.value = observed(false)
            release.countDown()
            withTimeout(5_000L) { closing.await() }
            assertEquals(listOf(true), sent)
        } finally { release.countDown(); bridge.close() }
    }

    @Test fun `failed send remains eligible for next readiness without a retry loop`() = runTest {
        val source = MutableStateFlow(observed(false))
        val ready = MutableStateFlow(true)
        var attempts = 0
        val bridge = CarPlayAppearanceBridge(source, StandardTestDispatcher(testScheduler), { 1_000L }, 5_000L)
        bridge.replace(ready) { attempts++; attempts > 1 }
        runCurrent()
        assertEquals(1, attempts)
        ready.value = false; runCurrent()
        ready.value = true; runCurrent()
        assertEquals(2, attempts)
        source.value = observed(false, 999L); runCurrent()
        assertEquals(2, attempts)
        bridge.close()
    }

    @Test fun `missing process source is silent`() = runTest {
        val bridge = CarPlayAppearanceBridge(null, StandardTestDispatcher(testScheduler), { 1_000L }, 5_000L)
        var attempts = 0
        bridge.replace(MutableStateFlow(true)) { attempts++; true }
        runCurrent()
        assertEquals(0, attempts)
        bridge.close()
    }

    @Test fun `facade calls exact authenticated AirPlay session setNightMode on worker`() = runTest {
        val source = MutableStateFlow(observed(false))
        val ready = MutableStateFlow(true)
        val session = mockk<AirPlaySession>()
        var authenticated = false
        every { session.controlAuthenticated } answers { authenticated }
        every { session.setNightMode(false) } returns true
        val bridge = CarPlayAppearanceBridge(source, StandardTestDispatcher(testScheduler), { 1_000L }, 5_000L)
        bridge.replace(session, ready)
        verify(exactly = 0) { session.setNightMode(any()) }
        runCurrent()
        verify(exactly = 0) { session.setNightMode(any()) }
        authenticated = true
        ready.value = false; runCurrent()
        ready.value = true; runCurrent()
        verify(exactly = 1) { session.setNightMode(false) }
        bridge.close()
    }

    private fun observed(night: Boolean?, at: Long = 1_000L) = ControlMessage.VehicleData(
        nightMode = night,
        vhalRegistrationGeneration = 7,
        evObservationMetadata = mapOf("NIGHT_MODE" to VehiclePropertyObservation(
            timestampElapsedNanos = at * 1_000_000,
            receivedElapsedMs = at,
            status = 0,
            registrationGeneration = 7,
            propertyId = 0x11200407,
            subscriptionActive = true,
        )),
    )

    @Test fun `unknown stale unavailable or non authoritative observations never send`() = runTest {
        val good = observed(true)
        val meta = good.evObservationMetadata.getValue("NIGHT_MODE")
        val invalid = listOf(
            ControlMessage.VehicleData(),
            good.copy(nightMode = null),
            good.copy(evObservationMetadata = emptyMap()),
            observed(true, at = 0L),
            observed(true, at = 2_000L),
            good.copy(evObservationMetadata = mapOf("NIGHT_MODE" to meta.copy(status = 1))),
            good.copy(evObservationMetadata = mapOf("NIGHT_MODE" to meta.copy(status = null))),
            good.copy(evObservationMetadata = mapOf("NIGHT_MODE" to meta.copy(subscriptionActive = false))),
            good.copy(vhalRegistrationGeneration = 8),
            good.copy(vhalRegistrationGeneration = null),
            good.copy(evObservationMetadata = mapOf("NIGHT_MODE" to meta.copy(timestampElapsedNanos = null))),
            good.copy(evObservationMetadata = mapOf("NIGHT_MODE" to meta.copy(propertyId = 123))),
            good.copy(evObservationMetadata = mapOf("NIGHT_MODE" to meta.copy(source = "unknown"))),
        )
        val source = MutableStateFlow(invalid.first())
        val sent = mutableListOf<Boolean>()
        val bridge = CarPlayAppearanceBridge(source, StandardTestDispatcher(testScheduler), { 1_000L }, 500L)
        bridge.replace(MutableStateFlow(true)) { sent += it; true }
        for (sample in invalid) { source.value = sample; runCurrent() }
        assertTrue("invalid observations must not become phone appearance", sent.isEmpty())
        bridge.close()
    }

    @Test fun `readiness after data delivers only latest still fresh value`() = runTest {
        val source = MutableStateFlow(observed(true))
        val ready = MutableStateFlow(false)
        val sent = mutableListOf<Boolean>()
        val bridge = CarPlayAppearanceBridge(source, StandardTestDispatcher(testScheduler), { 1_000L }, 5_000L)
        bridge.replace(ready) { sent += it; true }
        runCurrent()
        source.value = observed(false)
        runCurrent()
        assertTrue(sent.isEmpty())
        ready.value = true
        runCurrent()
        assertEquals(listOf(false), sent)
        bridge.close()
    }

    @Test fun `same value metadata refresh is deduplicated while true false transitions deliver`() = runTest {
        val source = MutableStateFlow(observed(true))
        val ready = MutableStateFlow(true)
        val sent = mutableListOf<Boolean>()
        val bridge = CarPlayAppearanceBridge(source, StandardTestDispatcher(testScheduler), { 1_000L }, 5_000L)
        bridge.replace(ready) { sent += it; true }
        runCurrent()
        source.value = observed(true, 999L)
        runCurrent()
        ready.value = false; runCurrent()
        ready.value = true; runCurrent()
        source.value = observed(false); runCurrent()
        source.value = observed(true); runCurrent()
        assertEquals(listOf(true, false, true), sent)
        bridge.close()
    }

    @Test fun `replacement rejects old readiness and stale retirement cannot close successor`() = runTest {
        val source = MutableStateFlow(observed(false))
        val oldReady = MutableStateFlow(false)
        val newReady = MutableStateFlow(true)
        val oldSent = mutableListOf<Boolean>()
        val newSent = mutableListOf<Boolean>()
        val bridge = CarPlayAppearanceBridge(source, StandardTestDispatcher(testScheduler), { 1_000L }, 5_000L)
        val old = bridge.replace(oldReady) { oldSent += it; true }
        runCurrent()
        bridge.replace(newReady) { newSent += it; true }
        runCurrent()
        bridge.retire(old)
        oldReady.value = true
        source.value = observed(true)
        runCurrent()
        assertTrue("old generation must never deliver", oldSent.isEmpty())
        assertEquals(listOf(false, true), newSent)
        bridge.close()
    }

    @Test fun `shutdown discards scheduled delivery and forbids a new generation`() = runTest {
        val source = MutableStateFlow(observed(true))
        val ready = MutableStateFlow(true)
        val sent = mutableListOf<Boolean>()
        val bridge = CarPlayAppearanceBridge(source, StandardTestDispatcher(testScheduler), { 1_000L }, 5_000L)
        bridge.replace(ready) { sent += it; true }
        bridge.close()
        runCurrent()
        assertTrue(sent.isEmpty())
        var rejected = false
        try { bridge.replace(ready) { sent += it; true } } catch (_: IllegalStateException) { rejected = true }
        assertTrue("closed facade must not admit another session", rejected)
    }

    @Test fun `observed false reaches ready session without assuming default false`() = runTest {
        val source = MutableStateFlow(observed(false))
        val ready = MutableStateFlow(true)
        val sent = mutableListOf<Boolean>()
        val bridge = CarPlayAppearanceBridge(source, StandardTestDispatcher(testScheduler), { 1_000L }, 5_000L)
        bridge.replace(ready) { sent += it; true }
        runCurrent()
        assertEquals(listOf(false), sent)
        bridge.close()
    }
}
