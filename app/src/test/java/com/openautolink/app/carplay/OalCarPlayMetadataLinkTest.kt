package com.openautolink.app.carplay

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.*
import org.junit.Test

class OalCarPlayMetadataLinkTest {
    @Test fun routeReceiptsDistinguishFailuresCacheMissesAndClearWithoutRoadOrIds() {
        var now = 0L
        val receipts = mutableListOf<String>()
        val link = OalCarPlayMetadataLink({ now }, receipts::add)
        link.authenticate()
        link.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(77)); string(3,"Private road") })
        assertTrue(receipts.any { it.startsWith("carplay-nav kind=route parsed=1 failed=0 result=parsed") && it.contains("cached=false publishable=false") })
        link.handle(Iap2Messages.buildRaw(0x5202) { u16(1,77); u8(3,2) })
        assertTrue(receipts.any { it.contains("publishable=true transition=start") })
        now = 5000
        link.handle(Iap2Messages.buildRaw(0x5201) { bytes(1,byteArrayOf(1,2)) })
        assertTrue(receipts.any { it.contains("result=failed") && it.contains("publishable=true transition=none") })
        link.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,emptyList()) })
        assertTrue(receipts.any { it.contains("transition=clear reason=empty") })
        link.handle(Iap2Messages.buildRaw(0x5201) { u16List(13,listOf(77)) })
        now = 35000; link.tick()
        assertTrue(receipts.any { it.contains("transition=clear reason=stale") })
        assertFalse(receipts.any { it.contains("Private") || it.contains("77") })
        receipts.filter { it.startsWith("carplay-nav") }.forEach { assertNotNull(it, CarPlayAudioDiagnostics.sanitize(1,it)) }
        link.close()
    }

    @Test fun guidancePublicationIsRecordedAfterHostReturnsAndClearIsIndependentOfMusic() {
        val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val host = object : CarPlayMetadataHost {
            override fun start() = true
            override fun publish(media: com.openautolink.carplaylab.metadata.CarPlayMediaMetadata?, navigation: com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata?, artwork: ByteArray?) {
                if (navigation != null) { entered.countDown(); release.await(2, java.util.concurrent.TimeUnit.SECONDS) }
            }
            override fun close() {}
        }
        val link = OalCarPlayMetadataLink({ 0L }, lines::add, host)
        try {
            link.authenticate()
            link.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Private music") } })
            link.handle(Iap2Messages.buildRaw(0x5202) { u16(1,1); u8(3,2) })
            link.handle(Iap2Messages.buildRaw(0x5201) { u8(1,1); u16List(13,listOf(1)) })
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(lines.contains("carplay-nav publication=returned guidance=true transition=start"))
            release.countDown()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (!lines.contains("carplay-nav publication=returned guidance=true transition=start") && System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue(lines.contains("carplay-nav publication=returned guidance=true transition=start"))
            link.handle(Iap2Messages.buildRaw(0x5201) { u8(1,0) })
            val clearDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
            while (!lines.contains("carplay-nav publication=returned guidance=false transition=clear") && System.nanoTime() < clearDeadline) Thread.sleep(5)
            assertTrue(lines.contains("carplay-nav publication=returned guidance=false transition=clear"))
        } finally { release.countDown(); link.close() }
    }

    @Test fun revokeIsNonblockingButCloseWaitsForAdmittedHostWrite() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val closed = java.util.concurrent.CountDownLatch(1)
        var writes = 0
        val host = object : CarPlayMetadataHost {
            override fun start() = true
            override fun publish(media: com.openautolink.carplaylab.metadata.CarPlayMediaMetadata?, navigation: com.openautolink.carplaylab.metadata.CarPlayNavigationMetadata?, artwork: ByteArray?) {
                if (media == null) return
                writes++; entered.countDown(); release.await(2, java.util.concurrent.TimeUnit.SECONDS)
            }
            override fun close() { closed.countDown() }
        }
        val link = OalCarPlayMetadataLink({ 0L }, {}, host)
        link.authenticate()
        link.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"First") } })
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        link.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Queued") } })
        link.revoke()
        val closer = Thread { link.close() }; closer.start()
        assertFalse(closed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        release.countDown(); closer.join(2000)
        assertTrue(closed.await(1, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, writes)
    }

    @Test fun authenticatedIntakePublishesOnWorkerAndCloseAwaitsRetirement() {
        val hostType = runCatching { Class.forName("com.openautolink.app.carplay.CarPlayMetadataHost") }.getOrNull()
        assertNotNull("Authenticated intake needs an awaited host publication adapter", hostType)
        val published = java.util.concurrent.CountDownLatch(1)
        var title: String? = null
        var retired = false
        val host = java.lang.reflect.Proxy.newProxyInstance(hostType!!.classLoader, arrayOf(hostType)) { _, method, args ->
            when (method.name) {
                "start" -> true
                "publish" -> { title = (args[0] as com.openautolink.carplaylab.metadata.CarPlayMediaMetadata).title; published.countDown(); Unit }
                "close" -> { retired = true; Unit }
                else -> Unit
            }
        }
        val constructor = OalCarPlayMetadataLink::class.java.declaredConstructors.find { it.parameterCount == 3 }
        assertNotNull(constructor)
        val link = constructor!!.newInstance({ 0L }, { _: String -> }, host) as OalCarPlayMetadataLink
        val frame = Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Song") } }
        link.handle(frame)
        assertFalse(published.await(100, java.util.concurrent.TimeUnit.MILLISECONDS))
        link.authenticate(); link.handle(frame)
        assertTrue(published.await(2, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("Song", title)
        link.close(); assertTrue(retired)
        link.handle(frame)
    }

    @Test fun intakeReceiptsNeverPublishPhoneNamesTitlesOrClaimHostSuccess() {
        val receipts = mutableListOf<String>()
        val link = OalCarPlayMetadataLink({ 0L },receipts::add)
        link.authenticate()
        assertTrue(link.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Private song") } }))
        assertTrue(receipts.single().contains("media=true"))
        assertTrue(receipts.single().contains("hostPublication=false"))
        assertFalse(receipts.single().contains("Private"))
        link.revoke()
        link.authenticate()
        link.handle(Iap2Messages.buildRaw(0x5001) { group(0) { string(1,"Late") } })
        assertEquals(1,receipts.size)
    }
}
