package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = android.app.Application::class)
class OalEvRoutingReporterTest {
    private fun model() = OalEvRoutingModel.parse(OalEvRoutingModelTest.charging, OalEvRoutingModelTest.consumption, 60000)!!
    @Test fun codecSendsCompactUtf8NulContractsButNeverSynthesizesSourcedMaximum() {
        val m = model()
        val body = Iap2BodyReader.of(OalCarDataCodec.vehicle(OalEvSnapshot(currentChargeWh=20000, routingModel=m))!!)
        assertEquals(setOf(22,26,27),body.list().map { it.id }.toSet())
        assertArrayEquals(m.chargingJson.toByteArray(Charsets.UTF_8)+byteArrayOf(0),body.raw(26))
        assertArrayEquals(m.consumptionJson.toByteArray(Charsets.UTF_8)+byteArrayOf(0),body.raw(27))
        assertFalse(body.has(23)); assertFalse(body.has(24)); assertFalse(body.has(25))
        assertNull(OalCarDataCodec.vehicle(OalEvSnapshot(routingModel=m)))
    }
    @Test fun authenticatedSelectedExactOwnerCanSendStableModelsAndFreshnessAlwaysVetoes() {
        var now=1000L
        val m=model()
        var snapshot: OalEvSnapshot? = OalEvSnapshot(currentChargeWh=20000,routingModel=m)
        val sent=mutableListOf<Iap2Frame>()
        val caps=OalCarDataCapabilities(currentEnergy=true,evRoutingModel=m)
        val link=OalIap2CarDataReporter(caps,{now},{null},{snapshot},{},sent::add,{})
        val start=Iap2Messages.buildRaw(0xA100) { void(21) }
        link.handle(start); assertTrue(sent.isEmpty())
        link.authenticate(); link.handle(start)
        assertEquals(setOf(22,26,27),Iap2BodyReader.of(sent.single()).list().map {it.id}.toSet())
        val charge=Iap2BodyReader.of(sent.single()).raw(26)
        now+=30000; snapshot=OalEvSnapshot(currentChargeWh=19000,routingModel=m); link.tick()
        assertArrayEquals(charge,Iap2BodyReader.of(sent.last()).raw(26))
        now+=30000; snapshot=null; link.tick(); assertEquals(2,sent.size)
        now+=30000; snapshot=OalEvSnapshot(currentChargeWh=18000,routingModel=model()); link.tick()
        assertEquals(listOf(22),Iap2BodyReader.of(sent.last()).list().map {it.id})
        link.handle(Iap2Messages.buildRaw(0xA102) {}); val stopped=sent.size
        now+=30000; snapshot=OalEvSnapshot(currentChargeWh=17000,routingModel=m); link.tick(); assertEquals(stopped,sent.size)
        link.close(); link.authenticate(); link.handle(start); assertEquals(stopped,sent.size)
    }
    @Test fun reportsActualEvValuesOnlyAfterWrite() {
        val m = model()
        val lines = mutableListOf<String>()
        val caps = OalCarDataCapabilities(range=true,currentEnergy=true,maximumEnergy=true,
            outsideTemperature=true,maximumRange=true,electricRangeWarning=true,evRoutingModel=m)
        var now = 1000L
        val reporter = OalIap2CarDataReporter(caps,{now},{null},{OalEvSnapshot(180,20000,60000,-5,m,0,33333,false,540,false)},
            {},{ lines.add("written") }, lines::add)
        val start = Iap2Messages.buildRaw(0xA100) { void(3); void(4); void(11); void(21) }
        reporter.handle(start)
        assertFalse(lines.any { it.startsWith("carplay-ev-wire") })
        reporter.authenticate(); reporter.handle(start)
        val tx = "carplay-ev-wire send=1 status=3:180,11:180,15:0,4:-5,21:0,22:20000,23:60000,24:33333,25:0,30:540"
        assertTrue(lines.toString(), lines.indexOf(tx) > lines.indexOf("written"))
        assertTrue(lines.any { it.startsWith("carplay-ev-wire send=1 model=26 id=12582922 energy=") })
        assertTrue(lines.any { it.startsWith("carplay-ev-wire send=1 model=27 id=12582921 consumption=") })
        now += 30_000; reporter.tick()
        assertTrue(lines.any { it.startsWith("carplay-ev-wire send=2 status=") })
        assertFalse(lines.any { it.startsWith("carplay-ev-wire send=2 model=") })
        val failed = mutableListOf<String>()
        OalIap2CarDataReporter(caps,{now},{null},{OalEvSnapshot(currentChargeWh=20000,routingModel=m)},
            {},{ error("send failed") }, failed::add).use {
            it.authenticate(); it.handle(start)
        }
        assertFalse(failed.any { it.startsWith("carplay-ev-wire") })
    }

    @Test fun rejectedModelGroupRetriesOnNextOrdinaryWriteAndOnlyAcknowledgedGroupsAreThrottled() {
        val m = model()
        var now = 1000L
        val writes = mutableListOf<Iap2Frame>()
        val batches = mutableListOf<List<String>>()
        OalIap2CarDataReporter(OalCarDataCapabilities(currentEnergy = true, evRoutingModel = m),
            { now }, { null }, { OalEvSnapshot(currentChargeWh = 20000, routingModel = m) }, {}, writes::add, {},
            evReceipt = { receipt ->
                if (receipt.kind == OalEvWireDiagnostics.Kind.WRITTEN_MODEL) {
                    batches.add(receipt.lines)
                    batches.size > 1 // Reject the whole first group, never just one line.
                } else true
            }).use { reporter ->
            reporter.authenticate()
            reporter.handle(Iap2Messages.buildRaw(0xA100) { void(21) })
            now += 29_999; reporter.tick(); assertEquals(1, writes.size)
            now++; reporter.tick(); assertEquals(2, writes.size)
            now += 30_000; reporter.tick(); assertEquals(3, writes.size)
        }
        assertEquals(listOf(9, 9), batches.map { it.size })
        assertTrue(batches[0].all { it.startsWith("carplay-ev-wire send=1 ") })
        assertTrue(batches[1].all { it.startsWith("carplay-ev-wire send=2 ") })
    }

    @Test fun disabledOrUnselectedModelsNeverExpandOfferOrLeakIntoFrames() {
        val m=model()
        val base=Iap2Messages.buildRaw(0x1D01) {u16List(6,emptyList());u16List(7,emptyList())}
        val caps=OalCarDataCapabilities(range=true,currentEnergy=true,evRoutingModel=m)
        val offer=Iap2BodyReader.of(OalCarDataIdentification.apply(base,caps))
        val status=Iap2BodyReader.of(Iap2Frame(0,offer.raw(21)))
        assertTrue(status.has(21)); assertFalse(status.has(26)); assertFalse(status.has(27))
        val sent=mutableListOf<Iap2Frame>()
        val link=OalIap2CarDataReporter(caps,{1000},{null},{OalEvSnapshot(123,20000,routingModel=m)},{},sent::add,{})
        link.authenticate(); link.handle(Iap2Messages.buildRaw(0xA100) {void(3)})
        assertEquals(listOf(3),Iap2BodyReader.of(sent.single()).list().map {it.id})
        val disabled=OalIap2CarDataReporter(caps.copy(evRoutingModel=null),{1000},{null},{OalEvSnapshot(currentChargeWh=20000,routingModel=m)},{},sent::add,{})
        disabled.authenticate(); disabled.handle(Iap2Messages.buildRaw(0xA100) {void(21)})
        assertEquals(listOf(22),Iap2BodyReader.of(sent.last()).list().map {it.id})
        link.close(); disabled.close()
    }
}
