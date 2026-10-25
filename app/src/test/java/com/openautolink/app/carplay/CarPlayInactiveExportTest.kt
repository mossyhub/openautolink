package com.openautolink.app.carplay

import android.app.Application
import com.openautolink.app.media.OalMediaSessionManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32,34],manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayInactiveExportTest {
    @Test fun withholdingBudgetCannotConsumeLifecycleRestorationAndPersistentFilesAreBounded() {
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.INACTIVE)
        val lines=mutableListOf<String>()
        lease.receipt=lines::add
        repeat(100) { lease.record("withhold","false","withheld") }
        lease.record("restore","true","ok")
        assertEquals(34,lines.size)
        assertEquals(32,lines.count { "event=withhold " in it })
        assertEquals(1,lines.count { "event=limit " in it })
        assertTrue(lines.last().contains("event=restore"))
        assertTrue(lines.all { CarPlayAudioDiagnostics.sanitize(1,it)!=null })
        assertTrue(HostMediaDiagnosticSink.flush())
        val app=RuntimeEnvironment.getApplication()
        val directory=java.io.File(app.cacheDir,"host-media-bound").apply { mkdirs() }
        directory.listFiles().orEmpty().forEach { it.delete() }
        val marker=CarPlayAudioDiagnostics.sanitize(1,lines.first())!!
        repeat(1000) { HostMediaDiagnosticSink.persist(directory,marker) }
        val files=directory.listFiles().orEmpty()
        assertEquals(2,files.size)
        assertTrue(files.all { it.length()<=32*1024 })
        assertTrue(files.sumOf { it.length() }<=64*1024)
    }

    @Test fun defaultProcessSinkRetainsPreauthAndRestoreWithoutAnAuthenticatedReporterOrFileLogging() {
        val app=RuntimeEnvironment.getApplication()
        val directory=java.io.File(app.getExternalFilesDir(null),"openautolink/logs").apply { mkdirs() }
        directory.listFiles().orEmpty().forEach { it.delete() }
        val manager=OalMediaSessionManager.getInstance(app).also { it.initialize() }
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.INACTIVE)
        try {
            manager.beginCarPlayHostMedia(lease);manager.finishCarPlayHostMedia(lease)
            val output=java.io.ByteArrayOutputStream()
            val http=object:java.net.HttpURLConnection(java.net.URL("https://example.invalid/upload")) {
                override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
                override fun getOutputStream()=output;override fun getResponseCode()=200
            }
            val result=com.openautolink.app.diagnostics.LogUploader(app,{http},{true}).upload("https://example.invalid/upload","fixture","fixture")
            assertTrue("default process lifecycle path must reach manual upload without file logging: $result",result is com.openautolink.app.diagnostics.UploadResult.Success)
            val members=mutableMapOf<String,ByteArray>()
            java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip -> while(true) {
                val entry=zip.nextEntry ?: break;members[entry.name]=zip.readBytes()
            } }
            val file=java.io.File(directory,"oal_host_media.log")
            assertTrue(file.isFile)
            assertArrayEquals(file.readBytes(),members[file.name])
            assertTrue(file.readText().contains("event=apply active=false"))
            assertTrue(file.readText().contains("event=restore active=true"))
        } finally { manager.release() }
    }

    @Test fun finiteInactiveSchemaAdmitsOnlyTruthfulCombinationsAndNoPrivateSuffix() {
        val marker="media stage=host-media event=apply active=false mode=inactive result=ok lost=0"
        assertNotNull("inactive lifecycle must survive real sanitizer",CarPlayAudioDiagnostics.sanitize(1,marker))
        assertNull(CarPlayAudioDiagnostics.sanitize(1,"media stage=host-media event=failed active=unknown mode=inactive result=pending lost=0"))
        assertNull(CarPlayAudioDiagnostics.sanitize(1,"media stage=host-media event=pending active=unknown mode=inactive result=failed lost=0"))
        for (bad in listOf(marker+" title=secret",marker+"\n",marker+"\u0000",marker.replace("active=false","active=true"),
            marker.replace("mode=inactive","mode=other"),marker.replace("event=apply","event=restore"))) {
            assertNull(bad,CarPlayAudioDiagnostics.sanitize(1,bad))
        }
    }
    @Test fun actualManagerProducesPreauthApplyAndPostHostRestoreFailureIntoManualZip() {
        val app=RuntimeEnvironment.getApplication()
        val manager=OalMediaSessionManager.getInstance(app).also { it.initialize() }
        val lease=CarPlayHostMediaLease(CarPlayHostMediaMode.INACTIVE)
        val field=lease.javaClass.declaredFields.firstOrNull { it.name=="receipt" }
        assertNotNull("process lifecycle receipt must not require authenticated host",field)
        val lines=mutableListOf<String>()
        val reporter=CarPlayAudioDiagnosticReporter(71,System::nanoTime,lines::add)
        field!!.isAccessible=true;field.set(lease,{ marker:String -> reporter.record(marker) })
        val sf=manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible=true }
        val session=io.mockk.spyk(sf.get(manager) as android.support.v4.media.session.MediaSessionCompat);sf.set(manager,session)
        try {
            manager.beginCarPlayHostMedia(lease)
            io.mockk.every { session.setActive(true) } throws IllegalStateException("private exception must not escape")
            assertTrue(runCatching { manager.finishCarPlayHostMedia(lease) }.isFailure)
            io.mockk.every { session.setActive(true) } answers { callOriginal() }
            manager.finishCarPlayHostMedia(lease)
            assertEquals(3,lines.size)
            assertTrue(lines[0].contains("event=apply active=false mode=inactive result=ok"))
            assertTrue(lines[1].contains("event=failed active=unknown mode=inactive result=failed"))
            assertTrue(lines[2].contains("event=restore active=true mode=inactive result=ok"))
            val directory=java.io.File(app.getExternalFilesDir(null),"openautolink/logs").apply { mkdirs() }
            directory.listFiles().orEmpty().forEach { it.delete() }
            val file=java.io.File(directory,"oal_host_media.log").apply { writeText(lines.joinToString("\n",postfix="\n")) }
            val output=java.io.ByteArrayOutputStream()
            val http=object:java.net.HttpURLConnection(java.net.URL("https://example.invalid/upload")) {
                override fun connect() {};override fun disconnect() {};override fun usingProxy()=false
                override fun getOutputStream()=output;override fun getResponseCode()=200
            }
            assertTrue(com.openautolink.app.diagnostics.LogUploader(app,{http},{true}).upload("https://example.invalid/upload","fixture","fixture") is com.openautolink.app.diagnostics.UploadResult.Success)
            val members=mutableMapOf<String,ByteArray>()
            java.util.zip.ZipInputStream(output.toByteArray().inputStream()).use { zip -> while(true) {
                val entry=zip.nextEntry ?: break;members[entry.name]=zip.readBytes()
            } }
            assertArrayEquals(file.readBytes(),members[file.name])
            assertFalse(members.values.any { it.toString(Charsets.UTF_8).contains("private exception") })
        } finally { io.mockk.every { session.setActive(true) } answers { callOriginal() };manager.release();io.mockk.unmockkAll() }
    }
}
