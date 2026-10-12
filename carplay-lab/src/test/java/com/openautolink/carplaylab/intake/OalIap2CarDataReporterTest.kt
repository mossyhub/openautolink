package com.openautolink.carplaylab.intake

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class OalIap2CarDataReporterTest {
    private var now = 0L
    private val sent = mutableListOf<Iap2Frame>()
    private val cache = OalNmeaCache { now }.also { it.accept(OalNmeaCacheTest.gga); it.accept(OalNmeaCacheTest.rmc) }
    private fun start(vararg selectors: Int, interval: Long = 1000) = Iap2Messages.buildRaw(0xFFFA) {
        u16(0,0); selectors.forEach { void(it) }; u32(0x8001,interval)
    }
    private fun link() = OalIap2CarDataReporter(
        OalCarDataCapabilities(location = true), { now }, cache::latest,
        { null }, {}, { sent += it }, {},
    )
    @Test fun receiptsIdentifyRequestedSentenceAndPresentEvFieldsWithoutValues() {
        val logs=mutableListOf<String>()
        val link=OalIap2CarDataReporter(OalCarDataCapabilities(location=true,currentEnergy=true),{now},cache::latest,
            {OalEvSnapshot(currentChargeWh=0)}, {}, {sent+=it}, {logs+=it})
        link.authenticate(); link.handle(start(2)); link.handle(Iap2Frame(0xA100,byteArrayOf()))
        assertTrue(logs.any {it.contains("id=65530 reason=started count=0 fields=2 ")})
        assertTrue(logs.any {it.contains("reason=transmitted count=2 fields=8 ")})
    }

    @Test fun stopWaitsForAdmittedWriteThenNoRetiredTickCanSend() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        val writes = java.util.concurrent.atomic.AtomicInteger()
        val link = OalIap2CarDataReporter(OalCarDataCapabilities(location=true),{now},cache::latest,{null},{},
            {entered.countDown(); assertTrue(release.await(2,java.util.concurrent.TimeUnit.SECONDS)); writes.incrementAndGet()}, {})
        try {
            link.authenticate()
            val startFuture = executor.submit {link.handle(start(1))}
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            val stop = executor.submit {link.handle(Iap2Frame(0xFFFC,byteArrayOf()))}
            release.countDown(); startFuture.get(2,java.util.concurrent.TimeUnit.SECONDS); stop.get(2,java.util.concurrent.TimeUnit.SECONDS)
            now=2000; repeat(100) {link.tick()}; link.close(); link.authenticate(); link.handle(start(1))
            assertEquals(1,writes.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun pollTimeoutTracksDueAttemptNotSuccessfulSampleAndClampsShortIntervals() {
        val link=link(); assertEquals(60000L,link.pollTimeout(60000))
        link.authenticate(); link.handle(start(1,interval=1))
        assertEquals(1000L,link.pollTimeout(60000))
        now=999; assertEquals(1L,link.pollTimeout(60000))
        now=1000; link.tick(); assertEquals(1000L,link.pollTimeout(60000))
        link.handle(Iap2Frame(0xFFFC,byteArrayOf(1))) // stop revokes even malformed payload
        assertEquals(60000L,link.pollTimeout(60000))
        link.close(); assertEquals(60000L,link.pollTimeout(60000))
    }

    @Test fun reporterEnforcesNmeaSizeAndSelectionAtProviderBoundary() {
        var payload = OalNmeaCacheTest.rmc
        val link = OalIap2CarDataReporter(OalCarDataCapabilities(location=true),{now},{payload}, {null},{},{sent+=it},{})
        link.authenticate(); link.handle(start(1)); assertTrue(sent.isEmpty())
        payload = "x".repeat(4096); now=1000; link.tick(); assertTrue(sent.isEmpty())
        payload = OalNmeaCacheTest.gga + "\r\n" + OalNmeaCacheTest.rmc
        now=2000; link.tick(); assertEquals(1,sent.size)
        assertEquals(OalNmeaCacheTest.gga+"\r\n", Iap2BodyReader.of(sent.single()).string(0))
    }

    @Test fun transmissionReceiptsContainNoNmeaAndFailureRetiresWithoutClaimingConsumption() {
        val logs = mutableListOf<String>()
        var stopped = false
        val link = OalIap2CarDataReporter(OalCarDataCapabilities(location=true), {now}, cache::latest,
            {null}, {stopped = !it}, { throw IllegalStateException("secret-coordinate") }, {logs += it})
        link.authenticate(); link.handle(start(1)); now=1000; link.tick()
        assertTrue(stopped)
        assertTrue(logs.any {it.contains("reason=send_failed")})
        assertFalse(logs.any {it.contains("reason=transmitted")})
        assertFalse(logs.any {it.contains("secret") || it.contains("4807") || it.contains("consumed")})
        val working = OalIap2CarDataReporter(OalCarDataCapabilities(location=true), {now}, cache::latest,
            {null}, {}, {sent += it}, {logs += it})
        working.authenticate(); working.handle(start(2))
        assertEquals(1,sent.size)
        assertTrue(logs.any {it.matches(Regex("carplay-car-data id=65531 reason=transmitted count=1 fields=1 suppressed=[0-9]+"))})
    }

    @Test fun throwingDiagnosticSinkCannotBreakReportingOrStop() {
        val link = OalIap2CarDataReporter(OalCarDataCapabilities(location=true), {now}, cache::latest,
            {null}, {}, {sent += it}, {throw IllegalStateException("secret")})
        link.authenticate(); link.handle(start(1)); assertEquals(1,sent.size)
        link.close(); now=1000; link.tick(); assertEquals(1,sent.size)
    }

    @Test fun evEmptyStartIsSourceBackedButUnknownSelectorsFailClosedAndMissingDoesNotSpin() {
        var reads = 0
        var snapshot: OalEvSnapshot? = null
        val link = OalIap2CarDataReporter(OalCarDataCapabilities(currentEnergy=true), {now}, cache::latest,
            { reads++; snapshot }, {}, {sent += it}, {})
        link.authenticate(); link.handle(Iap2Frame(0xA100,byteArrayOf()))
        repeat(100) { link.tick() }; assertEquals(1,reads)
        snapshot = OalEvSnapshot(100,0,60000)
        now=29999; link.tick(); assertTrue(sent.isEmpty())
        now=30000; link.tick(); assertEquals(1,sent.size)
        assertEquals(listOf(22), Iap2BodyReader.of(sent.single()).list().map {it.id})
        link.handle(Iap2Messages.buildRaw(0xA100) {void(22)}) // unsupported request semantics, not silently guessed
        now=60000; link.tick(); assertEquals(1,sent.size)
        link.handle(Iap2Frame(0xA100,byteArrayOf())); link.handle(Iap2Frame(0xA102,byteArrayOf()))
        now=90000; link.tick(); assertEquals(2,sent.size)
        link.close(); now=120000; link.tick(); assertEquals(2,sent.size)
    }

    @Test fun malformedUnknownComponentAndUnsupportedOnlyRequestsRevokeWithoutThrowing() {
        val invalid = listOf(
            Iap2Frame(0xFFFA, byteArrayOf(0,3,0)),
            Iap2Messages.buildRaw(0xFFFA) { u16(0,7); void(1) },
            Iap2Messages.buildRaw(0xFFFA) { u16(0,0); u8(1,1) },
            Iap2Messages.buildRaw(0xFFFA) { void(1); void(1) },
            Iap2Messages.buildRaw(0xFFFA) { void(1); void(99) },
            start(4), start(1,interval=0),
        )
        invalid.forEach { bad ->
            sent.clear(); now = 0
            val link = link(); link.authenticate(); link.handle(start(1)); assertEquals(1,sent.size)
            assertTrue(link.handle(bad))
            now = 1000; link.tick(); assertEquals(1,sent.size)
        }
    }

    @Test fun requestedIntervalIsHonoredAndRetiredLinksCanNeverReauthenticate() {
        val link = link(); link.authenticate(); link.handle(start(2,interval=2500))
        now=1000; link.tick(); assertEquals(1,sent.size)
        now=2500; link.tick(); assertEquals(2,sent.size)
        link.close(); link.authenticate(); link.handle(start(1)); now=3000; link.tick()
        assertEquals(2,sent.size)
    }

    @Test fun authenticatedSameLinkStartSendsRequestedSentenceAtBoundedCadenceAndStopRevokes() {
        val link = link()
        link.handle(start(1))
        link.tick()
        assertTrue(sent.isEmpty())
        link.authenticate()
        link.tick()
        assertTrue(sent.isEmpty()) // pre-auth request did not authorize a later send
        link.handle(start(1,4))
        assertEquals(1, sent.size)
        assertEquals(OalNmeaCacheTest.gga + "\r\n", Iap2BodyReader.of(sent.single()).string(0))
        assertEquals(0, sent.single().payload.last().toInt())
        repeat(100) { link.tick() }
        assertEquals(1, sent.size)
        now = 999; link.tick(); assertEquals(1, sent.size)
        now = 1000; link.tick(); assertEquals(2, sent.size)
        link.handle(Iap2Frame(0xFFFC, byteArrayOf()))
        now = 2000; link.tick(); assertEquals(2, sent.size)
        link().also { it.authenticate(); it.tick() }
        assertEquals(2, sent.size)
    }
}
