package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class CaptureTerminalLedgerTest {
    private fun summary(owner: Long = 1): CaptureTerminalSummary {
        val pcm = Pcm16Window(1).snapshot()
        return CaptureTerminalSummary(1, owner, CaptureTerminalSummary.History.NORMAL,
            CaptureTerminalSummary.Profile.DEFAULT, MicrophoneSource.MIC, 24000, 1, 24000,
            CaptureTerminalSummary.Selection.AVERAGE, CaptureAcquisitionLedger.Termination.CANCELLED,
            0, 0, 0, pcm, pcm)
    }
    @Test fun completedOwnerIsRetainedUntilAcknowledgedAndNotDeliveredTwice() {
        val ledger = CaptureTerminalLedger()
        val tasks = mutableListOf<Runnable>()
        var attempts = 0
        ledger.bind(Executor { tasks.add(it) }) { attempts++; attempts >= 2 }
        assertTrue(ledger.complete(summary()))
        assertFalse(ledger.complete(summary()))
        assertEquals(0, attempts)
        tasks.removeAt(0).run()
        assertEquals(1, ledger.pending())
        assertTrue(ledger.flush())
        assertEquals(2, attempts)
        assertFalse(ledger.complete(summary()))
        assertTrue(ledger.flush())
        assertEquals(2, attempts)
    }
    @Test fun pendingReleaseStateIsBoundedAndNeverMisrepresentedAsCompletion() {
        val ledger=CaptureTerminalLedger()
        assertTrue(ledger.releasePending(1,1))
        assertEquals(listOf("media stage=microphone-owner receiver=1 owner=1 state=release-pending"),ledger.ownerStates())
        assertEquals(0,ledger.pending())
        ledger.releaseFailed(1,1)
        assertTrue(ledger.ownerStates().single().endsWith("state=release-failed"))
        ledger.complete(summary())
        assertTrue(ledger.ownerStates().isEmpty())
        repeat(12) { ledger.releasePending(1,it+2L) }
        assertEquals(12,ledger.ownerStates().size)
        assertFalse(ledger.releasePending(1,14))
        assertEquals(1L,ledger.lost())
        assertTrue(ledger.status(1).contains("lost=1"))
    }
    @Test fun arrivalDuringCallbackSchedulesNextDrainButDoesNotSpinFailedSink() {
        val ledger=CaptureTerminalLedger()
        val tasks=java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        ledger.bind(Executor { tasks.add(it) }) { entered.countDown(); release.await(); true }
        // Drain initial empty bind task before enqueueing completion.
        tasks.remove().run()
        ledger.complete(summary())
        val worker=Thread { tasks.remove().run() }.apply { start() }
        assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        ledger.complete(summary(2)); release.countDown(); worker.join(2000)
        assertEquals("arrival during flush must be rescheduled",1,tasks.size)
        tasks.remove().run(); assertEquals(0,ledger.pending())
        ledger.bind(Executor { tasks.add(it) }) { false }
        tasks.remove().run(); ledger.complete(summary(3)); tasks.remove().run()
        assertEquals(1,ledger.pending()); assertEquals(0,tasks.size)
    }
    @Test fun blockingDurableSinkNeverHoldsIngressMonitor() {
        val ledger=CaptureTerminalLedger()
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        ledger.bind(Executor { }) { entered.countDown(); release.await(); true }
        ledger.complete(summary())
        val worker=Thread { ledger.flush() }.apply { start() }
        assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
        val ingressed=java.util.concurrent.CountDownLatch(1)
        val producer=Thread { ledger.complete(summary(2)); ingressed.countDown() }.apply { start() }
        try { assertTrue("disk sink must not block mic ingress",ingressed.await(200,java.util.concurrent.TimeUnit.MILLISECONDS)) }
        finally { release.countDown(); worker.join(2000); producer.join(2000) }
    }
    @Test fun boundedQueueReportsLossAndRetiresAfterThreeFailedAttempts() {
        val ledger = CaptureTerminalLedger()
        repeat(12) { assertTrue(ledger.complete(summary(it + 1L))) }
        assertFalse(ledger.complete(summary(13)))
        assertEquals(1L, ledger.lost())
        ledger.bind(Executor { }) { false }
        repeat(3) { assertFalse(ledger.flush()) }
        assertEquals(0, ledger.pending())
        assertEquals(13L, ledger.lost())
    }
    @Test fun summaryDefensivelyCopiesPcmAndDistinguishesNoFramesFromZeros() {
        val channels = mutableListOf(Pcm16Window.Channel(0,0,0,0,0))
        val empty = Pcm16Window.Snapshot(0,0,0,channels)
        val value = summary().withPcm(empty, empty)
        channels.clear()
        assertEquals(1,value.raw.channels.size)
        assertTrue(value.lines().first().contains("signal=no-frames"))
        val zeros = Pcm16Window(1).apply { observe(ByteArray(4)) }.snapshot()
        assertTrue(summary().withPcm(zeros,zeros).lines().first().contains("signal=zeros"))
    }
}
