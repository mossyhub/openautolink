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
@Config(application=Application::class,sdk=[32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayPlayingPublicationExportTest {
    @Test fun finiteFullStringPublicationSchemaRejectsUnknownStateIdentityAndOemClaims() {
        for (previous in listOf("none","paused","playing")) for (next in listOf("none","paused","playing")) for (mode in listOf("normal","suppress")) {
            val marker="media stage=playing-publication previous=$previous new=$next active=true mode=$mode owner=current"
            assertNotNull(marker,CarPlayAudioDiagnostics.sanitize(1,marker))
            listOf(" title=secret"," selected=true"," owner=phone42","\n","\u0000").forEach {
                assertNull(CarPlayAudioDiagnostics.sanitize(1,marker+it))
            }
            listOf(marker.replace("previous=$previous","previous=unknown"),
                marker.replace("new=$next","new=buffering"),marker.replace("active=true","active=false"),
                marker.replace("mode=$mode","mode=inactive"),marker.replace("owner=current","owner=oem-selected")).forEach {
                assertNull(CarPlayAudioDiagnostics.sanitize(1,it))
            }
        }
    }
    @Test fun actualReporterAndManualUploaderPreserveExactFinitePublicationBytes() {
        val lines=mutableListOf<String>()
        val reporter=CarPlayAudioDiagnosticReporter(7,System::nanoTime,lines::add)
        val marker="media stage=playing-publication previous=none new=none active=true mode=suppress owner=current"
        val app=RuntimeEnvironment.getApplication()
        val prefs=io.mockk.mockk<com.openautolink.app.data.AppPreferences>()
        io.mockk.mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        io.mockk.every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        io.mockk.every { prefs.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        io.mockk.every { prefs.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        val manager=com.openautolink.app.media.OalMediaSessionManager.getInstance(app)
        CarPlayPlayingPublicationExperiment.arm()
        val host=OalCarPlayMetadataHost(app,reporter::record) { true }
        try {
            assertTrue(host.start())
            host.publish(com.openautolink.carplaylab.metadata.CarPlayMediaMetadata(playing=true,title="secret"),null,null)
        } finally { host.close();manager.release();CarPlayPlayingPublicationExperiment.disarm();io.mockk.unmockkAll() }
        reporter.record(marker+" title=secret")
        assertEquals(listOf("CARPLAY_AUDIO session=7 $marker"),lines)
        val directory=File(app.getExternalFilesDir(null),"openautolink/logs").apply { mkdirs() }
        directory.listFiles().orEmpty().forEach { it.delete() }
        val file=File(directory,"oal_audio.log").apply { writeText(lines.joinToString("\n",postfix="\n")) }
        val output=ByteArrayOutputStream()
        val http=object:HttpURLConnection(URL("https://example.invalid/upload")) {
            override fun connect() {}; override fun disconnect() {}; override fun usingProxy()=false
            override fun getOutputStream()=output; override fun getResponseCode()=200
        }
        assertTrue(LogUploader(app,{http},{true}).upload("https://example.invalid/upload","fixture","fixture") is UploadResult.Success)
        val members=mutableMapOf<String,ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip -> while(true) {
            val entry=zip.nextEntry ?: break; members[entry.name]=zip.readBytes()
        } }
        assertArrayEquals(file.readBytes(),members[file.name])
        assertFalse(members.values.any { it.toString(Charsets.UTF_8).contains("secret") })
    }
}
