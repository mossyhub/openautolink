package com.openautolink.app.carplay

import android.app.Application
import com.openautolink.app.diagnostics.LogUploader
import com.openautolink.app.diagnostics.UploadResult
import com.shilapi.xcertplay.media.*
import java.io.*
import java.net.*
import java.util.concurrent.Executor
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayPcmSchemaExportTest {
    @Test fun numericInputAndOutputSchemasRetainChannelUnitsAndExactManualZipBytes() {
        val output="media stage=audio-pcm stream=1 type=101 audioType=default codec=LPCM purpose=nav phase=accepted event=terminal window=0 rate=8000 channels=2 observed=2 scanned=2 tail=0 limit=2048 coverage=full ch0Samples=2 ch0Nonzero=1 ch0Peak=32768 ch0Rms=23170 ch0Clip=1 ch1Samples=2 ch1Nonzero=1 ch1Peak=32767 ch1Rms=23170 ch1Clip=1 queueDrops=0"
        val markers=mutableListOf(output)
        val ledger=CaptureAcquisitionLedger(false)
        val owner=ledger.owner(CaptureAcquisitionLedger.Kind.LIVE,MicrophoneSource.VOICE_RECOGNITION,48000,2)
        val stats=Pcm16Window(2);stats.observe(byteArrayOf(0,128.toByte(),255.toByte(),127))
        owner.pcm(CaptureAcquisitionLedger.Boundary.RAW,CaptureAcquisitionLedger.Phase.RELEASED,48000,stats.snapshot())
        ledger.bind(Executor { it.run() },markers::add)
        val receiver=Regex("receiver=([0-9]+)").find(markers.last())!!.groupValues[1]
        markers.add("media stage=microphone-owned receiver=$receiver event=3 owner=1 elapsedUs=1 media stage=microphone-policy scope=own source=VOICE_RECOGNITION rate=48000 permission=granted mute=false route=builtin silenced=false result=ok")
        // Accepted capture is 16k mono; retain the existing advanced stereo coverage too.
        val accepted=CaptureAcquisitionLedger(false)
        val mono=accepted.owner(CaptureAcquisitionLedger.Kind.LIVE,MicrophoneSource.VOICE_RECOGNITION,16000,1)
        val monoStats=Pcm16Window(1);monoStats.observe(byteArrayOf(0,128.toByte()))
        mono.pcm(CaptureAcquisitionLedger.Boundary.RAW,CaptureAcquisitionLedger.Phase.RELEASED,16000,monoStats.snapshot())
        accepted.bind(Executor { it.run() },markers::add)
        val lines=mutableListOf<String>();val reporter=CarPlayAudioDiagnosticReporter(2,{0},lines::add)
        markers.forEach { marker ->
            assertNotNull("Finite numeric marker must pass: $marker",CarPlayAudioDiagnostics.sanitize(2,marker))
            reporter.record(marker)
            listOf("\n","\u0000"," address=private"," package=com.private"," rawException=private").forEach {
                assertNull(CarPlayAudioDiagnostics.sanitize(2,marker+it))
            }
        }
        listOf(output.replace("purpose=nav","purpose=private"),output.replace("phase=accepted","phase=encoded"),
            output.replace("rate=8000","rate=NaN"),output.replace("ch0Rms=23170","ch0Rms=Infinity"),
            output.replace("ch0Peak=32768","ch0Peak=32769"),output.replace("scanned=2","scanned=2049"),
            output.replace("ch0Nonzero=1","ch0Nonzero=-1"),output.replace("observed=2","observed=12345678901234567890")
        ).forEach { assertNull(CarPlayAudioDiagnostics.sanitize(2,it)) }
        assertEquals(markers.map { "CARPLAY_AUDIO session=2 $it" },lines)
        val app=RuntimeEnvironment.getApplication()
        val file=File(app.getExternalFilesDir(null),"openautolink/logs/oal_pcm_schema.log")
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
