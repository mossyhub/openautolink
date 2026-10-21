package com.openautolink.app.carplay

import android.app.Application
import com.openautolink.app.diagnostics.LogUploader
import com.openautolink.app.diagnostics.UploadResult
import com.shilapi.xcertplay.media.CaptureAcquisitionLedger
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
import java.util.concurrent.Executor
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayCaptureLedgerExportTest {
    @Test fun finiteActualLedgerSurvivesBusyReporterAndManualZipWithoutPrivateSuffixes() {
        val lines=mutableListOf<String>()
        val reporter=CarPlayAudioDiagnosticReporter(1,{0},lines::add)
        repeat(64) { reporter.record("media stage=audio-owners scope=own owners=0 tracks=0") }
        val tasks=mutableListOf<Runnable>()
        val ledger=CaptureAcquisitionLedger(false)
        repeat(300) { ledger.root.event(CaptureAcquisitionLedger.Action.INPUTS) }
        val produced=mutableListOf<String>()
        ledger.bind(Executor { tasks.add(it) }) { produced.add(it);reporter.record(it) }
        assertEquals("No synchronous observer at producer ingress",64,lines.size)
        tasks.toList().forEach { it.run() }
        assertEquals(193,produced.size)
        assertEquals("Ledger must survive ordinary per-second status saturation",193,lines.count { "stage=microphone-ledger" in it })
        assertEquals(1,produced.count { "action=limit result=dropped" in it })
        produced.forEach { marker ->
            assertNotNull(CarPlayAudioDiagnostics.sanitize(1,marker))
            listOf(" address=private"," package=com.private","\n","\u0000"," exception=secret").forEach {
                assertNull(CarPlayAudioDiagnostics.sanitize(1,marker+it))
            }
            assertNull(CarPlayAudioDiagnostics.sanitize(1,marker.replace("kind=receiver","kind=private")))
        }
        produced.forEach(reporter::record)
        assertEquals("Duplicate ledger delivery cannot consume another lifetime allowance",193,lines.count { "stage=microphone-ledger" in it })
        val app=RuntimeEnvironment.getApplication()
        val file=File(app.getExternalFilesDir(null),"openautolink/logs/oal_capture_ledger.log")
        file.parentFile!!.mkdirs();file.writeText(lines.joinToString("\n",postfix="\n"))
        val expected=file.readBytes();val body=ByteArrayOutputStream()
        val http=object:HttpURLConnection(URL("https://example.invalid/upload")) {
            override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
            override fun getOutputStream()=body;override fun getResponseCode()=200
        }
        assertTrue(LogUploader(app,{http},{true}).upload("https://example.invalid/upload","fixture","fixture") is UploadResult.Success)
        val members=mutableMapOf<String,ByteArray>()
        ZipInputStream(body.toByteArray().inputStream()).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break;members[entry.name]=zip.readBytes() }
        }
        assertArrayEquals(expected,members[file.name])
    }
}
