package com.openautolink.app.ui.diagnostics

import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class ParkedCaptureComparisonTest {
    private class Queue: Executor {
        val tasks=java.util.ArrayDeque<Runnable>()
        override fun execute(command:Runnable){ tasks.add(command) }
        fun drain(){while(tasks.isNotEmpty())tasks.remove().run()}
    }
    private class Run(val backend:Int): NumericCaptureRun {
        var done=false
        var termination=1
        var disposed=false
        override var numbers:LongArray?=null
        override fun begin(){}
        override fun cancel(){}
        override fun pollClosedJoin():NativeCaptureOwnerRegistry.Receipt? {
            if(!done)return null
            numbers=LongArray(32).also { it[0]=backend.toLong();it[1]=1;it[6]=1;it[7]=1;it[31]=2000;it[18]=-1 }
            return NativeCaptureOwnerRegistry.Receipt(termination,0,0,0,false)
        }
        override fun disposeJoined(){disposed=true}
    }
    @Test fun cancelIsNonblockingEvenWhileNumericStorageIsBlocked() {
        val queue=Queue();val registry=NativeCaptureOwnerRegistry(queue){false}
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        lateinit var run:Run
        val comparison=ParkedCaptureComparison(registry,{ backend,_->Run(backend).also { run=it } },{entered.countDown();release.await();true})
        assertTrue(comparison.start { true });queue.drain();run.done=true
        comparison.poll();queue.drain()
        val poll=Thread { comparison.poll() }.apply { start() }
        assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        val cancel=Thread { comparison.cancel() }.apply { start() }
        try {
            cancel.join(150)
            assertFalse("UI revocation cannot wait for storage/native owner",cancel.isAlive)
        } finally { release.countDown();poll.join();cancel.join();repeat(3){comparison.poll();queue.drain()} }
    }
    @Test fun twoSecondDeadlineStillRunsSecondArmWithoutCallingItFrameCompletion() {
        val queue=Queue();val registry=NativeCaptureOwnerRegistry(queue){false}
        val runs=mutableListOf<Run>()
        val comparison=ParkedCaptureComparison(registry,{ backend,_->Run(backend).also(runs::add) },{true})
        assertTrue(comparison.start { true });queue.drain()
        try {
            runs[0].termination=3;runs[0].done=true
            comparison.poll();queue.drain();comparison.poll();queue.drain()
            assertEquals("a truthful 2-second deadline is not a failed recorder",2,runs.size)
        } finally {
            comparison.cancel();runs.forEach { it.done=true };repeat(3){comparison.poll();queue.drain()}
        }
    }
    @Test fun realRegistrySequentiallyDrainsEachArmBeforeNativeAndSurvivesLostUi() {
        val queue=Queue();val registry=NativeCaptureOwnerRegistry(queue){false}
        val runs=mutableListOf<Run>();val saved=mutableListOf<LongArray>()
        var allowed=true
        val comparison=ParkedCaptureComparison(registry,{ backend,_->Run(backend).also(runs::add) },{saved.add(it);true})
        assertTrue(comparison.start { allowed });queue.drain()
        assertEquals(listOf(0),runs.map{it.backend})
        comparison.poll();queue.drain();assertTrue(saved.isEmpty())
        runs[0].done=true;comparison.poll();queue.drain();comparison.poll();queue.drain()
        assertTrue(runs[0].disposed)
        assertEquals(listOf(0,1),runs.map{it.backend});assertEquals(1,saved.size)
        allowed=false;comparison.cancel() // UI may now disappear; process poll remains
        comparison.poll();queue.drain();assertNull(MicrophoneCaptureFence.tryProjectionClaim())
        runs[1].done=true;comparison.poll();queue.drain();comparison.poll()
        assertEquals(2,saved.size);assertFalse(comparison.busy)
        MicrophoneCaptureFence.tryProjectionClaim()!!.release()
    }
}
