package com.openautolink.app.carplay

import com.openautolink.carplaylab.media.ReceiverTelemetrySnapshot
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class CarPlayTelemetryIntegrationTest {
    @Test fun coordinatorPublishesNumericSnapshotOnlyForCurrentUnrevokedAttempt() {
        val running = CountDownLatch(1)
        val release = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val state = AtomicReference(CarPlayUiState())
        val coordinator = CarPlayCoordinator(CarPlayOwnership(), {}) {
            state.set(it)
            if (it.selected && !it.busy) ended.countDown()
        }
        try {
            coordinator.connect(true) { object : CarPlayRuntime {
                override fun telemetrySnapshot() = ReceiverTelemetrySnapshot(42, renderedFrames = 12)
                override fun run() { running.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
                override fun cancel() {}
                override fun close() { release.countDown() }
            } }
            assertTrue(running.await(3, TimeUnit.SECONDS))
            val status = state.get().status
            coordinator.refreshTelemetry()
            assertEquals(12L, state.get().evidence.telemetry?.renderedFrames)
            assertEquals(status, state.get().status)
            coordinator.stop()
            assertTrue(ended.await(3, TimeUnit.SECONDS))
            coordinator.refreshTelemetry()
            assertNull(state.get().evidence.telemetry)
        } finally { release.countDown(); coordinator.close() }
    }

    /** Wiring receipt only: Android callback execution still requires parent phone/device proof. */
    @Test fun callbacksMeasureActualSinkBoundariesAndUiPollsWithoutStatusParsing() {
        val lab = File("../carplay-lab/src/main/java")
        val sink = File(lab, "com/shilapi/xcertplay/media/AndroidMediaSink.kt").readText()
        val receiver = File(lab, "com/openautolink/carplaylab/media/ProjectionReceiver.kt").readText()
        val app = File("src/main/java/com/openautolink/app")
        val runtime = File(app, "carplay/OalCarPlayRuntime.kt").readText()
        val vm = File(app, "ui/projection/ProjectionViewModel.kt").readText()
        assertTrue(sink.contains("telemetry.frameRendered(nanoTime)"))
        assertTrue(sink.contains("telemetry.decoderConfigured(candidate.name)"))
        assertTrue(sink.contains("telemetry.outputFormat(width, height,"))
        assertTrue(sink.contains("if (queue.offer(Packet(bytes,sample))) telemetry.audioPacketAccepted()"))
        assertTrue(sink.contains("telemetry.audioWritten(count)"))
        assertTrue(receiver.contains("fun telemetrySnapshot()"))
        assertTrue(receiver.contains("telemetry.retire()"))
        assertTrue(runtime.contains("override fun telemetrySnapshot()"))
        assertTrue(vm.contains("carPlayCoordinator.refreshTelemetry()"))
    }
}
