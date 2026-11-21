package com.openautolink.app.carplay

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.carplaylab.intake.OalCarDataIdentification
import com.openautolink.carplaylab.intake.OalIap2CarDataReporter
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tests-only 589 packet: deliberately uses only APIs already present in frozen 588. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class OalCarPlayEvDonorParity589Test {
    private var now = 170000L
    private val ids = mapOf(
        "EV_BATTERY_LEVEL" to 0x11600309,
        "INFO_EV_BATTERY_CAPACITY" to 0x11600106,
        "EV_CURRENT_BATTERY_CAPACITY" to 0x1160030d,
        "RANGE_REMAINING" to 0x11600308,
        "EV_CHARGE_STATE" to 0x11400f41,
        "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to 0x1160030c,
        "EV_CHARGE_PORT_CONNECTED" to 0x1120030b,
    )
    private fun observation(property: String) = VehiclePropertyObservation(
        timestampElapsedNanos = 1000000000L, receivedElapsedMs = now, status = 0,
        registrationGeneration = 7, propertyId = ids.getValue(property), subscriptionActive = true,
        changeMode = 2, statusReadElapsedMs = now,
    )
    private fun source() = ControlMessage.VehicleData(
        carMake = "Chevrolet", carModel = "C234", fuelTypes = listOf(10),
        evBatteryLevelWh = 42950f, evBatteryCapacityWh = 85900f, rangeKm = 215f,
        evChargeState = 3, evChargeRateW = 21000f, chargePortConnected = true,
        vhalRegistrationGeneration = 7,
        evObservationMetadata = ids.keys.associateWith(::observation),
    )
    private fun config() = CarPlayEvPilotConfiguration(
        enabled = true, profileId = CarPlayEvPilotConfiguration.GM_SOURCE, averageChargeKw = "90",
    )
    private fun fresh(data: ControlMessage.VehicleData) = data.copy(
        evObservationMetadata = data.evObservationMetadata.mapValues { (_, o) ->
            o.copy(receivedElapsedMs = now, statusReadElapsedMs = now)
        },
    )
    private class Checks {
        private val failures = mutableListOf<String>()
        fun equal(label: String, expected: Any?, actual: Any?) {
            if (expected != actual) failures += "$label: expected <$expected>, got <$actual>"
        }
        fun finish() = assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
    private inner class Link(initial: ControlMessage.VehicleData, selected: CarPlayEvPilotConfiguration = config()) : AutoCloseable {
        val flow = MutableStateFlow(initial)
        val receipts = mutableListOf<String>()
        val adapter = OalCarPlayVehicleSnapshotAdapter({ flow }, { now }, { selected })
        val caps = adapter.capabilities(false, receipts::add)
        val sent = mutableListOf<Iap2Frame>()
        val reporter = OalIap2CarDataReporter(caps, { now }, { null }, { adapter.snapshot() }, {}, sent::add, receipts::add)
        fun start() {
            val request = Iap2Messages.buildRaw(0xA100) { void(3); void(11); void(21) }
            reporter.handle(request)
            assertTrue("Identification/start alone is not authentication", sent.isEmpty())
            reporter.authenticate()
            reporter.handle(request)
            assertEquals(1, sent.size)
        }
        fun update(data: ControlMessage.VehicleData): Iap2BodyReader {
            now += 30000
            flow.value = fresh(data)
            val before = sent.size
            reporter.tick()
            assertEquals("A fresh remaining source must still deliver", before + 1, sent.size)
            return body()
        }
        fun body(): Iap2BodyReader {
            assertEquals(0xA101, sent.last().messageId)
            return Iap2BodyReader.of(sent.last())
        }
        override fun close() = reporter.close()
    }

    @Test fun benignReportedCapacityMovementKeepsFrozenModelsButTrueSourceLossStillVetoes() {
        val checks = Checks()
        val original = source()
        Link(original).use { link ->
            val model = link.caps.evRoutingModel
            assertNotNull("Positive source/configuration control", model)
            assertEquals(85900, model!!.pilotCapacityWh)
            link.start()
            val charging = link.body().raw(26)
            val consumption = link.body().raw(27)
            assertArrayEquals(model.chargingJson.toByteArray(Charsets.UTF_8) + byteArrayOf(0), charging)
            assertArrayEquals(model.consumptionJson.toByteArray(Charsets.UTF_8) + byteArrayOf(0), consumption)
            for (capacity in listOf(85910f, 85890f)) {
                val body = link.update(original.copy(evBatteryCapacityWh = capacity))
                checks.equal("$capacity frozen owner retained", true, link.adapter.snapshot()?.routingModel === model)
                checks.equal("$capacity exact model field presence", setOf(26, 27), body.list().map { it.id }.filter { it in setOf(26, 27) }.toSet())
                checks.equal("$capacity charging bytes unchanged", charging.toList(), body.optionalRaw(26)?.toList())
                checks.equal("$capacity consumption bytes unchanged", consumption.toList(), body.optionalRaw(27)?.toList())
                assertEquals(42950L, body.u32(22))
                assertEquals(215, body.u16(11))
            }
            val info = original.evObservationMetadata.getValue("INFO_EV_BATTERY_CAPACITY")
            val denied = listOf(
                original.copy(evBatteryCapacityWh = null), original.copy(evBatteryCapacityWh = Float.NaN),
                original.copy(evBatteryCapacityWh = 0f), original.copy(evBatteryCapacityWh = 250001f),
                original.copy(evObservationMetadata = original.evObservationMetadata - "INFO_EV_BATTERY_CAPACITY"),
                original.copy(carModel = "different-source"), original.copy(fuelTypes = listOf(1, 10)),
                original.copy(vhalRegistrationGeneration = 8),
                // Even a coherent new registration is not the frozen model's owner.
                original.copy(vhalRegistrationGeneration = 8, evObservationMetadata = original.evObservationMetadata.mapValues { it.value.copy(registrationGeneration = 8) }),
            ) + listOf(
                info.copy(source = "history"), info.copy(status = 1), info.copy(subscriptionActive = false),
                info.copy(registrationGeneration = 6), info.copy(propertyId = ids.getValue("EV_BATTERY_LEVEL")),
            ).map { original.copy(evObservationMetadata = original.evObservationMetadata + ("INFO_EV_BATTERY_CAPACITY" to it)) }
            for ((index, deniedSource) in denied.withIndex()) {
                now += 30000
                link.flow.value = fresh(deniedSource)
                checks.equal("source denial $index vetoes models", null, link.adapter.snapshot()?.routingModel)
                val before = link.sent.size
                link.reporter.tick()
                if (link.sent.size > before) {
                    checks.equal("source denial $index wire model fields absent", emptySet<Int>(), link.body().list().map { it.id }.filter { it in setOf(26, 27) }.toSet())
                }
            }
            link.flow.value = fresh(original)
            checks.equal("same-owner recovery", true, link.adapter.snapshot()?.routingModel === model)
            link.flow.value = fresh(original.copy(evObservationMetadata = emptyMap()))
            val before = link.sent.size
            now += 30000
            link.reporter.tick()
            assertEquals("Total source loss cannot resend held models", before, link.sent.size)
            link.flow.value = fresh(original)
            link.reporter.close()
            link.reporter.authenticate()
            link.reporter.handle(Iap2Messages.buildRaw(0xA100) { void(21) })
            link.reporter.tick()
            assertEquals("Retirement is permanent for the exact owner", before, link.sent.size)
        }
        checks.finish()
    }

    @Test fun qualifiedGmPilotOffersAndSendsDonorStatusWithoutManualOrInvalidSourcePromotion() {
        val checks = Checks()
        val original = source()
        Link(original).use { link ->
            checks.equal("Reported INFO is an explicitly scoped wire maximum", true, link.caps.maximumEnergy)
            checks.equal("Existing maxChargeWh API carries qualified reported basis", 85900L, link.adapter.snapshot()?.maxChargeWh)
            val base = Iap2Messages.buildRaw(0x1D01) { u16List(6, emptyList()); u16List(7, emptyList()) }
            val offer = Iap2BodyReader.of(OalCarDataIdentification.apply(base, link.caps))
            val status = offer.group(21)
            checks.equal("Exact implemented status capabilities, not scalar IDs", setOf(0, 1, 3, 11, 21, 30), status.list().map { it.id }.toSet())
            // GM_SOURCE with entered average is NOT a named connector calibration.
            checks.equal("No guessed/live-power connector declarations", emptyList<Int>(), offer.group(20).list().map { it.id }.filter { it in 12..20 })
            link.start()
            fun complete(label: String, body: Iap2BodyReader, charging: Int, fullRange: Int, sourceRange: Int) {
                val expected = setOf(3, 11, 21, 22, 23, 24, 25, 26, 27, 30)
                checks.equal("$label exact A101 fields", expected, body.list().map { it.id }.toSet())
                checks.equal("$label no duplicate fields", body.list().size, body.list().map { it.id }.toSet().size)
                checks.equal("$label source range", sourceRange, body.optionalU16(3))
                checks.equal("$label source electric range", sourceRange, body.optionalU16(11))
                checks.equal("$label reference minimum, not reserve", 0L, body.optionalU32(21))
                checks.equal("$label current Wh", 42950L, body.optionalU32(22))
                checks.equal("$label reported maximum Wh", 85900L, body.optionalU32(23))
                checks.equal("$label SOC percent times 1000", 50000L, body.optionalU32(24))
                checks.equal("$label fresh explicit state beats positive rate/port", charging, body.optionalU8(25))
                checks.equal("$label full range quantized/held km", fullRange, body.optionalU16(30))
                for ((id, json) in listOf(26 to link.caps.evRoutingModel!!.chargingJson, 27 to link.caps.evRoutingModel!!.consumptionJson)) {
                    checks.equal("$label model $id exact UTF8 NUL bytes", (json.toByteArray(Charsets.UTF_8) + byteArrayOf(0)).toList(), body.optionalRaw(id)?.toList())
                }
            }
            complete("initial", link.body(), 0, 430, 215)
            complete("30s candidate held", link.update(original.copy(rangeKm = 235f, evChargeState = 1)), 1, 430, 235)
            complete("60s qualified candidate", link.update(original.copy(rangeKm = 235f)), 0, 470, 235)
            val charge = original.evObservationMetadata.getValue("EV_CHARGE_STATE")
            for (bad in listOf(charge.copy(status = 1), charge.copy(source = "history"), charge.copy(subscriptionActive = false), charge.copy(propertyId = ids.getValue("RANGE_REMAINING")))) {
                val body = link.update(original.copy(evObservationMetadata = original.evObservationMetadata + ("EV_CHARGE_STATE" to bad)))
                checks.equal("Unqualified state cannot infer charging from port/power", false, body.has(25))
            }
            val unknownState = link.update(original.copy(evChargeState = 99))
            checks.equal("Unknown charging enum omitted", false, unknownState.has(25))
            for (badCapacity in listOf(Float.NaN, -1f, 0f, 250001f)) {
                val body = link.update(original.copy(evBatteryCapacityWh = badCapacity))
                for (id in listOf(23, 24, 26, 27, 30)) checks.equal("Invalid INFO $badCapacity omits $id", false, body.has(id))
            }
            for (badCurrent in listOf(Float.NaN, -1f, 90000f)) {
                val body = link.update(original.copy(evBatteryLevelWh = badCurrent))
                for (id in listOf(22, 24, 26, 27, 30)) checks.equal("Invalid current $badCurrent omits $id", false, body.has(id))
            }
            val badRange = link.update(original.copy(rangeKm = Float.NaN))
            for (id in listOf(3, 11, 26, 27, 30)) checks.equal("Invalid range omits $id, including held full range", false, badRange.has(id))
            val before = link.sent.size
            now += 31001
            link.reporter.tick()
            assertEquals("A held range/model never bypasses source freshness", before, link.sent.size)
        }
        Link(fresh(original.copy(evBatteryLevelWh = 8590f, rangeKm = 43f))).use { link ->
            link.start()
            checks.equal("Initial SOC below 20 percent cannot source a full range", false, link.body().has(30))
        }
        val actual = original.copy(evCurrentBatteryCapacityWh = 80000f)
        Link(fresh(actual)).use { link ->
            checks.equal("Measured usable remains first", 80000L, link.adapter.snapshot()?.maxChargeWh)
            assertEquals(80000, link.caps.evRoutingModel!!.pilotCapacityWh)
        }
        Link(fresh(original), config().copy(modelCapacityKwh = "85")).use { link ->
            assertEquals("Explicit manual model scale preserved", 85000, link.caps.evRoutingModel!!.pilotCapacityWh)
            checks.equal("Manual calibration does not replace sourced maximum", 85900L, link.adapter.snapshot()?.maxChargeWh)
        }
        Link(fresh(original.copy(evBatteryCapacityWh = null)), config().copy(modelCapacityKwh = "85")).use { link ->
            assertNotNull("Manual calibration remains usable for models", link.caps.evRoutingModel)
            assertFalse(link.caps.maximumEnergy)
            assertNull("Manual scale is never sourced max", link.adapter.snapshot()?.maxChargeWh)
        }
        for ((data, selected) in listOf(original to config().copy(enabled = false), original.copy(carMake = "Other") to config())) {
            Link(fresh(data), selected).use { link ->
                assertFalse("No reported-maximum expansion outside enabled identified GM_SOURCE", link.caps.maximumEnergy)
                assertNull(link.adapter.snapshot()?.maxChargeWh)
                assertNull(link.caps.evRoutingModel)
            }
        }
        checks.finish()
    }
}
