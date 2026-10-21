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

    @Test fun retentionReservationIsFinitePerReceiverWithIndependentFinalAndBoundedDeliveryRetries() {
        val captured=mutableListOf<String>();var calls=0
        val record=reporter(9,{0L}) {line -> calls++;if(calls<=2) error("temporary sink failure");captured.add(line)}
        fun status(receiver:Int,loss:Int,final:Boolean=false) =
            "media stage=microphone-retention receiver=$receiver final=$final pending=0 owners=0 lost=$loss"
        record(status(1,0));assertEquals("Retry only failed delivery, within finite three-attempt allowance",3,calls)
        repeat(1000) {record(status(1,0))};assertEquals(3,calls)
        // A detail flood exhausts changed-state slots, never the separate final reservation.
        for(receiver in 1..5) {
            for(loss in 0..999) record(status(receiver,loss))
            repeat(1000) {record(status(receiver,999,true))}
        }
        assertEquals(100,captured.size) // four receivers * (24 changed + one final)
        assertEquals(4,captured.count {"final=true" in it})
        assertTrue(captured.none {"receiver=5 " in it})
        val failed=java.util.concurrent.atomic.AtomicInteger()
        val never=reporter(10,{0L}) {failed.incrementAndGet();error("persistent failure")}
        repeat(1000) {never(status(1,1,true))}
        assertEquals("Duplicate reoffers cannot reset exhausted delivery attempts",3,failed.get())
    }

    @Test fun retentionReservationRejectsPrivateSuffixesControlsUnknownsAndInvalidIdentity() {
        val captured=mutableListOf<String>();val record=reporter(11,{0L},captured::add)
        val good="media stage=microphone-retention receiver=1 final=true pending=0 owners=0 lost=1"
        val bad=listOf(good+" privateKey=secret",good+"\nsecret",good+"\r",good+"\u0000",
            good.replace("final=true","final=unknown"),good.replace("receiver=1","receiver=0"),
            good.replace("receiver=1","receiver=9223372036854775808"),good.replace("pending=0","pending=13"),
            good.replace("owners=0","owners=-1"),good.replace("lost=1","lost=-1"))
        bad.forEach(record);assertTrue(captured.isEmpty())
        repeat(64) {record("media stage=audio-queue-overflow")};record(good)
        assertEquals(65,captured.size);assertTrue(captured.last().endsWith(good))
    }

    @Test fun receiverQueueDropsHaveExactNumericExportOnly() {
        assertEquals("CARPLAY_AUDIO session=7 media stage=status-queue drops=12",
            CarPlayAudioDiagnostics.sanitize(7, "media stage=status-queue drops=12"))
        for (invalid in listOf("-1", "unknown", "12 privateKey=secret", "12\nsecret")) {
            assertNull(CarPlayAudioDiagnostics.sanitize(7, "media stage=status-queue drops=$invalid"))
        }
    }
}
