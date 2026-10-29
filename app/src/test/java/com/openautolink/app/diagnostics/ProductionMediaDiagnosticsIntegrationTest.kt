package com.openautolink.app.diagnostics

import android.app.Application
import com.openautolink.app.media.OalMediaSessionManager
import com.openautolink.app.carplay.*
import com.openautolink.carplaylab.metadata.CarPlayMediaMetadata
import io.mockk.*
import org.junit.*
import org.junit.Assert.*
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
@Config(application = Application::class, sdk = [32, 34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ProductionMediaDiagnosticsIntegrationTest {
    @Before fun installProductionDiagnostics() {
        // Match OalApplication installation even when a prior upload fixture replaced
        // the process provider and this sandbox already has the manager singleton.
        MediaDiagnosticHooks.install(RuntimeEnvironment.getApplication())
    }
    @After fun cleanup() { OalMediaSessionManager.instanceOrNull()?.release(); unmockkAll() }
    @Test fun ownSnapshotUsesActualManagerNotPresenceOnly() {
        val manager = OalMediaSessionManager.getInstance(RuntimeEnvironment.getApplication())
        manager.initialize()
        manager.updatePlaybackState(true, 12)
        val own = MediaDiagnosticHooks.own()
        assertTrue("actual initialized token must be represented", own.tokenPresent)
        assertEquals(MediaTruth.TRUE, own.phonePlaying)
        assertEquals(MediaSubmittedState.PLAYING, own.submitted)
        assertEquals(MediaTruth.TRUE, own.active)
    }
    @Test fun productionInstallationRestoresActualProviderAfterUploadFixtureOnExistingManager() {
        val app = RuntimeEnvironment.getApplication()
        val manager = OalMediaSessionManager.getInstance(app)
        manager.initialize()
        manager.updatePlaybackState(true, 12)
        // Same provider left by MediaManualUploadTest.cleanup in the full worker.
        MediaDiagnosticHooks.snapshotProvider = MediaSnapshotProvider { MediaOwnSnapshot() }
        try {
            assertSame(manager, OalMediaSessionManager.getInstance(app))
            assertEquals(true, manager.diagnosticSnapshot().phonePlaying)
            assertEquals(MediaTruth.UNKNOWN, MediaDiagnosticHooks.own().phonePlaying)
            installProductionDiagnostics()
            assertEquals(MediaTruth.TRUE, MediaDiagnosticHooks.own().phonePlaying)
            assertEquals(MediaSubmittedState.PLAYING, MediaDiagnosticHooks.own().submitted)
        } finally { MediaDiagnosticHooks.install(app) }
    }

    @Test fun actualForegroundRegistryDenialIsDurableWithoutPanelOrFileLoggerAndUploaded() {
        val app = RuntimeEnvironment.getApplication()
        MediaDiagnosticHooks.install(app)
        assertTrue(MediaDiagnosticStore.flushTerminals())
        DiagnosticLog.fileLogWriter = null; DiagnosticLog.instance = null
        val dir = File(app.getExternalFilesDir(null), "openautolink/logs").apply { mkdirs() }
        dir.listFiles().orEmpty().forEach { it.delete() }
        val boundary = object : android.content.ContextWrapper(app) {
            override fun getApplicationContext(): android.content.Context = this
            override fun startForegroundService(intent: android.content.Intent): android.content.ComponentName? {
                throw SecurityException("private-platform-message-canary")
            }
        }
        val before = MediaOutcomeSummary.process.snapshot().foregroundEvents
        val lease = CarPlaySessionLeases.forContext(boundary).acquire()
        try {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue(runCatching { lease.awaitReady() }.isFailure)
            val own = MediaOutcomeSummary.process.snapshot()
            assertEquals(before + 2, own.foregroundEvents)
            assertEquals(MediaOutcomeSummary.ForegroundOutcome.DENIED, own.lastForeground)
            assertEquals(CarPlayForegroundStage.START, own.foregroundStage)
            assertEquals(CarPlayForegroundStatus.DENIED, own.foregroundStatus)
            assertEquals(CarPlayForegroundMask.UNKNOWN, own.foregroundMask)
            assertTrue(MediaDiagnosticStore.flushTerminals())
            val terminal = dir.listFiles().orEmpty().filter { MediaDiagnosticStore.runName.matches(it.name) }
                .associate { it.name to it.readBytes() }
            assertTrue("actual registry denial retained before any upload", terminal.isNotEmpty())
            val wire = ByteArrayOutputStream()
            val http = object : HttpURLConnection(URL("https://example.invalid/upload")) {
                override fun connect() {}; override fun disconnect() {}; override fun usingProxy() = false
                override fun getOutputStream() = wire; override fun getResponseCode() = 200
            }
            assertTrue(LogUploader(app, { http }, { true }, { true }).upload("https://example.invalid/upload", "token-canary", "fixture") is UploadResult.Success)
            val members = mutableMapOf<String, ByteArray>()
            ZipInputStream(wire.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; members[e.name] = z.readBytes() } }
            terminal.forEach { (name, bytes) -> assertArrayEquals(bytes, members[name]) }
            assertTrue(members.values.any { "foreground_status=DENIED" in it.toString(Charsets.UTF_8) })
            assertFalse(members.values.any { "private-platform-message-canary" in it.toString(Charsets.UTF_8) })
        } finally { lease.release(); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
    }

    @Test fun failedAndroidSubmissionPreservesPhoneTruthSeparatelyFromSuccessfulState() {
        val manager = OalMediaSessionManager.getInstance(RuntimeEnvironment.getApplication())
        manager.initialize(); manager.updatePlaybackState(false, 0)
        val field = manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible = true }
        val session = spyk(field.get(manager) as android.support.v4.media.session.MediaSessionCompat)
        field.set(manager, session)
        every { session.setPlaybackState(any()) } throws IllegalStateException("private failure")
        try {
            val failure = runCatching { manager.updatePlaybackState(true, 10) }.exceptionOrNull()
            assertTrue("the injected Android submission, not fixture setup, must fail", failure is IllegalStateException)
            assertEquals("private failure", failure?.message)
            assertEquals(true, manager.diagnosticSnapshot().phonePlaying)
            val own = MediaDiagnosticHooks.own()
            assertEquals(MediaTruth.TRUE, own.phonePlaying)
            assertEquals(MediaSubmittedState.PAUSED, own.submitted)
        } finally { every { session.setPlaybackState(any()) } answers { callOriginal() } }
    }

    @Test fun recoveryAndPublicationMarkersAreFiniteAtAppBoundary() {
        val markers = listOf(
            "media stage=audio-focus-request reason=phone-resume result=denied revision=1 volumePercent=0 mute=denied",
            "media stage=media-resume-policy reason=phone-resume result=accepted",
            "media stage=media-resume-policy reason=phone-resume result=canceled",
            "media stage=media-resume-policy reason=phone-resume result=deferred",
            "media stage=media-resume-policy reason=phone-resume result=denied",
            "media stage=media-resume-policy reason=phone-resume result=granted",
            "media stage=playing-publication previous=none new=playing active=true mode=normal owner=current")
        markers.forEach { marker ->
            assertNotNull(marker, CarPlayAudioDiagnostics.sanitize(1, marker))
            assertNull(CarPlayAudioDiagnostics.sanitize(1, "$marker title=canary"))
        }
    }

    @Test fun productionPublicationAndDisconnectSurviveLoggingOffFailedUploadInActualZip() {
        val app = RuntimeEnvironment.getApplication()
        DiagnosticLog.fileLogWriter = null
        DiagnosticLog.instance = null
        val dir = File(app.getExternalFilesDir(null), "openautolink/logs").apply { mkdirs() }
        dir.listFiles().orEmpty().forEach { it.delete() }
        val prefs = mockk<com.openautolink.app.data.AppPreferences>()
        mockkObject(com.openautolink.app.data.AppPreferences.Companion)
        every { com.openautolink.app.data.AppPreferences.getInstance(any()) } returns prefs
        every { prefs.clusterNavigation } returns kotlinx.coroutines.flow.flowOf(false)
        every { prefs.distanceUnits } returns kotlinx.coroutines.flow.flowOf("auto")
        val manager = OalMediaSessionManager.getInstance(app)
        val lease = CarPlayHostMediaLease(CarPlayHostMediaMode.NORMAL)
        manager.beginCarPlayHostMedia(lease)
        val before = MediaOutcomeSummary.process.snapshot()
        val receiptLines = java.util.concurrent.CopyOnWriteArrayList<String>()
        val reporter = CarPlayAudioDiagnosticReporter(700, System::nanoTime, receiptLines::add)
        val caps = org.robolectric.shadows.MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(android.media.MediaFormat.createVideoFormat(android.media.MediaFormat.MIMETYPE_VIDEO_AVC, 1280, 720))
            .setIsEncoder(false).setColorFormats(intArrayOf(2130708361))
            .setProfileLevels(arrayOf(android.media.MediaCodecInfo.CodecProfileLevel().apply {
                profile = android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                level = android.media.MediaCodecInfo.CodecProfileLevel.AVCLevel52
            })).build()
        org.robolectric.shadows.ShadowMediaCodecList.addCodec(org.robolectric.shadows.MediaCodecInfoBuilder.newBuilder()
            .setName("diagnostic.avc").setIsEncoder(false).setCapabilities(caps).build())
        val receiver = com.openautolink.carplaylab.media.ProjectionReceiver(app, "02:00:00:00:00:01",
            mockk<android.view.Surface> { every { isValid } returns true },
            mockk<com.shilapi.xcertplay.mfi.MfiAuthenticator>(relaxed = true), wired = true, onStatus = { message ->
                MediaDiagnosticHooks.recordReceiverReceipt(lease, message)
                reporter.record(message)
            })
        val session = mockk<com.shilapi.xcertplay.airplay.AirPlaySession>(relaxed = true) {
            every { controlAuthenticated } returns true
            every { encryptedEventReady } returns true
        }
        fun field(name: String) = receiver.javaClass.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field("sessions").get(receiver) as MutableSet<com.shilapi.xcertplay.airplay.AirPlaySession>).add(session)
        field("active").set(receiver, session); field("runtimeOwner").set(receiver, session); field("runtimeReady").set(receiver, true)
        val commands = mutableListOf<Int>()
        val host = OalCarPlayMetadataHost(app, receipt = { marker -> assertTrue(receiver.audioDiagnostic(session, marker)) },
            attemptContext = CarPlayAttemptContext(lease)) { index -> commands.add(index); true }
        try {
            assertTrue(host.start())
            val audio = app.getSystemService(android.media.AudioManager::class.java)
            // A denied predecessor owns no grant. Robolectric shares this response with abandon.
            org.robolectric.Shadows.shadowOf(audio).setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            val sink = field("sink").get(receiver) as com.shilapi.xcertplay.media.AndroidMediaSink
            val stream = com.shilapi.xcertplay.airplay.AudioStreamId(100, "media")
            sink.onAudioStarted(stream, requireNotNull(com.shilapi.xcertplay.media.AudioOutputSupport.negotiate(0x4000L, 100, "media")), 0)
            org.robolectric.Shadows.shadowOf(audio).setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            val stamp = receiver.captureMediaRecoveryStamp(session, 1) { true }!!
            assertTrue(receiver.offerPhonePlaybackObservation(session, false, stamp))
            assertTrue(receiver.offerPhonePlaybackObservation(session, true, stamp))
            (field("input").get(receiver) as java.util.concurrent.ExecutorService).submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            (field("statuses").get(receiver) as java.util.concurrent.ExecutorService).submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            val recovery = receiptLines.first { "audio-focus-request reason=phone-resume result=denied" in it }
                .substringAfter("CARPLAY_AUDIO session=700 ")
            for(result in listOf("accepted","denied")) {
                val policy=receiptLines.first { "stage=media-resume-policy reason=phone-resume result=$result" in it }
                    .substringAfter("CARPLAY_AUDIO session=700 ")
                assertTrue(receiver.audioDiagnostic(session,policy))
                assertFalse(receiver.audioDiagnostic(session,"$policy title=canary"))
            }
            assertTrue("actual recovery receipt passes receiver's finite admission", receiver.audioDiagnostic(session, recovery))
            assertFalse(receiver.audioDiagnostic(session, "$recovery title=canary"))
            host.publish(CarPlayMediaMetadata(playing = true, title = "private-title-canary", artist = "private-artist-canary"), null, null)
            (field("statuses").get(receiver) as java.util.concurrent.ExecutorService).submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("actual producer must pass receiver allowlist and app sanitizer", receiptLines.any { "stage=playing-publication" in it })
            // Robolectric's MediaSession shadow supplies no Binder transport dispatch.
            // Enter the exact callback installed by production, not an outcome/helper fake.
            val compatSession = manager.javaClass.getDeclaredField("mediaSession").apply { isAccessible = true }
                .get(manager) as android.support.v4.media.session.MediaSessionCompat
            val implementation = compatSession.javaClass.getDeclaredField("mImpl").apply { isAccessible = true }.get(compatSession)
            val callback = implementation.javaClass.methods.first { it.name == "getCallback" }.apply { isAccessible = true }
                .invoke(implementation) as android.support.v4.media.session.MediaSessionCompat.Callback
            callback.onPause()
            assertEquals("real installed control callback must reach current host", listOf(2), commands)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            (field("statuses").get(receiver) as java.util.concurrent.ExecutorService).submit {}.get(3, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(MediaTruth.TRUE, MediaDiagnosticHooks.own().phonePlaying)
            host.close()
            val wire = ByteArrayOutputStream(); var code = 503
            val http = object : HttpURLConnection(URL("https://example.invalid/upload")) {
                override fun connect() {}; override fun disconnect() {}; override fun usingProxy() = false
                override fun getOutputStream() = wire; override fun getResponseCode() = code
            }
            val uploader = LogUploader(app, { http }, { true }, { true })
            assertTrue(uploader.upload("https://example.invalid/upload", "token-canary", "fixture") is UploadResult.Failure)
            val retained = dir.listFiles().orEmpty().filter { MediaDiagnosticStore.runName.matches(it.name) }.associate { it.name to it.readBytes() }
            assertTrue(retained.isNotEmpty())
            code = 200; wire.reset()
            assertTrue(uploader.upload("https://example.invalid/upload", "token-canary", "fixture") is UploadResult.Success)
            val members = mutableMapOf<String, ByteArray>()
            ZipInputStream(wire.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; members[e.name] = z.readBytes() } }
            retained.forEach { (name, bytes) -> assertArrayEquals(bytes, members[name]) }
            val summaries = members.filterKeys { MediaDiagnosticStore.runName.matches(it) }.values.map { it.toString(Charsets.UTF_8) }
            assertTrue("terminal snapshot must preserve pre-reset phone truth", summaries.any { "phone_playing=TRUE" in it && "submitted=PLAYING" in it })
            assertTrue(MediaOutcomeSummary.process.snapshot().playbackSubmitted > before.playbackSubmitted)
            assertTrue("actual control receipt must survive store and transmitted ZIP", summaries.any { text ->
                text.lineSequence().any { it.substringBefore('=') == "control_received" &&
                    (it.substringAfter('=').toIntOrNull() ?: 0) > before.controlReceived }
            })
            assertTrue(MediaOutcomeSummary.process.snapshot().controlReceived > before.controlReceived)
            assertTrue(MediaOutcomeSummary.process.snapshot().metadataSubmitted > before.metadataSubmitted)
            assertTrue(MediaOutcomeSummary.process.snapshot().losses > before.losses)
            assertTrue(MediaOutcomeSummary.process.snapshot().recoveries > before.recoveries)
            assertTrue(summaries.any { "last_recovery=RECOVERY_DENIED" in it })
            val durablePolicy=members.filterKeys { it.startsWith("oal_host_media") }.values.joinToString("\n") { it.toString(Charsets.UTF_8) }
            assertTrue("accepted phone intent must survive logging-off actual manual ZIP", "reason=phone-resume result=accepted" in durablePolicy)
            assertTrue("denial must survive logging-off actual manual ZIP", "reason=phone-resume result=denied" in durablePolicy)
            assertFalse(members.values.any { bytes -> listOf("private-title-canary", "private-artist-canary", "token-canary").any { it in bytes.toString(Charsets.UTF_8) } })
        } finally {
            host.close()
            org.robolectric.Shadows.shadowOf(app.getSystemService(android.media.AudioManager::class.java))
                .setNextFocusRequestResponse(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            receiver.close(); assertTrue(receiver.awaitClosed(3000))
            manager.finishCarPlayHostMedia(lease)
            manager.release()
            org.robolectric.shadows.ShadowMediaCodecList.reset()
        }
    }
}
