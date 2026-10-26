package com.openautolink.app.audio

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[32,34], manifest=Config.NONE, application=Application::class)
class AaMicSignalTest {
    @Test fun wholeWindowIncludesSignalAfterFiveSilentReads() {
        val lines=java.util.concurrent.CopyOnWriteArrayList<String>()
        val done=CountDownLatch(1)
        var reads=0
        ShadowAudioRecord.setSource(object: ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(b:ByteArray,o:Int,n:Int,blocking:Boolean):Int {
                reads++
                if(reads>6) return -1
                b.fill(0)
                if(reads==6) { b[0]=0; b[1]=(-128).toByte(); b[2]=(-1).toByte(); b[3]=127; b[4]=1 }
                return if(reads==6) 5 else 1024
            }
        })
        // Reflection keeps missing instrumentation a compiling assertion RED.
        val ctor=MicCaptureManager::class.java.constructors.firstOrNull { it.parameterTypes.size==2 }
        assertNotNull("actual recorder must expose its numeric terminal delivery seam",ctor)
        val app=org.robolectric.RuntimeEnvironment.getApplication()
        val directory=java.io.File(app.getExternalFilesDir(null),"openautolink/logs").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val previous=com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter
        com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter=null
        val durable=AaMicDiagnostics.sink(app)
        val diagnostic:(String)->Unit={ lines.add(it); durable(it); if(it.contains("state=released")) done.countDown() }
        val manager=ctor!!.newInstance(diagnostic, { _:AudioFrame -> Unit }) as MicCaptureManager
        try {
            manager.start(16000)
            assertTrue(done.await(5,TimeUnit.SECONDS))
            assertEquals(2,lines.size)
            val terminal=lines.first()
            assertTrue(terminal,terminal.contains("blocks=6 samples=2562 nonzero=2 peak=32768"))
            assertTrue(terminal,terminal.contains("clipped=2 tailBytes=1 readErrors=1"))
            assertTrue(lines.last(),lines.last().contains("state=released"))
            val wire=java.io.ByteArrayOutputStream()
            val connection=object:java.net.HttpURLConnection(java.net.URL("https://logs.example.test/upload")) {
                override fun connect()=Unit
                override fun disconnect()=Unit
                override fun usingProxy()=false
                override fun getOutputStream()=wire
                override fun getResponseCode()=200
            }
            val result=com.openautolink.app.diagnostics.LogUploader(app,{connection},{true},{true})
                .upload("https://logs.example.test/upload","fixture-token","fixture")
            assertTrue(result.toString(),result is com.openautolink.app.diagnostics.UploadResult.Success)
            val members=mutableMapOf<String,String>()
            java.util.zip.ZipInputStream(wire.toByteArray().inputStream()).use { zip ->
                while(true) { val e=zip.nextEntry ?: break;members[e.name]=zip.readBytes().toString(Charsets.UTF_8) }
            }
            val expected=lines.joinToString("\n",postfix="\n")
            assertEquals(expected,members["oal_host_media.log"])
            assertEquals(expected,java.io.File(directory,"oal_host_media.log").readText())
            assertNull(AaMicDiagnostics.sanitize(terminal+" private suffix"))
            assertNull(AaMicDiagnostics.sanitize(terminal.replace("coverage=full","coverage=full\n")))
        } finally { manager.stop(); ShadowAudioRecord.clearSource(); com.openautolink.app.diagnostics.DiagnosticLog.fileLogWriter=previous }
    }
    @Test @Config(shadows=[com.openautolink.app.carplay.CarPlayCaptureProductionIntegrationTest.Recorder::class])
    fun aaReleasedReceiptWaitsForActualRecorderRelease() {
        val fixture=com.openautolink.app.carplay.CarPlayCaptureProductionIntegrationTest
        fixture.reading=CountDownLatch(1);fixture.releasing=CountDownLatch(1);fixture.release=CountDownLatch(1)
        val lines=java.util.concurrent.CopyOnWriteArrayList<String>()
        val done=CountDownLatch(1)
        val manager=MicCaptureManager({ line -> lines.add(line);if("state=released" in line)done.countDown() }) {}
        try {
            manager.start(16000)
            assertTrue(fixture.reading.await(3,TimeUnit.SECONDS))
            manager.stop()
            assertTrue(fixture.releasing.await(3,TimeUnit.SECONDS))
            assertTrue(lines.any { "state=terminal" in it })
            assertFalse(lines.any { "state=released" in it })
            assertNull(com.shilapi.xcertplay.media.MicrophoneCaptureFence.tryDiagnostic())
            fixture.release.countDown()
            assertTrue(done.await(3,TimeUnit.SECONDS))
        } finally { fixture.release.countDown();manager.stop() }
    }
    @Test fun zeroOddTailBoundsAndSaturatingSignedMinimumVector() {
        val signal=AaMicSignal()
        signal.read(ByteArray(1024),1024)
        assertTrue(signal.line(1,16000).contains("blocks=1 samples=512 nonzero=0 peak=0 sumSquares=0 rms=0"))
        signal.read(byteArrayOf(1),1)
        assertEquals(1L,signal.tailBytes)
        assertThrows(IllegalArgumentException::class.java) { signal.read(ByteArray(1026),1026) }
        val sum=AaMicSignal::class.java.getDeclaredField("sumSquares").apply { isAccessible=true }
        sum.setLong(signal,Long.MAX_VALUE)
        signal.read(byteArrayOf(0,(-128).toByte()),2)
        assertEquals(32768,signal.peak)
        assertEquals(Long.MAX_VALUE,signal.sumSquares)
        assertTrue(signal.saturated)
    }
}
