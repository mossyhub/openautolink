package com.openautolink.app.carplay

import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.openautolink.carplaylab.intake.OalCarDataBootstrap
import com.openautolink.carplaylab.intake.OalCarDataCapabilities
import com.openautolink.carplaylab.intake.OalCarDataIdentification
import com.openautolink.carplaylab.intake.OalEvRoutingModel
import com.openautolink.carplaylab.intake.OalIap2CarDataReporter
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Frozen tests-only packet. Constructed selectors, NOT a recovered phone A100 payload.
 * 30 is a recognized EMPTY capability echo, not a scalar or independent model authority.
 * No new production API, permissive unknown handling, clock wait or late offer expansion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class OalCarPlayEvRequest30RegressionTest {
    private var now = 170000L
    private val ids = mapOf(
        "EV_BATTERY_LEVEL" to 0x11600309,
        "INFO_EV_BATTERY_CAPACITY" to 0x11600106,
        "EV_CURRENT_BATTERY_CAPACITY" to 0x1160030d,
        "RANGE_REMAINING" to 0x11600308,
        "EV_CHARGE_STATE" to 0x11400f41,
        "EV_BATTERY_INSTANTANEOUS_CHARGE_RATE" to 0x1160030c,
        "EV_CHARGE_PORT_CONNECTED" to 0x1120030b,
        "ENV_OUTSIDE_TEMPERATURE" to 0x11600703,
    )
    private fun observation(property: String) = VehiclePropertyObservation(
        timestampElapsedNanos = 1000000000L, receivedElapsedMs = now, status = 0,
        registrationGeneration = 7, propertyId = ids.getValue(property), subscriptionActive = true,
        changeMode = 2, statusReadElapsedMs = now,
    )
    private fun source() = ControlMessage.VehicleData(
        carMake = "Chevrolet", carModel = "synthetic-request30", fuelTypes = listOf(10),
        evBatteryLevelWh = 42950f, evBatteryCapacityWh = 85900f, rangeKm = 215f,
        ambientTempC = 12f, evChargeState = 3, evChargeRateW = 21000f, chargePortConnected = true,
        vhalRegistrationGeneration = 7,
        evObservationMetadata = ids.keys.associateWith(::observation),
    )
    private fun configuration() = CarPlayEvPilotConfiguration(
        enabled = true, profileId = CarPlayEvPilotConfiguration.GM_SOURCE, averageChargeKw = "90",
    ) // Explicit synthetic average from the existing fixture, never a recovered owner value/curve.
    private fun fresh(data: ControlMessage.VehicleData) = data.copy(
        evObservationMetadata = data.evObservationMetadata.mapValues { (_, o) ->
            o.copy(receivedElapsedMs = now, statusReadElapsedMs = now)
        },
    )
    private fun request(vararg ids: Int) = Iap2Messages.buildRaw(0xA100) { ids.forEach { void(it) } }
    private fun base() = Iap2Messages.buildRaw(0x1D01) { u16List(6, emptyList()); u16List(7, emptyList()) }
    private inner class Fixture(initial: ControlMessage.VehicleData = source()) : AutoCloseable {
        val flow = MutableStateFlow(initial)
        val receipts = mutableListOf<String>()
        val adapter = OalCarPlayVehicleSnapshotAdapter({ flow }, { now }, ::configuration)
        val caps = adapter.capabilities(false, receipts::add)
        val sent = mutableListOf<Iap2Frame>() // This exact owner's synchronous writer only.
        var reads = 0
        val reporter = reporter(caps)
        fun reporter(selected: OalCarDataCapabilities) = OalIap2CarDataReporter(
            selected, { now }, { null }, { reads++; adapter.snapshot(receipts::add) },
            {}, sent::add, receipts::add,
        )
        fun offer() = OalCarDataIdentification.apply(base(), caps)
        override fun close() = reporter.close()
    }
    private fun assertReady(f: Fixture) {
        assertNull("No measured usable capacity in this reported-INFO fixture", f.flow.value.evCurrentBatteryCapacityWh)
        assertTrue(f.caps.range && f.caps.currentEnergy && f.caps.maximumEnergy && f.caps.outsideTemperature && f.caps.maximumRange)
        val model = f.caps.evRoutingModel!!
        assertEquals(85900, model.pilotCapacityWh)
        assertEquals(OalEvRoutingModel.CapacityProvenance.SOURCE_REPORTED, model.capacityProvenance)
        assertEquals(85900L, f.adapter.snapshot()!!.maxChargeWh)
        assertTrue("Wire maximum and derived SOC retain reported-INFO provenance", f.receipts.any {
            it.contains("carplay-ev-admission wireBasis=reported socBasis=reported ")
        })
        assertSame(model, f.adapter.snapshot()!!.routingModel)
        val offer = Iap2BodyReader.of(f.offer())
        assertEquals(setOf(0, 1, 3, 4, 11, 21, 30), offer.group(21).list().map { it.id }.toSet())
        assertEquals(0, offer.group(21).raw(30).size) // Identify capability, NOT A101 uint16.
        assertTrue(0xA101 in offer.u16List(6))
        assertTrue(offer.u16List(7).containsAll(listOf(0xA100, 0xA102)))
        assertFalse(offer.group(21).has(26) || offer.group(21).has(27))
    }
    private fun assertComplete(f: Fixture, frame: Iap2Frame, charging: ByteArray, consumption: ByteArray) {
        assertEquals(0xA101, frame.messageId)
        val b = Iap2BodyReader.of(frame)
        val fields = b.list().map { it.id }
        assertEquals(setOf(3, 4, 11, 21, 22, 23, 24, 25, 26, 27, 30), fields.toSet())
        assertEquals("No duplicate output fields", fields.size, fields.toSet().size)
        assertEquals(215, b.u16(3)); assertEquals(215, b.u16(11)); assertEquals(12, b.i16(4))
        assertEquals(0L, b.u32(21)) // Reference convention, not measured OEM reserve.
        assertEquals(42950L, b.u32(22)); assertEquals(85900L, b.u32(23)); assertEquals(50000L, b.u32(24))
        assertEquals("Explicit state 3 beats positive rate and connected port", 0, b.u8(25))
        assertEquals(2, b.raw(30).size); assertEquals(430, b.u16(30))
        assertArrayEquals(charging, b.raw(26)); assertArrayEquals(consumption, b.raw(27))
        assertEquals(12582922, JSONObject(b.string(26)).getInt("model_id"))
        assertEquals(12582921, JSONObject(b.string(27)).getInt("model_id"))
        assertSame(f.caps.evRoutingModel, f.adapter.snapshot()!!.routingModel)
    }

    @Test fun advertisedEmpty30PreservesOtherwiseIdenticalRequestAndRealThirtySecondModelWrites() {
        val initial = source()
        var controlOffer: ByteArray? = null
        var controlPayload: ByteArray? = null
        // The only changed request byte sequence is the appended empty TLV30.
        for (echo in listOf(false, true)) {
            now = 170000L
            Fixture(initial).use { f ->
                assertReady(f)
                val offer = f.offer().encodedFrame()
                if (!echo) controlOffer = offer else assertArrayEquals(controlOffer!!, offer)
                val frozen = f.caps.evRoutingModel!!
                val charging = frozen.chargingJson.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
                val consumption = frozen.consumptionJson.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
                val selected = if (echo) request(3, 4, 11, 21, 30) else request(3, 4, 11, 21)
                f.reporter.authenticate(); assertTrue(f.reporter.handle(selected))
                assertEquals("$echo: actual same-owner A101 must be written, not merely accepted", 1, f.sent.size)
                assertTrue(f.receipts.any { it.contains("id=41216 ") && it.contains("result=accepted ") && it.contains("groups=15 ") })
                assertFalse(f.receipts.any { it.contains("result=unsupported_id parameter=30 ") })
                assertComplete(f, f.sent.single(), charging, consumption)
                if (!echo) controlPayload = f.sent.single().payload.copyOf()
                else assertArrayEquals(controlPayload!!, f.sent.single().payload)
                assertEquals(30000L, f.reporter.pollTimeout(60000))
                now += 29999; f.flow.value = fresh(initial); f.reporter.tick()
                assertEquals(1, f.sent.size); assertEquals(1L, f.reporter.pollTimeout(60000))
                now++; f.flow.value = fresh(initial); f.reporter.tick()
                assertEquals("Real second A101, not a location deadline", 2, f.sent.size)
                assertComplete(f, f.sent.last(), charging, consumption)
                assertSame(frozen, f.adapter.snapshot()!!.routingModel)
                assertArrayEquals(offer, f.offer().encodedFrame())
                assertEquals(30000L, f.reporter.pollTimeout(60000))
            }
        }
    }

    @Test fun deferredEmpty30ReplaysOnlyIntoAuthenticatedActualAppIntakeOwner() {
        Fixture().use { f ->
            assertReady(f)
            var gnssCreates = 0
            fun actual(writes: MutableList<Iap2Frame>) = OalCarPlayIntakeLink(
                gpsQualified = false, vehicle = f.adapter,
                gnss = OalCarPlayGnssSnapshotAdapter({ now }) { gnssCreates++; error("EV request cannot activate GNSS") },
                elapsedMs = { now }, appearance = CarPlayAppearanceBridge(f.flow, Dispatchers.IO, { now }, 5000),
                sendNight = { error("No encrypted-event appearance admission") }, send = writes::add,
                receipt = f.receipts::add, frozenCapabilities = f.caps,
                restrictionPolicy = CarPlayRestrictionPolicy(false, false), restriction = null,
            )
            val deferred = OalCarDataBootstrap(f.caps, { now }, f.receipts::add)
            val owner = actual(f.sent)
            val otherWrites = mutableListOf<Iap2Frame>()
            val other = actual(otherWrites)
            val frozen = deferred.identification(base()).encodedFrame()
            val command = request(3, 4, 11, 21, 30)
            assertTrue(deferred.handle(command)); command.payload.fill(99)
            val replay = deferred.wrap(owner)
            try {
                assertTrue(f.sent.isEmpty()); assertEquals(0, gnssCreates)
                assertArrayEquals(frozen, replay.identification(base()).encodedFrame())
                owner.tick(false); assertTrue(f.sent.isEmpty())
                other.authenticate(); other.tick(false); assertTrue(otherWrites.isEmpty())
                replay.authenticate()
                assertEquals("Authenticated replay uses the real app intake reporter/writer", 1, f.sent.size)
                val model = f.caps.evRoutingModel!!
                assertComplete(f, f.sent.single(), model.chargingJson.toByteArray(Charsets.UTF_8) + byteArrayOf(0), model.consumptionJson.toByteArray(Charsets.UTF_8) + byteArrayOf(0))
                replay.authenticate(); replay.tick(false); assertEquals(1, f.sent.size)
                assertTrue(otherWrites.isEmpty()); assertEquals(0, gnssCreates)
                replay.revoke(); now += 30000; f.flow.value = fresh(f.flow.value)
                replay.authenticate(); replay.handle(request(3, 4, 11, 21, 30)); replay.tick(false)
                assertEquals(1, f.sent.size); assertTrue(otherWrites.isEmpty())
                val cancelled = OalCarDataBootstrap(f.caps, { now }, f.receipts::add)
                cancelled.handle(request(3, 4, 11, 21, 30)); cancelled.close()
                val cancelledReplay = cancelled.wrap(other)
                try {
                    cancelledReplay.authenticate(); cancelledReplay.tick(false)
                    other.handle(request(3, 4, 11, 21, 30)); other.tick(false)
                    assertTrue("Cancelled bootstrap cannot hand a subscription to another owner", otherWrites.isEmpty())
                } finally { cancelledReplay.close() }
            } finally { replay.close(); deferred.close(); other.close() }
        }
    }

    @Test fun genuinelyMalformedAndUnknownRequestsStillRevokeAnAlreadyActiveSubscription() {
        val malformed = Iap2Frame(0xA100, byteArrayOf(0, 3, 0))
        assertTrue("Actual TLV reader rejects this truncated/illegal length", runCatching { Iap2BodyReader.of(malformed) }.isFailure)
        val bad = listOf(
            malformed to "result=malformed ",
            Iap2Messages.buildRaw(0xA100) { void(21); u16(30, 430) } to "result=width parameter=30 length=2 ",
            request(21, 30, 30) to "result=duplicate parameter=30 length=0 ",
            request(21, 31) to "result=unsupported_id parameter=31 length=0 ",
            request(21, 22) to "result=unsupported_id parameter=22 length=0 ",
            Iap2Frame(0xA100, ByteArray(129)) to "result=oversized ",
            request(30) to "result=no_supported_groups ",
        )
        for ((command, expected) in bad) Fixture().use { f ->
            f.reporter.authenticate(); f.reporter.handle(request(3, 4, 11, 21))
            assertEquals("Positive active-subscription control before $expected", 1, f.sent.size)
            f.receipts.clear(); assertTrue(f.reporter.handle(command))
            assertTrue("Must reject for $expected, not silently ignore actual malformed/unknown input: ${f.receipts}", f.receipts.any { it.contains(expected) })
            now += 30000; f.flow.value = fresh(f.flow.value); f.reporter.tick()
            assertEquals("Bad replacement request revokes all old selections", 1, f.sent.size)
            assertEquals(60000L, f.reporter.pollTimeout(60000))
        }
    }

    @Test fun empty30CannotGrantGroup21ModelsOrUnofferedFields() {
        Fixture().use { f ->
            assertReady(f)
            val selections = listOf(
                request(3, 4, 11, 30) to setOf(3, 4, 11),
                request(21, 30) to setOf(21, 22, 23, 24, 25, 26, 27),
            )
            for ((command, expected) in selections) {
                f.sent.clear()
                f.reporter(f.caps).use { r ->
                    r.authenticate(); r.handle(command)
                    assertEquals(1, f.sent.size)
                    assertEquals(expected, Iap2BodyReader.of(f.sent.single()).list().map { it.id }.toSet())
                }
            }
            f.sent.clear()
            val limited = f.caps.copy(range = false, outsideTemperature = false, maximumRange = false, evRoutingModel = null)
            f.reporter(limited).use { r ->
                val offer = Iap2BodyReader.of(OalCarDataIdentification.apply(base(), limited)).group(21)
                for (id in listOf(3, 4, 11, 30)) assertFalse(offer.has(id))
                r.authenticate(); r.handle(request(3, 4, 11, 21, 30))
                assertEquals(1, f.sent.size)
                assertEquals(setOf(21, 22, 23, 24, 25), Iap2BodyReader.of(f.sent.single()).list().map { it.id }.toSet())
            }
        }
    }

    @Test fun preauthStopRetirementAndSourceOwnerVetoesRemainEffectiveWithKnown30() {
        Fixture().use { f ->
            f.reporter.handle(request(3, 4, 11, 21, 30)); f.reporter.tick()
            assertEquals(0, f.reads); assertTrue(f.sent.isEmpty())
            f.reporter.authenticate(); f.reporter.tick()
            assertEquals("Raw reporter does not retain preauth starts", 0, f.reads)
            f.reporter.handle(request(3, 4, 11, 21, 30)); assertEquals(1, f.sent.size)
            f.reporter.handle(Iap2Frame(0xA102, byteArrayOf(1)))
            now += 30000; f.flow.value = fresh(f.flow.value); f.reporter.tick()
            assertEquals(1, f.sent.size); assertEquals(60000L, f.reporter.pollTimeout(60000))
            f.reporter.close(); f.reporter.authenticate(); f.reporter.handle(request(21, 30)); f.reporter.tick()
            assertEquals(1, f.sent.size)
        }
        val original = source()
        val wrongOwner = original.copy(carModel = "different-owner")
        val wrongGeneration = original.copy(vhalRegistrationGeneration = 8,
            evObservationMetadata = original.evObservationMetadata.mapValues { it.value.copy(registrationGeneration = 8) })
        for (denied in listOf(wrongOwner, wrongGeneration, original.copy(evObservationMetadata = emptyMap()))) {
            Fixture(original).use { f ->
                f.flow.value = denied
                assertNull(f.adapter.snapshot()?.routingModel)
                f.reporter.authenticate(); f.reporter.handle(request(3, 4, 11, 21, 30))
                if (f.sent.isNotEmpty()) {
                    val b = Iap2BodyReader.of(f.sent.single())
                    for (id in listOf(26, 27, 30)) assertFalse("No stale-owner model/full range", b.has(id))
                }
                if (denied.evObservationMetadata.isNotEmpty()) assertEquals("Qualified scalar survives model owner veto", 1, f.sent.size)
                else assertTrue("No retained-model-only write on total source loss", f.sent.isEmpty())
            }
        }
    }

    @Test fun firstUnreadyRangeCannotHealFrozenIdentifyButNewReadyLinkCanOfferModels() {
        val valid = source()
        val range = valid.evObservationMetadata.getValue("RANGE_REMAINING")
        val unready = valid.copy(evObservationMetadata = valid.evObservationMetadata +
            ("RANGE_REMAINING" to range.copy(statusReadElapsedMs = null)))
        Fixture(unready).use { first ->
            assertFalse(first.caps.range); assertFalse(first.caps.maximumRange); assertNull(first.caps.evRoutingModel)
            assertTrue(first.receipts.any { it.contains("readiness=source_range ") })
            val frozen = first.offer().encodedFrame()
            first.flow.value = fresh(valid)
            assertEquals(215, first.adapter.snapshot()!!.rangeKm)
            assertNull(first.adapter.snapshot()!!.routingModel)
            assertArrayEquals(frozen, first.offer().encodedFrame())
            first.reporter.authenticate(); first.reporter.handle(request(3, 4, 11, 21))
            val b = Iap2BodyReader.of(first.sent.single())
            for (id in listOf(3, 11, 26, 27, 30)) assertFalse(b.has(id))
            Fixture(first.flow.value).use { next ->
                assertReady(next)
                assertFalse("New owner, new frozen offer", frozen.contentEquals(next.offer().encodedFrame()))
                next.reporter.authenticate(); next.reporter.handle(request(3, 4, 11, 21))
                assertTrue(Iap2BodyReader.of(next.sent.single()).has(26))
                assertTrue(Iap2BodyReader.of(next.sent.single()).has(27))
            }
        }
    }
}
