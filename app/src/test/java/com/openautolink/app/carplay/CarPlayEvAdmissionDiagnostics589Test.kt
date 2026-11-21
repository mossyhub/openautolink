package com.openautolink.app.carplay

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class CarPlayEvAdmissionDiagnostics589Test {
    private val now = 170000L
    private fun observation(id: Int) = VehiclePropertyObservation(1000000000L, now, 0,
        registrationGeneration = 7, propertyId = id, subscriptionActive = true,
        changeMode = 2, statusReadElapsedMs = now)
    private fun data() = ControlMessage.VehicleData(carMake = "Chevrolet", carModel = "C234", fuelTypes = listOf(10),
        evBatteryLevelWh = 42950f, evBatteryCapacityWh = 85900f, rangeKm = 215f,
        vhalRegistrationGeneration = 7, evObservationMetadata = mapOf(
            "EV_BATTERY_LEVEL" to observation(0x11600309),
            "INFO_EV_BATTERY_CAPACITY" to observation(0x11600106),
            "RANGE_REMAINING" to observation(0x11600308)))
    private fun config() = CarPlayEvPilotConfiguration(enabled = true,
        profileId = CarPlayEvPilotConfiguration.GM_SOURCE, averageChargeKw = "90")
    private fun marker(wire: String, basis: String, model: String, full: String = "available") =
        "carplay-ev-admission wireBasis=$wire socBasis=$wire modelBasis=$basis model=$model fullRange=$full charging=absent"

    @Test fun actualAdapterDistinguishesReportedWireFromManualModelAndFinalRevocation() {
        val flow = MutableStateFlow(data())
        val lines = mutableListOf<String>()
        val adapter = OalCarPlayVehicleSnapshotAdapter({ flow }, { now }, { config().copy(modelCapacityKwh = "85") })
        adapter.capabilities(false, lines::add)
        assertNotNull(adapter.snapshot(lines::add)!!.routingModel)
        assertTrue(lines.contains(marker("reported", "manual", "admitted")))
        flow.value = data().copy(carModel = "different")
        assertNull(adapter.snapshot(lines::add)!!.routingModel)
        assertTrue(lines.contains(marker("absent", "manual", "owner", "absent")))
        flow.value = data()
        adapter.snapshot(lines::add)
        assertTrue(lines.last().contains("model=admitted"))
        val actual = data().copy(evCurrentBatteryCapacityWh = 80000f,
            evObservationMetadata = data().evObservationMetadata +
                ("EV_CURRENT_BATTERY_CAPACITY" to observation(0x1160030d)))
        val actualLines = mutableListOf<String>()
        val measured = OalCarPlayVehicleSnapshotAdapter({ MutableStateFlow(actual) }, { now }, { config() })
        measured.capabilities(false, actualLines::add)
        measured.snapshot(actualLines::add)
        assertTrue(actualLines.contains(marker("usable", "usable", "admitted")))
        assertFalse(lines.joinToString().contains("C234"))
        assertFalse(lines.joinToString().contains("model_id"))
    }

    @Test fun finalAdmissionReceiptsFollowRealScaleCurrentAndRangeVetoes() {
        val original = data()
        val flow = MutableStateFlow(original)
        val lines = mutableListOf<String>()
        val adapter = OalCarPlayVehicleSnapshotAdapter({ flow }, { now }, { config() })
        adapter.capabilities(false)
        for ((denied, result) in listOf(
            original.copy(evBatteryCapacityWh = Float.NaN) to "scale",
            original.copy(evBatteryLevelWh = Float.NaN) to "current",
            original.copy(rangeKm = Float.NaN) to "range",
            original.copy(fuelTypes = listOf(1, 10)) to "source",
            original.copy(vhalRegistrationGeneration = 8) to "owner")) {
            flow.value = denied
            assertNull(adapter.snapshot(lines::add)?.routingModel)
            assertTrue(lines.last(), lines.last().contains("model=$result fullRange=absent"))
            assertNotNull(CarPlayAudioDiagnostics.sanitize(7, lines.last()))
        }
        flow.value = original
        assertNotNull(adapter.snapshot(lines::add)!!.routingModel)
        assertEquals(marker("reported", "reported", "admitted"), lines.last())
    }

    @Test fun finiteProvenanceAndExtendedWirePresenceSurviveRealReporterAndArchive() {
        val flow = MutableStateFlow(data())
        val adapter = OalCarPlayVehicleSnapshotAdapter({ flow }, { now }, { config() })
        val actualReceipts = mutableListOf<String>()
        val caps = adapter.capabilities(false, actualReceipts::add)
        val sent = mutableListOf<com.shilapi.xcertplay.iap2.wire.Iap2Frame>()
        val wire = com.openautolink.carplaylab.intake.OalIap2CarDataReporter(caps, { now }, { null },
            { adapter.snapshot(actualReceipts::add) }, {}, sent::add, actualReceipts::add)
        try {
            wire.authenticate()
            wire.handle(com.shilapi.xcertplay.iap2.message.Iap2Messages.buildRaw(0xA100) { void(3); void(11); void(21) })
            val body = com.shilapi.xcertplay.iap2.body.Iap2BodyReader.of(sent.single())
            assertEquals(setOf(3, 11, 21, 22, 23, 24, 26, 27, 30), body.list().map { it.id }.toSet())
            val bits = mapOf(3 to 2, 11 to 4, 22 to 8, 23 to 16, 26 to 64, 27 to 128, 21 to 256, 24 to 512, 25 to 1024, 30 to 2048)
            val mask = body.list().fold(0) { value, parameter -> value or bits.getValue(parameter.id) }
            assertTrue(actualReceipts.contains("carplay-car-data id=41217 reason=transmitted count=1 fields=$mask suppressed=0"))
        } finally { wire.close() }
        val messages = actualReceipts + listOf(marker("reported", "manual", "admitted"),
            marker("usable", "usable", "admitted"), marker("absent", "reported", "scale", "absent"),
            "carplay-car-data id=41217 reason=transmitted count=1 fields=4094 suppressed=0")
        val lines = mutableListOf<String>()
        val reporter = CarPlayAudioDiagnosticReporter(7, { 0L }, lines::add)
        messages.forEach { message ->
            assertEquals("CARPLAY_AUDIO session=7 $message", CarPlayAudioDiagnostics.sanitize(7, message))
            reporter.record(message)
            listOf(" vin=private", " identity=private", " coordinates=private", " json={}", "\n", "\u0000").forEach {
                assertNull(CarPlayAudioDiagnostics.sanitize(7, message + it))
                reporter.record(message + it)
            }
        }
        assertEquals(messages.map { "CARPLAY_AUDIO session=7 $it" }, lines)
        val folder = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")), "ev-admission-export").toFile()
        try {
            val contents = lines.joinToString("\n", postfix = "\n")
            val log = java.io.File(folder, "oal_audio.log").apply { writeText(contents) }
            val plan = com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(log), log.lastModified())
            com.openautolink.app.diagnostics.LogUploadArchive.create(plan, folder).use { archive ->
                java.util.zip.ZipFile(archive.file).use { zip ->
                    assertEquals(contents, zip.getInputStream(zip.getEntry(log.name)).bufferedReader().readText())
                }
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun unknownBasisAdmissionAndOutOfBoundsPresenceFailClosed() {
        val valid = marker("reported", "manual", "admitted")
        listOf(valid.replace("wireBasis=reported", "wireBasis=measured"),
            valid.replace("socBasis=reported", "socBasis=manual"),
            valid.replace("modelBasis=manual", "modelBasis=private"),
            valid.replace("model=admitted", "model=private"),
            valid.replace("fullRange=available", "fullRange=held_secret"),
            "carplay-car-data id=41217 reason=transmitted count=1 fields=4096 suppressed=0"
        ).forEach { assertNull(it, CarPlayAudioDiagnostics.sanitize(7, it)) }
    }
}
