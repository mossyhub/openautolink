package com.openautolink.app.carplay

import android.Manifest
import android.graphics.SurfaceTexture
import android.media.*
import android.view.Surface
import com.openautolink.carplaylab.media.*
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.util.ReflectionHelpers
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioRecord
import java.net.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(application = android.app.Application::class, sdk=[32], manifest=Config.NONE)
class CarPlayPcmMicrophoneExportTest {
    @Test fun applicationFixtureDoesNotBootProcessServices() {
        // Config.NONE is ignored by Robolectric's AGP Build System API. Check
        // the actual application, not just the annotation: OalApplication starts
        // process-owned IO work that can outlive this test's Android sandbox.
        assertEquals(
            "This fixture must not boot OalApplication process services",
            android.app.Application::class.java,
            org.robolectric.RuntimeEnvironment.getApplication().javaClass,
        )
    }

    @Test fun actualPcmSinkCaptureReceiptsSurviveBoundedReporterAndUploadArchive() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        // OS-boundary fixture: the builder has no role setter; AudioPort source role is 1.
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        val cap=AndroidMicrophoneSupport.probe(app)
        val lines=java.util.concurrent.CopyOnWriteArrayList<String>()
        val sent=CountDownLatch(1)
        val policy=CountDownLatch(1)
        val configuration=CountDownLatch(1)
        val effects=CountDownLatch(2)
        val reporter=CarPlayAudioDiagnosticReporter(1,System::nanoTime) {
            lines.add(it); if (it.contains("microphone-datagram-sent")) sent.countDown()
            if (it.contains("microphone-policy")) policy.countDown()
            if (it.contains("microphone-config ")) configuration.countDown()
            if (it.contains("microphone-effects ")) effects.countDown()
        }
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(b: ByteArray,o: Int,n: Int,blocking: Boolean): Int {
                Thread.sleep(20); b.fill(0,o,o+n); return n
            }
        })
        val texture=SurfaceTexture(0); val surface=Surface(texture)
        val sink=AndroidMediaSink(app,surface,display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),captureAdmission={true},onStatus=reporter::record)
        val session=AirPlaySession(Socket(),AirPlayConfig("test","id","mac","950.7.1",AirPlayDisplayConfig(800,480)),
            AirPlayIdentity.generate(),PairingStore(),null,object : AirPlaySessionListener {},object : AirPlayMediaHandler {})
        val folder=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"pcm-export").toFile()
        try {
            DatagramSocket(0,InetAddress.getLoopbackAddress()).use { receive ->
                val mic=MicrophoneSupport.negotiate(cap,100,"telephony",0x40L,receive.localPort,480,receive.localAddress,ByteArray(32))!!
                assertTrue(sink.supportsMicrophone(AudioOutputSupport.negotiate(0x40L,100,"telephony")!!))
                sink.onMicrophoneStarted(MicrophoneStreamId(session,100,"telephony",1),mic)
                assertTrue("actual send receipt omitted by sanitizer",sent.await(3,TimeUnit.SECONDS))
                assertTrue("actual exact-recorder policy omitted by sanitizer",policy.await(3,TimeUnit.SECONDS))
                assertTrue("actual exact-recorder configuration omitted by sanitizer",configuration.await(3,TimeUnit.SECONDS))
                assertTrue("actual exact-recorder effects omitted by sanitizer",effects.await(3,TimeUnit.SECONDS))
                // Normal telephony owns accepted VR16 mono capture, independently of
                // the negotiated LPCM24 mono wire format. No manual experiment is armed.
                assertTrue(lines.any { it.contains("microphone-config scope=own requested=VOICE_RECOGNITION rate=16000 recorder=VOICE_RECOGNITION") })
                assertEquals(2,lines.count { it.contains("microphone-effects ") })
                listOf("client","capture").forEach { chain ->
                    assertTrue(lines.any { it.contains("microphone-effects scope=own source=VOICE_RECOGNITION rate=16000 chain=$chain ") })
                }
                assertTrue(lines.any { it.contains("microphone-policy scope=own source=VOICE_RECOGNITION rate=16000 permission=granted") })
                assertTrue(lines.any { it.contains("microphone-acquisition path=aa-style source=VOICE_RECOGNITION captureRate=16000 captureChannels=1 wireRate=24000") })
                assertTrue(lines.any { it.endsWith("media stage=microphone-format type=100 audioType=telephony codec=LPCM rate=24000 channels=1 spf=480 source=VOICE_RECOGNITION") })
                assertFalse(lines.any { it.contains("media stage=microphone-experiment ") })
                assertTrue("actual capture aggregate omitted by sanitizer",lines.any {
                    it.endsWith("media stage=microphone-capture type=100 audioType=telephony frames=1 nonzeroFrames=0 state=3 ended=false")
                })
                // Deterministic finite control/focus receipts exercise the same production
                // reporter/archive boundary, not physical focus events or phone receipt.
                reporter.record("media stage=audio-focus-change change=-3 volumePercent=20")
                reporter.record("media stage=media-control index=2 outcome=sent")
                assertTrue(lines.any { it.endsWith("media stage=audio-focus-change change=-3 volumePercent=20") })
                assertTrue(lines.any { it.endsWith("media stage=media-control index=2 outcome=sent") })
                val contents=lines.joinToString("\n",postfix="\n")
                val logs=java.io.File(folder,"openautolink/logs").apply { mkdirs() }
                val log=java.io.File(logs,"oal_pcm.log").apply { writeText(contents) }
                val context=object : android.content.ContextWrapper(app) {
                    override fun getExternalFilesDirs(type: String?)=arrayOf(folder)
                    override fun getCacheDir()=folder
                }
                val bytes=java.io.ByteArrayOutputStream()
                val connection=object : java.net.HttpURLConnection(java.net.URL("https://logs.example.test/upload")) {
                    override fun connect()=Unit
                    override fun disconnect()=Unit
                    override fun usingProxy()=false
                    override fun getOutputStream()=bytes
                    override fun getResponseCode()=200
                }
                val result=com.openautolink.app.diagnostics.LogUploader(context,{connection},{true})
                    .upload("https://logs.example.test/upload","synthetic-test-token","test-car")
                assertTrue(result.toString(),result is com.openautolink.app.diagnostics.UploadResult.Success)
                val members=mutableMapOf<String,String>()
                java.util.zip.ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
                    while(true) { val entry=zip.nextEntry?:break;members[entry.name]=zip.readBytes().toString(Charsets.UTF_8) }
                }
                assertEquals(contents,members[log.name])
                assertFalse(members.values.any { it.contains("synthetic-test-token") })
            }
        } finally {
            sink.close(); assertTrue(sink.awaitRetired(3000)); session.close()
            ShadowAudioRecord.clearSource(); folder.deleteRecursively(); surface.release(); texture.release()
        }
    }
    @Test fun stalledCaptureKeepsExactSinkRetirementPendingAndCannotSendAfterCancellation() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val device=AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_MIC).build()
        ReflectionHelpers.setField(ReflectionHelpers.getField<Any>(device,"mPort"),"mRole",1)
        shadowOf(app.getSystemService(AudioManager::class.java)).setInputDevices(listOf(device))
        val cap=AndroidMicrophoneSupport.probe(app)
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        ShadowAudioRecord.setSource(object : ShadowAudioRecord.AudioRecordSource {
            override fun readInByteArray(b: ByteArray,o: Int,n: Int,blocking: Boolean): Int {
                entered.countDown()
                while (release.count > 0) { try { release.await() } catch (_: InterruptedException) {} }
                b.fill(0,o,o+n); return n
            }
        })
        val texture=SurfaceTexture(0); val surface=Surface(texture)
        val sink=AndroidMediaSink(app,surface,display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),captureAdmission={true})
        val session=AirPlaySession(Socket(),AirPlayConfig("test","id","mac","950.7.1",AirPlayDisplayConfig(800,480)),
            AirPlayIdentity.generate(),PairingStore(),null,object : AirPlaySessionListener {},object : AirPlayMediaHandler {})
        try {
            DatagramSocket(0,InetAddress.getLoopbackAddress()).use { receive ->
                val mic=MicrophoneSupport.negotiate(cap,100,"default",0x40L,receive.localPort,480,receive.localAddress,ByteArray(32))!!
                sink.onMicrophoneStarted(MicrophoneStreamId(session,100,"default",2),mic)
                assertTrue(entered.await(3,TimeUnit.SECONDS))
                // A stale stop cannot retire a newer exact stream generation.
                sink.onMicrophoneStopped(MicrophoneStreamId(session,100,"default",1))
                sink.close()
                assertFalse("500ms close join is not worker retirement",sink.awaitRetired(100))
                sink.close() // explicit retirement retry retains the same stalled owner
                assertFalse(sink.awaitRetired(100))
                release.countDown()
                assertTrue(sink.awaitRetired(3000))
                receive.soTimeout=100
                assertTrue(runCatching { receive.receive(DatagramPacket(ByteArray(4096),4096)) }.exceptionOrNull() is SocketTimeoutException)
            }
        } finally {
            release.countDown(); sink.close(); assertTrue(sink.awaitRetired(3000)); session.close()
            ShadowAudioRecord.clearSource(); surface.release(); texture.release()
        }
    }
    @Test fun microphoneSchemasRejectPrivateSuffixesAndUnsupportedSourceRateClaims() {
        val lines=listOf(
            "media stage=microphone-format type=100 audioType=telephony codec=LPCM rate=24000 channels=1 spf=480 source=VOICE_COMMUNICATION",
            "media stage=microphone-started type=100; uplink audibility unverified",
            "media stage=microphone-datagram-sent packets=1; phone receipt unverified",
            "media stage=microphone-capture type=100 audioType=telephony frames=2 nonzeroFrames=1 state=3 ended=true",
            "media stage=audio-focus-change change=-3 volumePercent=20",
            "media stage=media-control index=2 outcome=sent",
            "media stage=microphone-retirement-pending",
            "media stage=microphone-worker-start-failed",
            "media stage=microphone-failed type=100 errorType=SecurityException")
        lines.forEach {
            assertNotNull(it,CarPlayAudioDiagnostics.sanitize(1,it))
            listOf(" key=private"," host=private","\n","\u0000").forEach { suffix -> assertNull(CarPlayAudioDiagnostics.sanitize(1,it+suffix)) }
        }
        assertNull(CarPlayAudioDiagnostics.sanitize(1,lines[0].replace("rate=24000","rate=16000")))
        assertNull(CarPlayAudioDiagnostics.sanitize(1,lines[0].replace("source=VOICE_COMMUNICATION","source=private")))
    }
}
