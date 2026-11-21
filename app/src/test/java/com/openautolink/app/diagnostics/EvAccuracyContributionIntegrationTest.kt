package com.openautolink.app.diagnostics

import com.openautolink.app.navigation.EnergyAtDistance
import com.openautolink.app.navigation.VehicleEnergyForecast
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvAccuracyContributionIntegrationTest {
    @get:Rule val folder = TemporaryFolder()
    private val url = "https://example.test/compact"
    private val token = "owner-secret"

    @Test fun legacyBindingStaysLegacyAndExpandedGrantBindsExactIntake() {
        val v2 = EvContributionConsentBinding.create(url, token)!!
        val expanded = EvContributionConsentBinding.createAccuracy(url, token)!!
        assertFalse(v2.accuracy)
        assertTrue(EvContributionConsentBinding.decode(expanded.encode())!!.accuracy)
        assertTrue(expanded.matches(url, token))
        assertFalse(expanded.matches("$url/other", token))
        assertFalse(expanded.matches(url, "other-secret"))
        assertNotEquals(v2.tokenFingerprint, expanded.captureNamespace)
    }

    @Test fun schema3ArchiveRetainsQualifiedParkedChargeAndDoesNotBridgeSleep() {
        val queue = EvContributionQueue(folder.newFolder())
        val binding = EvContributionConsentBinding.createAccuracy(url, token)!!
        var next = 0
        val capture = EvAccuracyCapture { "window-${++next}" }
        capture.vehicle(queue, binding, sample(40_000, 4), 40_000, 10_000)
        // Refreshes and changes between emissions do not amplify disk rows.
        for (now in 41_000L..45_000L step 1_000L) {
            capture.vehicle(queue, binding, sample(now, 4, battery = 51_000f), now, 10_000 + now - 40_000)
        }
        capture.vehicle(queue, binding, sample(80_000, 4), 80_000, 50_000)
        // Port + positive rate are not independent authorization to record a charge.
        capture.vehicle(queue, binding, sample(85_000, 4, state = 3), 85_000, 55_000)
        val units = queue.pending()
        assertEquals(2, units.size)
        assertTrue(units.all { it.namespace == binding.captureNamespace })
        val (manifest, lines) = read(units.first())
        assertTrue(manifest.startsWith("schema=3\n"))
        assertTrue(manifest.contains("closure=gap\n"))
        assertEquals(listOf("0", "5000"), lines.map { it.getValue("elapsedMs").jsonPrimitive.content })
        assertEquals(listOf("charge", "charge"), lines.map { it.getValue("type").jsonPrimitive.content })
        assertEquals("51000.0", lines.last().getValue("batteryWh").jsonPrimitive.content)
        assertEquals("76000.0", lines.last().getValue("usableCapacityWh").jsonPrimitive.content)
        assertEquals("85000.0", lines.last().getValue("reportedCapacityWh").jsonPrimitive.content)
        assertFalse(lines.last().containsKey("batteryPct")) // Not a displayed SOC observation.
        assertFalse(lines.last().containsKey("latitude"))
        assertEquals(1, read(units.last()).second.size)
        exportFixture("charge-v3.zip", units.first())
    }

    @Test fun legacyQueueProducesCompatibleUploadFixture() {
        val queue = EvContributionQueue(folder.newFolder())
        val binding = EvContributionConsentBinding.create(url, token)!!
        queue.append("legacy-fixture", 10_000,
            """{"schema":2,"type":"vehicle","elapsedBucketS":0,"batteryWh":50000,"batteryPct":58,"energyBasis":"absolute-wh","estimatorRevision":"rev2","vehicleClass":"0123456789abcdef"}""",
            binding.captureNamespace, "ev-class-0123456789abcdef")
        queue.close("legacy-fixture", 15_000)
        val unit = queue.pending().single()
        assertTrue(read(unit).first.startsWith("schema=2\n"))
        exportFixture("legacy-v2.zip", unit)
    }

    private fun exportFixture(name: String, unit: EvContributionQueue.Pending) {
        val output = System.getenv("OAL_EV_QUEUE_FIXTURE_DIR") ?: return
        val dir = java.nio.file.Path.of(output)
        require(java.nio.file.Files.isDirectory(dir))
        java.nio.file.Files.copy(unit.file.toPath(), dir.resolve(name))
    }

    @Test fun driveSeparatesStalePackReadFromCoveredSpeedAndRejectsLegacyUploadAuthority() {
        val queue = EvContributionQueue(folder.newFolder())
        val binding = EvContributionConsentBinding.createAccuracy(url, token)!!
        val capture = EvAccuracyCapture { "drive" }
        capture.vehicle(queue, binding, sample(40_000, 8, state = 3), 40_000, 10_000)
        val data = sample(45_000, 8, state = 3)
        val stale = data.evObservationMetadata.getValue("EV_BATTERY_LEVEL").copy(timestampElapsedNanos = 1_000_000L)
        capture.vehicle(queue, binding, data.copy(evObservationMetadata = data.evObservationMetadata + ("EV_BATTERY_LEVEL" to stale)), 45_000, 15_000)
        val withoutPark = sample(50_000, 4, state = 3).let {
            it.copy(evObservationMetadata = it.evObservationMetadata - "GEAR_SELECTION", ignitionState = 1)
        }
        val gate = EvFreshParkGate()
        assertTrue(gate.observe(withoutPark, 50_000)) // baseline ignition fallback
        assertFalse(gate.rawParkAuthorization(50_000))
        gate.observe(sample(51_000, 4, state = 3), 51_000)
        assertTrue(gate.rawParkAuthorization(51_000))
        assertFalse(gate.rawParkAuthorization(81_000))
        capture.vehicle(queue, binding, withoutPark, 50_000, 20_000)
        val rows = read(queue.pending().single()).second
        assertEquals("5000", rows.last().getValue("speedCoveredMs").jsonPrimitive.content)
        assertEquals("100", rows.last().getValue("distanceM").jsonPrimitive.content)
        assertFalse(rows.last().containsKey("batteryWh"))
    }

    @Test fun mixedVhalBatchesDoNotShortenCoveredSpeedOrDistance() {
        fun record(mixed: Boolean): List<Pair<Long, Long>> {
            val queue = EvContributionQueue(folder.newFolder())
            val binding = EvContributionConsentBinding.createAccuracy(url, token)!!
            val capture = EvAccuracyCapture { "drive" }
            val first = sample(40_000, 8, state = 3).copy(speedKmh = 36f)
            capture.vehicle(queue, binding, first, 40_000, 10_000)
            if (mixed) {
                for (now in 41_000L..44_000L step 1_000L) {
                    val battery = sample(now, 8, state = 3).evObservationMetadata.getValue("EV_BATTERY_LEVEL")
                    // The battery changed; speed value and all its provenance are still cached.
                    val batch = first.copy(evBatteryLevelWh = 50_000f - (now - 40_000),
                        evObservationMetadata = first.evObservationMetadata + ("EV_BATTERY_LEVEL" to battery))
                    capture.vehicle(queue, binding, batch, now, now - 30_000)
                }
            }
            val next = sample(45_000, 8, state = 3).copy(speedKmh = 108f)
            capture.vehicle(queue, binding, next, 45_000, 15_000)
            capture.close(queue, 16_000, EvAccuracyClosure.BOUNDARY)
            return read(queue.pending().single()).second.map { row ->
                row.getValue("speedCoveredMs").jsonPrimitive.content.toLong() to
                    row.getValue("distanceM").jsonPrimitive.content.toLong()
            }
        }
        val speedOnly = record(mixed = false)
        assertEquals(listOf(0L to 0L, 5_000L to 100L), speedOnly)
        assertEquals(speedOnly, record(mixed = true))
    }

    @Test fun invalidSpeedBreaksContinuityAndCachedReplayCannotRestoreIt() {
        val key = "PERF_VEHICLE_SPEED"
        val first = sample(40_000, 8, state = 3)
        val obs = first.evObservationMetadata.getValue(key)
        val breaks = mapOf(
            "missing speed observation" to first.copy(evObservationMetadata = first.evObservationMetadata - key),
            "unavailable speed" to first.copy(evObservationMetadata = first.evObservationMetadata + (key to obs.copy(status = 1))),
            "conflicting duplicate" to first.copy(speedKmh = 36f),
            "regressed event" to first.copy(evObservationMetadata = first.evObservationMetadata +
                (key to obs.copy(timestampElapsedNanos = 39_999_000_000L, receivedElapsedMs = 41_000, sequence = 41_000))),
        )
        val binding = EvContributionConsentBinding.createAccuracy(url, token)!!
        for ((reason, loss) in breaks) {
            val queue = EvContributionQueue(folder.newFolder())
            val capture = EvAccuracyCapture { "drive" }
            capture.vehicle(queue, binding, first, 40_000, 10_000)
            capture.vehicle(queue, binding, loss, 41_000, 11_000)
            capture.vehicle(queue, binding, first, 42_000, 12_000) // Replay from before the break.
            capture.vehicle(queue, binding, sample(45_000, 8, state = 3), 45_000, 15_000)
            capture.vehicle(queue, binding, sample(50_000, 8, state = 3), 50_000, 20_000)
            capture.close(queue, 21_000, EvAccuracyClosure.BOUNDARY)
            val rows = read(queue.pending().single()).second
            assertEquals(reason, listOf("0", "0", "5000"), rows.map { it.getValue("speedCoveredMs").jsonPrimitive.content })
            assertEquals(reason, listOf("0", "0", "100"), rows.map { it.getValue("distanceM").jsonPrimitive.content })
        }
    }

    @Test fun neitherAnOverlongSpeedIntervalNorACachedSampleAcrossAWindowBoundaryAddsCoverage() {
        val queue = EvContributionQueue(folder.newFolder())
        val binding = EvContributionConsentBinding.createAccuracy(url, token)!!
        var next = 0
        val capture = EvAccuracyCapture { "drive-${++next}" }
        fun tick(now: Long, data: ControlMessage.VehicleData = sample(now, 8, state = 3)) =
            capture.vehicle(queue, binding, data, now, now - 30_000)
        val key = "PERF_VEHICLE_SPEED"
        val first = sample(40_000, 8, state = 3)
        tick(40_000, first)
        tick(44_000, first) // The cached speed is still fresh, but the next event is too far from the first.
        tick(45_001)
        tick(50_001)
        tick(70_000, sample(70_000, 8, state = 3).let { reopened ->
            val cached = sample(69_000, 8, state = 3).evObservationMetadata.getValue(key)
            reopened.copy(evObservationMetadata = reopened.evObservationMetadata + (key to cached))
        })
        tick(74_000) // The first new event cannot bridge back to a sample before the window opened.
        tick(75_000)
        capture.close(queue, 46_000, EvAccuracyClosure.BOUNDARY)
        val units = queue.pending()
        assertEquals(2, units.size)
        val (before, after) = units.map(::read)
        assertTrue(before.first.contains("closure=gap\n"))
        assertEquals(listOf("0", "0", "5000"), before.second.map { it.getValue("speedCoveredMs").jsonPrimitive.content })
        assertEquals(listOf("0", "0", "100"), before.second.map { it.getValue("distanceM").jsonPrimitive.content })
        assertEquals(listOf("0", "1000"), after.second.map { it.getValue("speedCoveredMs").jsonPrimitive.content })
        assertEquals(listOf("0", "20"), after.second.map { it.getValue("distanceM").jsonPrimitive.content })
    }

    @Test fun clearingParkGateRevokesBothUploadAuthoritiesUntilFreshPark() {
        val gate = EvFreshParkGate()
        fun parkOnly(now: Long) = sample(now, 4).let {
            it.copy(evObservationMetadata = it.evObservationMetadata - "IGNITION_STATE")
        }
        assertTrue(gate.observe(parkOnly(40_000), 40_000))
        assertTrue(gate.rawParkAuthorization(40_000))
        gate.clear()
        assertFalse(gate.authorization(40_001))
        assertFalse(gate.rawParkAuthorization(40_001))
        assertTrue(gate.observe(parkOnly(40_002), 40_002))
        assertTrue(gate.rawParkAuthorization(40_002))
    }

    @Test fun stagedAcceptanceChecksActualZipDigestAndCannotUseLegacyNamespace() {
        val queue = EvContributionQueue(folder.newFolder())
        val binding = EvContributionConsentBinding.createAccuracy(url, token)!!
        val capture = EvAccuracyCapture { "charge" }
        capture.vehicle(queue, binding, sample(40_000, 4), 40_000, 10_000)
        capture.vehicle(queue, binding, sample(45_000, 4, state = 3), 45_000, 15_000)
        var sends = 0
        val uploader = EvContributionUploader(queue) { file, _ ->
            sends++
            val pending = queue.pending().single()
            assertEquals(file, pending.file)
            assertTrue(read(pending).first.startsWith("schema=3\n"))
            EvContributionUploader.Response(200, "{\"ok\":true,\"sha256\":\"${sha256(file.readBytes())}\"}")
        }
        assertEquals(EvContributionUploader.Outcome.OWNER_MISMATCH, uploader.uploadOldest(15_000, sha256(token.toByteArray())))
        assertEquals(0, sends)
        assertEquals(EvContributionUploader.Outcome.ACCEPTED, uploader.uploadOldest(15_000, binding.captureNamespace))
        assertEquals(1, sends)
        assertTrue(queue.pending().isEmpty())
    }

    @Test fun noOldEventParkExceptionWithoutExactOnChangeRead() {
        fun oldPark(mode: Int, exact: Boolean) = sample(80_000, 4).let { data ->
            val key = "GEAR_SELECTION"
            data.copy(evObservationMetadata = data.evObservationMetadata + (key to
                data.evObservationMetadata.getValue(key).copy(timestampElapsedNanos = 1_000_000L,
                    changeMode = mode, statusReadElapsedMs = 80_000L.takeIf { exact })))
        }
        val gate = EvFreshParkGate()
        gate.observe(oldPark(mode = 0, exact = true), 80_000)
        assertFalse(gate.rawParkAuthorization(80_000))
        gate.observe(oldPark(mode = 1, exact = false), 80_000)
        assertFalse(gate.rawParkAuthorization(80_000))
        // Independent, fresh process gate for this exact old-event control.
        val confirmed = EvFreshParkGate()
        confirmed.observe(oldPark(mode = 1, exact = true), 80_000)
        assertTrue(confirmed.rawParkAuthorization(80_000))
        assertFalse(confirmed.rawParkAuthorization(110_000))
    }

    @Test fun recoveryMarksInterruptedDriveAndDoesNotCertifyAaForecastJoin() {
        val root = folder.newFolder()
        val queue = EvContributionQueue(root)
        val binding = EvContributionConsentBinding.createAccuracy(url, token)!!
        val capture = EvAccuracyCapture { "drive" }
        capture.vehicle(queue, binding, sample(40_000, 8, state = 3), 40_000, 10_000)
        capture.forecast(queue, binding, VehicleEnergyForecast(EnergyAtDistance(2200, 41_000, 180),
            receivedAtElapsedMs = 40_800))
        val reopened = EvContributionQueue(root)
        assertEquals(1, reopened.recoverInterrupted(20_000))
        val (manifest, lines) = read(reopened.pending().single())
        assertTrue(manifest.contains("closure=interrupted\n"))
        val forecast = lines.last()
        assertEquals("uncertain", forecast.getValue("correlation").jsonPrimitive.content)
        assertEquals("41000", forecast.getValue("forecastWh").jsonPrimitive.content)
        assertFalse(forecast.containsKey("arrivalConfirmed"))
        exportFixture("drive-v3.zip", reopened.pending().single())
    }

    @Test fun schema3RejectsUnlistedAndUnboundedFieldsBeforeWriting() {
        val queue = EvContributionQueue(folder.newFolder())
        val valid = """{"schema":3,"type":"charge","elapsedMs":0,"chargeState":1,"batteryWh":50000}"""
        for (extra in listOf("\"vin\":\"sentinel\"", "\"latitude\":42", "\"destinationHash\":\"bad\"", "\"chargeRateW\":9000000", "\"batteryAgeMs\":30001")) {
            assertThrows(IllegalArgumentException::class.java) {
                queue.append("charge", 10_000, valid.dropLast(1) + ",$extra}")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            queue.append("charge", 10_000, valid.replace("\"type\":", "\"type\":\"hidden-text\",\"type\":"))
        }
        assertEquals(0, queue.storageUsage().units)
        queue.append("charge", 10_000, valid)
        assertThrows(IllegalStateException::class.java) {
            queue.append("charge", 10_000, """{"schema":2,"type":"vehicle","batteryWh":50000}""")
        }
    }

    private fun read(unit: EvContributionQueue.Pending) = ZipFile(unit.file).use { zip ->
        assertEquals(setOf("manifest.log", "telemetry.log"), zip.entries().asSequence().map { it.name }.toSet())
        val manifest = zip.getInputStream(zip.getEntry("manifest.log")).bufferedReader().use { it.readText() }
        val rows = zip.getInputStream(zip.getEntry("telemetry.log")).bufferedReader().use { stream ->
            stream.readLines().map { Json.parseToJsonElement(it).jsonObject }
        }
        manifest to rows
    }

    private fun sample(now: Long, gear: Int, state: Int = 1, battery: Float = 50_000f) = ControlMessage.VehicleData(
        carMake = "Chevrolet", carModel = "Blazer EV", carYear = "2025", fuelTypes = listOf(10),
        gearRaw = gear, ignitionState = 4, speedKmh = 72f, batteryPct = 58,
        evBatteryLevelWh = battery, evBatteryCapacityWh = 85_000f, evCurrentBatteryCapacityWh = 76_000f,
        evChargeState = state, evChargeRateW = 34_000f, chargePortConnected = true,
        vhalRegistrationGeneration = 1L,
        evObservationMetadata = mapOf(
            "GEAR_SELECTION" to 0x11400400, "IGNITION_STATE" to 0x11400409,
            "EV_BATTERY_LEVEL" to 0x11600309, "INFO_EV_BATTERY_CAPACITY" to 0x11600106,
            "EV_CURRENT_BATTERY_CAPACITY" to 0x1160030D, "PERF_VEHICLE_SPEED" to 0x11600207,
            "EV_CHARGE_STATE" to 0x11400F41, "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to 0x1160030C,
        ).mapValues { (_, id) ->
            VehiclePropertyObservation(now * 1_000_000, now, 0, registrationGeneration = 1L,
                propertyId = id, subscriptionActive = true, sequence = now)
        },
    )
}
