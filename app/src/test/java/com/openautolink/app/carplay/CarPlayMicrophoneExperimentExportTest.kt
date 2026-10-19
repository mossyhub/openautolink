package com.openautolink.app.carplay

import android.app.Application
import com.openautolink.app.diagnostics.LogUploader
import com.openautolink.app.diagnostics.UploadResult
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
@Config(application = Application::class, sdk = [32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayMicrophoneExperimentExportTest {
    @Test fun finiteCaptureVsWireProfileSurvivesReporterAndManualUpload() {
        val marker = "media stage=microphone-experiment source=VOICE_RECOGNITION captureRate=48000 captureChannels=2 selection=FIRST wireRate=24000"
        val lines = mutableListOf<String>()
        val reporter = CarPlayAudioDiagnosticReporter(1, System::nanoTime, lines::add)
        reporter.record(marker)
        assertEquals("Experimental capture configuration must not vanish at export", listOf("CARPLAY_AUDIO session=1 $marker"), lines)
        listOf(" host=private", " key=private", "\n", "\u0000").forEach { assertNull(CarPlayAudioDiagnostics.sanitize(1,marker+it)) }
        assertNull(CarPlayAudioDiagnostics.sanitize(1,marker.replace("captureChannels=2","captureChannels=8")))
        assertNull(CarPlayAudioDiagnostics.sanitize(1,marker.replace("captureRate=48000","captureRate=12345")))
        val app = RuntimeEnvironment.getApplication()
        val dir = File(app.getExternalFilesDir(null), "openautolink/logs").apply { mkdirs() }
        val file = File(dir,"oal_experiment_export.log").apply { writeText(lines.joinToString("\n", postfix="\n")) }
        val expected=file.readBytes()
        val bytes=ByteArrayOutputStream()
        val connection=object : HttpURLConnection(URL("https://example.invalid/upload")) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy()=false
            override fun getOutputStream()=bytes
            override fun getResponseCode()=200
        }
        assertTrue(LogUploader(app,{connection},{true}).upload("https://example.invalid/upload","fixture","fixture") is UploadResult.Success)
        val members=mutableMapOf<String,ByteArray>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while(true) { val entry=zip.nextEntry ?: break;members[entry.name]=zip.readBytes() }
        }
        assertArrayEquals(expected,members[file.name])
    }
}
