package com.openautolink.app.carplay

import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class OalCarPlayIntakeIntegrationTest {
    private var now = 1000L
    private val source = MutableStateFlow(ControlMessage.VehicleData())
    private val sent = mutableListOf<Iap2Frame>()
    private var starts = 0
    private var stops = 0
    private lateinit var nmea: (ControlMessage.Gnss) -> Unit
    private val adapter = OalCarPlayVehicleSnapshotAdapter({ source }, { now })
    private val nights = mutableListOf<Boolean>()
    private val receipts = mutableListOf<String>()
    private fun link(gps: Boolean = true, appearance: CarPlayAppearanceBridge = CarPlayAppearanceBridge(source, Dispatchers.IO, {now}, 5000),
        sendNight: (Boolean) -> Boolean = {nights += it; true}): OalCarPlayIntakeLink = OalCarPlayIntakeLink(
        gps, adapter, OalCarPlayGnssSnapshotAdapter({now}) { callback ->
            nmea = callback
            object : GnssForwarder {
                override var isActive = false
                override fun start() { starts++; isActive = true }
                override fun stop() { if (isActive) stops++; isActive = false }
            }
        }, {now}, appearance, sendNight, {sent += it}, {receipts += it},
    )
    private fun initial() = Iap2IdentificationClient.identificationInformation(Iap2IdentificationConfig(
        "OAL", "OAL", "OAL", "private", "1", "1", 0))
    private fun start() = Iap2Messages.buildRaw(0xFFFA) { u16(0,0); void(1); u32(0x8001,1000) }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun appearanceRequiresSameLinkAuthPlusEncryptedEventsAndRetires() = kotlinx.coroutines.test.runTest {
        source.value = ControlMessage.VehicleData(nightMode = false, vhalRegistrationGeneration = 1,
            evObservationMetadata = mapOf("NIGHT_MODE" to VehiclePropertyObservation(
                1_000_000_000, 1000, 0, registrationGeneration = 1, propertyId = 0x11200407, subscriptionActive = true)))
        val appearance = CarPlayAppearanceBridge(source, kotlinx.coroutines.test.StandardTestDispatcher(testScheduler), {now}, 5000)
        val link = link(appearance = appearance)
        link.tick(true); testScheduler.runCurrent()
        assertTrue(nights.isEmpty())
        link.authenticate(); link.tick(false); testScheduler.runCurrent()
        assertTrue(nights.isEmpty())
        link.tick(true); testScheduler.runCurrent()
        assertEquals(listOf(false), nights)
        assertTrue("actual appearance delivery needs value-free receipt", receipts.contains("carplay-appearance reason=transmitted count=1"))
        link.close()
        source.value = source.value.copy(nightMode = true)
        link.authenticate(); link.tick(true); testScheduler.runCurrent()
        assertEquals(listOf(false), nights)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun appearanceDataBeforeEventsExpiresRatherThanBeingRetainedInTransport() = kotlinx.coroutines.test.runTest {
        source.value = ControlMessage.VehicleData(nightMode=true,vhalRegistrationGeneration=1,
            evObservationMetadata=mapOf("NIGHT_MODE" to VehiclePropertyObservation(1_000_000_000,1000,0,
                registrationGeneration=1,propertyId=0x11200407,subscriptionActive=true)))
        val link = link(appearance=CarPlayAppearanceBridge(source,kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),{now},5000))
        link.authenticate(); link.tick(false); testScheduler.runCurrent()
        now=6001; link.tick(true); testScheduler.runCurrent()
        assertTrue("expired pre-event sample must never reach retained transport",nights.isEmpty())
        source.value = source.value.copy(evObservationMetadata=mapOf("NIGHT_MODE" to VehiclePropertyObservation(6_001_000_000,6001,0,
            registrationGeneration=1,propertyId=0x11200407,subscriptionActive=true)))
        testScheduler.runCurrent()
        assertEquals(listOf(true),nights)
        link.close()
    }

    @Test fun evCapabilitiesStayFrozenWhileZeroIsSentAndStaleUnknownFieldsAreOmitted() {
        val observation = VehiclePropertyObservation(1_000_000_000,1000,0,registrationGeneration=1,subscriptionActive=true)
        source.value = ControlMessage.VehicleData(fuelTypes=listOf(10),evBatteryLevelWh=0f,
            vhalRegistrationGeneration=1,evObservationMetadata=mapOf("EV_BATTERY_LEVEL" to observation))
        val link = link(gps=false)
        val frozen = link.identification(initial()).encodedFrame()
        assertFalse(Iap2BodyReader.of(link.identification(initial())).has(22))
        link.authenticate(); link.handle(Iap2Frame(0xA100,byteArrayOf()))
        assertEquals(0L,Iap2BodyReader.of(sent.single()).u32(22))
        assertFalse(Iap2BodyReader.of(sent.single()).has(23))
        now=31001
        source.value = source.value.copy(evCurrentBatteryCapacityWh=60000f,
            evObservationMetadata=mapOf("EV_BATTERY_LEVEL" to observation.copy(timestampElapsedNanos=31_000_000_000,receivedElapsedMs=31000),
                "EV_CURRENT_BATTERY_CAPACITY" to observation.copy(timestampElapsedNanos=31_000_000_000,receivedElapsedMs=31000)))
        assertArrayEquals(frozen,link.identification(initial()).encodedFrame())
        link.tick(false)
        assertEquals(2,sent.size)
        assertFalse("late maximum cannot expand the frozen link offer", Iap2BodyReader.of(sent.last()).has(23))
        now=62001; link.tick(false)
        assertEquals("stale fields produce no synthetic update",2,sent.size)
        link.handle(Iap2Frame(0xA102,byteArrayOf())); link.close()
        val unknown = link(gps=false)
        assertFalse(Iap2BodyReader.of(unknown.identification(initial())).has(21))
        unknown.authenticate(); unknown.handle(Iap2Frame(0xA100,byteArrayOf())); unknown.tick(false)
        assertEquals(2,sent.size); unknown.close()
    }

    @Test fun interruptedRetirementStillWaitsForAdmittedAppearanceWriteBeforeReplacement() {
        source.value = ControlMessage.VehicleData(nightMode = false, vhalRegistrationGeneration = 1,
            evObservationMetadata = mapOf("NIGHT_MODE" to VehiclePropertyObservation(
                1_000_000_000,1000,0,registrationGeneration=1,propertyId=0x11200407,subscriptionActive=true)))
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val failed = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val link = link(sendNight = { entered.countDown(); check(release.await(3,java.util.concurrent.TimeUnit.SECONDS)); true })
        link.authenticate(); link.tick(true)
        assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
        val worker = Thread {
            Thread.currentThread().interrupt()
            try { link.close() } catch (t: Throwable) { failed.set(t) }
            finally { done.countDown() }
        }.apply { start() }
        try {
            assertFalse("interruption cannot acknowledge an admitted old write",done.await(100,java.util.concurrent.TimeUnit.MILLISECONDS))
        } finally { release.countDown() }
        assertTrue(done.await(1,java.util.concurrent.TimeUnit.SECONDS)); assertNull(failed.get())
        worker.join(1000)
        val successor = link(); successor.authenticate(); successor.tick(true)
        successor.close(); link.tick(true)
    }

    @Test fun cancellationCallbackCannotRestartRawGnssBeforeRetirementAcknowledges() {
        val link = link()
        link.authenticate()
        link.revoke()
        link.handle(start()); link.tick(true)
        assertEquals("revoked exact link cannot activate a source", 0, starts)
        assertTrue(sent.isEmpty())
        link.close()
    }

    @Test fun authenticatedExactLinkRequestsDriveRealGnssAndStopRetires() {
        val link = link()
        val body = Iap2BodyReader.of(link.identification(initial()))
        assertTrue("real runtime identification must advertise qualified GNSS", body.has(22))
        assertTrue(link.handle(start()))
        assertEquals(0, starts)
        link.authenticate()
        assertTrue(link.handle(start()))
        assertEquals(1, starts)
        nmea(ControlMessage.Gnss("\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"))
        now += 1000
        link.tick(false)
        assertEquals(listOf(0xFFFB), sent.map { it.messageId })
        assertEquals(1000L, link.pollTimeout(1000))
        link.handle(Iap2Frame(0xFFFC, byteArrayOf()))
        assertEquals(1, stops)
        now += 1000; link.tick(true)
        assertEquals(1, sent.size)
        link.close(); link.authenticate(); link.handle(start())
        assertEquals(1, starts)
    }
}
