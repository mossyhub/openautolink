package com.openautolink.app.carplay

import android.media.AudioFocusRequest
import android.media.AudioManager
import android.view.Surface
import com.openautolink.carplaylab.media.*
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.media.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.ConscryptMode(org.robolectric.annotation.ConscryptMode.Mode.OFF)
@Config(application = android.app.Application::class, sdk=[32], manifest=Config.NONE)
class CarPlayAudioOwnershipExportTest {
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

    private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible=true }
    private fun change(request: AudioFocusRequest, change: Int) {
        val listener=AudioFocusRequest::class.java.getDeclaredMethod("getOnAudioFocusChangeListener").invoke(request) as AudioManager.OnAudioFocusChangeListener
        listener.onAudioFocusChange(change)
    }
    @Test fun newStreamPublishesSuccessfulAppliedVolumeBeforePeriodicSampling() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        val applied=CountDownLatch(1)
        val surface=mockk<Surface>(); every { surface.isValid } returns true
        val sink=AndroidMediaSink(app,surface,display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),onStatus={
            if (it.contains("audio-volume stream=1") && it.contains("effectivePercent=0") && it.contains("mute=denied")) applied.countDown()
        })
        try {
            val diagnostics=requireNotNull(field(sink,"routeDiagnostics").get(sink))
            val executor=field(diagnostics,"executor").get(diagnostics) as ScheduledThreadPoolExecutor
            // Stop future ticks after the initial snapshot; track creation must provide this evidence.
            executor.submit {}.get(3,TimeUnit.SECONDS)
            executor.shutdownNow()
            // Primary media actually requests focus; alternate-only guidance intentionally does not.
            sink.onAudioStarted(AudioStreamId(100,"media"),AudioOutputSupport.negotiate(0x4000L,100,"media")!!,0)
            assertTrue("new own track must publish actual successful setVolume without waiting five seconds",applied.await(1,TimeUnit.SECONDS))
        } finally { sink.close();assertTrue(sink.awaitRetired(3000)) }
    }
    @Test fun actualFocusMuteAndRetainedOwnVolumeSurviveSanitizerAndUploadArchive() {
        val app=RuntimeEnvironment.getApplication()
        val manager=app.getSystemService(AudioManager::class.java)
        shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        val raw=CopyOnWriteArrayList<String>(); val lines=CopyOnWriteArrayList<String>()
        val reporter=CarPlayAudioDiagnosticReporter(7,System::nanoTime,lines::add)
        val surface=mockk<Surface>(); every { surface.isValid } returns true
        val sink=AndroidMediaSink(app,surface,display=CarPlayDisplaySelection(CarPlayDisplayPreferences(),"avc.exact",false),onStatus={ raw.add(it); reporter.record(it) })
        val media=AudioStreamId(100,"media"); val nav=AudioStreamId(101,"default")
        val folder=java.nio.file.Files.createTempDirectory(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")),"audio-ownership-export").toFile()
        try {
            sink.onAudioStarted(media,AudioOutputSupport.negotiate(0x4000L,100,"media")!!,0)
            assertTrue("real stream-start focus result must survive sanitizer",lines.any { it.contains("audio-focus-request reason=stream-start result=granted") })
            val old=field(sink,"focus").get(sink) as AudioFocusRequest
            change(old,AudioManager.AUDIOFOCUS_LOSS)
            sink.onAudioStopped(media)
            sink.onAudioStarted(nav,AudioOutputSupport.negotiate(0x4000L,101,"default")!!,0)
            assertTrue(lines.any { it.contains("audio-mute") && it.contains("reason=stream-start") && it.contains("mute=permanent-loss") && it.contains("volumePercent=0") })
            // Sample the existing real passive worker, not media IO; no new timer/API needed.
            val diagnostics=requireNotNull(field(sink,"routeDiagnostics").get(sink))
            val executor=field(diagnostics,"executor").get(diagnostics) as ExecutorService
            executor.submit { diagnostics.javaClass.getDeclaredMethod("sample").apply { isAccessible=true }.invoke(diagnostics) }.get(3,TimeUnit.SECONDS)
            assertTrue(lines.any { it.contains("audio-volume stream=2 type=101") && it.contains("requestedPercent=0") && it.contains("mute=permanent-loss") })
            shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            assertFalse(sink.requestFocusForUserPlay())
            assertTrue(lines.any { it.contains("audio-focus-request reason=user-play result=denied") && it.contains("mute=denied") })
            shadowOf(manager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            assertTrue(sink.requestFocusForUserPlay())
            assertTrue(lines.any { it.contains("audio-focus-request reason=user-play result=granted") && it.contains("mute=unmuted") })
            fun focusReceipts()=raw.count { it.contains("audio-focus") || it.contains("audio-mute reason=focus-change") }
            val before=focusReceipts()
            change(old,AudioManager.AUDIOFOCUS_GAIN)
            assertEquals("abandoned revision cannot emit a misleading recovery receipt",before,focusReceipts())
            val current=field(sink,"focus").get(sink) as AudioFocusRequest
            change(current,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertTrue(lines.any { it.contains("audio-mute reason=focus-change") && it.contains("volumePercent=20") && it.contains("mute=duck") })
            change(current,AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertTrue(lines.any { it.contains("audio-mute reason=focus-change") && it.contains("mute=transient") })
            change(current,AudioManager.AUDIOFOCUS_GAIN)
            // Explicit PLAY without a live primary interaction deliberately renews MEDIA.
            // Alternate navigation (101/default) is not an interaction focus owner.
            val grantedBefore=lines.count { it.contains("audio-focus-request reason=user-play result=granted") }
            assertTrue(sink.requestFocusForUserPlay())
            assertEquals(grantedBefore+1,lines.count { it.contains("audio-focus-request reason=user-play result=granted") })
            val assistant=AudioStreamId(100,"speechrecognition")
            sink.onAudioStarted(assistant,AudioOutputSupport.negotiate(0x4000L,100,"speechrecognition")!!,0)
            val interactionRequest=shadowOf(manager).lastAudioFocusRequest
            val interactionFocus=field(sink,"focus").get(sink)
            assertTrue(sink.requestFocusForUserPlay())
            assertSame("PLAY during the live interaction must not request focus again",interactionRequest,shadowOf(manager).lastAudioFocusRequest)
            assertSame("the exact interaction request remains retained",interactionFocus,field(sink,"focus").get(sink))
            assertTrue(lines.any { it.contains("audio-focus-request reason=user-play result=retained") })
            val generated=raw.filter { it.contains("audio-focus-request") || it.contains("audio-mute") || it.contains("audio-volume") }
            assertTrue(generated.isNotEmpty())
            generated.forEach { receipt ->
                assertNotNull(receipt,CarPlayAudioDiagnostics.sanitize(7,receipt))
                listOf(" package=private"," address=private"," uid=10","\n","\u0000").forEach { assertNull(CarPlayAudioDiagnostics.sanitize(7,receipt+it)) }
                assertNull(CarPlayAudioDiagnostics.sanitize(7,receipt.replace(Regex("mute=[a-z-]+"),"mute=private")))
            }
            val contents=lines.joinToString("\n",postfix="\n")
            val log=java.io.File(folder,"oal_audio.log").apply { writeText(contents) }
            val plan=com.openautolink.app.diagnostics.LogUploadArchive.select(listOf(log),log.lastModified())
            com.openautolink.app.diagnostics.LogUploadArchive.create(plan,folder).use { archive ->
                java.util.zip.ZipFile(archive.file).use { zip ->
                    assertEquals(contents,zip.getInputStream(zip.getEntry(log.name)).bufferedReader().readText())
                }
            }
        } finally { sink.close(); assertTrue(sink.awaitRetired(3000)); folder.deleteRecursively() }
    }
}
