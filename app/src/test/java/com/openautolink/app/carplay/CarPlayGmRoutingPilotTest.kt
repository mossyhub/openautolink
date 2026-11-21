package com.openautolink.app.carplay

import com.openautolink.app.data.CarPlaySettings
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.carplaylab.intake.OalCarDataIdentification
import com.openautolink.carplaylab.intake.OalIap2CarDataReporter
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 34], application = android.app.Application::class)
class CarPlayGmRoutingPilotTest {
    private var now = 170_000L
    private val propertyIds = mapOf("EV_BATTERY_LEVEL" to 0x11600309,
        "INFO_EV_BATTERY_CAPACITY" to 0x11600106, "RANGE_REMAINING" to 0x11600308,
        "EV_CHARGE_STATE" to 0x11400f41)
    private fun source() = ControlMessage.VehicleData(
        carMake = " Chevrolet ", carModel = " C234 ", carYear = "2025", fuelTypes = listOf(10),
        evConnectorTypes = listOf(1, 5), evBatteryLevelWh = 42_785f,
        evBatteryCapacityWh = 85_570f, rangeKm = 210f, evChargeState = 3,
        vhalRegistrationGeneration = 7, evObservationMetadata = propertyIds.mapValues { (_, id) ->
            VehiclePropertyObservation(1_000_000_000L, now, 0, registrationGeneration = 7,
                propertyId = id, subscriptionActive = true, changeMode = 2, statusReadElapsedMs = now)
        })
    // Deliberately use the existing saved settings API so this fixture can run against the predecessor.
    private val on = CarPlaySettings.decode("""{"evRoutingPilot":{"enabled":true,"profileId":"gm-routing-pilot"}}""").evRoutingPilot
    private val base = Iap2Messages.buildRaw(0x1D01) {
        string(0, "OpenAutoLink"); string(1, "OAL-native-CarPlay"); string(2, "OpenAutoLink")
        string(3, "accessory-serial"); u16List(6, emptyList()); u16List(7, emptyList())
    }
    private val request = Iap2Messages.buildRaw(0xA100) { void(3); void(11); void(21); void(30) }

    @Test fun savedOptInProducesRealGmNameAndAuthenticatedRoutingWithoutManualInputs() {
        val data = source()
        val adapter = OalCarPlayVehicleSnapshotAdapter({ MutableStateFlow(data) }, { now }, { on })
        val caps = adapter.capabilities(false)
        val offer = Iap2BodyReader.of(OalCarDataIdentification.apply(base, caps))
        for (id in 0..3) assertArrayEquals(Iap2BodyReader.of(base).raw(id), offer.raw(id))
        val info = offer.group(20)
        assertFalse("No verified MY25 connector rating in this pilot", info.has(12))
        assertFalse("No verified MY25 connector rating in this pilot", info.has(14))
        val name = "2025 Chevrolet C234\u0000".toByteArray()
        for (id in listOf(1, 6, 8)) assertArrayEquals(name, info.raw(id))
        assertFalse("No VIN", info.has(7))
        assertEquals(listOf(0, 2), info.list().filter { it.id == 11 }.map { it.payload.single().toInt() })
        val model = requireNotNull(caps.evRoutingModel) { "Expected the supported GM routing model with no manual settings" }
        val writes = mutableListOf<Iap2Frame>()
        OalIap2CarDataReporter(caps, { now }, { null }, adapter::snapshot, {}, writes::add, {}).use { reporter ->
            reporter.handle(request)
            assertTrue(writes.isEmpty())
            reporter.authenticate()
            reporter.handle(request)
            val body = Iap2BodyReader.of(writes.single().also { assertEquals(0xA101, it.messageId) })
            assertEquals(setOf(3, 11, 21, 22, 23, 24, 25, 26, 27, 30), body.list().map { it.id }.toSet())
            assertEquals(210, body.u16(11))
            assertEquals(42_785L, body.u32(22))
            assertEquals(85_570L, body.u32(23))
            assertEquals(50_000L, body.u32(24))
            assertEquals(0, body.u8(25))
            assertEquals(420, body.u16(30))
            for ((id, json) in listOf(26 to model.chargingJson, 27 to model.consumptionJson)) {
                assertArrayEquals((json + '\u0000').toByteArray(), body.raw(id))
            }
            val powers = JSONObject(model.chargingJson).getJSONObject("vehicle_parameters").getJSONArray("vehicle_charge_axis_w")
            for (index in 0 until powers.length()) assertEquals(100_637, powers.getInt(index))
        }
    }

