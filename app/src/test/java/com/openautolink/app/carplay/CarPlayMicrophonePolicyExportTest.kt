package com.openautolink.app.carplay

import com.openautolink.app.diagnostics.LogUploadArchive
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

class CarPlayMicrophonePolicyExportTest {
    @Test fun exactRecorderPolicySurvivesSanitizerAndActualArchiveWithoutIdentities() {
        val receipt = "media stage=microphone-policy scope=own source=VOICE_RECOGNITION rate=24000 permission=granted mute=false route=builtin silenced=true result=ok"
        val lines = mutableListOf<String>()
        CarPlayAudioDiagnosticReporter(1, System::nanoTime, lines::add).record(receipt)
        assertEquals(listOf("CARPLAY_AUDIO session=1 $receipt"), lines)
        val folder = Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), "mic-policy-export").toFile()
        try {
            val log = java.io.File(folder, "oal_mic.log").apply { writeText(lines.single()+"\n") }
            LogUploadArchive.create(LogUploadArchive.select(listOf(log),log.lastModified()),folder).use { archive ->
                ZipFile(archive.file).use { zip ->
                    assertEquals(lines.single()+"\n",zip.getInputStream(zip.getEntry(log.name)).bufferedReader().readText())
                }
            }
        } finally { folder.deleteRecursively() }
        listOf(" id=123", " name=private", " address=private", "\n", "\u0000").forEach {
            assertNull(CarPlayAudioDiagnostics.sanitize(1,receipt+it))
        }
        listOf("scope=own" to "scope=device", "route=builtin" to "route=123", "silenced=true" to "silenced=private", "source=VOICE_RECOGNITION" to "source=private").forEach { (from,to) ->
            assertNull(CarPlayAudioDiagnostics.sanitize(1,receipt.replace(from,to)))
        }
    }
}
