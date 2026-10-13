package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MicrophonePolicyBoundsTest {
    @Test fun stalledQueryDropsOtherCaptureObservationsRatherThanQueuingThem() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        val rejectedQueries=AtomicInteger()
        val owner=MicrophonePolicyDiagnostics({ entered.countDown();release.await();"receipt" },{done.countDown()})
        val other=MicrophonePolicyDiagnostics({rejectedQueries.incrementAndGet();"receipt"},{})
        try {
            owner.sample();assertTrue(entered.await(2,TimeUnit.SECONDS))
            repeat(100) {other.sample()}
            other.close() // must not wait on owner's OS query
            release.countDown();assertTrue(done.await(2,TimeUnit.SECONDS))
            Thread.sleep(50)
            assertEquals("no queued work may replay into later captures",0,rejectedQueries.get())
        } finally {release.countDown();owner.close();other.close()}
    }
    @Test fun eachCaptureHasAtMostThreeObservationAttempts() {
        val reports=Semaphore(0);val count=AtomicInteger()
        val policy=MicrophonePolicyDiagnostics({count.incrementAndGet();"receipt"},{reports.release()})
        try {
            repeat(3) {policy.sample();assertTrue(reports.tryAcquire(2,TimeUnit.SECONDS));Thread.sleep(30)}
            repeat(100) {policy.sample()}
            assertFalse(reports.tryAcquire(100,TimeUnit.MILLISECONDS))
            assertEquals(3,count.get())
        } finally {policy.close()}
    }
}
