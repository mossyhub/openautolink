package com.openautolink.app.carplay

import com.openautolink.app.input.GnssForwarder
import com.openautolink.app.transport.ControlMessage
import com.openautolink.app.transport.VehiclePropertyObservation
import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2CsmFramer
import com.openautolink.carplaylab.intake.OalCarDataIdentification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class CarPlayVehicleIdentityIntegrationTest {
    @Test fun allFrozenCapabilityCombinationsPreserveAccessoryIdentityAndOmitUnprovenFields() {
        val observation = VehiclePropertyObservation(1_000_000_000, 1000, 0,
            registrationGeneration = 1, subscriptionActive = true)
        val base = Iap2Messages.buildRaw(0x1D01) {
            string(0, "OpenAutoLink"); string(1, "OAL-native-CarPlay"); string(2, "OpenAutoLink")
            u16List(6, listOf(0x1234)); u16List(7, listOf(0x5678))
        }
        for (bits in 0 until 32) {
            val source = MutableStateFlow(ControlMessage.VehicleData(carMake = "Chevrolet", carModel = "C234",
                carYear = "2024", fuelTypes = listOf(10), evConnectorTypes = listOf(1, 2, 5, 6, 10, 11, 3, 4, 7, 8, 9, 0, 101),
                rangeKm = 123f.takeIf { bits and 1 != 0 }, evBatteryLevelWh = 12345f.takeIf { bits and 2 != 0 },
                evCurrentBatteryCapacityWh = 60000f.takeIf { bits and 4 != 0 }, ambientTempC = 25f.takeIf { bits and 8 != 0 },
                vhalRegistrationGeneration = 1, evObservationMetadata = listOf("RANGE_REMAINING", "EV_BATTERY_LEVEL",
                    "EV_CURRENT_BATTERY_CAPACITY", "ENV_OUTSIDE_TEMPERATURE").associateWith { observation }))
            val caps = OalCarPlayVehicleSnapshotAdapter({ source }, { 1000L }).capabilities(bits and 16 != 0)
            val encoded = OalCarDataIdentification.apply(base, caps).encodedFrame()
            val body = Iap2BodyReader.of(Iap2CsmFramer().offer(encoded).single())
            for (id in 0..2) assertArrayEquals(Iap2BodyReader.of(base).raw(id), body.raw(id))
            assertEquals(bits and 16 != 0, body.has(22))
            assertEquals(bits and 15 != 0, body.has(20))
            assertEquals(bits and 15 != 0, body.has(21))
            if (body.has(20)) {
                val info = Iap2BodyReader.of(Iap2Frame(0, body.raw(20)))
                assertEquals("2024 Chevrolet C234", info.string(6)); assertEquals(info.string(6), info.string(8))
                assertEquals(listOf(0, 1, 2, 4, 5, 6), info.list().filter { it.id == 11 }.map { it.payload.single().toInt() })
                assertEquals(setOf(0, 1, 2, 6, 8, 11), info.list().map { it.id }.toSet())
            }
        }
    }

    @Test fun absentMalformedAndNonElectricIdentityNeverBecomesAGuessedVehicle() {
        val source = MutableStateFlow(ControlMessage.VehicleData(fuelTypes = listOf(10), carYear = "2024",
            vhalRegistrationGeneration = 1))
        val adapter = OalCarPlayVehicleSnapshotAdapter({ source }, { 1000L })
        assertNull(adapter.capabilities(false).vehicleDisplayName)
        for (bad in listOf("", "   ", "unknown", "null", "n/a", "bad\u0000name", "bad\nname", "é".repeat(41))) {
            source.value = source.value.copy(carMake = bad, carModel = bad)
            assertNull(bad, adapter.capabilities(false).vehicleDisplayName)
        }
        source.value = source.value.copy(carMake = "Chevrolet", carModel = "C234", carYear = "not-a-year")
        assertEquals("Chevrolet C234", adapter.capabilities(false).vehicleDisplayName)
        for (fuel in listOf(null, emptyList(), listOf(1), listOf(1, 10))) {
            source.value = source.value.copy(fuelTypes = fuel, evConnectorTypes = listOf(1, 5))
            val caps = adapter.capabilities(false)
            assertNull(caps.vehicleDisplayName); assertEquals(0, caps.chargingConnectorMask)
        }
    }

    @Test fun vhalIdentityReachesFinalIdentificationWithoutChangingEnergyFrames() {
        val observation = VehiclePropertyObservation(1_000_000_000, 1000, 0,
            registrationGeneration = 1, subscriptionActive = true)
        val source = MutableStateFlow(ControlMessage.VehicleData(
            carMake = "Chevrolet", carModel = "C234", carYear = "2024",
            fuelTypes = listOf(10), evConnectorTypes = listOf(1, 5, 5, 0, 101, 9),
            evBatteryLevelWh = 12345f, evBatteryCapacityWh = 85570f,
            vhalRegistrationGeneration = 1, evObservationMetadata = mapOf("EV_BATTERY_LEVEL" to observation)))
        val clock = { 1000L }
        val sent = mutableListOf<Iap2Frame>()
        val link = OalCarPlayIntakeLink(false, OalCarPlayVehicleSnapshotAdapter({ source }, clock),
            OalCarPlayGnssSnapshotAdapter(clock) { object : GnssForwarder {
                override val isActive = false
                override fun start() { fail("GPS was not offered") }
                override fun stop() {}
            } }, clock, CarPlayAppearanceBridge(source, Dispatchers.IO, clock, 5000),
            { false }, sent::add, {})
        val base = Iap2Messages.buildRaw(0x1D01) { u16List(6, emptyList()); u16List(7, emptyList()) }
        try {
            val identification = Iap2CsmFramer().offer(link.identification(base).encodedFrame()).single()
            val info = Iap2BodyReader.of(Iap2Frame(0, Iap2BodyReader.of(identification).raw(20)))
            assertEquals("2024 Chevrolet C234", info.optionalString(6))
            assertEquals("2024 Chevrolet C234", info.string(8))
            assertEquals(2, info.u8(2))
            assertEquals(listOf(0, 2), info.list().filter { it.id == 11 }.map { it.payload.single().toInt() })
            assertEquals(setOf(0, 1, 2, 6, 8, 11), info.list().map { it.id }.toSet())
            val frozen = identification.encodedFrame()
            source.value = source.value.copy(carMake = "Changed", carModel = null, evConnectorTypes = listOf(6))
            assertArrayEquals(frozen, link.identification(base).encodedFrame())
            link.handle(Iap2Messages.buildRaw(0xA100) { void(21) })
            assertTrue(sent.isEmpty())
            link.authenticate()
            link.handle(Iap2Messages.buildRaw(0xA100) { void(21) })
            assertArrayEquals(Iap2Messages.buildRaw(0xA101) { u32(22, 12345) }.encodedFrame(), sent.single().encodedFrame())
            link.revoke(); link.tick(true)
            assertEquals(1, sent.size)
        } finally { link.close() }
    }
}