    @Test fun confirmed2024RsAwdPackUsesItsOwnApproximationAndDetectedName() {
        val data = source().copy(carYear = "2024", evBatteryLevelWh = 34_190f,
            evBatteryCapacityWh = 83_130f, rangeKm = 180f)
        val adapter = OalCarPlayVehicleSnapshotAdapter({ MutableStateFlow(data) }, { now }, { on })
        val caps = adapter.capabilities(false)
        val info = Iap2BodyReader.of(OalCarDataIdentification.apply(base, caps)).group(20)
        for (id in listOf(6, 8)) assertEquals("2024 Chevrolet C234", info.optionalString(id))
        assertEquals(150_000L, info.u32(12))
        assertEquals(11_500L, info.u32(14))
        assertNotNull("MY24 small-pack pilot must admit the confirmed RS AWD family", caps.evRoutingModel)
        val writes = mutableListOf<Iap2Frame>()
        OalIap2CarDataReporter(caps, { now }, { null }, adapter::snapshot, {}, writes::add, {}).use { reporter ->
            reporter.authenticate(); reporter.handle(request)
            val body = Iap2BodyReader.of(writes.single().also { assertEquals(0xA101, it.messageId) })
            assertEquals(34_190L, body.u32(22))
            assertEquals(83_130L, body.u32(23))
            val powers = JSONObject(caps.evRoutingModel!!.chargingJson)
                .getJSONObject("vehicle_parameters").getJSONArray("vehicle_charge_axis_w")
            for (index in 0 until powers.length()) assertEquals(89_395, powers.getInt(index))
            assertArrayEquals((caps.evRoutingModel!!.chargingJson + '\u0000').toByteArray(), body.raw(26))
            assertArrayEquals((caps.evRoutingModel!!.consumptionJson + '\u0000').toByteArray(), body.raw(27))
        }
        assertNull(OalCarPlayVehicleSnapshotAdapter({ MutableStateFlow(data.copy(evBatteryCapacityWh = 102_000f)) },
            { now }, { on }).capabilities(false).evRoutingModel)
    }

