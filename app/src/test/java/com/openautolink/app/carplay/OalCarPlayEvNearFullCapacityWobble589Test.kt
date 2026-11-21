package com.openautolink.app.carplay

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.carplaylab.intake.OalCarDataCodec
import com.openautolink.carplaylab.intake.OalEvSnapshot
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

/** Private tests-only follow-on. Existing 589 APIs only; no admission/rounding policy invented here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class OalCarPlayEvNearFullCapacityWobble589Test {
    private var now = 170000L
    private val ids = mapOf(
        "EV_BATTERY_LEVEL" to 0x11600309,
        "INFO_EV_BATTERY_CAPACITY" to 0x11600106,
        "EV_CURRENT_BATTERY_CAPACITY" to 0x1160030d,
        "RANGE_REMAINING" to 0x11600308,
        "EV_CHARGE_STATE" to 0x11400f41,
    )
    private fun observation(property: String) = VehiclePropertyObservation(
        timestampElapsedNanos = 1000000000L, receivedElapsedMs = now, status = 0,
        registrationGeneration = 7, propertyId = ids.getValue(property), subscriptionActive = true,
        changeMode = 2, statusReadElapsedMs = now,
    )
    private fun source() = ControlMessage.VehicleData(
        carMake = "Chevrolet", carModel = "C234", fuelTypes = listOf(10),
        evBatteryLevelWh = 42950f, evBatteryCapacityWh = 85900f, rangeKm = 215f,
        evChargeState = 3, vhalRegistrationGeneration = 7,
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
    // Aggregate behavioral assertions so the positive RED does not skip the guard controls.
    private class Checks {
        private val failures = mutableListOf<String>()
        fun equal(label: String, expected: Any?, actual: Any?) {
            if (expected != actual) failures += "$label: expected <$expected>, got <$actual>"
        }
        fun finish() = assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
    private inner class Link(initial: ControlMessage.VehicleData, selected: CarPlayEvPilotConfiguration = config()) : AutoCloseable {
        val flow = MutableStateFlow(fresh(initial))
        val adapter = OalCarPlayVehicleSnapshotAdapter({ flow }, { now }, { selected })
        val caps = adapter.capabilities(false)
        val sent = mutableListOf<Iap2Frame>()
        val reporter = OalIap2CarDataReporter(caps, { now }, { null }, { adapter.snapshot() }, {}, sent::add, {})
        fun start(): Iap2BodyReader {
            val request = Iap2Messages.buildRaw(0xA100) { void(3); void(11); void(21) }
            reporter.handle(request)
            assertTrue("Start without authentication must not send", sent.isEmpty())
            reporter.authenticate()
            reporter.handle(request)
            assertEquals(1, sent.size)
            return body()
        }
        fun update(data: ControlMessage.VehicleData): Iap2BodyReader {
            now += 30000
            flow.value = fresh(data)
            val before = sent.size
            reporter.tick()
            assertEquals("Qualified range still supplies a real update", before + 1, sent.size)
            return body()
        }
        fun negativeUpdate(data: ControlMessage.VehicleData): Pair<OalEvSnapshot?, Iap2BodyReader?> {
            now += 30000
            flow.value = fresh(data)
            val before = sent.size
            reporter.tick()
            val snapshot = adapter.snapshot()
            // A fully unavailable source legitimately has no frame. Never synthesize one;
            // retain exact real delivery whenever qualified fields still survive.
            assertEquals(if (snapshot == null) before else before + 1, sent.size)
            return snapshot to if (sent.size > before) body() else null
        }
        private fun body(): Iap2BodyReader {
            assertEquals(0xA101, sent.last().messageId)
            return Iap2BodyReader.of(sent.last())
        }
        override fun close() = reporter.close()
    }

    @Test fun nearFullReportedCapacityWobbleRetainsFrozenModelsWithoutWideningManualOrOwnerAdmission() {
        val checks = Checks()
        val original = source()
        val nearFull = original.copy(evBatteryCapacityWh = 85910f, evBatteryLevelWh = 85905f, rangeKm = 430f)
        val expectedSoc = (85905.0 / 85910.0 * 100000).toLong()
        Link(original).use { link ->
            val model = link.caps.evRoutingModel
            assertNotNull("Cold qualified GM_SOURCE freezes a model", model)
            assertTrue(link.caps.maximumEnergy)
            val first = link.start()
            assertEquals(42950L, first.u32(22))
            assertEquals(85900L, first.u32(23))
            val charging = model!!.chargingJson.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            val consumption = model.consumptionJson.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            assertArrayEquals(charging, first.raw(26))
            assertArrayEquals(consumption, first.raw(27))
            fun stable(label: String, snapshot: OalEvSnapshot, body: Iap2BodyReader) {
                checks.equal("$label same frozen model object", true, snapshot.routingModel === model)
                checks.equal("$label original capability owner", true, link.caps.evRoutingModel === model)
                checks.equal("$label current Wh", 85905L, snapshot.currentChargeWh)
                checks.equal("$label source maximum Wh", 85910L, snapshot.maxChargeWh)
                checks.equal("$label derived SOC", expectedSoc, snapshot.chargePercentMilli)
                checks.equal("$label wire current 22", 85905L, body.optionalU32(22))
                checks.equal("$label wire source maximum 23", 85910L, body.optionalU32(23))
                checks.equal("$label wire derived SOC 24", expectedSoc, body.optionalU32(24))
                checks.equal("$label charging 26 frozen bytes", charging.toList(), body.optionalRaw(26)?.toList())
                checks.equal("$label consumption 27 frozen bytes", consumption.toList(), body.optionalRaw(27)?.toList())
                checks.equal("$label no duplicate fields", body.list().size, body.list().map { it.id }.toSet().size)
            }
            val updated = link.update(nearFull)
            val live = link.adapter.snapshot()!!
            stable("near-full authenticated reporter", live, updated)
            // Isolate codec's independent capacity gate even while adapter currently drops its model.
            // This is a real qualified snapshot with only its original frozen model restored for this probe.
            val codecProbe = live.copy(routingModel = model)
            stable("near-full codec boundary", codecProbe, Iap2BodyReader.of(OalCarDataCodec.vehicle(codecProbe)!!))

            val info = nearFull.evObservationMetadata.getValue("INFO_EV_BATTERY_CAPACITY")
            val denied = listOf(
                "above current source maximum" to nearFull.copy(evBatteryLevelWh = 85911f),
                "identity make replacement" to nearFull.copy(carMake = "GMC"),
                "identity model replacement" to nearFull.copy(carModel = "different-source"),
                "non-pure EV source" to nearFull.copy(fuelTypes = listOf(1, 10)),
                "generation mismatch" to nearFull.copy(vhalRegistrationGeneration = 8),
                "coherent replacement generation" to nearFull.copy(vhalRegistrationGeneration = 8,
                    evObservationMetadata = nearFull.evObservationMetadata.mapValues { it.value.copy(registrationGeneration = 8) }),
                "INFO value loss" to nearFull.copy(evBatteryCapacityWh = null),
                "INFO observation loss" to nearFull.copy(evObservationMetadata = nearFull.evObservationMetadata - "INFO_EV_BATTERY_CAPACITY"),
            ) + listOf(
                "INFO wrong source" to info.copy(source = "history"),
                "INFO unavailable status" to info.copy(status = 1),
                "INFO unsubscribed" to info.copy(subscriptionActive = false),
                "INFO wrong generation" to info.copy(registrationGeneration = 6),
                "INFO wrong property" to info.copy(propertyId = ids.getValue("EV_BATTERY_LEVEL")),
            ).map { (label, observation) -> label to nearFull.copy(
                evObservationMetadata = nearFull.evObservationMetadata + ("INFO_EV_BATTERY_CAPACITY" to observation)) }
            for ((label, data) in denied) {
                val (snapshot, body) = link.negativeUpdate(data)
                checks.equal("$label vetoes adapter model", null, snapshot?.routingModel)
                for (id in listOf(26, 27)) checks.equal("$label omits model $id", false, body?.has(id) == true)
                if (label == "above current source maximum") {
                    assertNotNull("Range and source maximum still require a real frame", body)
                    assertNotNull(snapshot)
                    checks.equal("Inconsistent current is not clamped or admitted", null, snapshot!!.currentChargeWh)
                    checks.equal("Fresh source maximum survives inconsistency", 85910L, snapshot.maxChargeWh)
                    for (id in listOf(22, 24, 26, 27)) {
                        checks.equal("Inconsistency omits wire $id", false, body!!.has(id))
                        val guarded = Iap2BodyReader.of(OalCarDataCodec.vehicle(snapshot.copy(
                            currentChargeWh = 85911L, chargePercentMilli = 100000L, routingModel = model))!!)
                        checks.equal("Codec independently rejects inconsistent $id", false, guarded.has(id))
                    }
                }
            }
            val recovered = link.update(nearFull)
            stable("same-owner near-full recovery", link.adapter.snapshot()!!, recovered)
            val beforeClose = link.sent.size
            link.reporter.close()
            link.reporter.authenticate()
            link.reporter.handle(Iap2Messages.buildRaw(0xA100) { void(21) })
            now += 30000
            link.flow.value = fresh(nearFull)
            link.reporter.tick()
            checks.equal("Exact reporter retirement cannot be reversed", beforeClose, link.sent.size)
        }
        val manual = config().copy(modelCapacityKwh = "85")
        Link(original, manual).use { link ->
            val model = link.caps.evRoutingModel
            assertNotNull("Manual positive control below its own scale", model)
            assertEquals("Explicit manual model scale remains exact", 85000, model!!.pilotCapacityWh)
            val first = link.start()
            assertArrayEquals(model.chargingJson.toByteArray(Charsets.UTF_8) + byteArrayOf(0), first.raw(26))
            assertArrayEquals(model.consumptionJson.toByteArray(Charsets.UTF_8) + byteArrayOf(0), first.raw(27))
            val body = link.update(nearFull)
            val snapshot = link.adapter.snapshot()!!
            checks.equal("Reported status cannot expand explicit manual admission", null, snapshot.routingModel)
            checks.equal("Manual capability model not rebuilt", true, link.caps.evRoutingModel === model)
            checks.equal("Manual model not resized", 85000, model.pilotCapacityWh)
            checks.equal("Manual scale is not source maximum", 85910L, snapshot.maxChargeWh)
            checks.equal("Valid source current remains usable with manual model veto", 85905L, body.optionalU32(22))
            checks.equal("Manual source maximum 23", 85910L, body.optionalU32(23))
            checks.equal("Manual source SOC remains valid", expectedSoc, body.optionalU32(24))
            for (id in listOf(26, 27)) checks.equal("Manual over-scale omits $id", false, body.has(id))
            val recovery = link.update(original)
            checks.equal("Manual in-scale recovery keeps same object", true, link.adapter.snapshot()?.routingModel === model)
            checks.equal("Manual recovery charging bytes", first.raw(26).toList(), recovery.optionalRaw(26)?.toList())
            checks.equal("Manual recovery consumption bytes", first.raw(27).toList(), recovery.optionalRaw(27)?.toList())
        }
        Link(nearFull, manual).use { link ->
            checks.equal("Cold manual over-scale cannot fall back to reported status", null, link.caps.evRoutingModel)
            val body = link.start()
            checks.equal("Cold manual source current survives", 85905L, body.optionalU32(22))
            checks.equal("Cold manual source maximum stays sourced", 85910L, body.optionalU32(23))
            for (id in listOf(26, 27)) checks.equal("Cold manual over-scale omits $id", false, body.has(id))
        }
        checks.finish()
    }
}
