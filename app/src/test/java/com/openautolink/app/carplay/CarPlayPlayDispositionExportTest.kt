package com.openautolink.app.carplay

import android.content.ContextWrapper
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.openautolink.app.diagnostics.LogUploader
import com.openautolink.app.diagnostics.UploadResult
import com.openautolink.carplaylab.media.ProjectionReceiver
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.media.AudioOutputSupport
import com.shilapi.xcertplay.mfi.MfiAuthenticator
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.MediaCodecInfoBuilder
import org.robolectric.shadows.ShadowMediaCodecList
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/** Real receiver -> sink disposition -> status worker -> reporter -> manual uploader; mocked OS/phone/HTTP. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application=android.app.Application::class,sdk=[32],manifest=Config.NONE)
class CarPlayPlayDispositionExportTest {
    private fun field(target: Any,name: String)=target.javaClass.getDeclaredField(name).apply { isAccessible=true }

    @Test fun finiteDispositionAndDeferredMuteSchemasRejectContradictionsAndPrivateData() {
        val receipts=listOf(
            "media stage=media-play-disposition result=granted transport=permitted output=permitted",
            "media stage=media-play-disposition result=retained transport=permitted output=permitted",
            "media stage=media-play-disposition result=deferred transport=permitted output=withheld",
            "media stage=media-play-disposition result=interrupted transport=permitted output=withheld",
            "media stage=media-play-disposition result=denied transport=withheld output=withheld",
            "media stage=media-play-disposition result=closed transport=withheld output=withheld",
            "media stage=media-play-disposition result=stale-owner transport=withheld output=withheld",
            "media stage=audio-mute reason=stream-start revision=0 volumePercent=0 mute=deferred",
            "media stage=audio-volume stream=1 type=100 audioType=media codec=LPCM scope=own requestedPercent=0 effectivePercent=0 mute=deferred revision=0")
        receipts.forEach { receipt ->
            assertEquals(receipt,"CARPLAY_AUDIO session=7 $receipt",CarPlayAudioDiagnostics.sanitize(7,receipt))
            listOf(" package=private"," uid=1","\n","\u0000").forEach { assertNull(CarPlayAudioDiagnostics.sanitize(7,receipt+it)) }
        }
        listOf("private","deferred transport=permitted output=permitted","denied transport=permitted output=withheld").forEach {
            assertNull(CarPlayAudioDiagnostics.sanitize(7,"media stage=media-play-disposition result=$it"))
        }
    }

    @Test fun actualReceiverTransportOnlyReceiptSurvivesManualUploaderByteForByte() {
        val app=RuntimeEnvironment.getApplication()
        val caps=MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,1280,720))
            .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(MediaCodecInfo.CodecProfileLevel().apply { profile=MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;level=MediaCodecInfo.CodecProfileLevel.AVCLevel52 })).build()
        ShadowMediaCodecList.addCodec(MediaCodecInfoBuilder.newBuilder().setName("media.avc").setIsEncoder(false).setCapabilities(caps).build())
        val raw=CopyOnWriteArrayList<String>();val exported=CopyOnWriteArrayList<String>();val clock=AtomicLong()
        val reporter=CarPlayAudioDiagnosticReporter(7,{clock.addAndGet(1_000_000_000L)},exported::add)
        val surface=mockk<Surface>();every { surface.isValid } returns true
        val receiver=ProjectionReceiver(app,"02:00:00:00:00:01",surface,mockk<MfiAuthenticator>(relaxed=true),wired=true,onStatus={raw+=it;reporter.record(it)})
        val session=mockk<AirPlaySession>(relaxed=true)
        every { session.controlAuthenticated } returns true
        every { session.encryptedEventReady } returns true
        every { session.sendMedia(1,any()) } returns true
        (field(receiver,"sessions").get(receiver) as MutableSet<AirPlaySession>).add(session)
        field(receiver,"active").set(receiver,session);field(receiver,"runtimeOwner").set(receiver,session);field(receiver,"runtimeReady").set(receiver,true)
        val sink=field(receiver,"sink").get(receiver) as AndroidMediaSink
        val input=field(receiver,"input").get(receiver) as ExecutorService
        val statuses=field(receiver,"statuses").get(receiver) as ExecutorService
        val manager=app.getSystemService(AudioManager::class.java)
        val folder=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"play-export").toFile()
        try {
            shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            sink.onAudioStarted(AudioStreamId(100,"speechrecognition"),requireNotNull(AudioOutputSupport.negotiate(0x4000L,100,"speechrecognition")),0)
            val assistant=shadowOf(manager).lastAudioFocusRequest
            assertTrue(receiver.mediaCommand(session,1));input.submit {}.get(3,TimeUnit.SECONDS)
            verify(exactly=1) { session.sendMedia(1,any()) }
            assertSame(assistant,shadowOf(manager).lastAudioFocusRequest)
            sink.onAudioStarted(AudioStreamId(100,"media"),requireNotNull(AudioOutputSupport.negotiate(0x4000L,100,"media")),0)
            statuses.submit {}.get(3,TimeUnit.SECONDS)
            val disposition="media stage=media-play-disposition result=deferred transport=permitted output=withheld"
            assertTrue(raw.contains(disposition))
            assertTrue("actual receiver disposition must survive reporter",exported.contains("CARPLAY_AUDIO session=7 $disposition"))
            assertTrue(exported.any { "audio-mute reason=stream-start" in it && "mute=deferred" in it })
            receiver.close();assertTrue(receiver.awaitClosed(3000))
            val contents=exported.joinToString("\n",postfix="\n")
            val log=File(folder,"openautolink/logs/oal_play.log").apply { parentFile.mkdirs();writeText(contents) }
            val context=object: ContextWrapper(app) {
                override fun getExternalFilesDirs(type: String?)=arrayOf(folder)
                override fun getCacheDir()=folder
            }
            val bytes=ByteArrayOutputStream()
            val connection=object: HttpURLConnection(URL("https://logs.example.test/upload")) {
                override fun connect()=Unit
                override fun disconnect()=Unit
                override fun usingProxy()=false
                override fun getOutputStream()=bytes
                override fun getResponseCode()=200
            }
            assertTrue(LogUploader(context,{connection},{true}).upload("https://logs.example.test/upload","synthetic-token","test-car") is UploadResult.Success)
            val members=mutableMapOf<String,String>()
            ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
                while (true) { val entry=zip.nextEntry ?: break;members[entry.name]=zip.readBytes().toString(Charsets.UTF_8) }
            }
            assertEquals(contents,members[log.name])
            println("PLAY_EXPORT_EVIDENCE receiver=actual sink=actual reporter=actual zipEquality=true phone=mock os=shadow http=mock")
        } finally { receiver.close();assertTrue(receiver.awaitClosed(3000));folder.deleteRecursively() }
    }
}