    @Test fun explicitOffAndUnmatchedOrStaleSourcesDoNotOfferPilotModels() {
        val good = source()
        fun caps(data: ControlMessage.VehicleData, setting: CarPlayEvPilotConfiguration = on) =
            OalCarPlayVehicleSnapshotAdapter({ MutableStateFlow(data) }, { now }, { setting }).capabilities(false)
        for (setting in listOf(null, CarPlaySettings(evDataEnabled = false),
            CarPlaySettings.decode("""{"evDataEnabled":"true"}"""), CarPlaySettings(evRoutingPilot = on, evDataEnabled = false))) {
            val policy = OalCarPlayVehicleSnapshotAdapter.savedPilot(setting)
            val offer = caps(good, policy)
            assertNull(offer.evRoutingModel)
            assertFalse(offer.maximumRange)
            assertFalse(offer.range)
            assertFalse(offer.currentEnergy)
            assertFalse("No reported maximum expansion", offer.maximumEnergy)
            val disabledAdapter = OalCarPlayVehicleSnapshotAdapter({ MutableStateFlow(good) }, { now }, { policy })
            val disabled = disabledAdapter.capabilities(true)
            val disabledOffer = Iap2BodyReader.of(OalCarDataIdentification.apply(base, disabled))
            assertFalse(disabledOffer.has(20)); assertFalse(disabledOffer.has(21)); assertTrue(disabledOffer.has(22))
            assertNull(disabledAdapter.snapshot())
            OalIap2CarDataReporter(disabled, { now }, { null }, disabledAdapter::snapshot, {},
                { fail("Disabled EV plane emitted a frame") }, {}).use { reporter ->
                reporter.authenticate(); reporter.handle(request); reporter.tick()
            }
        }
        assertNotNull("Fresh saved settings retain the qualified GM preset", caps(good,
            OalCarPlayVehicleSnapshotAdapter.savedPilot(CarPlaySettings.decode(null))).evRoutingModel)
        val unmatched = good.copy(carModel = "Different model")
        assertEquals("2025 Chevrolet Different model", caps(unmatched).vehicleDisplayName)
        assertTrue("On with unmatched variant retains regular sourced status", caps(unmatched).range)
        for (bad in listOf(unmatched, good.copy(carYear = null), good.copy(carYear = "2023"), good.copy(carYear = "2026"),
            good.copy(evBatteryCapacityWh = 102_000f), good.copy(evConnectorTypes = listOf(6)),
            good.copy(carMake = null), good.copy(carModel = "n/a"), good.copy(fuelTypes = listOf(1, 10)),
            good.copy(evObservationMetadata = good.evObservationMetadata - "INFO_EV_BATTERY_CAPACITY"))) {
            assertNull(caps(bad).evRoutingModel)
            assertFalse(caps(bad).maximumRange)
        }
        assertEquals("Chevrolet C234", caps(good.copy(carYear = "9999")).vehicleDisplayName)
        for (missing in listOf(good.copy(carMake = null), good.copy(carModel = "n/a"), good.copy(vhalRegistrationGeneration = null))) {
            val body = Iap2BodyReader.of(OalCarDataIdentification.apply(base, caps(missing)))
            if (body.has(20)) {
                val info = body.group(20)
                assertFalse(info.has(6)); assertFalse(info.has(8)); assertFalse(info.has(7))
                assertFalse(info.optionalString(1)?.contains("OAL") == true)
            }
        }
        now += 30_001L
        assertNull(caps(good).evRoutingModel)
    }

    @Test fun negotiatedOwnerWithholdsPilotFieldsOnSourceLossAndNewConnectionsReadSavedToggle() {
        val flow = MutableStateFlow(source())
        var saved = on
        fun adapter() = OalCarPlayVehicleSnapshotAdapter({ flow }, { now }, { saved })
        val live = adapter()
        val caps = live.capabilities(false)
        val model = requireNotNull(caps.evRoutingModel)
        val writes = mutableListOf<Iap2Frame>()
        OalIap2CarDataReporter(caps, { now }, { null }, live::snapshot, {}, writes::add, {}).use { reporter ->
            reporter.authenticate(); reporter.handle(request)
            assertEquals(1, writes.size)
            saved = on.copy(enabled = false)
            assertNull(adapter().capabilities(false).evRoutingModel)
            assertSame("Saved choice is next-connection only", model, live.snapshot()?.routingModel)
            now += 30_001L
            reporter.tick()
            assertEquals("An expired source cannot resend the cached frame", 1, writes.size)
            val replacement = source().copy(vhalRegistrationGeneration = 8,
                evObservationMetadata = source().evObservationMetadata.mapValues { (_, value) -> value.copy(registrationGeneration = 8) })
            flow.value = replacement
            now += 30_000L
            flow.value = replacement.copy(evObservationMetadata = replacement.evObservationMetadata.mapValues { (_, value) ->
                value.copy(receivedElapsedMs = now, statusReadElapsedMs = now) })
            reporter.tick()
            assertEquals(2, writes.size)
            val body = Iap2BodyReader.of(writes.last())
            assertTrue(body.has(22))
            for (id in listOf(21, 23, 24, 25, 26, 27, 30)) assertFalse("old owner field $id", body.has(id))
        }
    }
}
