package com.openautolink.app.carplay

import android.app.Application
import android.media.*
import android.view.Surface
import com.openautolink.carplaylab.media.ProjectionReceiver
import com.openautolink.app.diagnostics.*
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[32],manifest=Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CarPlayOutputPcmExportTest {
    @Test fun actualReceiverQueueSinkReporterAndManualUploaderRetainNumericOutputWithoutAudio() {
        val app=RuntimeEnvironment.getApplication()
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat("video/avc",1280,720)).setIsEncoder(false)
            .setColorFormats(intArrayOf(2130708361)).setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply {
                profile=MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;level=MediaCodecInfo.CodecProfileLevel.AVCLevel52
            })).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("test.numeric.avc.decoder").setIsEncoder(false).setCapabilities(caps).build())
        File(app.noBackupFilesDir,"pairing-probe").deleteRecursively()
        val raw=CopyOnWriteArrayList<String>();val exported=CopyOnWriteArrayList<String>();val threads=CopyOnWriteArrayList<String>()
        val terminal=CountDownLatch(1)
        val reporter=CarPlayAudioDiagnosticReporter(539,System::nanoTime,exported::add)
        val surface=mockk<Surface>();every {surface.isValid} returns true
        val receiver=ProjectionReceiver(app,"02:00:00:00:00:01",surface,mockk<MfiAuthenticator>(relaxed=true),wired=true,onStatus={
            raw.add(it); if(it.startsWith("media stage=audio-pcm ")) threads.add(Thread.currentThread().name)
            reporter.record(it)
            if(it.contains("phase=accepted event=terminal")) terminal.countDown()
        })
        fun field(name: String)=receiver.javaClass.getDeclaredField(name).apply {isAccessible=true}.get(receiver)
        val sink=field("sink") as AndroidMediaSink
        val statuses=field("statuses") as ThreadPoolExecutor
        val folder=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"numeric-output-export").toFile()
        val releaseStatus=CountDownLatch(1)
        try {
            // Exercise actual constructor-wired sink/status queue. Authentication/transport is not
            // claimed by this OS-boundary fixture; no sockets or playback devices are exercised.
            val id=AudioStreamId(101,"default");val format=AudioFormat(AudioCodecKind.LPCM,8000,2,101,"default")
            sink.onAudioStarted(id,format,0)
            sink.onAudioRtp(id,format,ByteArray(12).apply {this[0]=0x80.toByte()}+byteArrayOf(0x80.toByte(),0,0x7f,0xff.toByte(),0,0,0,0),0)
            val telemetry=receiver.javaClass.getDeclaredField("telemetry").apply {isAccessible=true}.get(receiver) as com.openautolink.carplaylab.media.ReceiverTelemetry
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(telemetry.snapshot(System.nanoTime()).audioWrittenBytes!=8L && System.nanoTime()<deadline) Thread.sleep(5)
            assertEquals(8L,telemetry.snapshot(System.nanoTime()).audioWrittenBytes)
            sink.onAudioStopped(id)
            assertTrue(terminal.await(3,TimeUnit.SECONDS));statuses.submit {}.get(3,TimeUnit.SECONDS)
            assertTrue(threads.toString(),threads.isNotEmpty() && threads.all {it=="lab-media-status"})
            val produced=raw.filter {it.startsWith("media stage=audio-pcm ") && it.contains("event=terminal")}
            assertEquals(2,produced.size)
            produced.forEach {line->
                assertNotNull("actual output numeric schema must survive",CarPlayAudioDiagnostics.sanitize(539,line))
                assertTrue(exported.contains("CARPLAY_AUDIO session=539 $line"))
                for(suffix in listOf(" address=private"," exception=private","\n","\u0000")) assertNull(CarPlayAudioDiagnostics.sanitize(539,line+suffix))
                for((from,to) in listOf("purpose=nav" to "purpose=private","phase=decoded" to "phase=encoded","rate=8000" to "rate=NaN","ch0Rms=23170" to "ch0Rms=Infinity")) {
                    if(line.contains(from)) assertNull(CarPlayAudioDiagnostics.sanitize(539,line.replace(from,to)))
                }
            }
            val accepted=produced.single {it.contains("phase=accepted")}
            assertTrue(accepted,accepted.contains("observed=2 scanned=2 tail=0"))
            assertTrue(accepted.contains("ch0Samples=2 ch0Nonzero=1 ch0Peak=32768 ch0Rms=23170 ch0Clip=1"))
            // Saturate the ACTUAL bounded receiver queue while its worker is parked. Producer
            // offers must reject truthfully; no PCM observer/logger may run on the output thread.
            val blocked=CountDownLatch(1)
            statuses.execute {blocked.countDown();releaseStatus.await(3,TimeUnit.SECONDS)}
            assertTrue(blocked.await(3,TimeUnit.SECONDS))
            repeat(64) {statuses.execute {}}
            sink.onAudioStarted(id,format,0)
            sink.onAudioRtp(id,format,ByteArray(20).apply {this[0]=0x80.toByte()},0)
            val pressureDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(telemetry.snapshot(System.nanoTime()).audioWrittenBytes!=16L && System.nanoTime()<pressureDeadline) Thread.sleep(5)
            assertEquals(16L,telemetry.snapshot(System.nanoTime()).audioWrittenBytes)
            releaseStatus.countDown()
            val drainDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3)
            while(statuses.queue.isNotEmpty() && System.nanoTime()<drainDeadline) Thread.sleep(5)
            statuses.submit {}.get(3,TimeUnit.SECONDS)
            sink.onAudioStopped(id); statuses.submit {}.get(3,TimeUnit.SECONDS)
            val pressure=raw.single {it.startsWith("media stage=audio-pcm stream=2 ") && it.contains("phase=accepted event=terminal")}
            assertTrue(pressure,pressure.endsWith("queueDrops=2"))
            assertTrue(raw.any {it.startsWith("media stage=status-queue drops=") && !it.endsWith("drops=0")})
            assertNotNull(CarPlayAudioDiagnostics.sanitize(539,pressure))
            assertTrue(exported.contains("CARPLAY_AUDIO session=539 $pressure"))
            assertTrue("all numeric callbacks still use real status worker",threads.all {it=="lab-media-status"})
            val contents=exported.joinToString("\n",postfix="\n")
            val logs=File(folder,"openautolink/logs").apply {mkdirs()};val log=File(logs,"oal_output_numeric.log").apply {writeText(contents)}
            val context=object:android.content.ContextWrapper(app) {
                override fun getExternalFilesDirs(type: String?)=arrayOf(folder)
                override fun getCacheDir()=folder
            }
            val body=ByteArrayOutputStream()
            val http=object:HttpURLConnection(URL("https://example.invalid/upload")) {
                override fun connect()=Unit;override fun disconnect()=Unit;override fun usingProxy()=false
                override fun getOutputStream()=body;override fun getResponseCode()=200
            }
            assertTrue(LogUploader(context,{http},{true}).upload("https://example.invalid/upload","fixture","fixture") is UploadResult.Success)
            val members=mutableMapOf<String,ByteArray>()
            ZipInputStream(body.toByteArray().inputStream()).use {zip-> while(true) {val entry=zip.nextEntry?:break;members[entry.name]=zip.readBytes()} }
            assertArrayEquals(contents.toByteArray(),members[log.name])
            assertFalse(members.values.any {it.toString(Charsets.UTF_8).contains("address=private")})
        } finally {releaseStatus.countDown();receiver.close();assertTrue(receiver.awaitClosed(10000));folder.deleteRecursively()}
    }
}
