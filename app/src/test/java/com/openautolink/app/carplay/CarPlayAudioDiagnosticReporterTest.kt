package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class CarPlayAudioDiagnosticReporterTest {
    private fun reporter(session: Long, clock: () -> Long, sink: (String) -> Unit): (String) -> Unit {
        val type = try { Class.forName("com.openautolink.app.carplay.CarPlayAudioDiagnosticReporter") }
            catch (_: ClassNotFoundException) { null }
        assertNotNull("bounded diagnostic reporter must exist", type)
        val instance = type!!.constructors.single().newInstance(session, clock, sink)
        return { message -> type.getMethod("record", String::class.java).invoke(instance, message); Unit }
    }

    @Test fun boundedRateReportsSuppressionWhenNextWindowOpens() {
        var now = 0L
        val captured = mutableListOf<String>()
        val record = reporter(3, { now }, captured::add)
        repeat(1000) { record("media stage=audio-queue-overflow") }
        assertEquals(64, captured.size)
        now = 1_000_000_000L
        record("BENCH_DIAG responseStatus=200")
        assertEquals("CARPLAY_AUDIO session=3 diagnosticsSuppressed=936", captured[64])
        assertEquals("CARPLAY_AUDIO session=3 BENCH_DIAG responseStatus=200", captured[65])
        assertTrue(captured.all { it.length <= CarPlayAudioDiagnostics.MAX_LINE_LENGTH })
    }

    @Test fun invalidMessagesNeitherReachSinkNorConsumeAllowedBudget() {
        val captured = mutableListOf<String>()
        val record = reporter(4, { 0L }, captured::add)
        repeat(1000) { record("BENCH_DIAG privateKey=secretSSID") }
        record("BENCH_DIAG responseStatus=400")
        assertEquals(listOf("CARPLAY_AUDIO session=4 BENCH_DIAG responseStatus=400"), captured)
    }

    @Test fun sinkFailureCannotEscapeOrDisableFutureObservation() {
        var calls = 0
        val record = reporter(5, { 0L }) { calls++; if (calls == 1) error("sink unavailable") }
        record("BENCH_DIAG responseStatus=400")
        record("BENCH_DIAG responseStatus=200")
        assertEquals(2, calls)
    }

    @Test fun runtimeWiresOnlySanitizedDiagnosticsAtExistingBoundedReceiverWorker() {
        val runtime = File("src/main/java/com/openautolink/app/carplay/OalCarPlayRuntime.kt").readText()
        assertTrue(runtime.contains("CarPlayAudioDiagnosticReporter("))
        assertTrue(runtime.contains("audioDiagnostics.record(message)"))
        assertTrue(runtime.contains("DiagnosticLog.i(\"carplay-audio\", line)"))
        assertTrue(runtime.contains("Log.i(\"OalCarPlayAudio\", line)"))
        assertFalse(runtime.contains("Log.i(\"OalCarPlayAudio\", message)"))
        assertFalse(runtime.contains("DiagnosticLog.i(\"carplay-audio\", message)"))
        val receiver = File("../carplay-lab/src/main/java/com/openautolink/carplaylab/media/ProjectionReceiver.kt").readText()
        assertTrue(receiver.contains("ArrayBlockingQueue(64)"))
        assertTrue(receiver.contains("statuses.execute {"))
        assertTrue(receiver.contains("runCatching { onStatus(message) }"))
        assertTrue(receiver.contains("statusQueueDrops.getAndSet(0)"))
        assertTrue(receiver.contains("media stage=status-queue drops="))
        assertFalse(receiver.contains("ThreadPoolExecutor.DiscardPolicy()"))
    }

    @Test fun receiverQueueDropsHaveExactNumericExportOnly() {
        assertEquals("CARPLAY_AUDIO session=7 media stage=status-queue drops=12",
            CarPlayAudioDiagnostics.sanitize(7, "media stage=status-queue drops=12"))
        for (invalid in listOf("-1", "unknown", "12 privateKey=secret", "12\nsecret")) {
            assertNull(CarPlayAudioDiagnostics.sanitize(7, "media stage=status-queue drops=$invalid"))
        }
    }
}
