package com.openautolink.app.carplay

import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.carplaylab.intake.OalCarDataBootstrap
import com.openautolink.carplaylab.intake.OalCarDataIdentification
import com.openautolink.carplaylab.intake.OalEvSnapshot
import com.openautolink.carplaylab.intake.OalIap2CarDataReporter
import com.openautolink.carplaylab.intake.RuntimeCarDataLink
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class CarPlayVehicleTemperatureIntegrationTest {
    @Test fun explicitOffOffersAndSendsOnlyFreshTemperatureEvenWithQualifiedEvData() {
        val saved = CarPlaySettings(evDataEnabled = false)
        assertFalse(saved.evDataEnabled)
        val policy = OalCarPlayVehicleSnapshotAdapter.savedPilot(saved)
        val base = Iap2Messages.buildRaw(0x1D01) {
            u16List(6, emptyList()); u16List(7, emptyList())
        }
        val evOnly = Iap2Messages.buildRaw(0xA100) { void(3); void(11); void(21); void(30) }
        val mixed = Iap2Messages.buildRaw(0xA100) { void(3); void(4); void(11); void(21); void(30) }
        for (fuel in listOf(10, 1)) {
            var now = 1000L
            val observation = VehiclePropertyObservation(1_000_000_000, now, 0,
                registrationGeneration = 1, subscriptionActive = true)
            val source = MutableStateFlow(ControlMessage.VehicleData(
                fuelTypes = listOf(fuel), carMake = "Chevrolet", carModel = "C234", carYear = "2025",
                evConnectorTypes = listOf(1, 5), vhalRegistrationGeneration = 1,
                ambientTempC = -12.6f, rangeKm = 210f, evBatteryLevelWh = 42_785f,
                evCurrentBatteryCapacityWh = 85_570f, evBatteryCapacityWh = 85_570f, evChargeState = 1,
                evObservationMetadata = mapOf("ENV_OUTSIDE_TEMPERATURE" to 0x11600703,
                    "RANGE_REMAINING" to 0x11600308, "EV_BATTERY_LEVEL" to 0x11600309,
                    "EV_CURRENT_BATTERY_CAPACITY" to 0x1160030D, "INFO_EV_BATTERY_CAPACITY" to 0x11600106,
                    "EV_CHARGE_STATE" to 0x11400F41).mapValues { (_, id) -> observation.copy(propertyId = id) }))
            val adapter = OalCarPlayVehicleSnapshotAdapter({ source }, { now }, { policy })
            val caps = adapter.capabilities(location = true)
            val identify = Iap2BodyReader.of(Iap2CsmFramer().offer(
                OalCarDataIdentification.apply(base, caps).encodedFrame()).single())
            assertTrue("Outside temperature must offer vehicle status with EV off (fuel=$fuel)", identify.has(21))
            assertEquals(listOf(0, 1, 4), identify.group(21).list().map { it.id })
            val info = identify.group(20)
            assertEquals(listOf(0, 1, 2), info.list().map { it.id })
            assertEquals("Vehicle information", info.string(1))
            assertTrue("EV off must not affect the location offer", identify.has(22))
            assertEquals(OalEvSnapshot(outsideTemperatureC = -13), adapter.snapshot())

            val sent = mutableListOf<Iap2Frame>()
            OalIap2CarDataReporter(caps, { now }, { null }, adapter::snapshot, {}, sent::add, {}).use { reporter ->
                reporter.handle(mixed)
                assertTrue("No pre-authentication status", sent.isEmpty())
                reporter.authenticate()
                reporter.handle(evOnly)
                assertTrue("No EV-only status with EV off", sent.isEmpty())
                reporter.handle(mixed)
                val frame = Iap2CsmFramer().offer(sent.single().encodedFrame()).single()
                assertEquals(0xA101, frame.messageId)
                val status = Iap2BodyReader.of(frame)
                assertEquals(listOf(4), status.list().map { it.id })
                assertEquals(-13, status.i16(4))

                now += 30_001L
                assertNull(adapter.snapshot())
                assertSame("Capability stays frozen while its value expires", caps, adapter.capabilities(true))
                reporter.tick()
                assertEquals("Expired temperature must not be sent again or substituted", 1, sent.size)
            }
        }
    }

    @Test fun temperatureRejectsInvalidStatusProvenanceBoundsAndTimesWithoutSubstitution() {
        val observation = VehiclePropertyObservation(1_000_000_000, 1000, 0,
            registrationGeneration = 1, subscriptionActive = true)
        val valid = ControlMessage.VehicleData(fuelTypes = listOf(10), ambientTempC = -12.6f,
            evBatteryTempC = 99f, vhalRegistrationGeneration = 1,
            evObservationMetadata = mapOf("ENV_OUTSIDE_TEMPERATURE" to observation))
        val source = MutableStateFlow(valid)
        var now = 1000L
        val adapter = OalCarPlayVehicleSnapshotAdapter({ source }, { now })
        assertEquals(-13, adapter.snapshot()!!.outsideTemperatureC)
        for (raw in listOf(null, Float.NaN, Float.POSITIVE_INFINITY, -32769f, 32768f)) {
            source.value = valid.copy(ambientTempC = raw)
            assertNull(adapter.snapshot()); assertFalse(adapter.capabilities(false).outsideTemperature)
        }
        for (bad in listOf(observation.copy(status = 1), observation.copy(source = "history-provider"),
            observation.copy(registrationGeneration = 2), observation.copy(subscriptionActive = false),
            observation.copy(timestampElapsedNanos = 0), observation.copy(timestampElapsedNanos = 2_000_000_000),
            observation.copy(receivedElapsedMs = 1001))) {
            source.value = valid.copy(evObservationMetadata = mapOf("ENV_OUTSIDE_TEMPERATURE" to bad))
            assertNull(adapter.snapshot())
        }
        now = 60000
        for (mode in listOf(null, 2)) {
            source.value = valid.copy(evObservationMetadata = mapOf("ENV_OUTSIDE_TEMPERATURE" to
                observation.copy(changeMode = mode, receivedElapsedMs = now)))
            assertNull(adapter.snapshot())
        }
        source.value = valid.copy(evObservationMetadata = mapOf("ENV_OUTSIDE_TEMPERATURE" to
            observation.copy(changeMode = 1, receivedElapsedMs = now)))
        assertEquals(-13, adapter.snapshot()!!.outsideTemperatureC)
        now = 90001; assertNull(adapter.snapshot())
    }

    @Test fun requestedSignedTemperatureUsesRealAdapterFrozenBootstrapReporterAndExport() {
        for (wired in listOf(false, true)) {
            var now = 1000L
            val clock = { now }
            val observation = VehiclePropertyObservation(1_000_000_000, 1000, 0,
                registrationGeneration = 1, subscriptionActive = true)
            val source = MutableStateFlow(ControlMessage.VehicleData(fuelTypes = listOf(10),
                ambientTempC = -12.6f, carMake = "Chevrolet", carModel = "C234", carYear = "2024",
                evConnectorTypes = listOf(1, 5), vhalRegistrationGeneration = 1,
                evObservationMetadata = mapOf("ENV_OUTSIDE_TEMPERATURE" to observation)))
            val vehicle = OalCarPlayVehicleSnapshotAdapter({ source }, clock)
            val caps = vehicle.capabilities(false)
            val receipts = mutableListOf<String>()
            val sent = mutableListOf<Iap2Frame>()
            val bootstrap = OalCarDataBootstrap(caps, clock, receipts::add)
            val base = Iap2Messages.buildRaw(0x1D01) { u16List(6, emptyList()); u16List(7, emptyList()) }
            val advertised = bootstrap.identification(base).encodedFrame()
            val request = Iap2Messages.buildRaw(0xA100) { void(4) }
            if (wired) {
                bootstrap.handle(request)
                // USB bootstrap has already sent this contract; later runtime creation must not resample identity.
                source.value = source.value.copy(carMake = "Changed", evConnectorTypes = listOf(6))
            }
            val target = OalCarPlayIntakeLink(false, vehicle, OalCarPlayGnssSnapshotAdapter(clock) {
                object : GnssForwarder {
                    override val isActive = false
                    override fun start() { fail("unadvertised GPS") }
                    override fun stop() {}
                }
            }, clock, CarPlayAppearanceBridge(source, Dispatchers.IO, clock, 5000),
                { false }, { sent += Iap2CsmFramer().offer(it.encodedFrame()).single() }, receipts::add,
                frozenCapabilities = if (wired) caps else null)
            val link: RuntimeCarDataLink = if (wired) bootstrap.wrap(target) else target
            val folder = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), "temperature-wire").toFile()
            try {
                val final = Iap2CsmFramer().offer(link.identification(base).encodedFrame()).single()
                val info = Iap2BodyReader.of(final)
                assertTrue("temperature-only source must offer vehicle status", info.has(21))
                assertTrue(Iap2BodyReader.of(Iap2Frame(0, info.raw(21))).has(4))
                assertEquals("2024 Chevrolet C234", Iap2BodyReader.of(Iap2Frame(0, info.raw(20))).string(6))
                if (wired) assertArrayEquals(advertised, target.identification(base).encodedFrame())
                assertTrue(sent.isEmpty())
                link.authenticate()
                if (!wired) link.handle(request)
                assertEquals(-13, Iap2BodyReader.of(sent.single()).i16(4))
                assertArrayEquals(byteArrayOf(0xff.toByte(), 0xf3.toByte()), Iap2BodyReader.of(sent.single()).raw(4))
                assertEquals(listOf(4), Iap2BodyReader.of(sent.single()).list().map { it.id })
                now = 31001
                link.tick(false)
                assertEquals("expired temperature cannot become a zero", 1, sent.size)
                source.value = source.value.copy(ambientTempC = 0f, evObservationMetadata = mapOf(
                    "ENV_OUTSIDE_TEMPERATURE" to observation.copy(timestampElapsedNanos = 61_001_000_000, receivedElapsedMs = 61001)))
                now = 61001; link.tick(false)
                assertEquals(0, Iap2BodyReader.of(sent.last()).i16(4)); assertEquals(2, sent.size)
                link.handle(Iap2Messages.buildRaw(0xA102) {})
                now += 30000; link.tick(false); assertEquals(2, sent.size)
                val exported = mutableListOf<String>()
                val reporter = CarPlayAudioDiagnosticReporter(1, { 1_000_000_000L }, exported::add)
                receipts.forEach {
                    assertNotNull(it, CarPlayAudioDiagnostics.sanitize(1, it))
                    assertNull(CarPlayAudioDiagnostics.sanitize(1, "$it VIN=private"))
                    reporter.record(it)
                }
                assertTrue(exported.any { it.contains("reason=transmitted count=1 fields=32") })
                val contents = exported.joinToString("\n", postfix = "\n")
                val file = java.io.File(folder, "oal_temperature.log").apply { writeText(contents) }
                val plan = com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(file), file.lastModified())
                com.openautolink.app.diagnostics.LogUploadArchive.create(plan, folder).use { archive ->
                    java.util.zip.ZipFile(archive.file).use { zip ->
                        assertEquals(contents, zip.getInputStream(zip.getEntry(file.name)).bufferedReader().readText())
                    }
                }
            } finally { link.close(); bootstrap.close(); folder.deleteRecursively() }
        }
    }
}
