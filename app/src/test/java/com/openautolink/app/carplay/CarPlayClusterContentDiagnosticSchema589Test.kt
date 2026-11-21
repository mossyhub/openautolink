package com.openautolink.app.carplay

import org.junit.Assert.*
import org.junit.Test

/** Companion export boundary to the actual encrypted-wire diagnostics test in carplay-lab.
 * Parent integrates the four strict schemas once into its concurrently owned diagnostics file.
 */
class CarPlayClusterContentDiagnosticSchema589Test {
    private val receipts = listOf(
        "BENCH_DIAG clusterPrepared content=MAP_WITH_CARD offered=true",
        "BENCH_DIAG clusterPrepared content=absent offered=false",
        "BENCH_DIAG clusterOffer content=MAP supported=true suggested=true instruments=true uuidMatch=true owner=3",
        "BENCH_DIAG clusterOffer content=absent supported=false suggested=false instruments=false uuidMatch=false owner=3",
        "BENCH_DIAG clusterSetup epoch=2 requestUuid=absent target=alt owner=3",
        "BENCH_DIAG clusterSetup epoch=2 requestUuid=match target=alt owner=3",
        "BENCH_DIAG clusterSetup epoch=2 requestUuid=mismatch target=alt owner=3",
        "BENCH_DIAG clusterSelection epoch=2 content=INSTRUCTION_CARD showWritten=true keyframeWritten=true owner=3",
        "BENCH_DIAG clusterSelection epoch=2 content=MAP_WITH_CARD showWritten=true keyframeWritten=false owner=3",
        "BENCH_DIAG clusterSelection epoch=2 content=other showWritten=false keyframeWritten=false owner=3")

    @Test fun finiteLocalWriteReceiptsSurviveTheRealSanitizerAndReporter() {
        val reported = arrayListOf<String>()
        val reporter = CarPlayAudioDiagnosticReporter(7, { 0L }, reported::add)
        receipts.forEach { line ->
            assertEquals("CARPLAY_AUDIO session=7 $line", CarPlayAudioDiagnostics.sanitize(7, line))
            reporter.record(line)
        }
        assertEquals(receipts.map { "CARPLAY_AUDIO session=7 $it" }, reported)
    }

    @Test fun arbitraryUrlsQueriesIdentityAndFalsePhoneAcknowledgmentsFailClosed() {
        receipts.forEach { line ->
            listOf(" url=maps:/car/instrumentcluster?private", " uuid=private", " phone-accepted=true", "\n", "\u0000").forEach {
                assertNull(CarPlayAudioDiagnostics.sanitize(7, line + it))
            }
            assertNull(CarPlayAudioDiagnostics.sanitize(0, line))
        }
        listOf(
            receipts[0].replace("MAP_WITH_CARD", "maps:/car/instrumentcluster?private"),
            receipts[2].replace("owner=3", "owner=-1"),
            receipts[2].replace("owner=3", "owner=33333333333333333333"),
            receipts[4].replace("epoch=2", "epoch=0"),
            receipts[4].replace("target=alt", "target=main"),
            receipts[7].replace("showWritten=true", "showWritten=phone-accepted")
        ).forEach { assertNull(it, CarPlayAudioDiagnostics.sanitize(7, it)) }
    }
}
