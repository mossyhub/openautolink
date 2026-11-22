package com.openautolink.app.carplay

import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.transport.ControlMessage.VehicleData
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.carplaylab.intake.OalCarDataIdentification
import com.openautolink.carplaylab.intake.OalIap2CarDataReporter
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AppleMapsEvWideRuntimeTest {
    private var now = 170_000L
    private val request = Iap2Messages.buildRaw(0xA100) { void(3); void(4); void(11); void(21); void(30) }
    private val identify = Iap2Messages.buildRaw(0x1D01) { u16List(6, emptyList()); u16List(7, emptyList()) }
    private val propertyIds = mapOf(
        "EV_BATTERY_LEVEL" to 0x11600309,
        "EV_CURRENT_BATTERY_CAPACITY" to 0x1160030D,
        "INFO_EV_BATTERY_CAPACITY" to 0x11600106,
        "RANGE_REMAINING" to 0x11600308,
        "ENV_OUTSIDE_TEMPERATURE" to 0x11600703,
        "EV_CHARGE_STATE" to 0x11400f41,
    )

    private fun sample(generation: Long = 7) = VehicleData(
        carMake = "Other Brand", carModel = "Other Model", carYear = "2026", fuelTypes = listOf(10),
        evConnectorTypes = listOf(2, 6), vhalRegistrationGeneration = generation,
        evBatteryLevelWh = 30_000f, evBatteryCapacityWh = 65_000f, evCurrentBatteryCapacityWh = 60_000f,
        rangeKm = 150f, ambientTempC = 18f, evChargeState = 3,
        evObservationMetadata = propertyIds.mapValues { (_, id) ->
            VehiclePropertyObservation((now - 500) * 1_000_000, now, 0, registrationGeneration = generation,
                propertyId = id, subscriptionActive = true, changeMode = 2)
        },
    )

    private fun settings(average: String) = CarPlaySettings.decode(
        """{"evDataEnabled":true,"evAverageDcChargeKw":"$average"}""",
    )

    private fun adapter(flow: MutableStateFlow<VehicleData>, saved: () -> CarPlaySettings?) =
        OalCarPlayVehicleSnapshotAdapter({ flow }, { now }, { OalCarPlayVehicleSnapshotAdapter.savedPilot(saved()) })

    private fun output(adapter: OalCarPlayVehicleSnapshotAdapter): Pair<Iap2Frame, Iap2Frame> {
        val caps = adapter.capabilities(false)
        val writes = mutableListOf<Iap2Frame>()
        OalIap2CarDataReporter(caps, { now }, { null }, adapter::snapshot, {}, writes::add, {}).use {
            it.handle(request)
            assertTrue("No pre-authentication write", writes.isEmpty())
            it.authenticate()
            it.handle(request)
        }
        return OalCarDataIdentification.apply(identify, caps) to writes.single().also { assertEquals(0xA101, it.messageId) }
    }

    @Test fun unconfiguredNonBlazerSharesSourcedStatusWithFallbackModelAndNoInventedRatings() {
        val (offer, status) = output(adapter(MutableStateFlow(sample())) { CarPlaySettings.decode(null) })
        val info = Iap2BodyReader.of(offer).group(20)
        assertEquals("2026 Other Brand Other Model", info.optionalString(8))
        assertEquals(listOf(1, 4), info.list().filter { it.id == 11 }.map { it.payload.single().toInt() })
        for (id in listOf(7, 12, 14)) assertFalse("Unsourced Identify field $id", info.has(id))
        val body = Iap2BodyReader.of(status)
        assertEquals(150, body.u16(11))
        assertEquals(30_000L, body.u32(22))
        assertEquals(60_000L, body.u32(23))
        assertTrue(body.has(26) && body.has(27))
        assertEquals(89_395, firstChargingWatts(body))
    }

    private fun firstChargingWatts(body: Iap2BodyReader) = JSONObject(body.string(26))
        .getJSONObject("vehicle_parameters").getJSONArray("vehicle_charge_axis_w").getInt(0)

    @Test fun otherYearsAndUnknownOrUnmatchedIdentityUseTheSameFallback() {
        for (data in listOf(
            sample().copy(carMake = "Chevrolet", carModel = "C234", carYear = "2025",
                evBatteryCapacityWh = 85_000f, evConnectorTypes = listOf(1, 5)),
            sample().copy(carMake = "Chevrolet", carModel = "Equinox", carYear = "2026"),
            sample().copy(carMake = null, carModel = null, carYear = null),
        )) {
            val (offer, status) = output(adapter(MutableStateFlow(data)) { settings("") })
            assertEquals(89_395, firstChargingWatts(Iap2BodyReader.of(status)))
            val info = Iap2BodyReader.of(offer).group(20)
            assertFalse(info.has(12)); assertFalse(info.has(14))
            assertTrue("Sourced range capability without a preset", Iap2BodyReader.of(offer).group(21).has(30))
            assertEquals(300, Iap2BodyReader.of(status).u16(30))
        }
    }

    @Test fun derivedFullRangeIsWithheldBelowMinimumChargeOrOnLossOfQualifiedRange() {
        val flow = MutableStateFlow(sample().copy(evBatteryLevelWh = 11_999f))
        val adapter = adapter(flow) { settings("") }
        val caps = adapter.capabilities(false)
        assertTrue(caps.maximumRange)
        val sent = mutableListOf<Iap2Frame>()
        OalIap2CarDataReporter(caps, { now }, { null }, adapter::snapshot, {}, sent::add, {}).use { reporter ->
            reporter.authenticate()
            reporter.handle(request)
            assertFalse(Iap2BodyReader.of(sent.single()).has(30))
            now += 30_000
            flow.value = sample()
            reporter.tick()
            assertEquals(300, Iap2BodyReader.of(sent.last()).u16(30))
            now += 30_000
            val next = sample()
            flow.value = next.copy(evObservationMetadata = next.evObservationMetadata - "RANGE_REMAINING")
            reporter.tick()
            assertEquals(3, sent.size)
            assertFalse(Iap2BodyReader.of(sent.last()).has(30))
        }
    }

    @Test fun explicitOffAndFailedPreferenceReadKeepTemperatureAndLocationIndependent() {
        val disabledRecords = listOf(null, CarPlaySettings.decode("""{"evDataEnabled":false,"evAverageDcChargeKw":"73.125"}"""),
            CarPlaySettings.decode("""{"evDataEnabled":"true","evAverageDcChargeKw":"73.125"}"""))
        for (saved in disabledRecords) {
            val adapter = adapter(MutableStateFlow(sample())) { saved }
            val caps = adapter.capabilities(true)
            val offer = Iap2BodyReader.of(OalCarDataIdentification.apply(identify, caps))
            assertTrue(offer.has(22))
            assertEquals(listOf(0, 1, 4), offer.group(21).list().map { it.id })
            assertFalse(offer.group(20).has(8))
            assertFalse(offer.group(20).has(11))
            val sent = mutableListOf<Iap2Frame>()
            OalIap2CarDataReporter(caps, { now }, { null }, adapter::snapshot, {}, sent::add, {}).use {
                it.authenticate()
                it.handle(Iap2Messages.buildRaw(0xA100) { void(3); void(11); void(21) })
                assertTrue(sent.isEmpty())
                it.handle(request)
            }
            val status = Iap2BodyReader.of(sent.single())
            assertEquals(listOf(4), status.list().map { it.id })
            assertEquals(18, status.i16(4))
        }
    }

    @Test fun deliberateNonGmEstimateChangesOnlyChargingWattsAndUsesSourcedCapacityAndConsumption() {
        fun send(average: String) = output(adapter(MutableStateFlow(sample())) { settings(average) })
        val (aOffer, aFrame) = send("73.125")
        val (bOffer, bFrame) = send("91.5")
        assertArrayEquals(aOffer.encodedFrame(), bOffer.encodedFrame())
        val a = Iap2BodyReader.of(aFrame)
        val b = Iap2BodyReader.of(bFrame)
        assertEquals(a.list().map { it.id }, b.list().map { it.id })
        assertTrue("Both model TLVs must be present", a.has(26) && a.has(27))
        for (param in a.list().filterNot { it.id == 26 }) assertArrayEquals(param.payload, b.raw(param.id))
        assertEquals(60_000L, a.u32(23))
        assertEquals(50_000L, a.u32(24))
        fun charging(body: Iap2BodyReader) = JSONObject(body.string(26)).getJSONObject("vehicle_parameters")
        val baseline = charging(a)
        val revised = charging(b)
        for ((params, watts) in listOf(baseline to 73_125, revised to 91_500)) {
            val power = params.getJSONArray("vehicle_charge_axis_w")
            assertEquals(11, power.length())
            for (i in 0 until power.length()) assertEquals(watts, power.getInt(i))
            assertEquals(60_000, params.getJSONArray("vehicle_energy_axis_wh").getInt(10))
        }
        baseline.remove("vehicle_charge_axis_w"); revised.remove("vehicle_charge_axis_w")
        assertEquals(baseline.toString(), revised.toString())
        val rates = JSONObject(a.string(27)).getJSONObject("vehicle_parameters")
            .getJSONArray("vehicle_consumption_values_wh_per_m")
        assertEquals(10, rates.length())
        for (i in 0 until rates.length()) assertEquals(0.2, rates.getDouble(i), 0.0000001)
    }

    @Test fun validReportedCapacityFallbackDoesNotRescueAnIncoherentOrStaleSource() {
        val good = sample().copy(evCurrentBatteryCapacityWh = null)
        fun send(data: VehicleData) = Iap2BodyReader.of(output(adapter(MutableStateFlow(data)) { settings("73.125") }).second)
        val positive = send(good)
        assertEquals(65_000L, positive.u32(23))
        assertTrue(positive.has(26) && positive.has(27))
        val reported = good.evObservationMetadata.getValue("INFO_EV_BATTERY_CAPACITY")
        val range = good.evObservationMetadata.getValue("RANGE_REMAINING")
        for (data in listOf(
            good.copy(evBatteryLevelWh = 65_001f),
            good.copy(fuelTypes = listOf(1, 10)),
            good.copy(rangeKm = 0f),
            good.copy(evObservationMetadata = good.evObservationMetadata - "INFO_EV_BATTERY_CAPACITY"),
            good.copy(evObservationMetadata = good.evObservationMetadata + ("INFO_EV_BATTERY_CAPACITY" to reported.copy(registrationGeneration = 6))),
            good.copy(evObservationMetadata = good.evObservationMetadata + ("RANGE_REMAINING" to range.copy(receivedElapsedMs = now - 30_001, timestampElapsedNanos = (now - 30_001) * 1_000_000))),
        )) {
            val rejected = send(data)
            assertEquals(18, rejected.i16(4)) // Do not accept an inert reporter as a negative.
            assertFalse(rejected.has(26)); assertFalse(rejected.has(27))
        }
    }

    @Test fun activeLinkKeepsFrozenModelUntilSourceOwnerChangesAndNewLinkUsesSavedEdit() {
        val first = MutableStateFlow(sample())
        var current = first
        var saved = settings("73.125")
        fun open() = OalCarPlayVehicleSnapshotAdapter({ current }, { now }, { OalCarPlayVehicleSnapshotAdapter.savedPilot(saved) })
        val adapter = open()
        val caps = adapter.capabilities(false)
        val writes = mutableListOf<Iap2Frame>()
        OalIap2CarDataReporter(caps, { now }, { null }, adapter::snapshot, {}, writes::add, {}).use { reporter ->
            reporter.authenticate(); reporter.handle(request)
            val original = Iap2BodyReader.of(writes.single())
            assertTrue(original.has(26) && original.has(27))
            saved = settings("91.5")
            now += 30_000
            first.value = sample()
            reporter.tick()
            assertEquals(2, writes.size)
            assertArrayEquals(writes[0].encodedFrame(), writes[1].encodedFrame())

            now += 30_000
            // Same generation and identity on a *different* producer must not inherit this model.
            current = MutableStateFlow(sample())
            reporter.tick()
            assertEquals(3, writes.size)
            val replacement = Iap2BodyReader.of(writes.last())
            assertEquals(18, replacement.i16(4))
            assertFalse(replacement.has(26)); assertFalse(replacement.has(27))
            val newLink = Iap2BodyReader.of(output(open()).second)
            assertNotEquals(original.string(26), newLink.string(26))
            assertArrayEquals(original.raw(27), newLink.raw(27))
        }
    }

    @Test fun exactOwnerBlazerAndLegacyMy25EngineeringPilotRetainIndependent599WireBytes() {
        now = 1000
        // Reproduce the separately compiled 599 HostDefaultWireProbe, including metadata shape.
        // A101 goldens use Android JSONObject insertion order within TLVs 26/27, not the host
        // probe's map iteration order. Every other byte matches the archived predecessor frames.
        val declared = listOf("INFO_EV_BATTERY_CAPACITY", "EV_CURRENT_BATTERY_CAPACITY", "EV_BATTERY_LEVEL",
            "RANGE_REMAINING", "FUEL_LEVEL_LOW", "EV_CHARGE_STATE", "ENV_OUTSIDE_TEMPERATURE")
        val gm24 = VehicleData(carMake = "Chevrolet", carModel = "C234", carYear = "2024", fuelTypes = listOf(10),
            evConnectorTypes = listOf(1, 5), vhalRegistrationGeneration = 3, evBatteryCapacityWh = 85_000f,
            evBatteryLevelWh = 40_000f, rangeKm = 213.2f, evLowRangeWarning = false, evChargeState = 3, ambientTempC = 18f,
            evObservationMetadata = declared.associateWith { prop ->
                VehiclePropertyObservation(now * 1_000_000, now, 0, registrationGeneration = 3,
                    propertyId = 0x11200405.takeIf { prop == "FUEL_LEVEL_LOW" }, subscriptionActive = true)
            })
        val gm25 = gm24.copy(carYear = "2025", evCurrentBatteryCapacityWh = 75_005f)
        fun sha(frame: Iap2Frame) = MessageDigest.getInstance("SHA-256").digest(frame.encodedFrame())
            .joinToString("") { "%02x".format(it) }
        for ((data, expectedIdentify, expectedStatus) in listOf(
            Triple(gm24, "6c7aaed497014d414a0d3d3b75283eaeb65b1fe8a41702591d8e615996d3875b",
                "b0e2ebedc3a6625fdb9bda9e1150c021f3ea82d62e37d1972edc44bb3b632c8d"),
            Triple(gm25, "f506ca3124f4dd4183032dedb6f21d3ad4b72733c6e7dafa3d5a1d2586dfb902",
                "6bc3dc682d80083f8da775a90128decc79bfc5f647ef5cf83d4b39eb9df23c26"),
        )) {
            val source = MutableStateFlow(data)
            val testedAdapter = if (data.carYear == "2025") {
                // Keep the old MY25 wire baseline for the engineering pilot, not normal saved sharing.
                OalCarPlayVehicleSnapshotAdapter({ source }, { now }, {
                    CarPlayEvPilotConfiguration(enabled = true, profileId = CarPlayEvPilotConfiguration.GM_ROUTING)
                })
            } else adapter(source) { settings("") }
            val (offer, status) = output(testedAdapter)
            assertEquals(data.carYear, expectedIdentify, sha(offer))
            assertEquals(data.carYear, expectedStatus, sha(status))
        }
    }
}
