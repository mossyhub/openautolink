package com.openautolink.app.secondary

import java.util.concurrent.*
import java.util.concurrent.locks.ReentrantLock
import org.junit.Assert.*
import org.junit.Test

class PageReturnDiagnosticsTest {
    @Test fun fullQueueNeverWaitsForBlockedSinkAndCountsLossWithFifoCapturedFacts() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(5)
        val events=CopyOnWriteArrayList<String>()
        val sink=BoundedPageDiagnosticSink(4) { message ->
            entered.countDown();check(release.await(5,TimeUnit.SECONDS));events.add(message);done.countDown()
        }
        val producer=Executors.newSingleThreadExecutor()
        try {
            assertTrue(sink.tryOffer("session=41 attachment=7 stage=submit"))
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            repeat(4) { assertTrue(sink.tryOffer("session=41 attachment=7 stage=ack index=$it")) }
            producer.submit<Boolean> {
                repeat(1000) { assertFalse(sink.tryOffer("session=42 stage=overflow")) };true
            }.get(1,TimeUnit.SECONDS)
            assertEquals(1000L,sink.dropped)
            assertEquals(1L,release.count)
            release.countDown();assertTrue(done.await(2,TimeUnit.SECONDS))
            assertEquals(5,events.size)
            events.forEachIndexed { index,message ->
                assertTrue(message.contains("session=41 attachment=7"))
                assertTrue(message.contains("diagnosticSeq=${index+1} "))
                if (index>0) assertTrue(message.endsWith("diagnosticDropped=1000"))
            }
        } finally { release.countDown();sink.close();producer.shutdownNow() }
    }
    @Test fun contendedAdmissionNeverWaitsAndOversizedFactsAreCounted() {
        val sink=BoundedPageDiagnosticSink(4) { }
        val field=sink.javaClass.getDeclaredField("lock").apply { isAccessible=true }
        val lock=field.get(sink) as ReentrantLock
        val producer=Executors.newSingleThreadExecutor()
        try {
            lock.lock()
            try { assertFalse(producer.submit<Boolean> { sink.tryOffer("stage=contended") }.get(1,TimeUnit.SECONDS)) }
            finally { lock.unlock() }
            assertFalse(sink.tryOffer("x".repeat(2049)))
            assertEquals(2L,sink.dropped)
        } finally { sink.close();producer.shutdownNow() }
    }
}
