package com.openautolink.app.carplay

import android.app.Application
import com.openautolink.app.diagnostics.*
import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayCaptureTerminalExportTest {
    private fun summary(owner:Long):CaptureTerminalSummary {
        val raw=Pcm16Window(1).apply { observe(byteArrayOf(1,0,0)) }.snapshot()
        return CaptureTerminalSummary(1,owner,CaptureTerminalSummary.History.MANUAL,
            CaptureTerminalSummary.Profile.EXPERIMENT,MicrophoneSource.MIC,24000,1,24000,
            CaptureTerminalSummary.Selection.FIRST,CaptureAcquisitionLedger.Termination.CANCELLED,
            1,1,1,raw,raw)
    }
    @Test fun exactTerminalSchemasRejectUnknownPrivateSuffixesAndInvalidRouteIdentity() {
        summary(1).lines().forEach { line ->
            assertNotNull(line,CarPlayAudioDiagnostics.sanitize(1,line))
            listOf(" address=secret"," package=private","\n","\u0000").forEach {
                assertNull(CarPlayAudioDiagnostics.sanitize(1,line+it))
            }
        }
        assertNull(CarPlayAudioDiagnostics.sanitize(1,summary(1).lines()[0].replace("route=unknown","route=device42")))
    }
    @Test fun finiteOwnerRouteAndRetentionStatesRejectIdentityLeaks() {
        val safe=listOf(
            "media stage=microphone-owner receiver=1 owner=2 state=release-pending",
            "media stage=microphone-owner receiver=1 owner=2 state=release-failed",
            "media stage=microphone-retention receiver=1 final=false pending=12 owners=12 lost=1",
            "media stage=microphone-control receiver=1 history=manual-vr48-two live=vr24-mono",
            "media stage=microphone-route scope=own routed=32 active=1 equality=different stability=same truncated=false",
            "media stage=microphone-mapping scope=own result=unsupported examined=0 truncated=false",
            "media stage=microphone-channel scope=own row=3 channel=7 mapping=processed")
        safe.forEach { marker ->
            assertNotNull(marker,CarPlayAudioDiagnostics.sanitize(1,marker))
            listOf(" address=private"," id=42"," name=secret","\n","\u0000").forEach {
                assertNull(CarPlayAudioDiagnostics.sanitize(1,marker+it))
            }
        }
        assertNull(CarPlayAudioDiagnostics.sanitize(1,safe[4].replace("routed=32","routed=33")))
        assertNull(CarPlayAudioDiagnostics.sanitize(1,safe[6].replace("channel=7","channel=8")))
    }
    @Test fun terminalAllowanceSurvivesExhaustedDetailsAndReporterDeduplicatesParts() {
        val lines=mutableListOf<String>(); val reporter=CarPlayAudioDiagnosticReporter(1,{0},lines::add)
        repeat(64) { reporter.record("media stage=audio-owners scope=own owners=0 tracks=0") }
        repeat(193) { reporter.record("media stage=microphone-ledger receiver=1 event=${it+1} owner=0 history=normal kind=receiver action=inputs result=ok source=NONE rate=0 channels=0 bytes=0 elapsedUs=0") }
        repeat(2) { for(owner in 1L..10L) summary(owner).lines().forEach(reporter::record) }
        assertEquals(30,lines.count { "stage=microphone-terminal" in it })
        repeat(2) { reporter.record("media stage=microphone-owner receiver=1 owner=11 state=release-pending") }
        assertEquals(1,lines.count { "stage=microphone-owner " in it })
    }
    @Test fun identicalPreviousProcessCensusIsNotMistakenForCurrentOwnerRetry() {
        val app=RuntimeEnvironment.getApplication()
        val directory=File(app.getExternalFilesDir(null),"openautolink/logs").apply { mkdirs() }
        directory.listFiles().orEmpty().forEach { it.delete() }
        File(directory,"carplay_capture_run_0000000000000000001.log").writeText(summary(1).lines().joinToString("\n",postfix="\n"))
        val store=CaptureTerminalLogStore(app)
        assertTrue(store.save(summary(1)))
        assertEquals(2,directory.listFiles()!!.size)
        assertTrue(store.save(summary(1)))
        assertEquals(2,directory.listFiles()!!.size)
    }
    @Test fun deletionFailureCannotGrowRetainedFilesAndRetryRemainsIdempotent() {
        val app=RuntimeEnvironment.getApplication()
        val directory=File(app.getExternalFilesDir(null),"openautolink/logs").apply { mkdirs() }
        directory.listFiles().orEmpty().forEach { it.delete() }
        val store=CaptureTerminalLogStore(app)
        for(owner in 1L..12L) assertTrue(store.save(summary(owner)))
        val blocked=CaptureTerminalLogStore(app) { false }
        for(owner in 13L..20L) assertFalse(blocked.save(summary(owner)))
        assertEquals(12,directory.listFiles()!!.count { it.name.startsWith("carplay_capture_run_") })
        assertTrue(store.save(summary(13))); assertTrue(store.save(summary(13)))
        assertEquals(12,directory.listFiles()!!.count { it.name.startsWith("carplay_capture_run_") })
    }
    @Test fun finiteRetentionSelectionCapsEachKindAndDoesNotCountLatestAliasTwice() {
        val app=RuntimeEnvironment.getApplication()
        val directory=File(app.getExternalFilesDir(null),"openautolink/logs").apply { mkdirs() }
        directory.listFiles().orEmpty().forEach { it.delete() }
        repeat(15) { index ->
            val stamp=(index+1).toString().padStart(19,'0')
            File(directory,"carplay_capture_run_$stamp.log").apply { writeText("capture$index"); setLastModified(1) }
            File(directory,"microphone_diagnostic_run_$stamp.log").apply { writeText("local$index"); setLastModified(1) }
        }
        File(directory,"microphone_diagnostic.log").writeText("local14")
        File(directory,"carplay_capture_run_0000000000000000016.log").writeBytes(ByteArray(4097))
        val plan=LogUploadArchive.select(directory.listFiles()!!.toList(),System.currentTimeMillis())
        assertEquals(12,plan.selected.count { it.name.startsWith("carplay_capture_run_") })
        assertEquals(12,plan.selected.count { it.name.startsWith("microphone_diagnostic_run_") })
        assertFalse(plan.selected.any { it.name=="microphone_diagnostic.log" })
        assertFalse(plan.selected.any { it.bytes > 4096 })
    }
    @Test fun retainedLatestTwelveCoexistWithLocalDiagnosticsAndUploadExactBytes() {
        val app=RuntimeEnvironment.getApplication()
        val directory=File(app.getExternalFilesDir(null),"openautolink/logs").apply { mkdirs() }
        directory.listFiles().orEmpty().forEach { it.delete() }
        val local=File(directory,"microphone_diagnostic_run_0000000000000000001.log").apply { writeText("local\n") }
        val store=CaptureTerminalLogStore(app)
        for(owner in 1L..14L) assertTrue(store.save(summary(owner)))
        assertTrue(store.save(summary(14)))
        val files=directory.listFiles()!!.filter { it.name.startsWith("carplay_capture_run_") }
        assertEquals(12,files.size)
        assertEquals(12,files.map { it.readText().substringBefore('\n') }.distinct().size)
        assertTrue(local.exists())
        assertTrue(files.all { it.length() <= CaptureTerminalLogStore.MAX_BYTES })
        files.forEach { it.setLastModified(1) }
        val expected=files.associate { it.name to it.readBytes() }
        val body=ByteArrayOutputStream()
        val http=object:HttpURLConnection(URL("https://example.invalid/upload")) {
            override fun connect() {}; override fun disconnect() {}; override fun usingProxy()=false
            override fun getOutputStream()=body; override fun getResponseCode()=200
        }
        assertTrue(LogUploader(app,{http},{true}).upload("https://example.invalid/upload","fixture","fixture") is UploadResult.Success)
        val members=mutableMapOf<String,ByteArray>()
        ZipInputStream(body.toByteArray().inputStream()).use { zip -> while(true) {
            val entry=zip.nextEntry ?: break; members[entry.name]=zip.readBytes()
        } }
        expected.forEach { (name,bytes) -> assertArrayEquals(name,bytes,members[name]) }
        assertArrayEquals(local.readBytes(),members[local.name])
        assertFalse(members.values.any { it.toString(Charsets.UTF_8).contains("address=") })
    }
}
