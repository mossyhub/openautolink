package com.openautolink.app.ui.diagnostics

import android.app.Application
import com.openautolink.app.diagnostics.MicrophoneDiagnosticLogStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.util.zip.ZipInputStream
import com.openautolink.app.diagnostics.LogUploadArchive

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class, sdk=[32])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ParkedCaptureNumericTest {
    private fun values() = LongArray(32).also {
        it[0]=1; it[1]=1; it[6]=1; it[7]=1; it[8]=6; it[9]=48000; it[10]=2; it[11]=1
        it[12]=1; it[13]=12; it[14]=3; it[17]=-1; it[18]=-1; it[19]=2; it[21]=2;it[26]=2;it[31]=2000
    }
    @Test fun finiteNumericProducerIsValidatedRetainedAndArchivedPerRun() {
        val app=RuntimeEnvironment.getApplication()
        val store=MicrophoneDiagnosticLogStore(app)
        val save=store.javaClass.methods.singleOrNull { it.name=="saveParkedCapture" }
        assertNotNull("actual native producer needs numeric sanitizer + existing manual ZIP",save)
        repeat(2) { assertEquals(true,save!!.invoke(store,values().also { v->v[0]=it.toLong() })) }
        assertEquals(false,save!!.invoke(store,values().also { it[19]=96001 }))
        assertEquals(false,save.invoke(store,values().also { it[24]=Long.MAX_VALUE }))
        val dir=File(app.getExternalFilesDir(null),"openautolink/logs")
        val runs=dir.listFiles()!!.filter { it.name.startsWith("microphone_diagnostic_run_") }
        assertEquals(2,runs.size)
        LogUploadArchive.create(LogUploadArchive.select(runs,System.currentTimeMillis()),app.cacheDir).use { archive ->
            val members=mutableListOf<String>()
            ZipInputStream(archive.file.inputStream()).use { zip ->
                while(true){ val e=zip.nextEntry ?: break; if(e.name.startsWith("microphone_diagnostic_run_")) members+=zip.readBytes().toString(Charsets.UTF_8) }
            }
            assertEquals(2,members.size)
            assertTrue(members.all { !it.contains("address") && !it.contains("pcm",true) && it.contains("2000") })
        }
    }
}
