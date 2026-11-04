package com.openautolink.app.diagnostics.gm

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GmAdmissionQueueTest {
    @Test fun rejectedScheduleIsContainedAndLaterOfferRearmsWithoutInlineConsumer() {
        var calls=0;var next:Runnable?=null
        val consumed=mutableListOf<Int>()
        val queue=GmAdmissionQueue<Int>(schedule={if(calls++==0)throw java.util.concurrent.RejectedExecutionException() else next=it},consume={consumed.add(it)})
        assertFalse(queue.offer(1));assertTrue(consumed.isEmpty());assertEquals(1L,queue.dropped)
        assertTrue(queue.offer(2));assertTrue(consumed.isEmpty())
        requireNotNull(next).run()
        assertEquals(listOf(2),consumed);assertTrue(queue.isIdle())
    }
    @Test fun capacityIsBoundedWhileConsumerHeldAndPositiveWorkSurvivesFailure() {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val pool=Executors.newSingleThreadExecutor()
        val consumed=java.util.concurrent.CopyOnWriteArrayList<Int>()
        val queue=GmAdmissionQueue<Int>(capacity=2,schedule={pool.execute(it)},consume={
            if(it==1){entered.countDown();check(release.await(5,TimeUnit.SECONDS))}
            if(it==2)throw IllegalStateException("fixture write failure")
            consumed.add(it)
        })
        try {
            assertTrue(queue.offer(1));assertTrue(entered.await(5,TimeUnit.SECONDS))
            assertTrue(queue.offer(2));assertTrue(queue.offer(3));assertFalse(queue.offer(4))
            assertEquals(1L,queue.dropped);assertFalse(queue.isIdle())
            release.countDown()
            val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!queue.isIdle() && System.nanoTime()<until)Thread.sleep(5)
            assertTrue(queue.isIdle());assertEquals(listOf(1,3),consumed)
            assertEquals(2L,queue.dropped)
            assertTrue(queue.offer(5))
            val after=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(!queue.isIdle() && System.nanoTime()<after)Thread.sleep(5)
            assertEquals(listOf(1,3,5),consumed)
        }finally{release.countDown();pool.shutdownNow()}
    }
    @Test fun fullCapacityNeverExecutesConsumerOnProducer() {
        var scheduled:Runnable?=null;var invoked=0
        val queue=GmAdmissionQueue<Int>(capacity=2,schedule={scheduled=it},consume={invoked++})
        assertTrue(queue.offer(1));assertTrue(queue.offer(2))
        repeat(10000){assertFalse(queue.offer(3))}
        assertEquals(0,invoked);assertEquals(10000L,queue.dropped)
        requireNotNull(scheduled).run();assertEquals(2,invoked)
    }
}
